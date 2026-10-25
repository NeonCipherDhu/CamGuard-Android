# V380 Protect for Android

A native Android security camera client for **V380 / V380 Pro** IP cameras, built with **Kotlin**, **Jetpack Compose**, Android hardware **`MediaCodec`**, and the native **V380 TCP Port 8800 / UDP 10008** local network protocol.

---

## ✨ Features

- **Native V380 LAN Protocol (TCP Port 8800 & UDP 10008/10009)**:
  - Dual-stage AES-128-ECB password authentication (`macrovideo+*#!^@` key handshake, `cmd 1167` -> `resp 1168`, supporting both `v31`/`v32` and classic `v2` firmware)
  - Automatic UDP `10008` + Cloud Dispatch (`dispa1.av380.net:8001`) Port 8800 wakeup for standby cameras
  - Stream login & control (`cmd 301` -> `resp 401` -> `cmd 303`)
  - Firmware `v31+` 80-byte stride AES-128-ECB video decryption & audio decryption
- **Hardware-Accelerated Video & Live Audio**:
  - Low-latency hardware decoding for both **H.264 (`video/avc`)** and **H.265 / HEVC (`video/hevc`)** via Android `MediaCodec`
  - **Live Audio** supporting **AAC-LC (`0x18`)**, **G.711 A-Law (`0x1A`)**, and **IMA-ADPCM (`0x16`)** with per-camera Mute/Unmute and a live audio level indicator
  - Instant **HD (1080p) / SD (360p)** stream quality toggle
- **Adaptive Phone UI (Portrait & Fullscreen Landscape)**:
  - **1.0× – 5.0× Pinch-to-Zoom & Pan** (plus double-tap zoom reset)
  - Switchable **1-Column** and **2-Column** live camera grid
  - **Fullscreen Landscape HUD** with floating translucent PTZ D-pad and quick Light/Night-Vision controls
- **PTZ, Presets, Floodlight & Night Vision Controls**:
  - Touch-and-hold directional **PTZ Gimbal D-Pad** (`Up`, `Down`, `Left`, `Right`, `Stop`)
  - **6 Hardware PTZ Preset Slots** (`Go To`, `Set Here`, `Delete` via stream `0xAB` & port 8800 `cmd 129`)
  - **Floodlight LED** (`Auto`, `On`, `Off`), **Night Vision** (`Auto IR`, `Full Color`, `IR B&W`), and **180° Image Flip**
  - **Snapshots** saved directly to Android Gallery (`Pictures/V380Protect`)
- **LAN Auto-Discovery**:
  - Scans your local Wi-Fi using both native V380 UDP broadcast (`NVDEVSEARCH^100` on UDP `10008`/`10009`) and a multi-threaded **TCP Port 8800** subnet scanner.

---

## 📱 Installing the APK on Your Android Phone

1. Copy **`V380Protect-Android.apk`** to your Android phone (via USB cable, Google Drive, Quick Share, or Telegram/WhatsApp).
2. Tap **`V380Protect-Android.apk`** on your phone and allow *Install from unknown sources* if prompted.
3. Connect your phone to the same Wi-Fi network as your V380 cameras, open **V380 Protect**, and tap **Auto-Discover LAN (Port 8800)** or **Add Camera Manually**.
