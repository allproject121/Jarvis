# Resume Instructions — God's Eye View & JARVIS Integration

## Current State
- **Active Working Phase:** Completed Phase 5 (including post-build device verification). Ready for Phase 6.
- **Production Server State:** Production preview server is running on `http://127.0.0.1:4173/`.
- **Application Directory:** `~/gods-eye-view` (`/data/data/com.termux/files/home/gods-eye-view`).
- **Android Directory:** `~/storage/document/Jarvis` (`/data/data/com.termux/files/home/storage/document/Jarvis`).
- **Verified APK:**
  - Path: `app/build/outputs/apk/debug/app-debug.apk` (and `/storage/emulated/0/Download/Jarvis-godseye-debug.apk`)
  - Size: 107,266,928 bytes (102.29 MB)
  - Package ID: `com.jarvis.assistant` (versionCode: 143, versionName: 1.8.7)
  - Signing: Debug keystore (`~/.android/debug.keystore`, 2048-bit RSA)
- **Device & Environment:** Host model `I2221` (Vivo Android 16 / SDK 36). ADB has 0 attached devices (manual APK install initiated via `termux-open`).

## Next Step to Execute
**Phase 6: Home Screen & Mobile Compatibility (Pending User Approval)**
- Verify touch gestures, Cesium globe responsiveness, zoom, and orientation handling inside Android WebView.
- Test tactical HUD overlay controls (CCTV, radar, filters) on mobile viewport.
- Optimize WebView cache/render memory consumption if needed.
- Assess Orb integration options vs dedicated activity experience.

## Exact Termux Production Backend Commands
- **Start (Background):**
  ```bash
  cd ~/gods-eye-view
  nohup npm run preview -- --host 127.0.0.1 --port 4173 > ~/gods-eye-view/preview.log 2>&1 &
  ```
- **Check Status:**
  ```bash
  curl -sI http://127.0.0.1:4173/ | head -n 1
  # Expected: HTTP/1.1 200 OK
  ```
- **Stop:**
  ```bash
  pkill -f "vite preview"
  ```
