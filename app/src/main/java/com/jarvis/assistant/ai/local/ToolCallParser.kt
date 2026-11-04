package com.jarvis.assistant.ai.local

import android.util.Log
import com.jarvis.assistant.ai.provider.AiToolCall
import org.json.JSONObject
import java.util.UUID

/**
 * Parses structured tool and function calls from LLM generation streams.
 * Supports:
 *  1. Nous Hermes & ChatML XML tag format: <tool_call>{"name": "...", "arguments": {...}}</tool_call>
 *  2. Markdown JSON code blocks: ```json {"name": "...", "arguments": {...}} ```
 *  3. Direct JSON objects containing "name" and "arguments"
 */
object ToolCallParser {

    private const val TAG = "ToolCallParser"

    private val XML_TOOL_CALL_REGEX = Regex(
        "(?s)<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>"
    )

    private const val TOOL_CALL_OPEN = "<tool_call>"

    private val THINK_BLOCK_REGEX = Regex("(?s)<think>.*?</think>")

    private val MARKDOWN_JSON_REGEX = Regex(
        "(?s)```(?:json)?\\s*(\\{\\s*\"name\"\\s*:\\s*\".*?\\}\\s*)```"
    )

    /**
     * Attempts to find and extract an AiToolCall from an accumulated text buffer.
     */
    fun parseToolCall(accumulatedText: String): AiToolCall? {
        // A call written while the model is still "thinking" is not a decision; never run it.
        val trimmed = stripThinking(accumulatedText).trim()
        if (!trimmed.contains("name") && !trimmed.contains("arguments")) {
            return null
        }

        // 1. Check for <tool_call>...</tool_call>
        val xmlMatch = XML_TOOL_CALL_REGEX.find(trimmed)
        if (xmlMatch != null) {
            val jsonPayload = xmlMatch.groupValues[1]
            val parsed = parseJsonToToolCall(jsonPayload)
            if (parsed != null) return parsed
        }

        // 1b. <tool_call>{...} without the closing tag. Qwen lists "</tool_call>" as a stop word,
        //     so llama.cpp cuts it off and only the opening tag plus the JSON object arrive.
        val openIdx = trimmed.indexOf(TOOL_CALL_OPEN)
        if (openIdx >= 0) {
            val jsonPayload = extractBalancedJson(trimmed, openIdx + TOOL_CALL_OPEN.length)
            if (jsonPayload != null) {
                val parsed = parseJsonToToolCall(jsonPayload)
                if (parsed != null) return parsed
            }
        }

        // 2. Check for markdown json block ```json ... ```
        val mdMatch = MARKDOWN_JSON_REGEX.find(trimmed)
        if (mdMatch != null) {
            val jsonPayload = mdMatch.groupValues[1]
            val parsed = parseJsonToToolCall(jsonPayload)
            if (parsed != null) return parsed
        }

        // 3. Fallback: Check if the text itself is a raw JSON tool call
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val parsed = parseJsonToToolCall(trimmed)
            if (parsed != null) return parsed
        }

        return null
    }

    /**
     * Removes <tool_call> tags and their contents from display text so the user only sees conversational speech.
     */
    fun cleanConversationalText(rawText: String): String {
        var clean = XML_TOOL_CALL_REGEX.replace(stripThinking(rawText), "").trim()
        clean = MARKDOWN_JSON_REGEX.replace(clean, "").trim()
        // An unclosed <tool_call> (stop word cut the closing tag): drop it and everything after it.
        val openIdx = clean.indexOf(TOOL_CALL_OPEN)
        if (openIdx >= 0) clean = clean.substring(0, openIdx).trim()
        return clean.replace("</tool_call>", "").trim()
    }

    /** Removes `<think>…</think>` blocks, and an unfinished `<think>` up to the end of the text. */
    fun stripThinking(text: String): String {
        val closed = THINK_BLOCK_REGEX.replace(text, "")
        val openIdx = closed.indexOf("<think>")
        return if (openIdx >= 0) closed.substring(0, openIdx) else closed
    }

    /** Returns the first complete `{...}` object starting at or after [from], or null if it is not finished yet. */
    private fun extractBalancedJson(text: String, from: Int): String? {
        val start = text.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parseJsonToToolCall(jsonString: String): AiToolCall? {
        return try {
            val json = JSONObject(jsonString)
            val name = when {
                json.has("name") -> json.getString("name")
                json.has("function") -> json.getString("function")
                json.has("action") -> json.getString("action")
                else -> return null
            }

            val args = when {
                json.has("arguments") -> {
                    val rawArgs = json.get("arguments")
                    if (rawArgs is JSONObject) rawArgs
                    else if (rawArgs is String) JSONObject(rawArgs)
                    else JSONObject()
                }
                json.has("parameters") -> {
                    val rawParams = json.get("parameters")
                    if (rawParams is JSONObject) rawParams
                    else if (rawParams is String) JSONObject(rawParams)
                    else JSONObject()
                }
                else -> JSONObject()
            }

            val callId = "local_call_${UUID.randomUUID().toString().take(8)}"
            AiToolCall(callId = callId, functionName = name, arguments = args)
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing JSON tool call: ${e.message}")
            null
        }
    }
}
