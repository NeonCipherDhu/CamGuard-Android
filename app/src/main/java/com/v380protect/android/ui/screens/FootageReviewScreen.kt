package com.v380protect.android.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.v380protect.android.media.FootageReviewController
import com.v380protect.android.media.FootageReviewState
import com.v380protect.android.media.FootageSourceMode
import com.v380protect.android.media.LocalCaptureItem
import com.v380protect.android.media.PreviewCanvasMode
import com.v380protect.android.model.CameraConfig
import com.v380protect.android.protocol.V380RecordingSegment
import com.v380protect.android.ui.components.VideoSurfaceHandle
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun FootageReviewScreen(
    camera: CameraConfig,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember(camera.camId) {
        FootageReviewController(context.applicationContext, camera)
    }
    val state by controller.state.collectAsState()
    val videoHandle = remember { VideoSurfaceHandle() }
    val listState = rememberLazyListState()

    var contextMenuSegment by remember { mutableStateOf<V380RecordingSegment?>(null) }
    var showRangeBackupDialog by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }

    BackHandler {
        onBack()
    }

    // Bidirectional sync: when user taps or scrubs on the 24h timeline, automatically scroll
    // the clip list to the matching clip without re-triggering a duplicate playback call.
    LaunchedEffect(state.scrollToSegmentKeyTrigger) {
        val trigger = state.scrollToSegmentKeyTrigger ?: return@LaunchedEffect
        val targetKey = trigger.first
        val list = state.filteredAndSortedSegments
        val idx = list.indexOfFirst { "${it.segId}_${it.startTs}" == targetKey }
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
    }

    if (showRangeBackupDialog) {
        DateTimeRangeBackupDialog(
            state = state,
            onDismiss = { showRangeBackupDialog = false },
            onStartRangeBackup = { fromEpoch, toEpoch, filter ->
                showRangeBackupDialog = false
                controller.startRangeBackup(fromEpoch, toEpoch, filter)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
    ) {
        // 1. Top Header Bar + Source Mode Switcher (`On-Camera SD Card` vs `Local Captures`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121215))
                .border(1.dp, Color(0xFF1F1F23))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFF4F4F5)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${camera.name} • Footage Review",
                        color = Color(0xFFF4F4F5),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.statusSummary,
                        color = Color(0xFFA1A1AA),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Range Backup button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF064E3B))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                        .clickable {
                            controller.cancelPendingDebouncedPlayback()
                            showRangeBackupDialog = true
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Backup Range",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Backup Range",
                            color = Color(0xFFA7F3D0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (state.sourceMode == FootageSourceMode.SD_CARD) {
                            controller.loadCurrentDateSdIndex()
                        } else {
                            controller.refreshLocalCaptures()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color(0xFFA1A1AA),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Source Mode Switcher: `On-Camera SD Card` vs `Local Captures`
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121215))
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .background(Color(0xFF18181B), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val sdSelected = state.sourceMode == FootageSourceMode.SD_CARD
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sdSelected) Color(0xFF1E3A8A) else Color.Transparent)
                    .clickable { controller.setSourceMode(FootageSourceMode.SD_CARD) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SdCard,
                    contentDescription = null,
                    tint = if (sdSelected) Color(0xFF93C5FD) else Color(0xFFA1A1AA),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "On-Camera SD Card",
                    color = if (sdSelected) Color.White else Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    fontWeight = if (sdSelected) FontWeight.Bold else FontWeight.Medium
                )
            }

            val localSelected = state.sourceMode == FootageSourceMode.LOCAL_CAPTURES
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (localSelected) Color(0xFF1E3A8A) else Color.Transparent)
                    .clickable { controller.setSourceMode(FootageSourceMode.LOCAL_CAPTURES) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = if (localSelected) Color(0xFF93C5FD) else Color(0xFFA1A1AA),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Local Captures",
                    color = if (localSelected) Color.White else Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    fontWeight = if (localSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        // 2. Preview Canvas (16:9) with Idle States (`READING SD CARD ...`, `SELECT A CLIP`, `NO RECORDINGS`)
        //    and Emerald-Green Circular Downloading Animation (`DOWNLOADING • {pct}%`)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF050507)),
            contentAlignment = Alignment.Center
        ) {
            // Keep video surface mounted underneath so hardware decoder surface stays valid
            FootageVideoSurface(
                controller = controller,
                state = state,
                handle = videoHandle,
                modifier = Modifier.fillMaxSize()
            )

            when (state.canvasMode) {
                PreviewCanvasMode.READING_SD -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xE609090B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFF3B82F6),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "READING SD CARD ...",
                                color = Color(0xFF93C5FD),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.formattedDateFull,
                                color = Color(0xFF71717A),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                PreviewCanvasMode.SELECT_CLIP -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xE609090B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF18181B))
                                    .border(1.5.dp, Color(0xFF3B82F6), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "SELECT A CLIP",
                                color = Color(0xFFF4F4F5),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (state.sourceMode == FootageSourceMode.SD_CARD) {
                                    "Tap any clip below or tap on the 24-hour timeline"
                                } else {
                                    "Tap a local recording or snapshot below to preview"
                                },
                                color = Color(0xFF71717A),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                PreviewCanvasMode.NO_RECORDINGS -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xE609090B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (state.sourceMode == FootageSourceMode.SD_CARD) Icons.Default.SdCard else Icons.Default.FolderSpecial,
                                contentDescription = null,
                                tint = Color(0xFF52525B),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "NO RECORDINGS",
                                color = Color(0xFFA1A1AA),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = state.statusSummary,
                                color = Color(0xFF71717A),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                PreviewCanvasMode.LOADING_CLIP -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xB309090B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFF22D3EE),
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "OPENING SD CLIP ...",
                                color = Color(0xFFA5F3FC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                PreviewCanvasMode.DOWNLOADING -> {
                    EmeraldCircularDownloadOverlay(
                        percent = state.downloadPercent,
                        label = state.downloadLabel,
                        onCancel = { controller.cancelActiveDownload() }
                    )
                }

                PreviewCanvasMode.VIEWING_SNAPSHOT -> {
                    val path = state.viewingSnapshotPath
                    val bmp = remember(path) {
                        if (!path.isNullOrBlank() && File(path).isFile) {
                            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch (_: Exception) { null }
                        } else null
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Snapshot",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }
                }

                PreviewCanvasMode.PLAYING_VIDEO, PreviewCanvasMode.ERROR -> {
                    // Active video surface is visible
                }
            }

            // Playback source badge in top-left corner when playing
            if (state.isPlaying && state.canvasMode == PreviewCanvasMode.PLAYING_VIDEO) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (state.isLocalPlayback) Color(0xCC064E3B) else Color(0xCC1E3A8A)
                        )
                        .border(
                            1.dp,
                            if (state.isLocalPlayback) Color(0xFF10B981) else Color(0xFF3B82F6),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (state.isLocalPlayback) {
                            "LOCAL MP4 • ${state.playbackSpeed.toInt()}x"
                        } else {
                            "SD STREAM • 1x"
                        },
                        color = if (state.isLocalPlayback) Color(0xFFA7F3D0) else Color(0xFFBFDBFE),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. Transport Controls + High-Sensitivity Sound Level Meter + Speed Lock (`1x, 2x, 4x, 8x, 16x`)
        TransportAndSpeedBar(
            state = state,
            onPlayPause = { controller.togglePauseResume() },
            onStop = { controller.stopPlaybackNonBlocking(resetToIdle = true) },
            onToggleMute = { controller.toggleMute() },
            onSelectSpeed = { spd ->
                val ok = controller.setPlaybackSpeed(spd)
                if (!ok) {
                    Toast.makeText(
                        context,
                        "Fast playback (${spd.toInt()}x) is locked while streaming from SD card. Backup clip to phone (.MP4) to unlock 2x–16x speeds.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onSnapshot = {
                val saved = videoHandle.captureSnapshotToGallery(context, camera.name, camera)
                controller.refreshLocalCaptures()
                Toast.makeText(
                    context,
                    if (saved != null) "Snapshot saved: $saved" else "Play a video clip first to take a snapshot",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        if (state.sourceMode == FootageSourceMode.SD_CARD) {
            // 4. Date Picker Bar (Full spelled-out month `MMMM dd, yyyy`, `<`, `Today`, `>`)
            DateNavigationBar(
                state = state,
                onPrevDay = { controller.navigateDay(-1) },
                onToday = { controller.jumpToToday() },
                onNextDay = { controller.navigateDay(1) },
                onOpenCalendar = {
                    DatePickerDialog(
                        context,
                        { _, y, mZero, d ->
                            controller.selectDate(y, mZero + 1, d)
                        },
                        state.year,
                        (state.month - 1).coerceIn(0, 11),
                        state.day
                    ).show()
                }
            )

            // 5. Interactive 24-Hour Timeline with Zoom, Pan, Mini Scrollbar & Bidirectional Selection
            Interactive24HourTimeline(
                state = state,
                onSeekTimeline = { tsSec, commit ->
                    controller.seekTimelineToEpochSec(tsSec, commitPlayback = commit)
                }
            )

            // 6. Filter Pills (`All`, `Anomalies`, `Normal`) & Sort Order Toggle (`↓ New → Old` / `↑ Old → New`)
            SdFilterAndSortHeader(
                state = state,
                onSelectFilter = { kind -> controller.setFilterKind(kind) },
                onToggleSort = { controller.toggleSortOrder() }
            )

            // 7. SD Card Clip List (Icon-only type indicator, 12-hour AM/PM time, `[LOCAL]` badge, long-press / menu)
            val clips = state.filteredAndSortedSegments
            if (clips.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.canvasMode == PreviewCanvasMode.READING_SD) {
                            "Reading SD card recording index..."
                        } else {
                            "No SD card clips match the current filter"
                        },
                        color = Color(0xFF71717A),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = clips,
                        key = { _, seg -> "${seg.segId}_${seg.startTs}" }
                    ) { _, seg ->
                        val segKey = "${seg.segId}_${seg.startTs}"
                        val isSelected = state.selectedSegmentKey == segKey
                        val isBackedUp = state.isBackedUp(seg)
                        val isMenuOpen = contextMenuSegment?.let { "${it.segId}_${it.startTs}" == segKey } == true

                        SdClipRowCard(
                            segment = seg,
                            isSelected = isSelected,
                            isBackedUp = isBackedUp,
                            isMenuOpen = isMenuOpen,
                            onClick = {
                                controller.selectSdSegment(seg, fromTimeline = false)
                            },
                            onOpenMenu = {
                                controller.cancelPendingDebouncedPlayback()
                                contextMenuSegment = seg
                            },
                            onDismissMenu = { contextMenuSegment = null },
                            onBackupSingle = {
                                contextMenuSegment = null
                                controller.backupSingleSegment(seg)
                            },
                            onForceSdStream = {
                                contextMenuSegment = null
                                controller.selectSdSegment(seg, forceSdStream = true)
                            },
                            onOpenRangeBackup = {
                                contextMenuSegment = null
                                showRangeBackupDialog = true
                            }
                        )
                    }
                }
            }
        } else {
            // Local Captures Mode (only manual live-stream recordings & snapshots)
            LocalCapturesListSection(
                captures = state.localCaptures,
                selectedPath = state.selectedLocalPath,
                onSelectCapture = { item -> controller.selectLocalCapture(item) },
                onDeleteCapture = { item -> controller.deleteLocalCapture(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

/*
 * Dedicated emerald-green circular downloading animation (`DOWNLOADING • {pct}%`)
 * with an animated bobbing download arrow + tray icon. Never shows `"CONNECTING ..."`.
 */
@Composable
private fun EmeraldCircularDownloadOverlay(
    percent: Int,
    label: String,
    onCancel: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "dlAnim")
    val bobOffset by infinite.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bobArrow"
    )
    val ringRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "emeraldSpin"
    )
    val animatedPct by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100) / 100f,
        animationSpec = tween(durationMillis = 180),
        label = "pctArc"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06110E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(74.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 5.dp.toPx()
                    val pad = strokePx / 2f + 2.dp.toPx()
                    val arcSize = Size(size.width - pad * 2, size.height - pad * 2)

                    // Background track ring
                    drawArc(
                        color = Color(0xFF064E3B),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = strokePx)
                    )

                    // Subtle rotating glow arc
                    drawArc(
                        color = Color(0xFF10B981).copy(alpha = 0.35f),
                        startAngle = ringRotation,
                        sweepAngle = 75f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )

                    // Deterministic emerald progress arc
                    drawArc(
                        color = Color(0xFF10B981),
                        startAngle = -90f,
                        sweepAngle = 360f * max(0.04f, animatedPct),
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )

                    // Bobbing download arrow + tray icon inside the emerald circle
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val arrowY = cy - 2.dp.toPx() + bobOffset.dp.toPx()
                    val arrowHalfW = 7.dp.toPx()

                    // Vertical shaft
                    drawLine(
                        color = Color(0xFF34D399),
                        start = Offset(cx, arrowY - 9.dp.toPx()),
                        end = Offset(cx, arrowY + 4.dp.toPx()),
                        strokeWidth = 2.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    // Arrowhead
                    val headPath = Path().apply {
                        moveTo(cx - arrowHalfW, arrowY - 1.dp.toPx())
                        lineTo(cx, arrowY + 6.dp.toPx())
                        lineTo(cx + arrowHalfW, arrowY - 1.dp.toPx())
                    }
                    drawPath(
                        path = headPath,
                        color = Color(0xFF34D399),
                        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                    )
                    // Bottom tray
                    val trayY = cy + 12.dp.toPx()
                    val trayHalfW = 10.dp.toPx()
                    drawLine(
                        color = Color(0xFFA7F3D0),
                        start = Offset(cx - trayHalfW, trayY),
                        end = Offset(cx + trayHalfW, trayY),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "DOWNLOADING • ${percent.coerceIn(0, 100)}%",
                color = Color(0xFF34D399),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )

            if (label.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = label,
                    color = Color(0xFFA7F3D0),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF18181B))
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Cancel Download",
                    color = Color(0xFFD4D4D8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/*
 * Transport Controls + High-Sensitivity Sound Level Meter bar + Playback Speed Lock/Unlock (`1x..16x`).
 */
@Composable
private fun TransportAndSpeedBar(
    state: FootageReviewState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSnapshot: () -> Unit
) {
    val animatedAudioLevel by animateFloatAsState(
        targetValue = if (state.isMuted) 0f else state.audioLevel.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (state.audioLevel > 0.1f) 35 else 190),
        label = "soundMeterBallistics"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121215))
            .border(1.dp, Color(0xFF1F1F23))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Play/Pause, Stop, Mute + High-Sensitivity Sound Level Meter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Play / Pause
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (state.isPlaying && !state.isPaused) Color(0xFF1E3A8A) else Color(0xFF27272A))
                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(6.dp))
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.isPlaying && !state.isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Stop
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF27272A))
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = Color(0xFFD4D4D8),
                    modifier = Modifier.size(15.dp)
                )
            }

            // Mute / Unmute + High-Sensitivity Sound Level Indicator Bar
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF18181B))
                    .border(
                        1.dp,
                        if (!state.isMuted) Color(0xFF10B981).copy(alpha = 0.6f) else Color(0xFF27272A),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable(onClick = onToggleMute)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Audio Mute",
                    tint = if (state.isMuted) Color(0xFF71717A) else Color(0xFF10B981),
                    modifier = Modifier.size(15.dp)
                )

                // High-Sensitivity Green Sound Level Meter Bar
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF09090B))
                        .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
                ) {
                    val fillFrac = animatedAudioLevel.coerceIn(0f, 1f)
                    if (fillFrac > 0.01f) {
                        val barColor = when {
                            fillFrac > 0.85f -> Color(0xFFF59E0B)
                            fillFrac > 0.45f -> Color(0xFF34D399)
                            else -> Color(0xFF10B981)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fillFrac)
                                .background(barColor)
                        )
                    }
                }
            }

            // Snapshot button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF27272A))
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                    .clickable(onClick = onSnapshot),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Snapshot",
                    tint = Color(0xFFD4D4D8),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Right: Speed Pills (`1x`, `2x`, `4x`, `8x`, `16x`) with lock/unlock rules
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val speeds = listOf(1f, 2f, 4f, 8f, 16f)
            speeds.forEach { spd ->
                val isLocked = state.speedLockedTo1x && spd > 1.01f
                val isSelected = abs(state.playbackSpeed - spd) < 0.1f && !isLocked
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                isSelected -> Color(0xFF065F46)
                                isLocked -> Color(0xFF141417)
                                else -> Color(0xFF27272A)
                            }
                        )
                        .border(
                            1.dp,
                            when {
                                isSelected -> Color(0xFF10B981)
                                isLocked -> Color(0xFF27272A)
                                else -> Color(0xFF3F3F46)
                            },
                            RoundedCornerShape(5.dp)
                        )
                        .clickable { onSelectSpeed(spd) }
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        if (isLocked) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = Color(0xFF52525B),
                                modifier = Modifier.size(8.dp)
                            )
                        }
                        Text(
                            text = "${spd.toInt()}x",
                            color = when {
                                isSelected -> Color(0xFFA7F3D0)
                                isLocked -> Color(0xFF52525B)
                                else -> Color(0xFFD4D4D8)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/*
 * Date Picker & Navigation bar with full spelled-out month name (`MMMM dd, yyyy`)
 * and `<` (Previous Day), `Today`, and `>` (Next Day) buttons.
 */
@Composable
private fun DateNavigationBar(
    state: FootageReviewState,
    onPrevDay: () -> Unit,
    onToday: () -> Unit,
    onNextDay: () -> Unit,
    onOpenCalendar: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141418))
            .border(1.dp, Color(0xFF1F1F23))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // `<` Previous Day
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF27272A))
                .clickable(onClick = onPrevDay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "Previous Day",
                tint = Color(0xFFF4F4F5),
                modifier = Modifier.size(18.dp)
            )
        }

        // Full spelled-out date (`October 25, 2026`)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF18181B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenCalendar)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.CalendarMonth,
                contentDescription = "Select Date",
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = state.formattedDateFull,
                color = Color(0xFFF4F4F5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // `Today` button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E3A8A))
                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(6.dp))
                    .clickable(onClick = onToday)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Today",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // `>` Next Day
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF27272A))
                    .clickable(onClick = onNextDay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = "Next Day",
                    tint = Color(0xFFF4F4F5),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/*
 * Interactive 24-Hour Timeline (`00:00` to `24:00`) with:
 * - Color-coded blocks: `#ef4444` (Anomaly/Alarm), `#f59e0b` (Motion), `#3b82f6` (Continuous),
 *   and `#10b981` (Local Backup indicator stripe)
 * - Bright cyan playhead needle showing current 12-hour time (`hh:mm:ss AM/PM`)
 * - Pinch-to-zoom + quick zoom preset pills (`24h`, `6h`, `2h`, `30m`, `5m`), `+`/`-` and `<`/`>` pan buttons
 * - Compact 24-hour mini overview scrollbar when zoomed in (`windowSec < 86400`)
 * - Bidirectional sync with the clip list
 */
@Composable
private fun Interactive24HourTimeline(
    state: FootageReviewState,
    onSeekTimeline: (Long, Boolean) -> Unit
) {
    val dayStart = state.dayStartEpochUtc
    val playheadOffsetSec = (state.playheadEpochSec - dayStart).coerceIn(0L, 86399L).toFloat()

    // Visible window duration in seconds (86400 = 24h down to 300 = 5m)
    var windowSec by remember(state.year, state.month, state.day) { mutableFloatStateOf(86400f) }
    var windowStartSec by remember(state.year, state.month, state.day) { mutableFloatStateOf(0f) }

    fun clampWindowStart(start: Float, span: Float): Float {
        return start.coerceIn(0f, max(0f, 86400f - span))
    }

    fun applyZoomSpan(newSpan: Float, centerSec: Float = playheadOffsetSec) {
        val clampedSpan = newSpan.coerceIn(300f, 86400f)
        val newStart = clampWindowStart(centerSec - clampedSpan * 0.5f, clampedSpan)
        windowSec = clampedSpan
        windowStartSec = newStart
    }

    // Keep playhead visible inside zoomed window when clip advances
    LaunchedEffect(state.playheadEpochSec) {
        val pos = (state.playheadEpochSec - dayStart).coerceIn(0L, 86399L).toFloat()
        if (pos < windowStartSec || pos > windowStartSec + windowSec) {
            windowStartSec = clampWindowStart(pos - windowSec * 0.5f, windowSec)
        }
    }

    val playheadTime12h = remember(state.playheadEpochSec) {
        V380RecordingSegment.formatUtc12h(state.playheadEpochSec)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101014))
            .border(1.dp, Color(0xFF1F1F23))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Current 12-hour playhead badge + Zoom preset pills (`24h`, `6h`, `2h`, `30m`, `5m`) + `+/-` + `< / >`
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Cyan 12-hour playhead time badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF083344))
                    .border(1.dp, Color(0xFF22D3EE), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = playheadTime12h,
                    color = Color(0xFF67E8F9),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Zoom presets + zoom/pan buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val presets = listOf(
                    86400f to "24h",
                    21600f to "6h",
                    7200f to "2h",
                    1800f to "30m",
                    300f to "5m"
                )
                presets.forEach { (sec, label) ->
                    val active = abs(windowSec - sec) < 60f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) Color(0xFF1E3A8A) else Color(0xFF1F1F23))
                            .border(
                                0.8.dp,
                                if (active) Color(0xFF3B82F6) else Color(0xFF27272A),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { applyZoomSpan(sec) }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) Color.White else Color(0xFFA1A1AA),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Zoom In `+`
                TimelineMiniIconBtn(icon = Icons.Default.Add) {
                    applyZoomSpan(windowSec * 0.5f)
                }
                // Zoom Out `-`
                TimelineMiniIconBtn(icon = Icons.Default.Remove) {
                    applyZoomSpan(windowSec * 2.0f)
                }
                // Pan Left `<`
                TimelineMiniIconBtn(icon = Icons.Default.KeyboardArrowLeft) {
                    windowStartSec = clampWindowStart(windowStartSec - windowSec * 0.35f, windowSec)
                }
                // Pan Right `>`
                TimelineMiniIconBtn(icon = Icons.Default.KeyboardArrowRight) {
                    windowStartSec = clampWindowStart(windowStartSec + windowSec * 0.35f, windowSec)
                }
            }
        }

        // Row 2: Main 24-Hour Timeline Bar (supports tap, scrub, and two-finger pinch-to-zoom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF09090B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(6.dp))
                .pointerInput(dayStart, state.segments) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        var didPinchOrDrag = false
                        var lastX = down.position.x

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                val endSec = (windowStartSec + (lastX / widthPx).coerceIn(0f, 1f) * windowSec).toLong()
                                onSeekTimeline(dayStart + endSec, true)
                                break
                            }

                            if (pressed.size >= 2) {
                                didPinchOrDrag = true
                                val x0 = pressed[0].position.x
                                val x1 = pressed[1].position.x
                                val prevX0 = pressed[0].previousPosition.x
                                val prevX1 = pressed[1].previousPosition.x
                                val prevDist = abs(prevX1 - prevX0)
                                val curDist = abs(x1 - x0)
                                if (prevDist > 8f && curDist > 8f) {
                                    val zoomFactor = prevDist / curDist
                                    val midFrac = (((x0 + x1) * 0.5f) / widthPx).coerceIn(0f, 1f)
                                    val anchorSec = windowStartSec + midFrac * windowSec
                                    val newSpan = (windowSec * zoomFactor).coerceIn(300f, 86400f)
                                    windowSec = newSpan
                                    windowStartSec = clampWindowStart(anchorSec - midFrac * newSpan, newSpan)
                                }
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val ch = pressed[0]
                                lastX = ch.position.x
                                if (abs(lastX - down.position.x) > 4f || !didPinchOrDrag) {
                                    val hoverSec = (windowStartSec + (lastX / widthPx).coerceIn(0f, 1f) * windowSec).toLong()
                                    onSeekTimeline(dayStart + hoverSec, false)
                                }
                                ch.consume()
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val winStart = windowStartSec
                val winEnd = winStart + windowSec

                // Draw hour / tick grid lines
                val stepSec = when {
                    windowSec <= 900f -> 60f      // 1-min ticks
                    windowSec <= 3600f -> 300f    // 5-min ticks
                    windowSec <= 14400f -> 900f   // 15-min ticks
                    windowSec <= 28800f -> 1800f  // 30-min ticks
                    else -> 3600f                 // 1-hour ticks
                }
                var tick = ((winStart / stepSec).toInt() * stepSec).coerceAtLeast(0f)
                while (tick <= winEnd) {
                    if (tick >= winStart) {
                        val x = ((tick - winStart) / windowSec) * w
                        val isMajor = (tick.toInt() % 10800) == 0 || stepSec < 3600f
                        drawLine(
                            color = if (isMajor) Color(0xFF27272A) else Color(0xFF18181B),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = if (isMajor) 1.2f else 0.8f
                        )
                    }
                    tick += stepSec
                }

                // Draw recording segments:
                // #ef4444 (Anomaly/Alarm), #f59e0b (Motion), #3b82f6 (Continuous), #10b981 (Local Backup stripe)
                for (seg in state.segments) {
                    val sRel = (seg.startTs - dayStart).toFloat()
                    val eRel = (seg.endTs - dayStart).toFloat()
                    if (eRel < winStart || sRel > winEnd) continue

                    val x0 = (((max(sRel, winStart) - winStart) / windowSec) * w).coerceIn(0f, w)
                    val x1 = (((min(eRel, winEnd) - winStart) / windowSec) * w).coerceIn(0f, w)
                    val blockW = max(2.5f, x1 - x0)

                    val blockColor = when (seg.rawType) {
                        3 -> Color(0xFFEF4444) // Anomaly / Alarm
                        2 -> Color(0xFFF59E0B) // Motion
                        else -> Color(0xFF3B82F6) // Continuous
                    }

                    val isSel = state.selectedSegmentKey == "${seg.segId}_${seg.startTs}"
                    drawRect(
                        color = blockColor.copy(alpha = if (isSel) 0.95f else 0.72f),
                        topLeft = Offset(x0, 5f),
                        size = Size(blockW, h - 12f)
                    )

                    // Emerald-green (#10b981) bottom stripe if backed up locally
                    if (state.isBackedUp(seg)) {
                        drawRect(
                            color = Color(0xFF10B981),
                            topLeft = Offset(x0, h - 6f),
                            size = Size(blockW, 5f)
                        )
                    }
                }

                // Draw bright cyan (#22d3ee) playhead needle
                if (playheadOffsetSec in winStart..winEnd) {
                    val px = (((playheadOffsetSec - winStart) / windowSec) * w).coerceIn(0f, w)
                    drawLine(
                        color = Color(0xFF22D3EE),
                        start = Offset(px, 0f),
                        end = Offset(px, h),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawCircle(
                        color = Color(0xFF22D3EE),
                        radius = 3.5.dp.toPx(),
                        center = Offset(px, 4.dp.toPx())
                    )
                }
            }

            // Window start / center / end 12-hour time labels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDaySecShort12h(windowStartSec.roundToInt()),
                    color = Color(0xFF9CA3AF),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatDaySecShort12h((windowStartSec + windowSec * 0.5f).roundToInt()),
                    color = Color(0xFF9CA3AF),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatDaySecShort12h((windowStartSec + windowSec).roundToInt()),
                    color = Color(0xFF9CA3AF),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Row 3: Compact 24-hour mini overview scrollbar when zoomed in (`windowSec < 86400f`)
        if (windowSec < 86000f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF09090B))
                    .border(1.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
                    .pointerInput(windowSec) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val deltaSec = (dragAmount / w) * 86400f
                            windowStartSec = clampWindowStart(windowStartSec + deltaSec, windowSec)
                        }
                    }
                    .pointerInput(windowSec) {
                        detectTapGestures { pos ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val centerSec = (pos.x / w) * 86400f
                            windowStartSec = clampWindowStart(centerSec - windowSec * 0.5f, windowSec)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    for (seg in state.segments) {
                        val s = (seg.startTs - dayStart).coerceIn(0L, 86400L).toFloat()
                        val e = (seg.endTs - dayStart).coerceIn(0L, 86400L).toFloat()
                        val x0 = (s / 86400f) * w
                        val x1 = (e / 86400f) * w
                        val col = when (seg.rawType) {
                            3 -> Color(0xFFEF4444)
                            2 -> Color(0xFFF59E0B)
                            else -> Color(0xFF3B82F6)
                        }
                        drawRect(
                            color = col.copy(alpha = 0.55f),
                            topLeft = Offset(x0, 2f),
                            size = Size(max(1.5f, x1 - x0), h - 4f)
                        )
                    }

                    // Active zoomed viewport thumb
                    val thumbX = (windowStartSec / 86400f) * w
                    val thumbW = max(14.dp.toPx(), (windowSec / 86400f) * w)
                    drawRoundRect(
                        color = Color(0xFF3B82F6).copy(alpha = 0.32f),
                        topLeft = Offset(thumbX.coerceIn(0f, max(0f, w - thumbW)), 1f),
                        size = Size(min(w, thumbW), h - 2f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color(0xFF60A5FA),
                        topLeft = Offset(thumbX.coerceIn(0f, max(0f, w - thumbW)), 1f),
                        size = Size(min(w, thumbW), h - 2f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun formatDaySecShort12h(secOfDay: Int): String {
    val clamped = secOfDay.coerceIn(0, 86400)
    if (clamped >= 86400) return "11:59 PM"
    val h24 = clamped / 3600
    val m = (clamped % 3600) / 60
    val ampm = if (h24 < 12) "AM" else "PM"
    val h12 = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return String.format(Locale.US, "%02d:%02d %s", h12, m, ampm)
}

@Composable
private fun TimelineMiniIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1F1F23))
            .border(0.8.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color(0xFFD4D4D8),
            modifier = Modifier.size(12.dp)
        )
    }
}

/*
 * Filter Pills (`All`, `Anomalies`, `Normal`) & Sort Order Toggle (`↓ New → Old` / `↑ Old → New`).
 */
@Composable
private fun SdFilterAndSortHeader(
    state: FootageReviewState,
    onSelectFilter: (String) -> Unit,
    onToggleSort: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121215))
            .border(1.dp, Color(0xFF1F1F23))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Filter pills: `All`, `Anomalies`, `Normal`
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val totalCnt = state.segments.size
            val anomCnt = state.segments.count { it.isAnomaly }
            val normCnt = totalCnt - anomCnt

            val filters = listOf(
                Triple("all", "All ($totalCnt)", Color(0xFF3B82F6)),
                Triple("anomaly", "Anomalies ($anomCnt)", Color(0xFFEF4444)),
                Triple("normal", "Normal ($normCnt)", Color(0xFF60A5FA))
            )
            filters.forEach { (key, label, accent) ->
                val selected = state.filterKind == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF18181B))
                        .border(
                            1.dp,
                            if (selected) accent else Color(0xFF27272A),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onSelectFilter(key) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else Color(0xFFA1A1AA),
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Sort Order Toggle Button (`↓ New → Old` / `↑ Old → New`)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF18181B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleSort)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    Icons.Default.SwapVert,
                    contentDescription = "Sort Order",
                    tint = Color(0xFF60A5FA),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (state.sortNewestFirst) "↓ New → Old" else "↑ Old → New",
                    color = Color(0xFFD4D4D8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/*
 * SD Card Clip Row:
 * - Does NOT display `"Normal"` or `"Anomaly"` text labels in the clip row—uses the colored
 *   status icon alone (`#ef4444` red alert icon for Alarm/Anomaly, `#f59e0b` amber icon for Motion,
 *   `#3b82f6` blue icon for Continuous).
 * - Formats all clip times in 12-hour format with AM/PM (`10:42:15 AM – 10:52:15 AM  (10m 00s)`).
 * - Shows a clear emerald-green `[LOCAL]` badge and subtle green highlight on backed-up clips.
 * - Long-pressing or tapping the download/overflow button opens the backup context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SdClipRowCard(
    segment: V380RecordingSegment,
    isSelected: Boolean,
    isBackedUp: Boolean,
    isMenuOpen: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onBackupSingle: () -> Unit,
    onForceSdStream: () -> Unit,
    onOpenRangeBackup: () -> Unit
) {
    val bgColor = when {
        isSelected && isBackedUp -> Color(0xFF0A2E23)
        isSelected -> Color(0xFF172554)
        isBackedUp -> Color(0xFF091D17)
        else -> Color(0xFF141418)
    }
    val borderColor = when {
        isSelected && isBackedUp -> Color(0xFF10B981)
        isSelected -> Color(0xFF3B82F6)
        isBackedUp -> Color(0xFF065F46)
        else -> Color(0xFF27272A)
    }

    val (statusIcon, iconTint, iconBg) = when (segment.rawType) {
        3 -> Triple(Icons.Default.Warning, Color(0xFFEF4444), Color(0xFF450A0A))
        2 -> Triple(Icons.Default.DirectionsRun, Color(0xFFF59E0B), Color(0xFF451A03))
        else -> Triple(Icons.Default.FiberManualRecord, Color(0xFF3B82F6), Color(0xFF172554))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onOpenMenu
            ),
        shape = RoundedCornerShape(10.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Colored status icon ONLY (no "Normal" or "Anomaly" text label)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconBg)
                        .border(1.dp, iconTint.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 12-hour AM/PM time range + duration
                Text(
                    text = segment.displayTimeRange12h,
                    color = Color(0xFFF4F4F5),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Emerald-green `[LOCAL]` badge when backed up on phone
                if (isBackedUp) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF064E3B))
                            .border(1.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "[LOCAL]",
                            color = Color(0xFF34D399),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Overflow / Download menu button
                Box {
                    IconButton(
                        onClick = onOpenMenu,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (isBackedUp) Icons.Default.MoreVert else Icons.Default.Download,
                            contentDescription = "Clip Options",
                            tint = if (isBackedUp) Color(0xFF34D399) else Color(0xFF93C5FD),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = onDismissMenu,
                        modifier = Modifier
                            .background(Color(0xFF18181B))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isBackedUp) {
                                        "Re-Download Local Backup (.MP4)"
                                    } else {
                                        "Backup This Clip to Phone (.MP4)"
                                    },
                                    color = Color(0xFF34D399),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            onClick = onBackupSingle
                        )

                        if (isBackedUp) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Force Stream from SD Card",
                                        color = Color(0xFF93C5FD),
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.SdCard,
                                        contentDescription = null,
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(17.dp)
                                    )
                                },
                                onClick = onForceSdStream
                            )
                        }

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Backup Footage by Date & Time Range...",
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 12.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            onClick = onOpenRangeBackup
                        )
                    }
                }
            }
        }
    }
}

/*
 * Local Captures list section (only shows manual recordings & snapshots captured from the live feed).
 */
@Composable
private fun LocalCapturesListSection(
    captures: List<LocalCaptureItem>,
    selectedPath: String?,
    onSelectCapture: (LocalCaptureItem) -> Unit,
    onDeleteCapture: (LocalCaptureItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (captures.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = Color(0xFF52525B),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No manual live recordings or snapshots yet",
                    color = Color(0xFFA1A1AA),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Snapshots and live REC clips captured from the camera screen appear here.",
                    color = Color(0xFF71717A),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(
            items = captures,
            key = { _, item -> item.file.absolutePath }
        ) { _, item ->
            val isSelected = selectedPath == item.file.absolutePath
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) Color(0xFF3B82F6) else Color(0xFF27272A),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectCapture(item) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Color(0xFF172554) else Color(0xFF141418)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (item.isVideo) Color(0xFF064E3B) else Color(0xFF1E3A8A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                                contentDescription = null,
                                tint = if (item.isVideo) Color(0xFF34D399) else Color(0xFF60A5FA),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayTime12h,
                                color = Color(0xFFF4F4F5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${if (item.isVideo) "Live Recording (.MP4)" else "Live Snapshot (.JPG)"} • ${item.sizeKbStr}",
                                color = Color(0xFF9CA3AF),
                                fontSize = 10.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = { onDeleteCapture(item) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/*
 * Date & Time Range Backup Dialog:
 * - Allows selecting `From Date & Time` and `To Date & Time` (in 12-hour AM/PM format with spelled-out months)
 * - Quick presets (`Last 1 Hour`, `Last 3 Hours`, `Last 6 Hours`, `Full Selected Day`)
 * - Clip filter (`All Clips` vs `Anomalies / Motion Only`)
 */
@Composable
private fun DateTimeRangeBackupDialog(
    state: FootageReviewState,
    onDismiss: () -> Unit,
    onStartRangeBackup: (Long, Long, String) -> Unit
) {
    val context = LocalContext.current
    val dayStart = state.dayStartEpochUtc
    val latestSegEnd = state.segments.maxOfOrNull { it.endTs } ?: (dayStart + 86399L)

    var fromEpochSec by remember { mutableLongStateOf(dayStart) }
    var toEpochSec by remember { mutableLongStateOf(dayStart + 86399L) }
    var clipFilter by remember { mutableStateOf("all") } // "all" | "anomaly"

    fun pickDateTimeUtc(initialEpochSec: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = initialEpochSec * 1000L
        }
        DatePickerDialog(
            context,
            { _, y, mZero, d ->
                TimePickerDialog(
                    context,
                    { _, hr, min ->
                        val updated = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            set(Calendar.YEAR, y)
                            set(Calendar.MONTH, mZero)
                            set(Calendar.DAY_OF_MONTH, d)
                            set(Calendar.HOUR_OF_DAY, hr)
                            set(Calendar.MINUTE, min)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onPicked(updated.timeInMillis / 1000L)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false // 12-hour AM/PM picker
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141418),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Backup Footage by Date & Time Range",
                    color = Color(0xFFF4F4F5),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quick Range Presets:",
                    color = Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        "Last 1h" to {
                            toEpochSec = latestSegEnd
                            fromEpochSec = max(dayStart, latestSegEnd - 3600L)
                        },
                        "Last 3h" to {
                            toEpochSec = latestSegEnd
                            fromEpochSec = max(dayStart, latestSegEnd - 3 * 3600L)
                        },
                        "Last 6h" to {
                            toEpochSec = latestSegEnd
                            fromEpochSec = max(dayStart, latestSegEnd - 6 * 3600L)
                        },
                        "Full Day" to {
                            fromEpochSec = dayStart
                            toEpochSec = dayStart + 86399L
                        }
                    )
                    presets.forEach { (label, action) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1F1F25))
                                .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .clickable { action() }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = Color(0xFF93C5FD),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // From Date & Time (12-hour AM/PM with spelled-out month)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("From Date & Time:", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF18181B))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                            .clickable {
                                pickDateTimeUtc(fromEpochSec) { picked -> fromEpochSec = picked }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = FootageReviewController.formatEpochDateTime12hUtc(fromEpochSec),
                            color = Color(0xFFF4F4F5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // To Date & Time (12-hour AM/PM with spelled-out month)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("To Date & Time:", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF18181B))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                            .clickable {
                                pickDateTimeUtc(toEpochSec) { picked -> toEpochSec = picked }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = FootageReviewController.formatEpochDateTime12hUtc(toEpochSec),
                            color = Color(0xFFF4F4F5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Clip Filter (`All Clips` vs `Anomalies / Motion Only`)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Clip Filter:", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filterOpts = listOf(
                            "all" to "All Clips",
                            "anomaly" to "Anomalies / Motion Only"
                        )
                        filterOpts.forEach { (key, title) ->
                            val sel = clipFilter == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Color(0xFF064E3B) else Color(0xFF18181B))
                                    .border(
                                        1.dp,
                                        if (sel) Color(0xFF10B981) else Color(0xFF27272A),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { clipFilter = key }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (sel) Color(0xFFA7F3D0) else Color(0xFFA1A1AA),
                                    fontSize = 10.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStartRangeBackup(fromEpochSec, toEpochSec, clipFilter) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start Backup (.MP4)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFA1A1AA))
            }
        }
    )
}

@Composable
private fun FootageVideoSurface(
    controller: FootageReviewController,
    state: FootageReviewState,
    handle: VideoSurfaceHandle,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var activeSurface by remember(controller) { mutableStateOf<Surface?>(null) }

    DisposableEffect(controller) {
        onDispose {
            activeSurface?.let { s ->
                controller.detachSurface(s)
                try { s.release() } catch (_: Exception) {}
            }
            activeSurface = null
        }
    }

    val videoAspect = if (state.videoWidth > 0 && state.videoHeight > 0) {
        (state.videoWidth.toFloat() / state.videoHeight.toFloat()).coerceIn(0.5f, 3.0f)
    } else {
        16f / 9f
    }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(controller) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom > 1.05f) {
                            zoom = 1f
                            offset = Offset.Zero
                        } else {
                            zoom = 2.5f
                        }
                    }
                )
            }
            .pointerInput(controller) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                    zoom = newZoom
                    if (newZoom <= 1.01f) {
                        offset = Offset.Zero
                    } else {
                        val maxX = (size.width * (newZoom - 1f)) / 2f
                        val maxY = (size.height * (newZoom - 1f)) / 2f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                            y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val containerAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else videoAspect
        val sizingMod = if (containerAspect > videoAspect) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(videoAspect, matchHeightConstraintsFirst = false)
        }

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    handle.textureView = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val s = Surface(st)
                            activeSurface = s
                            controller.attachSurface(s)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            activeSurface?.let { s ->
                                controller.detachSurface(s)
                                try { s.release() } catch (_: Exception) {}
                            }
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            update = { tv ->
                handle.textureView = tv
                val st = tv.surfaceTexture
                if (st != null && activeSurface == null) {
                    val s = Surface(st)
                    activeSurface = s
                    controller.attachSurface(s)
                }
            },
            modifier = sizingMod.graphicsLayer(
                scaleX = zoom,
                scaleY = zoom,
                translationX = offset.x,
                translationY = offset.y
            )
        )
    }
}
