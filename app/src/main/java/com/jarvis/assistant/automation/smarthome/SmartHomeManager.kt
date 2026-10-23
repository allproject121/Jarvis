package com.jarvis.assistant.automation.smarthome

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * JARVIS Smart Home & IoT Automation Engine.
 * Supports:
 *  - Native Home Assistant REST API (lights, switches, scenes, climate)
 *  - Dynamic Entity Resolution (maps friendly names like "Living Room Lights" to entity_id)
 *  - HTTP Webhooks (IFTTT, n8n, Node-RED, local webhooks)
 *  - State inspection & diagnostics
 */
class SmartHomeManager(
    private val context: Context,
    private val preferences: AppPreferences = (context.applicationContext as? JarvisApp)?.preferences ?: AppPreferences(context)
) {

    companion object {
        private const val TAG = "SmartHomeManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CACHE_EXPIRY_MS = 60_000L
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class HaEntity(
        val entityId: String,
        val domain: String,
        val state: String,
        val friendlyName: String
    )

    private var cachedEntities: List<HaEntity> = emptyList()
    private var lastEntityFetchTime: Long = 0L

    private fun normalizeUrl(rawUrl: String): String {
        var clean = rawUrl.trim().trimEnd('/')
        if (clean.isNotEmpty() && !clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return clean
    }

    /**
     * Tests connectivity with the configured Home Assistant instance or Webhook URL.
     * Returns Pair(isSuccess, message).
     */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val haUrl = normalizeUrl(preferences.homeAssistantUrl)
        val token = preferences.homeAssistantToken.trim()
        val webhookUrl = normalizeUrl(preferences.smartHomeWebhookUrl)

        if (haUrl.isNotBlank()) {
            if (token.isBlank()) {
                return@withContext Pair(false, "Home Assistant URL is configured, but Long-Lived Access Token is missing.")
            }
            try {
                val request = Request.Builder()
                    .url("$haUrl/api/")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    return@withContext when (response.code) {
                        200 -> Pair(true, "Connected to Home Assistant! API is running.")
                        401 -> Pair(false, "Authentication Failed (HTTP 401). Check your Long-Lived Access Token.")
                        403 -> Pair(false, "Forbidden (HTTP 403). Token does not have sufficient permissions.")
                        404 -> Pair(false, "HTTP 404: '/api/' endpoint not found. Verify the Home Assistant URL and port (default :8123).")
                        else -> Pair(false, "Home Assistant returned HTTP ${response.code}: ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Home Assistant connection test failed: ${e.message}", e)
                return@withContext Pair(false, "Connection error: ${e.localizedMessage ?: e.message}")
            }
        } else if (webhookUrl.isNotBlank()) {
            return@withContext Pair(true, "Webhook URL configured: $webhookUrl (Ready to receive event triggers)")
        } else {
            return@withContext Pair(false, "Neither Home Assistant URL nor Webhook URL is configured. Please enter your setup details.")
        }
    }

    /**
     * Fetches current states of smart home devices from Home Assistant.
     */
    suspend fun getDeviceStates(): String = withContext(Dispatchers.IO) {
        val haUrl = normalizeUrl(preferences.homeAssistantUrl)
        val token = preferences.homeAssistantToken.trim()

        if (haUrl.isBlank() || token.isBlank()) {
            return@withContext "Home Assistant is not configured. Please set the URL and Token in Settings."
        }

        try {
            refreshEntitiesCache(force = true)
            if (cachedEntities.isEmpty()) {
                return@withContext "No devices found on Home Assistant instance."
            }

            val summaryList = cachedEntities
                .filter { it.domain in setOf("light", "switch", "climate", "lock", "cover") }
                .take(15)
                .map { "${it.friendlyName} (${it.entityId}): ${it.state}" }

            "Smart Home Devices (${cachedEntities.size} total):\n" + summaryList.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device states: ${e.message}", e)
            "Failed to fetch smart home states: ${e.message}"
        }
    }

    /**
     * Controls a device via Home Assistant REST API or Webhook fallback.
     */
    suspend fun controlDevice(
        target: String,
        action: String,
        delaySec: Int = 0,
        intervalSec: Int = 0
    ): String = withContext(Dispatchers.IO) {
        if (delaySec > 0) {
            delay(delaySec * 1000L)
        }

        val cleanTarget = target.trim()
        val cleanAction = action.lowercase().trim()
        val haUrl = normalizeUrl(preferences.homeAssistantUrl)
        val token = preferences.homeAssistantToken.trim()
        val webhookUrl = normalizeUrl(preferences.smartHomeWebhookUrl)

        // 1. Try Home Assistant if configured
        if (haUrl.isNotBlank() && token.isNotBlank()) {
            return@withContext executeHomeAssistantCommand(haUrl, token, cleanTarget, cleanAction, intervalSec)
        }

        // 2. Try Webhook fallback if configured
        if (webhookUrl.isNotBlank()) {
            return@withContext executeWebhookCommand(webhookUrl, cleanTarget, cleanAction, intervalSec)
        }

        // 3. Neither configured: Return helpful guidance
        "Smart Home setup required: Home Assistant URL or Webhook is not configured. Please set your Home Assistant URL and Token in Jarvis Settings or Matrix."
    }

    private suspend fun executeHomeAssistantCommand(
        haUrl: String,
        token: String,
        target: String,
        action: String,
        intervalSec: Int
    ): String {
        try {
            refreshEntitiesCache(force = false)

            val isAll = target.contains("all", ignoreCase = true) || target.isBlank()
            val resolvedEntity = if (isAll) null else resolveEntityId(target)
            val domain = when {
                resolvedEntity != null -> resolvedEntity.substringBefore('.')
                target.contains("switch", ignoreCase = true) || target.contains("plug", ignoreCase = true) || target.contains("socket", ignoreCase = true) -> "switch"
                target.contains("climate", ignoreCase = true) || target.contains("ac", ignoreCase = true) || target.contains("thermostat", ignoreCase = true) -> "climate"
                target.contains("scene", ignoreCase = true) -> "scene"
                else -> "light"
            }

            val service: String
            val payload = JSONObject()

            when {
                action.contains("on") -> {
                    service = "turn_on"
                }
                action.contains("off") -> {
                    service = "turn_off"
                }
                action.contains("toggle") -> {
                    service = "toggle"
                }
                action.contains("blink") || action.contains("strobe") -> {
                    service = "turn_on"
                    payload.put("flash", "short")
                }
                action.contains("chase") || action.contains("party") -> {
                    service = "turn_on"
                    payload.put("effect", "colorloop")
                }
                else -> {
                    service = action.replace(" ", "_")
                }
            }

            if (isAll) {
                payload.put("entity_id", "all")
            } else if (resolvedEntity != null) {
                payload.put("entity_id", resolvedEntity)
            } else {
                // Synthesize entity ID from target name if not found in cache
                val fallbackEntity = "$domain." + target.lowercase()
                    .replace("&", "and")
                    .replace(Regex("[^a-z0-9_]"), "_")
                    .trim('_')
                payload.put("entity_id", fallbackEntity)
            }

            val endpoint = "$haUrl/api/services/$domain/$service"
            val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val entityName = resolvedEntity ?: payload.optString("entity_id")
                    return "Protocol Executed: '$entityName' set to ${service.uppercase()} via Home Assistant."
                } else {
                    Log.w(TAG, "Home Assistant API error ($endpoint): HTTP ${response.code} - $bodyStr")
                    return "Home Assistant Error (HTTP ${response.code}): ${response.message}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute Home Assistant command for target '$target': ${e.message}", e)
            return "Smart Home Error: ${e.localizedMessage ?: e.message}"
        }
    }

    private fun executeWebhookCommand(
        webhookUrl: String,
        target: String,
        action: String,
        intervalSec: Int
    ): String {
        try {
            val payload = JSONObject().apply {
                put("target", target)
                put("action", action)
                put("interval_sec", intervalSec)
                put("source", "jarvis")
                put("timestamp", System.currentTimeMillis())
            }

            val request = Request.Builder()
                .url(webhookUrl)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                return if (response.isSuccessful) {
                    "Protocol Executed: '$action' dispatched to Webhook for '$target'."
                } else {
                    "Webhook Error (HTTP ${response.code}): ${response.message}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webhook execution failed: ${e.message}", e)
            return "Webhook Dispatch Failed: ${e.localizedMessage ?: e.message}"
        }
    }

    private fun resolveEntityId(target: String): String? {
        val clean = target.lowercase().trim()

        // Exact entity_id match
        val exactId = cachedEntities.firstOrNull { it.entityId.equals(clean, ignoreCase = true) }
        if (exactId != null) return exactId.entityId

        // Exact friendly name match
        val exactName = cachedEntities.firstOrNull { it.friendlyName.equals(clean, ignoreCase = true) }
        if (exactName != null) return exactName.entityId

        // Substring / fuzzy friendly name match
        val containsName = cachedEntities.firstOrNull {
            it.friendlyName.contains(clean, ignoreCase = true) || clean.contains(it.friendlyName, ignoreCase = true)
        }
        if (containsName != null) return containsName.entityId

        // Substring entity_id match
        val containsId = cachedEntities.firstOrNull {
            it.entityId.substringAfter('.').contains(clean) || clean.contains(it.entityId.substringAfter('.'))
        }
        return containsId?.entityId
    }

    private fun refreshEntitiesCache(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && cachedEntities.isNotEmpty() && (now - lastEntityFetchTime < CACHE_EXPIRY_MS)) {
            return
        }

        val haUrl = normalizeUrl(preferences.homeAssistantUrl)
        val token = preferences.homeAssistantToken.trim()
        if (haUrl.isBlank() || token.isBlank()) return

        try {
            val request = Request.Builder()
                .url("$haUrl/api/states")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bodyStr = response.body?.string() ?: return
                val jsonArray = JSONArray(bodyStr)
                val list = mutableListOf<HaEntity>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val entityId = obj.optString("entity_id", "")
                    if (entityId.isBlank()) continue
                    val domain = entityId.substringBefore('.', "")
                    val state = obj.optString("state", "")
                    val attrs = obj.optJSONObject("attributes")
                    val friendlyName = attrs?.optString("friendly_name", entityId) ?: entityId

                    list.add(
                        HaEntity(
                            entityId = entityId,
                            domain = domain,
                            state = state,
                            friendlyName = friendlyName
                        )
                    )
                }

                cachedEntities = list
                lastEntityFetchTime = now
                Log.d(TAG, "Refreshed Home Assistant entities cache: ${list.size} entities loaded.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh entities cache from Home Assistant: ${e.message}")
        }
    }
}
