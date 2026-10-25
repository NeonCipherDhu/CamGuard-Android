package com.camguard.android.protocol

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class VideoCodec(val mimeType: String, val label: String) {
    UNKNOWN("", "Unknown"),
    H264("video/avc", "H.264"),
    H265("video/hevc", "H.265")
}

data class RecordingSegment(
    val segId: Int,
    val rawType: Int,
    val startTs: Long,
    val endTs: Long
) {
    val durationSec: Int
        get() = max(0L, endTs - startTs).toInt()

    val isAnomaly: Boolean
        get() = rawType == 2 || rawType == 3

    val kindCode: String
        get() = when (rawType) {
            3 -> "alarm"
            2 -> "motion"
            else -> "continuous"
        }

    val startTime12h: String
        get() = formatUtc12h(startTs)

    val endTime12h: String
        get() = formatUtc12h(endTs)

    val durationStr: String
        get() = formatDuration(durationSec)

    val displayTimeRange12h: String
        get() = "$startTime12h – $endTime12h  ($durationStr)"

    val dateKeyUtc: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(startTs * 1000L))
        }

    companion object {
        fun formatUtc12h(epochSec: Long): String {
            val sdf = SimpleDateFormat("hh:mm:ss a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(epochSec * 1000L))
        }

        fun formatDuration(sec: Int): String {
            val s = max(0, sec)
            val hrs = s / 3600
            val mins = (s % 3600) / 60
            val remSec = s % 60
            return when {
                hrs > 0 -> String.format(Locale.US, "%dh %02dm %02ds", hrs, mins, remSec)
                mins > 0 -> String.format(Locale.US, "%dm %02ds", mins, remSec)
                else -> String.format(Locale.US, "%ds", remSec)
            }
        }
    }
}

object SdSessionGate {
    private val mapGuard = Any()
    private val locks = HashMap<String, ReentrantLock>()
    private val lastClosedMs = HashMap<String, Long>()
    private const val COOLDOWN_MS = 165L

    private fun lockForIp(ip: String): ReentrantLock {
        val key = ip.trim().ifEmpty { "default" }
        synchronized(mapGuard) {
            return locks.getOrPut(key) { ReentrantLock() }
        }
    }

    private fun waitCooldown(ip: String, stopFlag: AtomicBoolean?) {
        val key = ip.trim().ifEmpty { "default" }
        val last = synchronized(mapGuard) { lastClosedMs[key] ?: 0L }
        val waitUntil = last + COOLDOWN_MS
        while (true) {
            if (stopFlag?.get() == true) break
            val rem = waitUntil - System.currentTimeMillis()
            if (rem <= 4L) break
            try {
                Thread.sleep(min(rem, 25L))
            } catch (_: Exception) {
                break
            }
        }
    }

    fun markClosed(ip: String) {
        val key = ip.trim().ifEmpty { "default" }
        synchronized(mapGuard) {
            lastClosedMs[key] = System.currentTimeMillis()
        }
    }

    fun <T> withCameraSdLock(ip: String, stopFlag: AtomicBoolean? = null, block: () -> T): T {
        val lock = lockForIp(ip)
        lock.lock()
        try {
            waitCooldown(ip, stopFlag)
            return block()
        } finally {
            markClosed(ip)
            lock.unlock()
        }
    }
}

data class DiscoveredCamera(
    val devId: String,
    val ip: String,
    val mac: String = "",
    val port: Int = 8800,
    val name: String = "",
    val discoveryMethod: String = "UDP Broadcast"
) {
    fun displayTitle(): String {
        val label = name.ifBlank {
            if (devId.isNotBlank()) "Camera ($devId)" else "Camera @ $ip"
        }
        return "$label [$ip:$port]"
    }
}

class CameraFrame(
    val isAudio: Boolean,
    val rawType: Int,
    val payload: ByteArray,
    val codec: VideoCodec = VideoCodec.UNKNOWN,
    val isKeyframe: Boolean = false,
    val frameId: Long = 0L,
    val frameType: Int = 0,
    val frameRate: Int = 20,
    val timestamp: Long = 0L,
    val audioCodec: String = "adpcm" // "adpcm" | "alaw" | "aac"
)

object CameraCommands {
    val PTZ_RIGHT = byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xEA.toByte(), 0x03, 0xE8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    val PTZ_LEFT  = byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE9.toByte(), 0x03, 0xE8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    val PTZ_UP    = byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xEB.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    val PTZ_DOWN  = byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xEC.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)
    val PTZ_STOP  = byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0xE8.toByte(), 0x03, 0x00, 0x00, 0x01, 0x00)

    val LIGHT_ON   = byteArrayOf(0xC4.toByte(), 0x00, 0x00, 0x00, 0xE9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val LIGHT_OFF  = byteArrayOf(0xC4.toByte(), 0x00, 0x00, 0x00, 0xEA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val LIGHT_AUTO = byteArrayOf(0xC4.toByte(), 0x00, 0x00, 0x00, 0xEB.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    val IMAGE_COLOR = byteArrayOf(0xC5.toByte(), 0x00, 0x00, 0x00, 0xE9.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val IMAGE_BW    = byteArrayOf(0xC5.toByte(), 0x00, 0x00, 0x00, 0xEA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val IMAGE_AUTO  = byteArrayOf(0xC5.toByte(), 0x00, 0x00, 0x00, 0xEB.toByte(), 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val IMAGE_FLIP  = byteArrayOf(0xBE.toByte(), 0x00, 0x00, 0x00, 0xE8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    const val PTZX_ACTION_ADD   = 100
    const val PTZX_ACTION_DEL   = 101
    const val PTZX_ACTION_RESET = 102
    const val PTZX_ACTION_GOTO  = 103

    fun ptzxPacket(action: Int, slotId: Int): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xAB)
        buf.putInt(action)
        buf.putInt(slotId)
        buf.putInt(0)
        return buf.array()
    }
}

object CameraAudioDecoder {
    private val IMA_STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private val IMA_INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val ALAW_TO_PCM16 = ShortArray(256).apply {
        for (a in 0..255) {
            val aVal = a xor 0x55
            var t = (aVal and 0x0F) shl 4
            val seg = (aVal and 0x70) shr 4
            when (seg) {
                0 -> t += 8
                1 -> t += 0x108
                else -> {
                    t += 0x108
                    t = t shl (seg - 1)
                }
            }
            this[a] = (if ((aVal and 0x80) != 0) t else -t).toShort()
        }
    }

    fun decodeAlawToPcm16(alawBytes: ByteArray): ShortArray {
        if (alawBytes.isEmpty()) return ShortArray(0)
        val out = ShortArray(alawBytes.size)
        for (i in alawBytes.indices) {
            out[i] = ALAW_TO_PCM16[alawBytes[i].toInt() and 0xFF]
        }
        return out
    }

    fun decodeAdpcmToPcm16(adpcmBytes: ByteArray): ShortArray {
        if (adpcmBytes.size < 4) return ShortArray(0)
        var predictor = ((adpcmBytes[0].toInt() and 0xFF) or (adpcmBytes[1].toInt() shl 8)).toShort().toInt()
        var stepIndex = (adpcmBytes[2].toInt() and 0xFF).coerceIn(0, 88)
        var step = IMA_STEP_TABLE[stepIndex]

        val sampleCount = 1 + (adpcmBytes.size - 4) * 2
        val out = ShortArray(sampleCount)
        var outIdx = 0
        out[outIdx++] = predictor.toShort()

        for (i in 4 until adpcmBytes.size) {
            val byteVal = adpcmBytes[i].toInt() and 0xFF
            val nibbles = intArrayOf(byteVal and 0x0F, (byteVal shr 4) and 0x0F)
            for (nibble in nibbles) {
                stepIndex = (stepIndex + IMA_INDEX_TABLE[nibble]).coerceIn(0, 88)
                val sign = nibble and 8
                val delta = nibble and 7
                var diff = step shr 3
                if ((delta and 4) != 0) diff += step
                if ((delta and 2) != 0) diff += step shr 1
                if ((delta and 1) != 0) diff += step shr 2

                if (sign != 0) {
                    predictor -= diff
                } else {
                    predictor += diff
                }
                predictor = predictor.coerceIn(-32768, 32767)
                step = IMA_STEP_TABLE[stepIndex]
                out[outIdx++] = predictor.toShort()
            }
        }
        return out
    }
}

object LanDiscovery {
    private val SEARCH_CMD = "NVDEVSEARCH^100".toByteArray(Charsets.US_ASCII)

    fun getLocalIpv4Addresses(): List<String> {
        val ips = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ips.sorted()
    }

    private fun getTargetSubnets(localIps: List<String>): List<String> {
        val prefixes = linkedSetOf<String>()
        for (lip in localIps) {
            val p = lip.split(".")
            if (p.size == 4) {
                val prefix = "${p[0]}.${p[1]}.${p[2]}"
                // Exclude Android Emulator virtual SLIRP NAT subnet (10.0.2.x)
                if (prefix != "10.0.2") {
                    prefixes.add(prefix)
                }
            }
        }
        prefixes.add("192.168.1")
        prefixes.add("192.168.0")
        return prefixes.toList()
    }

    fun discover(
        context: Context? = null,
        timeoutSec: Double = 2.0,
        scanTcp8800: Boolean = true,
        progressCb: ((String) -> Unit)? = null
    ): List<DiscoveredCamera> {
        val devicesByIp = linkedMapOf<String, DiscoveredCamera>()

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("camguard_discovery_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {
        }

        try {
            val localIps = getLocalIpv4Addresses()
            val localSet = localIps.toSet()
            val targetSubnets = getTargetSubnets(localIps)

            progressCb?.invoke("Searching Wi-Fi for cameras...")

            val broadcastTargets = linkedSetOf("255.255.255.255", "192.168.1.255", "192.168.0.255")
            for (prefix in targetSubnets) {
                broadcastTargets.add("$prefix.255")
            }

            val socks = mutableListOf<DatagramSocket>()
            try {
                // V380 cameras always send NVDEVRESULT replies to destination UDP port 10009
                val sMain = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        receiveBufferSize = 262144
                        soTimeout = 120
                        bind(InetSocketAddress("0.0.0.0", 10009))
                    }
                } catch (_: Exception) {
                    DatagramSocket().apply {
                        broadcast = true
                        receiveBufferSize = 262144
                        soTimeout = 120
                    }
                }
                socks.add(sMain)

                for (lip in localIps) {
                    if (lip.startsWith("10.0.2.")) continue
                    try {
                        val sIf = DatagramSocket(InetSocketAddress(lip, 0)).apply {
                            broadcast = true
                            soTimeout = 80
                        }
                        socks.add(sIf)
                    } catch (_: Exception) {
                    }
                }

                val recvBuf = ByteArray(1024)
                fun drainReplies(waitMs: Long) {
                    val deadline = System.currentTimeMillis() + waitMs
                    while (System.currentTimeMillis() < deadline) {
                        var anyData = false
                        for (sock in socks) {
                            try {
                                val dp = DatagramPacket(recvBuf, recvBuf.size)
                                sock.receive(dp)
                                anyData = true
                                val raw = dp.data.copyOfRange(0, dp.length)
                                val srcIp = dp.address?.hostAddress ?: ""
                                val cam = parseNvDevResult(raw, srcIp)
                                if (cam != null && cam.ip.isNotBlank()) {
                                    devicesByIp[cam.ip] = cam
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (!anyData) break
                    }
                }

                // 1) Broadcast + Unicast UDP 10008 sweep across all target subnets.
                // Unicast UDP 10008 wakes up unadded cameras in standby AND works across
                // subnets / routers that block broadcast UDP (255.255.255.255).
                for (round in 0 until 2) {
                    for (sock in socks) {
                        for (bcast in broadcastTargets) {
                            try {
                                val pkt = DatagramPacket(
                                    SEARCH_CMD,
                                    SEARCH_CMD.size,
                                    InetAddress.getByName(bcast),
                                    10008
                                )
                                sock.send(pkt)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    drainReplies(200L)

                    for (prefix in targetSubnets) {
                        for (host in 1..254) {
                            val targetIp = "$prefix.$host"
                            if (targetIp in localSet) continue
                            try {
                                val pkt = DatagramPacket(
                                    SEARCH_CMD,
                                    SEARCH_CMD.size,
                                    InetAddress.getByName(targetIp),
                                    10008
                                )
                                sMain.send(pkt)
                            } catch (_: Exception) {
                            }
                            if (host % 12 == 0) {
                                try { Thread.sleep(2) } catch (_: Exception) {}
                            }
                            if (host % 48 == 0) {
                                drainReplies(50L)
                            }
                        }
                        drainReplies(200L)
                    }
                }
                drainReplies(350L)
            } finally {
                socks.forEach { try { it.close() } catch (_: Exception) {} }
            }

            if (scanTcp8800) {
                progressCb?.invoke("Scanning Wi-Fi for cameras...")
                val knownWithDevId = devicesByIp.filterValues { it.devId.isNotBlank() }.keys
                val tcpHits = scanSubnetPort8800(targetSubnets, localSet, knownWithDevId)
                for (ip in tcpHits) {
                    val existing = devicesByIp[ip]
                    if (existing != null && existing.devId.isNotBlank()) continue

                    val unicastCam = queryUnicastUdp10008(ip)
                    if (unicastCam != null) {
                        devicesByIp[unicastCam.ip.ifBlank { ip }] = unicastCam
                    } else if (existing == null) {
                        devicesByIp[ip] = DiscoveredCamera(
                            devId = "",
                            ip = ip,
                            mac = "",
                            port = 8800,
                            name = "Camera ($ip)",
                            discoveryMethod = "TCP Port 8800 Scan"
                        )
                    }
                }
            }
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {
            }
        }

        return devicesByIp.values.sortedBy { it.ip }
    }

    fun queryUnicastUdp10008(targetIp: String): DiscoveredCamera? {
        // Bind to UDP port 10009 (with SO_REUSEADDR) because V380 firmware sends NVDEVRESULT to port 10009
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 400
                bind(InetSocketAddress("0.0.0.0", 10009))
            }
        } catch (_: Exception) {
            try {
                DatagramSocket().apply { soTimeout = 400 }
            } catch (_: Exception) {
                return null
            }
        }
        return try {
            sock.use { s ->
                val pkt = DatagramPacket(
                    SEARCH_CMD,
                    SEARCH_CMD.size,
                    InetAddress.getByName(targetIp),
                    10008
                )
                repeat(2) { s.send(pkt) }
                val deadline = System.currentTimeMillis() + 450L
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val dp = DatagramPacket(buf, buf.size)
                    s.receive(dp)
                    val srcIp = dp.address?.hostAddress ?: ""
                    val raw = dp.data.copyOfRange(0, dp.length)
                    val cam = parseNvDevResult(raw, srcIp)
                    if (cam != null && (cam.ip == targetIp || srcIp == targetIp)) {
                        return cam
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseNvDevResult(raw: ByteArray, sourceIp: String): DiscoveredCamera? {
        val text = String(raw, Charsets.US_ASCII).trim('\u0000', '\r', '\n', ' ')
        val parts = text.split("^")
        if (parts.size < 13 || parts[0] != "NVDEVRESULT") return null

        val mac = parts[2].trim().lowercase()
        val ip = parts[3].trim().ifBlank { sourceIp }
        val devId = parts[12].trim()
        return DiscoveredCamera(
            devId = devId,
            ip = ip,
            mac = mac,
            port = 8800,
            name = if (devId.isNotBlank()) "Camera ($devId)" else "Camera ($ip)",
            discoveryMethod = "UDP Discovery"
        )
    }

    private fun scanSubnetPort8800(
        targetSubnets: List<String>,
        localSet: Set<String>,
        excludeIps: Set<String>
    ): List<String> {
        val candidates = mutableListOf<String>()
        for (prefix in targetSubnets) {
            for (host in 1..254) {
                val hip = "$prefix.$host"
                if (hip !in localSet && hip !in excludeIps) {
                    candidates.add(hip)
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Use 40 threads and 1250ms connect timeout (so Android's 1000ms TCP SYN retry fires
        // if a single Wi-Fi packet or ARP request is delayed), with 2 attempts per IP.
        val pool = Executors.newFixedThreadPool(40)
        val found = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        try {
            val tasks = candidates.map { targetIp ->
                Callable<String?> {
                    for (attempt in 0 until 2) {
                        if (targetIp in found) return@Callable targetIp
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(targetIp, 8800), 1250)
                                s.soTimeout = 700
                                // Send a 520-byte V380 handshake frame so the camera's blocking recv()
                                // loop finishes immediately and never locks port 8800.
                                val probePkt = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN)
                                probePkt.putInt(0, 1167)
                                probePkt.putInt(4, 120)
                                probePkt.put(8, 31.toByte())
                                probePkt.putInt(9, 1)
                                s.getOutputStream().write(probePkt.array())
                                s.getOutputStream().flush()
                                try {
                                    val resp = ByteArray(256)
                                    s.getInputStream().read(resp)
                                } catch (_: Exception) {
                                }
                                found.add(targetIp)
                                return@Callable targetIp
                            }
                        } catch (_: Exception) {
                            // Brief backoff before 2nd attempt if SYN timed out
                            if (attempt == 0) {
                                try { Thread.sleep(40) } catch (_: Exception) {}
                            }
                        }
                    }
                    null
                }
            }
            pool.invokeAll(tasks)
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }
        return found.sorted()
    }
}

class CameraClient(
    ip: String,
    port: Int = 8800,
    deviceId: String = "",
    username: String = "admin",
    val password: String = "",
    streamQuality: Int = 1, // 1 = HD, 0 = SD
    val enableAudio: Boolean = true,
    private val onFrame: ((CameraFrame) -> Unit)? = null,
    private val onStatus: ((String) -> Unit)? = null
) {
    companion object {
        private val STATIC_KEY = "macrovideo+*#!^@".toByteArray(Charsets.US_ASCII)
        private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun generatePassword(password: String, blockBytes: Int = 48): ByteArray {
            val rkStr = buildString(16) {
                repeat(16) { append(ALPHANUM[Random.nextInt(ALPHANUM.length)]) }
            }
            val rk = rkStr.toByteArray(Charsets.US_ASCII)

            val pwBytes = password.toByteArray(Charsets.UTF_8)
            val padBuf = ByteArray(blockBytes)
            System.arraycopy(pwBytes, 0, padBuf, 0, min(pwBytes.size, blockBytes))

            val cipher1 = Cipher.getInstance("AES/ECB/NoPadding")
            cipher1.init(Cipher.ENCRYPT_MODE, SecretKeySpec(STATIC_KEY, "AES"))
            val enc1 = cipher1.doFinal(padBuf)

            val cipher2 = Cipher.getInstance("AES/ECB/NoPadding")
            cipher2.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rk, "AES"))
            val enc2 = cipher2.doFinal(enc1)

            return rk + enc2
        }
    }

    var ip: String = ip.trim()
    var port: Int = port
    var deviceId: Long = deviceId.trim().toLongOrNull() ?: 0L
    var username: String = username.trim().ifBlank { "admin" }
    @Volatile var streamQuality: Int = if (streamQuality != 0) 1 else 0

    var authTicket: Long = 0L
    var sessionId: Long = 0L
    var deviceVersion: Int = 0
    var communicationVersion: Int = 0
    var frameWidth: Int = 1280
    var frameHeight: Int = 720
    private var aesKey: ByteArray = ByteArray(16)
    private var pbkdf2AesKey: ByteArray = ByteArray(16)

    @Volatile private var streamSock: Socket? = null
    private val sendLock = Any()
    private val stopEvent = AtomicBoolean(false)
    @Volatile private var needReconnect = false
    @Volatile private var qualitySwitchPending = false

    fun setQuality(quality: Int) {
        val newQ = if (quality != 0) 1 else 0
        this.streamQuality = newQ
        this.qualitySwitchPending = true
        this.needReconnect = true
        closeStreamSock()
    }

    private fun emitStatus(msg: String) {
        try {
            onStatus?.invoke(msg)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        stopEvent.set(true)
        closeStreamSock()
    }

    private fun closeStreamSock() {
        val sock = streamSock
        streamSock = null
        if (sock != null) {
            try { sock.shutdownInput() } catch (_: Exception) {}
            try { sock.shutdownOutput() } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
            SdSessionGate.markClosed(ip)
        }
    }

    private fun generateMediaKey(ticket: Long) {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt((ticket and 0xFFFFFFFFL).toInt())
        buf.putLong(0x618123462C14795CL)
        buf.putInt(0x82800DF0.toInt())
        aesKey = buf.array()

        // Also derive PBKDF2-HMAC-SHA256 key candidate for firmwares using PBKDF2 media_key
        try {
            val saltBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            saltBuf.put("salt".toByteArray(Charsets.US_ASCII))
            saltBuf.putInt((ticket and 0xFFFFFFFFL).toInt())
            val spec = PBEKeySpec("macrovideo+*#!^@".toCharArray(), saltBuf.array(), 1000, 128)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            pbkdf2AesKey = skf.generateSecret(spec).encoded
        } catch (_: Exception) {
            pbkdf2AesKey = aesKey.copyOf()
        }
    }

    private fun resolveDeviceIdIfMissing(): Boolean {
        if (deviceId > 0L) return true
        emitStatus("Discovering Device ID...")
        val directCam = LanDiscovery.queryUnicastUdp10008(ip)
        val directId = directCam?.devId?.toLongOrNull()
        if (directId != null && directId > 0L) {
            deviceId = directId
            emitStatus("Found Device ID: $deviceId")
            return true
        }
        val devices = LanDiscovery.discover(timeoutSec = 1.5, scanTcp8800 = false)
        for (dev in devices) {
            val idVal = dev.devId.toLongOrNull()
            if (dev.ip == ip && idVal != null && idVal > 0L) {
                deviceId = idVal
                emitStatus("Found Device ID: $deviceId")
                return true
            }
        }
        return false
    }

    private fun wakeCameraPort8800() {
        // 1. Send unicast UDP discovery wakeup to camera IP on port 10008
        try {
            DatagramSocket().use { usock ->
                val cmd = "NVDEVSEARCH^100".toByteArray(Charsets.US_ASCII)
                val pkt = DatagramPacket(cmd, cmd.size, InetAddress.getByName(ip), 10008)
                usock.send(pkt)
            }
        } catch (_: Exception) {
        }

        if (deviceId <= 0L) return

        // 2. Trigger V380 Cloud Dispatch wakeup so v31/v32 cameras open local TCP port 8800
        val relayIps = mutableListOf<String>()
        try {
            val ts = System.currentTimeMillis() / 1000L
            val base = "dev_id=$deviceId&platform=10001&timestamp=${ts}hsdata2022"
            val sha1 = MessageDigest.getInstance("SHA-1").digest(base.toByteArray(Charsets.UTF_8))
            val sign = sha1.joinToString("") { "%02x".format(it) }

            val body = JSONObject().apply {
                put("dev_id", deviceId)
                put("platform", 10001)
                put("timestamp", ts)
                put("sign", sign)
            }.toString().toByteArray(Charsets.UTF_8)

            val conn = (java.net.URL("http://dispa1.av380.net:8001/api/v1/get_stream_server")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1800
                readTimeout = 1800
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body) }
            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(respText)
            if (json.optInt("code") == 2000) {
                val arr = json.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val rip = arr.optJSONObject(i)?.optString("ip")
                        if (!rip.isNullOrBlank() && rip !in relayIps) {
                            relayIps.add(rip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        for (fallbackIp in listOf("103.138.75.221", "129.226.139.226", "101.33.35.22")) {
            if (fallbackIp !in relayIps) relayIps.add(fallbackIp)
        }

        val userStr = if (username != "admin") username else (deviceId.toString().ifBlank { "admin" })
        val userB = userStr.toByteArray(Charsets.US_ASCII).let { it.copyOf(min(it.size, 32)) }
        val hostB = "$deviceId.nvdvr.net".toByteArray(Charsets.US_ASCII).let { it.copyOf(min(it.size, 50)) }
        val encPw = generatePassword(password, blockBytes = 48)

        val cmd = ByteBuffer.allocate(520).order(ByteOrder.LITTLE_ENDIAN)
        cmd.putInt(0, 1167)
        cmd.putInt(4, 1022)
        cmd.put(8, 31.toByte())
        cmd.putInt(9, 1)
        cmd.putInt(13, (deviceId and 0xFFFFFFFFL).toInt())
        val cmdArr = cmd.array()
        System.arraycopy(hostB, 0, cmdArr, 17, hostB.size)
        cmd.putInt(67, 8800)
        System.arraycopy(userB, 0, cmdArr, 71, userB.size)
        System.arraycopy(encPw, 0, cmdArr, 103, encPw.size)

        for (rip in relayIps.take(2)) {
            try {
                Socket().use { rsock ->
                    rsock.connect(InetSocketAddress(rip, 8800), 1800)
                    rsock.soTimeout = 1500
                    rsock.getOutputStream().write(cmdArr)
                    rsock.getOutputStream().flush()
                    recvResponse(rsock, 256, 1500)
                }
                break
            } catch (_: Exception) {
            }
        }

        try { Thread.sleep(550) } catch (_: Exception) {}
    }

    fun authenticate(skipCloudWake: Boolean = false): Pair<Int, String> {
        resolveDeviceIdIfMissing()
        if (!skipCloudWake) {
            wakeCameraPort8800()
        }

        val portsToTry = if (port == 8800) listOf(8800) else listOf(port, 8800)
        val usernamesToTry = linkedSetOf<String>()
        if (username.isNotBlank()) usernamesToTry.add(username)
        if (deviceId > 0L) usernamesToTry.add(deviceId.toString())
        usernamesToTry.add("admin")

        data class AuthVariant(val pktSize: Int, val unk1: Int, val unk2Ver: Int, val pwBlockLen: Int)
        val variants = listOf(
            AuthVariant(520, 120, 31, 48), // Modern V380 Pro LAN (v31/v32)
            AuthVariant(256, 1022, 2, 16)  // Classic V380 LAN (v2)
        )

        var lastErr = "Connection timed out"
        for (candidatePort in portsToTry) {
            var portReachable = false
            for (candidateUser in usernamesToTry) {
                for (variant in variants) {
                    if (stopEvent.get()) return -1 to "Stopped"
                    val sock = Socket()
                    try {
                        sock.connect(InetSocketAddress(ip, candidatePort), 7000)
                        sock.soTimeout = 7000
                        portReachable = true

                        val encPw = generatePassword(password, blockBytes = variant.pwBlockLen)
                        val userBytes = candidateUser.toByteArray(Charsets.US_ASCII)
                        val userLen = min(userBytes.size, 32)

                        val buf = ByteBuffer.allocate(variant.pktSize).order(ByteOrder.LITTLE_ENDIAN)
                        buf.putInt(0, 1167)
                        buf.putInt(4, variant.unk1)
                        buf.put(8, variant.unk2Ver.toByte())
                        buf.putInt(9, 1)
                        buf.putInt(13, (deviceId and 0xFFFFFFFFL).toInt())
                        val arr = buf.array()
                        System.arraycopy(userBytes, 0, arr, 49, userLen)
                        System.arraycopy(encPw, 0, arr, 81, encPw.size)

                        sock.getOutputStream().write(arr)
                        sock.getOutputStream().flush()

                        val resp = recvResponse(sock, 256, 7000)
                        if (resp.size < 32) {
                            lastErr = "Empty auth response from camera"
                            continue
                        }

                        val rBuf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                        val respCmd = rBuf.getInt(0)
                        if (respCmd != 1168) {
                            lastErr = "Unexpected auth response: $respCmd"
                            continue
                        }

                        val loginResult = rBuf.getInt(4)
                        if (loginResult == 1001) {
                            this.port = candidatePort
                            this.username = candidateUser
                            this.deviceVersion = resp[12].toInt() and 0xFF
                            this.authTicket = rBuf.getInt(13).toLong() and 0xFFFFFFFFL
                            this.sessionId = rBuf.getInt(17).toLong() and 0xFFFFFFFFL
                            return 1 to "Authenticated (v$deviceVersion)"
                        }
                        if (loginResult == 1011) {
                            lastErr = "Invalid camera username (1011)"
                            break
                        }
                        if (loginResult == 1012) {
                            return -1 to "Invalid camera password (1012)"
                        }
                        if (loginResult == 1018) {
                            return -1 to "Invalid Device ID ($deviceId) — check sticker (1018)"
                        }
                        return -1 to "Camera rejected login (code $loginResult)"
                    } catch (exc: Exception) {
                        lastErr = "Cannot connect to $ip:$candidatePort"
                        break
                    } finally {
                        try { sock.close() } catch (_: Exception) {}
                    }
                }
                if (!portReachable) break
            }
        }

        if ("1011" in lastErr) return -1 to lastErr
        return 0 to lastErr
    }

    fun streamLoginAndStart(): Pair<Boolean, String> {
        closeStreamSock()
        return try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 262144
            sock.connect(InetSocketAddress(ip, port), 6000)
            sock.soTimeout = 10000
            streamSock = sock

            val cmd301 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            cmd301.putInt(0, 301)
            cmd301.putInt(4, (deviceId and 0xFFFFFFFFL).toInt())
            cmd301.putInt(8, 0)
            cmd301.putShort(12, 20.toShort())
            cmd301.putInt(14, (authTicket and 0xFFFFFFFFL).toInt())
            cmd301.putInt(22, if (enableAudio) 4097 else 4096)
            cmd301.putInt(26, streamQuality)

            if (!sendRaw(cmd301.array())) {
                return false to "Failed sending stream login (cmd 301)"
            }

            val resp401 = recvResponse(sock, 412, 10000)
            if (resp401.size < 16) {
                return false to "Stream login response too short"
            }

            val rBuf = ByteBuffer.wrap(resp401).order(ByteOrder.LITTLE_ENDIAN)
            val respCmd = rBuf.getInt(0)
            if (respCmd != 401) {
                return false to "Expected stream login 401, got $respCmd"
            }

            val result = rBuf.getInt(4)
            if (result == -11 || result == -12) {
                return false to "Camera stream rejected (code $result)"
            }

            val version = rBuf.getShort(8).toInt() and 0xFFFF
            val width = rBuf.getInt(10)
            val height = rBuf.getInt(14)

            communicationVersion = version
            if (width in 160..4096) frameWidth = width
            if (height in 120..4096) frameHeight = height

            if (deviceVersion > 30) {
                generateMediaKey(authTicket)
            }

            val cmd303 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            cmd303.putInt(0, 303)
            if (deviceVersion > 30) {
                cmd303.putShort(4, 0x3001.toShort())
            } else {
                cmd303.putInt(4, if (result == 402 || result == 1001) result else 0x3001)
            }

            if (!sendRaw(cmd303.array())) {
                return false to "Failed sending stream start (cmd 303)"
            }

            val qLabel = if (streamQuality != 0) "HD" else "SD"
            true to "Streaming (${frameWidth}x${frameHeight}, $qLabel)"
        } catch (exc: Exception) {
            closeStreamSock()
            false to "Stream handshake failed: ${exc.message ?: "error"}"
        }
    }

    fun runForever() {
        stopEvent.set(false)
        var fastReconnect = false
        while (!stopEvent.get()) {
            if (fastReconnect) {
                emitStatus("Switching to ${if (streamQuality != 0) "HD" else "SD"} stream...")
            } else {
                emitStatus("Connecting to camera stream...")
            }
            var (authStatus, authMsg) = authenticate(skipCloudWake = fastReconnect)
            if (authStatus == 0 && fastReconnect && !stopEvent.get()) {
                val retry = authenticate(skipCloudWake = false)
                authStatus = retry.first
                authMsg = retry.second
            }
            fastReconnect = false

            if (stopEvent.get()) break

            if (authStatus == -1) {
                emitStatus("Auth Error: $authMsg")
                break
            }

            if (authStatus == 0) {
                emitStatus("$authMsg — retrying in 3s...")
                sleepInterruptible(3000)
                continue
            }

            emitStatus(authMsg)
            val (ok, streamMsg) = streamLoginAndStart()
            if (!ok) {
                emitStatus("$streamMsg — retrying in 2s...")
                sleepInterruptible(2000)
                continue
            }

            emitStatus(streamMsg)
            receiveFramesLoop()

            if (qualitySwitchPending && !stopEvent.get()) {
                qualitySwitchPending = false
                fastReconnect = true
                continue
            }

            if (!stopEvent.get()) {
                emitStatus("Stream disconnected — reconnecting...")
                sleepInterruptible(1500)
            }
        }
        closeStreamSock()
    }

    private fun sleepInterruptible(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!stopEvent.get() && System.currentTimeMillis() < end) {
            try { Thread.sleep(100) } catch (_: Exception) { break }
        }
    }

    private fun receiveFramesLoop() {
        val sock = streamSock ?: return
        val needDecrypt = deviceVersion > 30
        var frameBuffer = ByteArray(262144)
        var frameLen = 0
        var frameTotal = 0
        var nextFragment = 0
        var frameStartType = 0
        var assembling = false
        needReconnect = false

        val hdr = ByteArray(12)
        val payloadBuf = ByteArray(20480)

        while (!stopEvent.get() && !needReconnect) {
            if (!readExact(sock, hdr, 12, 12000)) break

            val lead = hdr[0].toInt() and 0xFF
            if (lead == 0x6F) {
                try { Thread.sleep(10) } catch (_: Exception) {}
                continue
            }
            if (lead != 0x7F) continue

            val pktType = hdr[1].toInt() and 0xFF
            val totalFrame = (hdr[3].toInt() and 0xFF) or ((hdr[4].toInt() and 0xFF) shl 8)
            val curFrame = (hdr[5].toInt() and 0xFF) or ((hdr[6].toInt() and 0xFF) shl 8)
            val payLen = (hdr[7].toInt() and 0xFF) or ((hdr[8].toInt() and 0xFF) shl 8)

            if (payLen <= 0 || payLen > 20000 || totalFrame == 0 || curFrame >= totalFrame) {
                continue
            }

            if (!readExact(sock, payloadBuf, payLen, 12000)) break

            if (pktType == 0x5B) continue

            if (curFrame == 0 || !assembling || totalFrame != frameTotal || curFrame != nextFragment) {
                frameLen = 0
                frameTotal = totalFrame
                nextFragment = 0
                frameStartType = pktType
                assembling = true
            }

            if (frameLen + payLen > frameBuffer.size) {
                frameBuffer = frameBuffer.copyOf(maxOf(frameBuffer.size * 2, frameLen + payLen))
            }
            System.arraycopy(payloadBuf, 0, frameBuffer, frameLen, payLen)
            frameLen += payLen
            nextFragment = curFrame + 1

            if (curFrame != totalFrame - 1) continue

            if (frameLen < 16) {
                frameLen = 0
                assembling = false
                continue
            }

            val fullFrame = frameBuffer.copyOfRange(0, frameLen)
            frameLen = 0
            assembling = false

            handleMediaFrame(frameStartType, fullFrame, needDecrypt)
        }
    }

    private fun handleMediaFrame(rawType: Int, full: ByteArray, needDecrypt: Boolean) {
        val cb = onFrame ?: return
        val hdrBuf = ByteBuffer.wrap(full, 0, min(16, full.size)).order(ByteOrder.LITTLE_ENDIAN)
        val outerFrameId = if (full.size >= 4) hdrBuf.getInt(0).toLong() and 0xFFFFFFFFL else 0L
        val outerFrameType = if (full.size >= 6) hdrBuf.getShort(4).toInt() and 0xFFFF else 0
        val outerFrameRate = if (full.size >= 8) hdrBuf.getShort(6).toInt() and 0xFFFF else 20
        val outerTimestamp = if (full.size >= 16) hdrBuf.getLong(8) else 0L

        if (rawType == 0x18 || rawType == 0x1A || rawType == 0x16) {
            if (!enableAudio) return
            val headerSkip = if (rawType == 0x16) 20 else 16
            if (full.size <= headerSkip) return
            val audioPayload = full.copyOfRange(headerSkip, full.size)
            if (needDecrypt && audioPayload.size >= 16) {
                if (communicationVersion == 21) {
                    decryptMediaPre2k(audioPayload, mode = 1)
                } else {
                    decryptAudioFrame(audioPayload)
                }
            }

            val acodec = when {
                rawType == 0x18 || (
                    audioPayload.size >= 4 &&
                        (audioPayload[0].toInt() and 0xFF) == 0xFF &&
                        (audioPayload[1].toInt() and 0xF0) == 0xF0
                    ) -> "aac"
                rawType == 0x1A -> "alaw"
                else -> "adpcm"
            }

            cb(
                CameraFrame(
                    isAudio = true,
                    rawType = rawType,
                    payload = audioPayload,
                    frameId = outerFrameId,
                    frameType = outerFrameType,
                    frameRate = outerFrameRate,
                    timestamp = outerTimestamp,
                    audioCodec = acodec
                )
            )
            return
        }

        val normalized = tryExtractVideoPayload(full, needDecrypt) ?: return
        val codec = detectVideoCodec(rawType, normalized)
        val isKey = isKeyVideoFrame(rawType, outerFrameType, normalized)

        cb(
            CameraFrame(
                isAudio = false,
                rawType = rawType,
                payload = normalized,
                codec = codec,
                isKeyframe = isKey,
                frameId = outerFrameId,
                frameType = outerFrameType,
                frameRate = if (outerFrameRate > 0) outerFrameRate else 20,
                timestamp = outerTimestamp
            )
        )
    }

    private fun tryExtractVideoPayload(full: ByteArray, needDecrypt: Boolean): ByteArray? {
        var bestScore = -1_000_000_000
        var bestPayload: ByteArray? = null
        val offsets = intArrayOf(16, 22, 20, 12, 8, 4, 0)

        for (offset in offsets) {
            if (full.size <= offset) continue
            val direct = full.copyOfRange(offset, full.size)
            val candidates = ArrayList<ByteArray>(5)

            if (needDecrypt && direct.size >= 16) {
                val dec = direct.copyOf()
                if (communicationVersion == 21) {
                    decryptMediaPre2k(dec, mode = 1)
                } else {
                    decryptVideoFrame(dec)
                }
                candidates.add(dec)

                // Also test 16-byte encrypted + 64-byte plain stride and PBKDF2 media_key
                val dec16 = direct.copyOf()
                decryptVideoFrameStride16(dec16, aesKey)
                candidates.add(dec16)

                if (!pbkdf2AesKey.contentEquals(aesKey)) {
                    val decPbkdf64 = direct.copyOf()
                    decryptVideoFrameWithKey(decPbkdf64, pbkdf2AesKey, blockBytes = 64)
                    candidates.add(decPbkdf64)

                    val decPbkdf16 = direct.copyOf()
                    decryptVideoFrameStride16(decPbkdf16, pbkdf2AesKey)
                    candidates.add(decPbkdf16)
                }
            }
            candidates.add(direct)

            for (cand in candidates) {
                val norm = normalizeVideoPayload(cand) ?: continue
                val score = scoreVideoPayload(norm)
                if ((offset == 16 || offset == 22) && cand.size >= 4 &&
                    cand[0] == 0.toByte() && cand[1] == 0.toByte() &&
                    cand[2] == 0.toByte() && cand[3] == 1.toByte() && score > 0
                ) {
                    return norm
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPayload = norm
                }
            }
        }
        return bestPayload
    }

    private fun normalizeVideoPayload(payload: ByteArray): ByteArray? {
        val start = findVideoStartCode(payload)
        if (start < 0) return null
        if (start + 3 <= payload.size &&
            payload[start] == 0.toByte() &&
            payload[start + 1] == 0.toByte() &&
            payload[start + 2] == 1.toByte()
        ) {
            val out = ByteArray(payload.size - start + 1)
            out[0] = 0
            System.arraycopy(payload, start, out, 1, payload.size - start)
            return out
        }
        return if (start == 0) payload else payload.copyOfRange(start, payload.size)
    }

    private fun findVideoStartCode(payload: ByteArray): Int {
        val limit = min(payload.size - 4, 128)
        if (limit < 0) return -1
        for (i in 0..limit) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte()) {
                if (payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()) {
                    if (isLikelyNalHeader(payload, i + 4)) return i
                } else if (payload[i + 2] == 1.toByte()) {
                    if (isLikelyNalHeader(payload, i + 3)) return i
                }
            }
        }
        return -1
    }

    private fun scoreVideoPayload(payload: ByteArray): Int {
        var score = 0
        var nalCount = 0
        var paramCount = 0
        var idrCount = 0
        var i = 0
        val n = payload.size

        while (i < n - 4 && nalCount < 12) {
            val sc = findNextStartCode(payload, i)
            if (sc < 0) break
            val scLen = if (payload[sc + 2] == 1.toByte()) 3 else 4
            val nalStart = sc + scLen
            if (!isLikelyNalHeader(payload, nalStart)) {
                i = sc + 1
                score -= 20
                continue
            }

            nalCount++
            val nalHdr = payload[nalStart].toInt() and 0xFF
            val h264Type = nalHdr and 0x1F
            val h265Type = (nalHdr shr 1) and 0x3F

            if (h264Type == 7 || h264Type == 8 || h265Type in 32..34) {
                paramCount++
                score += 50
            }
            if (h264Type == 5 || h265Type == 19 || h265Type == 20) {
                idrCount++
                score += 30
            }
            score += 10
            i = nalStart + 1
        }

        if (nalCount == 0) return -1_000_000_000
        return score + nalCount * 5 + paramCount * 40 + idrCount * 20
    }

    private fun findNextStartCode(payload: ByteArray, startFrom: Int): Int {
        var i = startFrom
        val end = payload.size - 3
        while (i <= end) {
            if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte()) {
                return if (i > startFrom && payload[i - 1] == 0.toByte()) i - 1 else i
            }
            i++
        }
        return -1
    }

    private fun isLikelyNalHeader(payload: ByteArray, index: Int): Boolean {
        if (index >= payload.size) return false
        val nalHdr = payload[index].toInt() and 0xFF
        val h264Type = nalHdr and 0x1F
        if (h264Type in 1..23) return true
        val h265Type = (nalHdr shr 1) and 0x3F
        return h265Type in 1..47
    }

    private fun detectVideoCodec(rawType: Int, payload: ByteArray): VideoCodec {
        if (rawType == 0x28 || rawType == 0x29) return VideoCodec.H265
        if (rawType == 0x00 || rawType == 0x01) return VideoCodec.H264

        if (payload.size < 6) return VideoCodec.UNKNOWN
        val offset = if (payload[2] == 1.toByte()) 3 else 4
        if (payload.size <= offset + 1) return VideoCodec.UNKNOWN
        val nalHdr = payload[offset].toInt() and 0xFF
        val nalB1 = payload[offset + 1].toInt() and 0xFF
        val h265Type = (nalHdr shr 1) and 0x3F
        if ((nalHdr and 0x80) == 0 && nalB1 == 0x01 && (h265Type == 1 || h265Type == 19 || h265Type == 20 || h265Type in 32..34)) {
            return VideoCodec.H265
        }
        val h264Type = nalHdr and 0x1F
        if (h264Type in 1..23) return VideoCodec.H264
        if (h265Type in 1..47) return VideoCodec.H265
        return VideoCodec.UNKNOWN
    }

    private fun isKeyVideoFrame(rawType: Int, frameType: Int, payload: ByteArray): Boolean {
        if (rawType == 0x00 || rawType == 0x28) return true
        val nalOffset = if (payload.size >= 4 && payload[2] == 0.toByte() && payload[3] == 1.toByte()) 4 else 3
        if (payload.size <= nalOffset) return false
        val nalHdr = payload[nalOffset].toInt() and 0xFF
        val h264Type = nalHdr and 0x1F
        if (h264Type == 5 || h264Type == 7 || h264Type == 8) return true
        val h265Type = (nalHdr shr 1) and 0x3F
        if (h265Type == 19 || h265Type == 20 || h265Type in 32..34) return true
        return frameType == 0
    }

    private fun decryptVideoFrame(data: ByteArray) {
        decryptVideoFrameWithKey(data, aesKey, blockBytes = 64)
    }

    private fun decryptVideoFrameWithKey(data: ByteArray, key: ByteArray, blockBytes: Int = 64) {
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            var offset = 0
            while (offset + blockBytes <= data.size) {
                cipher.update(data, offset, blockBytes, data, offset)
                offset += 80
            }
        } catch (_: Exception) {
        }
    }

    private fun decryptVideoFrameStride16(data: ByteArray, key: ByteArray) {
        decryptVideoFrameWithKey(data, key, blockBytes = 16)
    }

    private fun decryptAudioFrame(data: ByteArray) {
        val aligned = (data.size / 16) * 16
        if (aligned <= 0) return
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            cipher.update(data, 0, aligned, data, 0)
        } catch (_: Exception) {
        }
    }

    private fun decryptMediaPre2k(data: ByteArray, mode: Int = 1) {
        val decryptLen = if (mode == 0 && data.size > 2048) 2048 else (data.size / 16) * 16
        if (decryptLen <= 0) return
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"))
            cipher.update(data, 0, decryptLen, data, 0)
        } catch (_: Exception) {
        }
    }

    private fun sendRaw(payload: ByteArray): Boolean {
        val sock = streamSock ?: return false
        synchronized(sendLock) {
            return try {
                val out = sock.getOutputStream()
                out.write(payload)
                out.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun recvResponse(sock: Socket, maxLen: Int, timeoutMs: Int): ByteArray {
        sock.soTimeout = timeoutMs
        val buf = ByteArray(maxLen)
        var total = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val input = sock.getInputStream()
        while (System.currentTimeMillis() < deadline && total < maxLen) {
            try {
                val n = input.read(buf, total, maxLen - total)
                if (n <= 0) break
                total += n
                if (total >= 16) break
            } catch (_: Exception) {
                break
            }
        }
        return buf.copyOfRange(0, total)
    }

    private fun readExact(sock: Socket, dest: ByteArray, count: Int, timeoutMs: Int): Boolean {
        sock.soTimeout = timeoutMs
        var total = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val input: InputStream = try { sock.getInputStream() } catch (_: Exception) {
            needReconnect = true
            return false
        }
        while (total < count) {
            if (stopEvent.get() || System.currentTimeMillis() > deadline) {
                needReconnect = true
                return false
            }
            try {
                val n = input.read(dest, total, count - total)
                if (n <= 0) {
                    needReconnect = true
                    return false
                }
                total += n
            } catch (_: Exception) {
                needReconnect = true
                return false
            }
        }
        return true
    }

    // --- Camera Control Commands ---
    fun ptzUp(): Boolean = sendRaw(CameraCommands.PTZ_UP)
    fun ptzDown(): Boolean = sendRaw(CameraCommands.PTZ_DOWN)
    fun ptzLeft(): Boolean = sendRaw(CameraCommands.PTZ_LEFT)
    fun ptzRight(): Boolean = sendRaw(CameraCommands.PTZ_RIGHT)
    fun ptzStop(): Boolean = sendRaw(CameraCommands.PTZ_STOP)

    fun lightOn(): Boolean = sendRaw(CameraCommands.LIGHT_ON)
    fun lightOff(): Boolean = sendRaw(CameraCommands.LIGHT_OFF)
    fun lightAuto(): Boolean {
        val ok1 = sendRaw(CameraCommands.LIGHT_AUTO)
        try { Thread.sleep(30) } catch (_: Exception) {}
        val ok2 = sendRaw(CameraCommands.IMAGE_AUTO)
        return ok1 || ok2
    }

    fun imageColor(): Boolean = sendRaw(CameraCommands.IMAGE_COLOR)
    fun imageBw(): Boolean = sendRaw(CameraCommands.IMAGE_BW)
    fun imageAuto(): Boolean {
        val ok1 = sendRaw(CameraCommands.IMAGE_AUTO)
        try { Thread.sleep(30) } catch (_: Exception) {}
        val ok2 = sendRaw(CameraCommands.LIGHT_AUTO)
        return ok1 || ok2
    }

    fun imageFlip(): Boolean = sendRaw(CameraCommands.IMAGE_FLIP)

    // --- Hardware PTZ Preset (PTZX) Commands ---
    private fun sendPtzxPort8800(action: Int, slotId: Int): Boolean {
        return try {
            var ticket = authTicket
            if (ticket == 0L) {
                val (st, _) = authenticate(skipCloudWake = true)
                if (st != 1) return false
                ticket = authTicket
            }

            val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(0, 129)
            if (deviceVersion > 20 || ticket != 0L) {
                buf.putInt(4, (deviceId and 0xFFFFFFFFL).toInt())
                buf.put(8, 0.toByte())
                buf.putInt(9, (ticket and 0xFFFFFFFFL).toInt())
                buf.putInt(13, action)
                buf.putInt(17, slotId)
            } else {
                val uB = username.ifBlank { "admin" }.toByteArray(Charsets.US_ASCII)
                val pB = password.toByteArray(Charsets.US_ASCII)
                val arr = buf.array()
                System.arraycopy(uB, 0, arr, 4, min(uB.size, 32))
                System.arraycopy(pB, 0, arr, 36, min(pB.size, 32))
                buf.putInt(68, action)
                buf.putInt(72, slotId)
                buf.putInt(76, (deviceId and 0xFFFFFFFFL).toInt())
            }

            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 3000)
                s.soTimeout = 3000
                s.getOutputStream().write(buf.array())
                s.getOutputStream().flush()
                val resp = recvResponse(s, 256, 3000)
                if (resp.size >= 12) {
                    val rBuf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                    val rCmd = rBuf.getInt(0)
                    val rRes = rBuf.getInt(4)
                    if ((rCmd == 229 || rCmd == 429) && rRes == 1001) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun ptzPresetSet(slotId: Int): Boolean {
        val slot = slotId.coerceIn(0, 5)
        val okAdd = sendRaw(CameraCommands.ptzxPacket(CameraCommands.PTZX_ACTION_ADD, slot))
        try { Thread.sleep(40) } catch (_: Exception) {}
        val okReset = sendRaw(CameraCommands.ptzxPacket(CameraCommands.PTZX_ACTION_RESET, slot))
        Thread {
            sendPtzxPort8800(CameraCommands.PTZX_ACTION_ADD, slot)
            try { Thread.sleep(120) } catch (_: Exception) {}
            sendPtzxPort8800(CameraCommands.PTZX_ACTION_RESET, slot)
        }.apply { isDaemon = true }.start()
        return okAdd || okReset
    }

    fun ptzPresetGoto(slotId: Int): Boolean {
        val slot = slotId.coerceIn(0, 5)
        val okStream = sendRaw(CameraCommands.ptzxPacket(CameraCommands.PTZX_ACTION_GOTO, slot))
        if (!okStream) {
            Thread {
                sendPtzxPort8800(CameraCommands.PTZX_ACTION_GOTO, slot)
            }.apply { isDaemon = true }.start()
        }
        return okStream
    }

    fun ptzPresetClear(slotId: Int): Boolean {
        val slot = slotId.coerceIn(0, 5)
        val okStream = sendRaw(CameraCommands.ptzxPacket(CameraCommands.PTZX_ACTION_DEL, slot))
        Thread {
            sendPtzxPort8800(CameraCommands.PTZX_ACTION_DEL, slot)
        }.apply { isDaemon = true }.start()
        return okStream
    }

    // =========================================================================
    // V380 Port 8800 On-Camera SD Card Recording Search & Playback Protocol
    // Supports both Macrovideo SDK 6.0 (cmd 361/362 & 363/364) and Classic (cmd 1159/1160/1166 & 321/421/322)
    // =========================================================================

    private fun putWallClock7Bytes(buf: ByteArray, offset: Int, tsSec: Long) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = tsSec * 1000L
        }
        val yr = cal.get(Calendar.YEAR)
        buf[offset] = (yr and 0xFF).toByte()
        buf[offset + 1] = ((yr ushr 8) and 0xFF).toByte()
        buf[offset + 2] = (cal.get(Calendar.MONTH) + 1).toByte()
        buf[offset + 3] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        buf[offset + 4] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        buf[offset + 5] = cal.get(Calendar.MINUTE).toByte()
        buf[offset + 6] = cal.get(Calendar.SECOND).toByte()
    }

    fun listSdRecordings(
        year: Int,
        month: Int,
        day: Int
    ): Triple<Boolean, String, List<RecordingSegment>> {
        if (stopEvent.get()) return Triple(false, "Cancelled", emptyList())

        return SdSessionGate.withCameraSdLock(ip, stopEvent) {
            if (stopEvent.get()) return@withCameraSdLock Triple(false, "Cancelled", emptyList())

            var authMsg = "Connection failed"
            var authed = false
            for (attempt in 0 until 3) {
                if (stopEvent.get()) return@withCameraSdLock Triple(false, "Cancelled", emptyList())
                if (attempt == 1) {
                    sleepInterruptible(250)
                }
                val (res, msg) = authenticate(skipCloudWake = (attempt < 2))
                authMsg = msg
                if (res == 1) {
                    authed = true
                    break
                }
            }
            if (!authed) {
                return@withCameraSdLock Triple(false, "SD card auth failed: $authMsg", emptyList())
            }

            if (stopEvent.get()) return@withCameraSdLock Triple(false, "Cancelled", emptyList())

            // Try Macrovideo SDK 6.0 (cmd 361/362) first, then fallback to cmd 1159/1160/1166
            val res361 = querySdVia361(year, month, day)
            if (res361 != null) {
                return@withCameraSdLock res361
            }

            if (stopEvent.get()) return@withCameraSdLock Triple(false, "Cancelled", emptyList())

            // Re-authenticate for fallback 1159 command since 361 socket consumed the previous ticket
            val (reSt, _) = authenticate(skipCloudWake = true)
            if (reSt == 1 && !stopEvent.get()) {
                val res1159 = querySdVia1159(year, month, day)
                if (res1159 != null) {
                    return@withCameraSdLock res1159
                }
            }

            Triple(false, "No response from camera SD card index", emptyList())
        }
    }

    private fun querySdVia361(
        year: Int,
        month: Int,
        day: Int
    ): Triple<Boolean, String, List<RecordingSegment>>? {
        for (readTry in 0 until 2) {
            if (stopEvent.get()) return Triple(false, "Cancelled", emptyList())
            if (readTry > 0) {
                SdSessionGate.markClosed(ip)
                sleepInterruptible(165)
                val (res, _) = authenticate(skipCloudWake = true)
                if (res != 1) break
            }

            val segments = ArrayList<RecordingSegment>()
            val seenKeys = HashSet<Long>()
            val sock = Socket()
            try {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(ip, port), 4500)
                sock.soTimeout = 5000
                streamSock = sock

                val cmd361 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
                cmd361.putInt(0, 361)
                cmd361.putInt(4, (deviceId and 0xFFFFFFFFL).toInt())
                cmd361.putInt(8, (authTicket and 0xFFFFFFFFL).toInt())
                cmd361.put(12, 0.toByte()) // channel
                cmd361.put(13, 0.toByte()) // all types (normal + motion + alarm)
                // Start time at offset 14 (<HHHHHH)
                cmd361.putShort(14, year.toShort())
                cmd361.putShort(16, month.toShort())
                cmd361.putShort(18, day.toShort())
                cmd361.putShort(20, 0.toShort())
                cmd361.putShort(22, 0.toShort())
                cmd361.putShort(24, 0.toShort())
                // End time at offset 34 (<HHHHHH)
                cmd361.putShort(34, year.toShort())
                cmd361.putShort(36, month.toShort())
                cmd361.putShort(38, day.toShort())
                cmd361.putShort(40, 23.toShort())
                cmd361.putShort(42, 59.toShort())
                cmd361.putShort(44, 59.toShort())

                val out = sock.getOutputStream()
                out.write(cmd361.array())
                out.flush()

                val head = ByteArray(32)
                var gotHead = readExact(sock, head, 32, 5000)
                while (gotHead && ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 2000) {
                    gotHead = readExact(sock, head, 32, 5000)
                }
                if (!gotHead) {
                    continue
                }

                val hBuf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val rCmd = hBuf.getInt(0)
                val rRes = hBuf.getInt(4)
                if (rCmd != 461 && rRes != 1) {
                    continue
                }
                if (rRes == -30) {
                    return Triple(false, "SD card not inserted or not ready", emptyList())
                }
                if (rRes < 0) {
                    return Triple(true, "No recordings found for this date", emptyList())
                }

                val ask362 = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                ask362.putInt(0, 362)
                out.write(ask362.array())
                out.flush()

                val blk = ByteArray(512)
                while (!stopEvent.get()) {
                    if (!readExact(sock, blk, 512, 5000)) break
                    val bBuf = ByteBuffer.wrap(blk).order(ByteOrder.LITTLE_ENDIAN)
                    val bCmd = bBuf.getInt(0)
                    if (bCmd == 460) break
                    if (bCmd == 2000) continue
                    if (bCmd != 462) continue

                    val flag = bBuf.getShort(4).toInt() and 0xFFFF
                    val n = bBuf.getShort(6).toInt() and 0xFFFF
                    val recCount = min(n, (512 - 8) / 13)
                    for (i in 0 until recCount) {
                        val o = 8 + 13 * i
                        val sid = bBuf.getInt(o)
                        val rtype = blk[o + 4].toInt() and 0xFF
                        val st = bBuf.getInt(o + 5).toLong() and 0xFFFFFFFFL
                        val et = bBuf.getInt(o + 9).toLong() and 0xFFFFFFFFL
                        if (sid > 0 && et >= st) {
                            val dedupKey = (sid.toLong() shl 32) or (st and 0xFFFFFFFFL)
                            if (seenKeys.add(dedupKey)) {
                                segments.add(
                                    RecordingSegment(
                                        segId = sid,
                                        rawType = rtype,
                                        startTs = st,
                                        endTs = max(st + 1L, et)
                                    )
                                )
                            }
                        }
                    }
                    if (flag == 12) break
                }

                segments.sortByDescending { it.startTs }
                val anomCount = segments.count { it.isAnomaly }
                val summary = if (anomCount > 0) "${segments.size} clips ($anomCount anomalies)" else "${segments.size} clips"
                return Triple(true, summary, segments)
            } catch (_: Exception) {
                if (stopEvent.get()) return Triple(false, "Cancelled", emptyList())
            } finally {
                closeStreamSock()
            }
        }
        return null
    }

    private fun querySdVia1159(
        year: Int,
        month: Int,
        day: Int
    ): Triple<Boolean, String, List<RecordingSegment>>? {
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 6000
            streamSock = sock

            val pkt = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
            pkt.putInt(0, 1159)
            pkt.putInt(4, (deviceId and 0xFFFFFFFFL).toInt())
            pkt.putInt(8, (authTicket and 0xFFFFFFFFL).toInt())
            pkt.put(28, 1.toByte())
            pkt.putShort(29, year.toShort())
            pkt.put(31, month.toByte())
            pkt.put(32, day.toByte())
            pkt.put(37, 1.toByte())

            val out = sock.getOutputStream()
            out.write(pkt.array())
            out.flush()

            val resp1160 = ByteArray(24)
            if (!readExact(sock, resp1160, 24, 6000)) return null
            val rBuf = ByteBuffer.wrap(resp1160).order(ByteOrder.LITTLE_ENDIAN)
            val rCmd = rBuf.getInt(0)
            if (rCmd != 1160) return null

            val result = rBuf.getInt(4)
            val totalRecords = rBuf.getInt(16)
            val chunkCount = rBuf.getInt(20)

            if (result < 0) {
                return Triple(false, "SD card error ($result)", emptyList())
            }
            if (totalRecords <= 0 || chunkCount <= 0) {
                return Triple(true, "No recordings on SD card for this date", emptyList())
            }

            val segments = ArrayList<RecordingSegment>(min(totalRecords, 4096))
            val seenKeys = HashSet<Long>()
            val chunkHdr = ByteArray(12)

            for (c in 0 until min(chunkCount, 500)) {
                if (stopEvent.get()) break
                if (!readExact(sock, chunkHdr, 12, 5000)) break
                val cBuf = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN)
                val cCmd = cBuf.getInt(0)
                val recInPacket = cBuf.getInt(8)
                if (cCmd != 1166 || recInPacket <= 0 || recInPacket > 2048) break

                val bodyBytes = ByteArray(recInPacket * 16)
                if (!readExact(sock, bodyBytes, bodyBytes.size, 6000)) break
                val bBuf = ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until recInPacket) {
                    val base = i * 16
                    val segId = bBuf.getInt(base)
                    val rawType = bBuf.getInt(base + 4)
                    val sTs = bBuf.getInt(base + 8).toLong() and 0xFFFFFFFFL
                    val eTs = bBuf.getInt(base + 12).toLong() and 0xFFFFFFFFL
                    if (sTs > 0L && eTs >= sTs) {
                        val dedupKey = (segId.toLong() shl 32) or (sTs and 0xFFFFFFFFL)
                        if (seenKeys.add(dedupKey)) {
                            segments.add(
                                RecordingSegment(
                                    segId = segId,
                                    rawType = rawType,
                                    startTs = sTs,
                                    endTs = max(sTs + 1L, eTs)
                                )
                            )
                        }
                    }
                }
            }

            segments.sortByDescending { it.startTs }
            val anomCount = segments.count { it.isAnomaly }
            Triple(true, "${segments.size} clips ($anomCount anomalies)", segments)
        } catch (_: Exception) {
            null
        } finally {
            closeStreamSock()
        }
    }

    fun streamSdSegment(
        segment: RecordingSegment,
        startTs: Long? = null,
        onProgressTs: ((Long) -> Unit)? = null
    ): Pair<Boolean, String> {
        stopEvent.set(false)
        return SdSessionGate.withCameraSdLock(ip, stopEvent) {
            if (stopEvent.get()) return@withCameraSdLock false to "Stopped"

            var authMsg = "Connection failed"
            var authed = false
            for (attempt in 0 until 3) {
                if (stopEvent.get()) return@withCameraSdLock false to "Stopped"
                if (attempt == 1) {
                    sleepInterruptible(250)
                }
                val (res, msg) = authenticate(skipCloudWake = (attempt < 2))
                authMsg = msg
                if (res == 1) {
                    authed = true
                    break
                }
            }
            if (!authed) {
                return@withCameraSdLock false to "Playback auth failed: $authMsg"
            }

            if (deviceVersion > 30) {
                generateMediaKey(authTicket)
            }

            val reqStart = (startTs ?: segment.startTs).coerceIn(
                segment.startTs,
                max(segment.startTs, segment.endTs - 1L)
            )

            // Try SDK 6.0 cmd 363/364 first; if handshake does not return 463, fallback to cmd 321/421/322
            val res363 = streamSdSegmentVia363(segment, reqStart, onProgressTs)
            if (res363 != null) {
                return@withCameraSdLock res363
            }

            if (stopEvent.get()) return@withCameraSdLock false to "Stopped"

            val (reSt, _) = authenticate(skipCloudWake = true)
            if (reSt == 1 && !stopEvent.get()) {
                if (deviceVersion > 30) {
                    generateMediaKey(authTicket)
                }
                val res321 = streamSdSegmentVia321(segment, reqStart, onProgressTs)
                if (res321 != null) {
                    return@withCameraSdLock res321
                }
            }

            false to "SD card playback handshake failed"
        }
    }

    private fun streamSdSegmentVia363(
        segment: RecordingSegment,
        reqStart: Long,
        onProgressTs: ((Long) -> Unit)?
    ): Pair<Boolean, String>? {
        closeStreamSock()
        val sock = Socket()
        var sentStart = false
        return try {
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 262144
            sock.connect(InetSocketAddress(ip, port), 6000)
            sock.soTimeout = 8000
            streamSock = sock

            val req363 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            req363.putInt(0, 363)
            req363.putInt(4, (deviceId and 0xFFFFFFFFL).toInt())
            req363.putInt(8, (authTicket and 0xFFFFFFFFL).toInt())
            req363.putInt(12, segment.segId)
            putWallClock7Bytes(req363.array(), 16, reqStart)

            val out = sock.getOutputStream()
            out.write(req363.array())
            out.flush()

            val resp = ByteArray(64)
            var gotResp = readExact(sock, resp, 64, 6000)
            while (gotResp && ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 2000) {
                gotResp = readExact(sock, resp, 64, 6000)
            }
            if (!gotResp) return null

            val rBuf = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
            val rCmd = rBuf.getInt(0)
            val rRes = rBuf.getInt(4)
            if (rCmd != 463 || rRes != 1000) {
                return null
            }

            val w = rBuf.getShort(14).toInt()
            val h = rBuf.getShort(16).toInt()
            if (w in 160..4096) frameWidth = w
            if (h in 120..4096) frameHeight = h

            var playbackNote = 0
            var lastFid = 0L
            var lastPkt = 0

            val start364 = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            val startArr = start364.array()
            start364.putInt(0, 364)
            startArr[4] = 1
            startArr[5] = playbackNote.toByte()
            start364.putShort(6, 200.toShort())
            start364.putInt(8, 0)
            start364.putInt(12, segment.segId)
            putWallClock7Bytes(startArr, 16, reqStart)
            start364.putShort(23, 0.toShort())
            out.write(startArr)
            out.flush()
            sentStart = true

            fun sendAck() {
                val ack = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                val ackArr = ack.array()
                ack.putInt(0, 364)
                ackArr[4] = 2
                ackArr[5] = playbackNote.toByte()
                ack.putShort(6, 200.toShort())
                ack.putShort(8, (lastFid and 0xFFFFL).toShort())
                ack.putInt(12, segment.segId)
                ack.putShort(23, (lastPkt and 0xFFFF).toShort())
                out.write(ackArr)
                out.flush()
            }

            var pending = ByteArray(262144)
            var pendingLen = 0
            var packetsSinceAck = 0
            val needDecrypt = deviceVersion > 30
            val hdr = ByteArray(8)
            var payloadBuf = ByteArray(65536)

            while (!stopEvent.get()) {
                if (!readExact(sock, hdr, 8, 8000)) break
                val ptype = hdr[0].toInt() and 0xFF
                val note = hdr[1].toInt() and 0xFF
                val pktNo = (hdr[2].toInt() and 0xFF) or ((hdr[3].toInt() and 0xFF) shl 8)
                val psize = (hdr[4].toInt() and 0xFF) or ((hdr[5].toInt() and 0xFF) shl 8)

                if (psize > 0) {
                    if (psize > payloadBuf.size) {
                        payloadBuf = ByteArray(max(payloadBuf.size * 2, psize + 64))
                    }
                    if (!readExact(sock, payloadBuf, psize, 8000)) break
                }

                when (ptype) {
                    0x7F -> {
                        playbackNote = note
                        lastPkt = pktNo
                        if (psize > 0) {
                            if (pendingLen + psize > pending.size) {
                                pending = pending.copyOf(max(pending.size * 2, pendingLen + psize + 4096))
                            }
                            System.arraycopy(payloadBuf, 0, pending, pendingLen, psize)
                            pendingLen += psize
                        }
                        packetsSinceAck++

                        while (pendingLen >= 22 && !stopEvent.get()) {
                            val pBuf = ByteBuffer.wrap(pending, 0, 22).order(ByteOrder.LITTLE_ENDIAN)
                            val fid = pBuf.getInt(0).toLong() and 0xFFFFFFFFL
                            val ftype = pBuf.getShort(4).toInt() and 0xFFFF
                            val fts = pBuf.getLong(10)
                            val fsize = pBuf.getInt(18)

                            if (fsize < 0 || fsize > 0x80000) {
                                pendingLen = 0
                                break
                            }
                            if (pendingLen < 22 + fsize) {
                                break
                            }

                            val fbody = pending.copyOfRange(22, 22 + fsize)
                            val consumed = 22 + fsize
                            val rem = pendingLen - consumed
                            if (rem > 0) {
                                System.arraycopy(pending, consumed, pending, 0, rem)
                            }
                            pendingLen = rem
                            lastFid = fid

                            val curTimestampSec = when {
                                fts > 1_000_000_000_000L -> fts / 1000L
                                fts > 1_000_000_000L -> fts
                                fts > 100_000L -> fts / 1000L
                                else -> reqStart
                            }
                            if (curTimestampSec in (segment.startTs - 86400L)..(segment.endTs + 86400L)) {
                                onProgressTs?.invoke(curTimestampSec)
                            }

                            dispatchSdInnerFrame(
                                curFrameType = ftype,
                                curFrameId = fid,
                                curFrameRate = 25,
                                rawTimestampMs = fts,
                                rawFrame = fbody,
                                needDecrypt = needDecrypt
                            )
                        }

                        if (packetsSinceAck >= 200 && !stopEvent.get()) {
                            sendAck()
                            packetsSinceAck = 0
                        }
                    }
                    0x6F -> {
                        sendAck()
                        packetsSinceAck = 0
                    }
                    0xEF -> {
                        return true to "Segment finished"
                    }
                    0x1F -> {
                        // Keepalive / NOP
                    }
                }
            }
            true to "Stopped"
        } catch (_: Exception) {
            if (stopEvent.get()) return true to "Stopped"
            null
        } finally {
            if (sentStart && !stopEvent.get()) {
                try {
                    sock.soTimeout = 100
                    val stop364 = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                    stop364.putInt(0, 364)
                    stop364.array()[4] = 3
                    sock.getOutputStream().write(stop364.array())
                    sock.getOutputStream().flush()
                } catch (_: Exception) {
                }
            }
            closeStreamSock()
        }
    }

    private fun dispatchSdInnerFrame(
        curFrameType: Int,
        curFrameId: Long,
        curFrameRate: Int,
        rawTimestampMs: Long,
        rawFrame: ByteArray,
        needDecrypt: Boolean
    ) {
        val cb = onFrame ?: return
        val isAudioType = curFrameType in intArrayOf(21, 22, 24, 25, 26, 0x16, 0x18, 0x1A)
        if (isAudioType) {
            if (!enableAudio || rawFrame.size <= 4) return
            val audioPayload = rawFrame.copyOf()
            if (needDecrypt && audioPayload.size >= 16) {
                decryptAudioFrame(audioPayload)
            }
            val acodec = when {
                curFrameType == 24 || curFrameType == 0x18 || (
                    audioPayload.size >= 4 &&
                        (audioPayload[0].toInt() and 0xFF) == 0xFF &&
                        (audioPayload[1].toInt() and 0xF0) == 0xF0
                    ) -> "aac"
                curFrameType == 26 || curFrameType == 0x1A -> "alaw"
                else -> "adpcm"
            }
            cb(
                CameraFrame(
                    isAudio = true,
                    rawType = curFrameType,
                    payload = audioPayload,
                    frameId = curFrameId,
                    frameType = curFrameType,
                    frameRate = curFrameRate,
                    timestamp = rawTimestampMs,
                    audioCodec = acodec
                )
            )
            return
        }

        val isVideoType = curFrameType in intArrayOf(0, 1, 11, 30, 31, 40, 41, 50, 51, 52, 53)
        if (!isVideoType) return

        // Video frame (rawFrame already has 22-byte inner header stripped, so offset 0 is primary)
        val candidates = ArrayList<ByteArray>(5)
        if (needDecrypt && rawFrame.size >= 16) {
            val dec64 = rawFrame.copyOf()
            decryptVideoFrame(dec64)
            candidates.add(dec64)

            val dec16 = rawFrame.copyOf()
            decryptVideoFrameStride16(dec16, aesKey)
            candidates.add(dec16)

            if (!pbkdf2AesKey.contentEquals(aesKey)) {
                val decPbkdf64 = rawFrame.copyOf()
                decryptVideoFrameWithKey(decPbkdf64, pbkdf2AesKey, blockBytes = 64)
                candidates.add(decPbkdf64)

                val decPbkdf16 = rawFrame.copyOf()
                decryptVideoFrameStride16(decPbkdf16, pbkdf2AesKey)
                candidates.add(decPbkdf16)
            }
        }
        candidates.add(rawFrame)

        var bestNorm: ByteArray? = null
        var bestScore = -1_000_000_000
        for (cand in candidates) {
            val norm = normalizeVideoPayload(cand) ?: continue
            val sc = scoreVideoPayload(norm)
            if (cand.size >= 4 &&
                cand[0] == 0.toByte() && cand[1] == 0.toByte() &&
                cand[2] == 0.toByte() && cand[3] == 1.toByte() && sc > 0
            ) {
                bestNorm = norm
                break
            }
            if (sc > bestScore) {
                bestScore = sc
                bestNorm = norm
            }
        }

        val normalized = bestNorm ?: return
        val isH265 = curFrameType in intArrayOf(30, 31, 40, 41, 50, 51, 52, 53)
        val rawHint = if (isH265) {
            if (curFrameType in intArrayOf(30, 40, 50, 52)) 0x28 else 0x29
        } else {
            if (curFrameType in intArrayOf(0, 11)) 0x00 else 0x01
        }
        val codec = if (isH265) VideoCodec.H265 else VideoCodec.H264
        val isKey = curFrameType in intArrayOf(0, 11, 30, 40, 50, 52) ||
            isKeyVideoFrame(rawHint, curFrameType, normalized)

        cb(
            CameraFrame(
                isAudio = false,
                rawType = rawHint,
                payload = normalized,
                codec = codec,
                isKeyframe = isKey,
                frameId = curFrameId,
                frameType = curFrameType,
                frameRate = curFrameRate,
                timestamp = rawTimestampMs
            )
        )
    }

    private fun streamSdSegmentVia321(
        segment: RecordingSegment,
        reqStart: Long,
        onProgressTs: ((Long) -> Unit)?
    ): Pair<Boolean, String>? {
        closeStreamSock()
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 262144
            sock.connect(InetSocketAddress(ip, port), 5000)
            sock.soTimeout = 8000
            streamSock = sock

            val cmd321 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            cmd321.putInt(0, 321)
            cmd321.putInt(4, (authTicket and 0xFFFFFFFFL).toInt())
            cmd321.putInt(8, (deviceId and 0xFFFFFFFFL).toInt())
            cmd321.putInt(12, segment.rawType)
            cmd321.putInt(16, (reqStart and 0xFFFFFFFFL).toInt())
            cmd321.putInt(20, (segment.endTs and 0xFFFFFFFFL).toInt())
            cmd321.putInt(24, segment.segId)

            if (!sendRaw(cmd321.array())) return null

            val resp421 = recvResponse(sock, 412, 8000)
            if (resp421.size < 12) return null
            val rBuf = ByteBuffer.wrap(resp421).order(ByteOrder.LITTLE_ENDIAN)
            val rCmd = rBuf.getInt(0)
            val rRes = rBuf.getInt(4)
            if (rCmd != 421) return null
            if (rRes < 0) {
                return false to "Camera rejected SD playback (code $rRes)"
            }
            communicationVersion = rBuf.getShort(8).toInt() and 0xFFFF

            val cmd322 = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            cmd322.putInt(0, 322)
            cmd322.putShort(4, 0x3001.toShort())
            if (!sendRaw(cmd322.array())) return null

            // Demux 0x7F fragmented media frames and track timestamp progress
            val origCb = onFrame
            receiveFramesLoopWithProgress(reqStart, segment.endTs, onProgressTs, origCb)
            true to "SD playback finished"
        } catch (_: Exception) {
            null
        } finally {
            closeStreamSock()
        }
    }

    private fun receiveFramesLoopWithProgress(
        startTs: Long,
        endTs: Long,
        onProgressTs: ((Long) -> Unit)?,
        origCb: ((CameraFrame) -> Unit)?
    ) {
        val sock = streamSock ?: return
        val needDecrypt = deviceVersion > 30
        var frameBuffer = ByteArray(262144)
        var frameLen = 0
        var frameTotal = 0
        var nextFragment = 0
        var frameStartType = 0
        var assembling = false
        needReconnect = false

        val hdr = ByteArray(12)
        val payloadBuf = ByteArray(20480)
        var videoFramesSeen = 0L

        while (!stopEvent.get() && !needReconnect) {
            if (!readExact(sock, hdr, 12, 8000)) break

            val lead = hdr[0].toInt() and 0xFF
            if (lead == 0x6F) {
                try { Thread.sleep(10) } catch (_: Exception) {}
                continue
            }
            if (lead != 0x7F) continue

            val pktType = hdr[1].toInt() and 0xFF
            val totalFrame = (hdr[3].toInt() and 0xFF) or ((hdr[4].toInt() and 0xFF) shl 8)
            val curFrame = (hdr[5].toInt() and 0xFF) or ((hdr[6].toInt() and 0xFF) shl 8)
            val payLen = (hdr[7].toInt() and 0xFF) or ((hdr[8].toInt() and 0xFF) shl 8)

            if (payLen <= 0 || payLen > 20000 || totalFrame == 0 || curFrame >= totalFrame) {
                continue
            }

            if (!readExact(sock, payloadBuf, payLen, 8000)) break
            if (pktType == 0x5B) continue

            if (curFrame == 0 || !assembling || totalFrame != frameTotal || curFrame != nextFragment) {
                frameLen = 0
                frameTotal = totalFrame
                nextFragment = 0
                frameStartType = pktType
                assembling = true
            }

            if (frameLen + payLen > frameBuffer.size) {
                frameBuffer = frameBuffer.copyOf(maxOf(frameBuffer.size * 2, frameLen + payLen))
            }
            System.arraycopy(payloadBuf, 0, frameBuffer, frameLen, payLen)
            frameLen += payLen
            nextFragment = curFrame + 1

            if (curFrame != totalFrame - 1) continue
            if (frameLen < 16) {
                frameLen = 0
                assembling = false
                continue
            }

            val fullFrame = frameBuffer.copyOfRange(0, frameLen)
            frameLen = 0
            assembling = false

            if (frameStartType != 0x18 && frameStartType != 0x1A && frameStartType != 0x16) {
                videoFramesSeen++
                val hdrBuf = ByteBuffer.wrap(fullFrame, 0, min(16, fullFrame.size)).order(ByteOrder.LITTLE_ENDIAN)
                val rawTs = if (fullFrame.size >= 16) hdrBuf.getLong(8) else 0L
                val estTs = when {
                    rawTs > 1_000_000_000_000L -> rawTs / 1000L
                    rawTs > 1_000_000_000L -> rawTs
                    else -> min(endTs, startTs + (videoFramesSeen / 20L))
                }
                onProgressTs?.invoke(estTs)
            }

            if (origCb != null) {
                handleMediaFrame(frameStartType, fullFrame, needDecrypt)
            }
        }
    }
}
