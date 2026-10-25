package com.camguard.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camguard.android.media.CameraStreamController
import com.camguard.android.model.CameraConfig
import com.camguard.android.model.CameraRepository
import com.camguard.android.model.PtzPreset
import com.camguard.android.protocol.DiscoveredCamera
import com.camguard.android.protocol.LanDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = CameraRepository(application)

    private val _cameras = MutableStateFlow<List<CameraConfig>>(emptyList())
    val cameras: StateFlow<List<CameraConfig>> = _cameras.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    private val _footageReviewCameraId = MutableStateFlow<String?>(null)
    val footageReviewCameraId: StateFlow<String?> = _footageReviewCameraId.asStateFlow()

    private val _gridColumns = MutableStateFlow(1) // 1 = large cards, 2 = 2-column compact grid
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    // Discovery state
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryStatus = MutableStateFlow("")
    val discoveryStatus: StateFlow<String> = _discoveryStatus.asStateFlow()

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras.asStateFlow()

    private val controllers = mutableMapOf<String, CameraStreamController>()

    init {
        _gridColumns.value = repo.loadGridColumns()
        val saved = repo.loadCameras()
        _cameras.value = saved
        saved.forEach { cfg ->
            getOrCreateController(cfg).start()
        }
    }

    fun getOrCreateController(config: CameraConfig): CameraStreamController {
        synchronized(controllers) {
            val existing = controllers[config.camId]
            if (existing != null) return existing
            val created = CameraStreamController(config)
            controllers[config.camId] = created
            return created
        }
    }

    fun selectCamera(camId: String?) {
        _selectedCameraId.value = camId
    }

    fun openFootageReview(camId: String?) {
        val prevId = _footageReviewCameraId.value
        if (prevId == camId) return
        _footageReviewCameraId.value = camId

        if (camId != null) {
            // Mute all cameras and pause the target camera's live stream while using SD Card Footage Review
            // so TCP port 8800 SD sessions never collide with live stream reconnects.
            synchronized(controllers) {
                controllers.forEach { (id, ctrl) ->
                    ctrl.setMuted(true)
                    if (id == camId) {
                        Thread { ctrl.stop() }.apply { isDaemon = true }.start()
                    }
                }
            }
        } else if (prevId != null) {
            // Resume live stream when leaving Footage Review
            val cam = _cameras.value.find { it.camId == prevId }
            if (cam != null) {
                val ctrl = getOrCreateController(cam)
                Thread { ctrl.reconnect(cam) }.apply { isDaemon = true }.start()
            }
        }
    }

    fun toggleGridColumns() {
        val next = if (_gridColumns.value == 1) 2 else 1
        _gridColumns.value = next
        repo.saveGridColumns(next)
    }

    fun moveCameraById(fromCamId: String, toCamId: String): Boolean {
        if (fromCamId == toCamId) return false
        val current = _cameras.value.toMutableList()
        val fromIndex = current.indexOfFirst { it.camId == fromCamId }
        val toIndex = current.indexOfFirst { it.camId == toCamId }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return false
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        _cameras.value = current
        repo.saveCameras(current)
        return true
    }

    fun persistCameraOrder() {
        repo.saveCameras(_cameras.value)
    }

    fun addOrUpdateCamera(config: CameraConfig) {
        val current = _cameras.value.toMutableList()
        val idx = current.indexOfFirst { it.camId == config.camId }
        if (idx >= 0) {
            current[idx] = config
        } else {
            current.add(config)
        }
        _cameras.value = current
        repo.saveCameras(current)
        repo.saveCustomName(config.camId, config.deviceId, config.ip, config.name)

        val ctrl = getOrCreateController(config)
        ctrl.reconnect(config)
    }

    fun deleteCamera(camId: String) {
        synchronized(controllers) {
            controllers.remove(camId)?.stop()
        }
        val removed = _cameras.value.find { it.camId == camId }
        if (removed != null) {
            repo.removeCustomName(removed.camId, removed.deviceId, removed.ip)
        }
        val next = _cameras.value.filterNot { it.camId == camId }
        _cameras.value = next
        repo.saveCameras(next)
        if (_selectedCameraId.value == camId) {
            _selectedCameraId.value = null
        }
    }

    fun reconnectAll() {
        _cameras.value.forEach { cfg ->
            getOrCreateController(cfg).reconnect(cfg)
        }
    }

    fun toggleMuteExclusive(camId: String) {
        val targetCtrl = controllers[camId] ?: return
        val willUnmute = targetCtrl.telemetry.value.isMuted
        if (willUnmute) {
            // Mute other cameras so only one plays audio at a time
            controllers.forEach { (id, ctrl) ->
                if (id != camId) ctrl.setMuted(true)
            }
            targetCtrl.setMuted(false)
        } else {
            targetCtrl.setMuted(true)
        }
    }

    fun updateCameraQuality(camId: String, quality: Int) {
        val ctrl = controllers[camId] ?: return
        ctrl.setQuality(quality)
        val updated = _cameras.value.map {
            if (it.camId == camId) it.copy(quality = quality) else it
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun updateLightMode(camId: String, mode: String) {
        val ctrl = controllers[camId] ?: return
        ctrl.setLightMode(mode)
        val updated = _cameras.value.map {
            if (it.camId == camId) it.copy(lightMode = mode) else it
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun updateImageMode(camId: String, mode: String) {
        val ctrl = controllers[camId] ?: return
        ctrl.setImageMode(mode)
        val updated = _cameras.value.map {
            if (it.camId == camId) it.copy(imageMode = mode) else it
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun flipCameraImage(camId: String) {
        val ctrl = controllers[camId] ?: return
        ctrl.flipImage()
        val updated = _cameras.value.map {
            if (it.camId == camId) it.copy(flipped = !it.flipped) else it
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun savePtzPreset(camId: String, slotId: Int, label: String) {
        val ctrl = controllers[camId] ?: return
        ctrl.ptzPresetSet(slotId)
        val cleanName = label.trim().ifBlank { "Preset ${slotId + 1}" }
        val updated = _cameras.value.map { cam ->
            if (cam.camId == camId) {
                val filtered = cam.ptzPresets.filterNot { it.slotId == slotId }.toMutableList()
                filtered.add(PtzPreset(slotId = slotId, name = cleanName))
                filtered.sortBy { it.slotId }
                cam.copy(ptzPresets = filtered)
            } else {
                cam
            }
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun deletePtzPreset(camId: String, slotId: Int) {
        val ctrl = controllers[camId] ?: return
        ctrl.ptzPresetClear(slotId)
        val updated = _cameras.value.map { cam ->
            if (cam.camId == camId) {
                cam.copy(ptzPresets = cam.ptzPresets.filterNot { it.slotId == slotId })
            } else {
                cam
            }
        }
        _cameras.value = updated
        repo.saveCameras(updated)
    }

    fun runLanDiscovery() {
        if (_isDiscovering.value) return
        _isDiscovering.value = true
        _discoveryStatus.value = "Searching Wi-Fi..."
        _discoveredCameras.value = emptyList()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingList = _cameras.value
                val found = LanDiscovery.discover(
                    context = getApplication(),
                    timeoutSec = 2.2,
                    scanTcp8800 = true,
                    progressCb = { msg ->
                        _discoveryStatus.value = msg
                    }
                ).map { dev ->
                    val matchedExisting = existingList.find {
                        (dev.devId.isNotBlank() && it.deviceId == dev.devId) || it.ip == dev.ip
                    }
                    val resolvedDevId = dev.devId.ifBlank { matchedExisting?.deviceId ?: "" }
                    val savedName = repo.lookupCustomName(resolvedDevId, dev.ip)
                    dev.copy(
                        devId = resolvedDevId,
                        name = if (!savedName.isNullOrBlank()) savedName else dev.name
                    )
                }.sortedBy { dev ->
                    val isAdded = existingList.any {
                        (dev.devId.isNotBlank() && it.deviceId == dev.devId) || it.ip == dev.ip
                    }
                    if (isAdded) 1 else 0
                }
                _discoveredCameras.value = found
                val newCount = found.count { dev ->
                    existingList.none {
                        (dev.devId.isNotBlank() && it.deviceId == dev.devId) || it.ip == dev.ip
                    }
                }
                _discoveryStatus.value = when {
                    found.isEmpty() -> "No cameras found on Wi-Fi"
                    newCount > 0 -> "Found ${found.size} cameras ($newCount new)"
                    else -> "Found ${found.size} cameras (all added)"
                }
            } catch (e: Exception) {
                _discoveryStatus.value = "Discovery error: ${e.message ?: "unknown"}"
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    fun addDiscoveredCamera(
        disc: DiscoveredCamera,
        customName: String,
        password: String,
        quality: Int = 1
    ) {
        val existing = _cameras.value.find {
            (disc.devId.isNotBlank() && it.deviceId == disc.devId) || it.ip == disc.ip
        }
        val camId = existing?.camId ?: if (disc.devId.isNotBlank()) "cam_${disc.devId}" else "cam_${disc.ip.replace('.', '_')}"
        val finalName = customName.trim().ifBlank {
            disc.name.ifBlank {
                if (disc.devId.isNotBlank()) "Camera (${disc.devId})" else "Camera (${disc.ip})"
            }
        }
        val username = if (disc.devId.isNotBlank()) disc.devId else "admin"

        val cfg = CameraConfig(
            camId = camId,
            name = finalName,
            ip = disc.ip,
            port = disc.port,
            deviceId = disc.devId,
            username = username,
            password = password,
            quality = quality
        )
        addOrUpdateCamera(cfg)
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(controllers) {
            controllers.values.forEach { it.stop() }
            controllers.clear()
        }
    }
}
