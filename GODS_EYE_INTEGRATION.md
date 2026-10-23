# God's Eye View Architecture & JARVIS Integration Guide

## 1. Overview
God's Eye View is an open-source real-time geospatial intelligence console built by Bilawal Sidhu using CesiumJS, Vite, and Node.js. This document records the integration architecture into JARVIS Part 7 Android application under **Plan A**.

## 2. Plan A Architecture

```
+-------------------------------------------------------------+
|                     JARVIS Android App                      |
|                                                             |
|   +-------------------+          +----------------------+   |
|   |  Home / Voice AI  |          |   God's Eye View     |   |
|   |   (Gemini Live)   |          |      Activity        |   |
|   +-------------------+          +----------+-----------+   |
|                                             |               |
|                                    Android WebView          |
|                             (Hardware-Accelerated WebGL 2)  |
|                                             |               |
+---------------------------------------------+---------------+
                                              |
                                     http://127.0.0.1:4173/
                                              |
+---------------------------------------------v---------------+
|              God's Eye View (Isolated ~/gods-eye-view)       |
|                                                             |
|   +-----------------------------------------------------+   |
|   |              CesiumJS 3D Virtual Globe              |   |
|   |          GLSL Optics Shaders (FLIR, NVG, CRT)       |   |
|   |            Tactical HUD & Cockpit Telemetry         |   |
|   +--------------------------+--------------------------+   |
|                              |                              |
|                 Production Vite Preview Server              |
|        Serves ~/gods-eye-view/dist/ + /api/* Proxy Plugins  |
|                              |                              |
+------------------------------+------------------------------+
                               |
                   External Data Providers
    (adsb.lol, CelesTrak, USGS, Esri World Imagery, HLS CCTV)
```

## 3. Production Hosting Architecture (Phase 4 Verified)
- **Serving Engine:** `vite preview --host 127.0.0.1 --port 4173`
- **Asset Source:** `~/gods-eye-view/dist/` (804 compiled production modules, minified, with Cesium.js).
- **Backend Proxy Lifecycle:** All proxy plugins (`server/providers/`) implement Vite's `configurePreviewServer` hook, ensuring that production mode serves static HTML/JS/CSS assets AND proxies live data routes (`/api/military`, `/api/celestrak`, `/api/wind`, `/api/cctv`) simultaneously on the same origin.
- **Network Security & Binding:**
  - Strictly bound to IPv4 loopback (`127.0.0.1:4173`).
  - No exposure to LAN or external network interfaces (`0.0.0.0` disabled).
  - Production key-setup endpoint (`/api/key-setup`) is deliberately absent in preview mode for security hardening.
- **Android WebView Prerequisites:**
  - Android 9+ requires cleartext permission for `127.0.0.1` and `localhost` in `network_security_config.xml` to allow the loopback connection without breaking HTTPS enforcement for external traffic.

## 4. Android WebView Integration (Phase 5 Verified)
- **Activity:** `com.jarvis.assistant.ui.godseye.GodsEyeActivity`
- **Layout:** `res/layout/activity_gods_eye.xml`
- **Navigation Entry:** Action button `btnTopGodsEye` in `fragment_home.xml` top action bar.
- **Hardware Acceleration:** Enabled at Activity level (`android:hardwareAccelerated="true"`) and WebView layer level (`LAYER_TYPE_HARDWARE`) to support CesiumJS WebGL 2 globe rendering.
- **Security Hardening:**
  - Local file system access is strictly disabled (`allowFileAccess = false`, `allowContentAccess = false`, `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`).
  - Network security config scoped: Cleartext HTTP is permitted ONLY for `127.0.0.1` and `localhost`. External traffic remains strictly HTTPS.
  - Non-loopback URLs (mailto, tel, external sites) are redirected to system intents rather than navigated inside the internal WebView.
- **Resilience:**
  - Native loading progress bar while CesiumJS initializes.
  - Native offline card with "Retry Connection" button if the Termux background service is not running.

## 5. Post-Build Device Verification & Background Process Lifecycle
- **Host Device Profile:**
  - Model: `I2221` (Vivo / iQOO)
  - Android OS: Version 16 (API 36, Linux kernel 6.1.145)
  - Termux Sandbox: UID `u0_a394`
- **ADB Availability:**
  - Termux's local ADB client starts daemon on `tcp:5037` with 0 attached devices (`adb devices` is empty).
  - Without USB debugging connected to a PC or active Wireless Debugging loopback pairing (`127.0.0.1:<port>`), shell-driven automated UI testing/monkey cannot be performed from the terminal sandbox.
  - The APK is delivered to public user storage at `/storage/emulated/0/Download/Jarvis-godseye-debug.apk` and the native installer intent is initiated via `termux-open`.
- **Background Server Lifecycle on Android (Termux):**
  - Aggressive OEM battery managers (e.g. Funtouch OS on Vivo) can freeze or kill background Termux processes when the user switches to JARVIS.
  - Mitigation:
    1. Termux CPU WakeLock enabled (`termux-wake-lock`).
    2. Set Termux battery usage to "Unrestricted" / "Don't optimize" in Android system settings.
    3. Run preview server via `nohup npm run preview -- --host 127.0.0.1 --port 4173 > ~/gods-eye-view/preview.log 2>&1 &`.
- **Server Offline Recovery Behavior:**
  - If the server process is killed or stopped, `GodsEyeActivity` catches `onReceivedError` on main frame requests.
  - Displays `layoutError` with an informative error banner and exact command to relaunch the server.
  - Provides "Retry" and "Back" buttons to prevent black-screen deadlocks or crashes.

## 6. Termux Process Lifecycle Commands
- **Start Production Backend:**
  ```bash
  cd ~/gods-eye-view
  nohup npm run preview -- --host 127.0.0.1 --port 4173 > ~/gods-eye-view/preview.log 2>&1 &
  ```
- **Check Health:**
  ```bash
  curl -sI http://127.0.0.1:4173/ | head -n 1
  # HTTP/1.1 200 OK
  ```
- **Stop Backend:**
  ```bash
  pkill -f "vite preview"
  ```
