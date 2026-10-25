package com.jarvis.assistant.connectors

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GitHubConnector(private val context: Context) {
    companion object {
        private const val TAG = "GitHubConnector"
        private const val BASE_URL = "https://api.github.com"
        private const val PREFS_NAME = "jarvis_github_prefs"
        private const val KEY_TOKEN = "github_personal_token"
        private const val KEY_USERNAME = "github_username"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "tiwariroshan121") ?: "tiwariroshan121"
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    private fun newRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Jarvis-Assistant-Android")
        token?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder
    }

    suspend fun listMyRepositories(): List<String> = withContext(Dispatchers.IO) {
        val user = username.ifBlank { "tiwariroshan121" }
        val url = if (token.isNullOrBlank()) "$BASE_URL/users/$user/repos?sort=updated" else "$BASE_URL/user/repos?sort=updated"
        val request = newRequestBuilder(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(body)
                val list = mutableListOf<String>()
                for (i in 0 until minOf(array.length(), 15)) {
                    val repo = array.getJSONObject(i)
                    list.add("${repo.getString("name")} (${repo.optString("visibility", "public")})")
                }
                return@withContext list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list repos: ${e.message}")
            emptyList()
        }
    }

    suspend fun getRepoIssues(owner: String, repo: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/repos/$owner/$repo/issues?state=open"
        val request = newRequestBuilder(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(body)
                val list = mutableListOf<String>()
                for (i in 0 until minOf(array.length(), 10)) {
                    val item = array.getJSONObject(i)
                    list.add("#${item.getInt("number")}: ${item.getString("title")}")
                }
                return@withContext list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch issues: ${e.message}")
            emptyList()
        }
    }

    suspend fun createIssue(owner: String, repo: String, title: String, bodyText: String): String = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/repos/$owner/$repo/issues"
        val jsonPayload = JSONObject().apply {
            put("title", title)
            put("body", bodyText)
        }
        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
        val request = newRequestBuilder(url).post(requestBody).build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val resJson = JSONObject(response.body?.string() ?: "{}")
                    "Issue created successfully: #${resJson.optInt("number")}"
                } else {
                    "Failed to create issue (Code ${response.code})"
                }
            }
        } catch (e: Exception) {
            "Error creating issue: ${e.message}"
        }
    }
}
