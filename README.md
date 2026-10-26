# CamGuard (Unofficial Client for V380 Cameras)

> **Disclaimer:** This project is an independent, unofficial open-source client. It is not affiliated with, endorsed by, sponsored by, or connected to Macro-Video Technologies Co., Ltd. or the official V380 / V380 Pro brands. All product and company names are trademarks of their respective holders; their use here is strictly for identification and compatibility purposes.

Unofficial client compatible with V380 cameras, built with **Kotlin**, **Jetpack Compose**, Android hardware **`MediaCodec`**, and local network **TCP Port 8800 / UDP 10008** streaming.

---

## ✨ Features

- **Local Network Camera Protocol (TCP Port 8800 & UDP 10008/10009)**:
  - Dual-stage AES-128-ECB password authentication (`cmd 1167` -> `resp 1168`, supporting both `v31`/`v32` and classic `v2` firmware)
  - Automatic UDP `10008` + Dispatch Port 8800 wakeup for standby cameras
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
  - **Snapshots** saved directly to Android Gallery (`Pictures/CamGuard`)
- **LAN Auto-Discovery**:
  - Scans your local Wi-Fi using both UDP broadcast (`NVDEVSEARCH^100` on UDP `10008`/`10009`) and a multi-threaded **TCP Port 8800** subnet scanner.

---

## 📱 Installing the APK on Your Android Phone

1. Download **`CamGuard-Android.apk`** from the [**GitHub Releases page**](https://github.com/NeonCipherDhu/CamGuard-Android/releases/latest) ([Direct APK Download](https://github.com/NeonCipherDhu/CamGuard-Android/releases/download/v1.1.0/CamGuard-Android.apk)) onto your Android phone.
2. Tap **`CamGuard-Android.apk`** on your phone and allow *Install from unknown sources* if prompted.
3. Open **CamGuard** and follow the steps below to connect your cameras.

---

## 📷 How to Connect a Camera to the App

### Prerequisites
- Your camera must already be connected to your local Wi-Fi router and have a camera password set (initial Wi-Fi pairing is done once using the camera's hotspot or setup utility).
- Connect your Android phone to the **same Wi-Fi network (subnet)** as your camera(s).

---

### Method 1: Automatic Wi-Fi Discovery (Recommended)

1. Open **CamGuard** on your phone.
2. On the home screen, tap **Find Cameras on Wi-Fi** (or tap the top-right **☰ Menu** → **Scan Wi-Fi for Cameras**).
3. Wait a few seconds while the app scans your local network via UDP broadcast (`10008`/`10009`) and TCP Port `8800`.
4. In the **Camera Password (applied on Add)** field at the top of the dialog, enter the password you configured for your camera(s).
5. *(Optional)* If a camera was found via TCP Port `8800` scan and its **Device ID** field is blank, type the **8–10 digit Device ID** printed on the sticker/QR label on the camera body.
6. Tap **Add** next to an individual camera, or tap **Add New** at the bottom to add all discovered cameras at once.
7. Tap **Done** — your camera stream will connect and start playing automatically.

---

### Method 2: Add a Camera Manually

If your router blocks local broadcast packets or you prefer entering the camera details directly:

1. Tap **Add Camera Manually** on the home screen (or tap the top-right **☰ Menu** → **Add Camera Manually**).
2. Fill in the camera details:
   - **Camera Name**: Any friendly name (e.g., `Front Gate`, `Living Room`).
   - **Camera IP Address**: Your camera's local LAN IPv4 address (e.g., `192.168.1.100`).
   - **Port**: Leave as `8800` (default local TCP port).
   - **Device ID (8–10 digits)**: The numeric ID printed on the sticker/QR code on your camera (e.g., `12345678`). *Note: If left blank, the app will attempt to auto-query it over UDP `10008`.*
   - **Username**: Leave as `admin` (default for compatible cameras).
   - **Camera Password**: The password set for the camera.
   - **Stream Quality**: Choose **HD (High Definition)** or **SD (Low Bandwidth)**.
3. Tap **Save Camera**.

---

### 🔍 Where to Find Your Camera Details & Troubleshooting

- **Where is the Device ID?**
  - Check the physical sticker or QR code label on the bottom/back of the camera (usually labeled `ID` or `DevID`, 8 to 10 digits).
- **`Auth Error: Invalid camera password (1012)`**:
  - Tap the **⚙️ Settings** icon on the camera card, verify your camera password, and tap **Save Camera**.
- **`Auth Error: Invalid Device ID (1018)`**:
  - The camera rejected the Device ID. Open **⚙️ Settings** on the camera card and enter the exact 8–10 digit ID printed on the camera's sticker.
- **No cameras found during Wi-Fi scan**:
  - Make sure your phone is not on a Guest Wi-Fi network with *AP / Client Isolation* enabled, and turn off any active VPN on your phone so local LAN traffic (`192.168.x.x`) is reachable.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
