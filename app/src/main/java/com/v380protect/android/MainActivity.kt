package com.v380protect.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.v380protect.android.model.CameraConfig
import com.v380protect.android.ui.V380ViewModel
import com.v380protect.android.ui.components.AddOrEditCameraDialog
import com.v380protect.android.ui.components.LanDiscoveryDialog
import com.v380protect.android.ui.screens.CameraDashboardScreen
import com.v380protect.android.ui.screens.CameraDetailScreen
import com.v380protect.android.ui.screens.FootageReviewScreen

private val V380DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF10B981),
    background = Color(0xFF09090B),
    surface = Color(0xFF121215),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFF4F4F5),
    onSurface = Color(0xFFF4F4F5)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while viewing live security cameras
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = V380DarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF09090B)
                ) {
                    V380ProtectApp()
                }
            }
        }
    }
}

@Composable
fun V380ProtectApp(viewModel: V380ViewModel = viewModel()) {
    val cameras by viewModel.cameras.collectAsState()
    val selectedCameraId by viewModel.selectedCameraId.collectAsState()
    val footageReviewCameraId by viewModel.footageReviewCameraId.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val discoveryStatus by viewModel.discoveryStatus.collectAsState()
    val discoveredCameras by viewModel.discoveredCameras.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingCamera by remember { mutableStateOf<CameraConfig?>(null) }
    var showDiscoveryDialog by remember { mutableStateOf(false) }

    val footageCamera = cameras.find { it.camId == footageReviewCameraId }
    val activeCamera = cameras.find { it.camId == selectedCameraId }

    if (footageCamera != null) {
        FootageReviewScreen(
            camera = footageCamera,
            onBack = { viewModel.openFootageReview(null) }
        )
    } else if (activeCamera != null) {
        val ctrl = viewModel.getOrCreateController(activeCamera)
        CameraDetailScreen(
            camera = activeCamera,
            controller = ctrl,
            viewModel = viewModel,
            onBack = { viewModel.selectCamera(null) },
            onEditSettings = { cam -> editingCamera = cam },
            onOpenFootageReview = { viewModel.openFootageReview(activeCamera.camId) }
        )
    } else {
        CameraDashboardScreen(
            viewModel = viewModel,
            onOpenCamera = { cam -> viewModel.selectCamera(cam.camId) },
            onOpenFootageReview = { cam -> viewModel.openFootageReview(cam.camId) },
            onAddCameraClick = { showAddDialog = true },
            onEditCameraClick = { cam -> editingCamera = cam },
            onDiscoverLanClick = {
                showDiscoveryDialog = true
                viewModel.runLanDiscovery()
            }
        )
    }

    if (showAddDialog) {
        AddOrEditCameraDialog(
            initialCamera = null,
            onDismiss = { showAddDialog = false },
            onSave = { newCam -> viewModel.addOrUpdateCamera(newCam) }
        )
    }

    if (editingCamera != null) {
        AddOrEditCameraDialog(
            initialCamera = editingCamera,
            onDismiss = { editingCamera = null },
            onSave = { updated -> viewModel.addOrUpdateCamera(updated) },
            onDelete = { id -> viewModel.deleteCamera(id) }
        )
    }

    if (showDiscoveryDialog) {
        LanDiscoveryDialog(
            isDiscovering = isDiscovering,
            discoveryStatus = discoveryStatus,
            discoveredCameras = discoveredCameras,
            existingCameras = cameras,
            onRescan = { viewModel.runLanDiscovery() },
            onAddCamera = { disc, customName, password ->
                viewModel.addDiscoveredCamera(disc, customName, password)
            },
            onDismiss = { showDiscoveryDialog = false }
        )
    }
}
