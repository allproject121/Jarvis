# God's Eye View × JARVIS Integration Architecture Report

**Document Date:** October 14, 2026  
**Auditor:** Antigravity AI  
**Scope:** Architecture evaluation, comparison of integration options, frontend serving strategy, native WebView communication, and Gemini Live tool bridging.

---

## 1. Architectural Objective

The goal is to seamlessly unify the **JARVIS Android Voice Assistant** (`com.jarvis.assistant`) with the **God's Eye View 3D Geospatial Intelligence Console** (`gods-eye-view`) into a cohesive, high-performance, hands-free tactical system.

---

## 2. Evaluation of Integration Options

Three distinct integration architectures were analyzed during the Phase 1 audit:

### Option A: Plan A — Local Loopback Node/Vite Service + Hardware-Accelerated WebView (RECOMMENDED)

```
+-------------------------------------------------------------+
|                     JARVIS Android App                      |
|                                                             |
|   +-------------------+          +----------------------+   |
|   |  Home / Voice AI  |          |   God's Eye View     |   |
|   |   (Gemini Live)   |          |      Activity        |   |
|   +---------+---------+          +----------+-----------+   |
|             |                               |               |
|             | HTTP JSON-RPC                 | WebGL 2       |
|             | Tool Calls                    | Render        |
|             |                               |               |
+-------------+-------------------------------+---------------+
              |                               |
       http://127.0.0.1:4173/mcp       http://127.0.0.1:4173/#...
              |                               |
+-------------v-------------------------------v---------------+
|          God's Eye View Production Server (Loopback)        |
|                                                             |
|   - Serves compiled CesiumJS web application from dist/     |
|   - Binds strictly to 127.0.0.1:4173 (loopback isolated)    |
|   - Proxies live streaming APIs (/api/military, /api/cctv)  |
|   - Runs local MCP tool server at /mcp                      |
+-------------------------------------------------------------+
```

- **How it works:**
  - The God's Eye View frontend is compiled into a production bundle via `vite build` (804 modules, Cesium WebAssembly decoders, minified CSS/JS).
  - The production bundle is hosted on local IPv4 loopback (`http://127.0.0.1:4173/`) using Vite's production preview server (`vite preview --host 127.0.0.1 --port 4173`).
  - Android loads the console via [`GodsEyeActivity`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeActivity.kt) with hardware-accelerated WebGL 2.
  - JARVIS's background conversational AI engine ([`JarvisConversationService`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt)) queries live geospatial intelligence via the loopback MCP endpoint ([`GodsEyeMcpClient`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt) connecting to `http://localhost:4173/mcp`).
- **Pros:**
  1. **Preserves 100% of Live Data Feeds:** All 25 live data proxy plugins (`server/providers/`) run natively, circumventing browser CORS restrictions, managing token rotations, handling HLS video chunk buffering, and decoding GRIB2 binary weather data.
  2. **Security & Secrets Isolation:** External API credentials (e.g. `GOOGLE_MAPS_SERVER_API_KEY`, `AISSTREAM_API_KEY`, `FIRMS_MAP_KEY`) remain securely on the server side and never leak into client JavaScript or the Android APK bundle.
  3. **Full MCP Availability:** Exposes the complete 41-tool catalog over HTTP JSON-RPC without duplicating geospatial logic in Kotlin.
  4. **Uncompromised Cesium Performance:** WebGL 2, web workers (`worker-src blob:`), and WASM decoders run smoothly with strict Content-Security-Policy.
- **Cons:** Requires a local Node.js runtime process (e.g., in Termux or an embedded background sidecar).

---

### Option B: Pure Asset Bundling (`file:///android_asset/`) in Android WebView (REJECTED)

- **How it works:** Static HTML, JS, CSS, and Cesium assets are copied directly into `app/src/main/assets/` and loaded via `webView.loadUrl("file:///android_asset/index.html")`.
- **Why this Option Fails (Blockers):**
  1. **Fatal CORS & Proxy Blocker:** God's Eye View relies on server-side `/api/*` proxies (`/api/military`, `/api/flights`, `/api/cctv`, `/api/wind`, `/api/firms`) to query upstream providers that do not send permissive `Access-Control-Allow-Origin: *` headers. In a purely static WebView, all cross-origin requests to third-party endpoints will be blocked by Chromium origin policies.
  2. **Credential Exposure Risk:** Any API keys required by data providers would have to be bundled directly into the APK assets, exposing them to reverse engineering and extraction.
  3. **Loss of Server-Side Processing:** Features like GRIB2 wind stream decoding, in-memory HLS video buffering for CCTV (`/api/cctv/stream`), and rate-limited token caching cannot run without the Node.js backend.
  4. **Loss of MCP Endpoint:** The `/mcp` tool server would not exist, preventing JARVIS voice from querying live intelligence.

---

### Option C: Remote Cloud Server Hosting (NOT RECOMMENDED)

- **How it works:** Host God's Eye View on an external cloud VM (e.g. Google Cloud Run or AWS EC2) and connect Android WebView to `https://my-godseye-server.com`.
- **Why this Option is Inferior:**
  1. **Continuous Recurring Costs:** Requires a continuously running cloud instance and outbound egress bandwidth for streaming video and map tiles.
  2. **Latency & Privacy:** Loopback latency is <1 ms; cloud latency adds network jitter and transmits location coordinates off-device.
  3. **Offline / Flight Mode Degradation:** Fails completely when internet connectivity drops, whereas local loopback can still visualize bundled offline layers (Natural Earth regions, submarine cables, datacenters, dams, offline geocoder).

---

## 3. Recommended Architecture Details (Plan A)

### 3.1 Project Structure & Separation of Concerns

```
C:\Users\Roshan\Documents\Jarvis\
├── app/                                 # Native Android Application (JARVIS)
│   ├── src/main/java/com/jarvis/assistant/
│   │   ├── background/
│   │   │   └── JarvisConversationService.kt  # Central foreground service & tool dispatcher
│   │   ├── network/
│   │   │   └── GeminiLiveWebSocket.kt        # Gemini Live BidiSetup & WebSocket transport
│   │   ├── ui/godseye/
│   │   │   ├── GodsEyeActivity.kt            # Hardware-accelerated WebView activity
│   │   │   └── GodsEyeMcpClient.kt           # JSON-RPC client to http://127.0.0.1:4173/mcp
│   │   └── res/xml/
│   │       └── network_security_config.xml   # Strict loopback-only cleartext rule
└── gods-eye-view-main/                  # Unmodified God's Eye View Repository
    ├── server/
    │   ├── mcp/                              # MCP JSON-RPC server on /mcp
    │   └── providers/                        # Live data provider proxies (/api/*)
    ├── src/                                  # Cesium globe, layers, and cyber HUD
    └── dist/                                 # Optimized production build artifacts
```

### 3.2 Frontend Build & Asset Strategy

1. **Deterministic Build Pipeline:**
   ```bash
   cd gods-eye-view-main
   npm run build
   ```
   Generates optimized assets in `dist/` with:
   - CesiumJS runtime and WebGL 2 shaders.
   - Pre-bundled web workers for ADS-B signal processing and vector tile parsing.
   - WebAssembly modules for EGM96 geoid and GRIB2 wind decoding.
2. **Serving Engine:**
   ```bash
   npm run preview -- --host 127.0.0.1 --port 4173
   ```
   - Serves static assets from `dist/` with HTTP caching headers.
   - Vite `configurePreviewServer` hooks dynamically attach all `/api/*` proxies and the `/mcp` tool server on the exact same port (`4173`).
   - Bound exclusively to IPv4 loopback (`127.0.0.1`), ensuring zero external network exposure.

---

### 3.3 Android WebView Configuration & Lifecycle

In [`GodsEyeActivity.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeActivity.kt):

- **Hardware Acceleration:**
  ```xml
  <activity
      android:name=".ui.godseye.GodsEyeActivity"
      android:hardwareAccelerated="true"
      android:launchMode="singleTop"
      android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize" />
  ```
- **Security Hardening (Local Isolation):**
  - `allowFileAccess = false`
  - `allowContentAccess = false`
  - `allowFileAccessFromFileURLs = false`
  - `allowUniversalAccessFromFileURLs = false`
  - Origin Isolation: `shouldOverrideUrlLoading` intercepts all non-loopback URLs and routes them externally via Android `Intent.ACTION_VIEW`.
- **Camera Repositioning Without Page Reloads:**
  When JARVIS Gemini Live voice tools return a camera hash (e.g. `#v=2&lat=35.68&lon=139.76&alt=1500000`), `GodsEyeActivity` receives it in `onNewIntent` and evaluates:
  ```kotlin
  webView.evaluateJavascript("window.location.hash = '$hash';", null)
  ```
  This instructs Cesium's internal hash watcher to smoothly fly the camera to the target coordinates without reloading the DOM or losing WebGL context.

---

### 3.4 Gemini Live Tool Integration & Dispatch Flow

1. **Declaration:** Gemini Live setup message in [`GeminiLiveWebSocket.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt#L646-L832) declares 10 God's Eye View tools:
   - `open_gods_eye_view`
   - `gods_eye_show_view`
   - `gods_eye_situation_brief`
   - `gods_eye_get_earthquakes`
   - `gods_eye_get_active_fires`
   - `gods_eye_aircraft_in_area`
   - `gods_eye_satellites_overhead`
   - `gods_eye_get_recent_launches`
   - `gods_eye_military_awareness`
   - `gods_eye_find_cctv_cameras`
2. **Dispatch & Execution:**
   - User speaks: *"Jarvis, show me live military flights in the Persian Gulf."*
   - Gemini Live detects intent and emits `onToolCall("call_123", "gods_eye_military_awareness", {"area": "Persian Gulf", "show_on_globe": true})`.
   - [`JarvisConversationService.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt#L2317) receives the call and forwards it to [`GodsEyeMcpClient.executeTool`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt#L32).
   - `GodsEyeMcpClient` sends HTTP POST JSON-RPC to `http://localhost:4173/mcp`.
   - MCP executes the query deterministically against adsb.lol, computes camera coordinates, and returns structured flight telemetry + camera view hash.
   - If `show_on_globe` is true, `GodsEyeMcpClient.launchOrUpdateGodsEye` launches or brings `GodsEyeActivity` to the top.
   - `JarvisConversationService` sends the structured flight telemetry back to Gemini Live via `sendToolResponse`.
   - Gemini Live speaks the situational brief aloud to the user while the 3D globe displays the tactical aircraft positions on screen.

---

### 3.5 Permissions & Network Security

1. **Cleartext Security Configuration:**
   [`app/src/main/res/xml/network_security_config.xml`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/res/xml/network_security_config.xml) enforces:
   ```xml
   <base-config cleartextTrafficPermitted="false" />
   <domain-config cleartextTrafficPermitted="true">
       <domain includeSubdomains="false">127.0.0.1</domain>
       <domain includeSubdomains="false">localhost</domain>
   </domain-config>
   ```
   External HTTP is strictly prohibited; only the local loopback server is permitted cleartext communication.
2. **Android Runtime Permissions:**
   - `ACCESS_FINE_LOCATION`: Required if the user activates "My Location" on the globe.
   - `RECORD_AUDIO`: Used exclusively by JARVIS's native voice pipeline ([`AudioRecorder.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/audio/AudioRecorder.kt)) for Gemini Live; the WebView never requests direct microphone access.
   - `INTERNET`: Required for external live data streams and Gemini WebSocket.

---

### 3.6 Error Handling & Fault Recovery

1. **Service Not Running:**
   If the user opens `GodsEyeActivity` when `http://127.0.0.1:4173/` is offline:
   - WebView catches `onReceivedError` in `WebViewClient`.
   - Displays a custom dark cyber error overlay with **RETRY** and **BACK** buttons.
   - `GodsEyeMcpClient` catches `ConnectException` and returns a clean error object: `"Failed to connect to God's Eye console on localhost:4173. Ensure the service is running."`
   - Gemini informs the user gracefully without crashing.
2. **Provider Failures & Throttles:**
   - If OpenSky is throttled, server automatically falls back to adsb.lol.
   - If Google Geocoding fails, server automatically falls back to Photon and Nominatim.
   - If Re:Earth terrain is unreachable, Cesium falls back to flat ellipsoid and bundled EGM96 geoid math.
   - If TomTom key is absent, traffic runs on built-in offline simulation.
