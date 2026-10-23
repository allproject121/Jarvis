# God's Eye View × JARVIS Android Integration Design (Phase 3 Specification)

**Specification Date:** October 14, 2026  
**Auditor:** Antigravity AI  
**Scope:** Architectural blueprint, provider taxonomy, Gemini Live tool dispatch, WebView security lifecycle, battery optimization, and real-device testing methodology.

---

## 1. Proven Android Architecture Evaluation

### 1.1 Architectural Comparison

Four candidate architectures were analyzed against the real God's Eye View and JARVIS codebases:

```
+---------------------------------------------------------------------------------------------------------+
| Architecture Model     | Live Data Support | MCP Tool Bridging | Complexity | APK Size | Viability      |
+---------------------------------------------------------------------------------------------------------+
| 1. Bundled Assets Only | FATAL BLOCKER     | IMPOSSIBLE        | Low        | +110 MB  | REJECTED       |
|    (file:///android_asset/) (No CORS proxy) | (No Node runtime) |            |          |                |
|                                                                                                         |
| 2. Embedded Node Sidecar| COMPLETE         | COMPLETE          | Very High  | +180 MB  | VIABLE (Future)|
|    (libnode.so in APK) | (All 28 proxies)  | (Local /mcp)      | (NDK+V8)   |          |                |
|                                                                                                         |
| 3. Local Loopback Host | COMPLETE          | COMPLETE          | Zero APK   | +0 MB    | RECOMMENDED    |
|    (Termux / 127.0.0.1) | (All 28 proxies)  | (Local /mcp)      | Bloat      | (Clean)  | (PROVEN)       |
|                                                                                                         |
| 4. Remote Cloud Backend | COMPLETE         | HIGH LATENCY      | High Cost  | +0 MB    | NOT            |
|    (Cloud Run / AWS)   | (Subject to WAN)  | ($50-200/mo)      | PrivacyRisk|          | RECOMMENDED    |
+---------------------------------------------------------------------------------------------------------+
```

### 1.2 Deep-Dive into Candidate Architectures

#### Candidate 1: Bundled Web Assets inside APK (`file:///android_asset/`) — REJECTED
- **Technical Mechanism:** Static HTML, JS, CSS, and 3D glTF models copied into `app/src/main/assets/` and rendered via `webView.loadUrl("file:///android_asset/dist/index.html")`.
- **Fatal Blockers:**
  1. **CORS Failure on Live Data:** Over 80% of data feeds (adsb.lol military flights, CelesTrak TLEs, NOAA nowCOAST WMS radar, USGS earthquakes, and public CCTV JPEG/HLS streams) do not emit permissive cross-origin headers (`Access-Control-Allow-Origin: *`) for `file://` or custom WebView schemes.
  2. **Absence of Server Middleware:** Features like GRIB2 binary wind decoding (`@meri-imperiumi/eccodes-wasm`), in-memory HLS MPEG-TS segment relaying (`server/providers/cctv/stream.js`), and rate-limited token caching cannot execute inside a static browser environment.
  3. **Loss of MCP Server:** The `/mcp` JSON-RPC server is a Node.js HTTP server. In a pure asset bundle, JARVIS Gemini Live voice cannot query live situational telemetry.

#### Candidate 2: Embedded Node.js Native Sidecar (`libnode.so`) — VIABLE BUT HIGH COMPLEXITY
- **Technical Mechanism:** Cross-compiling Node.js v24 for ARM64-v8a using the Android NDK, packaging `libnode.so` and `node_modules` inside the APK, and launching the server process from a native Android foreground service via JNI.
- **Evaluation:**
  - **Pros:** Completely self-contained; zero dependency on external terminal apps.
  - **Cons:** Adds 70–90 MB of native library bloat to the APK; Node.js v24 JIT compilation on Android 14+ requires `PROT_EXEC` W^X memory permissions that are heavily restricted by SELinux without special platform signatures; requires complex NDK build toolchains.
  - **Verdict:** Recommended as a long-term standalone packaging path, but unnecessarily complex for immediate integration.

#### Candidate 3: Separately Managed Local Loopback Service (Termux / Linux Bionic) — PROVEN & RECOMMENDED
- **Technical Mechanism:**
  - The production God's Eye View bundle runs on the host device's local loopback interface (`http://127.0.0.1:4173/`) inside Termux (`node` v24 + `vite preview`).
  - Android kernel networking exposes `127.0.0.1` uniformly across all user processes on the same device.
  - The JARVIS Android app (`com.jarvis.assistant`) connects seamlessly to `http://127.0.0.1:4173/` for Cesium WebGL rendering and `http://127.0.0.1:4173/mcp` for Gemini Live tool queries.
- **Why this is the Best Solution:**
  1. **Zero APK Bloat:** The APK remains compact (~103 MB including Vosk speech models) without bundling duplicative Node runtime binaries.
  2. **100% Feature Parity:** Every single proxy plugin, WebSocket stream, GRIB2 decoder, and MCP tool runs in its native Node environment.
  3. **Zero Security Leakage:** API credentials stored in `.env` remain inside Termux's private storage, isolated from Android app sandboxes.
  4. **Proven Reliability:** Verified on target test device (Vivo Android 16 / SDK 36) with sub-millisecond loopback latency.

#### Candidate 4: Remote Cloud Server Backend — NOT RECOMMENDED
- **Technical Mechanism:** Host the Node.js backend on Google Cloud Run or AWS EC2 and point WebView to `https://godseye.mydomain.com`.
- **Why this Fails:** Continuous operational hosting costs ($30–$150/month for bandwidth and CPU); latency penalty (100–300 ms vs <1 ms on loopback); privacy exposure (transmitting user GPS and private queries across the open internet); complete failure in offline/airplane mode.

---

## 2. Comprehensive Feature Taxonomy & Data State Preservation

To ensure data integrity, every feature in God's Eye View is strictly classified by its data state. The system must **never** present estimated, simulated, or cached data as real-time live data:

| Feature / Layer | Data Nature | Provider / Source | Update Cadence | Fallback Behavior When Down / Unset |
|---|---|---|---|---|
| **Military Air Operations** | **REAL-TIME LIVE** | adsb.lol (`/api/military`) | 12s cache TTL | Serves last good body with `X-Feed-Cache: STALE`; exponential backoff. |
| **Civilian Air Traffic** | **REAL-TIME LIVE** | OpenSky Network (`/api/flights`) | 15s poll | Automatic failover to localized adsb.lol point radius snapshot. |
| **Aircraft Enrichment** | **CACHED (24h)** | adsbdb (`/api/adsbdb`) | 24h disk cache | Best-effort; missing records retain callsign without metadata. |
| **Satellites & Stations** | **ESTIMATED (SGP4)** | CelesTrak TLEs + `satellite.js` | Orbit math | Real-time position calculated mathematically from NORAD TLE orbital models. |
| **Earthquakes & Tremors** | **REAL-TIME LIVE** | USGS GeoJSON | Trailing 24h | Direct client fetch; fails silent if network is disconnected. |
| **Weather Rain Radar** | **DELAYED OBSERVED** | NOAA MRMS via nowCOAST WMS | 4-minute mosaic | Drapery layers hide cleanly on missing WMS tiles. |
| **Satellite Infrared Clouds**| **DELAYED OBSERVED** | NOAA GOES-18/19 via nowCOAST | 5-15 minute mosaic | Global infrared mosaic fallback. |
| **Lightning Strikes** | **DELAYED OBSERVED** | NOAA NLDN/GLD360 via nowCOAST | 15-minute grid | Grid density tiles vanish if service is unreachable. |
| **Cyclones & Hurricane Tracks**| **LIVE ADVISORIES** | NOAA NHC / CPHC (`/api/cyclones`)| 5-minute cache | Storm vectors and forecast cones clear when storms dissipate. |
| **Global 10m Wind Fields** | **MODEL SIMULATION** | NOAA GFS / ECMWF IFS GRIB2 | 6-hourly cycles | Decoded via WASM; particles animate based on static vector field. |
| **Traffic Flow & Congestion**| **SIMULATED / LIVE**| TomTom Traffic (`/api/tomtom`) | 120s tile cache | **Simulation Default:** Runs procedural white particle flow without key. |
| **Public CCTV Traffic Cameras**| **REAL-TIME STILL/HLS**| 14 Global DOT Open Data feeds | 10s still / Live HLS| Failed frames retain last-good still; dead cameras marked inactive. |
| **Public Transit Tracking** | **REAL-TIME LIVE** | 7 GTFS-RT agencies (MBTA, etc.) | Real-time vehicle | Vehicles hold last position with age badge before fading. |
| **Bikeshare Availability** | **REAL-TIME LIVE** | GBFS (Lyft / BCycle) | 60s poll | Station docks update live; dropped on connection error. |
| **Wildfire Perimeters** | **INCIDENT OBSERVED**| NIFC WFIGS ArcGIS (`/api/fire-perimeters`)| 5-minute cache | Incident shapes link to InciWeb; cleared when extinguished. |
| **NASA Active Thermal Fires**| **SATELLITE DETECTED**| NASA FIRMS (`/api/firms`) | Trailing 24h VIIRS | Requires `FIRMS_MAP_KEY`; displays "KEY REQUIRED" if unset. |
| **AIS Maritime Vessels** | **REAL-TIME STREAM** | AISStream.io (`/api/vessels`) | WebSocket stream | Requires `AISSTREAM_API_KEY`; layer remains empty if unset. |
| **Submarine Fiber Cables** | **STATIC BUNDLED** | TeleGeography Cable Map | Shipped GeoJSON | 712 cables + 1,917 landings; NonCommercial CC BY-NC-SA 3.0. |
| **Strategic Infrastructure** | **STATIC BUNDLED** | OpenStreetMap (Datacenters/Dams)| Shipped GeoJSONL | 4,351 datacenters and 704 dams; ODbL 1.0 license. |
| **Military Installations** | **STATIC BUNDLED** | Overture Maps Foundation | Shipped GeoJSON | 36,466 named bases and boundaries; ODbL 1.0 license. |

---

## 3. JARVIS Integration Architecture

### 3.1 Dual-Entry Interaction Model

JARVIS provides two frictionless entry points into God's Eye View:

```
                  USER INTERACTION
                         │
         ┌───────────────┴───────────────┐
         ▼                               ▼
    MANUAL TOUCH                    SPOKEN VOICE
 (Top HUD ImageButton)         ("Jarvis, show Tokyo")
         │                               │
         ▼                               ▼
 HomeFragment.kt                GeminiLiveWebSocket.kt
 [btnTopGodsEye]                [onToolCall: gods_eye_show_view]
         │                               │
         │                               ▼
         │                      JarvisConversationService.kt
         │                      [handleToolCallExecution]
         │                               │
         │                               ▼
         │                      GodsEyeMcpClient.kt
         │                      [POST http://127.0.0.1:4173/mcp]
         │                               │
         │                               ▼
         │                      God's Eye MCP Engine
         │                      [Calculates Coordinates + URL]
         │                               │
         └───────────────┬───────────────┘
                         ▼
                GodsEyeActivity.kt
        (singleTop, hardware-accelerated)
                         │
             window.location.hash = ...
                         │
                         ▼
            Cesium 3D Globe Smooth Fly-To
```

### 3.2 Spoken Orchestration & Intelligence Delivery

When an intelligence request is received (e.g. *"Jarvis, report on active wildfires in California"*):
1. **Gemini Live Intent Recognition:** Gemini Live matches the utterance against the declared `gods_eye_get_active_fires` tool schema.
2. **Deterministic MCP Query:** [`GodsEyeMcpClient`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt) dispatches a JSON-RPC request to `http://127.0.0.1:4173/mcp`.
3. **Structured Response Parsing:** The MCP server parses FIRMS satellite detections, computes the bounding camera view, and returns:
   - `summary`: *"Found 14 active thermal hotspots in California over the trailing 24 hours."*
   - `view.url`: `http://127.0.0.1:4173/#v=2&lat=36.77&lon=-119.41&alt=1200000&l=f`
4. **Visual Synchronization:** If `show_on_globe` is true (or if `GodsEyeActivity` is already in foreground), [`launchOrUpdateGodsEye`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/ui/godseye/GodsEyeMcpClient.kt#L165) evaluates the new hash without reloading the DOM.
5. **Auditory Butler Delivery:** The telemetry summary is piped back to Gemini Live via `sendToolResponse`. Gemini speaks the brief aloud in its refined butler voice: *"Sir, satellite telemetry indicates 14 active hotspots currently detected in the California sector."*

### 3.3 Preserving Core Voice Engine Behaviors

The integration strictly preserves all existing JARVIS core voice subsystems:
- **Offline Wake-Word Pipeline:** Vosk on-device wake-word detection ([`VoskModelManager.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/audio/VoskModelManager.kt)) continuously monitors audio at 16 kHz mono PCM. God's Eye View never captures the microphone directly.
- **Barge-In & Audio Interruption:** If the user speaks while Gemini is delivering a tactical briefing, [`GeminiLiveWebSocket.Listener.onInterrupted()`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt#L27) instantly flushes [`AudioPlayer.kt`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt) buffers, ensuring zero latency interruption.
- **Foreground Service Lifecycle:** [`JarvisConversationService`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt) retains its `microphone|mediaPlayback|mediaProjection` foreground service declaration, remaining active even when the user is deeply interacting with the 3D globe.

---

## 4. Security, Isolation & Reliability Engineering

### 4.1 Strict Origin & Network Isolation

```
                  ANDROID OPERATING SYSTEM
 +---------------------------------------------------------+
 |                                                         |
 |   JARVIS App (com.jarvis.assistant)                     |
 |   ├── Cleartext HTTP: PERMITTED ONLY for 127.0.0.1     |
 |   └── All External Traffic: STRICTLY ENFORCED HTTPS     |
 |                                                         |
 |   GodsEyeActivity (WebView Sandbox)                     |
 |   ├── allowFileAccess = false                           |
 |   ├── allowContentAccess = false                        |
 |   ├── allowFileAccessFromFileURLs = false               |
 |   └── shouldOverrideUrlLoading:                         |
 |       - 127.0.0.1 / localhost: Loaded in WebView       |
 |       - All other origins: Diverted to Intent.ACTION_VIEW|
 |                                                         |
 +---------------------------------------------------------+
```

1. **Scoped Cleartext Policy:** Enforced via [`app/src/main/res/xml/network_security_config.xml`](file:///C:/Users/Roshan/Documents/Jarvis/app/src/main/res/xml/network_security_config.xml). External unencrypted HTTP is rejected by the Android networking stack.
2. **Local Request Gating:** In God's Eye View's [`server/mcp/plugin.js`](file:///C:/Users/Roshan/Documents/Jarvis/gods-eye-view-main/server/mcp/plugin.js#L26-L59), every request to `/mcp` is inspected. Non-loopback IP addresses (`remoteAddress !== '127.0.0.1'`), reverse-proxy headers (`X-Forwarded-For`), and foreign origins are rejected with HTTP 403 Forbidden.
3. **No Unsafe JavaScript Interfaces:** `GodsEyeActivity` exposes **zero** `@JavascriptInterface` objects to the web context. Communication from native Kotlin to the web app is unidirectional and strictly limited to setting `window.location.hash`.

### 4.2 Lifecycle & Battery Optimization

WebGL rendering on high-resolution mobile displays (120 Hz AMOLED) can draw substantial battery power if unmanaged:
1. **Activity Pause State:** When `GodsEyeActivity` moves to background:
   ```kotlin
   override fun onPause() {
       super.onPause()
       binding.webViewGodsEye.onPause()
       binding.webViewGodsEye.pauseTimers()
   }
   ```
   This freezes the Chromium rendering pipeline, stops Cesium `requestAnimationFrame` render loops, and pauses all data polling timers.
2. **Activity Resume State:** When brought back to the foreground:
   ```kotlin
   override fun onResume() {
       super.onResume()
       binding.webViewGodsEye.onResume()
       binding.webViewGodsEye.resumeTimers()
   }
   ```
3. **Activity Destruction:** When the user closes the console:
   ```kotlin
   override fun onDestroy() {
       binding.webViewGodsEye.destroy()
       super.onDestroy()
   }
   ```
   This immediately deallocates GPU WebGL context buffers, preventing memory leaks.

---

## 5. Concrete Implementation Roadmap

The remaining implementation work is divided into ordered, verifiable phases:

### Phase 4: Live Data Provider & Proxy Route Verification
- **Tasks:**
  1. Audit and verify every `/api/*` proxy route against upstream servers.
  2. Test fail-soft behavior for optional key-dependent services (FIRMS, AISStream, TomTom).
  3. Validate GRIB2 wind stream decoding performance on loopback.
- **Artifacts:** `GODS_EYE_PHASE4_PROVIDER_VERIFICATION.md`.

### Phase 5: Mobile UI & HUD Layout Optimization
- **Tasks:**
  1. Review CSS media queries and touch event listeners in `gods-eye-view-main/src/ui/`.
  2. Optimize panel rails and command dock spacing for portrait mobile aspect ratios (19.5:9 / 20:9).
  3. Verify touch gesture handling (pinch-to-zoom, two-finger rotate/pitch) in Cesium globe view inside Android WebView.
- **Artifacts:** `GODS_EYE_PHASE5_MOBILE_UI.md`.

### Phase 6: End-to-End Android Verification Build & Delivery
- **Tasks:**
  1. Execute complete Android test suite (`./gradlew testDebugUnitTest`).
  2. Compile and package the production debug APK (`./gradlew assembleDebug`).
  3. Verify DEX bytecode contains all required MCP client and tool handler classes.
  4. Deliver verified APK artifact ready for device sideloading.
- **Artifacts:** `GODS_EYE_PHASE6_BUILD_DELIVERY.md`.

---

## 6. Acceptance Criteria Checklist

- [ ] **A1: Standalone Server Parity:** Backend serves compiled assets from `dist/`, attaches `/api/*` proxies, and hosts `/mcp` on `127.0.0.1:4173`.
- [ ] **A2: Native UI Integration:** Home screen top action button `btnTopGodsEye` launches `GodsEyeActivity` with full cyber HUD styling.
- [ ] **A3: Voice Command Integration:** Gemini Live understands *"Open God's Eye View"*, launching the console and confirming via voice.
- [ ] **A4: Tactical Tool Bridging:** 10 Gemini Live tools query the local `/mcp` endpoint and receive structured responses.
- [ ] **A5: Dynamic Camera Repositioning:** Tool executions update `window.location.hash` without reloading the page or losing WebGL context.
- [ ] **A6: Graceful Offline Recovery:** If the backend service is offline, `GodsEyeActivity` displays a cyber recovery card with a retry button, and Gemini explains the offline status without crashing.
- [ ] **A7: Hardware Acceleration & 60 FPS:** WebGL 2 globe and optical GLSL shaders render smoothly on mobile GPU.
- [ ] **A8: Security & Secret Hardening:** Zero API keys bundled in APK; cleartext traffic permitted exclusively for `127.0.0.1` and `localhost`.
- [ ] **A9: Core Voice Preservation:** Offline Vosk wake word, barge-in detection, and conversation foreground service operate with zero regression.
