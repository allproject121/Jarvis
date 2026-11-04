package com.jarvis.assistant.maya.brain

import org.json.JSONArray
import org.json.JSONObject

/** One tool invocation requested by the model (provider-agnostic). */
data class BrainToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

/**
 * Parsed assistant turn: either plain [text], or one or more [toolCalls] (possibly with
 * text alongside). [rawAssistantMessage] is the provider's own message object so it can be
 * echoed back verbatim in the next request (OpenAI needs the original `tool_calls` array,
 * Anthropic the original content blocks).
 */
data class BrainTurn(
    val text: String,
    val toolCalls: List<BrainToolCall>,
    val model: String,
    val finishReason: String,
    val rawAssistantMessage: JSONObject?,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * Pure (no Android) JSON parsing for the OpenAI chat-completions and Anthropic messages
 * responses. Kept separate from the HTTP client so unit tests cover it with org.json only.
 */
object BrainJson {

    // ── OpenAI chat completions ─────────────────────────────────────────────

    /** Parses a non-streaming `/chat/completions` response body. */
    fun parseOpenAiResponse(root: JSONObject): BrainTurn {
        val choices = root.optJSONArray("choices") ?: JSONArray()
        val first = if (choices.length() > 0) choices.optJSONObject(0) else null
        val message = first?.optJSONObject("message")
        val finish = first?.optString("finish_reason", "") ?: ""
        val usage = root.optJSONObject("usage")
        val text = contentToText(message?.opt("content"))
        val calls = mutableListOf<BrainToolCall>()
        val toolCalls = message?.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                if (name.isBlank()) continue
                val id = tc.optString("id", "").ifBlank { "call_$i" }
                calls += BrainToolCall(id, name, parseArguments(fn.opt("arguments")))
            }
        }
        // Legacy single function_call shape.
        if (calls.isEmpty()) {
            val fc = message?.optJSONObject("function_call")
            val name = fc?.optString("name", "")?.trim().orEmpty()
            if (name.isNotBlank()) calls += BrainToolCall("call_0", name, parseArguments(fc?.opt("arguments")))
        }
        return BrainTurn(
            text = text,
            toolCalls = calls,
            model = root.optString("model", ""),
            finishReason = finish,
            rawAssistantMessage = message,
            promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
        )
    }

    /** `arguments` may be a JSON string, an object, or missing. Never throws. */
    fun parseArguments(raw: Any?): JSONObject = when (raw) {
        null -> JSONObject()
        is JSONObject -> raw
        is String -> {
            val s = raw.trim()
            if (s.isBlank() || s == "null") JSONObject()
            else runCatching { JSONObject(s) }.getOrElse {
                // Some models wrap arguments in code fences or add trailing text.
                val start = s.indexOf('{')
                val end = s.lastIndexOf('}')
                if (start >= 0 && end > start) runCatching { JSONObject(s.substring(start, end + 1)) }.getOrDefault(JSONObject())
                else JSONObject()
            }
        }
        else -> JSONObject()
    }

    /** OpenAI `content` is a string or an array of `{type:"text", text}` parts. */
    fun contentToText(content: Any?): String = when (content) {
        null, org.json.JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type", "text") == "text") sb.append(part.optString("text", ""))
            }
            sb.toString()
        }
        else -> content.toString()
    }

    /** Collects `delta.content` tokens from SSE `data:` lines of a streaming response. */
    fun parseOpenAiStreamDelta(line: String): String? {
        val payload = line.removePrefix("data:").trim()
        if (payload.isBlank() || payload == "[DONE]") return null
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val choices = obj.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
        return delta.optString("content", "").takeIf { it.isNotEmpty() }
    }

    /** OpenAI-style error body → human text ("" when not an error body). */
    fun extractErrorMessage(body: String): String {
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(200)
        val err = obj.opt("error")
        return when (err) {
            is JSONObject -> err.optString("message", "").ifBlank { err.toString().take(200) }
            is String -> err
            else -> obj.optString("message", "").ifBlank { body.take(200) }
        }
    }

    // ── Anthropic messages ──────────────────────────────────────────────────

    /** Parses a non-streaming `/v1/messages` response body. */
    fun parseAnthropicResponse(root: JSONObject): BrainTurn {
        val content = root.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        val calls = mutableListOf<BrainToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type", "")) {
                "text" -> sb.append(block.optString("text", ""))
                "tool_use" -> {
                    val name = block.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val input = block.optJSONObject("input") ?: parseArguments(block.opt("input"))
                    calls += BrainToolCall(block.optString("id", "").ifBlank { "toolu_$i" }, name, input)
                }
            }
        }
        val usage = root.optJSONObject("usage")
        val assistant = JSONObject().apply {
            put("role", "assistant")
            put("content", content)
        }
        return BrainTurn(
            text = sb.toString(),
            toolCalls = calls,
            model = root.optString("model", ""),
            finishReason = root.optString("stop_reason", ""),
            rawAssistantMessage = assistant,
            promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("output_tokens", 0) ?: 0
        )
    }

    /**
     * Converts OpenAI tool schemas (`{"type":"function","function":{name,description,parameters}}`)
     * to Anthropic tool definitions (`{name, description, input_schema}`).
     */
    fun openAiToolsToAnthropic(tools: List<JSONObject>): JSONArray {
        val out = JSONArray()
        for (t in tools) {
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            out.put(JSONObject().apply {
                put("name", name)
                put("description", fn.optString("description", ""))
                put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject()))
            })
        }
        return out
    }

    /** Builds the Anthropic `tool_result` user message for a batch of tool results. */
    fun anthropicToolResults(results: List<Pair<String, JSONObject>>): JSONObject {
        val blocks = JSONArray()
        for ((id, result) in results) {
            blocks.put(JSONObject().apply {
                put("type", "tool_result")
                put("tool_use_id", id)
                put("content", result.toString())
            })
        }
        return JSONObject().put("role", "user").put("content", blocks)
    }

    /** Builds an OpenAI `tool` role message for one tool result. */
    fun openAiToolResult(callId: String, name: String, result: JSONObject): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", callId)
            put("name", name)
            put("content", result.toString())
        }

    /** Builds an assistant message carrying tool calls when the provider returned none we can echo. */
    fun openAiAssistantWithToolCalls(text: String, calls: List<BrainToolCall>): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            if (text.isNotBlank()) put("content", text) else put("content", JSONObject.NULL)
            put("tool_calls", JSONArray().apply {
                calls.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("type", "function")
                        put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.toString()))
                    })
                }
            })
        }
}
