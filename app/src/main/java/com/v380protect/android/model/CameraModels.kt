package com.v380protect.android.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PtzPreset(
    val slotId: Int,
    val name: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slot_id", slotId)
        put("name", name)
    }

    companion object {
        fun fromJson(obj: JSONObject): PtzPreset = PtzPreset(
            slotId = obj.optInt("slot_id", 0).coerceIn(0, 5),
            name = obj.optString("name", "Slot ${obj.optInt("slot_id", 0) + 1}")
        )
    }
}

data class CameraConfig(
    val camId: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val ip: String,
    val port: Int = 8800,
    val deviceId: String = "",
    val username: String = "admin",
    val password: String = "",
    val quality: Int = 1, // 1 = HD, 0 = SD
    val audioEnabled: Boolean = true,
    val lightMode: String = "auto", // "on" | "off" | "auto"
    val imageMode: String = "auto", // "color" | "bw" | "auto"
    val flipped: Boolean = false,
    val ptzPresets: List<PtzPreset> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cam_id", camId)
        put("name", name)
        put("ip", ip)
        put("port", port)
        put("device_id", deviceId)
        put("username", username)
        put("password", password)
        put("quality", quality)
        put("audio_enabled", audioEnabled)
        put("light_mode", lightMode)
        put("image_mode", imageMode)
        put("flipped", flipped)
        val presetsArr = JSONArray()
        ptzPresets.forEach { presetsArr.put(it.toJson()) }
        put("ptz_presets", presetsArr)
    }

    companion object {
        fun fromJson(obj: JSONObject): CameraConfig {
            val presets = mutableListOf<PtzPreset>()
            val arr = obj.optJSONArray("ptz_presets")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    presets.add(PtzPreset.fromJson(item))
                }
            }
            return CameraConfig(
                camId = obj.optString("cam_id", UUID.randomUUID().toString().take(8)),
                name = obj.optString("name", "V380 Camera"),
                ip = obj.optString("ip", ""),
                port = obj.optInt("port", 8800),
                deviceId = obj.optString("device_id", ""),
                username = obj.optString("username", "admin").ifBlank { "admin" },
                password = obj.optString("password", ""),
                quality = obj.optInt("quality", 1),
                audioEnabled = obj.optBoolean("audio_enabled", true),
                lightMode = obj.optString("light_mode", "auto"),
                imageMode = obj.optString("image_mode", "auto"),
                flipped = obj.optBoolean("flipped", false),
                ptzPresets = presets
            )
        }
    }
}

class CameraRepository(context: Context) {
    private val prefs = context.getSharedPreferences("v380_protect_prefs", Context.MODE_PRIVATE)

    fun loadCameras(): List<CameraConfig> {
        val raw = prefs.getString("cameras_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CameraConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(CameraConfig.fromJson(obj))
            }
            val orderRaw = prefs.getString("camera_order_json", null)
            if (!orderRaw.isNullOrBlank()) {
                val orderArr = JSONArray(orderRaw)
                val orderMap = mutableMapOf<String, Int>()
                for (i in 0 until orderArr.length()) {
                    val key = orderArr.optString(i)
                    if (key.isNotBlank()) orderMap[key] = i
                }
                if (orderMap.isNotEmpty()) {
                    list.sortWith(compareBy { cam ->
                        orderMap["id:${cam.camId}"]
                            ?: (if (cam.deviceId.isNotBlank()) orderMap["dev:${cam.deviceId}"] else null)
                            ?: (if (cam.ip.isNotBlank()) orderMap["ip:${cam.ip}"] else null)
                            ?: Int.MAX_VALUE
                    })
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCameras(cameras: List<CameraConfig>) {
        val arr = JSONArray()
        val orderArr = JSONArray()
        cameras.forEach { cam ->
            arr.put(cam.toJson())
            if (cam.camId.isNotBlank()) orderArr.put("id:${cam.camId}")
            if (cam.deviceId.isNotBlank()) orderArr.put("dev:${cam.deviceId}")
            if (cam.ip.isNotBlank()) orderArr.put("ip:${cam.ip}")
        }
        prefs.edit()
            .putString("cameras_json", arr.toString())
            .putString("camera_order_json", orderArr.toString())
            .apply()
    }

    fun loadGridColumns(): Int {
        return prefs.getInt("grid_columns", 1).coerceIn(1, 2)
    }

    fun saveGridColumns(columns: Int) {
        prefs.edit().putInt("grid_columns", columns.coerceIn(1, 2)).apply()
    }

    fun saveCustomName(camId: String, deviceId: String, ip: String, name: String) {
        val raw = prefs.getString("camera_names_json", "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        if (camId.isNotBlank()) obj.put("id:$camId", name)
        if (deviceId.isNotBlank()) obj.put("dev:$deviceId", name)
        if (ip.isNotBlank()) obj.put("ip:$ip", name)
        prefs.edit().putString("camera_names_json", obj.toString()).apply()
    }

    fun removeCustomName(camId: String, deviceId: String, ip: String) {
        val raw = prefs.getString("camera_names_json", "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (_: Exception) { return }
        if (camId.isNotBlank()) obj.remove("id:$camId")
        if (deviceId.isNotBlank()) obj.remove("dev:$deviceId")
        if (ip.isNotBlank()) obj.remove("ip:$ip")
        prefs.edit().putString("camera_names_json", obj.toString()).apply()
    }

    fun lookupCustomName(deviceId: String, ip: String): String? {
        val raw = prefs.getString("camera_names_json", "{}") ?: "{}"
        val obj = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (deviceId.isNotBlank() && obj.has("dev:$deviceId")) {
            return obj.optString("dev:$deviceId").takeIf { it.isNotBlank() }
        }
        if (ip.isNotBlank() && obj.has("ip:$ip")) {
            return obj.optString("ip:$ip").takeIf { it.isNotBlank() }
        }
        return null
    }
}
