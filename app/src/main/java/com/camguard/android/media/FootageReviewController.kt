package com.camguard.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import com.camguard.android.model.CameraConfig
import com.camguard.android.protocol.CameraAudioDecoder
import com.camguard.android.protocol.CameraClient
import com.camguard.android.protocol.CameraFrame
import com.camguard.android.protocol.RecordingSegment
import com.camguard.android.protocol.VideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

enum class FootageSourceMode {
    SD_CARD,
    LOCAL_CAPTURES
}

enum class PreviewCanvasMode {
    READING_SD,
    SELECT_CLIP,
    NO_RECORDINGS,
    LOADING_CLIP,
    PLAYING_VIDEO,
    VIEWING_SNAPSHOT,
    DOWNLOADING,
    ERROR
}

data class FootageReviewState(
    val sourceMode: FootageSourceMode = FootageSourceMode.SD_CARD,
    val year: Int = 2026,
    val month: Int = 1,
    val day: Int = 1,
    val formattedDateFull: String = "",
    val canvasMode: PreviewCanvasMode = PreviewCanvasMode.READING_SD,
    val statusSummary: String = "Reading SD card...",
    val segments: List<RecordingSegment> = emptyList(),
    val localBackupPaths: Map<String, String> = emptyMap(), // key = "${segId}_${startTs}"
    val localCaptures: List<LocalCaptureItem> = emptyList(),
    val filterKind: String = "all", // "all" | "anomaly" | "normal"
    val sortNewestFirst: Boolean = true,
    val selectedSegmentKey: String? = null,
    val selectedLocalPath: String? = null,
    val viewingSnapshotPath: String? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isLocalPlayback: Boolean = false,
    val playbackSpeed: Float = 1f,
    val speedLockedTo1x: Boolean = true,
    val isMuted: Boolean = false,
    val audioLevel: Float = 0f,
    val playheadEpochSec: Long = 0L,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
    val downloadLabel: String = "",
    val scrollToSegmentKeyTrigger: Pair<String, Long>? = null
) {
    fun segmentKey(seg: RecordingSegment): String = "${seg.segId}_${seg.startTs}"

    fun isBackedUp(seg: RecordingSegment): Boolean = localBackupPaths.containsKey(segmentKey(seg))

    val filteredAndSortedSegments: List<RecordingSegment>
        get() {
            val filtered = when (filterKind) {
                "anomaly" -> segments.filter { it.isAnomaly }
                "normal" -> segments.filter { !it.isAnomaly }
                else -> segments
            }
            return if (sortNewestFirst) {
                filtered.sortedByDescending { it.startTs }
            } else {
                filtered.sortedBy { it.startTs }
            }
        }

    val dayStartEpochUtc: Long
        get() {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis / 1000L
        }
}

class FootageReviewController(
    private val appContext: Context,
    initialCamera: CameraConfig
) {
    @Volatile
    var camera: CameraConfig = initialCamera
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state: MutableStateFlow<FootageReviewState>
    val state: StateFlow<FootageReviewState>

    private val listGeneration = AtomicLong(0L)
    private val playbackGeneration = AtomicLong(0L)
    private var debounceJob: Job? = null

    @Volatile private var activeSdClient: CameraClient? = null
    @Volatile private var activePlaybackStop: AtomicBoolean? = null
    @Volatile private var activeDownloadStop: AtomicBoolean? = null
    @Volatile private var activeDownloadClient: CameraClient? = null

    // Hardware Video Decoder & Surface (shared between SD stream and local .mp4 playback)
    private val videoLock = Any()
    private var activeSurface: Surface? = null
    private var streamVideoCodec: MediaCodec? = null
    private var streamCodecType: VideoCodec = VideoCodec.UNKNOWN
    private var seenKeyframe = false
    private var lastKeyframePayload: ByteArray? = null
    private var streamPtsUs = 0L

    // Persistent AudioTrack across clip switches (flushed rather than recreated on UI thread)
    private val audioLock = Any()
    private var persistentAudioTrack: AudioTrack? = null
    private var persistentSampleRate: Int = 0
    private var aacCodec: MediaCodec? = null
    private var aacSampleRate: Int = 8000
    private var smoothedAudioLevel: Float = 0f

    init {
        val nowCal = Calendar.getInstance()
        val y = nowCal.get(Calendar.YEAR)
        val m = nowCal.get(Calendar.MONTH) + 1
        val d = nowCal.get(Calendar.DAY_OF_MONTH)
        val initial = FootageReviewState(
            year = y,
            month = m,
            day = d,
            formattedDateFull = formatFullDate(y, m, d),
            canvasMode = PreviewCanvasMode.READING_SD,
            statusSummary = "Reading SD card..."
        )
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        refreshPlayheadToDayStart(y, m, d)
        loadCurrentDateSdIndex()
        refreshLocalCaptures()
    }

    companion object {
        fun formatFullDate(year: Int, month: Int, day: Int): String {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
                set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            val sdf = SimpleDateFormat("MMMM dd, yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(cal.time)
        }

        fun formatEpochDateTime12hUtc(epochSec: Long): String {
            val sdf = SimpleDateFormat("MMMM dd, yyyy  hh:mm a", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(epochSec * 1000L))
        }
    }

    private fun refreshPlayheadToDayStart(year: Int, month: Int, day: Int) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis / 1000L
        _state.value = _state.value.copy(playheadEpochSec = dayStart)
    }

    fun setSourceMode(mode: FootageSourceMode) {
        if (_state.value.sourceMode == mode) return
        stopPlaybackNonBlocking(resetToIdle = true)
        _state.value = _state.value.copy(
            sourceMode = mode,
            viewingSnapshotPath = null
        )
        if (mode == FootageSourceMode.LOCAL_CAPTURES) {
            refreshLocalCaptures()
            val caps = _state.value.localCaptures
            _state.value = _state.value.copy(
                canvasMode = if (caps.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP,
                statusSummary = if (caps.isEmpty()) "No local captures yet" else "${caps.size} local capture(s)"
            )
        } else {
            val segs = _state.value.segments
            _state.value = _state.value.copy(
                canvasMode = if (segs.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP,
                statusSummary = if (segs.isEmpty()) "No recordings for this date" else "${segs.size} clips (${segs.count { it.isAnomaly }} anomalies)"
            )
        }
    }

    fun setFilterKind(kind: String) {
        _state.value = _state.value.copy(filterKind = kind)
    }

    fun toggleSortOrder() {
        val next = !_state.value.sortNewestFirst
        _state.value = _state.value.copy(sortNewestFirst = next)
    }

    fun navigateDay(deltaDays: Int) {
        val cur = _state.value
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, cur.year)
            set(Calendar.MONTH, cur.month - 1)
            set(Calendar.DAY_OF_MONTH, cur.day)
            set(Calendar.HOUR_OF_DAY, 12)
            add(Calendar.DAY_OF_MONTH, deltaDays)
        }
        selectDate(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun jumpToToday() {
        val now = Calendar.getInstance()
        selectDate(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun selectDate(year: Int, month: Int, day: Int) {
        stopPlaybackNonBlocking(resetToIdle = false)
        val fullStr = formatFullDate(year, month, day)
        _state.value = _state.value.copy(
            year = year,
            month = month,
            day = day,
            formattedDateFull = fullStr,
            selectedSegmentKey = null,
            isPlaying = false,
            isPaused = false
        )
        refreshPlayheadToDayStart(year, month, day)
        loadCurrentDateSdIndex()
    }

    fun refreshLocalCaptures() {
        val items = FootageBackupStore.listLocalCaptures(appContext, camera)
        _state.value = _state.value.copy(localCaptures = items)
    }

    fun deleteLocalCapture(item: LocalCaptureItem) {
        if (_state.value.selectedLocalPath == item.file.absolutePath) {
            stopPlaybackNonBlocking(resetToIdle = true)
        }
        try { item.file.delete() } catch (_: Exception) {}
        refreshLocalCaptures()
        if (_state.value.sourceMode == FootageSourceMode.LOCAL_CAPTURES) {
            val caps = _state.value.localCaptures
            _state.value = _state.value.copy(
                canvasMode = if (caps.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP,
                statusSummary = if (caps.isEmpty()) "No local captures yet" else "${caps.size} local capture(s)"
            )
        }
    }

    /*
     * Queries the camera's SD card for the active calendar date (`READING SD CARD ...`),
     * merging with any locally backed-up SD segments (`backups/{cam}/{YYYY-MM-DD}/`) so
     * backed-up SD clips always remain visible and playable inside `On-Camera SD Card`.
     */
    fun loadCurrentDateSdIndex() {
        val gen = listGeneration.incrementAndGet()
        val y = _state.value.year
        val m = _state.value.month
        val d = _state.value.day
        val dateKey = FootageBackupStore.dateKeyForYmd(y, m, d)

        // Cancel any previous list client in background
        val prevClient = activeSdClient
        if (prevClient != null) {
            Thread { try { prevClient.stop() } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        }

        // First load any locally backed up segments for this date immediately
        val backedUpSegs = FootageBackupStore.loadBackedUpSegmentsForDate(appContext, camera, dateKey)
        val initialMap = HashMap<String, String>()
        for (s in backedUpSegs) {
            val f = FootageBackupStore.findSegmentBackupFile(appContext, camera, s)
            if (f != null) {
                initialMap["${s.segId}_${s.startTs}"] = f.absolutePath
            }
        }

        _state.value = _state.value.copy(
            canvasMode = PreviewCanvasMode.READING_SD,
            statusSummary = "Reading SD card...",
            segments = backedUpSegs,
            localBackupPaths = initialMap
        )

        scope.launch {
            val client = CameraClient(
                ip = camera.ip,
                port = camera.port,
                deviceId = camera.deviceId,
                username = camera.username,
                password = camera.password
            )
            activeSdClient = client

            val (ok, msg, remoteSegs) = client.listSdRecordings(y, m, d)
            if (listGeneration.get() != gen) return@launch

            // Merge remote SD segments + local backed-up SD segments deterministically
            val mergedByKey = LinkedHashMap<String, RecordingSegment>()
            for (s in remoteSegs) {
                mergedByKey["${s.segId}_${s.startTs}"] = s
            }
            for (s in backedUpSegs) {
                val k = "${s.segId}_${s.startTs}"
                if (!mergedByKey.containsKey(k)) {
                    mergedByKey[k] = s
                }
            }

            val allSegments = mergedByKey.values.sortedByDescending { it.startTs }
            val backupPaths = HashMap<String, String>()
            for (s in allSegments) {
                val f = FootageBackupStore.findSegmentBackupFile(appContext, camera, s)
                if (f != null) {
                    backupPaths["${s.segId}_${s.startTs}"] = f.absolutePath
                }
            }

            val firstSeg = allSegments.firstOrNull()
            val curState = _state.value
            val nextPlayhead = if (firstSeg != null && curState.selectedSegmentKey == null) {
                firstSeg.startTs
            } else {
                curState.playheadEpochSec
            }

            val nextCanvas = when {
                curState.isDownloading -> PreviewCanvasMode.DOWNLOADING
                curState.isPlaying -> PreviewCanvasMode.PLAYING_VIDEO
                allSegments.isNotEmpty() -> PreviewCanvasMode.SELECT_CLIP
                ok -> PreviewCanvasMode.NO_RECORDINGS
                else -> PreviewCanvasMode.NO_RECORDINGS
            }

            val summary = when {
                allSegments.isNotEmpty() -> {
                    val anom = allSegments.count { it.isAnomaly }
                    val localCnt = backupPaths.size
                    if (localCnt > 0) {
                        "${allSegments.size} clips ($anom anomalies • $localCnt local)"
                    } else {
                        "${allSegments.size} clips ($anom anomalies)"
                    }
                }
                ok -> "No recordings on SD card for this date"
                else -> msg
            }

            _state.value = curState.copy(
                segments = allSegments,
                localBackupPaths = backupPaths,
                playheadEpochSec = nextPlayhead,
                canvasMode = nextCanvas,
                statusSummary = summary
            )
        }
    }

    /*
     * Immediately cancels any pending 120ms debounced SD playback timer (e.g., when the user
     * opens the long-press / backup menu so a background stream never collides with backup).
     */
    fun cancelPendingDebouncedPlayback() {
        debounceJob?.cancel()
        debounceJob = null
    }

    /*
     * Selects an SD card clip (from the clip list or timeline tap) and starts playback.
     * - If a local `.mp4` backup exists (and `forceSdStream == false`), plays the local `.mp4`
     *   immediately and unlocks all speeds (`1x, 2x, 4x, 8x, 16x`).
     * - If streaming from the camera SD card, debounces by `120ms` and locks speed to `1x`.
     */
    fun selectSdSegment(
        segment: RecordingSegment,
        startAtEpochSec: Long? = null,
        fromTimeline: Boolean = false,
        forceSdStream: Boolean = false
    ) {
        cancelPendingDebouncedPlayback()
        val key = "${segment.segId}_${segment.startTs}"
        val targetTs = (startAtEpochSec ?: segment.startTs).coerceIn(segment.startTs, max(segment.startTs, segment.endTs - 1L))
        val localPath = if (!forceSdStream) _state.value.localBackupPaths[key] else null
        val hasLocal = !localPath.isNullOrBlank() && File(localPath).isFile

        val nextSpeed = if (hasLocal) _state.value.playbackSpeed else 1f
        val scrollTrigger = if (fromTimeline) (key to System.currentTimeMillis()) else _state.value.scrollToSegmentKeyTrigger

        _state.value = _state.value.copy(
            sourceMode = FootageSourceMode.SD_CARD,
            selectedSegmentKey = key,
            selectedLocalPath = null,
            viewingSnapshotPath = null,
            playheadEpochSec = targetTs,
            isLocalPlayback = hasLocal,
            speedLockedTo1x = !hasLocal,
            playbackSpeed = nextSpeed,
            scrollToSegmentKeyTrigger = scrollTrigger
        )

        if (hasLocal && localPath != null) {
            startLocalMp4Playback(
                mp4File = File(localPath),
                segment = segment,
                startEpochSec = targetTs
            )
        } else {
            // Debounce rapid taps across un-downloaded SD card clips by 120ms
            debounceJob = scope.launch {
                delay(120L)
                startRemoteSdPlayback(segment, targetTs)
            }
        }
    }

    /*
     * Called when the user taps or scrubs on the 24-hour timeline at `targetEpochSec`.
     * Finds the clip covering (or nearest to) `targetEpochSec`, highlights & scrolls to it in the
     * clip list without triggering duplicate playback calls, and starts playback at `targetEpochSec`.
     */
    fun seekTimelineToEpochSec(targetEpochSec: Long, commitPlayback: Boolean) {
        val segs = _state.value.filteredAndSortedSegments.ifEmpty { _state.value.segments }
        if (segs.isEmpty()) {
            _state.value = _state.value.copy(playheadEpochSec = targetEpochSec)
            return
        }

        // Find exact covering segment or nearest segment
        val covering = segs.firstOrNull { targetEpochSec in it.startTs..it.endTs }
            ?: segs.minByOrNull { seg ->
                when {
                    targetEpochSec < seg.startTs -> seg.startTs - targetEpochSec
                    targetEpochSec > seg.endTs -> targetEpochSec - seg.endTs
                    else -> 0L
                }
            } ?: return

        val clampedTs = targetEpochSec.coerceIn(covering.startTs, max(covering.startTs, covering.endTs - 1L))
        val key = "${covering.segId}_${covering.startTs}"

        if (!commitPlayback) {
            // Scrubbing preview: update playhead needle & selected card highlight without opening socket yet
            cancelPendingDebouncedPlayback()
            _state.value = _state.value.copy(
                selectedSegmentKey = key,
                playheadEpochSec = clampedTs,
                scrollToSegmentKeyTrigger = key to System.currentTimeMillis()
            )
        } else {
            selectSdSegment(
                segment = covering,
                startAtEpochSec = clampedTs,
                fromTimeline = true
            )
        }
    }

    fun selectLocalCapture(item: LocalCaptureItem) {
        cancelPendingDebouncedPlayback()
        if (!item.isVideo) {
            stopPlaybackNonBlocking(resetToIdle = false)
            _state.value = _state.value.copy(
                sourceMode = FootageSourceMode.LOCAL_CAPTURES,
                selectedLocalPath = item.file.absolutePath,
                viewingSnapshotPath = item.file.absolutePath,
                canvasMode = PreviewCanvasMode.VIEWING_SNAPSHOT,
                isPlaying = false,
                isPaused = false,
                isLocalPlayback = true,
                speedLockedTo1x = false,
                statusSummary = "Viewing snapshot: ${item.file.name}"
            )
            return
        }

        _state.value = _state.value.copy(
            sourceMode = FootageSourceMode.LOCAL_CAPTURES,
            selectedLocalPath = item.file.absolutePath,
            viewingSnapshotPath = null,
            isLocalPlayback = true,
            speedLockedTo1x = false
        )
        startLocalMp4Playback(mp4File = item.file, segment = null, startEpochSec = 0L)
    }

    fun togglePauseResume() {
        val cur = _state.value
        if (!cur.isPlaying) {
            // If a segment is selected, start playing it
            val seg = cur.segments.firstOrNull { "${it.segId}_${it.startTs}" == cur.selectedSegmentKey }
            if (seg != null) {
                selectSdSegment(seg, startAtEpochSec = cur.playheadEpochSec)
            }
            return
        }
        val nextPaused = !cur.isPaused
        _state.value = cur.copy(isPaused = nextPaused)
        if (nextPaused) {
            flushAudioTrackNonBlocking()
        }
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        val cur = _state.value
        if (cur.speedLockedTo1x && speed > 1.01f) {
            return false
        }
        _state.value = cur.copy(playbackSpeed = speed.coerceIn(1f, 16f))
        return true
    }

    fun toggleMute(): Boolean {
        val next = !_state.value.isMuted
        _state.value = _state.value.copy(
            isMuted = next,
            audioLevel = if (next) 0f else _state.value.audioLevel
        )
        if (next) {
            flushAudioTrackNonBlocking()
        }
        return next
    }

    fun stopPlaybackNonBlocking(resetToIdle: Boolean = true) {
        cancelPendingDebouncedPlayback()
        playbackGeneration.incrementAndGet()
        val prevStop = activePlaybackStop
        activePlaybackStop = null
        prevStop?.set(true)

        val prevClient = activeSdClient
        if (prevClient != null) {
            Thread { try { prevClient.stop() } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        }

        Thread {
            synchronized(videoLock) {
                releaseStreamVideoCodecUnlocked()
            }
            flushAudioTrackNonBlocking()
        }.apply { isDaemon = true }.start()

        val cur = _state.value
        val idleMode = when {
            cur.isDownloading -> PreviewCanvasMode.DOWNLOADING
            cur.sourceMode == FootageSourceMode.LOCAL_CAPTURES -> {
                if (cur.localCaptures.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP
            }
            cur.segments.isEmpty() -> PreviewCanvasMode.NO_RECORDINGS
            else -> PreviewCanvasMode.SELECT_CLIP
        }

        _state.value = cur.copy(
            isPlaying = false,
            isPaused = false,
            audioLevel = 0f,
            canvasMode = if (resetToIdle) idleMode else cur.canvasMode
        )
    }

    private fun startRemoteSdPlayback(segment: RecordingSegment, startEpochSec: Long) {
        val gen = playbackGeneration.incrementAndGet()
        val prevStop = activePlaybackStop
        prevStop?.set(true)
        val stopFlag = AtomicBoolean(false)
        activePlaybackStop = stopFlag

        val prevClient = activeSdClient
        if (prevClient != null) {
            Thread { try { prevClient.stop() } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        }

        synchronized(videoLock) {
            seenKeyframe = false
            releaseStreamVideoCodecUnlocked()
        }
        flushAudioTrackNonBlocking()

        _state.value = _state.value.copy(
            canvasMode = PreviewCanvasMode.LOADING_CLIP,
            isPlaying = true,
            isPaused = false,
            isLocalPlayback = false,
            speedLockedTo1x = true,
            playbackSpeed = 1f,
            playheadEpochSec = startEpochSec,
            statusSummary = "Streaming SD (${segment.startTime12h})"
        )

        scope.launch {
            var clientRef: CameraClient? = null
            var baseFrameTsMs: Long = -1L
            var baseWallMs: Long = 0L
            var videoFramesShown = 0L

            val client = CameraClient(
                ip = camera.ip,
                port = camera.port,
                deviceId = camera.deviceId,
                username = camera.username,
                password = camera.password,
                enableAudio = true,
                onFrame = { frame ->
                    if (stopFlag.get() || playbackGeneration.get() != gen) {
                        clientRef?.stop()
                        return@CameraClient
                    }
                    // Wait while paused
                    var wasPaused = false
                    while (_state.value.isPaused && !stopFlag.get() && playbackGeneration.get() == gen) {
                        wasPaused = true
                        try { Thread.sleep(30L) } catch (_: Exception) { break }
                    }
                    if (wasPaused) {
                        baseFrameTsMs = -1L
                    }
                    if (!stopFlag.get() && playbackGeneration.get() == gen) {
                        // Pace video frames at 1x real-time speed
                        val fts = frame.timestamp
                        if (!frame.isAudio && fts > 0L) {
                            val nowWall = System.currentTimeMillis()
                            if (baseFrameTsMs < 0L || fts < baseFrameTsMs) {
                                baseFrameTsMs = fts
                                baseWallMs = nowWall
                            } else {
                                val targetWall = baseWallMs + (fts - baseFrameTsMs)
                                val waitMs = targetWall - nowWall
                                if (waitMs > 3000L || waitMs < -1000L) {
                                    baseFrameTsMs = fts
                                    baseWallMs = nowWall
                                } else if (waitMs > 2L) {
                                    var rem = waitMs
                                    while (rem > 0L && !stopFlag.get() && playbackGeneration.get() == gen) {
                                        val step = min(rem, 30L)
                                        try { Thread.sleep(step) } catch (_: Exception) { break }
                                        rem -= step
                                    }
                                }
                            }
                        }
                        if (!frame.isAudio) {
                            videoFramesShown++
                            val c = clientRef
                            if (c != null && (c.frameWidth != _state.value.videoWidth || c.frameHeight != _state.value.videoHeight)) {
                                _state.value = _state.value.copy(
                                    videoWidth = c.frameWidth.coerceIn(160, 4096),
                                    videoHeight = c.frameHeight.coerceIn(120, 4096)
                                )
                            }
                        }
                        handleIncomingSdFrame(frame, gen)
                    }
                }
            )
            clientRef = client
            activeSdClient = client

            val (ok, msg) = client.streamSdSegment(
                segment = segment,
                startTs = startEpochSec,
                onProgressTs = { tsSec ->
                    if (!stopFlag.get() && playbackGeneration.get() == gen) {
                        if (tsSec in segment.startTs..segment.endTs) {
                            _state.value = _state.value.copy(playheadEpochSec = tsSec)
                        } else if (tsSec > segment.endTs && videoFramesShown > 0L) {
                            client.stop()
                        }
                    }
                }
            )

            if (playbackGeneration.get() == gen && !stopFlag.get()) {
                _state.value = _state.value.copy(
                    isPlaying = false,
                    isPaused = false,
                    audioLevel = 0f,
                    canvasMode = if (_state.value.isDownloading) PreviewCanvasMode.DOWNLOADING else PreviewCanvasMode.SELECT_CLIP,
                    statusSummary = if (ok) "Finished clip (${segment.startTime12h})" else msg
                )
            }
        }
    }

    private fun handleIncomingSdFrame(frame: CameraFrame, gen: Long) {
        if (playbackGeneration.get() != gen) return
        if (frame.isAudio) {
            handleSdAudioFrame(frame)
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
                streamCodecType = resolvedCodec
            }
            if (!seenKeyframe) {
                if (!frame.isKeyframe) return
                seenKeyframe = true
            }
            if (streamCodecType != resolvedCodec) {
                streamCodecType = resolvedCodec
                releaseStreamVideoCodecUnlocked()
            }
            val s = activeSurface
            if (s != null && s.isValid) {
                ensureStreamVideoCodecUnlocked(resolvedCodec)
                queueNalToStreamCodecUnlocked(frame.payload, frame.frameRate)
            }
        }

        if (_state.value.canvasMode != PreviewCanvasMode.PLAYING_VIDEO && !_state.value.isDownloading) {
            _state.value = _state.value.copy(canvasMode = PreviewCanvasMode.PLAYING_VIDEO)
        }
    }

    /*
     * Plays a local `.mp4` file (either a backed-up SD card segment or a manual live recording)
     * using `MediaExtractor` + `MediaCodec` rendered directly onto `activeSurface`, supporting
     * variable speed (`1x`, `2x`, `4x`, `8x`, `16x`) and pause/resume.
     */
    private fun startLocalMp4Playback(
        mp4File: File,
        segment: RecordingSegment?,
        startEpochSec: Long
    ) {
        if (!mp4File.isFile || mp4File.length() <= 512L) return
        val gen = playbackGeneration.incrementAndGet()
        val prevStop = activePlaybackStop
        prevStop?.set(true)
        val stopFlag = AtomicBoolean(false)
        activePlaybackStop = stopFlag

        val prevClient = activeSdClient
        if (prevClient != null) {
            Thread { try { prevClient.stop() } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        }

        synchronized(videoLock) {
            releaseStreamVideoCodecUnlocked()
        }
        flushAudioTrackNonBlocking()

        _state.value = _state.value.copy(
            canvasMode = PreviewCanvasMode.PLAYING_VIDEO,
            isPlaying = true,
            isPaused = false,
            isLocalPlayback = true,
            speedLockedTo1x = false,
            statusSummary = if (segment != null) {
                "Playing Local Backup (${segment.startTime12h})"
            } else {
                "Playing Local Capture (${mp4File.name})"
            }
        )

        scope.launch {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(mp4File.absolutePath)
                var videoTrack = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrack = i
                        format = fmt
                        break
                    }
                }
                if (videoTrack < 0 || format == null) return@launch
                extractor.selectTrack(videoTrack)

                val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1280
                val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 720
                _state.value = _state.value.copy(videoWidth = w, videoHeight = h)

                if (segment != null && startEpochSec > segment.startTs) {
                    val offsetUs = (startEpochSec - segment.startTs) * 1_000_000L
                    extractor.seekTo(offsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                // Wait briefly if Surface is still attaching
                var waitTries = 0
                while (synchronized(videoLock) { activeSurface == null || !activeSurface!!.isValid } &&
                    waitTries < 25 && !stopFlag.get() && playbackGeneration.get() == gen
                ) {
                    delay(20L)
                    waitTries++
                }

                val surface = synchronized(videoLock) { activeSurface } ?: return@launch
                if (!surface.isValid) return@launch

                val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
                val mc = MediaCodec.createDecoderByType(mime)
                mc.configure(format, surface, null, 0)
                mc.start()
                decoder = mc

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var anchorWallMs = System.currentTimeMillis()
                var anchorPtsUs = -1L
                var lastSpeed = _state.value.playbackSpeed

                while (!outputDone && !stopFlag.get() && playbackGeneration.get() == gen) {
                    if (_state.value.isPaused) {
                        delay(30L)
                        anchorWallMs = System.currentTimeMillis()
                        anchorPtsUs = -1L
                        continue
                    }

                    val curSpeed = _state.value.playbackSpeed.coerceIn(1f, 16f)
                    if (curSpeed != lastSpeed) {
                        lastSpeed = curSpeed
                        anchorWallMs = System.currentTimeMillis()
                        anchorPtsUs = -1L
                    }

                    if (!inputDone) {
                        val inIdx = mc.dequeueInputBuffer(8_000L)
                        if (inIdx >= 0) {
                            val inBuf = mc.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize < 0) {
                                mc.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                mc.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = mc.dequeueOutputBuffer(info, 8_000L)
                    if (outIdx >= 0) {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        val ptsUs = info.presentationTimeUs
                        if (anchorPtsUs < 0L) {
                            anchorPtsUs = ptsUs
                            anchorWallMs = System.currentTimeMillis()
                        } else {
                            val mediaDeltaMs = ((ptsUs - anchorPtsUs) / 1000L).coerceAtLeast(0L)
                            val targetWallDeltaMs = (mediaDeltaMs / curSpeed).toLong()
                            val elapsedWallMs = System.currentTimeMillis() - anchorWallMs
                            val sleepMs = (targetWallDeltaMs - elapsedWallMs).coerceIn(0L, 120L)
                            if (sleepMs > 2L) {
                                delay(sleepMs)
                            }
                        }

                        mc.releaseOutputBuffer(outIdx, true)

                        if (segment != null) {
                            val curSec = (segment.startTs + (ptsUs / 1_000_000L)).coerceIn(segment.startTs, segment.endTs)
                            _state.value = _state.value.copy(playheadEpochSec = curSec)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
                if (playbackGeneration.get() == gen && !stopFlag.get()) {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        isPaused = false,
                        canvasMode = if (_state.value.isDownloading) PreviewCanvasMode.DOWNLOADING else PreviewCanvasMode.SELECT_CLIP,
                        statusSummary = "Local playback complete"
                    )
                }
            }
        }
    }

    /*
     * Downloads / backs up a single SD card clip into `backups/{cam}/{YYYY-MM-DD}/SD_{cam}_{segId}_{startTs}_{endTs}.mp4`
     * with the emerald-green circular downloading animation (`DOWNLOADING • {pct}%`), and unlocks
     * all playback speeds (`1x..16x`) immediately upon completion.
     */
    fun backupSingleSegment(segment: RecordingSegment) {
        if (_state.value.isDownloading) return
        cancelPendingDebouncedPlayback()
        stopPlaybackNonBlocking(resetToIdle = false)

        val stopFlag = AtomicBoolean(false)
        activeDownloadStop = stopFlag

        val key = "${segment.segId}_${segment.startTs}"
        _state.value = _state.value.copy(
            selectedSegmentKey = key,
            isDownloading = true,
            downloadPercent = 0,
            downloadLabel = "Backing up ${segment.startTime12h}...",
            canvasMode = PreviewCanvasMode.DOWNLOADING,
            statusSummary = "Downloading clip to phone (.MP4)..."
        )

        scope.launch {
            val outFile = FootageBackupStore.getSegmentBackupFile(appContext, camera, segment)
            val ok = downloadSegmentToFile(
                segment = segment,
                outFile = outFile,
                stopFlag = stopFlag,
                onPercent = { pct ->
                    _state.value = _state.value.copy(
                        downloadPercent = pct,
                        canvasMode = PreviewCanvasMode.DOWNLOADING
                    )
                }
            )

            activeDownloadStop = null
            activeDownloadClient = null

            if (ok && outFile.isFile && outFile.length() > 512L) {
                FootageBackupStore.saveSegmentBackupMeta(appContext, camera, listOf(segment))
                FootageBackupStore.exportMp4ToPublicMovies(appContext, outFile)

                val updatedMap = HashMap(_state.value.localBackupPaths)
                updatedMap[key] = outFile.absolutePath

                val isStillSelected = _state.value.selectedSegmentKey == key
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadPercent = 100,
                    localBackupPaths = updatedMap,
                    isLocalPlayback = if (isStillSelected) true else _state.value.isLocalPlayback,
                    speedLockedTo1x = if (isStillSelected) false else _state.value.speedLockedTo1x,
                    statusSummary = "Backed up clip (${segment.startTime12h}) • Speeds 1x–16x unlocked"
                )

                if (isStillSelected) {
                    startLocalMp4Playback(outFile, segment, segment.startTs)
                } else {
                    _state.value = _state.value.copy(canvasMode = PreviewCanvasMode.SELECT_CLIP)
                }
            } else {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadPercent = 0,
                    canvasMode = PreviewCanvasMode.SELECT_CLIP,
                    statusSummary = if (stopFlag.get()) "Backup cancelled" else "Failed to download clip from SD card"
                )
            }
        }
    }

    /*
     * Sequentially queries each calendar day in `[startEpochSec..endEpochSec]` and downloads all
     * overlapping SD card clips matching `eventFilter` (`"all"` or `"anomaly"`) into `backups/`,
     * updating each clip's `[LOCAL]` badge and timeline stripe live as each segment finishes.
     */
    fun startRangeBackup(
        startEpochSec: Long,
        endEpochSec: Long,
        eventFilter: String = "all"
    ) {
        if (_state.value.isDownloading) return
        cancelPendingDebouncedPlayback()
        stopPlaybackNonBlocking(resetToIdle = false)

        val stopFlag = AtomicBoolean(false)
        activeDownloadStop = stopFlag

        _state.value = _state.value.copy(
            isDownloading = true,
            downloadPercent = 0,
            downloadLabel = "Scanning SD card range...",
            canvasMode = PreviewCanvasMode.DOWNLOADING,
            statusSummary = "Scanning SD card for range backup..."
        )

        scope.launch {
            val candidates = ArrayList<RecordingSegment>()
            val calStart = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = min(startEpochSec, endEpochSec) * 1000L
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val calEnd = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = max(startEpochSec, endEpochSec) * 1000L
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }

            val curCal = calStart.clone() as Calendar
            while (!curCal.after(calEnd) && !stopFlag.get()) {
                val y = curCal.get(Calendar.YEAR)
                val m = curCal.get(Calendar.MONTH) + 1
                val d = curCal.get(Calendar.DAY_OF_MONTH)
                val dateLabel = formatFullDate(y, m, d)
                _state.value = _state.value.copy(
                    downloadLabel = "Scanning $dateLabel...",
                    canvasMode = PreviewCanvasMode.DOWNLOADING
                )

                val daySegs = if (y == _state.value.year && m == _state.value.month && d == _state.value.day && _state.value.segments.isNotEmpty()) {
                    _state.value.segments
                } else {
                    val c = CameraClient(
                        ip = camera.ip,
                        port = camera.port,
                        deviceId = camera.deviceId,
                        username = camera.username,
                        password = camera.password
                    )
                    activeDownloadClient = c
                    val (ok, _, list) = c.listSdRecordings(y, m, d)
                    if (ok) list else emptyList()
                }
                candidates.addAll(daySegs)
                curCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            val rStart = min(startEpochSec, endEpochSec)
            val rEnd = max(startEpochSec, endEpochSec)
            val filtered = candidates.filter { seg ->
                val overlaps = seg.endTs >= rStart && seg.startTs <= rEnd
                val matchesKind = when (eventFilter) {
                    "anomaly" -> seg.isAnomaly
                    "normal" -> !seg.isAnomaly
                    else -> true
                }
                overlaps && matchesKind
            }.sortedBy { it.startTs }

            if (stopFlag.get() || filtered.isEmpty()) {
                activeDownloadStop = null
                activeDownloadClient = null
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadPercent = 0,
                    canvasMode = if (_state.value.segments.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP,
                    statusSummary = if (stopFlag.get()) "Backup cancelled" else "No matching clips found in selected time range"
                )
                return@launch
            }

            var savedCount = 0
            val total = filtered.size
            for ((idx, seg) in filtered.withIndex()) {
                if (stopFlag.get()) break
                val key = "${seg.segId}_${seg.startTs}"
                val existing = FootageBackupStore.findSegmentBackupFile(appContext, camera, seg)
                if (existing != null) {
                    savedCount++
                    FootageBackupStore.saveSegmentBackupMeta(appContext, camera, listOf(seg))
                    val mapCopy = HashMap(_state.value.localBackupPaths)
                    mapCopy[key] = existing.absolutePath
                    _state.value = _state.value.copy(
                        localBackupPaths = mapCopy,
                        downloadPercent = (((idx + 1) * 100) / total).coerceIn(1, 100),
                        downloadLabel = "Already backed up (${idx + 1}/$total): ${seg.startTime12h}"
                    )
                    continue
                }

                val outFile = FootageBackupStore.getSegmentBackupFile(appContext, camera, seg)
                val ok = downloadSegmentToFile(
                    segment = seg,
                    outFile = outFile,
                    stopFlag = stopFlag,
                    onPercent = { segPct ->
                        val overall = (((idx * 100) + segPct) / total).coerceIn(1, 99)
                        _state.value = _state.value.copy(
                            downloadPercent = overall,
                            downloadLabel = "Clip ${idx + 1}/$total (${seg.startTime12h}) • $segPct%",
                            canvasMode = PreviewCanvasMode.DOWNLOADING
                        )
                    }
                )

                if (ok && outFile.isFile && outFile.length() > 512L) {
                    savedCount++
                    FootageBackupStore.saveSegmentBackupMeta(appContext, camera, listOf(seg))
                    FootageBackupStore.exportMp4ToPublicMovies(appContext, outFile)
                    val mapCopy = HashMap(_state.value.localBackupPaths)
                    mapCopy[key] = outFile.absolutePath
                    val unlockCurrent = _state.value.selectedSegmentKey == key
                    _state.value = _state.value.copy(
                        localBackupPaths = mapCopy,
                        isLocalPlayback = if (unlockCurrent) true else _state.value.isLocalPlayback,
                        speedLockedTo1x = if (unlockCurrent) false else _state.value.speedLockedTo1x
                    )
                }
            }

            activeDownloadStop = null
            activeDownloadClient = null
            _state.value = _state.value.copy(
                isDownloading = false,
                downloadPercent = 100,
                canvasMode = if (_state.value.segments.isEmpty()) PreviewCanvasMode.NO_RECORDINGS else PreviewCanvasMode.SELECT_CLIP,
                statusSummary = if (stopFlag.get()) {
                    "Backup stopped ($savedCount/$total clips saved)"
                } else {
                    "Backed up $savedCount of $total clips to phone"
                }
            )
        }
    }

    fun cancelActiveDownload() {
        activeDownloadStop?.set(true)
        val c = activeDownloadClient
        if (c != null) {
            Thread { try { c.stop() } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        }
    }

    private fun downloadSegmentToFile(
        segment: RecordingSegment,
        outFile: File,
        stopFlag: AtomicBoolean,
        onPercent: (Int) -> Unit
    ): Boolean {
        val muxer = Mp4StreamMuxer(
            outputFile = outFile,
            defaultWidth = _state.value.videoWidth.coerceIn(160, 3840),
            defaultHeight = _state.value.videoHeight.coerceIn(120, 2160)
        )
        var framesWritten = 0
        val expectedFrames = max(20, segment.durationSec * 20)
        var clientRef: CameraClient? = null

        val client = CameraClient(
            ip = camera.ip,
            port = camera.port,
            deviceId = camera.deviceId,
            username = camera.username,
            password = camera.password,
            enableAudio = false,
            onFrame = { frame ->
                if (stopFlag.get()) {
                    clientRef?.stop()
                    return@CameraClient
                }
                if (!frame.isAudio) {
                    if (muxer.writeVideoFrame(frame)) {
                        framesWritten++
                        val framePct = ((framesWritten * 100) / expectedFrames).coerceIn(1, 98)
                        onPercent(framePct)
                    }
                }
            }
        )
        clientRef = client
        activeDownloadClient = client

        return try {
            client.streamSdSegment(
                segment = segment,
                startTs = segment.startTs,
                onProgressTs = { tsSec ->
                    if (stopFlag.get()) {
                        client.stop()
                    } else if (tsSec >= segment.endTs && framesWritten > 0) {
                        client.stop()
                    } else if (segment.durationSec > 0 && tsSec >= segment.startTs) {
                        val elapsed = (tsSec - segment.startTs).toInt()
                        val tsPct = ((elapsed * 100) / segment.durationSec).coerceIn(1, 99)
                        onPercent(tsPct)
                    }
                }
            )
            val closedOk = muxer.close()
            !stopFlag.get() && closedOk
        } catch (_: Exception) {
            muxer.close()
            false
        }
    }

    fun attachSurface(surface: Surface) {
        synchronized(videoLock) {
            if (activeSurface === surface) return
            activeSurface = surface
            releaseStreamVideoCodecUnlocked()
            val kf = lastKeyframePayload
            val cType = streamCodecType
            if (kf != null && cType != VideoCodec.UNKNOWN && surface.isValid) {
                seenKeyframe = true
                ensureStreamVideoCodecUnlocked(cType)
                queueNalToStreamCodecUnlocked(kf, 20)
            }
        }
    }

    fun detachSurface(surface: Surface) {
        synchronized(videoLock) {
            if (activeSurface === surface) {
                activeSurface = null
                releaseStreamVideoCodecUnlocked()
            }
        }
    }

    private fun ensureStreamVideoCodecUnlocked(codecType: VideoCodec) {
        if (streamVideoCodec != null) return
        val surface = activeSurface ?: return
        if (!surface.isValid) return
        try {
            val w = _state.value.videoWidth.coerceIn(160, 3840)
            val h = _state.value.videoHeight.coerceIn(120, 2160)
            val format = MediaFormat.createVideoFormat(codecType.mimeType, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            val mc = MediaCodec.createDecoderByType(codecType.mimeType)
            mc.configure(format, surface, null, 0)
            mc.start()
            streamVideoCodec = mc
            streamPtsUs = 0L
        } catch (_: Exception) {
            releaseStreamVideoCodecUnlocked()
        }
    }

    private fun queueNalToStreamCodecUnlocked(nalPayload: ByteArray, fps: Int) {
        val mc = streamVideoCodec ?: return
        try {
            val inIdx = mc.dequeueInputBuffer(8_000L)
            if (inIdx >= 0) {
                val inBuf = mc.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    val len = min(nalPayload.size, inBuf.remaining())
                    inBuf.put(nalPayload, 0, len)
                    streamPtsUs += (1_000_000L / fps.coerceIn(10, 30).toLong())
                    mc.queueInputBuffer(inIdx, 0, len, streamPtsUs, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = mc.dequeueOutputBuffer(info, 0L)
            var guard = 0
            while (outIdx >= 0 && guard < 8) {
                mc.releaseOutputBuffer(outIdx, true)
                outIdx = mc.dequeueOutputBuffer(info, 0L)
                guard++
            }
        } catch (_: Exception) {
            releaseStreamVideoCodecUnlocked()
            seenKeyframe = false
        }
    }

    private fun releaseStreamVideoCodecUnlocked() {
        val mc = streamVideoCodec
        streamVideoCodec = null
        if (mc != null) {
            try { mc.stop() } catch (_: Exception) {}
            try { mc.release() } catch (_: Exception) {}
        }
    }

    private fun handleSdAudioFrame(frame: CameraFrame) {
        synchronized(audioLock) {
            when (frame.audioCodec) {
                "aac" -> decodeAndPlayAacUnlocked(frame.payload)
                "alaw" -> {
                    val pcm = CameraAudioDecoder.decodeAlawToPcm16(frame.payload)
                    playPcm16Unlocked(pcm, 8000)
                }
                else -> {
                    val pcm = CameraAudioDecoder.decodeAdpcmToPcm16(frame.payload)
                    playPcm16Unlocked(pcm, 8000)
                }
            }
        }
    }

    private fun decodeAndPlayAacUnlocked(adtsPayload: ByteArray) {
        if (adtsPayload.size < 8) return
        if ((adtsPayload[0].toInt() and 0xFF) != 0xFF || (adtsPayload[1].toInt() and 0xF0) != 0xF0) return
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
                    val len = min(rawAac.size, inBuf.remaining())
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
                    val shorts = ShortArray(pcmBytes.size / 2)
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
        val rawLevel = AcousticLevelDetector.computeLevel(pcmSamples, "sd_${camera.camId}")
        smoothedAudioLevel = if (rawLevel >= smoothedAudioLevel) {
            rawLevel // Instant attack
        } else {
            smoothedAudioLevel * 0.82f + rawLevel * 0.18f // ~200ms release ballistics
        }
        val muted = _state.value.isMuted
        _state.value = _state.value.copy(audioLevel = if (muted) 0f else smoothedAudioLevel.coerceIn(0f, 1f))
        if (muted) return

        try {
            if (persistentAudioTrack == null || persistentSampleRate != sampleRate) {
                try {
                    persistentAudioTrack?.stop()
                    persistentAudioTrack?.release()
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
                persistentAudioTrack = track
                persistentSampleRate = sampleRate
            }

            val track = persistentAudioTrack ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            track.write(pcmSamples, 0, pcmSamples.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {
        }
    }

    private fun flushAudioTrackNonBlocking() {
        Thread {
            synchronized(audioLock) {
                try {
                    persistentAudioTrack?.pause()
                    persistentAudioTrack?.flush()
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    private fun releaseAacCodecUnlocked() {
        val mc = aacCodec
        aacCodec = null
        if (mc != null) {
            try { mc.stop() } catch (_: Exception) {}
            try { mc.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        cancelPendingDebouncedPlayback()
        cancelActiveDownload()
        stopPlaybackNonBlocking(resetToIdle = false)
        scope.cancel()
        Thread {
            synchronized(audioLock) {
                releaseAacCodecUnlocked()
                val track = persistentAudioTrack
                persistentAudioTrack = null
                if (track != null) {
                    try { track.stop() } catch (_: Exception) {}
                    try { track.release() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
