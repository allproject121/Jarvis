package com.jarvis.assistant.maya.brain

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of one chat request. */
sealed class BrainResult {
    data class Success(val turn: BrainTurn, val latencyMs: Long, val modelUsed: String) : BrainResult()

    /**
     * [httpCode] 0 = transport failure. [userMessage] is the Maya-style spoken explanation,
     * [detail] the raw provider text for logs/UI.
     */
    data class Failure(val httpCode: Int, val userMessage: String, val detail: String, val retryable: Boolean) : BrainResult()
}

/**
 * Blocking OpenAI-compatible / Anthropic chat client with native tool calling.
 * Call it off the main thread.
 */
object BrainHttpClient {

    private const val TAG = "BrainHttp"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    const val MSG_NO_ANSWER = "Brain se jawab nahi aaya. Thodi der baad phir bolo, ya Settings > Sub-agents me provider test karo."
    const val MSG_KEY_REJECTED = "Agentic mode ke LLM ki API key reject ho gayi. Settings me Voice engine kholo aur key dobara daalo."
    const val MSG_NO_CREDIT = "LLM account me credit khatam ho gaya hai. Wahan balance daalo, ya Settings me Voice engine se doosra model chuno."
    const val MSG_MODEL_NOT_FOUND = "Jo LLM model chuna hai wo is provider pe mila nahi. Settings me Voice engine kholo aur list se model chuno."
    const val MSG_QUOTA = "Is API key ka is model pe aaj ka quota khatam ho gaya hai. Kal phir chalega, ya Settings me Voice engine se doosra model chuno."
    const val MSG_BAD_REQUEST = "LLM ne request thukra di. Settings me Voice engine ka model aur URL check karo."
    const val MSG_KEY_OR_MODEL = "LLM ki key ya model me dikkat hai — Settings → Voice engine me theek karo"

    /**
     * Sends one chat turn.
     *
     * @param messages OpenAI-style messages (role/content, tool messages, assistant tool_calls).
     *                 For Anthropic the `system` messages are lifted into the top-level field and
     *                 `tool` messages are converted to `tool_result` blocks.
     * @param tools OpenAI function schemas; converted for Anthropic.
     */
    fun chat(
        config: BrainConfig,
        model: String,
        messages: JSONArray,
        tools: List<JSONObject> = emptyList(),
        temperature: Double = 0.6,
        maxTokens: Int = 2048
    ): BrainResult {
        val started = System.currentTimeMillis()
        return try {
            val (url, body, headers) = when (config.provider.apiStyle) {
                BrainApiStyle.OPENAI -> buildOpenAi(config, model, messages, tools, temperature, maxTokens)
                BrainApiStyle.ANTHROPIC -> buildAnthropic(config, model, messages, tools, temperature, maxTokens)
            }
            val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
            headers.forEach { (k, v) -> req.addHeader(k, v) }
            http.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "${config.provider.id}/$model HTTP ${resp.code}: ${text.take(300)}")
                    return failureFor(resp.code, text)
                }
                val root = runCatching { JSONObject(text) }.getOrElse {
                    return BrainResult.Failure(resp.code, MSG_NO_ANSWER, "Non-JSON response: ${text.take(200)}", retryable = true)
                }
                if (root.has("error") && !root.has("choices") && !root.has("content")) {
                    return failureFor(root.optJSONObject("error")?.optInt("code", 500) ?: 500, text)
                }
                val turn = when (config.provider.apiStyle) {
                    BrainApiStyle.OPENAI -> BrainJson.parseOpenAiResponse(root)
                    BrainApiStyle.ANTHROPIC -> BrainJson.parseAnthropicResponse(root)
                }
                BrainResult.Success(turn, System.currentTimeMillis() - started, turn.model.ifBlank { model })
            }
        } catch (e: IOException) {
            Log.w(TAG, "${config.provider.id}/$model network error: ${e.message}")
            BrainResult.Failure(0, MSG_NO_ANSWER, e.message ?: "network error", retryable = true)
        } catch (e: Exception) {
            Log.e(TAG, "${config.provider.id}/$model error: ${e.message}", e)
            BrainResult.Failure(0, MSG_NO_ANSWER, e.message ?: "error", retryable = false)
        }
    }

    fun failureFor(code: Int, body: String): BrainResult.Failure {
        val detail = BrainJson.extractErrorMessage(body)
        val lower = detail.lowercase()
        val msg = when (code) {
            401, 403 -> MSG_KEY_REJECTED
            402 -> MSG_NO_CREDIT
            404 -> MSG_MODEL_NOT_FOUND
            429 -> if (OpenRouterAutoPick.isNegativeBalance(body)) OpenRouterAutoPick.NEGATIVE_BALANCE else MSG_QUOTA
            400, 422 -> when {
                lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("invalid model")) -> MSG_MODEL_NOT_FOUND
                lower.contains("api key") || lower.contains("api_key") -> MSG_KEY_REJECTED
                else -> MSG_BAD_REQUEST
            }
            else -> if (code >= 500) MSG_NO_ANSWER else MSG_KEY_OR_MODEL
        }
        return BrainResult.Failure(code, msg, detail, retryable = code == 429 || code >= 500)
    }

    private data class Built(val url: String, val body: JSONObject, val headers: Map<String, String>)

    private fun buildOpenAi(
        config: BrainConfig,
        model: String,
        messages: JSONArray,
        tools: List<JSONObject>,
        temperature: Double,
        maxTokens: Int
    ): Built {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply { tools.forEach { put(it) } })
                put("tool_choice", "auto")
            }
            if (config.think) {
                when (config.provider.id) {
                    BrainProviders.OPENROUTER -> put("reasoning", JSONObject().put("effort", "low"))
                    BrainProviders.OPENAI, BrainProviders.GEMINI_OPENAI -> put("reasoning_effort", "low")
                }
            }
        }
        val headers = LinkedHashMap<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        headers.putAll(config.provider.extraHeaders)
        return Built(config.provider.chatUrl(config.baseUrl), body, headers)
    }

    private fun buildAnthropic(
        config: BrainConfig,
        model: String,
        messages: JSONArray,
        tools: List<JSONObject>,
        temperature: Double,
        maxTokens: Int
    ): Built {
        val system = StringBuilder()
        val converted = JSONArray()
        var pendingToolResults = mutableListOf<Pair<String, JSONObject>>()
        fun flushToolResults() {
            if (pendingToolResults.isNotEmpty()) {
                converted.put(BrainJson.anthropicToolResults(pendingToolResults))
                pendingToolResults = mutableListOf()
            }
        }
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role", "user")) {
                "system" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(BrainJson.contentToText(m.opt("content")))
                }
                "tool" -> {
                    val result = runCatching { JSONObject(m.optString("content", "{}")) }.getOrElse { JSONObject().put("result", m.optString("content", "")) }
                    pendingToolResults += m.optString("tool_call_id", "") to result
                }
                "assistant" -> {
                    flushToolResults()
                    val content = m.opt("content")
                    if (content is JSONArray) {
                        // Already Anthropic blocks (echoed from a previous parse).
                        converted.put(JSONObject().put("role", "assistant").put("content", content))
                    } else {
                        val blocks = JSONArray()
                        val text = BrainJson.contentToText(content)
                        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
                        val tcs = m.optJSONArray("tool_calls")
                        if (tcs != null) for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            blocks.put(JSONObject().apply {
                                put("type", "tool_use")
                                put("id", tc.optString("id", "toolu_$j"))
                                put("name", fn.optString("name", ""))
                                put("input", BrainJson.parseArguments(fn.opt("arguments")))
                            })
                        }
                        if (blocks.length() > 0) converted.put(JSONObject().put("role", "assistant").put("content", blocks))
                    }
                }
                else -> {
                    flushToolResults()
                    val content = m.opt("content")
                    if (content is JSONArray) {
                        // OpenAI multimodal parts → Anthropic blocks.
                        val blocks = JSONArray()
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            when (part.optString("type", "text")) {
                                "text" -> blocks.put(JSONObject().put("type", "text").put("text", part.optString("text", "")))
                                "image_url" -> {
                                    val url = part.optJSONObject("image_url")?.optString("url", "").orEmpty()
                                    val dataIdx = url.indexOf("base64,")
                                    if (url.startsWith("data:") && dataIdx > 0) {
                                        val mime = url.substring(5, url.indexOf(';').coerceAtLeast(5))
                                        blocks.put(JSONObject().put("type", "image").put("source", JSONObject().apply {
                                            put("type", "base64")
                                            put("media_type", mime.ifBlank { "image/jpeg" })
                                            put("data", url.substring(dataIdx + 7))
                                        }))
                                    } else if (url.isNotBlank()) {
                                        blocks.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "url").put("url", url)))
                                    }
                                }
                            }
                        }
                        converted.put(JSONObject().put("role", "user").put("content", blocks))
                    } else {
                        converted.put(JSONObject().put("role", "user").put("content", BrainJson.contentToText(content)))
                    }
                }
            }
        }
        flushToolResults()
        val body = JSONObject().apply {
            put("model", model)
            put("messages", converted)
            put("max_tokens", if (config.think) maxOf(maxTokens, 4096) else maxTokens)
            if (!config.think) put("temperature", temperature)
            if (system.isNotEmpty()) put("system", system.toString())
            if (tools.isNotEmpty()) put("tools", BrainJson.openAiToolsToAnthropic(tools))
            if (config.think) put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 1024))
        }
        val headers = linkedMapOf(
            "x-api-key" to config.apiKey,
            "anthropic-version" to "2023-06-01"
        )
        return Built(config.provider.chatUrl(config.baseUrl), body, headers)
    }

    /** Lists model ids from the provider's models endpoint (OpenAI `data[].id` or Anthropic `data[].id`). */
    fun listModels(config: BrainConfig): Result<List<String>> = runCatching {
        val b = Request.Builder().url(config.provider.modelsUrl(config.baseUrl))
        when (config.provider.apiStyle) {
            BrainApiStyle.OPENAI -> if (config.apiKey.isNotBlank()) b.addHeader("Authorization", "Bearer ${config.apiKey}")
            BrainApiStyle.ANTHROPIC -> {
                b.addHeader("x-api-key", config.apiKey)
                b.addHeader("anthropic-version", "2023-06-01")
            }
        }
        config.provider.extraHeaders.forEach { (k, v) -> b.addHeader(k, v) }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${BrainJson.extractErrorMessage(text)}")
            val root = JSONObject(text)
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val ids = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val id = m.optString("id", "").ifBlank { m.optString("name", "") }
                if (id.isNotBlank()) ids += id.removePrefix("models/")
            }
            ids.sorted()
        }
    }
}
