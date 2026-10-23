package com.jarvis.assistant.automation.skills.companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight client communicating with the Jarvis Laptop Companion Service.
 * Allows Jarvis (via ADB reverse, local Wi-Fi, or emulator loopback) to delegate
 * heavy Git clones, AST inspection, proposal generation, and Gradle validation to the PC.
 */
class CompanionApiClient(private val context: Context) {

    companion object {
        private const val TAG = "CompanionApiClient"
        private const val PREFS_NAME = "jarvis_companion_prefs"
        private const val KEY_BASE_URL = "companion_base_url"
        private const val KEY_TOKEN = "companion_auth_token"

        // Default loopback URL. On Android Emulator, 10.0.2.2 maps to PC 127.0.0.1.
        // Over ADB reverse, 127.0.0.1:8765 maps directly to PC 127.0.0.1:8765.
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8765"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var authToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /**
     * Checks if the companion service is online and healthy.
     * Automatically attempts pairing if the auth token is missing.
     */
    suspend fun isCompanionAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val isHealthy = code in 200..299
            if (isHealthy && authToken.isBlank()) {
                ensurePaired()
            }
            isHealthy
        } catch (e: Exception) {
            Log.d(TAG, "Companion health check failed: ${e.message}")
            false
        }
    }

    /**
     * Obtains or refreshes the pairing auth token from the loopback Companion service.
     * Never logs the token itself.
     */
    suspend fun ensurePaired(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/pair")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (conn.responseCode in 200..299) {
                val inputStream = conn.inputStream ?: return@withContext false
                val responseText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                val json = JSONObject(responseText)
                val token = json.optString("token", "")
                if (token.isNotBlank()) {
                    authToken = token
                    Log.i(TAG, "Companion pairing successful on $baseUrl")
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Companion pairing request failed: ${e.message}")
            false
        }
    }

    /**
     * Delegates repository analysis (folder, ZIP, or GitHub URL) to the PC companion.
     */
    suspend fun analyzeRepository(repoPathOrUrl: String, maxDepth: Int = 4): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("repo_path", repoPathOrUrl)
            put("url", repoPathOrUrl)
            put("path", repoPathOrUrl)
            put("max_depth", maxDepth)
        }
        postJson("/api/analyze", payload, timeoutMs = 60_000)
    }

    /**
     * Inspects cross-codebase mapping and compatibility for a feature.
     */
    suspend fun inspectIntegration(analysis: JSONObject, featureName: String): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("analysis", analysis)
            put("feature_name", featureName)
        }
        postJson("/api/inspect-integration", payload, timeoutMs = 15_000)
    }

    /**
     * Generates a validated integration proposal and approval token.
     */
    suspend fun generateProposal(
        featureName: String,
        description: String,
        targetFile: String? = null,
        code: String? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("feature_name", featureName)
            put("description", description)
            if (targetFile != null) put("target_file_path", targetFile)
            if (code != null) put("proposed_code", code)
        }
        postJson("/api/generate-proposal", payload, timeoutMs = 15_000)
    }

    /**
     * Retrieves the diff and metadata for a pending integration proposal from the companion.
     */
    suspend fun getProposal(proposalId: String, featureName: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("proposal_id", proposalId)
            if (!featureName.isNullOrBlank()) {
                put("feature_name", featureName)
            }
        }
        postJson("/api/review-proposal", payload, timeoutMs = 15_000)
    }

    /**
     * Applies a proposal after verifying the user's explicit approval token.
     */
    suspend fun applyProposal(proposalId: String, approvalToken: String): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("proposal_id", proposalId)
            put("approval_token", approvalToken)
        }
        postJson("/api/apply-proposal", payload, timeoutMs = 20_000)
    }

    /**
     * Lists all pending integration proposals from the companion service.
     */
    suspend fun listProposals(): JSONObject? = withContext(Dispatchers.IO) {
        getJson("/api/proposals", timeoutMs = 10_000)
    }

    /**
     * Rolls back a previously applied checkpoint.
     */
    suspend fun rollback(checkpointId: String): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("checkpoint_id", checkpointId)
        }
        postJson("/api/rollback", payload, timeoutMs = 15_000)
    }

    /**
     * Triggers clean test validation on the host machine.
     */
    suspend fun runValidation(): JSONObject? = withContext(Dispatchers.IO) {
        postJson("/api/run-validation", JSONObject(), timeoutMs = 180_000)
    }

    private fun getJson(endpoint: String, timeoutMs: Int, isRetry: Boolean = false): JSONObject? {
        return try {
            if (authToken.isBlank() && !isRetry) {
                ensurePairedSync()
            }

            val url = URL("$baseUrl$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val token = authToken
            if (token.isNotBlank()) {
                conn.setRequestProperty("x-jarvis-token", token)
            }

            val code = conn.responseCode
            if (code == 401 && !isRetry) {
                Log.w(TAG, "Companion returned 401 Unauthorized for GET '$endpoint'. Re-pairing...")
                authToken = ""
                return getJson(endpoint, timeoutMs, isRetry = true)
            }

            val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (inputStream == null) return null

            val responseText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            if (responseText.isNotBlank()) JSONObject(responseText) else null
        } catch (e: Exception) {
            Log.w(TAG, "GET request to companion '$endpoint' failed: ${e.message}")
            null
        }
    }

    private fun ensurePairedSync() {
        runCatching {
            val pairUrl = URL("$baseUrl/api/pair")
            val pairConn = pairUrl.openConnection() as HttpURLConnection
            pairConn.connectTimeout = 3000
            pairConn.readTimeout = 3000
            pairConn.requestMethod = "POST"
            pairConn.setRequestProperty("Content-Type", "application/json")
            if (pairConn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(pairConn.inputStream)).use { it.readText() }
                val t = JSONObject(text).optString("token", "")
                if (t.isNotBlank()) {
                    authToken = t
                    Log.i(TAG, "Companion synchronized token on $baseUrl")
                }
            }
        }
    }

    private fun postJson(endpoint: String, json: JSONObject, timeoutMs: Int, isRetry: Boolean = false): JSONObject? {
        return try {
            if (authToken.isBlank() && !isRetry) {
                ensurePairedSync()
            }

            val url = URL("$baseUrl$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            val token = authToken
            if (token.isNotBlank()) {
                conn.setRequestProperty("x-jarvis-token", token)
            }
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val code = conn.responseCode
            // If unauthorized (e.g. companion restarted or token invalid), clear token, re-pair and retry once
            if (code == 401 && !isRetry) {
                Log.w(TAG, "Companion returned 401 Unauthorized for '$endpoint'. Attempting re-pairing...")
                authToken = ""
                return postJson(endpoint, json, timeoutMs, isRetry = true)
            }

            val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (inputStream == null) return null

            val responseText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            if (responseText.isNotBlank()) JSONObject(responseText) else null
        } catch (e: Exception) {
            Log.w(TAG, "Request to companion '$endpoint' failed: ${e.message}")
            null
        }
    }
}
