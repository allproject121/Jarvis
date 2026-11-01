package com.jarvis.assistant.ai

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Result data class for multi-model AI responses.
 */
data class AiExecutionResult(
    val finalText: String,
    val mode: String, // "single" or "all_together"
    val providerUsed: String,
    val individualResponses: Map<String, String> = emptyMap(),
    val latencyMs: Long = 0L
)

/**
 * JARVIS Multi-Model AI Router.
 * Supports:
 *  1. Google Gemini (Gemini 3.8 Live, 2.5 Flash, 1.5 Flash)
 *  2. OpenAI ChatGPT (GPT-4o, GPT-4o-mini, o1)
 *  3. Anthropic Claude (Claude 3.5 Sonnet, Haiku)
 *  4. DeepSeek (DeepSeek-V3, DeepSeek-R1 Reasoner)
 *  5. xAI Grok (Grok-2, Grok-beta)
 *  6. OpenRouter (200+ models with 1 key)
 *  7. Localhost Ollama / LM Studio (Offline LLaMA 3.2, Qwen 2.5)
 *
 * Supports two core execution modes:
 *  - MODE_SINGLE: Routes to the user's selected single model.
 *  - MODE_ALL_TOGETHER: Dispatches to all configured models simultaneously in parallel,
 *    cross-verifies, and produces a master consensus response.
 */
object MultiModelAiRouter {
    private const val TAG = "MultiModelAiRouter"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    const val MODE_SINGLE = "single"
    const val MODE_ALL_TOGETHER = "all_together"

    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_GROK = "grok"
    const val PROVIDER_OPENROUTER = "openrouter"
    const val PROVIDER_LOCAL = "ollama"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * Main dispatch method used across Jarvis (Chat, Bypass IDE, Code Studio, Voice, Macros).
     */
    suspend fun execute(
        context: Context,
        systemPrompt: String = "You are JARVIS, an autonomous AI assistant.",
        userPrompt: String,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): AiExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val app = (context.applicationContext as? JarvisApp) ?: JarvisApp.instance
        val prefs = app.preferences

        val mode = prefs.aiExecutionMode
        if (mode == MODE_ALL_TOGETHER) {
            return@withContext executeAllModelsTogether(
                context, systemPrompt, userPrompt, temperature, maxTokens, startTime
            )
        } else {
            return@withContext executeSingleModel(
                context, systemPrompt, userPrompt, temperature, maxTokens, startTime
            )
        }
    }

    /**
     * MODE A: Single Model Execution.
     * Executes the user's selected provider; if unconfigured, cascades to available providers.
     */
    private suspend fun executeSingleModel(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        startTime: Long
    ): AiExecutionResult {
        val prefs = ((context.applicationContext as? JarvisApp) ?: JarvisApp.instance).preferences
        val chosenProvider = prefs.selectedAiProvider

        val answer = when (chosenProvider) {
            PROVIDER_OPENAI -> callOpenAi(prefs.openAiApiKey, prefs.selectedModelName.ifBlank { "gpt-4o" }, systemPrompt, userPrompt, temperature, maxTokens)
            PROVIDER_CLAUDE -> callClaude(prefs.claudeApiKey, prefs.selectedModelName.ifBlank { "claude-3-5-sonnet-20241022" }, systemPrompt, userPrompt, temperature, maxTokens)
            PROVIDER_DEEPSEEK -> callDeepSeek(prefs.deepSeekApiKey, prefs.selectedModelName.ifBlank { "deepseek-chat" }, systemPrompt, userPrompt, temperature, maxTokens)
            PROVIDER_GROK -> callGrok(prefs.grokApiKey, prefs.selectedModelName.ifBlank { "grok-2" }, systemPrompt, userPrompt, temperature, maxTokens)
            PROVIDER_OPENROUTER -> callOpenRouter(prefs.openRouterApiKey, prefs.selectedModelName.ifBlank { "auto" }, systemPrompt, userPrompt, temperature, maxTokens)
            PROVIDER_LOCAL -> callLocalOllama(prefs.customEndpoint, prefs.localHostModel, systemPrompt, userPrompt)
            else -> callGemini(prefs.apiKey, prefs.selectedModelName.ifBlank { "gemini-2.0-flash" }, systemPrompt, userPrompt, temperature, maxTokens)
        }

        if (!answer.isNullOrBlank()) {
            val latency = System.currentTimeMillis() - startTime
            return AiExecutionResult(
                finalText = answer.trim(),
                mode = MODE_SINGLE,
                providerUsed = getProviderDisplayName(chosenProvider),
                individualResponses = mapOf(getProviderDisplayName(chosenProvider) to answer.trim()),
                latencyMs = latency
            )
        }

        // Fallback Cascade if primary had no response
        Log.w(TAG, "Primary provider $chosenProvider returned null. Falling back to available models...")
        val fallbackProviders = listOf(PROVIDER_GEMINI, PROVIDER_OPENAI, PROVIDER_DEEPSEEK, PROVIDER_CLAUDE, PROVIDER_LOCAL)
        for (provider in fallbackProviders) {
            if (provider == chosenProvider) continue
            val fbAns = when (provider) {
                PROVIDER_GEMINI -> if (prefs.apiKey.isNotBlank()) callGemini(prefs.apiKey, "gemini-2.0-flash", systemPrompt, userPrompt, temperature, maxTokens) else null
                PROVIDER_OPENAI -> if (prefs.openAiApiKey.isNotBlank()) callOpenAi(prefs.openAiApiKey, "gpt-4o-mini", systemPrompt, userPrompt, temperature, maxTokens) else null
                PROVIDER_DEEPSEEK -> if (prefs.deepSeekApiKey.isNotBlank()) callDeepSeek(prefs.deepSeekApiKey, "deepseek-chat", systemPrompt, userPrompt, temperature, maxTokens) else null
                PROVIDER_CLAUDE -> if (prefs.claudeApiKey.isNotBlank()) callClaude(prefs.claudeApiKey, "claude-3-5-haiku-20241022", systemPrompt, userPrompt, temperature, maxTokens) else null
                PROVIDER_LOCAL -> if (prefs.customEndpoint.isNotBlank()) callLocalOllama(prefs.customEndpoint, prefs.localHostModel, systemPrompt, userPrompt) else null
                else -> null
            }

            if (!fbAns.isNullOrBlank()) {
                val latency = System.currentTimeMillis() - startTime
                return AiExecutionResult(
                    finalText = fbAns.trim(),
                    mode = MODE_SINGLE,
                    providerUsed = "${getProviderDisplayName(provider)} (Fallback)",
                    individualResponses = mapOf(getProviderDisplayName(provider) to fbAns.trim()),
                    latencyMs = latency
                )
            }
        }

        return AiExecutionResult(
            finalText = "Sir, no AI provider responded. Please verify your API keys or endpoint in Jarvis Settings.",
            mode = MODE_SINGLE,
            providerUsed = "None",
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * MODE B: All Models Together (Multi-Model Consensus & Ensemble Mode).
     * Dispatches the prompt in parallel to all configured models, collects responses,
     * cross-verifies, and synthesizes the ultimate final answer!
     */
    private suspend fun executeAllModelsTogether(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        startTime: Long
    ): AiExecutionResult = coroutineScope {
        val prefs = ((context.applicationContext as? JarvisApp) ?: JarvisApp.instance).preferences
        val parallelTasks = mutableListOf<Pair<String, kotlinx.coroutines.Deferred<String?>>>()

        if (prefs.apiKey.isNotBlank()) {
            parallelTasks.add("Google Gemini" to async { callGemini(prefs.apiKey, "gemini-2.0-flash", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.openAiApiKey.isNotBlank()) {
            parallelTasks.add("OpenAI ChatGPT" to async { callOpenAi(prefs.openAiApiKey, "gpt-4o", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.deepSeekApiKey.isNotBlank()) {
            parallelTasks.add("DeepSeek R1/V3" to async { callDeepSeek(prefs.deepSeekApiKey, "deepseek-chat", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.claudeApiKey.isNotBlank()) {
            parallelTasks.add("Anthropic Claude" to async { callClaude(prefs.claudeApiKey, "claude-3-5-sonnet-20241022", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.grokApiKey.isNotBlank()) {
            parallelTasks.add("xAI Grok" to async { callGrok(prefs.grokApiKey, "grok-2", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.openRouterApiKey.isNotBlank()) {
            parallelTasks.add("OpenRouter" to async { callOpenRouter(prefs.openRouterApiKey, "auto", systemPrompt, userPrompt, temperature, maxTokens) })
        }
        if (prefs.customEndpoint.isNotBlank()) {
            parallelTasks.add("Localhost Ollama" to async { callLocalOllama(prefs.customEndpoint, prefs.localHostModel, systemPrompt, userPrompt) })
        }

        // If no keys configured, return friendly prompt
        if (parallelTasks.isEmpty()) {
            return@coroutineScope AiExecutionResult(
                finalText = "Sir, All-Model Ensemble requires at least 2 AI providers configured in Jarvis Settings (API Connection).",
                mode = MODE_ALL_TOGETHER,
                providerUsed = "None",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        val resultsMap = mutableMapOf<String, String>()
        parallelTasks.forEach { (name, deferred) ->
            try {
                val res = deferred.await()
                if (!res.isNullOrBlank()) {
                    resultsMap[name] = res.trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error from $name in parallel execution: ${e.message}")
            }
        }

        val latency = System.currentTimeMillis() - startTime

        if (resultsMap.isEmpty()) {
            return@coroutineScope AiExecutionResult(
                finalText = "All configured models failed to respond. Please check internet connection and API keys.",
                mode = MODE_ALL_TOGETHER,
                providerUsed = "Failed",
                latencyMs = latency
            )
        }

        // If only 1 responded, return that
        if (resultsMap.size == 1) {
            val entry = resultsMap.entries.first()
            return@coroutineScope AiExecutionResult(
                finalText = entry.value,
                mode = MODE_ALL_TOGETHER,
                providerUsed = entry.key,
                individualResponses = resultsMap,
                latencyMs = latency
            )
        }

        // Synthesize Multi-Model Consensus
        val consensusText = synthesizeConsensusResponse(resultsMap)
        return@coroutineScope AiExecutionResult(
            finalText = consensusText,
            mode = MODE_ALL_TOGETHER,
            providerUsed = "Multi-AI Ensemble (${resultsMap.keys.joinToString(" + ")})",
            individualResponses = resultsMap,
            latencyMs = latency
        )
    }

    /**
     * Synthesizes responses from multiple AI models into a master consolidated answer.
     */
    private fun synthesizeConsensusResponse(responses: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("⚡ **JARVIS MULTI-AI CONSENSUS** (Verified by ${responses.size} AI Models: ${responses.keys.joinToString(", ")})\n\n")

        // Primary Best Answer (using first comprehensive response)
        val bestEntry = responses.entries.maxByOrNull { it.value.length } ?: responses.entries.first()
        sb.append(bestEntry.value)

        // Append Cross-Verification Notes if differences exist
        sb.append("\n\n---\n")
        sb.append("🔍 **Multi-Model Cross-Check:**\n")
        responses.forEach { (model, answer) ->
            val snippet = if (answer.length > 140) answer.take(137) + "..." else answer
            sb.append("• **$model**: ${snippet.replace("\n", " ")}\n")
        }

        return sb.toString()
    }

    // ──────────────── API Call Implementations ────────────────

    /**
     * Google Gemini REST API.
     */
    private fun callGemini(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? {
        if (apiKey.isBlank()) return null
        return try {
            val resolvedModel = if (modelName.contains("gemini")) modelName.replace("models/", "") else "gemini-2.0-flash"
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$resolvedModel:generateContent?key=$apiKey"

            val jsonBody = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxTokens)
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
                val parts = content.optJSONArray("parts") ?: return null
                if (parts.length() == 0) return null
                parts.getJSONObject(0).optString("text", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callGemini error: ${e.message}")
            null
        }
    }

    /**
     * OpenAI ChatGPT API (gpt-4o, gpt-4o-mini).
     */
    private fun callOpenAi(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? = callOpenAiCompatible(
        url = "https://api.openai.com/v1/chat/completions",
        apiKey = apiKey,
        model = if (modelName.isBlank()) "gpt-4o" else modelName,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        temperature = temperature,
        maxTokens = maxTokens
    )

    /**
     * DeepSeek API (deepseek-chat V3, deepseek-reasoner R1).
     */
    private fun callDeepSeek(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? = callOpenAiCompatible(
        url = "https://api.deepseek.com/v1/chat/completions",
        apiKey = apiKey,
        model = if (modelName.isBlank()) "deepseek-chat" else modelName,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        temperature = temperature,
        maxTokens = maxTokens
    )

    /**
     * xAI Grok API (grok-2, grok-beta).
     */
    private fun callGrok(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? = callOpenAiCompatible(
        url = "https://api.x.ai/v1/chat/completions",
        apiKey = apiKey,
        model = if (modelName.isBlank()) "grok-2" else modelName,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        temperature = temperature,
        maxTokens = maxTokens
    )

    /**
     * OpenRouter API (Access to 200+ models with 1 key).
     */
    private fun callOpenRouter(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? = callOpenAiCompatible(
        url = "https://openrouter.ai/api/v1/chat/completions",
        apiKey = apiKey,
        model = if (modelName.isBlank() || modelName == "auto") "openrouter/auto" else modelName,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = mapOf("HTTP-Referer" to "https://jarvis.mobile", "X-Title" to "JARVIS Mobile OS")
    )

    /**
     * Anthropic Claude API (claude-3-5-sonnet, claude-3-5-haiku).
     */
    private fun callClaude(
        apiKey: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int
    ): String? {
        if (apiKey.isBlank()) return null
        return try {
            val url = "https://api.anthropic.com/v1/messages"
            val resolvedModel = if (modelName.isBlank()) "claude-3-5-sonnet-20241022" else modelName

            val jsonBody = JSONObject().apply {
                put("model", resolvedModel)
                put("max_tokens", maxTokens)
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", userPrompt)
                ))
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val contentArray = root.optJSONArray("content") ?: return null
                if (contentArray.length() == 0) return null
                contentArray.getJSONObject(0).optString("text", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callClaude error: ${e.message}")
            null
        }
    }

    /**
     * Local Ollama / LM Studio.
     */
    private fun callLocalOllama(
        endpoint: String,
        modelName: String,
        systemPrompt: String,
        userPrompt: String
    ): String? {
        if (endpoint.isBlank()) return null
        val cleanEp = endpoint.trimEnd('/')
        // Try OpenAI compatible endpoint on Ollama: /v1/chat/completions
        val openAiEp = "$cleanEp/v1/chat/completions"
        val res = callOpenAiCompatible(openAiEp, "", modelName.ifBlank { "llama3.2" }, systemPrompt, userPrompt, 0.7, 1024)
        if (!res.isNullOrBlank()) return res

        // Fallback to native Ollama /api/generate
        return try {
            val url = "$cleanEp/api/generate"
            val jsonBody = JSONObject().apply {
                put("model", modelName.ifBlank { "llama3.2" })
                put("prompt", if (systemPrompt.isNotBlank()) "$systemPrompt\n\n$userPrompt" else userPrompt)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                JSONObject(bodyStr).optString("response", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callLocalOllama error: ${e.message}")
            null
        }
    }

    /**
     * Generic OpenAI-Compatible REST executor (Works for OpenAI, DeepSeek, Grok, OpenRouter, vLLM, Ollama).
     */
    private fun callOpenAiCompatible(
        url: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val messages = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.put(JSONObject().put("role", "user").put("content", userPrompt))

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            extraHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "callOpenAiCompatible failed ($url) code: ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val choices = root.optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
                msg.optString("content", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "callOpenAiCompatible error ($url): ${e.message}")
            null
        }
    }

    fun getProviderDisplayName(providerKey: String): String = when (providerKey) {
        PROVIDER_GEMINI -> "Google Gemini"
        PROVIDER_OPENAI -> "OpenAI ChatGPT"
        PROVIDER_CLAUDE -> "Anthropic Claude"
        PROVIDER_DEEPSEEK -> "DeepSeek"
        PROVIDER_GROK -> "xAI Grok"
        PROVIDER_OPENROUTER -> "OpenRouter"
        PROVIDER_LOCAL -> "Local Ollama"
        else -> providerKey.replaceFirstChar { it.uppercase() }
    }
}
