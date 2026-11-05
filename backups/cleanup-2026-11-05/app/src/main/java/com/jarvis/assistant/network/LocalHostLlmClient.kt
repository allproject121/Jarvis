package com.jarvis.assistant.network

import android.util.Log
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Server health and model inspection descriptor for Ollama / Local-Host AI.
 */
data class LocalHostServerInfo(
    val isOnline: Boolean,
    val activeModel: String = "",
    val availableModels: List<String> = emptyList(),
    val pingMs: Long = 0L,
    val error: String? = null
)

/**
 * High-performance streaming client for Local-Host AI (Ollama / Local LLM servers).
 * Connects Jarvis Android directly to your laptop over Wi-Fi (LAN) or USB ADB Reverse.
 */
class LocalHostLlmClient {

    companion object {
        private const val TAG = "LocalHostLlmClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Shared HTTP client with connection pooling and generous timeout for local LLM inference
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun normalizeUrl(rawUrl: String): String {
            var url = rawUrl.trim()
            if (url.isBlank()) return "http://127.0.0.1:11434"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }
    }

    /**
     * Checks if the local host server (Ollama) is reachable and queries available models.
     */
    suspend fun pingServer(rawEndpoint: String): LocalHostServerInfo = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(rawEndpoint)
        val tagsUrl = "$baseUrl/api/tags"
        val startTime = System.currentTimeMillis()

        try {
            val request = Request.Builder()
                .url(tagsUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    return@withContext LocalHostServerInfo(
                        isOnline = false,
                        pingMs = elapsed,
                        error = "HTTP ${response.code}: ${response.message}"
                    )
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                val modelList = mutableListOf<String>()

                for (i in 0 until modelsArray.length()) {
                    val mObj = modelsArray.optJSONObject(i)
                    val name = mObj?.optString("name", "").orEmpty()
                    if (name.isNotBlank()) modelList.add(name)
                }

                val active = modelList.firstOrNull { it.contains("qwen", ignoreCase = true) }
                    ?: modelList.firstOrNull() ?: "ollama"

                Log.i(TAG, "Local host online at $baseUrl (${elapsed}ms). Found ${modelList.size} models: $modelList")
                return@withContext LocalHostServerInfo(
                    isOnline = true,
                    activeModel = active,
                    availableModels = modelList,
                    pingMs = elapsed
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Local host ping failed for $baseUrl: ${e.message}")
            return@withContext LocalHostServerInfo(
                isOnline = false,
                pingMs = elapsed,
                error = e.message ?: "Unable to connect"
            )
        }
    }

    /**
     * Streams conversational responses token-by-token using Ollama's native `/api/chat` streaming protocol.
     */
    fun streamChatCompletion(
        rawEndpoint: String,
        modelName: String,
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList(),
        temperature: Float = 0.7f
    ): Flow<String> = flow {
        val baseUrl = normalizeUrl(rawEndpoint)
        val chatUrl = "$baseUrl/api/chat"

        // 1. Build messages payload
        val messages = JSONArray()

        // System prompt with persistent memory injection
        val combinedSystem = buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
            } else {
                append("You are JARVIS, an ultra-intelligent, concise, and capable AI assistant created by Roshan Sir.")
            }
            if (memoryContext.isNotBlank()) {
                append("\n\n[PERSISTENT MEMORY CORE - USER FACTS]:\n")
                append(memoryContext.trim())
            }
        }
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", combinedSystem)
        })

        // Historical chat turns (last 6 turns for context efficiency)
        val recentHistory = chatHistory.takeLast(6)
        for (turn in recentHistory) {
            if (turn.userTranscript.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", turn.userTranscript.trim())
                })
            }
            if (turn.jarvisResponse.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", turn.jarvisResponse.trim())
                })
            }
        }

        // Current user message
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt.trim())
        })

        // Request root JSON
        val rootJson = JSONObject().apply {
            put("model", modelName.ifBlank { AppPreferences.DEFAULT_LOCAL_HOST_MODEL })
            put("messages", messages)
            put("stream", true)
            put("options", JSONObject().apply {
                put("temperature", temperature)
                put("top_p", 0.9)
            })
        }

        val request = Request.Builder()
            .url(chatUrl)
            .post(rootJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val err = response.body?.string().orEmpty()
            response.close()
            throw Exception("Local AI Error (HTTP ${response.code}): $err")
        }

        val responseBody = response.body ?: throw Exception("Empty response body from Local AI")
        BufferedReader(InputStreamReader(responseBody.byteStream())).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim().orEmpty()
                if (l.isBlank()) continue

                try {
                    val chunkJson = JSONObject(l)
                    val msgObj = chunkJson.optJSONObject("message")
                    val content = msgObj?.optString("content", "").orEmpty()

                    if (content.isNotEmpty()) {
                        emit(content)
                    }

                    if (chunkJson.optBoolean("done", false)) {
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Chunk parse notice: ${e.message}")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming direct chat completion.
     */
    suspend fun generateChatCompletion(
        rawEndpoint: String,
        modelName: String,
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        streamChatCompletion(
            rawEndpoint = rawEndpoint,
            modelName = modelName,
            userPrompt = userPrompt,
            systemPrompt = systemPrompt,
            memoryContext = memoryContext,
            chatHistory = chatHistory
        ).collect { chunk ->
            result.append(chunk)
        }
        result.toString()
    }
}
