package com.jarvis.assistant.ai

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Picks Gemini models that can answer plain-text `generateContent` calls for a given API key.
 *
 * Why: the model saved in Settings is usually a Live model (e.g. `gemini-3.1-flash-live-preview`),
 * which Google rejects on the text endpoint (HTTP 400), and the old hard-coded fallbacks
 * (`gemini-2.0-flash`, `gemini-1.5-flash`) have been retired (HTTP 404). So text chat silently
 * failed over to the offline brain. Here we ask Google which models this key can use
 * (ListModels), keep the text-capable ones and rank them (newest Flash first).
 *
 * Blocking network call: use from a background thread / Dispatchers.IO.
 */
object GeminiTextModels {
    private const val TAG = "GeminiTextModels"
    private const val CACHE_MS = 6 * 60 * 60 * 1000L
    private const val FAILED_CACHE_MS = 5 * 60 * 1000L

    /** Last-resort names, tried only after the key's own model list. */
    private val STATIC_FALLBACKS = listOf("gemini-flash-latest", "gemini-2.5-flash")

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class Entry(val at: Long, val ttl: Long, val models: List<String>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val lastWorking = ConcurrentHashMap<String, String>()

    /** False for Live / audio / TTS / image / embedding models that the text endpoint refuses. */
    fun isTextModelName(name: String): Boolean {
        val m = name.lowercase().removePrefix("models/")
        if (!m.startsWith("gemini")) return false
        val blocked = listOf(
            "live", "native-audio", "tts", "image", "embedding", "aqa", "veo", "imagen",
            "robotics", "computer-use", "audio", "learnlm", "nano-banana"
        )
        return blocked.none { m.contains(it) }
    }

    /**
     * Models to try in order: the saved model (only if it is text-capable), the model that worked
     * last time, then the key's own models ranked best-first, then a couple of static aliases.
     */
    fun candidates(apiKey: String, preferred: String? = null, max: Int = 4): List<String> {
        val key = apiKey.trim()
        val out = LinkedHashSet<String>()
        preferred?.trim()?.removePrefix("models/")?.takeIf { it.isNotBlank() && isTextModelName(it) }?.let(out::add)
        lastWorking[key]?.let(out::add)
        if (key.isNotBlank()) out += discovered(key)
        out += STATIC_FALLBACKS
        return out.take(max)
    }

    /** Remember which model answered so the next call tries it first. */
    fun markWorking(apiKey: String, model: String) {
        if (apiKey.isNotBlank() && model.isNotBlank()) lastWorking[apiKey.trim()] = model.removePrefix("models/")
    }

    /** Text-capable models this key can call, best first (cached for 6 h). */
    fun discovered(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        cache[apiKey]?.let { if (now - it.at < it.ttl) return it.models }
        val models = try {
            fetch(apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "ListModels failed: ${e.message}")
            null
        }
        val ranked = models.orEmpty().filter(::isTextModelName).sortedByDescending(::score)
        cache[apiKey] = Entry(now, if (models == null) FAILED_CACHE_MS else CACHE_MS, ranked)
        if (ranked.isNotEmpty()) Log.i(TAG, "Text models for key: ${ranked.take(5)}")
        return ranked
    }

    private fun fetch(apiKey: String): List<String>? {
        val names = mutableListOf<String>()
        var pageToken: String? = null
        repeat(3) {
            val url = buildString {
                append("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=").append(apiKey)
                pageToken?.let { append("&pageToken=").append(it) }
            }
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return if (names.isEmpty()) null else names
                val root = JSONObject(resp.body?.string().orEmpty())
                val arr = root.optJSONArray("models")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        var canGenerate = false
                        if (methods != null) for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") canGenerate = true
                        }
                        if (canGenerate) names += m.optString("name").removePrefix("models/")
                    }
                }
                pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
            if (pageToken == null) return names
        }
        return names
    }

    /** Newer version first; Flash > Pro > Flash-Lite; stable > preview > experimental. */
    private fun score(name: String): Double {
        val m = name.lowercase()
        val version = Regex("""gemini-(\d+(?:\.\d+)?)""").find(m)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val tier = when {
            m.contains("flash-lite") -> 1.0
            m.contains("flash") -> 3.0
            m.contains("pro") -> 2.0
            else -> 0.5
        }
        val stability = when {
            m.contains("exp") -> 0.0
            m.contains("preview") -> 0.5
            else -> 1.0
        }
        val alias = if (m.endsWith("-latest")) -0.2 else 0.0
        return version * 10 + tier + stability * 0.4 + alias
    }
}
