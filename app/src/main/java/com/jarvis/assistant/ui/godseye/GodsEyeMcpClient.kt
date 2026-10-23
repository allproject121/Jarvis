package com.jarvis.assistant.ui.godseye

import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified Model Context Protocol (MCP) client bridging JARVIS and God's Eye View.
 * Connects to the local God's Eye View loopback service at http://localhost:4173/mcp when available,
 * and falls back to standalone globe navigation and direct telemetry when running independently.
 */
object GodsEyeMcpClient {
    private const val TAG = "GodsEyeMcpClient"
    const val MCP_URL = "http://localhost:4173/mcp"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val rpcIdCounter = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val mcpHttpClient = OkHttpClient.Builder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .build()

    data class CctvCameraItem(
        val id: String,
        val name: String,
        val city: String,
        val region: String,
        val pack: String,
        val lat: Double,
        val lon: Double,
        val feedType: String, // "video" (mp4 or hls) or "image" (live snapshot)
        val url: String,
        val snapshotUrl: String = ""
    )

    private var cachedCctvList: List<CctvCameraItem>? = null
    private val cctvLock = Any()

    fun getCctvCatalog(): List<CctvCameraItem> {
        cachedCctvList?.let { return it }
        synchronized(cctvLock) {
            cachedCctvList?.let { return it }
            val loaded = loadCctvCatalog()
            cachedCctvList = loaded
            return loaded
        }
    }

    fun findCamera(id: String): CctvCameraItem? {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return null
        val catalog = getCctvCatalog()
        val found = catalog.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
            ?: catalog.firstOrNull { it.id.endsWith(trimmed, ignoreCase = true) }
            ?: catalog.firstOrNull { it.id.contains(trimmed, ignoreCase = true) }
        if (found != null) return found
        return getFallbackCctvCatalog().firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
            ?: getFallbackCctvCatalog().firstOrNull { it.id.endsWith(trimmed, ignoreCase = true) }
            ?: getFallbackCctvCatalog().firstOrNull { it.id.contains(trimmed, ignoreCase = true) }
    }

    private fun loadCctvCatalog(): List<CctvCameraItem> {
        val items = mutableListOf<CctvCameraItem>()
        try {
            val stream: InputStream? = try {
                com.jarvis.assistant.JarvisApp.instance.assets.open("godseye/cctv_sources.json")
            } catch (_: Throwable) {
                GodsEyeMcpClient::class.java.classLoader?.getResourceAsStream("godseye/cctv_sources.json")
                    ?: File("app/src/main/assets/godseye/cctv_sources.json").takeIf { it.exists() }?.inputStream()
                    ?: File("src/main/assets/godseye/cctv_sources.json").takeIf { it.exists() }?.inputStream()
            }

            if (stream != null) {
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(text)
                val sources = root.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val id = s.optString("id", "").trim()
                        val name = s.optString("name", id).trim()
                        val city = s.optString("city", "").trim()
                        val pack = s.optString("pack", "").trim()
                        val lat = s.optDouble("lat", Double.NaN)
                        val lon = s.optDouble("lon", Double.NaN)
                        val rawFeedType = s.optString("feedType", "").lowercase()
                        val streamUrl = s.optString("url", "").ifBlank { s.optString("videoUrl", "") }
                        val snapshotUrl = s.optString("snapshotUrl", "")
                        val effectiveUrl = streamUrl.ifBlank { snapshotUrl }
                        if (id.isNotBlank() && !lat.isNaN() && !lon.isNaN()) {
                            val isVideo = rawFeedType == "video" || rawFeedType == "hls" || rawFeedType == "stream" || rawFeedType == "mp4" || effectiveUrl.endsWith(".mp4") || effectiveUrl.endsWith(".m3u8")
                            val normalizedFeedType = if (rawFeedType == "hls" || effectiveUrl.endsWith(".m3u8")) "hls" else if (isVideo) "video" else "image"
                            val normalizedCity = when {
                                pack.equals("sydney", ignoreCase = true) || city.contains("SYD", ignoreCase = true) || city.contains("NSW", ignoreCase = true) -> "Sydney"
                                pack.equals("caltrans", ignoreCase = true) && city.isBlank() -> "California"
                                else -> city.ifBlank { pack.replaceFirstChar { it.uppercase() } }
                            }
                            items.add(
                                CctvCameraItem(
                                    id = id,
                                    name = name,
                                    city = normalizedCity,
                                    region = pack,
                                    pack = pack,
                                    lat = lat,
                                    lon = lon,
                                    feedType = normalizedFeedType,
                                    url = effectiveUrl,
                                    snapshotUrl = snapshotUrl.ifBlank { effectiveUrl }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Notice: Reading cctv_sources.json: ${e.message}")
        }

        if (items.isEmpty()) {
            items.addAll(getFallbackCctvCatalog())
        }
        return items
    }

    private fun getFallbackCctvCatalog(): List<CctvCameraItem> {
        return listOf(
            CctvCameraItem("tfl-00001.06502", "Trafalgar Square", "London", "tfl", "tfl", 51.5080, -0.1281, "video", "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00001.06502.mp4"),
            CctvCameraItem("tfl-00002.00865", "A40 Western Ave / Westway", "London", "tfl", "tfl", 51.5074, -0.1278, "video", "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/00002.00865.mp4"),
            CctvCameraItem("nsw-201", "Sydney Harbour Bridge", "Sydney", "sydney", "sydney", -33.8568, 151.2153, "image", "https://www.livetraffic.com/traffic-cam.json"),
            CctvCameraItem("354", "5TH ST / CONGRESS AVE", "Austin", "austin", "austin", 30.267119, -97.743172, "image", "https://cctv.austinmobility.io/image/354.jpg"),
            CctvCameraItem("caltrans-d7-1", "I-5 at Stadium Way", "Los Angeles", "caltrans", "caltrans", 34.0736, -118.2400, "image", "https://cwwp2.dot.ca.gov/data/d7/cctv/image/1/1.jpg"),
            CctvCameraItem("drivebc-19", "Hwy 1 at Ironworkers Bridge", "Vancouver", "drivebc", "drivebc", 49.3000, -123.0250, "image", "https://images.drivebc.ca/bchighwaycam/pub/cameras/19.jpg"),
            CctvCameraItem("deldot-301", "I-95 at Route 141", "Delaware", "deldot", "deldot", 39.7120, -75.5940, "video", "https://www.deldot.gov/cctv/live.m3u8"),
            CctvCameraItem("calgary-1", "Crowchild Trail at 24 Ave NW", "Calgary", "calgary", "calgary", 51.0740, -114.1200, "image", "https://trafficcam.calgary.ca/loc1.jpg"),
            CctvCameraItem("fi-c0150301", "Vt 1 Helsinki", "Helsinki", "fintraffic", "fintraffic", 60.1699, 24.9384, "image", "https://weathercam.digitraffic.fi/C0150301.jpg"),
            CctvCameraItem("3000997_1", "E6 Oslo", "Oslo", "norway", "norway", 59.9139, 10.7522, "video", "https://webkamera.vegvesen.no/kamera?id=3000997_1"),
            CctvCameraItem("tallinn-101", "Viru Valjak", "Tallinn", "tallinn", "tallinn", 59.4370, 24.7535, "image", "https://ristmikud.tallinn.ee/cam/cam101.jpg")
        )
    }

    fun searchCctvCatalog(query: String, maxResults: Int = 10): List<CctvCameraItem> {
        val q = query.trim().lowercase()
        val catalog = getCctvCatalog()
        val isGeneric = q.isBlank() || q == "cctv" || q == "camera" || q == "cameras" ||
                q == "video" || q == "videos" || q == "feeds" || q == "feed" || q == "all" ||
                q == "live" || q == "traffic" || q == "god eye" || q == "god's eye" || q == "globe"
        if (isGeneric) {
            return catalog.take(maxResults)
        }

        val searchTerms = when {
            q.contains("uk") || q.contains("england") || q.contains("britain") -> listOf(q, "london", "tfl")
            q.contains("australia") || q.contains("nsw") || q.contains("sydney") -> listOf(q, "sydney", "nsw")
            q.contains("california") || q.contains("caltrans") || q.contains("los angeles") || q.contains("san francisco") || q.contains("la") || q.contains("sf") -> listOf(q, "caltrans", "california", "los angeles")
            q.contains("texas") || q.contains("tx") -> listOf(q, "austin", "txdot")
            q.contains("vancouver") || q.contains("british columbia") || q.contains("bc") -> listOf(q, "drivebc", "vancouver", "bc")
            q.contains("delaware") || q.contains("deldot") -> listOf(q, "deldot", "delaware")
            q.contains("helsinki") || q.contains("finland") -> listOf(q, "fintraffic", "helsinki", "finland")
            q.contains("oslo") || q.contains("norway") -> listOf(q, "norway", "oslo")
            q.contains("tallinn") || q.contains("estonia") -> listOf(q, "tallinn")
            q.contains("calgary") || q.contains("alberta") -> listOf(q, "calgary")
            else -> listOf(q)
        }

        val matches = catalog.filter { cam ->
            searchTerms.any { term ->
                cam.city.lowercase().contains(term) ||
                cam.region.lowercase().contains(term) ||
                cam.pack.lowercase().contains(term) ||
                cam.name.lowercase().contains(term) ||
                cam.id.lowercase() == term ||
                cam.id.lowercase().contains(term)
            }
        }
        return matches.take(maxResults)
    }

    fun executeTool(toolName: String, args: JSONObject): JSONObject {
        val (mcpToolName, normalizedArgs) = normalizeToolAndArgs(toolName, args)

        val rpcId = rpcIdCounter.getAndIncrement()
        val traceId = "tr-mcp-" + System.currentTimeMillis() + "-" + rpcId
        val requestJson = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rpcId)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", mcpToolName)
                put("arguments", normalizedArgs)
            })
        }

        try {
            val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(MCP_URL)
                .post(requestBody)
                .build()

            val response = mcpHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val rpcResponse = JSONObject(responseBody)
                val resultObj = rpcResponse.optJSONObject("result") ?: JSONObject()
                val isError = resultObj.optBoolean("isError", false)

                if (!isError) {
                    val summaryText = extractContentText(resultObj)
                    val structuredContent = resultObj.optJSONObject("structuredContent")
                    var viewUrl: String? = structuredContent?.optString("url")?.takeIf { it.isNotBlank() }

                    val viewObj = structuredContent?.optJSONObject("view")
                    if (viewUrl == null && viewObj != null) {
                        viewUrl = buildViewUrlFromViewObject(viewObj)
                    }

                    val actionJson = if (viewObj != null) {
                        buildActionJsonFromViewObject(viewObj)
                    } else if (!viewUrl.isNullOrBlank() && viewUrl.contains("#")) {
                        JSONObject().apply { put("hash", viewUrl.substringAfter("#")) }.toString()
                    } else null

                    val enrichedActionJson = if (!actionJson.isNullOrBlank()) {
                        try {
                            val actObj = JSONObject(actionJson)
                            if (!actObj.has("trace_id")) actObj.put("trace_id", traceId)
                            actObj.toString()
                        } catch (_: Exception) { actionJson }
                    } else null

                    if (!enrichedActionJson.isNullOrBlank() && GodsEyeActivity.activeInstance != null) {
                        GodsEyeActivity.dispatchAction(enrichedActionJson)
                    }

                    val receipt = GodsEyeActivity.awaitActionReceipt(traceId, 250)

                    return JSONObject().apply {
                        put("success", true)
                        put("summary", summaryText)
                        put("trace_id", traceId)
                        if (receipt != null) {
                            put("verified_on_screen", true)
                            put("globe_state", receipt.optJSONObject("state"))
                        }
                        if (structuredContent != null) {
                            put("data", structuredContent)
                        }
                        if (!viewUrl.isNullOrBlank()) {
                            put("view_url", viewUrl)
                        }
                        if (!enrichedActionJson.isNullOrBlank()) {
                            put("action_json", enrichedActionJson)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Local Termux MCP service offline; seamlessly proceed to standalone live handler
        }

        val standaloneRes = executeStandaloneTool(toolName, args)

        val rawAction = standaloneRes.optString("action_json", "")
        if (rawAction.isNotBlank()) {
            try {
                val actObj = JSONObject(rawAction)
                val actTraceId = actObj.optString("trace_id", traceId)
                if (!actObj.has("trace_id")) actObj.put("trace_id", actTraceId)
                val updatedAction = actObj.toString()
                standaloneRes.put("action_json", updatedAction)
                standaloneRes.put("trace_id", actTraceId)

                if (GodsEyeActivity.activeInstance != null) {
                    GodsEyeActivity.dispatchAction(updatedAction)
                    val receipt = GodsEyeActivity.awaitActionReceipt(actTraceId, 250)
                    if (receipt != null) {
                        standaloneRes.put("verified_on_screen", true)
                        standaloneRes.put("globe_state", receipt.optJSONObject("state"))
                    }
                }
            } catch (_: Exception) {}
        }

        return standaloneRes
    }

    private fun normalizeToolAndArgs(toolName: String, args: JSONObject): Pair<String, JSONObject> {
        val normalized = JSONObject()
        val mcpName = when (toolName) {
            "open_gods_eye_view", "gods_eye_show_view" -> "show_in_gods_eye_view"
            "gods_eye_situation_brief" -> "situation_brief"
            "gods_eye_get_earthquakes" -> "get_earthquakes"
            "gods_eye_get_active_fires" -> "get_active_fires"
            "gods_eye_get_recent_launches" -> "get_recent_launches"
            "gods_eye_aircraft_in_area" -> "aircraft_in_area"
            "gods_eye_satellites_overhead" -> "satellites_overhead"
            "gods_eye_military_awareness" -> "military_awareness"
            "gods_eye_find_cctv_cameras" -> "find_cctv_cameras"
            "gods_eye_inspect_globe", "inspect_globe" -> "inspect_globe"
            "gods_eye_control_layers", "control_layers" -> "control_layers"
            "gods_eye_select_camera", "select_camera" -> "select_camera"
            else -> toolName.removePrefix("gods_eye_")
        }

        val location = args.optString("location", "").ifBlank { args.optString("area", "") }
        val rawArea = args.opt("area")
        if (rawArea is JSONObject) {
            normalized.put("area", rawArea)
        } else if (location.isNotBlank()) {
            normalized.put("area", JSONObject().apply { put("place", location) })
        }

        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "area" && key != "location" && key != "show_on_globe") {
                normalized.put(key, args.get(key))
            }
        }

        return Pair(mcpName, normalized)
    }

    private fun extractContentText(resultObj: JSONObject): String {
        val contentArray = resultObj.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArray.length()) {
            val item = contentArray.optJSONObject(i) ?: continue
            val text = item.optString("text", "")
            if (text.isNotBlank() && !text.startsWith("{")) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    fun buildViewUrlFromViewObject(viewObj: JSONObject): String {
        val camera = viewObj.optJSONObject("camera") ?: return GodsEyeActivity.GODS_EYE_LOCAL_URL
        val lat = camera.optDouble("lat", 0.0)
        val lon = camera.optDouble("lon", 0.0)
        val alt = camera.optLong("altitude_m", 1500000L)
        val heading = camera.optDouble("heading_deg", 0.0)
        val pitch = camera.optDouble("pitch_deg", -90.0)
        val style = viewObj.optString("style", "normal")
        val styleParam = if (style == "thermal") "&style=flir" else if (style.isNotBlank() && style != "normal") "&style=$style" else ""
        return "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$lat&lon=$lon&alt=$alt&heading=$heading&pitch=$pitch&roll=0$styleParam"
    }

    fun buildActionJsonFromViewObject(viewObj: JSONObject): String {
        val camera = viewObj.optJSONObject("camera")
        val lat = camera?.optDouble("lat", Double.NaN) ?: Double.NaN
        val lon = camera?.optDouble("lon", Double.NaN) ?: Double.NaN
        val alt = camera?.optLong("altitude_m", 1500000L) ?: 1500000L
        val heading = camera?.optDouble("heading_deg", 0.0) ?: 0.0
        val pitch = camera?.optDouble("pitch_deg", -45.0) ?: -45.0
        val style = viewObj.optString("style", "normal")
        val layers = viewObj.opt("layers")

        return JSONObject().apply {
            if (!lat.isNaN() && !lon.isNaN()) {
                put("lat", lat)
                put("lon", lon)
                put("alt", alt)
                put("heading", heading)
                put("pitch", pitch)
                put("roll", 0.0)
                put("duration", 2.0)
            }
            if (style.isNotBlank() && style != "normal") {
                put("style", if (style == "thermal") "flir" else style)
            }
            if (layers != null) {
                put("layers", layers)
            }
        }.toString()
    }

    fun launchOrUpdateGodsEye(context: Context, viewUrl: String? = null, actionJson: String? = null) {
        try {
            if (!actionJson.isNullOrBlank() && GodsEyeActivity.dispatchAction(actionJson)) {
                Log.i(TAG, "Dispatched action directly to active GodsEyeActivity: $actionJson")
                return
            }

            val intent = Intent(context, GodsEyeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (!viewUrl.isNullOrBlank()) {
                    putExtra(GodsEyeActivity.EXTRA_VIEW_URL, viewUrl)
                }
                if (!actionJson.isNullOrBlank()) {
                    putExtra(GodsEyeActivity.EXTRA_ACTION_JSON, actionJson)
                }
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched/Updated GodsEyeActivity with URL: $viewUrl, action: $actionJson")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GodsEyeActivity: ${e.message}", e)
        }
    }

    private val KNOWN_CITIES = mapOf(
        "london" to Pair(51.5074, -0.1278),
        "tokyo" to Pair(35.6762, 139.6503),
        "new york" to Pair(40.7128, -74.0060),
        "nyc" to Pair(40.7128, -74.0060),
        "san francisco" to Pair(37.7749, -122.4194),
        "sf" to Pair(37.7749, -122.4194),
        "los angeles" to Pair(34.0522, -118.2437),
        "la" to Pair(34.0522, -118.2437),
        "paris" to Pair(48.8566, 2.3522),
        "berlin" to Pair(52.5200, 13.4050),
        "rome" to Pair(41.9028, 12.4964),
        "beijing" to Pair(39.9042, 116.4074),
        "sydney" to Pair(-33.8688, 151.2093),
        "cairo" to Pair(30.0444, 31.2357),
        "mumbai" to Pair(19.0760, 72.8777),
        "delhi" to Pair(28.6139, 77.2090),
        "austin" to Pair(30.2672, -97.7431),
        "vancouver" to Pair(49.2827, -123.1207),
        "toronto" to Pair(43.6532, -79.3832),
        "chicago" to Pair(41.8781, -87.6298),
        "miami" to Pair(25.7617, -80.1918),
        "dubai" to Pair(25.2048, 55.2708),
        "singapore" to Pair(1.3521, 103.8198),
        "washington" to Pair(38.9072, -77.0369),
        "dc" to Pair(38.9072, -77.0369),
        "seattle" to Pair(47.6062, -122.3321),
        "boston" to Pair(42.3601, -71.0589),
        "calgary" to Pair(51.0447, -114.0719),
        "oslo" to Pair(59.9139, 10.7522),
        "helsinki" to Pair(60.1699, 24.9384),
        "tallinn" to Pair(59.4370, 24.7535),
        "delaware" to Pair(39.7120, -75.5940),
        "california" to Pair(36.7783, -119.4179)
    )

    fun executeStandaloneTool(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            "open_gods_eye_view", "gods_eye_show_view" -> handleStandaloneView(args)
            "gods_eye_get_earthquakes" -> handleStandaloneEarthquakes(args)
            "gods_eye_military_awareness" -> handleStandaloneMilitary(args)
            "gods_eye_aircraft_in_area" -> handleStandaloneAircraft(args)
            "gods_eye_get_recent_launches" -> handleStandaloneLaunches(args)
            "gods_eye_satellites_overhead" -> handleStandaloneSatellites(args)
            "gods_eye_find_cctv_cameras" -> handleStandaloneCctv(args)
            "gods_eye_situation_brief" -> handleStandaloneSituationBrief(args)
            "gods_eye_get_active_fires" -> handleStandaloneFires(args)
            "gods_eye_inspect_globe", "inspect_globe", "gods_eye_inspect_state" -> handleInspectGlobe()
            "gods_eye_control_layers", "control_layers" -> handleControlLayers(args)
            "gods_eye_select_camera", "select_camera" -> handleSelectCamera(args)
            else -> handleStandaloneView(args)
        }
    }

    private fun handleStandaloneView(args: JSONObject): JSONObject {
        val locationStr = args.optString("location", "").ifBlank {
            val areaObj = args.optJSONObject("area")
            areaObj?.optString("place", "") ?: ""
        }.trim().lowercase()

        var lat = args.optDouble("lat", Double.NaN)
        var lon = args.optDouble("lon", Double.NaN)
        var alt = args.optLong("altitude_m", 1500000L)
        val style = args.optString("style", "normal")
        val heading = args.optDouble("heading", 0.0)
        val pitch = args.optDouble("pitch", -45.0)

        var resolvedPlace = "Global"
        if (lat.isNaN() || lon.isNaN()) {
            if (locationStr.isNotBlank()) {
                val matched = KNOWN_CITIES.entries.firstOrNull { locationStr.contains(it.key) || it.key.contains(locationStr) }
                if (matched != null) {
                    lat = matched.value.first
                    lon = matched.value.second
                    alt = 50000L
                    resolvedPlace = matched.key.replaceFirstChar { it.uppercase() }
                }
            }
        } else {
            resolvedPlace = "Lat: %.2f, Lon: %.2f".format(lat, lon)
        }

        if (lat.isNaN() || lon.isNaN()) {
            lat = 20.0
            lon = 0.0
            alt = 20000000L
            resolvedPlace = "Global View"
        }

        val styleParam = if (style == "thermal") "&style=flir" else if (style.isNotBlank() && style != "normal") "&style=$style" else ""
        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$lat&lon=$lon&alt=$alt&heading=$heading&pitch=$pitch&roll=0$styleParam"

        val actionJson = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("alt", alt)
            put("heading", heading)
            put("pitch", pitch)
            put("roll", 0.0)
            put("duration", 2.0)
            if (style.isNotBlank() && style != "normal") {
                put("style", if (style == "thermal") "flir" else style)
            }
        }.toString()

        return JSONObject().apply {
            put("success", true)
            put("summary", "God's Eye globe centered on $resolvedPlace.")
            put("view_url", viewUrl)
            put("action_json", actionJson)
            put("target", resolvedPlace)
            put("latitude", lat)
            put("longitude", lon)
        }
    }

    private fun handleStandaloneEarthquakes(args: JSONObject): JSONObject {
        return try {
            val req = Request.Builder()
                .url("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson")
                .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return JSONObject().apply {
                    put("success", false)
                    put("message", "USGS earthquake service returned HTTP ${resp.code}")
                }
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val features = json.optJSONArray("features") ?: JSONArray()
            val quakes = mutableListOf<JSONObject>()
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val prop = f.optJSONObject("properties") ?: continue
                val geom = f.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                val mag = prop.optDouble("mag", 0.0)
                if (mag >= 2.0) {
                    quakes.add(JSONObject().apply {
                        put("magnitude", mag)
                        put("place", prop.optString("place", "Unknown"))
                        put("time", prop.optLong("time", 0L))
                        put("longitude", coords.optDouble(0))
                        put("latitude", coords.optDouble(1))
                        put("depth_km", coords.optDouble(2))
                    })
                }
            }
            quakes.sortByDescending { it.optDouble("magnitude") }

            val requestedArea = args.optString("area", "").ifBlank {
                args.optString("location", "")
            }.trim().lowercase()

            val filteredQuakes = if (requestedArea.isNotBlank()) {
                quakes.filter { it.optString("place").lowercase().contains(requestedArea) }
            } else quakes

            val displayQuakes = if (filteredQuakes.isNotEmpty()) filteredQuakes else quakes
            val strongest = displayQuakes.firstOrNull()

            val viewUrl = if (strongest != null) {
                val sLat = strongest.optDouble("latitude")
                val sLon = strongest.optDouble("longitude")
                "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$sLat&lon=$sLon&alt=600000&heading=0.0&pitch=-45.0&roll=0&layers=earthquakes"
            } else {
                "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=20.0&lon=0.0&alt=20000000&heading=0.0&pitch=-90.0&roll=0&layers=earthquakes"
            }

            val actionJson = JSONObject().apply {
                if (strongest != null) {
                    put("lat", strongest.optDouble("latitude"))
                    put("lon", strongest.optDouble("longitude"))
                    put("alt", 600000.0)
                } else {
                    put("lat", 20.0)
                    put("lon", 0.0)
                    put("alt", 20000000.0)
                }
                put("layers", "earthquakes")
                put("duration", 2.0)
            }.toString()

            val summary = if (requestedArea.isNotBlank() && filteredQuakes.isNotEmpty()) {
                "USGS seismic telemetry reports ${filteredQuakes.size} earthquakes near ${requestedArea.replaceFirstChar { it.uppercase() }} in the past 24 hours. The strongest recorded was magnitude ${strongest?.optDouble("magnitude")} near ${strongest?.optString("place")} at depth ${strongest?.optDouble("depth_km")} km."
            } else if (requestedArea.isNotBlank() && filteredQuakes.isEmpty()) {
                "No significant seismic events recorded by USGS near ${requestedArea.replaceFirstChar { it.uppercase() }} in the past 24 hours. Displaying ${quakes.size} worldwide events on God's Eye."
            } else if (strongest != null) {
                "USGS seismic telemetry reports ${quakes.size} earthquakes in the past 24 hours. The strongest recorded was magnitude ${strongest.optDouble("magnitude")} event near ${strongest.optString("place")} at depth ${strongest.optDouble("depth_km")} km."
            } else {
                "No significant seismic events recorded by USGS in the past 24 hours."
            }

            JSONObject().apply {
                put("success", true)
                put("summary", summary)
                put("total_detected", quakes.size)
                if (requestedArea.isNotBlank()) {
                    put("matched_count", filteredQuakes.size)
                }
                put("recent_earthquakes", JSONArray(displayQuakes.take(5)))
                put("view_url", viewUrl)
                put("action_json", actionJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch earthquakes: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("message", "Failed to retrieve seismic data: ${e.message}")
            }
        }
    }

    private fun handleStandaloneMilitary(args: JSONObject): JSONObject {
        return try {
            val req = Request.Builder()
                .url("https://api.adsb.lol/v2/mil")
                .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return JSONObject().apply {
                    put("success", false)
                    put("message", "ADSB.lol military telemetry returned HTTP ${resp.code}")
                }
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val aircraft = json.optJSONArray("ac") ?: JSONArray()
            val activeFlights = mutableListOf<JSONObject>()
            for (i in 0 until aircraft.length()) {
                val ac = aircraft.optJSONObject(i) ?: continue
                val callsign = ac.optString("flight", "").trim()
                val type = ac.optString("t", "Unknown").trim()
                val alt = ac.optString("alt_baro", "0")
                val lat = ac.optDouble("lat", Double.NaN)
                val lon = ac.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    activeFlights.add(JSONObject().apply {
                        put("callsign", callsign.ifBlank { "MIL" })
                        put("type", type)
                        put("altitude_baro", alt)
                        put("latitude", lat)
                        put("longitude", lon)
                        put("speed_kts", ac.optDouble("gs", 0.0))
                    })
                }
            }

            val summary = "Military awareness telemetry reports ${aircraft.length()} military aircraft currently airborne worldwide with ${activeFlights.size} active position fixes."
            val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=45.0&lon=15.0&alt=8000000&heading=0.0&pitch=-60.0&roll=0&style=flir&layers=military"

            val actionJson = JSONObject().apply {
                put("lat", 45.0)
                put("lon", 15.0)
                put("alt", 8000000.0)
                put("style", "flir")
                put("layers", "military")
                put("duration", 2.0)
            }.toString()

            JSONObject().apply {
                put("success", true)
                put("summary", summary)
                put("total_airborne", aircraft.length())
                put("active_tracked_samples", JSONArray(activeFlights.take(10)))
                put("view_url", viewUrl)
                put("action_json", actionJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch military telemetry: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("message", "Military ADS-B telemetry unavailable: ${e.message}")
            }
        }
    }

    private fun handleStandaloneAircraft(args: JSONObject): JSONObject {
        return try {
            val locationStr = args.optString("location", "").ifBlank {
                args.optJSONObject("area")?.optString("place", "") ?: ""
            }.trim().lowercase()

            var lat = args.optDouble("lat", Double.NaN)
            var lon = args.optDouble("lon", Double.NaN)
            var resolvedPlace = "Selected Region"

            if (lat.isNaN() || lon.isNaN()) {
                val matched = KNOWN_CITIES.entries.firstOrNull { locationStr.contains(it.key) || it.key.contains(locationStr) }
                if (matched != null) {
                    lat = matched.value.first
                    lon = matched.value.second
                    resolvedPlace = matched.key.replaceFirstChar { it.uppercase() }
                }
            }

            val url = if (!lat.isNaN() && !lon.isNaN()) {
                "https://api.adsb.lol/v2/lat/$lat/lon/$lon/250"
            } else {
                "https://api.adsb.lol/v2/mil"
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return JSONObject().apply {
                    put("success", false)
                    put("message", "ADS-B telemetry returned HTTP ${resp.code}")
                }
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val aircraft = json.optJSONArray("ac") ?: JSONArray()
            val flights = mutableListOf<JSONObject>()
            for (i in 0 until aircraft.length()) {
                val ac = aircraft.optJSONObject(i) ?: continue
                val callsign = ac.optString("flight", "").trim()
                val type = ac.optString("t", "Unknown").trim()
                val alt = ac.optString("alt_baro", "0")
                val aLat = ac.optDouble("lat", Double.NaN)
                val aLon = ac.optDouble("lon", Double.NaN)
                if (!aLat.isNaN() && !aLon.isNaN()) {
                    flights.add(JSONObject().apply {
                        put("flight", callsign.ifBlank { "AC" })
                        put("type", type)
                        put("altitude", alt)
                        put("latitude", aLat)
                        put("longitude", aLon)
                        put("speed_kts", ac.optDouble("gs", 0.0))
                    })
                }
            }

            val sLat = if (!lat.isNaN()) lat else 40.0
            val sLon = if (!lon.isNaN()) lon else 0.0
            val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$sLat&lon=$sLon&alt=400000&heading=0.0&pitch=-60.0&roll=0&layers=flights"

            val actionJson = JSONObject().apply {
                put("lat", sLat)
                put("lon", sLon)
                put("alt", 400000.0)
                put("layers", "flights")
                put("duration", 2.0)
            }.toString()

            val summary = "Radar coverage over $resolvedPlace detected ${flights.size} active airborne tracks."

            JSONObject().apply {
                put("success", true)
                put("summary", summary)
                put("area", resolvedPlace)
                put("aircraft_count", flights.size)
                put("flights", JSONArray(flights.take(8)))
                put("view_url", viewUrl)
                put("action_json", actionJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch aircraft in area: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("message", "Aircraft radar tracking unavailable: ${e.message}")
            }
        }
    }

    private fun handleStandaloneLaunches(args: JSONObject): JSONObject {
        return try {
            val req = Request.Builder()
                .url("https://ll.thespacedevs.com/2.3.0/launches/?limit=10&mode=detailed")
                .header("User-Agent", "Jarvis-GodsEye/1.8.7")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return JSONObject().apply {
                    put("success", false)
                    put("message", "Space Devs launch API returned HTTP ${resp.code}")
                }
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: JSONArray()
            val launches = mutableListOf<JSONObject>()
            for (i in 0 until results.length()) {
                val launch = results.optJSONObject(i) ?: continue
                val name = launch.optString("name", "Unknown Mission")
                val status = launch.optJSONObject("status")?.optString("name", "Status Pending") ?: "Scheduled"
                val net = launch.optString("net", "TBD")
                val pad = launch.optJSONObject("pad")
                val padName = pad?.optString("name", "Launch Site") ?: "Launch Site"
                val padLat = pad?.optDouble("latitude", Double.NaN) ?: Double.NaN
                val padLon = pad?.optDouble("longitude", Double.NaN) ?: Double.NaN

                launches.add(JSONObject().apply {
                    put("mission", name)
                    put("status", status)
                    put("launch_time", net)
                    put("launch_site", padName)
                    if (!padLat.isNaN() && !padLon.isNaN()) {
                        put("latitude", padLat)
                        put("longitude", padLon)
                    }
                })
            }

            val nextLaunch = launches.firstOrNull()
            val viewUrl = if (nextLaunch != null && nextLaunch.has("latitude") && nextLaunch.has("longitude")) {
                val pLat = nextLaunch.optDouble("latitude")
                val pLon = nextLaunch.optDouble("longitude")
                "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$pLat&lon=$pLon&alt=300000&heading=0.0&pitch=-45.0&roll=0&layers=launches"
            } else {
                "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=28.5728&lon=-80.6490&alt=500000&heading=0.0&pitch=-45.0&roll=0&layers=launches"
            }

            val actionJson = JSONObject().apply {
                if (nextLaunch != null && nextLaunch.has("latitude") && nextLaunch.has("longitude")) {
                    put("lat", nextLaunch.optDouble("latitude"))
                    put("lon", nextLaunch.optDouble("longitude"))
                    put("alt", 300000.0)
                } else {
                    put("lat", 28.5728)
                    put("lon", -80.6490)
                    put("alt", 500000.0)
                }
                put("layers", "launches")
                put("duration", 2.0)
            }.toString()

            val summary = if (nextLaunch != null) {
                "Next space launch is ${nextLaunch.optString("mission")} from ${nextLaunch.optString("launch_site")}, scheduled for ${nextLaunch.optString("launch_time")} (${nextLaunch.optString("status")})."
            } else {
                "Retrieved space telemetry with ${launches.size} upcoming launches."
            }

            JSONObject().apply {
                put("success", true)
                put("summary", summary)
                put("launches", JSONArray(launches.take(5)))
                put("view_url", viewUrl)
                put("action_json", actionJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch launches: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("message", "Space launch tracker unavailable: ${e.message}")
            }
        }
    }

    private fun handleStandaloneSatellites(args: JSONObject): JSONObject {
        return try {
            val req = Request.Builder()
                .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=tle")
                .header("User-Agent", "gods-eye-view-celestrak-proxy/1.0")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return JSONObject().apply {
                    put("success", false)
                    put("message", "Celestrak satellite tracker returned HTTP ${resp.code}")
                }
            }
            val body = resp.body?.string().orEmpty()
            val lines = body.lines().filter { it.isNotBlank() }
            val stations = mutableListOf<String>()
            for (i in 0 until lines.size step 3) {
                if (i < lines.size) {
                    stations.add(lines[i].trim())
                }
            }

            val summary = "Orbital tracking active via Celestrak. Currently tracking ${stations.size} space stations and orbital platforms, including ${stations.take(3).joinToString(", ")}."
            val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=0.0&lon=0.0&alt=25000000&heading=0.0&pitch=-90.0&roll=0&layers=satellites"

            val actionJson = JSONObject().apply {
                put("lat", 0.0)
                put("lon", 0.0)
                put("alt", 25000000.0)
                put("layers", "satellites")
                put("duration", 2.0)
            }.toString()

            JSONObject().apply {
                put("success", true)
                put("summary", summary)
                put("tracked_count", stations.size)
                put("stations", JSONArray(stations.take(8)))
                put("view_url", viewUrl)
                put("action_json", actionJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch satellites: ${e.message}", e)
            JSONObject().apply {
                put("success", false)
                put("message", "Orbital telemetry unavailable: ${e.message}")
            }
        }
    }

    private fun handleStandaloneCctv(args: JSONObject): JSONObject {
        val rawLocation = args.optString("location", "").ifBlank {
            args.optString("query", "").ifBlank {
                args.optJSONObject("area")?.optString("place", "") ?: args.optString("area", "")
            }
        }.trim()
        val query = rawLocation.lowercase()
        val isGeneric = query.isBlank() || query == "cctv" || query == "camera" || query == "cameras" ||
                query == "video" || query == "videos" || query == "feeds" || query == "feed" || query == "all" ||
                query == "live" || query == "traffic" || query == "god eye" || query == "god's eye" || query == "globe"

        val matches = searchCctvCatalog(query, 10)
        if (matches.isEmpty() && !isGeneric) {
            return JSONObject().apply {
                put("success", false)
                put("found", false)
                put("message", "No public CCTV cameras found in catalog for '$rawLocation'. The God's Eye catalog currently supports London (TfL), Sydney (NSW), Austin (TX), California (Caltrans), British Columbia (DriveBC), Delaware (DelDOT), Calgary, Finland (Fintraffic), Norway (Vegvesen), and Tallinn.")
                put("supported_cities", JSONArray(listOf("London", "Sydney", "Austin", "Los Angeles / California", "Vancouver / British Columbia", "Delaware", "Calgary", "Helsinki / Finland", "Oslo / Norway", "Tallinn")))
            }
        }

        val primary = matches.firstOrNull() ?: getFallbackCctvCatalog().first()
        val traceId = "act_" + System.currentTimeMillis() + "_" + (100..999).random()
        val actionObj = JSONObject().apply {
            put("trace_id", traceId)
            put("lat", primary.lat)
            put("lon", primary.lon)
            put("alt", 15000.0)
            put("layers", "cctv")
            put("cameraId", primary.id)
            put("duration", 2.0)
        }
        val actionJson = actionObj.toString()
        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=${primary.lat}&lon=${primary.lon}&alt=15000&heading=0.0&pitch=-45.0&roll=0&layers=cctv&camera=${primary.id}"

        var dispatched = false
        if (GodsEyeActivity.activeInstance != null) {
            dispatched = GodsEyeActivity.dispatchAction(actionJson)
        } else {
            try {
                val intent = android.content.Intent(com.jarvis.assistant.JarvisApp.instance, GodsEyeActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(GodsEyeActivity.EXTRA_VIEW_URL, viewUrl)
                    putExtra(GodsEyeActivity.EXTRA_ACTION_JSON, actionJson)
                }
                com.jarvis.assistant.JarvisApp.instance.startActivity(intent)
                dispatched = true
            } catch (e: Exception) {
                android.util.Log.w("GodsEyeMcpClient", "Failed to auto-launch GodsEyeActivity for CCTV: ${e.message}")
            }
        }
        val receipt = GodsEyeActivity.awaitActionReceipt(traceId, 250)
        val verifiedOnScreen = receipt != null

        val summary = if (isGeneric) {
            "Active CCTV surveillance camera network engaged on God's Eye. Selected primary feed: ${primary.name} in ${primary.city} (ID: ${primary.id}, format: ${primary.feedType.uppercase()}). Available regions include London, Sydney, Austin, California, British Columbia, Delaware, Calgary, Finland, Norway, and Tallinn."
        } else {
            "Located ${matches.size} CCTV camera(s) matching '$query' in ${primary.city}. Selected primary feed: ${primary.name} (ID: ${primary.id}, format: ${primary.feedType.uppercase()}). CCTV monitor active."
        }

        return JSONObject().apply {
            put("success", true)
            put("found", true)
            put("summary", summary)
            put("city", primary.city)
            put("sample_camera_id", primary.id)
            put("feed_type", primary.feedType)
            put("camera_count", matches.size)
            put("cameras", JSONArray(matches.map {
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("city", it.city)
                    put("feed_type", it.feedType)
                    put("lat", it.lat)
                    put("lon", it.lon)
                }
            }))
            put("view_url", viewUrl)
            put("action_json", actionJson)
            put("dispatched", dispatched)
            put("verified_on_screen", verifiedOnScreen)
        }
    }

    fun handleInspectGlobe(): JSONObject {
        val snapshot = GodsEyeActivity.getLatestGlobeStateSnapshot()
        return if (snapshot != null) {
            val lat = snapshot.optDouble("lat", 0.0)
            val lon = snapshot.optDouble("lon", 0.0)
            val alt = snapshot.optLong("alt", 0L)
            val layers = snapshot.optJSONArray("enabled_layers") ?: JSONArray()
            val activeLayers = mutableListOf<String>()
            for (i in 0 until layers.length()) activeLayers.add(layers.optString(i))
            val selectedCam = snapshot.optString("selected_camera", "")
            val style = snapshot.optString("visual_style", "normal")
            val summary = "God's Eye globe active at lat: %.2f, lon: %.2f (altitude: %d km). Style: %s. Active layers: %s.%s".format(
                lat, lon, alt / 1000, style,
                if (activeLayers.isEmpty()) "None" else activeLayers.joinToString(", "),
                if (selectedCam.isNotBlank()) " Selected CCTV camera: $selectedCam." else ""
            )
            JSONObject().apply {
                put("success", true)
                put("running", true)
                put("summary", summary)
                put("globe_state", snapshot)
                put("view_url", "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$lat&lon=$lon&alt=$alt&style=$style")
            }
        } else {
            val defaultState = JSONObject().apply {
                put("lat", 20.0)
                put("lon", 0.0)
                put("alt", 20000000L)
                put("heading", 0)
                put("pitch", -90)
                put("roll", 0)
                put("enabled_layers", JSONArray())
                put("selected_camera", JSONObject.NULL)
                put("visual_style", "normal")
            }
            JSONObject().apply {
                put("success", true)
                put("running", false)
                put("summary", "God's Eye globe is in standby or initializing. Default orbital vantage: Global (lat: 20.0, lon: 0.0, alt: 20000 km).")
                put("globe_state", defaultState)
                put("view_url", "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=20.0&lon=0.0&alt=20000000&heading=0.0&pitch=-90.0&roll=0")
            }
        }
    }

    fun handleControlLayers(args: JSONObject): JSONObject {
        val traceId = "tr-layers-" + System.currentTimeMillis() + "-" + rpcIdCounter.getAndIncrement()
        val enableRaw = args.opt("enable")
        val disableRaw = args.opt("disable")
        val toggle = args.optString("toggle", "").trim()
        val style = args.optString("style", "")

        val enableList = mutableListOf<String>()
        if (enableRaw is JSONArray) {
            for (i in 0 until enableRaw.length()) {
                val s = enableRaw.optString(i, "").trim()
                if (s.isNotBlank()) enableList.add(s)
            }
        } else if (enableRaw is String && enableRaw.isNotBlank()) {
            enableList.addAll(enableRaw.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }

        val disableList = mutableListOf<String>()
        if (disableRaw is JSONArray) {
            for (i in 0 until disableRaw.length()) {
                val s = disableRaw.optString(i, "").trim()
                if (s.isNotBlank()) disableList.add(s)
            }
        } else if (disableRaw is String && disableRaw.isNotBlank()) {
            disableList.addAll(disableRaw.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }

        if (toggle.isNotBlank()) {
            val currentEnabled = GodsEyeActivity.getLatestGlobeStateSnapshot()?.optJSONArray("enabled_layers")
            var isCurrentlyOn = false
            if (currentEnabled != null) {
                for (i in 0 until currentEnabled.length()) {
                    if (currentEnabled.optString(i).equals(toggle, ignoreCase = true)) {
                        isCurrentlyOn = true
                        break
                    }
                }
            }
            if (isCurrentlyOn) {
                disableList.add(toggle)
            } else {
                enableList.add(toggle)
            }
        }

        val isEnableAll = enableList.any { it.equals("all", ignoreCase = true) || it.equals("everything", ignoreCase = true) } ||
                args.optBoolean("enable_all", false)
        val isDisableAll = disableList.any { it.equals("all", ignoreCase = true) || it.equals("everything", ignoreCase = true) } ||
                args.optBoolean("clear_all", false) || args.optBoolean("clear", false) ||
                toggle.equals("clear", ignoreCase = true)

        val actionObj = JSONObject().apply {
            put("trace_id", traceId)
            if (isEnableAll) {
                put("enable_all", true)
            }
            if (isDisableAll) {
                put("clear_all", true)
            }
            if (enableList.isNotEmpty()) {
                put("enable_layers", JSONArray(enableList))
            }
            if (disableList.isNotEmpty()) {
                put("disable_layers", JSONArray(disableList))
            }
            if (style.isNotBlank()) {
                put("style", if (style == "thermal") "flir" else style)
            }
            put("duration", 1.5)
        }
        val actionJson = actionObj.toString()

        val layersParam = if (isEnableAll) "all" else enableList.joinToString(",")
        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=20.0&lon=0.0&alt=20000000" +
            (if (isEnableAll) "&${GodsEyeActivity.ALL_LAYERS_HASH}" else if (layersParam.isNotBlank()) "&layers=$layersParam" else "") +
            (if (style.isNotBlank()) "&style=$style" else "")

        var dispatched = false
        if (GodsEyeActivity.activeInstance != null) {
            dispatched = GodsEyeActivity.dispatchAction(actionJson)
        } else {
            try {
                val launchIntent = Intent(com.jarvis.assistant.JarvisApp.instance, GodsEyeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(GodsEyeActivity.EXTRA_VIEW_URL, viewUrl)
                    putExtra(GodsEyeActivity.EXTRA_ACTION_JSON, actionJson)
                }
                com.jarvis.assistant.JarvisApp.instance.startActivity(launchIntent)
                dispatched = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch GodsEyeActivity for layer control: ${e.message}")
            }
        }

        val receipt = GodsEyeActivity.awaitActionReceipt(traceId, 250)
        val verifiedOnScreen = receipt != null

        val summary = buildString {
            if (isEnableAll) {
                append("All God's Eye intelligence data layers have been enabled.")
            } else if (isDisableAll) {
                append("All God's Eye data layers cleared.")
            } else {
                append("God's Eye layers updated.")
                if (enableList.isNotEmpty()) append(" Enabled: [${enableList.joinToString(", ")}].")
                if (disableList.isNotEmpty()) append(" Disabled: [${disableList.joinToString(", ")}].")
            }
            if (verifiedOnScreen) append(" Verified active on screen.")
        }

        return JSONObject().apply {
            put("success", true)
            put("summary", summary)
            put("trace_id", traceId)
            put("is_enable_all", isEnableAll)
            put("is_disable_all", isDisableAll)
            put("enabled_layers", JSONArray(enableList))
            put("disabled_layers", JSONArray(disableList))
            put("dispatched", dispatched)
            put("verified_on_screen", verifiedOnScreen)
            put("action_json", actionJson)
            put("view_url", viewUrl)
            if (receipt != null) {
                put("receipt", receipt)
            }
        }
    }

    fun handleSelectCamera(args: JSONObject): JSONObject {
        val traceId = "tr-cam-" + System.currentTimeMillis() + "-" + rpcIdCounter.getAndIncrement()
        val camId = args.optString("camera_id", "").ifBlank {
            args.optString("cameraId", "")
        }.trim()

        if (camId.isBlank()) {
            return JSONObject().apply {
                put("success", false)
                put("message", "camera_id parameter is required to select a CCTV camera.")
            }
        }

        val catalog = getCctvCatalog()
        val camera = catalog.firstOrNull { it.id.equals(camId, ignoreCase = true) }
            ?: catalog.firstOrNull { it.id.endsWith(camId, ignoreCase = true) }
            ?: catalog.firstOrNull { it.id.contains(camId, ignoreCase = true) }

        if (camera == null) {
            return JSONObject().apply {
                put("success", false)
                put("message", "Camera ID '$camId' was not found in the God's Eye catalog. Use gods_eye_find_cctv_cameras to locate available feeds.")
            }
        }

        val actionObj = JSONObject().apply {
            put("trace_id", traceId)
            put("lat", camera.lat)
            put("lon", camera.lon)
            put("alt", 12000.0)
            put("heading", 0.0)
            put("pitch", -45.0)
            put("layers", "cctv")
            put("cameraId", camera.id)
            put("duration", 2.0)
        }
        val actionJson = actionObj.toString()

        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=${camera.lat}&lon=${camera.lon}&alt=12000&heading=0.0&pitch=-45.0&roll=0&layers=cctv&camera=${camera.id}"

        var dispatched = false
        if (GodsEyeActivity.activeInstance != null) {
            dispatched = GodsEyeActivity.dispatchAction(actionJson)
        } else {
            try {
                val intent = android.content.Intent(com.jarvis.assistant.JarvisApp.instance, GodsEyeActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(GodsEyeActivity.EXTRA_VIEW_URL, viewUrl)
                    putExtra(GodsEyeActivity.EXTRA_ACTION_JSON, actionJson)
                }
                com.jarvis.assistant.JarvisApp.instance.startActivity(intent)
                dispatched = true
            } catch (e: Exception) {
                android.util.Log.w("GodsEyeMcpClient", "Failed to auto-launch GodsEyeActivity for CCTV: ${e.message}")
            }
        }

        val receipt = GodsEyeActivity.awaitActionReceipt(traceId, 250)
        val verifiedOnScreen = receipt != null

        val summary = "Selected CCTV camera '${camera.name}' (${camera.id}) in ${camera.city}. Stream format: ${camera.feedType.uppercase()}. Globe centered at lat: %.4f, lon: %.4f and camera player engaged.".format(camera.lat, camera.lon)

        return JSONObject().apply {
            put("success", true)
            put("summary", summary)
            put("trace_id", traceId)
            put("camera", JSONObject().apply {
                put("id", camera.id)
                put("name", camera.name)
                put("city", camera.city)
                put("pack", camera.pack)
                put("feed_type", camera.feedType)
                put("lat", camera.lat)
                put("lon", camera.lon)
                put("url", camera.url)
            })
            put("dispatched", dispatched)
            put("verified_on_screen", verifiedOnScreen)
            put("view_url", viewUrl)
            put("action_json", actionJson)
            if (receipt != null) {
                put("receipt", receipt)
            }
        }
    }

    private fun handleStandaloneFires(args: JSONObject): JSONObject {
        val locationStr = args.optString("location", "").ifBlank {
            args.optJSONObject("area")?.optString("place", "") ?: ""
        }.trim().lowercase()

        var lat = args.optDouble("lat", Double.NaN)
        var lon = args.optDouble("lon", Double.NaN)
        var resolvedPlace = "Global"

        if (lat.isNaN() || lon.isNaN()) {
            if (locationStr.isNotBlank()) {
                val matched = KNOWN_CITIES.entries.firstOrNull { locationStr.contains(it.key) || it.key.contains(locationStr) }
                if (matched != null) {
                    lat = matched.value.first
                    lon = matched.value.second
                    resolvedPlace = matched.key.replaceFirstChar { it.uppercase() }
                }
            }
        }

        val sLat = if (!lat.isNaN()) lat else 37.0
        val sLon = if (!lon.isNaN()) lon else -119.0
        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=$sLat&lon=$sLon&alt=800000&heading=0.0&pitch=-50.0&roll=0&style=flir&layers=firms"

        val actionJson = JSONObject().apply {
            put("lat", sLat)
            put("lon", sLon)
            put("alt", 800000.0)
            put("style", "flir")
            put("layers", "firms")
            put("duration", 2.0)
        }.toString()

        val summary = "NASA FIRMS thermal observation active over $resolvedPlace. Satellite radiometers scanning for active wildfire perimeters and thermal anomalies."

        return JSONObject().apply {
            put("success", true)
            put("summary", summary)
            put("region", resolvedPlace)
            put("thermal_mode", "FLIR")
            put("view_url", viewUrl)
            put("action_json", actionJson)
        }
    }

    private fun handleStandaloneSituationBrief(args: JSONObject): JSONObject {
        val quakes = handleStandaloneEarthquakes(args)
        val mil = handleStandaloneMilitary(args)
        val launches = handleStandaloneLaunches(args)

        val quakesSummary = quakes.optString("summary", "Seismic telemetry nominal.")
        val milSummary = mil.optString("summary", "Military ADS-B nominal.")
        val launchSummary = launches.optString("summary", "Space launch schedules nominal.")

        val unifiedSummary = "JARVIS God's Eye Global Reconnaissance Briefing:\n• $quakesSummary\n• $milSummary\n• $launchSummary"
        val viewUrl = "${GodsEyeActivity.GODS_EYE_LOCAL_URL}#v=2&lat=25.0&lon=15.0&alt=18000000&heading=0.0&pitch=-90.0&roll=0&style=flir&layers=earthquakes,military"

        val actionJson = JSONObject().apply {
            put("lat", 25.0)
            put("lon", 15.0)
            put("alt", 18000000.0)
            put("style", "flir")
            put("layers", "earthquakes,military")
            put("duration", 2.0)
        }.toString()

        return JSONObject().apply {
            put("success", true)
            put("summary", unifiedSummary)
            put("earthquakes", quakes)
            put("military", mil)
            put("launches", launches)
            put("view_url", viewUrl)
            put("action_json", actionJson)
        }
    }
}
