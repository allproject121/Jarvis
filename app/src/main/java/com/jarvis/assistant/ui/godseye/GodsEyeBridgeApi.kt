package com.jarvis.assistant.ui.godseye

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.preferences.AppPreferences
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * God's Eye routes the WebView interception could not serve on its own.
 *
 * - POST bodies: `shouldInterceptRequest` never sees a request body, so Overpass
 *   queries (ALPR cameras, admin boundaries, map features), the HUD summary and
 *   the key-setup save all arrived empty. `jarvis-bridge.js` hands those POSTs
 *   to [handlePost] through `JarvisVoiceBridge.apiPost`.
 * - Satellites: CelesTrak blocks clients that download the same group too often
 *   (HTTP 403), and the app used to fetch six groups on every launch with no
 *   cache. [satelliteTle] keeps each group for two hours on disk, serves a stale
 *   copy when CelesTrak refuses, and falls back to the SatNOGS public TLE set.
 */
object GodsEyeBridgeApi {

    private const val TAG = "GodsEyeBridgeApi"

    data class Reply(val status: Int, val contentType: String, val body: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun json(status: Int, obj: JSONObject) = Reply(status, "application/json", obj.toString())

    fun handlePost(context: Context, prefs: AppPreferences, pathAndQuery: String, body: String, contentType: String): Reply {
        val path = pathAndQuery.substringBefore('?')
        return try {
            when {
                path.startsWith("/api/overpass") -> overpass(body, contentType)
                path.startsWith("/api/openai/hud-summary") || path.startsWith("/api/hud-summary") -> hudSummary(prefs, body)
                path.startsWith("/api/setup/keys") -> saveKeys(prefs, body)
                else -> json(501, JSONObject().put("error", "No POST handler for $path"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $path failed: ${e.message}")
            json(502, JSONObject().put("error", e.message ?: "request failed"))
        }
    }

    // -- Overpass -----------------------------------------------------------

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private fun overpass(body: String, contentType: String): Reply {
        if (body.isBlank()) return json(400, JSONObject().put("error", "Empty Overpass query"))
        val mediaType = (contentType.ifBlank { "application/x-www-form-urlencoded" }).toMediaTypeOrNull()
        var last: Reply? = null
        for (endpoint in OVERPASS_ENDPOINTS) {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "Jarvis-GodsEye/1.9 (Android)")
                .post(body.toRequestBody(mediaType))
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                last = Reply(resp.code, resp.header("Content-Type") ?: "application/json", text)
                // Rate-limited / busy: the console handles 429 and 504 itself, but a mirror may answer.
                if (resp.isSuccessful || resp.code !in setOf(429, 502, 503, 504)) return last!!
            }
        }
        return last ?: json(502, JSONObject().put("error", "Overpass unreachable"))
    }

    // -- HUD summary (Gemini first, then OpenAI) ----------------------------

    private const val HUD_INSTRUCTIONS =
        "Write one concise intelligence-HUD summary for God's Eye View. " +
            "Use only the supplied place, street, nearby-place, enabled-layer labels, and feedProvenance. " +
            "Prefer the clearest named place and include a relevant enabled layer only when useful. " +
            "If any enabled layer is not nominal, the five words MUST include that feedState token " +
            "(STALE, DEGRADED, FALLBACK, LOADING, or UNAVAILABLE) and must not present the view as live. " +
            "Do not infer from coordinates or invent a place or feed state. " +
            "Output exactly five words with no title, punctuation, markdown, or introductory phrase."

    @Volatile private var lastSummaryAt = 0L
    @Volatile private var lastSummaryInput = ""
    @Volatile private var lastSummary: String? = null

    private fun fiveWords(text: String): String =
        text.replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ").trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }.take(5).joinToString(" ")

    private fun hudSummary(prefs: AppPreferences, body: String): Reply {
        val gemini = prefs.apiKey.trim()
        val openAi = prefs.openAiApiKey.trim()
        if (gemini.isBlank() && openAi.isBlank()) {
            return json(200, JSONObject().put("configured", false).put("code", "HUD_SUMMARY_UNCONFIGURED")
                .put("error", JSONObject.NULL).put("summary", JSONObject.NULL))
        }
        // The console asks on every view change; one model call per 20 s (or per new view) is plenty.
        val now = System.currentTimeMillis()
        lastSummary?.let { cached ->
            if (body == lastSummaryInput || now - lastSummaryAt < 20_000L) {
                return json(200, JSONObject().put("configured", true).put("summary", cached).put("error", JSONObject.NULL))
            }
        }
        val input = body.take(12_000)
        val text = (if (gemini.isNotBlank()) geminiText(gemini, prefs, input) else null)
            ?: (if (openAi.isNotBlank()) openAiText(openAi, input) else null)
        val summary = text?.let(::fiveWords)?.takeIf { it.isNotBlank() }
            ?: return json(502, JSONObject().put("configured", true).put("summary", JSONObject.NULL)
                .put("error", "HUD summary request failed"))
        lastSummary = summary
        lastSummaryAt = now
        lastSummaryInput = body
        return json(200, JSONObject().put("configured", true).put("summary", summary)
            .put("provider", if (gemini.isNotBlank()) "gemini" else "openai").put("error", JSONObject.NULL))
    }

    private fun geminiText(key: String, prefs: AppPreferences, input: String): String? {
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", HUD_INSTRUCTIONS))))
            put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", input)))))
            put("generationConfig", JSONObject().put("maxOutputTokens", 40).put("temperature", 0.2))
        }.toString()
        for (model in com.jarvis.assistant.ai.GeminiTextModels.candidates(key, prefs.selectedModelName, max = 3)) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            try {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@use
                    val text = (0 until parts.length()).joinToString(" ") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
                    if (text.isNotBlank()) {
                        com.jarvis.assistant.ai.GeminiTextModels.markWorking(key, model)
                        return text
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini HUD summary with $model failed: ${e.message}")
            }
        }
        return null
    }

    private fun openAiText(key: String, input: String): String? {
        val payload = JSONObject().apply {
            put("model", "gpt-5-nano")
            put("instructions", HUD_INSTRUCTIONS)
            put("input", input)
            put("max_output_tokens", 100)
        }.toString()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optString("output_text").takeIf { it.isNotBlank() }
                    ?: root.optJSONArray("output")?.let { out ->
                        (0 until out.length()).flatMap { i ->
                            val content = out.optJSONObject(i)?.optJSONArray("content") ?: JSONArray()
                            (0 until content.length()).map { content.optJSONObject(it)?.optString("text").orEmpty() }
                        }.joinToString(" ").trim()
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI HUD summary failed: ${e.message}")
            null
        }
    }

    // -- Key setup (POWER UP dialog) ----------------------------------------

    /** Upstream env var -> the Jarvis preference that holds it. */
    private fun keySetter(prefs: AppPreferences, envVar: String): ((String) -> Unit)? = when (envVar) {
        "GOOGLE_MAPS_API_KEY" -> { v -> prefs.godsEyeGoogleApiKey = v }
        "OPENAI_API_KEY" -> { v -> prefs.openAiApiKey = v }
        "GEMINI_API_KEY" -> { v -> prefs.apiKey = v }
        "AISSTREAM_API_KEY" -> { v -> prefs.godsEyeAisStreamKey = v }
        "FIRMS_MAP_KEY" -> { v -> prefs.godsEyeNasaFirmsKey = v }
        "TOMTOM_API_KEY" -> { v -> prefs.godsEyeTomTomKey = v }
        "CESIUM_ION_TOKEN" -> { v -> prefs.godsEyeCesiumIonToken = v }
        "OPENSKY_CLIENT_ID" -> { v -> prefs.godsEyeOpenSkyClientId = v }
        "OPENSKY_CLIENT_SECRET" -> { v -> prefs.godsEyeOpenSkyClientSecret = v }
        "MAPILLARY_CLIENT_TOKEN" -> { v -> prefs.godsEyeMapillaryToken = v }
        else -> null
    }

    /** Body: `{ "ENV_VAR": "value", ... }` (empty string clears). Reply: `{ok, saved[], status}`. */
    private fun saveKeys(prefs: AppPreferences, body: String): Reply {
        val updates = try { JSONObject(body.ifBlank { "{}" }) } catch (_: Exception) {
            return json(400, JSONObject().put("ok", false).put("error", "Bad key update"))
        }
        val saved = JSONArray()
        val unknown = mutableListOf<String>()
        updates.keys().forEach { envVar ->
            val setter = keySetter(prefs, envVar)
            if (setter == null) unknown += envVar else {
                setter(updates.optString(envVar).trim())
                saved.put(envVar)
            }
        }
        val status = JSONObject(GodsEyeStandaloneApi.setupStatusJson(prefs))
        if (saved.length() == 0) {
            return json(400, JSONObject().put("ok", false).put("error", "Unknown key(s): ${unknown.joinToString()}").put("status", status))
        }
        return json(200, JSONObject().put("ok", true).put("saved", saved).put("status", status)
            .put("restartRequired", saved.toString().contains("GOOGLE_MAPS_API_KEY") || saved.toString().contains("CESIUM_ION_TOKEN")))
    }

    // -- Satellites ---------------------------------------------------------

    private const val TLE_FRESH_MS = 2 * 60 * 60 * 1000L
    private const val SATNOGS_URL = "https://db.satnogs.org/api/tle/?format=json"

    private fun tleCacheFile(context: Context, name: String): File =
        File(context.cacheDir, "godseye-tle").apply { mkdirs() }.let { File(it, "$name.tle") }

    /**
     * TLE text (name + two lines per object) for a CelesTrak group, or null.
     * Order: fresh cache, CelesTrak, stale cache, SatNOGS subset.
     */
    fun satelliteTle(context: Context, rawGroup: String): String? {
        val group = rawGroup.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "active" }
        val cache = tleCacheFile(context, group)
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < TLE_FRESH_MS && cache.length() > 0) {
            return cache.readText()
        }
        try {
            val request = Request.Builder()
                .url("https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle")
                .header("User-Agent", "gods-eye-view-celestrak-proxy/1.0 (+https://github.com/bilawalsidhu/gods-eye-view)")
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful && text.contains("\n1 ")) {
                    cache.writeText(text)
                    return text
                }
                Log.w(TAG, "CelesTrak $group answered HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "CelesTrak $group failed: ${e.message}")
        }
        if (cache.exists() && cache.length() > 0) return cache.readText()
        return satnogsSubset(context, group)?.also { if (it.isNotBlank()) cache.writeText(it) }
    }

    private val NAME_FILTERS: Map<String, (String) -> Boolean> = mapOf(
        "stations" to { n -> n.contains("ISS") || n.contains("TIANHE") || n.contains("CSS") || n.contains("TIANGONG") },
        "gps-ops" to { n -> n.contains("NAVSTAR") || n.contains("GPS") },
        "glo-ops" to { n -> n.contains("GLONASS") || n.contains("COSMOS 2") },
        "galileo" to { n -> n.contains("GALILEO") || n.contains("GSAT0") },
        "visual" to { n -> n.contains("ISS") || n.contains("HST") || n.contains("TIANHE") || n.contains("R/B") || n.contains("STARLINK") }
    )

    private fun satnogsSubset(context: Context, group: String): String? {
        val all = tleCacheFile(context, "_satnogs")
        val raw = if (all.exists() && System.currentTimeMillis() - all.lastModified() < TLE_FRESH_MS) all.readText() else try {
            http.newCall(Request.Builder().url(SATNOGS_URL).header("User-Agent", "Jarvis-GodsEye/1.9").build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty().also { all.writeText(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SatNOGS TLE fetch failed: ${e.message}")
            return null
        }
        val rows = try { JSONArray(raw) } catch (_: Exception) { return null }
        val filter = NAME_FILTERS[group]
        val out = StringBuilder()
        var count = 0
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("tle0").removePrefix("0 ").trim()
            val l1 = row.optString("tle1")
            val l2 = row.optString("tle2")
            if (l1.isBlank() || l2.isBlank()) continue
            val keep = when {
                group == "geo" -> l2.length >= 63 && (l2.substring(52, 63).trim().toDoubleOrNull() ?: 0.0) in 0.98..1.02
                filter != null -> filter(name.uppercase())
                else -> true
            }
            if (!keep) continue
            out.append(name).append('\n').append(l1).append('\n').append(l2).append('\n')
            if (++count >= 2500) break
        }
        Log.i(TAG, "Satellites '$group' from SatNOGS fallback: $count objects")
        return out.toString()
    }

    // -- Fire perimeters (NIFC WFIGS) + InciWeb ------------------------------
    // Port of server/providers/firePerimeters.js + src/layers/perimeters/records.js:
    // the console reads `{fetchedAt, rows[]}`, not the raw GeoJSON the app used to pass on.

    private const val WFIGS_URL =
        "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/" +
            "WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query"
    private val WFIGS_FIELDS = listOf(
        "poly_IncidentName", "attr_UniqueFireIdentifier", "attr_IncidentSize", "attr_PercentContained",
        "attr_POOState", "attr_IncidentTypeCategory", "attr_FireDiscoveryDateTime", "poly_DateCurrent",
        "attr_FireCause", "attr_FireBehaviorGeneral", "attr_TotalIncidentPersonnel", "attr_POOCounty",
        "attr_EstimatedCostToDate", "attr_IncidentComplexityLevel", "attr_CpxName"
    ).joinToString(",")

    private class Cached(val value: String, val savedAt: Long)
    private val fireCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    /** Fresh value within [ttlMs], else load; on a failed load the previous value (stale = true). */
    private fun cachedOr(key: String, ttlMs: Long, load: () -> String?): Pair<String?, Boolean> {
        val prev = fireCache[key]
        if (prev != null && System.currentTimeMillis() - prev.savedAt < ttlMs) return prev.value to false
        val fresh = try { load() } catch (e: Exception) { Log.w(TAG, "$key failed: ${e.message}"); null }
        if (fresh != null) {
            fireCache[key] = Cached(fresh, System.currentTimeMillis())
            return fresh to false
        }
        return prev?.value to (prev != null)
    }

    /** `GET /api/fire-perimeters[/inciweb/index | /inciweb/publication/{id}]`. */
    fun firePerimeters(path: String): Reply {
        val sub = path.removePrefix("/api/fire-perimeters").substringBefore('?').trimEnd('/')
        return when {
            sub.isEmpty() -> {
                val (value, stale) = cachedOr("perimeters", 300_000L) { loadPerimeters() }
                if (value == null) json(502, JSONObject().put("error", "upstream_unavailable"))
                else Reply(200, "application/json", if (stale) JSONObject(value).put("stale", true).toString() else value)
            }
            sub == "/inciweb/index" -> {
                val (value, _) = cachedOr("inciweb-index", 3_600_000L) {
                    val req = Request.Builder().url("https://inciweb.wildfire.gov/api/single-publication/")
                        .header("User-Agent", "Jarvis-GodsEye/1.9")
                        .post("""{"title":""}""".toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    http.newCall(req).execute().use { r ->
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful && text.trimStart().startsWith("[")) text else null
                    }
                }
                if (value == null) json(502, JSONObject().put("error", "upstream_unavailable"))
                else Reply(200, "application/json", value)
            }
            sub.startsWith("/inciweb/publication/") -> {
                val id = sub.removePrefix("/inciweb/publication/")
                if (!id.matches(Regex("""^\d{1,9}$"""))) return json(400, JSONObject().put("error", "invalid_publication_id"))
                val (value, _) = cachedOr("pub:$id", 1_800_000L) { loadPublication(id) }
                if (value == null) json(502, JSONObject().put("error", "upstream_unavailable"))
                else Reply(200, "application/json", value)
            }
            else -> json(404, JSONObject().put("error", "unknown_route"))
        }
    }

    private fun loadPerimeters(): String? {
        val rows = JSONArray()
        val ids = HashSet<String>()
        var offset = 0
        // The service caps a response at 2000 features; peak season pages with exceededTransferLimit.
        for (page in 0 until 5) {
            val url = "$WFIGS_URL?where=1%3D1&outFields=${java.net.URLEncoder.encode(WFIGS_FIELDS, "UTF-8")}" +
                "&maxAllowableOffset=0.001&outSR=4326&f=geojson&resultOffset=$offset"
            val payload = http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-GodsEye/1.9").build())
                .execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else return null }
            val features = payload.optJSONArray("features") ?: return null
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                normalizeFire(feature, ids)?.let(rows::put)
            }
            val more = payload.optBoolean("exceededTransferLimit") ||
                payload.optJSONObject("properties")?.optBoolean("exceededTransferLimit") == true
            if (!more || features.length() == 0) break
            offset += features.length()
        }
        return JSONObject().put("fetchedAt", System.currentTimeMillis()).put("rows", rows).toString()
    }

    private fun validRing(ring: JSONArray?): Boolean {
        if (ring == null || ring.length() < 4) return false
        for (i in 0 until ring.length()) {
            val p = ring.optJSONArray(i) ?: return false
            if (p.length() < 2) return false
            val lon = p.optDouble(0, Double.NaN)
            val lat = p.optDouble(1, Double.NaN)
            if (lon.isNaN() || Math.abs(lon) > 180 || lat.isNaN() || Math.abs(lat) > 90) return false
        }
        return true
    }

    /** One WFIGS feature -> console row; null skips it (a single bad incident must not blank the layer). */
    private fun normalizeFire(feature: JSONObject, ids: MutableSet<String>): JSONObject? {
        val p = feature.optJSONObject("properties") ?: return null
        val geometry = feature.optJSONObject("geometry") ?: return null
        val polygonsIn = when (geometry.optString("type")) {
            "Polygon" -> JSONArray().put(geometry.optJSONArray("coordinates") ?: return null)
            "MultiPolygon" -> geometry.optJSONArray("coordinates") ?: return null
            else -> return null
        }
        val polygons = JSONArray()
        for (i in 0 until polygonsIn.length()) {
            val rings = polygonsIn.optJSONArray(i) ?: return null
            if (rings.length() == 0) continue
            for (r in 0 until rings.length()) if (!validRing(rings.optJSONArray(r))) return null
            polygons.put(rings)
        }
        if (polygons.length() == 0) return null
        val stableId = p.optString("attr_UniqueFireIdentifier").ifBlank { feature.opt("id")?.toString().orEmpty() }
        if (stableId.isBlank() || !ids.add(stableId)) return null
        fun num(key: String): Any = p.opt(key)?.takeIf { it is Number && it.toDouble().isFinite() } ?: JSONObject.NULL
        fun text(key: String): Any = (p.opt(key) as? String) ?: JSONObject.NULL
        return JSONObject().apply {
            put("stableId", stableId)
            put("name", text("poly_IncidentName"))
            put("acres", num("attr_IncidentSize"))
            put("containedPct", num("attr_PercentContained"))
            put("state", text("attr_POOState"))
            put("category", text("attr_IncidentTypeCategory"))
            put("discoveredTime", num("attr_FireDiscoveryDateTime"))
            put("updatedTime", num("poly_DateCurrent"))
            put("cause", text("attr_FireCause"))
            put("behavior", text("attr_FireBehaviorGeneral"))
            put("personnel", num("attr_TotalIncidentPersonnel"))
            put("county", text("attr_POOCounty"))
            put("costToDate", num("attr_EstimatedCostToDate"))
            put("complexity", text("attr_IncidentComplexityLevel"))
            put("complexName", text("attr_CpxName"))
            put("polygons", polygons)
        }
    }

    // InciWeb's publication API was retired; the incident page carries origin and update times.
    private fun loadPublication(id: String): String? {
        val html = http.newCall(
            Request.Builder().url("https://inciweb.wildfire.gov/node/$id").header("User-Agent", "Jarvis-GodsEye/1.9").build()
        ).execute().use { r ->
            if (r.code != 200 || r.request.url.host != "inciweb.wildfire.gov") return null
            r.body?.string().orEmpty().take(2 * 1024 * 1024)
        }
        fun epochMs(pattern: String): Any {
            val iso = Regex(pattern).find(html)?.groupValues?.get(1) ?: return JSONObject.NULL
            return try {
                java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { JSONObject.NULL }
            }
        }
        val changed = epochMs("""<meta\s+property="og:updated_time"\s+content="([^"]+)"""")
        val created = epochMs("""Date of Origin</th>\s*<td[^>]*>\s*<time\s+datetime="([^"]+)"""")
        if (changed == JSONObject.NULL && created == JSONObject.NULL) return null
        return JSONObject().put("createdMs", created).put("changedMs", changed).toString()
    }

    // -- Wind (Open-Meteo, keyless) ------------------------------------------
    // The console's wind layer wants a global u/v grid (Float32, u then v, row 0 = north).
    // Upstream decodes NOAA GFS GRIB on its server; the phone has no GRIB decoder, so the
    // same grid is sampled from Open-Meteo's current 10 m wind at a coarse 6 degree spacing
    // (1860 points, kept 3 h on disk) to stay well inside the free daily call budget.

    private const val WIND_STEP = 6.0
    private const val WIND_NX = 60   // 0..354 E
    private const val WIND_NY = 31   // 90..-90
    private const val WIND_FRESH_MS = 3 * 60 * 60 * 1000L

    private fun windFile(context: Context) = File(context.cacheDir, "godseye-wind.bin")

    /** u then v, little-endian Float32, or null when Open-Meteo cannot be reached. */
    @Synchronized
    private fun windGridBytes(context: Context): ByteArray? {
        val file = windFile(context)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < WIND_FRESH_MS &&
            file.length() == (WIND_NX * WIND_NY * 8).toLong()) {
            return file.readBytes()
        }
        val count = WIND_NX * WIND_NY
        val u = FloatArray(count)
        val v = FloatArray(count)
        val points = (0 until count).map { i ->
            val row = i / WIND_NX
            val col = i % WIND_NX
            (90.0 - row * WIND_STEP) to (col * WIND_STEP).let { if (it > 180) it - 360 else it }
        }
        try {
            points.chunked(310).forEachIndexed { chunkIndex, chunk ->
                val lats = chunk.joinToString(",") { "%.1f".format(java.util.Locale.US, it.first) }
                val lons = chunk.joinToString(",") { "%.1f".format(java.util.Locale.US, it.second) }
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                    "&current=wind_speed_10m,wind_direction_10m&wind_speed_unit=ms"
                val rows = http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-GodsEye/1.9").build())
                    .execute().use { r -> if (r.isSuccessful) JSONArray(r.body?.string().orEmpty()) else return file.takeIf { it.exists() }?.readBytes() }
                for (j in 0 until rows.length()) {
                    val current = rows.optJSONObject(j)?.optJSONObject("current") ?: continue
                    val speed = current.optDouble("wind_speed_10m", 0.0)
                    val fromDeg = Math.toRadians(current.optDouble("wind_direction_10m", 0.0))
                    val i = chunkIndex * 310 + j
                    // Meteorological "from" direction -> vector the air moves along.
                    u[i] = (-speed * Math.sin(fromDeg)).toFloat()
                    v[i] = (-speed * Math.cos(fromDeg)).toFloat()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo wind grid failed: ${e.message}")
            return file.takeIf { it.exists() }?.readBytes()
        }
        val buffer = java.nio.ByteBuffer.allocate(count * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        u.forEach { buffer.putFloat(if (it.isFinite()) it else 0f) }
        v.forEach { buffer.putFloat(if (it.isFinite()) it else 0f) }
        return buffer.array().also { file.writeBytes(it) }
    }

    /** `GET /api/wind/manifest?model=gfs[&overlay=...]` in the upstream manifest shape. */
    fun windManifest(context: Context, model: String, overlay: String): Reply {
        val query = "model=$model" + if (overlay == "none") "" else "&overlay=$overlay"
        val bytes = windGridBytes(context)
        val base = JSONObject().put("schemaVersion", 1).put("model", model).put("fetchedAt", System.currentTimeMillis())
        if (overlay != "none") base.put("overlay", overlay)
        if (bytes == null) {
            return json(200, base.put("unavailable", true).put("stale", false)
                .put("reason", "Wind data source (Open-Meteo) unreachable"))
        }
        val stamp = windFile(context).lastModified()
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc).apply { timeInMillis = stamp }
        val date = "%04d%02d%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        val hour = "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY))
        if (overlay == "temperature") base.put("scalarError", "Temperature field unavailable")
        if (overlay == "pressure") base.put("scalarError", "Mean sea level pressure field unavailable")
        return json(200, base.apply {
            put("cycle", JSONObject().put("date", date).put("hour", hour).put("forecastHour", 0))
            put("level", "10 m above ground")
            put("units", "m/s")
            put("source", "Open-Meteo (current 10 m wind)")
            put("grid", JSONObject().put("nx", WIND_NX).put("ny", WIND_NY).put("lo1", 0.0).put("la1", 90.0)
                .put("dx", WIND_STEP).put("dy", WIND_STEP))
            put("stale", System.currentTimeMillis() - stamp > WIND_FRESH_MS)
            put("unavailable", false)
            put("reason", JSONObject.NULL)
            put("gridUrl", "/api/wind/grid/$model-$date-$hour-f0-om6.bin?$query")
        })
    }

    fun windGrid(context: Context): ByteArray? = windGridBytes(context)
}
