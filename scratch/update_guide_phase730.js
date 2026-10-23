const fs = require('fs');
let content = fs.readFileSync('guide.md', 'utf8');

const phase730 = `
---

### 7.30 God's Eye View - Full End-to-End Phone Layout & Verification (v1.9.4 - Build 150)
- **Defensive JavaScript & Zero SyntaxError Fix:**
  - In GodsEyeActivity.kt, replaced manual string interpolation in evaluateJavascript calls with JSONObject.quote(). Completely eliminated the "Uncaught SyntaxError: missing ) after argument list" crash.
- **Direct ADB Activity Launching:**
  - In AndroidManifest.xml, configured GodsEyeActivity as android:exported="true" allowing direct execution via ADB:
    adb shell am start -n com.jarvis.assistant/.ui.godseye.GodsEyeActivity
- **Google Places API v1 (New) Direct POST Contract:**
  - Implemented /api/google/nearby-places and /api/google/text-search with proper POST requests and JSON bodies (locationRestriction and textQuery + locationBias) with toRequestBody, projecting results into { places: [...] } format expected by placeProviderPayloads.js.
- **Windows Gradle File-Lock Fix:**
  - Configured gradle.properties with kotlin.incremental=false and org.gradle.parallel=false to resolve Windows lock contention on lookups.tab and compressDebugAssets.
- **Build & Verification:**
  - Executed clean package build: BUILD SUCCESSFUL in 5m 5s.
  - Verified APK signature using apksigner verify --verbose (v2 scheme valid).
  - Deployed to phone (10BG1D01DA007QG) via adb install -r (Success).
  - Synced to phone storage: /sdcard/Download/Jarvis-GodsEye.apk (363.77 MB) and PC Downloads: C:\\Users\\Roshan\\Downloads\\Jarvis-GodsEye-PhoneLayout-Fixed.apk.
- **Physical Device Live Verification (iQOO Neo 10R - 1260x2800):**
  - **WebGL Probing:** Verified logcat: "WebView WebGL probe result: WEBGL_OK".
  - **Live Flights:** Verified real-time telemetry streaming (202 planes in range, proximity capped at 150, refreshing smoothly every 5s).
  - **Live CCTV Cameras:** 4,000 global cameras loaded; verified live traffic snapshot frames rendering on 3D globe (Austin, Texas; Taiwan; Hong Kong; Florida; etc.).
  - **PR #1325 Mobile Drawer UI:** Verified responsive bottom tab bar ([ Layers | Cameras | Context | Search | More ]). Tapping tabs opens clean bottom sheets without desktop panel clutter.
  - **Event Reconstruction:** Verified modal does not auto-open or interfere with map controls.
  - **Responsiveness:** Verified map panning, 3D tilt, zoom, layer toggles, and soft keyboard on Search.
`;

content += phase730;
fs.writeFileSync('guide.md', content, 'utf8');
console.log('guide.md updated successfully.');
