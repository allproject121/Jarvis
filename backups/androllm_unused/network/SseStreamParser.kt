package com.jarvis.assistant.network

import org.json.JSONObject

/**
 * Universal Server-Sent Events (SSE) streaming parser for OpenAI and LiteLLM compatible endpoints.
 * Handles content deltas, extended reasoning / thinking tokens (DeepSeek-R1, Gemini Flash Thinking),
 * streaming tool call fragments, and terminal [DONE] signals.
 */
object SseStreamParser {

    sealed interface ParsedEvent {
        data class Content(val text: String) : ParsedEvent
        data class Reasoning(val text: String) : ParsedEvent
        data class ToolCallDelta(val index: Int, val name: String?, val arguments: String) : ParsedEvent
        data object Done : ParsedEvent
        data object Ignored : ParsedEvent
    }

    fun parseLine(line: String): ParsedEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return ParsedEvent.Ignored
        if (!trimmed.startsWith("data:")) return ParsedEvent.Ignored

        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return ParsedEvent.Done

        return try {
            val json = JSONObject(payload)
            val choices = json.optJSONArray("choices") ?: return ParsedEvent.Ignored
            if (choices.length() == 0) return ParsedEvent.Ignored

            val firstChoice = choices.getJSONObject(0)
            val delta = firstChoice.optJSONObject("delta") ?: return ParsedEvent.Ignored

            // Check for reasoning / thinking tokens
            val reasoning = delta.optString("reasoning_content", "")
            if (reasoning.isNotEmpty()) {
                return ParsedEvent.Reasoning(reasoning)
            }

            // Check for streaming tool calls
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val tc = toolCalls.getJSONObject(0)
                val idx = tc.optInt("index", 0)
                val func = tc.optJSONObject("function")
                val fnName = func?.optString("name", null)
                val fnArgs = func?.optString("arguments", "") ?: ""
                return ParsedEvent.ToolCallDelta(idx, fnName, fnArgs)
            }

            // Check for content tokens
            val content = delta.optString("content", "")
            if (content.isNotEmpty()) {
                return ParsedEvent.Content(content)
            }

            ParsedEvent.Ignored
        } catch (_: Exception) {
            ParsedEvent.Ignored
        }
    }
}
