# GOD'S EYE VIEW — Standalone APK Audit (Real Status + Fixes Applied)

**Date:** 01 Nov 2026 • **Checked by:** live code read of `ui/godseye/*`, the bundled web
bundle (`app/src/main/assets/godseye/`), and the upstream server contracts in
`gods-eye-view-main/server/` • **Upstream version:** gods-eye-view 0.2.1

---

## 1. WORK OR NOT? — Short Answer

God's Eye **chal raha hai** — the Cesium globe, the asset bundle, the CCTV system,
satellites, cyclones, radio, GBFS, Overpass, routing, geocode, places and ~45 proxy
routes are all genuinely wired into Kotlin. That part is solid.

**Lekin:** the console was built to read its own health off the API responses, and the
standalone proxy was answering **"everything is fine"** to things it could not actually
do. So dead layers rendered green. Three layers were serving **hardcoded fake data
labelled as live**, and the whole mic/camera side was silently denied by the WebView.

| What it looked like | What was really happening |
|---|---|
| Ships moving worldwide, `"status":"live"` | 19 hardcoded ships. `AISSTREAM_API_KEY` was collected in settings and **never used anywhere** |
| Flights layer populated | OpenSky auth was sending retired HTTP Basic → 401 → fell through to 24 **hardcoded airliners** |
| Wildfires on the map | Without a FIRMS key: **15 invented hotspots**, stamped with today's date |
| Wind layer: calm everywhere | `/api/wind/grid/` returned **521 280 zero bytes** — a fabricated dead calm |
| Provider Settings: all keys green | `/api/setup/status` returned a hardcoded `configured: true` for **every** provider |
| Voice / SDR audio "not responding" | `onPermissionRequest` was never overridden → WebView **denies every `getUserMedia()`** |
| Unknown layers "nominal" | Any unhandled `/api/*` returned `{"status":"nominal"}` **HTTP 200** |

---

## 2. BUGS FOUND & FIXED

### 🔴 G1 — Blanket `{"status":"nominal"}` fallback (the root cause of "sab green hai par kaam nahi karta")
- **File:** `GodsEyeActivity.handleApiProxy()`, last line
- Any `/api/*` route the APK did not implement answered `{"status":"nominal","available":true}`
  with **HTTP 200**. Every caller treated an unimplemented route as a healthy one.
- **Fixed:** unknown routes now return **HTTP 501** with the path, and log
  `Unhandled God's Eye API route: <path>` so a gap is visible in logcat.

### 🔴 G2 — `/api/setup/status` lied about every key
- Returned `{"configured":{"cesium":true,"firms":true,…}}` — hardcoded, and the wrong
  shape entirely (upstream returns `{keys[], setCount, total}`).
- **Fixed:** new `GodsEyeStandaloneApi.setupStatusJson()` reports the **real** state of
  each key from `AppPreferences`, in the upstream registry shape, with each provider's
  `getUrl` so the panel can link you to where to get it.
  `POST /api/setup/keys` now returns 405 + "managed in Jarvis > Settings > API Connections"
  (a WebView `shouldInterceptRequest` cannot read a POST body, so it must not pretend to save).
- Same lie fixed in `/api/mapillary/status` (was `{"configured":true}`) and
  `/api/firms/status` (was `hasKey:true, count:50`).

### 🔴 G3 — Vessels were 19 hardcoded ships labelled `"status":"live"`
- `/api/vessels` never contacted AISStream. `godsEyeAisStreamKey` had **zero usages**
  outside the settings screen.
- **Fixed:** new `GodsEyeAisStream.kt` — a real AISStream WebSocket client (OkHttp),
  one socket per app, worldwide bounding box, PositionReport + static-data ingest,
  per-MMSI thinned track ring buffer, 30-min staleness prune, 6000-vessel cap,
  backoff ladder, silence watchdog, and an hourly-only probe once the key is rejected.
  `/api/vessels/track?mmsi=` now serves the real accumulated track.
  With no key it answers **HTTP 503 `status:"missing-key"`** — no placeholder ships.

### 🔴 G4 — OpenSky used retired HTTP Basic auth
- `okhttp3.Credentials.basic(clientId, clientSecret)` — OpenSky **retired username/password
  Basic auth in March 2026**; a client id/secret pair only authenticates through OAuth2
  client-credentials. So your OpenSky keys were doing nothing and the layer always fell
  through to the military-only `api.adsb.lol/v2/mil` feed or the fake planes.
- **Fixed:** `GodsEyeStandaloneApi.openSkyBearer()` mints a token at
  `auth.opensky-network.org/.../openid-connect/token` (verified live: POST → 401 on bad
  creds, so the endpoint is right), caches it until 60 s before expiry, and sends
  `Authorization: Bearer`. Anonymous OpenSky still works as before, just rate-limited.
- **Fake-plane fallback deleted.** No live source reachable → `states:[]` + HTTP 502.

### 🔴 G5 — Mic and camera were dead inside God's Eye
- `WebChromeClient.onPermissionRequest` was never overridden, so Android denies **every**
  `getUserMedia()` by default. That killed voice control, the Gemini capture worklet and
  the SDR audio worklet — all of which are in the bundle.
- **Fixed:** overridden. Only the bundled origin (or loopback) may ask; it grants only
  what Android has already granted the app, and triggers the runtime permission request
  for anything missing instead of faking a grant.

### 🟠 G6 — Four endpoints the APK had no answer for at all
All four used to hit the "nominal" fallback:
- `/api/realtime/token` → **now implemented**: mints an OpenAI Realtime ephemeral client
  secret from your Jarvis `openAiApiKey`, with the full God's Eye globe-control tool set
  bundled as `assets/godseye/jarvis-realtime-session.json` (dumped from the upstream
  `createActionTools()` registry, 50 KB, 19 tools). No key → 503 saying so.
- `/api/realtime/oauth-status|oauth-login` → honest 503 (ChatGPT OAuth reads a local
  Codex credential file; it is desktop-only).
- `/api/local-receivers/aircraft` → 503 `configured:false` (no dump1090 bridged).
- `/api/transit/trail/…` → `{samples:[], history:false}` (standalone keeps no feed history).

### 🟠 G7 — Wind layer painted a fabricated worldwide dead calm
- Manifest said `unavailable:false, stale:false` with a live GFS cycle; the grid endpoint
  returned 521 280 zero bytes.
- **Fixed:** manifest reports `unavailable:true` with the reason (no GRIB decoder in the
  standalone build); the grid endpoint returns 503 instead of zeroes.

### 🟠 G8 — Weather manifest claimed global coverage for CONUS-only products
- nowCOAST `conus_base_reflectivity_mosaic` and `ldn_lightning_strike_density` are
  **continental-US only**, but the manifest advertised bounds `-180..180` and
  "Global Satellite and Radar Coverage". Outside the US you got transparent tiles that
  read as clear skies.
- **Fixed:** bounds and coverage text are now per-product; only the longwave satellite
  mosaic (`clouds`) is advertised as global.

### 🟠 G9 — FIRMS invented 15 wildfires when the key was missing
- **Fixed:** empty `fires:[]` in the same envelope shape, with
  `status:"missing-key"|"unavailable"` and the reason. HTTP 503/502.
  `/api/firms/status` now reports the real last fetch time and count.

### 🟡 G10 — Enabled layers were silently wiped on every reload
- `onPageStarted` + `onPageFinished` both ran: *if the user has more than 5 layers on,
  reset to `['cctv','flights','satellites']`*. So turning on 6+ layers and reloading
  threw your selection away.
- **Fixed:** removed from both injection points. The `bhote-koshi` cleanup filter stays.

### 🟡 G11 — Loopback probe on every single request
- Every `/api/*` call — including each weather tile and CCTV frame — first opened a
  connection to `127.0.0.1:4173` looking for a Termux server that is almost never there.
- **Fixed:** a refused probe is remembered for 30 s instead of being repeated.

### 🟡 G12 — Key injection raced the page
- `window.__CESIUM_ION_TOKEN__` / `__GOOGLE_MAPS_API_KEY__` were injected via
  `evaluateJavascript()` from `onPageStarted`, which on a cold load can run before there
  is a document — and the `Cesium.Ion.defaultAccessToken` assignment in `onPageFinished`
  is already too late for the globe's imagery provider.
- **Fixed:** `bootstrappedIndexHtml()` splices the key bootstrap into `<head>` of the
  index.html we already serve ourselves. It cannot race. Mapillary's client token is
  injected the same way now.

### 🟡 G13 — `GodsEyeActivity` was exported with a JS bridge attached
- `android:exported="true"` with no intent-filter. Every launcher is an explicit
  in-app intent (`RealLifeDataHub`, `GodsEyeMcpClient`, `JarvisConversationService`).
- **Fixed:** `android:exported="false"`.

---

## 3. STILL NOT WORKING (by design / needs more work)

| Thing | Why | What it would take |
|---|---|---|
| Wind / GFS streamlines | No GRIB2 decoder on device | Run the God's Eye server in Termux on :4173 (the proxy already prefers it), or add a GRIB subset fetch |
| Radar + lightning outside the US | nowCOAST mosaics are CONUS-only upstream | Add RainViewer or a regional IMD/Mosdac source for India |
| Transit vehicle trails | Needs a server sampling the feed over time | Keep a rolling in-app history like the AIS tracks now do |
| Local ADS-B receiver layer | No dump1090 bridged to the phone | Point it at a receiver on your LAN |
| ChatGPT OAuth voice | Reads the desktop Codex credential file | Use an OpenAI API key instead (now wired) |
| `/api/realtime/token` end-to-end | Implemented, but **untested on device** — needs a paid OpenAI Realtime key and WebRTC to work inside the WebView | Test with a key; check logcat `GodsEyeStandaloneApi` |

---

## 4. ALSO NOTED

- **`gods-eye-view-main/dist/` is a stale, incomplete build** — it is missing
  `index-mG_IdKA0.js` (the bundle `index.html` actually references), both `places-*.js`,
  the milkyway textures, `bright_stars`, `geminiCaptureWorklet`, `services`, and one
  `sdr.worker`. The copy under `app/src/main/assets/godseye/` is the **good** one.
  Do not re-copy `dist/` over the app assets — it will break the console.
- `app/src/main/assets/godseye/assets/` carries **two** `index-*.js`, two `places-*.js`
  and two `sdr.worker-*.js` from different builds. Only `index-mG_IdKA0.js` is referenced;
  the rest is dead weight in the APK. Safe to prune after a device test.
- No Jarvis preference exists for the Launch Library token (`LL2_API_TOKEN`); it is
  optional (higher request allowance only) and reported as unset.

---

## 5. Ek Line Me Summary (Hinglish)

God's Eye ka base **sach me solid hai** — globe, CCTV, satellites, 45+ proxy routes sab real
hain. Problem ye thi ki jo cheezein kaam nahi kar rahi thi, wo **"sab theek hai" bolkar jhoot**
bol rahi thi: ships aur planes hardcoded the, wind puri duniya me zero bhej raha tha, FIRMS
nakli aag dikha raha tha, setup panel har key ko green bata raha tha, aur mic/camera WebView
chupchap deny kar raha tha. Ab har layer **sach bolti hai** — key nahi hai to "missing-key",
data nahi hai to khali, aur AISStream + OpenSky OAuth2 + OpenAI Realtime ab asli me wired hain.
