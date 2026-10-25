package com.v380protect.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v380protect.android.model.CameraConfig
import com.v380protect.android.protocol.DiscoveredCamera
import com.v380protect.android.protocol.V380Discovery
import java.util.UUID

@Composable
fun AddOrEditCameraDialog(
    initialCamera: CameraConfig? = null,
    onDismiss: () -> Unit,
    onSave: (CameraConfig) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialCamera?.name ?: "") }
    var ip by remember { mutableStateOf(initialCamera?.ip ?: "") }
    var portStr by remember { mutableStateOf((initialCamera?.port ?: 8800).toString()) }
    var deviceId by remember { mutableStateOf(initialCamera?.deviceId ?: "") }
    var username by remember { mutableStateOf(initialCamera?.username ?: "admin") }
    var password by remember { mutableStateOf(initialCamera?.password ?: "") }
    var quality by remember { mutableIntStateOf(initialCamera?.quality ?: 1) }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3B82F6),
        unfocusedBorderColor = Color(0xFF27272A),
        focusedLabelColor = Color(0xFF60A5FA),
        unfocusedLabelColor = Color(0xFFA1A1AA),
        focusedTextColor = Color(0xFFF4F4F5),
        unfocusedTextColor = Color(0xFFF4F4F5),
        cursorColor = Color(0xFF3B82F6)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121215)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (initialCamera == null) "Add V380 Camera" else "Camera Settings",
                        color = Color(0xFFF4F4F5),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFA1A1AA))
                    }
                }

                Text(
                    text = "Native V380 / V380 Pro Protocol (TCP Port 8800)",
                    color = Color(0xFF71717A),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMsg = null },
                    label = { Text("Camera Name (e.g. Front Gate)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it; errorMsg = null },
                        label = { Text("Camera IP Address") },
                        placeholder = { Text("192.168.1.100", color = Color(0xFF52525B)) },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.weight(0.68f)
                    )
                    OutlinedTextField(
                        value = portStr,
                        onValueChange = { portStr = it },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors,
                        modifier = Modifier.weight(0.32f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it; errorMsg = null },
                    label = { Text("V380 Device ID (8–10 digits)") },
                    placeholder = { Text("e.g. 12345678", color = Color(0xFF52525B)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (default: admin)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("Camera Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password",
                                tint = Color(0xFFA1A1AA)
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Stream Quality",
                    color = Color(0xFFA1A1AA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(1 to "HD (High Definition)", 0 to "SD (Low Bandwidth)").forEach { (qVal, qLabel) ->
                        val selected = quality == qVal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selected) Color(0xFF1E3A8A).copy(alpha = 0.5f) else Color(0xFF18181B),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (selected) Color(0xFF3B82F6) else Color(0xFF27272A),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { quality = qVal }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = qLabel,
                                color = if (selected) Color(0xFF93C5FD) else Color(0xFFA1A1AA),
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (initialCamera != null && onDelete != null) {
                        TextButton(
                            onClick = {
                                onDelete(initialCamera.camId)
                                onDismiss()
                            }
                        ) {
                            Text("Delete Camera", color = Color(0xFFEF4444), fontSize = 13.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A))
                        ) {
                            Text("Cancel", color = Color(0xFFA1A1AA))
                        }
                        Button(
                            onClick = {
                                val cleanIp = ip.trim()
                                if (cleanIp.isEmpty()) {
                                    errorMsg = "Please enter the camera's IP address."
                                    return@Button
                                }
                                val cleanDevId = deviceId.trim()
                                val cleanName = name.trim().ifBlank {
                                    if (cleanDevId.isNotEmpty()) "V380 ($cleanDevId)" else "V380 ($cleanIp)"
                                }
                                val cleanPort = portStr.trim().toIntOrNull() ?: 8800
                                val cfg = CameraConfig(
                                    camId = initialCamera?.camId
                                        ?: (if (cleanDevId.isNotEmpty()) "v380_$cleanDevId" else UUID.randomUUID().toString().take(8)),
                                    name = cleanName,
                                    ip = cleanIp,
                                    port = cleanPort,
                                    deviceId = cleanDevId,
                                    username = username.trim().ifBlank { cleanDevId.ifBlank { "admin" } },
                                    password = password,
                                    quality = quality,
                                    audioEnabled = true,
                                    lightMode = initialCamera?.lightMode ?: "auto",
                                    imageMode = initialCamera?.imageMode ?: "auto",
                                    flipped = initialCamera?.flipped ?: false,
                                    ptzPresets = initialCamera?.ptzPresets ?: emptyList()
                                )
                                onSave(cfg)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("Save Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LanDiscoveryDialog(
    isDiscovering: Boolean,
    discoveryStatus: String,
    discoveredCameras: List<DiscoveredCamera>,
    existingCameras: List<CameraConfig>,
    onRescan: () -> Unit,
    onAddCamera: (DiscoveredCamera, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var sharedPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val manualDevIds = remember { mutableStateMapOf<String, String>() }
    val localIps = remember { V380Discovery.getLocalIpv4Addresses().filterNot { it.startsWith("10.0.2.") } }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF3B82F6),
        unfocusedBorderColor = Color(0xFF27272A),
        focusedLabelColor = Color(0xFF60A5FA),
        unfocusedLabelColor = Color(0xFFA1A1AA),
        focusedTextColor = Color(0xFFF4F4F5),
        unfocusedTextColor = Color(0xFFF4F4F5),
        cursorColor = Color(0xFF3B82F6)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121215)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Radar,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Find Cameras on Wi-Fi",
                                color = Color(0xFFF4F4F5),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (localIps.isNotEmpty()) {
                                    "Local Wi-Fi (${localIps.first()})"
                                } else {
                                    "Local Wi-Fi Network"
                                },
                                color = Color(0xFF71717A),
                                fontSize = 11.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFA1A1AA))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF18181B), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF3B82F6)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = discoveryStatus.ifBlank { "Ready to scan Wi-Fi" },
                            color = Color(0xFFD4D4D8),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!isDiscovering) {
                        IconButton(onClick = onRescan, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rescan",
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = sharedPassword,
                    onValueChange = { sharedPassword = it },
                    label = { Text("Camera Password (applied on Add)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password",
                                tint = Color(0xFFA1A1AA)
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (discoveredCameras.isEmpty() && !isDiscovering) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(0xFF09090B), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No cameras found yet.\nTap Rescan or add a camera by IP manually.",
                            color = Color(0xFF71717A),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clipToBounds(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(discoveredCameras, key = { "${it.ip}_${it.devId}" }) { disc ->
                            val alreadyAdded = existingCameras.any {
                                (disc.devId.isNotBlank() && it.deviceId == disc.devId) || it.ip == disc.ip
                            }
                            var customName by remember(disc.ip, disc.name) { mutableStateOf(disc.name) }
                            val currentDevId = manualDevIds[disc.ip] ?: disc.devId

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF18181B), RoundedCornerShape(10.dp))
                                    .border(
                                        1.dp,
                                        if (alreadyAdded) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF27272A),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = customName.ifBlank { disc.displayTitle() },
                                                color = Color(0xFFF4F4F5),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (alreadyAdded) Color(0xFF065F46).copy(alpha = 0.6f)
                                                        else Color(0xFF1D4ED8).copy(alpha = 0.6f),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (alreadyAdded) "ADDED" else "NEW",
                                                    color = if (alreadyAdded) Color(0xFF6EE7B7) else Color(0xFF93C5FD),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Text(
                                            text = buildString {
                                                append("IP: ${disc.ip}")
                                                if (currentDevId.isNotBlank()) append(" • ID: $currentDevId")
                                            },
                                            color = Color(0xFFA1A1AA),
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            val updatedDisc = disc.copy(devId = currentDevId.trim())
                                            onAddCamera(updatedDisc, customName, sharedPassword)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (alreadyAdded) Color(0xFF065F46) else Color(0xFF2563EB)
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 12.dp,
                                            vertical = 6.dp
                                        )
                                    ) {
                                        if (alreadyAdded) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFF34D399),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Update", fontSize = 12.sp, color = Color(0xFFD1FAE5))
                                        } else {
                                            Text("Add", fontSize = 12.sp, color = Color.White)
                                        }
                                    }
                                }

                                if (disc.devId.isBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = currentDevId,
                                        onValueChange = { manualDevIds[disc.ip] = it },
                                        label = { Text("Device ID (8–10 digits)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        placeholder = { Text("e.g. 12345678", color = Color(0xFF52525B)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = fieldColors,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val unaddedCameras = discoveredCameras.filter { disc ->
                        existingCameras.none {
                            (disc.devId.isNotBlank() && it.deviceId == disc.devId) || it.ip == disc.ip
                        }
                    }
                    if (discoveredCameras.isNotEmpty()) {
                        val targetsToAdd = if (unaddedCameras.isNotEmpty()) unaddedCameras else discoveredCameras
                        val btnLabel = if (unaddedCameras.isNotEmpty()) {
                            "Add New (${unaddedCameras.size})"
                        } else {
                            "Update All (${discoveredCameras.size})"
                        }
                        Button(
                            onClick = {
                                targetsToAdd.forEach { disc ->
                                    val resolvedId = (manualDevIds[disc.ip] ?: disc.devId).trim()
                                    onAddCamera(disc.copy(devId = resolvedId), disc.name, sharedPassword)
                                }
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text(btnLabel, color = Color.White, fontSize = 13.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A))
                    ) {
                        Text("Done", color = Color(0xFFD4D4D8))
                    }
                }
            }
        }
    }
}
