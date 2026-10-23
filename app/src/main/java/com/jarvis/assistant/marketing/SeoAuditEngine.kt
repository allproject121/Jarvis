package com.jarvis.assistant.marketing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SeoAuditEngine {
    companion object {
        private const val TAG = "SeoAuditEngine"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches real autocomplete keywords directly from Google Suggest API.
     */
    suspend fun fetchKeywords(seedQuery: String): List<String> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(seedQuery, "UTF-8")
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&hl=en&gl=in&q=$encoded"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(body)
                val suggestions = array.getJSONArray(1)
                val results = mutableListOf<String>()
                for (i in 0 until suggestions.length()) {
                    results.add(suggestions.getString(i))
                }
                return@withContext results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch keyword suggestions: ${e.message}")
            emptyList()
        }
    }

    /**
     * Runs Google PageSpeed audit on a web URL.
     */
    suspend fun runPageSpeedAudit(targetUrl: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        val url = "https://www.googleapis.com/pagespeedonline/v5/runPagespeed?strategy=mobile&category=performance&url=$encoded"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "PageSpeed audit failed (Code ${response.code})"
                val body = response.body?.string() ?: return@withContext "Empty response"
                val json = JSONObject(body)
                val lighthouse = json.getJSONObject("lighthouseResult")
                val categories = lighthouse.getJSONObject("categories")
                val performance = categories.getJSONObject("performance")
                val score = (performance.getDouble("score") * 100).toInt()

                return@withContext "Mobile Performance Score for $targetUrl: $score/100"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running PageSpeed: ${e.message}")
            "Error running PageSpeed audit: ${e.message}"
        }
    }
}
