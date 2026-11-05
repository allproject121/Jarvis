package com.jarvis.assistant.ai.router

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * OpenAI ChatGPT API Client for Jarvis.
 * Supports GPT-4o, GPT-4o-mini, streaming tokens, and memory injection.
 */
class ChatGptClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "ChatGptClient"
        private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val MODEL_GPT_4O = "gpt-4o"
        const val MODEL_GPT_4O_MINI = "gpt-4o-mini"
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Streams responses from ChatGPT token-by-token via Server-Sent Events (SSE).
     */
    fun streamChatCompletion(
        apiKey: String,
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList(),
        model: String = MODEL_GPT_4O_MINI
    ): Flow<String> = flow {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("OpenAI API Key is empty. Please set it in Settings.")
        }

        val requestBodyJson = buildRequestBody(
            userPrompt = userPrompt,
            systemPrompt = systemPrompt,
            memoryContext = memoryContext,
            chatHistory = chatHistory,
            model = model,
            stream = true
        )

        val request = Request.Builder()
            .url(OPENAI_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            val code = response.code
            Log.e(TAG, "OpenAI API error $code: $errorBody")
            when (code) {
                401 -> throw Exception("OPENAI_KEY_INVALID: Your OpenAI API key is unauthorized.")
                429 -> throw Exception("OPENAI_QUOTA_EXCEEDED: OpenAI rate limit or quota exceeded.")
                else -> throw Exception("OpenAI API returned HTTP $code: $errorBody")
            }
        }

        val source = response.body?.byteStream() ?: throw Exception("Empty response from OpenAI")
        val reader = BufferedReader(InputStreamReader(source))
        var line: String?

        try {
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (raw.startsWith("data: ")) {
                    val data = raw.substring(6).trim()
                    if (data == "[DONE]") break
                    try {
                        val json = gson.fromJson(data, JsonObject::class.java)
                        val choices = json.getAsJsonArray("choices")
                        if (choices != null && choices.size() > 0) {
                            val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                            if (delta != null && delta.has("content")) {
                                val contentChunk = delta.get("content").asString
                                emit(contentChunk)
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed chunk
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming direct text completion.
     */
    suspend fun directChatCompletion(
        apiKey: String,
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList(),
        model: String = MODEL_GPT_4O_MINI
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("OpenAI API Key is empty.")
        }

        val requestBodyJson = buildRequestBody(
            userPrompt = userPrompt,
            systemPrompt = systemPrompt,
            memoryContext = memoryContext,
            chatHistory = chatHistory,
            model = model,
            stream = false
        )

        val request = Request.Builder()
            .url(OPENAI_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw Exception("OpenAI API HTTP ${response.code}: $errorBody")
            }
            val bodyString = response.body?.string().orEmpty()
            val json = gson.fromJson(bodyString, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val message = choices[0].asJsonObject.getAsJsonObject("message")
                return@withContext message.get("content").asString.trim()
            }
            return@withContext ""
        }
    }

    private fun buildRequestBody(
        userPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        model: String,
        stream: Boolean
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("stream", stream)
        root.addProperty("temperature", 0.7)

        val messages = JsonArray()

        // 1. System directive + memory
        val systemContent = buildString {
            if (systemPrompt.isNotBlank()) append(systemPrompt.trim())
            else append("You are JARVIS, a hyper-intelligent, proactive AI assistant created by Roshan Sir.")
            if (memoryContext.isNotBlank()) {
                append("\n\n").append(memoryContext.trim())
            }
        }
        val sysMsg = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemContent)
        }
        messages.add(sysMsg)

        // 2. Chat history (last 6 turns)
        for (turn in chatHistory.takeLast(6)) {
            if (turn.userTranscript.isNotBlank()) {
                messages.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", turn.userTranscript.trim())
                })
            }
            if (turn.jarvisResponse.isNotBlank()) {
                messages.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", turn.jarvisResponse.trim())
                })
            }
        }

        // 3. User prompt
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userPrompt.trim())
        })

        root.add("messages", messages)
        return gson.toJson(root)
    }
}
