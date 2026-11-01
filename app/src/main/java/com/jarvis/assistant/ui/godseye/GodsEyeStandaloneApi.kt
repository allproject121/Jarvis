package com.jarvis.assistant.ui.godseye

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.preferences.AppPreferences
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standalone replacements for the three God's Eye dev-server endpoints that the
 * APK had no answer for: the credential-status panel, OpenSky authentication,
 * and the OpenAI Realtime ephemeral token.
 *
 * Everything here reports the *real* state of the user's keys. The console
 * renders its availability chips straight off these payloads, so a hardcoded
 * "configured" is worse than a missing key: the layer reads green and stays
 * empty.
 */
object GodsEyeStandaloneApi {

    private const val TAG = "GodsEyeStandaloneApi"

    // -- Provider Settings panel ------------------------------------------

    /**
     * One entry of the upstream key registry (src/keySetupCore.mjs). `set` is
     * resolved against the user's actual Jarvis preferences.
     */
    private data class KeyEntry(
        val id: String,
        val title: String,
        val unlocks: String,
        val getUrl: String,
        val envVars: List<String>,
        val tier: String,
        val clientExposed: Boolean,
        val isSet: Boolean
    )

    /**
     * The payload `/api/setup/status` must return: `{keys[], setCount, total}`,
     * matching keySetupStatus() upstream. Never includes a key value.
     */
    fun setupStatusJson(prefs: AppPreferences): String {
        val entries = listOf(
            KeyEntry(
                "google-maps", "GOOGLE MAPS",
                "The photorealistic 3D planet + place search",
                "https://developers.google.com/maps/documentation/tile/get-api-key",
                listOf("GOOGLE_MAPS_API_KEY"), "metered", true,
                prefs.godsEyeGoogleApiKey.isNotBlank()
            ),
            KeyEntry(
                "openai", "OPENAI",
                "Voice control and the HUD briefing summaries",
                "https://platform.openai.com/api-keys",
                listOf("OPENAI_API_KEY"), "metered", false,
                prefs.openAiApiKey.isNotBlank()
            ),
            KeyEntry(
                "aisstream", "AISSTREAM",
                "Live ships, worldwide",
                "https://aisstream.io",
                listOf("AISSTREAM_API_KEY"), "free", false,
                prefs.godsEyeAisStreamKey.isNotBlank()
            ),
            KeyEntry(
                "firms", "NASA FIRMS",
                "Live active-fire detections",
                "https://firms.modaps.eosdis.nasa.gov/api/map_key/",
                listOf("FIRMS_MAP_KEY"), "free", false,
                prefs.godsEyeNasaFirmsKey.isNotBlank()
            ),
            KeyEntry(
                "tomtom", "TOMTOM",
                "Real live traffic flow tiles",
                "https://my.tomtom.com/keys",
                listOf("TOMTOM_API_KEY"), "free", false,
                prefs.godsEyeTomTomKey.isNotBlank()
            ),
            KeyEntry(
                "cesium-ion", "CESIUM ION",
                "Bing imagery map stacks + world terrain",
                "https://ion.cesium.com/tokens",
                listOf("CESIUM_ION_TOKEN"), "free", true,
                prefs.godsEyeCesiumIonToken.isNotBlank()
            ),
            KeyEntry(
                "opensky", "OPENSKY",
                "Worldwide flight tracking (OAuth2 client credentials)",
                "https://opensky-network.org",
                listOf("OPENSKY_CLIENT_ID", "OPENSKY_CLIENT_SECRET"), "free", false,
                prefs.godsEyeOpenSkyClientId.isNotBlank() && prefs.godsEyeOpenSkyClientSecret.isNotBlank()
            ),
            KeyEntry(
                "mapillary", "MAPILLARY",
                "Street-level imagery and coverage in the Street Level layer",
                "https://www.mapillary.com/dashboard/developers",
                listOf("MAPILLARY_CLIENT_TOKEN"), "free", true,
                prefs.godsEyeMapillaryToken.isNotBlank()
            )
        )

        val keys = JSONArray()
        entries.forEach { entry ->
            keys.put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("unlocks", entry.unlocks)
                put("getUrl", entry.getUrl)
                put("envVars", JSONArray(entry.envVars))
                put("tier", entry.tier)
                put("clientExposed", entry.clientExposed)
                put("set", entry.isSet)
                // 'external' = supplied outside this panel's own store. On
                // Android every key lives in Jarvis settings, so the panel must
                // never offer to rewrite one from inside the WebView.
                put("managed", if (entry.isSet) "external" else JSONObject.NULL)
            })
        }

        return JSONObject().apply {
            put("keys", keys)
            put("setCount", entries.count { it.isSet })
            put("total", entries.size)
            put("store", "jarvis-settings")
            put("readOnly", true)
            put("hint", "Keys are managed in Jarvis > Settings > API Connections")
        }.toString()
    }

    // -- OpenSky OAuth2 ----------------------------------------------------

    private const val OPENSKY_TOKEN_URL =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token"

    @Volatile private var openSkyToken: String? = null
    @Volatile private var openSkyTokenExpiresAt = 0L
    @Volatile private var openSkyTokenFingerprint: String? = null

    /**
     * An OpenSky bearer token, minted via the client-credentials flow and
     * cached until shortly before it expires.
     *
     * OpenSky retired HTTP Basic username/password auth in March 2026; the
     * client id/secret pair only works through this token endpoint. Returns
     * null when unconfigured, which is fine - anonymous OpenSky calls still
     * work, just with a much lower rate limit.
     */
    fun openSkyBearer(prefs: AppPreferences, client: OkHttpClient): String? {
        val clientId = prefs.godsEyeOpenSkyClientId.trim()
        val clientSecret = prefs.godsEyeOpenSkyClientSecret.trim()
        if (clientId.isBlank() || clientSecret.isBlank()) return null

        val fingerprint = "$clientId:${clientSecret.hashCode()}"
        val now = System.currentTimeMillis()
        if (openSkyToken != null && fingerprint == openSkyTokenFingerprint && now < openSkyTokenExpiresAt) {
            return openSkyToken
        }

        return try {
            val form = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()
            val request = Request.Builder()
                .url(OPENSKY_TOKEN_URL)
                .post(form)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "OpenSky token request failed: HTTP ${response.code}")
                openSkyToken = null
                return null
            }
            val parsed = JSONObject(body)
            val token = parsed.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            // Renew a minute early so an in-flight request never races expiry.
            val lifetimeSec = parsed.optLong("expires_in", 1800L)
            openSkyToken = token
            openSkyTokenExpiresAt = now + (lifetimeSec - 60).coerceAtLeast(30) * 1000
            openSkyTokenFingerprint = fingerprint
            token
        } catch (e: Exception) {
            Log.w(TAG, "OpenSky token request error: ${e.message}")
            null
        }
    }

    // -- OpenAI Realtime ---------------------------------------------------

    private const val REALTIME_CLIENT_SECRETS = "https://api.openai.com/v1/realtime/client_secrets"
    private const val REALTIME_MODEL = "gpt-realtime-2"
    private const val REALTIME_MODEL_MINI = "gpt-realtime-2.1-mini"
    private const val REALTIME_VOICE = "cedar"
    private const val REALTIME_TRANSCRIBE_MODEL = "gpt-4o-mini-transcribe"
    private const val SESSION_ASSET = "godseye/jarvis-realtime-session.json"

    @Volatile private var cachedSessionAsset: JSONObject? = null

    private fun sessionAsset(context: Context): JSONObject? {
        cachedSessionAsset?.let { return it }
        return try {
            val text = context.assets.open(SESSION_ASSET).bufferedReader().use { it.readText() }
            JSONObject(text).also { cachedSessionAsset = it }
        } catch (e: Exception) {
            Log.w(TAG, "Realtime session asset unavailable: ${e.message}")
            null
        }
    }

    data class ProxyResult(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    /**
     * Mint an ephemeral Realtime client secret from the user's OpenAI key, with
     * the God's Eye globe-control tools attached, and pass the upstream body
     * through verbatim - the page parses it as-is.
     */
    fun realtimeToken(context: Context, prefs: AppPreferences, client: OkHttpClient, tier: String?): ProxyResult {
        val apiKey = prefs.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            return ProxyResult(
                503,
                JSONObject().put(
                    "error",
                    "OPENAI_API_KEY is not set - add it in Jarvis > Settings > API Connections"
                ).toString()
            )
        }

        val asset = sessionAsset(context)
        val resolvedTier = if (tier?.lowercase() == "mini") "mini" else "standard"
        val model = if (resolvedTier == "mini") REALTIME_MODEL_MINI else REALTIME_MODEL

        val session = JSONObject().apply {
            put("type", "realtime")
            put("model", model)
            put("audio", JSONObject().apply {
                put("input", JSONObject().apply {
                    put("noise_reduction", JSONObject().put("type", "near_field"))
                    put("transcription", JSONObject().put("model", REALTIME_TRANSCRIBE_MODEL))
                    put("turn_detection", JSONObject().apply {
                        put("type", "semantic_vad")
                        put("eagerness", "low")
                        put("create_response", true)
                        put("interrupt_response", false)
                    })
                })
                put("output", JSONObject().put("voice", REALTIME_VOICE))
            })
            asset?.optString("instructions")?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
            asset?.optJSONArray("tools")?.let {
                put("tools", it)
                put("tool_choice", "auto")
            }
        }
        val payload = JSONObject().put("session", session).toString()

        return try {
            val request = Request.Builder()
                .url(REALTIME_CLIENT_SECRETS)
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("OpenAI-Safety-Identifier", "jarvis-gods-eye")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            ProxyResult(
                response.code,
                body.ifBlank { JSONObject().put("error", "Empty response from OpenAI").toString() },
                mapOf(
                    "X-GEV-Voice-Tier" to resolvedTier,
                    "X-GEV-Voice-Model" to model,
                    "X-GEV-Voice-Auth" to "env"
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Realtime token request failed: ${e.message}")
            ProxyResult(
                502,
                JSONObject().put("error", e.message ?: "Realtime token request failed").toString()
            )
        }
    }

    /**
     * ChatGPT OAuth sign-in is a desktop-only flow (it reads the local Codex
     * credential file), so the console must be told plainly that it is not an
     * option here rather than being left to time out.
     */
    fun realtimeOAuthStatusJson(): String = JSONObject().apply {
        put("available", false)
        put("signedIn", false)
        put("state", "unavailable")
        put("code", "CODEX_OAUTH_UNAVAILABLE")
        put("error", "ChatGPT OAuth sign-in is desktop-only. Use an OpenAI API key in Jarvis settings.")
    }.toString()
}
