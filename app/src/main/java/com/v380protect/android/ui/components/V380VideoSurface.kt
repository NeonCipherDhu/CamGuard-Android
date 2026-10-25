package com.v380protect.android.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.v380protect.android.media.CameraStreamController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoSurfaceHandle {
    internal var textureView: TextureView? = null

    fun captureSnapshotToGallery(
        context: Context,
        cameraName: String,
        camera: com.v380protect.android.model.CameraConfig? = null
    ): String? {
        val tv = textureView ?: return null
        val bmp = try { tv.bitmap } catch (_: Exception) { null } ?: return null
        return try {
            val safeName = cameraName.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "V380" }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${safeName}_$ts.jpg"

            if (camera != null) {
                try {
                    val localDir = com.v380protect.android.media.FootageBackupStore.getLocalCapturesRoot(context, camera)
                    val localFile = java.io.File(localDir, filename)
                    localFile.outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                } catch (_: Exception) {
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/V380Protect")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            filename
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun V380VideoSurface(
    controller: CameraStreamController,
    modifier: Modifier = Modifier,
    enablePinchZoom: Boolean = false,
    handle: VideoSurfaceHandle? = null,
    onSingleTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null
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

    val gestureModifier = if (enablePinchZoom) {
        Modifier
            .pointerInput(controller) {
                detectTapGestures(
                    onTap = { onSingleTap?.invoke() },
                    onDoubleTap = {
                        if (zoom > 1.05f) {
                            zoom = 1f
                            offset = Offset.Zero
                        } else {
                            zoom = 2.5f
                        }
                        onDoubleTap?.invoke()
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
            }
    } else if (onSingleTap != null || onDoubleTap != null) {
        Modifier.pointerInput(controller) {
            detectTapGestures(
                onTap = { onSingleTap?.invoke() },
                onDoubleTap = { onDoubleTap?.invoke() }
            )
        }
    } else {
        Modifier
    }

    val telemetry by controller.telemetry.collectAsState()
    val videoAspect = if (telemetry.width > 0 && telemetry.height > 0) {
        (telemetry.width.toFloat() / telemetry.height.toFloat()).coerceIn(0.5f, 3.0f)
    } else {
        16f / 9f
    }

    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF09090B))
            .clipToBounds()
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        val containerAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else videoAspect
        val videoSizingModifier = if (containerAspect > videoAspect) {
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
                    handle?.textureView = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val s = Surface(st)
                            activeSurface = s
                            controller.attachSurface(s)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            activeSurface?.let { s ->
                                controller.detachSurface(s)
                                try { s.release() } catch (_: Exception) {}
                            }
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                        }
                    }
                }
            },
            update = { tv ->
                handle?.textureView = tv
                val st = tv.surfaceTexture
                if (st != null && activeSurface == null) {
                    val s = Surface(st)
                    activeSurface = s
                    controller.attachSurface(s)
                }
            },
            modifier = videoSizingModifier
                .graphicsLayer(
                    scaleX = zoom,
                    scaleY = zoom,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        if (enablePinchZoom && zoom > 1.05f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0xCC18181B), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f× ZOOM", zoom),
                    color = Color(0xFF60A5FA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
