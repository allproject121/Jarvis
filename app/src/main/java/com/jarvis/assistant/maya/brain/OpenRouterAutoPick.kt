package com.jarvis.assistant.maya.brain

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Maya "AutoPick": picks free, tool-capable OpenRouter models and rotates through them when
 * one is rate-limited (429) or broken (5xx). The `/models` listing is cached for 6 hours in
 * `maya_brain_prefs`.
 */
object OpenRouterAutoPick {

    private const val TAG = "OpenRouterAutoPick"
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val CACHE_KEY = "openrouter_models_cache"
    private const val CACHE_AT_KEY = "openrouter_models_cache_at"
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val PREFS = BrainSettings.PREFS_NAME

    /** Maya's user-facing explanation about OpenRouter free-model limits. */
    const val FREE_MODELS_EXPLANATION =
        "Free models cost nothing, but OpenRouter limits them: 20 requests a minute, and 50 a day until the account has bought 10 credits (\$10) - then 1000 a day. Failed requests count too. One thing you ask her can take several requests, because every tool step is one, so 50 a day is a short conversation. A free model can also answer worse than a paid one."

    const val NO_FREE_MODEL_WORKING =
        "Abhi koi free model kaam nahi kar raha. Thodi der me phir try karo, ya Settings me Voice engine se apna model chuno."

    const val NEGATIVE_BALANCE =
        "OpenRouter refused free models for this account - its balance is below zero. Add a little credit."

    /** Preferred order when several free tool-capable models are available. */
    private val PREFERRED_PREFIXES = listOf(
        "google/gemini", "meta-llama/llama-4", "meta-llama/llama-3.3", "qwen/qwen3", "deepseek/deepseek-chat",
        "mistralai/mistral-small", "nvidia/", "openai/gpt-oss", "z-ai/glm", "moonshotai/kimi"
    )

    data class FreeModel(val id: String, val contextLength: Int, val supportsTools: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cursor = 0
    private val cooldownUntil = HashMap<String, Long>()

    /**
     * Pure filter used by unit tests: returns the free (":free" id suffix or zero prompt &
     * completion pricing) models that list "tools" in `supported_parameters`, best first.
     */
    fun filterFreeToolModels(modelsJson: JSONObject): List<FreeModel> {
        val data = modelsJson.optJSONArray("data") ?: JSONArray()
        val out = mutableListOf<FreeModel>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "").trim()
            if (id.isBlank()) continue
            val pricing = m.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
            val completionPrice = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
            val free = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)
            if (!free) continue
            val params = m.optJSONArray("supported_parameters") ?: JSONArray()
            var tools = false
            for (j in 0 until params.length()) if (params.optString(j) == "tools") { tools = true; break }
            if (!tools) continue
            out += FreeModel(id, m.optInt("context_length", 0), true)
        }
        return out.sortedWith(compareBy<FreeModel> { rank(it.id) }.thenByDescending { it.contextLength })
    }

    private fun rank(id: String): Int {
        val idx = PREFERRED_PREFIXES.indexOfFirst { id.startsWith(it) }
        return if (idx < 0) PREFERRED_PREFIXES.size else idx
    }

    /** Fetches (or reads the cache of) the OpenRouter model list and filters it. */
    fun freeToolModels(context: Context, apiKey: String, forceRefresh: Boolean = false): List<FreeModel> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedAt = prefs.getLong(CACHE_AT_KEY, 0L)
        val cached = prefs.getString(CACHE_KEY, null)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            runCatching { return filterFreeToolModels(JSONObject(cached)) }
        }
        val body = fetchModels(apiKey)
        if (body != null) {
            prefs.edit().putString(CACHE_KEY, body).putLong(CACHE_AT_KEY, System.currentTimeMillis()).apply()
            return runCatching { filterFreeToolModels(JSONObject(body)) }.getOrDefault(emptyList())
        }
        if (cached != null) return runCatching { filterFreeToolModels(JSONObject(cached)) }.getOrDefault(emptyList())
        return emptyList()
    }

    private fun fetchModels(apiKey: String): String? = try {
        val b = Request.Builder().url(MODELS_URL)
        if (apiKey.isNotBlank()) b.addHeader("Authorization", "Bearer $apiKey")
        BrainProviders.OPENROUTER_HEADERS.forEach { (k, v) -> b.addHeader(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "models fetch failed: HTTP ${r.code}")
                null
            } else r.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "models fetch error: ${e.message}")
        null
    }

    /** The model to try next (skips models cooling down after a 429/5xx). */
    @Synchronized
    fun pick(context: Context, apiKey: String): String? {
        val models = freeToolModels(context, apiKey)
        if (models.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (attempt in models.indices) {
            val m = models[(cursor + attempt) % models.size]
            val until = cooldownUntil[m.id] ?: 0L
            if (until <= now) {
                cursor = (cursor + attempt) % models.size
                return m.id
            }
        }
        // Everything is cooling down: take the one that frees up soonest.
        return models.minByOrNull { cooldownUntil[it.id] ?: 0L }?.id
    }

    /** Marks [modelId] as failed (429 → 60 s, 5xx/other → 5 min) and advances the cursor. */
    @Synchronized
    fun reportFailure(modelId: String, httpCode: Int) {
        val cool = if (httpCode == 429) 60_000L else 5 * 60_000L
        cooldownUntil[modelId] = System.currentTimeMillis() + cool
        cursor++
        Log.i(TAG, "AutoPick(model=$modelId) failed with $httpCode; rotating")
    }

    @Synchronized
    fun reportSuccess(modelId: String) {
        cooldownUntil.remove(modelId)
    }

    /** True when an error body says the account balance is negative (Maya special-cases it). */
    fun isNegativeBalance(body: String): Boolean =
        body.contains("negative", ignoreCase = true) && body.contains("balance", ignoreCase = true) ||
            body.contains("insufficient credits", ignoreCase = true)
}
