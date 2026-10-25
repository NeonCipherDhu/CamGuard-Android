package com.v380protect.android.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v380protect.android.media.CameraStreamController
import com.v380protect.android.model.CameraConfig
import com.v380protect.android.ui.V380ViewModel
import com.v380protect.android.ui.components.VideoSurfaceHandle
import com.v380protect.android.ui.components.V380VideoSurface
import java.util.Locale

@Composable
fun CameraDetailScreen(
    camera: CameraConfig,
    controller: CameraStreamController,
    viewModel: V380ViewModel,
    onBack: () -> Unit,
    onEditSettings: (CameraConfig) -> Unit,
    onOpenFootageReview: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val telemetry by controller.telemetry.collectAsState()
    val videoHandle = remember { VideoSurfaceHandle() }
    var activeTab by remember { mutableIntStateOf(0) } // 0 = PTZ D-Pad, 1 = Presets, 2 = Light & Image
    var showLandscapeHud by remember { mutableStateOf(false) }
    var showLandscapeControls by remember { mutableStateOf(true) }
    var presetSaveSlot by remember { mutableStateOf<Int?>(null) }
    var presetNameInput by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        if (isLandscape && activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            onBack()
        }
    }

    if (presetSaveSlot != null) {
        val slot = presetSaveSlot!!
        AlertDialog(
            onDismissRequest = { presetSaveSlot = null },
            containerColor = Color(0xFF18181B),
            title = {
                Text(
                    "Save PTZ Preset Slot ${slot + 1}",
                    color = Color(0xFFF4F4F5),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Saves current camera pan/tilt position to hardware memory.",
                        color = Color(0xFFA1A1AA),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        label = { Text("Preset Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.savePtzPreset(camera.camId, slot, presetNameInput)
                        presetSaveSlot = null
                        Toast.makeText(context, "Saved Preset Slot ${slot + 1}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Save Position", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { presetSaveSlot = null }) {
                    Text("Cancel", color = Color(0xFFA1A1AA))
                }
            }
        )
    }

    if (isLandscape) {
        // Fullscreen Landscape Mode with Translucent HUD + Floating PTZ D-Pad
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF09090B))
        ) {
            V380VideoSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                enablePinchZoom = true,
                handle = videoHandle,
                onSingleTap = { showLandscapeHud = !showLandscapeHud }
            )

            AnimatedVisibility(
                visible = showLandscapeHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top HUD bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color(0xCC09090B))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = camera.name,
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                StreamStatusBadgeRow(telemetry = telemetry)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QualityBadgeButton(
                                isHd = telemetry.quality != 0,
                                onClick = {
                                    val next = if (telemetry.quality != 0) 0 else 1
                                    viewModel.updateCameraQuality(camera.camId, next)
                                }
                            )
                            HudIconBtn(
                                icon = if (telemetry.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                active = !telemetry.isMuted,
                                onClick = { viewModel.toggleMuteExclusive(camera.camId) }
                            )
                            if (!telemetry.isMuted) {
                                Box(
                                    modifier = Modifier
                                        .width(34.dp)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF09090B))
                                        .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
                                ) {
                                    val lvl = telemetry.audioLevel.coerceIn(0f, 1f)
                                    if (lvl > 0.01f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(lvl)
                                                .background(if (lvl > 0.85f) Color(0xFFF59E0B) else Color(0xFF10B981))
                                        )
                                    }
                                }
                            }
                            HudIconBtn(
                                icon = Icons.Default.CameraAlt,
                                active = false,
                                onClick = {
                                    val file = videoHandle.captureSnapshotToGallery(context, camera.name, camera)
                                    Toast.makeText(
                                        context,
                                        if (file != null) "Snapshot saved: $file" else "Wait for live video frame",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            HudIconBtn(
                                icon = Icons.Default.SdCard,
                                active = false,
                                onClick = {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    onOpenFootageReview()
                                }
                            )
                            HudIconBtn(
                                icon = Icons.Default.ControlCamera,
                                active = showLandscapeControls,
                                onClick = { showLandscapeControls = !showLandscapeControls }
                            )
                            HudIconBtn(
                                icon = Icons.Default.FullscreenExit,
                                active = false,
                                onClick = {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            )
                        }
                    }

                    if (showLandscapeControls) {
                        // Floating PTZ D-Pad on bottom-left
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp)
                                .background(Color(0xB3121215), RoundedCornerShape(18.dp))
                                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(18.dp))
                                .padding(10.dp)
                        ) {
                            PtzDpadWidget(
                                onMove = { dir -> controller.ptzMove(dir) },
                                compact = true
                            )
                        }

                        // Floating Light / Night Vision / Flip strip on bottom-right
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(18.dp)
                                .background(Color(0xB3121215), RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("auto" to "Light Auto", "on" to "LED On", "off" to "LED Off").forEach { (m, label) ->
                                    SmallModeChip(
                                        label = label,
                                        selected = camera.lightMode == m,
                                        onClick = { viewModel.updateLightMode(camera.camId, m) }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("auto" to "IR Auto", "color" to "Color", "bw" to "B&W").forEach { (m, label) ->
                                    SmallModeChip(
                                        label = label,
                                        selected = camera.imageMode == m,
                                        onClick = { viewModel.updateImageMode(camera.camId, m) }
                                    )
                                }
                                SmallModeChip(
                                    label = "Flip 180°",
                                    selected = camera.flipped,
                                    onClick = { viewModel.flipCameraImage(camera.camId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Portrait Mode: Top Video Player + Quick Action Bar + Bottom Control Deck
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF09090B))
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121215))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFF4F4F5)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = camera.name,
                            color = Color(0xFFF4F4F5),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        StreamStatusBadgeRow(telemetry = telemetry)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenFootageReview) {
                        Icon(Icons.Default.SdCard, contentDescription = "Footage Review", tint = Color(0xFF60A5FA))
                    }
                    IconButton(onClick = { controller.reconnect(camera) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect", tint = Color(0xFFA1A1AA))
                    }
                    IconButton(onClick = { onEditSettings(camera) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFA1A1AA))
                    }
                }
            }

            // Video Player container (16:9 with pinch-to-zoom, no UI overlaid on footage)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                V380VideoSurface(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                    enablePinchZoom = true,
                    handle = videoHandle
                )
            }

            // Quick Action Bar below video
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121215))
                    .border(width = 1.dp, color = Color(0xFF1F1F23))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute / Unmute + High-Sensitivity Sound Level Meter Bar
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!telemetry.isMuted) Color(0xFF064E3B) else Color(0xFF1F1F23))
                        .border(
                            1.dp,
                            if (!telemetry.isMuted) Color(0xFF10B981) else Color(0xFF2E2E36),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.toggleMuteExclusive(camera.camId) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = if (telemetry.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = if (!telemetry.isMuted) Color(0xFF34D399) else Color(0xFFD4D4D8),
                        modifier = Modifier.size(14.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF09090B))
                            .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
                    ) {
                        val lvl = if (telemetry.isMuted) 0f else telemetry.audioLevel.coerceIn(0f, 1f)
                        if (lvl > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(lvl)
                                    .background(if (lvl > 0.85f) Color(0xFFF59E0B) else Color(0xFF10B981))
                            )
                        }
                    }
                }

                // HD / SD switch
                QualityBadgeButton(
                    isHd = telemetry.quality != 0,
                    onClick = {
                        val next = if (telemetry.quality != 0) 0 else 1
                        viewModel.updateCameraQuality(camera.camId, next)
                    }
                )

                // Snapshot
                ActionPillButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Snap",
                    active = false,
                    onClick = {
                        val file = videoHandle.captureSnapshotToGallery(context, camera.name, camera)
                        Toast.makeText(
                            context,
                            if (file != null) "Saved Snapshot: $file" else "Wait for live video frame",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                // Live Record (.MP4 into Local Captures)
                ActionPillButton(
                    icon = if (telemetry.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    label = if (telemetry.isRecording) "Stop REC" else "REC",
                    active = telemetry.isRecording,
                    onClick = {
                        if (telemetry.isRecording) {
                            val saved = controller.stopLiveRecording(context)
                            Toast.makeText(
                                context,
                                if (saved != null) "Saved Recording: $saved" else "Recording stopped",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val started = controller.startLiveRecording(context)
                            Toast.makeText(
                                context,
                                if (started != null) "Recording live clip..." else "Wait for live stream",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                // Footage Review (SD Card & Local Captures)
                ActionPillButton(
                    icon = Icons.Default.SdCard,
                    label = "Footage",
                    active = false,
                    onClick = onOpenFootageReview
                )

                // Fullscreen Landscape
                ActionPillButton(
                    icon = Icons.Default.Fullscreen,
                    label = "Full",
                    active = false,
                    onClick = {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                )
            }

            // Control Deck Section Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .background(Color(0xFF18181B), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF27272A), RoundedCornerShape(10.dp))
                    .padding(4.dp)
            ) {
                val tabs = listOf(
                    Triple(0, "PTZ Gimbal", Icons.Default.ControlCamera),
                    Triple(1, "Presets (1–6)", Icons.Default.Bookmark),
                    Triple(2, "Light & Night", Icons.Default.LightMode)
                )
                tabs.forEach { (idx, title, icon) ->
                    val selected = activeTab == idx
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selected) Color(0xFF27272A) else Color.Transparent)
                            .clickable { activeTab = idx }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (selected) Color(0xFF60A5FA) else Color(0xFFA1A1AA),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = title,
                            color = if (selected) Color(0xFFF4F4F5) else Color(0xFFA1A1AA),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Active Tab Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                when (activeTab) {
                    0 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Press and hold arrows to pan & tilt camera",
                                color = Color(0xFF71717A),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            PtzDpadWidget(
                                onMove = { dir -> controller.ptzMove(dir) },
                                compact = false
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }

                    1 -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Hardware PTZ Presets (stored on camera port 8800)",
                                color = Color(0xFF71717A),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items((0..5).toList()) { slotId ->
                                    val savedPreset = camera.ptzPresets.find { it.slotId == slotId }
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(
                                                1.dp,
                                                if (savedPreset != null) Color(0xFF3B82F6).copy(alpha = 0.6f) else Color(0xFF27272A),
                                                RoundedCornerShape(12.dp)
                                            ),
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF141418)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (savedPreset != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                        contentDescription = null,
                                                        tint = if (savedPreset != null) Color(0xFF60A5FA) else Color(0xFF71717A),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = savedPreset?.name ?: "Slot ${slotId + 1}",
                                                        color = Color(0xFFF4F4F5),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (savedPreset != null) {
                                                    IconButton(
                                                        onClick = { viewModel.deletePtzPreset(camera.camId, slotId) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.DeleteOutline,
                                                            contentDescription = "Clear slot",
                                                            tint = Color(0xFFEF4444),
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        controller.ptzPresetGoto(slotId)
                                                        Toast.makeText(
                                                            context,
                                                            "Moving to ${savedPreset?.name ?: "Slot ${slotId + 1}"}...",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF2563EB)
                                                    ),
                                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Go To", fontSize = 12.sp, color = Color.White)
                                                }

                                                Button(
                                                    onClick = {
                                                        presetNameInput = savedPreset?.name ?: "Preset ${slotId + 1}"
                                                        presetSaveSlot = slotId
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = Color(0xFF27272A)
                                                    ),
                                                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Set Here", fontSize = 12.sp, color = Color(0xFFD4D4D8))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Floodlight Mode
                            ControlGroupCard(
                                title = "Floodlight / White LED",
                                subtitle = "Control camera security floodlight LEDs"
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        "auto" to "Auto",
                                        "on" to "Light On",
                                        "off" to "Light Off"
                                    ).forEach { (mode, label) ->
                                        ModeSelectButton(
                                            label = label,
                                            selected = camera.lightMode == mode,
                                            modifier = Modifier.weight(1f),
                                            onClick = { viewModel.updateLightMode(camera.camId, mode) }
                                        )
                                    }
                                }
                            }

                            // Night Vision / Image Color Mode
                            ControlGroupCard(
                                title = "Night Vision / Image Mode",
                                subtitle = "Switch between Full-Color and Infrared B&W"
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        "auto" to "Auto IR",
                                        "color" to "Full Color",
                                        "bw" to "IR B&W"
                                    ).forEach { (mode, label) ->
                                        ModeSelectButton(
                                            label = label,
                                            selected = camera.imageMode == mode,
                                            modifier = Modifier.weight(1f),
                                            onClick = { viewModel.updateImageMode(camera.camId, mode) }
                                        )
                                    }
                                }
                            }

                            // Orientation Flip
                            ControlGroupCard(
                                title = "Image Orientation (180° Flip)",
                                subtitle = "Invert video stream for ceiling-mounted cameras"
                            ) {
                                Button(
                                    onClick = { viewModel.flipCameraImage(camera.camId) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (camera.flipped) Color(0xFF1E3A8A) else Color(0xFF27272A)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Flip,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (camera.flipped) "180° Flip Enabled (Tap to Toggle)" else "Flip Image 180°",
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamStatusBadgeRow(
    telemetry: com.v380protect.android.media.StreamTelemetry
) {
    if (!telemetry.isConnected) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF3F2E0E))
                    .border(1.dp, Color(0xFFD97706), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFBBF24))
                    )
                    Text(
                        text = telemetry.statusText,
                        color = Color(0xFFFDE68A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        return
    }

    val isHd = telemetry.quality != 0
    val qLabel = if (isHd) "HD" else "SD"
    val codec = telemetry.codecLabel.ifBlank { "H.264" }
    val fpsStr = String.format(Locale.US, "%.0f fps", telemetry.fps)
    val kbpsStr = String.format(Locale.US, "%.0f kbps", telemetry.kbps)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // LIVE pill badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF064E3B))
                .border(1.dp, Color(0xFF10B981), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
            )
            Text(
                text = "LIVE",
                color = Color(0xFFA7F3D0),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Quality + Codec pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isHd) Color(0xFF172554) else Color(0xFF1F1F25))
                .border(
                    1.dp,
                    if (isHd) Color(0xFF3B82F6) else Color(0xFF3F3F46),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = "$qLabel $codec",
                color = if (isHd) Color(0xFFBFDBFE) else Color(0xFFD4D4D8),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // FPS + Bitrate pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF18181B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp)
        ) {
            Text(
                text = "$fpsStr · $kbpsStr",
                color = Color(0xFFA1A1AA),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PtzDpadWidget(
    onMove: (String) -> Unit,
    compact: Boolean = false
) {
    val btnSize = if (compact) 46.dp else 62.dp
    val gap = if (compact) 6.dp else 10.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        PtzHoldButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDesc = "Pan Up",
            size = btnSize,
            onPress = { onMove("up") },
            onRelease = { onMove("stop") }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PtzHoldButton(
                icon = Icons.Default.KeyboardArrowLeft,
                contentDesc = "Pan Left",
                size = btnSize,
                onPress = { onMove("left") },
                onRelease = { onMove("stop") }
            )
            Box(
                modifier = Modifier
                    .size(btnSize)
                    .clip(CircleShape)
                    .background(Color(0xFF18181B))
                    .border(1.dp, Color(0xFF27272A), CircleShape)
                    .clickable { onMove("stop") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop PTZ",
                    tint = Color(0xFFA1A1AA),
                    modifier = Modifier.size(if (compact) 18.dp else 22.dp)
                )
            }
            PtzHoldButton(
                icon = Icons.Default.KeyboardArrowRight,
                contentDesc = "Pan Right",
                size = btnSize,
                onPress = { onMove("right") },
                onRelease = { onMove("stop") }
            )
        }
        PtzHoldButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDesc = "Pan Down",
            size = btnSize,
            onPress = { onMove("down") },
            onRelease = { onMove("stop") }
        )
    }
}

@Composable
private fun PtzHoldButton(
    icon: ImageVector,
    contentDesc: String,
    size: androidx.compose.ui.unit.Dp,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) Color(0xFF2563EB) else Color(0xFF18181B))
            .border(
                1.dp,
                if (pressed) Color(0xFF60A5FA) else Color(0xFF27272A),
                RoundedCornerShape(14.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPress()
                    waitForUpOrCancellation()
                    pressed = false
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = if (pressed) Color.White else Color(0xFFE4E4E7),
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

@Composable
fun QualityBadgeButton(
    isHd: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHd) Color(0xFF1E3A8A) else Color(0xFF27272A))
            .border(1.dp, if (isHd) Color(0xFF3B82F6) else Color(0xFF3F3F46), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isHd) "HD 1080p" else "SD 360p",
            color = if (isHd) Color(0xFF93C5FD) else Color(0xFFD4D4D8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ActionPillButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF065F46) else Color(0xFF18181B))
            .border(
                1.dp,
                if (active) Color(0xFF10B981) else Color(0xFF27272A),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color(0xFF34D399) else Color(0xFFD4D4D8),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = if (active) Color(0xFFD1FAE5) else Color(0xFFD4D4D8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HudIconBtn(
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF2563EB) else Color(0xFF18181B))
            .border(1.dp, if (active) Color(0xFF60A5FA) else Color(0xFF27272A), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SmallModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFF1E3A8A) else Color(0xFF18181B))
            .border(1.dp, if (selected) Color(0xFF3B82F6) else Color(0xFF27272A), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF93C5FD) else Color(0xFFD4D4D8),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ControlGroupCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141418), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFFF4F4F5),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            color = Color(0xFF71717A),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ModeSelectButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF1E3A8A) else Color(0xFF18181B))
            .border(
                1.dp,
                if (selected) Color(0xFF3B82F6) else Color(0xFF27272A),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF93C5FD) else Color(0xFFD4D4D8),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
