package com.jarvis.assistant.ai

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Universal AI Text Synthesizer for single-turn generative tasks:
 *  - YouTube Creator Scripts & Outlines
 *  - Social Media Morning Stories & Captions
 *  - Creative Writing & Dynamic Prompts
 *
 * Implements intelligent cascade:
 *  1. Gemini REST API (gemini-2.0-flash / gemini-1.5-flash) using preferences.apiKey
 *  2. OpenAI REST API (gpt-4o / gpt-4o-mini) using preferences.openAiApiKey
 *  3. LocalHost LLM (Ollama / vLLM / LM Studio) using preferences.customEndpoint
 *  4. Fallback lambda / rule-based generator
 */
object AiTextSynthesizer {
    private const val TAG = "AiTextSynthesizer"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizeText(
        context: Context,
        systemInstruction: String,
        prompt: String,
        temperature: Double = 0.7,
        maxTokens: Int = 1200,
        fallback: () -> String
    ): String = withContext(Dispatchers.IO) {
        val app = (context.applicationContext as? JarvisApp) ?: JarvisApp.instance
        val prefs = app.preferences

        // 1. Try Gemini REST API (Direct and lightning fast)
        val geminiKey = prefs.apiKey.trim()
        if (geminiKey.isNotBlank()) {
            val geminiResult = callGeminiRestApi(geminiKey, systemInstruction, prompt, temperature, maxTokens)
            if (geminiResult != null && geminiResult.isNotBlank()) {
                Log.i(TAG, "Successfully synthesized text using Gemini REST API")
                return@withContext geminiResult.trim()
            }
        }

        // 2. Try OpenAI API if key is present
        val openAiKey = prefs.openAiApiKey.trim()
        if (openAiKey.isNotBlank()) {
            val openAiResult = callOpenAiRestApi(openAiKey, systemInstruction, prompt, temperature, maxTokens)
            if (openAiResult != null && openAiResult.isNotBlank()) {
                Log.i(TAG, "Successfully synthesized text using OpenAI API")
                return@withContext openAiResult.trim()
            }
        }

        // 3. Try Local Host LLM (Ollama / vLLM / LM Studio)
        val customEp = prefs.customEndpoint.trim()
        val localModel = prefs.localHostModel.trim()
        if (customEp.isNotBlank()) {
            val localResult = callLocalHostApi(customEp, localModel, systemInstruction, prompt)
            if (localResult != null && localResult.isNotBlank()) {
                Log.i(TAG, "Successfully synthesized text using LocalHost LLM")
                return@withContext localResult.trim()
            }
        }

        // 4. Return reliable deterministic fallback
        Log.i(TAG, "Using fallback deterministic generation")
        fallback()
    }

    private fun callGeminiRestApi(
        apiKey: String,
        systemInstruction: String,
        prompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? {
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val bodyJson = JSONObject().apply {
                    if (systemInstruction.isNotBlank()) {
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", systemInstruction) })
                            })
                        })
                    }
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", temperature)
                        put("maxOutputTokens", maxTokens)
                    })
                }

                val req = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val first = candidates.getJSONObject(0)
                            val content = first.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.getJSONObject(0).optString("text")
                                if (text.isNotBlank()) return text
                            }
                        }
                    } else {
                        Log.w(TAG, "Gemini $model returned HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini API call failed for $model: ${e.message}")
            }
        }
        return null
    }

    private fun callOpenAiRestApi(
        apiKey: String,
        systemInstruction: String,
        prompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? {
        try {
            val url = "https://api.openai.com/v1/chat/completions"
            val messages = JSONArray()
            if (systemInstruction.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemInstruction)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val bodyJson = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")
                        val content = msg?.optString("content")
                        if (!content.isNullOrBlank()) return content
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI API call failed: ${e.message}")
        }
        return null
    }

    private fun callLocalHostApi(
        endpoint: String,
        modelName: String,
        systemInstruction: String,
        prompt: String
    ): String? {
        try {
            val cleanEndpoint = endpoint.trimEnd('/')
            val fullPrompt = if (systemInstruction.isNotBlank()) "$systemInstruction\n\n$prompt" else prompt
            val url = "$cleanEndpoint/api/generate"
            val bodyJson = JSONObject().apply {
                put("model", modelName)
                put("prompt", fullPrompt)
                put("stream", false)
            }

            val req = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val res = json.optString("response")
                    if (res.isNotBlank()) return res
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocalHost API call failed: ${e.message}")
        }
        return null
    }
}
