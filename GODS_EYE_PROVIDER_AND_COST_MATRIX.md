# God's Eye View Provider, Licensing & Cost Matrix

**Document Date:** October 14, 2026  
**Auditor:** Antigravity AI  
**Scope:** Complete accounting of all 3rd-party data providers, API keys, environment variables, licensing constraints, rate limits, and estimated operating costs.

---

## 1. Cost & Tier Classification Overview

All external services utilized in God's Eye View fall into one of three cost tiers:

1. **FREE (Keyless / Public Domain / Open Data):** Requires zero credentials, incurs zero recurring costs, and operates indefinitely within fair use.
2. **LIMITED-FREE (Free Key / Freemium Tier):** Requires a developer registration to obtain an API key; operates completely free within generous monthly or daily quota ceilings.
3. **PAID / BYOK (Bring Your Own Key):** Commercial or metered APIs where high-volume usage may incur per-request costs if usage exceeds free trial or threshold limits.

---

## 2. Comprehensive Provider Matrix

| Provider / Service | Cost Tier | Required Credentials / Env Var | Rate Limits & Quotas | License & Terms | Attribution Obligation | Notes & Fallback Behavior |
|---|---|---|---|---|---|---|
| **NOAA GFS (Wind)** | **FREE** | None (Keyless) | Unthrottled (AWS S3 Open Data) | U.S. Public Domain | Courtesy: "NOAA GFS" | 0.25° 6-hourly global wind GRIB2; 1h server cache. |
| **ECMWF IFS (Wind)** | **FREE** | None (Keyless) | Open Data policy | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) + ECMWF Terms | Required: "ECMWF" + CC BY 4.0 link | Alternative wind model. |
| **NOAA nowCOAST (Radar, Clouds, Lightning)** | **FREE** | None (Keyless) | Fair use (2-min metadata refresh) | U.S. Public Domain / NOAA disclaimer | Required: "NOAA nowCOAST" | Rain radar (MRMS), GOES-18/19 satellite clouds, NLDN lightning. |
| **NOAA NHC / CPHC (Cyclones)** | **FREE** | None (Keyless) | 5-min server cache | U.S. Public Domain | Required: "NOAA/NWS NHC/CPHC" | Active tropical cyclones, forecast tracks, and wind cones. |
| **USGS Earthquakes** | **FREE** | None (Keyless) | Unthrottled | U.S. Public Domain | Courtesy: "USGS" | Real-time global seismic events GeoJSON. |
| **CelesTrak (Satellites)** | **FREE** | None (Keyless) | Fair use | US Gov Open Data | Citation requested: "CelesTrak, Dr. T.S. Kelso" | Real-time TLE orbital elements; SGP4 client propagation. |
| **Launch Library 2 (Space Missions)** | **FREE** (Opt. Key) | `LL2_API_TOKEN` (Optional) | 15 unauthenticated calls/hr (cached 15 min on server) | Space Devs Terms of Use | Courtesy attribution | Rolling 30-day rocket launch missions & countdowns. |
| **adsb.lol (Military & Fallback Flights)** | **FREE** | None (Keyless) | Community fair use | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) | Required: "adsb.lol (ODbL)" | Military air traffic; automatically used as civilian fallback. |
| **adsbdb (Aircraft Metadata)** | **FREE** | None (Keyless) | Fair use (~5 req/s max, cached 24h) | Free community API | Courtesy: "adsbdb" | Resolves ICAO hex to aircraft type, model, and carrier. |
| **Esri World Imagery** | **FREE** | None (Keyless) | ArcGIS public tile service terms | Esri Master Agreement (public use) | Required on-globe credit | Default keyless satellite basemap. |
| **Re:Earth Terrain (Mapterhorn)** | **FREE** | None (Keyless) | Community fair use | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Required: "Re:Earth Terrain" | Quantized-mesh 3D terrain; fallback to flat ellipsoid. |
| **OSRM on FOSSGIS (Routing)** | **FREE** | None (Keyless) | 1 req/sec max (60/min, 200/min total; 10 min cache) | FOSSGIS Terms + ODbL 1.0 | Required: "OSRM on FOSSGIS" + "fix the map" link | Turn-by-turn road routing via `/api/route`. |
| **Komoot Photon (Geocoding)** | **FREE** | None (Keyless) | Fair use public instance | ODbL 1.0 | Courtesy: "Photon (komoot)" | Primary keyless place geocoder. |
| **OpenStreetMap / Nominatim** | **FREE** | None (Keyless) | 1 req/sec max (`/api/geocode`) | ODbL 1.0 | Required: "© OpenStreetMap contributors" | Fallback place search; datacenters and dams. |
| **Open-Meteo (Weather)** | **FREE** | None (Keyless) | Fair use (Non-commercial) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Required: "Weather data by Open-Meteo.com" | Current weather conditions for cockpit mode. |
| **NASA GIBS & CMR (Recent Imagery)** | **FREE** | None (Keyless) | Fair use (6 concurrent tiles max) | U.S. Public Domain | Required: "NASA GIBS / ESDIS" | Sentinel-2 & Landsat 8/9 daily high-res tiles. |
| **NIFC WFIGS (Wildfire Perimeters)** | **FREE** | None (Keyless) | 5-min server cache | U.S. Public Domain | Required: "NIFC WFIGS" | Active wildfire incident perimeters and containment. |
| **Radio Browser (Internet Radio)** | **FREE** | None (Keyless) | Directory under PDDL 1.0 | Public Domain | Required: "Radio Browser" | 30,000+ geolocated internet radio stations. |
| **Public CCTV Catalogs (14 Global Packs)** | **FREE** | None (Keyless) | Regional DOT terms (5-15 min caches) | Public Domain / OGL / CC BY 4.0 / NLOD | Required per pack | Austin, Caltrans, TfL, Ontario 511, Fintraffic, DriveBC, TxDOT, Tallinn, Tarktee, Warendorf, NSW, Calgary, DelDOT, Norway. |
| **NASA FIRMS (Active Fires)** | **LIMITED-FREE** | `FIRMS_MAP_KEY` | Free key from NASA; cached 30 min | U.S. Public Domain / CC0 | Required: NASA FIRMS acknowledgement | VIIRS & MODIS satellite thermal hotspots. Layer shows "KEY REQUIRED" without key. |
| **AISStream.io (Live Vessels)** | **LIMITED-FREE** | `AISSTREAM_API_KEY` | Free developer key; server-side WebSocket | Free beta; AIS public broadcast | Courtesy: "AISStream.io" | Real-time maritime AIS ship tracking. Layer is empty without key. |
| **Mapillary (Street Level 360)** | **LIMITED-FREE** | `MAPILLARY_CLIENT_TOKEN` | Free client token from Mapillary dashboard | [CC BY-SA 4.0](https://www.mapillary.com/terms) | Required: "© Mapillary contributors" | 360-degree street-level panoramic photos. |
| **TomTom Traffic Flow** | **LIMITED-FREE** | `TOMTOM_API_KEY` | 200,000 tile requests/month free (capped at 6,000/day) | TomTom Developer Terms | Required: "Traffic flow data © TomTom" | Live congestion tiles. Built-in simulation runs automatically if key is absent. |
| **OpenSky Network (Flights)** | **LIMITED-FREE** | `OPENSKY_CLIENT_ID`<br>`OPENSKY_CLIENT_SECRET` | Anonymous: daily quota; Authenticated: 4,000 req/day | Non-commercial research license | Required: OpenSky citation | Primary global civilian flights. Automatically falls back to adsb.lol when throttled. |
| **Google Maps Platform (3D Tiles & Places)** | **PAID / BYOK** | `GOOGLE_MAPS_API_KEY`<br>`GOOGLE_MAPS_SERVER_API_KEY` | $200 free monthly credit from Google; metered thereafter | Google Maps Platform ToS | Required on-globe Google logo | Optional. Supplies Photorealistic 3D Tiles and Google Places search. Esri basemap used when absent. |
| **Cesium ion** | **LIMITED-FREE / PAID** | `CESIUM_ION_TOKEN` | Free Community plan (non-commercial personal quotas) | Cesium ion Terms | Required Cesium credit | Optional. Loads ion-hosted 3D assets and terrain. Re:Earth and Esri used when absent. |
| **OpenAI Realtime Voice** | **PAID / BYOK** | `OPENAI_API_KEY` | Metered per audio token | Commercial OpenAI Terms | N/A | **BYPASSED in JARVIS Integration**. JARVIS utilizes its native Gemini Live assistant, eliminating all OpenAI voice costs. |

---

## 3. Bundled Dataset Licensing Obligations

The following datasets are bundled directly in `src/data/local_data/` and must adhere to their specific licenses:

1. **TeleGeography Submarine Cable Map (`telegeography_submarine_cables/`):**
   - **License:** **CC BY-NC-SA 3.0** (Attribution-NonCommercial-ShareAlike).
   - **Crucial Restriction:** This dataset is **STRICTLY NON-COMMERCIAL**. It is included for personal/educational use. If JARVIS is ever deployed commercially, this directory must be deleted.
2. **OpenStreetMap Extracts (`datacenters/`, `dams/`, `osm_military_names/`):**
   - **License:** **ODbL 1.0**.
   - Commercial use is permitted with credit: `© OpenStreetMap contributors`.
3. **Natural Earth Physical Regions & Admin Boundaries (`natural_earth/`):**
   - **License:** **Public Domain**. No restrictions.
4. **DataSF Analysis Neighborhoods (`neighborhoods/`):**
   - **License:** **PDDL 1.0** (Public Domain). No restrictions.
5. **Bhote Koshi Event Pack (`public/events/bhote-koshi-2026/`):**
   - **License:** **CC BY-NC 4.0** (Non-commercial disaster study pack).

---

## 4. Total Cost of Operation (TCO) Analysis

Under the unified JARVIS Plan A integration architecture:

- **Baseline Cost:** **$0.00 / Month (100% Free)**
  - All core intelligence layers operate keyless: Military flights (adsb.lol), Satellites (CelesTrak), Earthquakes (USGS), Weather Radar & Clouds (NOAA nowCOAST), Wind (NOAA GFS), Launch missions (LL2), CCTV cameras (14 DOT feeds), Radio (Radio Browser), Routing (OSRM), and Basemap (Esri Satellite).
  - Conversational AI uses the existing JARVIS Gemini Live API key (which includes generous free tier limits in Google AI Studio).
- **Optional Enhanced Cost:** **$0.00 / Month**
  - Users can optionally add free developer keys for NASA FIRMS (`FIRMS_MAP_KEY`), AIS Vessels (`AISSTREAM_API_KEY`), and Mapillary (`MAPILLARY_CLIENT_TOKEN`) at zero monetary cost.
- **Optional Premium Features:**
  - Google Photorealistic 3D Tiles require a Google Maps API key, which is covered by Google's $200 recurring monthly free credit for low-to-moderate personal use.
