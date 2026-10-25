package com.v380protect.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.v380protect.android.model.CameraConfig
import com.v380protect.android.protocol.V380AudioDecoder
import com.v380protect.android.protocol.V380Client
import com.v380protect.android.protocol.V380Frame
import com.v380protect.android.protocol.VideoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StreamTelemetry(
    val statusText: String = "Idle",
    val isConnected: Boolean = false,
    val fps: Float = 0f,
    val kbps: Float = 0f,
    val codecLabel: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val quality: Int = 1, // 1 = HD, 0 = SD
    val isMuted: Boolean = true,
    val audioLevel: Float = 0f,
    val isRecording: Boolean = false
)

class CameraStreamController(
    initialConfig: CameraConfig
) {
    @Volatile
    var config: CameraConfig = initialConfig
        private set

    private val _telemetry = MutableStateFlow(
        StreamTelemetry(
            statusText = "Connecting...",
            quality = initialConfig.quality,
            isMuted = true
        )
    )
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private var client: V380Client? = null
    private var workerThread: Thread? = null
    @Volatile private var running = false

    // Video hardware decoder state
    private val videoLock = Any()
    private var activeSurface: Surface? = null
    private var videoCodec: MediaCodec? = null
    private var activeCodecType: VideoCodec = VideoCodec.UNKNOWN
    private var seenKeyframe = false
    private var lastKeyframePayload: ByteArray? = null
    private var ptsUs: Long = 0L

    // Audio decoder & output state
    private val audioLock = Any()
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate: Int = 0
    private var aacCodec: MediaCodec? = null
    private var aacSampleRate: Int = 8000
    private var smoothedAudioLevel: Float = 0f

    // Live manual recording state (saved into Local Captures)
    private val recordLock = Any()
    private var activeLiveMuxer: Mp4StreamMuxer? = null
    private var activeLiveRecordFile: File? = null

    // Telemetry window
    private var bytesWindow = 0L
    private var framesWindow = 0
    private var lastStatTimeMs = System.currentTimeMillis()

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            seenKeyframe = false
            lastStatTimeMs = System.currentTimeMillis()
            bytesWindow = 0L
            framesWindow = 0

            val c = V380Client(
                ip = config.ip,
                port = config.port,
                deviceId = config.deviceId,
                username = config.username,
                password = config.password,
                streamQuality = config.quality,
                enableAudio = config.audioEnabled,
                onFrame = { frame -> handleFrame(frame) },
                onStatus = { msg -> handleStatus(msg) }
            )
            client = c

            workerThread = Thread({
                c.runForever()
            }, "V380Stream-${config.camId}").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            client?.stop()
            client = null
            workerThread = null
        }
        synchronized(recordLock) {
            activeLiveMuxer?.close()
            activeLiveMuxer = null
            activeLiveRecordFile = null
        }
        synchronized(videoLock) {
            releaseVideoCodecUnlocked()
        }
        synchronized(audioLock) {
            releaseAudioUnlocked()
        }
        _telemetry.value = _telemetry.value.copy(
            statusText = "Offline",
            isConnected = false,
            fps = 0f,
            kbps = 0f,
            audioLevel = 0f,
            isRecording = false
        )
    }

    fun startLiveRecording(context: Context): String? {
        synchronized(recordLock) {
            if (activeLiveMuxer != null) return activeLiveRecordFile?.name
            val dir = FootageBackupStore.getLocalCapturesRoot(context, config)
            val safeName = config.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "V380" }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "${safeName}_REC_$ts.mp4")
            val muxer = Mp4StreamMuxer(
                outputFile = outFile,
                defaultWidth = _telemetry.value.width.coerceIn(160, 3840),
                defaultHeight = _telemetry.value.height.coerceIn(120, 2160)
            )
            // Seed with last keyframe if available so recording starts immediately
            val kf = synchronized(videoLock) { lastKeyframePayload }
            val kfCodec = synchronized(videoLock) { activeCodecType }
            if (kf != null && kfCodec != VideoCodec.UNKNOWN) {
                muxer.writeVideoFrame(
                    V380Frame(
                        isAudio = false,
                        rawType = if (kfCodec == VideoCodec.H265) 0x28 else 0x00,
                        payload = kf,
                        codec = kfCodec,
                        isKeyframe = true,
                        frameRate = 20,
                        timestamp = System.currentTimeMillis() / 1000L
                    )
                )
            }
            activeLiveMuxer = muxer
            activeLiveRecordFile = outFile
            _telemetry.value = _telemetry.value.copy(isRecording = true)
            return outFile.name
        }
    }

    fun stopLiveRecording(context: Context): String? {
        val (muxer, file) = synchronized(recordLock) {
            val m = activeLiveMuxer
            val f = activeLiveRecordFile
            activeLiveMuxer = null
            activeLiveRecordFile = null
            _telemetry.value = _telemetry.value.copy(isRecording = false)
            m to f
        }
        if (muxer == null || file == null) return null
        val ok = muxer.close()
        if (ok) {
            Thread {
                FootageBackupStore.exportMp4ToPublicMovies(context.applicationContext, file)
            }.apply { isDaemon = true }.start()
            return file.name
        }
        return null
    }

    fun reconnect(newConfig: CameraConfig = config) {
        stop()
        config = newConfig
        _telemetry.value = _telemetry.value.copy(
            statusText = "Reconnecting...",
            quality = newConfig.quality
        )
        start()
    }

    fun attachSurface(surface: Surface) {
        synchronized(videoLock) {
            if (activeSurface === surface) return
            activeSurface = surface
            releaseVideoCodecUnlocked()
            val kf = lastKeyframePayload
            val codecType = activeCodecType
            if (kf != null && codecType != VideoCodec.UNKNOWN && surface.isValid) {
                seenKeyframe = true
                ensureVideoCodecUnlocked(codecType)
                queueNalToCodecUnlocked(kf)
            } else {
                seenKeyframe = false
            }
        }
    }

    fun detachSurface(surface: Surface) {
        synchronized(videoLock) {
            if (activeSurface === surface) {
                activeSurface = null
                releaseVideoCodecUnlocked()
            }
        }
    }

    fun setMuted(muted: Boolean) {
        _telemetry.value = _telemetry.value.copy(
            isMuted = muted,
            audioLevel = if (muted) 0f else _telemetry.value.audioLevel
        )
        if (muted) {
            synchronized(audioLock) {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun toggleMuted(): Boolean {
        val next = !_telemetry.value.isMuted
        setMuted(next)
        return next
    }

    fun setQuality(quality: Int) {
        val newQ = if (quality != 0) 1 else 0
        config = config.copy(quality = newQ)
        _telemetry.value = _telemetry.value.copy(quality = newQ)
        synchronized(videoLock) {
            seenKeyframe = false
            releaseVideoCodecUnlocked()
        }
        client?.setQuality(newQ)
    }

    fun toggleQuality(): Int {
        val next = if (_telemetry.value.quality != 0) 0 else 1
        setQuality(next)
        return next
    }

    // --- Camera Controls (executed on background thread so UI never blocks) ---
    fun ptzMove(direction: String) {
        val c = client ?: return
        Thread {
            when (direction) {
                "up" -> c.ptzUp()
                "down" -> c.ptzDown()
                "left" -> c.ptzLeft()
                "right" -> c.ptzRight()
                "stop" -> c.ptzStop()
            }
        }.apply { isDaemon = true }.start()
    }

    fun setLightMode(mode: String) {
        config = config.copy(lightMode = mode)
        val c = client ?: return
        Thread {
            when (mode) {
                "on" -> c.lightOn()
                "off" -> c.lightOff()
                else -> c.lightAuto()
            }
        }.apply { isDaemon = true }.start()
    }

    fun setImageMode(mode: String) {
        config = config.copy(imageMode = mode)
        val c = client ?: return
        Thread {
            when (mode) {
                "color" -> c.imageColor()
                "bw" -> c.imageBw()
                else -> c.imageAuto()
            }
        }.apply { isDaemon = true }.start()
    }

    fun flipImage() {
        config = config.copy(flipped = !config.flipped)
        val c = client ?: return
        Thread {
            c.imageFlip()
        }.apply { isDaemon = true }.start()
    }

    fun ptzPresetSet(slotId: Int) {
        val c = client ?: return
        Thread { c.ptzPresetSet(slotId) }.apply { isDaemon = true }.start()
    }

    fun ptzPresetGoto(slotId: Int) {
        val c = client ?: return
        Thread { c.ptzPresetGoto(slotId) }.apply { isDaemon = true }.start()
    }

    fun ptzPresetClear(slotId: Int) {
        val c = client ?: return
        Thread { c.ptzPresetClear(slotId) }.apply { isDaemon = true }.start()
    }

    private fun handleStatus(msg: String) {
        val connected = msg.startsWith("Streaming")
        if (connected) {
            lastStatTimeMs = System.currentTimeMillis()
            framesWindow = 0
            bytesWindow = 0L
        } else {
            synchronized(videoLock) {
                seenKeyframe = false
            }
        }
        val c = client
        _telemetry.value = _telemetry.value.copy(
            statusText = msg,
            isConnected = connected,
            width = c?.frameWidth ?: _telemetry.value.width,
            height = c?.frameHeight ?: _telemetry.value.height
        )
    }

    private fun handleFrame(frame: V380Frame) {
        if (!running) return
        bytesWindow += frame.payload.size

        if (frame.isAudio) {
            handleAudioFrame(frame)
            return
        }

        val resolvedCodec = when {
            frame.codec != VideoCodec.UNKNOWN -> frame.codec
            frame.rawType == 0x28 || frame.rawType == 0x29 -> VideoCodec.H265
            else -> VideoCodec.H264
        }

        synchronized(videoLock) {
            if (frame.isKeyframe) {
                lastKeyframePayload = frame.payload
                activeCodecType = resolvedCodec
            }

            if (!seenKeyframe) {
                if (!frame.isKeyframe) return
                seenKeyframe = true
            }

            if (activeCodecType != resolvedCodec) {
                activeCodecType = resolvedCodec
                releaseVideoCodecUnlocked()
            }

            val surface = activeSurface
            if (surface != null && surface.isValid) {
                ensureVideoCodecUnlocked(resolvedCodec)
                queueNalToCodecUnlocked(frame.payload)
            }
        }

        synchronized(recordLock) {
            activeLiveMuxer?.writeVideoFrame(frame)
        }

        framesWindow++
        updateTelemetry(resolvedCodec)
    }

    private fun ensureVideoCodecUnlocked(codecType: VideoCodec) {
        if (videoCodec != null) return
        val surface = activeSurface ?: return
        if (!surface.isValid) return

        try {
            val w = (client?.frameWidth ?: 1280).coerceIn(160, 3840)
            val h = (client?.frameHeight ?: 720).coerceIn(120, 2160)
            val format = MediaFormat.createVideoFormat(codecType.mimeType, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)

            val mc = MediaCodec.createDecoderByType(codecType.mimeType)
            mc.configure(format, surface, null, 0)
            mc.start()
            videoCodec = mc
            ptsUs = 0L
        } catch (_: Exception) {
            releaseVideoCodecUnlocked()
        }
    }

    private fun queueNalToCodecUnlocked(nalPayload: ByteArray) {
        val mc = videoCodec ?: return
        try {
            val inIdx = mc.dequeueInputBuffer(8_000L)
            if (inIdx >= 0) {
                val inBuf = mc.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val len = minOf(nalPayload.size, inBuf.remaining())
                    inBuf.put(nalPayload, 0, len)
                    ptsUs += 50_000L // ~20 fps nominal step
                    mc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = mc.dequeueOutputBuffer(info, 0L)
            var loopGuard = 0
            while (outIdx >= 0 && loopGuard < 8) {
                mc.releaseOutputBuffer(outIdx, true)
                outIdx = mc.dequeueOutputBuffer(info, 0L)
                loopGuard++
            }
        } catch (_: Exception) {
            releaseVideoCodecUnlocked()
            seenKeyframe = false
        }
    }

    private fun releaseVideoCodecUnlocked() {
        val mc = videoCodec
        videoCodec = null
        if (mc != null) {
            try { mc.stop() } catch (_: Exception) {}
            try { mc.release() } catch (_: Exception) {}
        }
    }

    private fun handleAudioFrame(frame: V380Frame) {
        synchronized(audioLock) {
            when (frame.audioCodec) {
                "aac" -> decodeAndPlayAacUnlocked(frame.payload)
                "alaw" -> {
                    val pcm = V380AudioDecoder.decodeAlawToPcm16(frame.payload)
                    playPcm16Unlocked(pcm, 8000)
                }
                else -> {
                    val pcm = V380AudioDecoder.decodeAdpcmToPcm16(frame.payload)
                    playPcm16Unlocked(pcm, 8000)
                }
            }
        }
    }

    private fun decodeAndPlayAacUnlocked(adtsPayload: ByteArray) {
        if (adtsPayload.size < 8) return
        // Verify ADTS syncword 0xFFFx
        if ((adtsPayload[0].toInt() and 0xFF) != 0xFF || (adtsPayload[1].toInt() and 0xF0) != 0xF0) {
            return
        }

        val protectionAbsent = adtsPayload[1].toInt() and 0x01
        val headerLen = if (protectionAbsent != 0) 7 else 9
        if (adtsPayload.size <= headerLen) return

        val profileObjType = ((adtsPayload[2].toInt() and 0xC0) ushr 6) + 1
        val freqIdx = (adtsPayload[2].toInt() and 0x3C) ushr 2
        val chanCfg = ((adtsPayload[2].toInt() and 0x01) shl 2) or ((adtsPayload[3].toInt() and 0xC0) ushr 6)

        val freqTable = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
        val sampleRate = if (freqIdx in freqTable.indices) freqTable[freqIdx] else 8000
        val channels = if (chanCfg in 1..2) chanCfg else 1

        if (aacCodec == null || aacSampleRate != sampleRate) {
            releaseAacCodecUnlocked()
            try {
                val csd0 = ByteArray(2)
                csd0[0] = ((profileObjType shl 3) or (freqIdx ushr 1)).toByte()
                csd0[1] = (((freqIdx and 0x01) shl 7) or (channels shl 3)).toByte()

                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))

                val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                mc.configure(fmt, null, null, 0)
                mc.start()
                aacCodec = mc
                aacSampleRate = sampleRate
            } catch (_: Exception) {
                releaseAacCodecUnlocked()
                return
            }
        }

        val mc = aacCodec ?: return
        try {
            val rawAac = adtsPayload.copyOfRange(headerLen, adtsPayload.size)
            val inIdx = mc.dequeueInputBuffer(5_000L)
            if (inIdx >= 0) {
                val inBuf = mc.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val len = minOf(rawAac.size, inBuf.remaining())
                    inBuf.put(rawAac, 0, len)
                    mc.queueInputBuffer(inIdx, 0, len, System.nanoTime() / 1000L, 0)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = mc.dequeueOutputBuffer(info, 0L)
            while (outIdx >= 0) {
                val outBuf = mc.getOutputBuffer(outIdx)
                if (outBuf != null && info.size >= 2) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val pcmBytes = ByteArray(info.size)
                    outBuf.get(pcmBytes)
                    val shortCount = pcmBytes.size / 2
                    val shorts = ShortArray(shortCount)
                    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    playPcm16Unlocked(shorts, aacSampleRate)
                }
                mc.releaseOutputBuffer(outIdx, false)
                outIdx = mc.dequeueOutputBuffer(info, 0L)
            }
        } catch (_: Exception) {
            releaseAacCodecUnlocked()
        }
    }

    private fun playPcm16Unlocked(pcmSamples: ShortArray, sampleRate: Int) {
        if (pcmSamples.isEmpty()) return
        val level = computePcmLevel(pcmSamples)
        val muted = _telemetry.value.isMuted
        _telemetry.value = _telemetry.value.copy(audioLevel = if (muted) 0f else level)
        if (muted) return

        try {
            if (audioTrack == null || audioTrackSampleRate != sampleRate) {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sampleRate / 2)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                audioTrack = track
                audioTrackSampleRate = sampleRate
            }

            val track = audioTrack ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcmSamples, 0, pcmSamples.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {
        }
    }

    private fun releaseAacCodecUnlocked() {
        val mc = aacCodec
        aacCodec = null
        if (mc != null) {
            try { mc.stop() } catch (_: Exception) {}
            try { mc.release() } catch (_: Exception) {}
        }
    }

    private fun releaseAudioUnlocked() {
        releaseAacCodecUnlocked()
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun computePcmLevel(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        val raw = AcousticLevelDetector.computeLevel(samples, "live_${config.camId}")
        smoothedAudioLevel = if (raw >= smoothedAudioLevel) {
            raw // Instant attack
        } else {
            smoothedAudioLevel * 0.82f + raw * 0.18f // Smooth ~200ms release ballistics
        }
        return smoothedAudioLevel.coerceIn(0f, 1f)
    }

    private fun updateTelemetry(codecType: VideoCodec) {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - lastStatTimeMs) / 1000.0f
        if (elapsedSec >= 0.9f) {
            val fps = framesWindow / elapsedSec
            val kbps = (bytesWindow * 8f / 1000f) / elapsedSec
            framesWindow = 0
            bytesWindow = 0L
            lastStatTimeMs = now

            val c = client
            _telemetry.value = _telemetry.value.copy(
                fps = fps,
                kbps = kbps,
                codecLabel = codecType.label,
                width = c?.frameWidth ?: _telemetry.value.width,
                height = c?.frameHeight ?: _telemetry.value.height
            )
        }
    }
}
