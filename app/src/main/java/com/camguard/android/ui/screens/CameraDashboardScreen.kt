package com.camguard.android.ui.screens

import android.app.Activity
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.camguard.android.media.CameraStreamController
import com.camguard.android.model.CameraConfig
import com.camguard.android.ui.CameraViewModel
import com.camguard.android.ui.components.CameraVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraDashboardScreen(
    viewModel: CameraViewModel,
    onOpenCamera: (CameraConfig) -> Unit,
    onOpenFootageReview: (CameraConfig) -> Unit = {},
    onAddCameraClick: () -> Unit,
    onEditCameraClick: (CameraConfig) -> Unit,
    onDiscoverLanClick: () -> Unit
) {
    val cameras by viewModel.cameras.collectAsState()
    val gridColsPref by viewModel.gridColumns.collectAsState()
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showHamburgerMenu by remember { mutableStateOf(false) }
    var isFullscreenWall by remember { mutableStateOf(false) }
    var showFullscreenExitHint by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val view = LocalView.current

    // Toggle system bars when entering/exiting edge-to-edge Fullscreen Wall mode
    DisposableEffect(isFullscreenWall) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreenWall) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = (context as? Activity)?.window
            if (w != null) {
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(showFullscreenExitHint) {
        if (showFullscreenExitHint) {
            delay(2500)
            showFullscreenExitHint = false
        }
    }

    BackHandler(enabled = isFullscreenWall) {
        isFullscreenWall = false
    }

    if (isFullscreenWall && cameras.isNotEmpty()) {
        // Fullscreen All-Cameras Wall Mode: 100% of phone screen, zero visible UI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (!isLandscape) {
                // Portrait: Single column of all cameras, vertically scrollable
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    cameras.forEach { cam ->
                        val ctrl = viewModel.getOrCreateController(cam)
                        CameraVideoSurface(
                            controller = ctrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            enablePinchZoom = false,
                            onSingleTap = { showFullscreenExitHint = !showFullscreenExitHint },
                            onDoubleTap = { isFullscreenWall = false }
                        )
                    }
                }
            } else {
                // Landscape: Max 4 cameras per page (2x2 grid), swipe left/right for more cameras
                val pages = cameras.chunked(4)
                val pagerState = rememberPagerState(pageCount = { pages.size })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val pageCameras = pages[pageIndex]
                    val cols = if (pageCameras.size >= 2) 2 else 1
                    val rows = pageCameras.chunked(cols)

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                    ) {
                        rows.forEach { rowCameras ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)
                            ) {
                                rowCameras.forEach { cam ->
                                    val ctrl = viewModel.getOrCreateController(cam)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(1f)
                                            .background(Color.Black),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CameraVideoSurface(
                                            controller = ctrl,
                                            modifier = Modifier.fillMaxSize(),
                                            enablePinchZoom = false,
                                            onSingleTap = { showFullscreenExitHint = !showFullscreenExitHint },
                                            onDoubleTap = { isFullscreenWall = false }
                                        )
                                    }
                                }
                                // Keep 2x2 cell alignment if the bottom row of a 3-camera page has 1 item
                                if (cols == 2 && rowCameras.size == 1 && rows.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(1f)
                                            .background(Color.Black)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Subtle auto-hiding exit button only shown briefly if user taps screen
            AnimatedVisibility(
                visible = showFullscreenExitHint,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xAA09090B))
                        .border(1.dp, Color(0xFF3F3F46), CircleShape)
                        .clickable { isFullscreenWall = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        return
    }

    val effectiveColumns = when {
        isLandscape -> if (gridColsPref == 1) 2 else 3
        else -> gridColsPref
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121215))
                .border(width = 1.dp, color = Color(0xFF1F1F23))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E3A8A).copy(alpha = 0.5f))
                        .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy((-1).dp)
                ) {
                    Text(
                        text = "CamGuard",
                        color = Color(0xFFF4F4F5),
                        fontSize = 15.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${cameras.size} Camera${if (cameras.size == 1) "" else "s"} • Unofficial client compatible with V380 cameras.",
                        color = Color(0xFF71717A),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (cameras.isNotEmpty()) {
                    // Fullscreen All-Cameras Wall Mode Button
                    IconButton(
                        onClick = {
                            showFullscreenExitHint = false
                            isFullscreenWall = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen All Cameras",
                            tint = Color(0xFFA1A1AA),
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Grid Layout Toggle (1x1 vs 2x2)
                    IconButton(
                        onClick = { viewModel.toggleGridColumns() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (gridColsPref == 1) Icons.Default.GridView else Icons.Default.ViewAgenda,
                            contentDescription = "Toggle Grid",
                            tint = Color(0xFFA1A1AA),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Reconnect All
                    IconButton(
                        onClick = { viewModel.reconnectAll() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reconnect All",
                            tint = Color(0xFFA1A1AA),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Hamburger Menu containing Scan & + Add
                Box {
                    IconButton(
                        onClick = { showHamburgerMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color(0xFFF4F4F5),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showHamburgerMenu,
                        onDismissRequest = { showHamburgerMenu = false },
                        modifier = Modifier
                            .background(Color(0xFF18181B))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Scan Wi-Fi for Cameras",
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Radar,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showHamburgerMenu = false
                                onDiscoverLanClick()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Add Camera Manually",
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showHamburgerMenu = false
                                onAddCameraClick()
                            }
                        )
                    }
                }
            }
        }

        if (cameras.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF121215)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E3A8A).copy(alpha = 0.35f))
                                .border(1.dp, Color(0xFF3B82F6), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Radar,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "No Cameras Added Yet",
                            color = Color(0xFFF4F4F5),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Unofficial client compatible with V380 cameras.\nScan your Wi-Fi to automatically find cameras, or add a camera manually.",
                            color = Color(0xFFA1A1AA),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        Button(
                            onClick = onDiscoverLanClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Radar,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Find Cameras on Wi-Fi",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onAddCameraClick,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = Color(0xFFD4D4D8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Camera Manually", color = Color(0xFFD4D4D8))
                        }
                    }
                }
            }
        } else {
            val isCompactGrid = effectiveColumns >= 2
            val gridState = rememberLazyGridState()
            val coroutineScope = rememberCoroutineScope()

            var gridRootOffset by remember { mutableStateOf(Offset.Zero) }
            var gridViewportSize by remember { mutableStateOf(IntSize.Zero) }
            val itemSlotBounds = remember { mutableMapOf<String, Rect>() }

            var draggedCamId by remember { mutableStateOf<String?>(null) }
            var dragStartSlotOffset by remember { mutableStateOf(Offset.Zero) }
            var totalDragOffset by remember { mutableStateOf(Offset.Zero) }

            val currentCameras by rememberUpdatedState(cameras)
            val currentOnOpenCamera by rememberUpdatedState(onOpenCamera)
            val currentEffectiveCols by rememberUpdatedState(effectiveColumns)

            // Auto-scroll when dragging a Trello card near the top or bottom edge of the grid
            LaunchedEffect(draggedCamId) {
                val activeId = draggedCamId ?: return@LaunchedEffect
                while (isActive && draggedCamId == activeId) {
                    val slotRect = itemSlotBounds[activeId]
                    if (slotRect != null && gridViewportSize.height > 0) {
                        val floatingTop = dragStartSlotOffset.y + totalDragOffset.y
                        val floatingBottom = floatingTop + slotRect.height
                        val edgeZone = 72f
                        val scrollStep = when {
                            floatingTop < edgeZone && gridState.canScrollBackward -> {
                                -((edgeZone - floatingTop) / edgeZone).coerceIn(0.15f, 1f) * 18f
                            }
                            floatingBottom > gridViewportSize.height - edgeZone && gridState.canScrollForward -> {
                                ((floatingBottom - (gridViewportSize.height - edgeZone)) / edgeZone).coerceIn(0.15f, 1f) * 18f
                            }
                            else -> 0f
                        }
                        if (scrollStep != 0f) {
                            gridState.scrollBy(scrollStep)
                        }
                    }
                    delay(16)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        gridRootOffset = coords.positionInRoot()
                        gridViewportSize = coords.size
                    }
                    .pointerInput(Unit) {
                        val holdDurationMs = 1800L // ~2 seconds tap-and-hold required before drag activates
                        val scrollCancelPx = 6.dp.toPx() // Any slide > 6dp cancels hold and scrolls normally
                        awaitEachGesture {
                            // 1. Wait for DOWN on Main pass so child buttons (HD/SD, Mute, Settings, Expand)
                            //    consume DOWN first when tapped directly.
                            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
                            val downPos = down.position

                            val hitEntry = itemSlotBounds.entries.firstOrNull { (camId, rect) ->
                                currentCameras.any { it.camId == camId } && rect.contains(downPos)
                            } ?: return@awaitEachGesture

                            val hitCamId = hitEntry.key
                            val initialRect = hitEntry.value
                            val pointerId = down.id

                            var lastPointerPos = downPos
                            var gestureAbortedOrTapped = false

                            // 2. Require a ~2-second tap-and-hold before activating Trello card drag.
                            //    If the user slides their finger to scroll at any point before 2 seconds,
                            //    immediately abort the hold so LazyVerticalGrid scrolls the page normally.
                            val endedBeforeHold = withTimeoutOrNull(holdDurationMs) {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null) {
                                        gestureAbortedOrTapped = true
                                        break
                                    }
                                    if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                        // Finger lifted before hold timeout -> normal tap!
                                        if ((change.position - downPos).getDistance() <= scrollCancelPx && !change.isConsumed) {
                                            change.consume()
                                            val videoHeight = initialRect.width * (9f / 16f)
                                            if (downPos.y <= initialRect.top + videoHeight + 8.dp.toPx()) {
                                                currentCameras.find { it.camId == hitCamId }?.let { cam ->
                                                    currentOnOpenCamera(cam)
                                                }
                                            }
                                        }
                                        gestureAbortedOrTapped = true
                                        break
                                    }
                                    lastPointerPos = change.position
                                    if (change.isConsumed || (change.position - downPos).getDistance() > scrollCancelPx) {
                                        // User is sliding to scroll -> let LazyVerticalGrid scroll!
                                        gestureAbortedOrTapped = true
                                        break
                                    }
                                }
                                true
                            }

                            if (endedBeforeHold != null || gestureAbortedOrTapped) {
                                return@awaitEachGesture
                            }

                            // 3. 2-second hold completed! Lift the card in Trello style and start dragging.
                            var isDraggingCard = true
                            val activeSlotRect = itemSlotBounds[hitCamId] ?: initialRect
                            dragStartSlotOffset = activeSlotRect.topLeft
                            totalDragOffset = Offset.Zero
                            draggedCamId = hitCamId
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                                    if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                        change.consume()
                                        break
                                    }

                                    val currentPos = change.position
                                    val stepDelta = currentPos - lastPointerPos
                                    lastPointerPos = currentPos
                                    change.consume()

                                    if (stepDelta != Offset.Zero) {
                                        totalDragOffset += stepDelta
                                        val floatingTopLeft = dragStartSlotOffset + totalDragOffset
                                        val floatingCenter = floatingTopLeft + Offset(
                                            activeSlotRect.width / 2f,
                                            activeSlotRect.height / 2f
                                        )

                                        // Check if the floating Trello card center crossed into another card's slot
                                        val targetEntry = itemSlotBounds.entries.firstOrNull { (otherId, rect) ->
                                            if (otherId == hitCamId || currentCameras.none { it.camId == otherId }) {
                                                false
                                            } else {
                                                val cx = rect.center.x
                                                val cy = rect.center.y
                                                val halfH = rect.height * 0.45f
                                                if (currentEffectiveCols == 1) {
                                                    // In 1-column list mode, check vertical center crossing regardless of X offset
                                                    abs(floatingCenter.y - cy) <= halfH
                                                } else {
                                                    val halfW = rect.width * 0.45f
                                                    abs(floatingCenter.x - cx) <= halfW &&
                                                        abs(floatingCenter.y - cy) <= halfH
                                                }
                                            }
                                        }

                                        if (targetEntry != null) {
                                            val moved = viewModel.moveCameraById(hitCamId, targetEntry.key)
                                            if (moved) {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                if (isDraggingCard || draggedCamId == hitCamId) {
                                    draggedCamId = null
                                    viewModel.persistCameraOrder()
                                }
                            }
                        }
                    }
            ) {
                val gridPad = if (isCompactGrid) 8.dp else 12.dp
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(effectiveColumns),
                    userScrollEnabled = draggedCamId == null,
                    contentPadding = PaddingValues(
                        start = gridPad,
                        top = gridPad,
                        end = gridPad,
                        bottom = 72.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(gridPad),
                    verticalArrangement = Arrangement.spacedBy(gridPad),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(cameras, key = { it.camId }) { cam ->
                        val ctrl = viewModel.getOrCreateController(cam)
                        val isDragged = draggedCamId == cam.camId
                        val slideOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                        var prevSlotTopLeft by remember { mutableStateOf<Offset?>(null) }
                        var wasDragged by remember { mutableStateOf(false) }

                        // Auto-align card smoothly back to (0, 0) in its grid slot when released
                        LaunchedEffect(isDragged) {
                            if (isDragged) {
                                wasDragged = true
                                slideOffset.snapTo(Offset.Zero)
                            } else if (wasDragged) {
                                wasDragged = false
                                val currentSlot = itemSlotBounds[cam.camId]?.topLeft ?: dragStartSlotOffset
                                val releaseDelta = (dragStartSlotOffset + totalDragOffset) - currentSlot
                                if (releaseDelta.getDistance() > 0.5f) {
                                    try {
                                        slideOffset.snapTo(releaseDelta)
                                        slideOffset.animateTo(
                                            Offset.Zero,
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    } finally {
                                        slideOffset.snapTo(Offset.Zero)
                                    }
                                } else {
                                    slideOffset.snapTo(Offset.Zero)
                                }
                            } else if (slideOffset.value != Offset.Zero && !slideOffset.isRunning) {
                                slideOffset.snapTo(Offset.Zero)
                            }
                        }

                        val cardScale by animateFloatAsState(
                            targetValue = if (isDragged) 1.035f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "trelloScale"
                        )
                        val cardRotation by animateFloatAsState(
                            targetValue = if (isDragged) 1.6f else 0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "trelloTilt"
                        )

                        val itemZIndex = when {
                            isDragged -> 25f
                            slideOffset.isRunning -> 10f
                            else -> 0f
                        }

                        val cardCorner = if (isCompactGrid) 10.dp else 14.dp

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(itemZIndex)
                                .onGloballyPositioned { coords ->
                                    val slotTopLeft = coords.positionInRoot() - gridRootOffset
                                    val newRect = Rect(slotTopLeft, coords.size.toSize())
                                    val oldTopLeft = prevSlotTopLeft
                                    if (itemSlotBounds[cam.camId] != newRect) {
                                        itemSlotBounds[cam.camId] = newRect
                                    }
                                    if (oldTopLeft != null && oldTopLeft != slotTopLeft && !isDragged) {
                                        val displacement = oldTopLeft - slotTopLeft
                                        if (displacement.getDistance() > 2f && draggedCamId != null) {
                                            coroutineScope.launch {
                                                try {
                                                    slideOffset.snapTo(slideOffset.value + displacement)
                                                    slideOffset.animateTo(
                                                        Offset.Zero,
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                } finally {
                                                    if (draggedCamId != cam.camId) {
                                                        slideOffset.snapTo(Offset.Zero)
                                                    }
                                                }
                                            }
                                        } else if (draggedCamId == null && !slideOffset.isRunning && slideOffset.value != Offset.Zero) {
                                            coroutineScope.launch {
                                                slideOffset.snapTo(Offset.Zero)
                                            }
                                        }
                                    }
                                    prevSlotTopLeft = slotTopLeft
                                }
                        ) {
                            // Trello-style recessed drop slot placeholder shown in the target grid cell while dragging
                            if (isDragged) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(cardCorner))
                                        .background(Color(0xFF1E3A8A).copy(alpha = 0.16f))
                                        .border(
                                            width = 2.dp,
                                            color = Color(0xFF3B82F6).copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(cardCorner)
                                        )
                                )
                            }

                            CameraTileCard(
                                camera = cam,
                                controller = ctrl,
                                compact = isCompactGrid,
                                isDragged = isDragged,
                                modifier = Modifier.graphicsLayer {
                                    if (isDragged) {
                                        val currentSlot = itemSlotBounds[cam.camId]?.topLeft ?: dragStartSlotOffset
                                        val floatingTopLeft = dragStartSlotOffset + totalDragOffset
                                        translationX = floatingTopLeft.x - currentSlot.x
                                        translationY = floatingTopLeft.y - currentSlot.y
                                    } else {
                                        translationX = slideOffset.value.x
                                        translationY = slideOffset.value.y
                                    }
                                    scaleX = cardScale
                                    scaleY = cardScale
                                    rotationZ = cardRotation
                                    shadowElevation = if (isDragged) 24.dp.toPx() else 0f
                                },
                                onOpenDetail = { onOpenCamera(cam) },
                                onOpenFootageReview = { onOpenFootageReview(cam) },
                                onToggleMute = { viewModel.toggleMuteExclusive(cam.camId) },
                                onToggleQuality = {
                                    val next = if (ctrl.telemetry.value.quality != 0) 0 else 1
                                    viewModel.updateCameraQuality(cam.camId, next)
                                },
                                onEditSettings = { onEditCameraClick(cam) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTileCard(
    camera: CameraConfig,
    controller: CameraStreamController,
    compact: Boolean,
    isDragged: Boolean = false,
    modifier: Modifier = Modifier,
    onOpenDetail: () -> Unit,
    onOpenFootageReview: () -> Unit = {},
    onToggleMute: () -> Unit,
    onToggleQuality: () -> Unit,
    onEditSettings: () -> Unit
) {
    val telemetry by controller.telemetry.collectAsState()
    val borderColor = when {
        isDragged -> Color(0xFF3B82F6)
        telemetry.isConnected -> Color(0xFF27272A)
        else -> Color(0xFF3F3F46)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                if (isDragged) 2.dp else 1.dp,
                borderColor,
                RoundedCornerShape(if (compact) 10.dp else 14.dp)
            ),
        shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
        color = if (isDragged) Color(0xFF18181F) else Color(0xFF121215)
    ) {
        Column {
            // Video Viewport (16:9) - Pure unobstructed video footage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF09090B))
            ) {
                CameraVideoSurface(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                    enablePinchZoom = false,
                    onSingleTap = null,
                    onDoubleTap = null
                )

                // Center status message only when connecting / offline
                if (!telemetry.isConnected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xCC121215), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = telemetry.statusText,
                            color = Color(0xFFD4D4D8),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Bottom Control Strip (Camera Name + Telemetry + Controls outside the video)
            if (compact) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDragged) Color(0xFF1A1A22) else Color(0xFF141418))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Row 1: Trello Drag Grip + Status dot + full Camera Name
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "Drag to reorder",
                            tint = if (isDragged) Color(0xFF60A5FA) else Color(0xFF71717A),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (telemetry.isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = camera.name,
                            color = Color(0xFFF4F4F5),
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 2: HD/SD button on left, Mute + Settings + Expand icons on right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (telemetry.quality != 0) Color(0xFF1E3A8A) else Color(0xFF27272A)
                                )
                                .border(
                                    1.dp,
                                    if (telemetry.quality != 0) Color(0xFF3B82F6) else Color(0xFF52525B),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable(onClick = onToggleQuality)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (telemetry.quality != 0) "HD" else "SD",
                                color = if (telemetry.quality != 0) Color(0xFF93C5FD) else Color(0xFFD4D4D8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (telemetry.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Mute/Unmute",
                                    tint = if (telemetry.isMuted) Color(0xFF71717A) else Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = onOpenFootageReview,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.SdCard,
                                    contentDescription = "Footage Review",
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = onEditSettings,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = Color(0xFFA1A1AA),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = onOpenDetail,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInFull,
                                    contentDescription = "PTZ & Fullscreen",
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDragged) Color(0xFF1A1A22) else Color(0xFF141418))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "Drag to reorder",
                            tint = if (isDragged) Color(0xFF60A5FA) else Color(0xFF71717A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy((-1).dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (telemetry.isConnected) Color(0xFF10B981) else Color(0xFFF59E0B)
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = camera.name,
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 13.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (telemetry.isConnected) {
                                    String.format(
                                        Locale.US,
                                        "%s • %.0f fps • %.0f kbps",
                                        telemetry.codecLabel.ifBlank { "H.264" },
                                        telemetry.fps,
                                        telemetry.kbps
                                    )
                                } else {
                                    "${camera.ip}:${camera.port}"
                                },
                                color = Color(0xFF71717A),
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 13.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (telemetry.quality != 0) Color(0xFF1E3A8A) else Color(0xFF27272A)
                                )
                                .border(
                                    1.dp,
                                    if (telemetry.quality != 0) Color(0xFF3B82F6) else Color(0xFF52525B),
                                    RoundedCornerShape(5.dp)
                                )
                                .clickable(onClick = onToggleQuality)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (telemetry.quality != 0) "HD" else "SD",
                                color = if (telemetry.quality != 0) Color(0xFF93C5FD) else Color(0xFFD4D4D8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (telemetry.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Mute/Unmute",
                                tint = if (telemetry.isMuted) Color(0xFF71717A) else Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        if (!telemetry.isMuted) {
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF09090B))
                                    .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(3.dp))
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

                        IconButton(
                            onClick = onOpenFootageReview,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.SdCard,
                                contentDescription = "Footage Review",
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onEditSettings,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color(0xFFA1A1AA),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = onOpenDetail,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ControlCamera,
                                contentDescription = "PTZ & Fullscreen",
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
