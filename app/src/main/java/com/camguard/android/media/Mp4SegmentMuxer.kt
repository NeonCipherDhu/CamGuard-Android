package com.camguard.android.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.camguard.android.model.CameraConfig
import com.camguard.android.protocol.CameraFrame
import com.camguard.android.protocol.RecordingSegment
import com.camguard.android.protocol.VideoCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * High-sensitivity acoustic level detector (0.0f .. 1.0f) for 16-bit 8 kHz PCM.
 * Replicates the exact algorithm from desktop `stream_worker.py`:
 * 1. DC offset removal (`arr - mean(arr)`),
 * 2. 1st/2nd-order high-pass difference energy (`diff`) to suppress <150 Hz power-supply hum
 *    and isolate 300–3800 Hz whispers, taps, footsteps, and voices,
 * 3. 96th-percentile peak envelope so brief transients aren't averaged out,
 * 4. Per-stream adaptive noise-floor tracking + logarithmic compression (-68 dBFS to -20 dBFS).
 */
object AcousticLevelDetector {
    private val floorStateByStream = ConcurrentHashMap<String, FloatArray>()

    fun computeLevel(samples: ShortArray, streamKey: String = "default"): Float {
        val n = samples.size
        if (n < 2) return 0f

        val centered = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val v = samples[i] * (1.0f / 32768.0f)
            centered[i] = v
            sum += v
        }
        val mean = sum / n.toFloat()

        var sumSq = 0f
        for (i in 0 until n) {
            val c = centered[i] - mean
            centered[i] = c
            sumSq += c * c
        }
        val rms = sqrt(sumSq / n.toFloat())

        val hpRms: Float
        val peakEnv: Float
        if (n > 2) {
            val hp1 = FloatArray(n - 1)
            var hp1Sq = 0f
            for (i in 0 until n - 1) {
                val d = centered[i + 1] - centered[i]
                hp1[i] = d
                hp1Sq += d * d
            }
            var hp2Sq = 0f
            for (i in 0 until n - 2) {
                val d2 = hp1[i + 1] - hp1[i]
                hp2Sq += d2 * d2
            }
            val rmsHp1 = sqrt(hp1Sq / (n - 1).toFloat())
            val rmsHp2 = sqrt(hp2Sq / (n - 2).toFloat())
            hpRms = rmsHp1 * 0.80f + rmsHp2 * 0.50f

            val p96Centered = percentile96Abs(centered) * 0.60f
            val p96Hp1 = percentile96Abs(hp1) * 0.72f
            peakEnv = max(p96Centered, p96Hp1)
        } else {
            hpRms = rms
            peakEnv = max(abs(centered[0]), abs(centered[1])) * 0.60f
        }

        val state = floorStateByStream.getOrPut(streamKey) {
            floatArrayOf(
                rms.coerceIn(0.00018f, 0.015f),
                hpRms.coerceIn(0.00015f, 0.012f),
                peakEnv.coerceIn(0.00022f, 0.018f)
            )
        }

        val vals = floatArrayOf(rms, hpRms, peakEnv)
        val mins = floatArrayOf(0.00018f, 0.00015f, 0.00022f)
        val maxs = floatArrayOf(0.030f, 0.025f, 0.035f)

        synchronized(state) {
            for (idx in 0..2) {
                val v = vals[idx]
                var f = state[idx]
                if (v <= f * 1.04f) {
                    // Fast downward adaptation to lock onto the quietest ambient floor
                    f = f * 0.80f + v * 0.20f
                } else if (v < f * 1.32f && v < maxs[idx]) {
                    // Slow drift only for stationary background hiss
                    f = f * 0.988f + v * 0.012f
                }
                state[idx] = f.coerceIn(mins[idx], maxs[idx])
            }
        }

        val fRms = state[0]
        val fHp = state[1]
        val fPk = state[2]

        val dRms = max(0f, rms - fRms * 1.04f)
        val dHp = max(0f, hpRms - fHp * 1.04f)
        val dPk = max(0f, peakEnv - fPk * 1.05f)
        val delta = max(dRms * 1.15f, max(dHp * 1.45f, dPk * 1.30f))

        val relJump = max(
            dRms / max(0.00035f, fRms),
            max(
                dHp / max(0.00030f, fHp),
                dPk / max(0.00045f, fPk)
            )
        )

        val rawSig = max(rms, max(hpRms, peakEnv))
        val db = 20.0f * log10(max(rawSig, 1e-6f))
        val absNorm = ((db + 68.0f) / 48.0f).coerceIn(0f, 1f).pow(0.68f)

        if (delta > 0.00008f || relJump > 0.06f) {
            val deltaNorm = min(1f, (delta * 85.0f).pow(0.36f))
            val relNorm = min(1f, (relJump * 1.10f).pow(0.46f))
            return min(1f, max(deltaNorm, max(relNorm, absNorm * 0.88f)))
        }

        return min(0.06f, absNorm * 0.14f)
    }

    private fun percentile96Abs(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        val step = max(1, arr.size / 256)
        val sampled = FloatArray((arr.size + step - 1) / step)
        var j = 0
        var i = 0
        while (i < arr.size && j < sampled.size) {
            sampled[j++] = abs(arr[i])
            i += step
        }
        Arrays.sort(sampled, 0, j)
        val idx = ((j - 1) * 0.96f).toInt().coerceIn(0, max(0, j - 1))
        return sampled[idx]
    }
}

data class LocalCaptureItem(
    val file: File,
    val isVideo: Boolean,
    val timestampMs: Long,
    val durationSec: Int = 0
) {
    val displayTime12h: String
        get() {
            val sdf = SimpleDateFormat("MMMM dd, yyyy • hh:mm:ss a", Locale.US)
            return sdf.format(Date(timestampMs))
        }

    val shortTime12h: String
        get() {
            val sdf = SimpleDateFormat("hh:mm:ss a", Locale.US)
            return sdf.format(Date(timestampMs))
        }

    val sizeKbStr: String
        get() {
            val kb = file.length() / 1024L
            return if (kb >= 1024L) {
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            } else {
                "${max(1L, kb)} KB"
            }
        }
}

object FootageBackupStore {
    private fun safeToken(raw: String): String {
        return raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "cam" }
    }

    fun cameraFolderKey(camera: CameraConfig): String {
        val idPart = camera.deviceId.ifBlank { camera.camId.ifBlank { camera.ip } }
        return safeToken(idPart)
    }

    fun getBackupsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "backups").apply { mkdirs() }
    }

    fun getLocalCapturesRoot(context: Context, camera: CameraConfig): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, "captures"), cameraFolderKey(camera)).apply { mkdirs() }
    }

    fun dateKeyForYmd(year: Int, month: Int, day: Int): String {
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    fun getCameraDateBackupDir(
        context: Context,
        camera: CameraConfig,
        dateKey: String
    ): File {
        val camDir = File(getBackupsRoot(context), cameraFolderKey(camera))
        return File(camDir, dateKey).apply { mkdirs() }
    }

    /*
     * Deterministic backup filename:
     * `SD_{cam_id}_{seg_id}_{start_ts}_{end_ts}.mp4` in `backups/{cam_key}/{YYYY-MM-DD}/`
     */
    fun getSegmentBackupFile(
        context: Context,
        camera: CameraConfig,
        segment: RecordingSegment
    ): File {
        val camKey = cameraFolderKey(camera)
        val dateDir = getCameraDateBackupDir(context, camera, segment.dateKeyUtc)
        val fileName = "SD_${camKey}_${segment.segId}_${segment.startTs}_${segment.endTs}.mp4"
        return File(dateDir, fileName)
    }

    fun findSegmentBackupFile(
        context: Context,
        camera: CameraConfig,
        segment: RecordingSegment
    ): File? {
        val exact = getSegmentBackupFile(context, camera, segment)
        if (exact.isFile && exact.length() > 512L) {
            return exact
        }
        val dateDir = exact.parentFile ?: return null
        val suffix = "_${segment.segId}_${segment.startTs}_${segment.endTs}.mp4"
        val match = dateDir.listFiles()?.firstOrNull { f ->
            f.isFile && f.length() > 512L && (
                f.name.endsWith(suffix) ||
                    f.name.contains("_${segment.segId}_${segment.startTs}_")
                )
        }
        return match
    }

    fun saveSegmentBackupMeta(
        context: Context,
        camera: CameraConfig,
        segments: List<RecordingSegment>
    ) {
        if (segments.isEmpty()) return
        val byDate = segments.groupBy { it.dateKeyUtc }
        for ((dateKey, list) in byDate) {
            try {
                val dir = getCameraDateBackupDir(context, camera, dateKey)
                val metaFile = File(dir, "segments_meta.json")
                val existingMap = LinkedHashMap<String, RecordingSegment>()
                for (s in loadBackedUpSegmentsForDate(context, camera, dateKey)) {
                    existingMap["${s.segId}_${s.startTs}"] = s
                }
                for (s in list) {
                    existingMap["${s.segId}_${s.startTs}"] = s
                }
                val arr = JSONArray()
                for (s in existingMap.values) {
                    arr.put(
                        JSONObject().apply {
                            put("segId", s.segId)
                            put("rawType", s.rawType)
                            put("startTs", s.startTs)
                            put("endTs", s.endTs)
                        }
                    )
                }
                metaFile.writeText(arr.toString(2), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    /*
     * Loads all locally backed-up SD segments for a calendar date (`YYYY-MM-DD`) so they are
     * always listed in `On-Camera SD Card` mode even after restarting the app or while offline.
     */
    fun loadBackedUpSegmentsForDate(
        context: Context,
        camera: CameraConfig,
        dateKey: String
    ): List<RecordingSegment> {
        val dir = getCameraDateBackupDir(context, camera, dateKey)
        val result = LinkedHashMap<String, RecordingSegment>()

        val metaFile = File(dir, "segments_meta.json")
        if (metaFile.isFile) {
            try {
                val arr = JSONArray(metaFile.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val seg = RecordingSegment(
                        segId = obj.optInt("segId"),
                        rawType = obj.optInt("rawType", 1),
                        startTs = obj.optLong("startTs"),
                        endTs = obj.optLong("endTs")
                    )
                    if (seg.startTs > 0L && seg.endTs > seg.startTs) {
                        val f = findSegmentBackupFile(context, camera, seg)
                        if (f != null) {
                            result["${seg.segId}_${seg.startTs}"] = seg
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Also parse any `SD_{cam}_{segId}_{startTs}_{endTs}.mp4` files directly on disk
        val regex = Regex("""^SD_.*_(-?\d+)_(\d+)_(\d+)\.mp4$""")
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 512L && f.name.endsWith(".mp4", ignoreCase = true)) {
                val m = regex.find(f.name)
                if (m != null) {
                    val segId = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val sTs = m.groupValues[2].toLongOrNull() ?: return@forEach
                    val eTs = m.groupValues[3].toLongOrNull() ?: return@forEach
                    val key = "${segId}_$sTs"
                    if (!result.containsKey(key) && sTs > 0L && eTs > sTs) {
                        result[key] = RecordingSegment(
                            segId = segId,
                            rawType = 1,
                            startTs = sTs,
                            endTs = eTs
                        )
                    }
                }
            }
        }

        return result.values.sortedByDescending { it.startTs }
    }

    /*
     * Lists only manual recordings and snapshots captured from the live feed (`captures/`).
     * Never includes SD card backups.
     */
    fun listLocalCaptures(context: Context, camera: CameraConfig): List<LocalCaptureItem> {
        val dir = getLocalCapturesRoot(context, camera)
        val files = dir.listFiles() ?: return emptyList()
        val items = ArrayList<LocalCaptureItem>()
        for (f in files) {
            if (!f.isFile || f.length() <= 128L) continue
            val lower = f.name.lowercase(Locale.US)
            if (lower.startsWith("sd_")) continue // Never include SD backups
            val isVid = lower.endsWith(".mp4")
            val isImg = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            if (isVid || isImg) {
                items.add(
                    LocalCaptureItem(
                        file = f,
                        isVideo = isVid,
                        timestampMs = f.lastModified()
                    )
                )
            }
        }
        return items.sortedByDescending { it.timestampMs }
    }

    fun exportMp4ToPublicMovies(context: Context, mp4File: File, displaySubfolder: String = "CamGuard"): Boolean {
        if (!mp4File.isFile || mp4File.length() <= 512L) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, mp4File.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$displaySubfolder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                mp4File.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

/*
 * Native Android `.MP4` muxer using `MediaMuxer` (`MUXER_OUTPUT_MPEG_4`).
 * Muxes Annex-B H.264 / H.265 video frames directly into an `.mp4` container without re-encoding.
 */
class Mp4StreamMuxer(
    private val outputFile: File,
    private val defaultWidth: Int = 1280,
    private val defaultHeight: Int = 720
) {
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var started = false
    private var closed = false
    private var frameCount = 0
    private var ptsUs = 0L
    private var firstFrameWallClockSec: Long = 0L

    @Synchronized
    fun writeVideoFrame(frame: CameraFrame): Boolean {
        if (closed || frame.isAudio || frame.payload.size < 5) return false

        val codec = when {
            frame.codec != VideoCodec.UNKNOWN -> frame.codec
            frame.rawType == 0x28 || frame.rawType == 0x29 -> VideoCodec.H265
            else -> VideoCodec.H264
        }

        if (!started) {
            if (!frame.isKeyframe) return false
            val format = buildVideoMediaFormat(codec, frame.payload, defaultWidth, defaultHeight) ?: return false
            try {
                outputFile.parentFile?.mkdirs()
                if (outputFile.exists()) outputFile.delete()
                val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoTrackIndex = m.addTrack(format)
                m.start()
                muxer = m
                started = true
                ptsUs = 0L
                firstFrameWallClockSec = frame.timestamp
            } catch (_: Exception) {
                close()
                return false
            }
        }

        val m = muxer ?: return false
        return try {
            val fps = frame.frameRate.coerceIn(10, 30)
            val stepUs = 1_000_000L / fps.toLong()
            if (frameCount > 0) {
                ptsUs += stepUs
            }

            val buf = ByteBuffer.wrap(frame.payload)
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = frame.payload.size
                presentationTimeUs = ptsUs
                flags = if (frame.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            m.writeSampleData(videoTrackIndex, buf, info)
            frameCount++
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun close(): Boolean {
        if (closed) return frameCount > 0 && outputFile.isFile && outputFile.length() > 512L
        closed = true
        val m = muxer
        muxer = null
        var ok = false
        if (m != null) {
            try {
                if (started && frameCount > 0) {
                    m.stop()
                    ok = true
                }
            } catch (_: Exception) {
            } finally {
                try { m.release() } catch (_: Exception) {}
            }
        }
        if (!ok || !outputFile.isFile || outputFile.length() <= 512L) {
            try { outputFile.delete() } catch (_: Exception) {}
            return false
        }
        return true
    }

    companion object {
        private data class NalUnit(val typeH264: Int, val typeH265: Int, val annexBBytes: ByteArray)

        private fun splitAnnexBNals(payload: ByteArray): List<NalUnit> {
            val starts = ArrayList<Int>()
            var i = 0
            val n = payload.size
            while (i <= n - 3) {
                if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte()) {
                    if (payload[i + 2] == 1.toByte()) {
                        val scPos = if (i > 0 && payload[i - 1] == 0.toByte()) i - 1 else i
                        if (starts.isEmpty() || starts.last() != scPos) {
                            starts.add(scPos)
                        }
                        i += 3
                        continue
                    }
                }
                i++
            }
            if (starts.isEmpty()) return emptyList()

            val out = ArrayList<NalUnit>(starts.size)
            for (idx in starts.indices) {
                val s = starts[idx]
                val e = if (idx + 1 < starts.size) starts[idx + 1] else n
                val scLen = if (s + 3 < n && payload[s + 2] == 0.toByte() && payload[s + 3] == 1.toByte()) 4 else 3
                val nalHdrIdx = s + scLen
                if (nalHdrIdx >= e) continue
                val hdr = payload[nalHdrIdx].toInt() and 0xFF
                val h264Type = hdr and 0x1F
                val h265Type = (hdr ushr 1) and 0x3F

                // Normalize to 4-byte Annex-B start code 00 00 00 01
                val rawLen = e - nalHdrIdx
                val norm = ByteArray(4 + rawLen)
                norm[0] = 0
                norm[1] = 0
                norm[2] = 0
                norm[3] = 1
                System.arraycopy(payload, nalHdrIdx, norm, 4, rawLen)
                out.add(NalUnit(h264Type, h265Type, norm))
            }
            return out
        }

        fun buildVideoMediaFormat(
            codec: VideoCodec,
            keyframePayload: ByteArray,
            width: Int,
            height: Int
        ): MediaFormat? {
            val nals = splitAnnexBNals(keyframePayload)
            if (nals.isEmpty()) return null
            val w = width.coerceIn(160, 3840)
            val h = height.coerceIn(120, 2160)

            return if (codec == VideoCodec.H265) {
                val vps = nals.firstOrNull { it.typeH265 == 32 }?.annexBBytes
                val sps = nals.firstOrNull { it.typeH265 == 33 }?.annexBBytes
                val pps = nals.firstOrNull { it.typeH265 == 34 }?.annexBBytes
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
                if (vps != null && sps != null && pps != null) {
                    val csd0 = ByteArray(vps.size + sps.size + pps.size)
                    System.arraycopy(vps, 0, csd0, 0, vps.size)
                    System.arraycopy(sps, 0, csd0, vps.size, sps.size)
                    System.arraycopy(pps, 0, csd0, vps.size + sps.size, pps.size)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                } else {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(keyframePayload))
                }
                fmt
            } else {
                val sps = nals.firstOrNull { it.typeH264 == 7 }?.annexBBytes
                val pps = nals.firstOrNull { it.typeH264 == 8 }?.annexBBytes
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                if (sps != null && pps != null) {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                } else {
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(keyframePayload))
                }
                fmt
            }
        }
    }
}
