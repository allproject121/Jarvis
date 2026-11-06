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

    private val NAME_REGEX = Regex("\"(?:name|function)\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\"")
    private val ARGS_START_REGEX = Regex("\"(?:arguments|parameters)\"\\s*:\\s*")
    private val SIMPLE_PAIR_REGEX = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|-?\\d+(?:\\.\\d+)?)")
    private val ARITHMETIC_VALUE_REGEX = Regex("(:\\s*)(\\(?\\s*-?\\d+(?:\\.\\d+)?(?:\\s*[-+*/]\\s*\\(?\\s*-?\\d+(?:\\.\\d+)?\\s*\\)?)+\\s*\\)?)(?=\\s*[,}])")

    /**
     * Use ONLY on a finished reply. [parseToolCall], then a repair pass for the broken JSON small models
     * write, e.g. `"arguments": {"}}` or `"minutes": 60*24 - 6*60`. The phone audit (Nov 6) found such
     * calls on 3 of 24 tool runs: nothing ran and the user got an empty reply.
     * Never used on a partial stream: a half-written call would otherwise run with missing arguments.
     */
    fun parseToolCallLenient(finishedText: String): AiToolCall? =
        parseToolCall(finishedText) ?: repairToolCall(finishedText)

    private fun repairToolCall(text: String): AiToolCall? {
        val stripped = stripThinking(text)
        val openIdx = stripped.indexOf(TOOL_CALL_OPEN)
        val body = when {
            openIdx >= 0 -> stripped.substring(openIdx + TOOL_CALL_OPEN.length)
            stripped.trimStart().startsWith("{") -> stripped
            else -> return null
        }.substringBefore("</tool_call>")
        val name = NAME_REGEX.find(body)?.groupValues?.get(1) ?: return null
        val argsMatch = ARGS_START_REGEX.find(body)
        val args = if (argsMatch == null) JSONObject() else {
            val rest = body.substring(argsMatch.range.last + 1)
            val candidate = extractBalancedJson(rest, 0) ?: rest
            lenientArgs(candidate)
        }
        Log.w(TAG, "Repaired broken tool call JSON: $name $args")
        return AiToolCall(callId = "local_call_${UUID.randomUUID().toString().take(8)}", functionName = name, arguments = args)
    }

    /** Best-effort object: arithmetic values computed, else every simple "key": value pair, else empty. */
    private fun computeArithmeticValues(raw: String): String =
        ARITHMETIC_VALUE_REGEX.replace(raw) { m ->
            val v = evaluateArithmetic(m.groupValues[2])
            if (v == null) m.value else m.groupValues[1] + formatNumber(v)
        }

    private fun lenientArgs(raw: String): JSONObject {
        val computed = computeArithmeticValues(raw)
        runCatching { return JSONObject(computed) }
        val out = JSONObject()
        for (m in SIMPLE_PAIR_REGEX.findAll(computed)) {
            val key = m.groupValues[1]
            val rawValue = m.groupValues[2]
            when {
                rawValue.startsWith("\"") -> out.put(key, m.groupValues[3])
                rawValue == "true" || rawValue == "false" -> out.put(key, rawValue.toBoolean())
                rawValue.contains('.') -> out.put(key, rawValue.toDouble())
                else -> out.put(key, rawValue.toLong())
            }
        }
        return out
    }

    private fun formatNumber(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    /** + - * / and parentheses only; null when the text is anything else. */
    internal fun evaluateArithmetic(expr: String): Double? {
        val s = expr.replace(" ", "")
        var pos = 0
        fun parseExpr(): Double? {
            fun parseFactor(): Double? {
                if (pos < s.length && s[pos] == '(') {
                    pos++
                    val v = parseExpr() ?: return null
                    if (pos >= s.length || s[pos] != ')') return null
                    pos++
                    return v
                }
                val start = pos
                if (pos < s.length && s[pos] == '-') pos++
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDoubleOrNull()
            }
            fun parseTerm(): Double? {
                var v = parseFactor() ?: return null
                while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                    val op = s[pos++]
                    val r = parseFactor() ?: return null
                    v = if (op == '*') v * r else { if (r == 0.0) return null; v / r }
                }
                return v
            }
            var v = parseTerm() ?: return null
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val r = parseTerm() ?: return null
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        val result = parseExpr()
        return if (pos == s.length) result else null
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

    /**
     * Removes `<think>…</think>` blocks, an unfinished `<think>` up to the end of the text, and
     * (reasoning models whose prompt already opened the block) everything before a lone `</think>`.
     */
    fun stripThinking(text: String): String {
        var closed = THINK_BLOCK_REGEX.replace(text, "")
        val loneClose = closed.lastIndexOf("</think>")
        if (loneClose >= 0) closed = closed.substring(loneClose + "</think>".length)
        val openIdx = closed.indexOf("<think>")
        return if (openIdx >= 0) closed.substring(0, openIdx) else closed
    }

    /**
     * Chat-template control tags a small on-device model sometimes writes as plain text when it does
     * not stop at its end-of-turn token (e.g. "…questions</|im_end|>
<|im_start|>user
it's always").
     * Everything from the first one on is the model inventing the next turn, never part of the answer.
     */
    private val TURN_LEAK_MARKERS = listOf(
        "<|im_end|>", "</|im_end|>", "<|im_start|>", "<|endoftext|>", "<|end|>",
        "<|eot_id|>", "<|start_header_id|>", "<|end_of_text|>",
        "<end_of_turn>", "<start_of_turn>",
        "<｜User｜>", "<｜Assistant｜>", "<｜end▁of▁sentence｜>",
        "<|user|>", "<|assistant|>", "<|system|>"
    )

    /** Longest marker, used to hold back a stream tail that may be the start of one. */
    private val MAX_MARKER_LEN = TURN_LEAK_MARKERS.maxOf { it.length }

    /** Index of the first leaked turn marker in [text], or -1. */
    fun findTurnLeak(text: String): Int {
        var best = -1
        for (m in TURN_LEAK_MARKERS) {
            val i = text.indexOf(m)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    /** [text] cut before the first leaked turn marker (whole text when there is none). */
    fun cutAtTurnLeak(text: String): String {
        val i = findTurnLeak(text)
        return if (i >= 0) text.substring(0, i) else text
    }

    /**
     * How much of a streaming buffer is safe to show: a trailing `<…` that could still grow into a
     * turn marker or `<think>` / `<tool_call>` tag is held back until the next tokens decide it.
     */
    fun safeStreamLength(text: String): Int {
        val lt = text.lastIndexOf('<')
        if (lt < 0 || text.length - lt > MAX_MARKER_LEN) return text.length
        val tail = text.substring(lt)
        if (tail.contains('>')) return text.length
        val candidates = TURN_LEAK_MARKERS + listOf("<think>", "</think>", TOOL_CALL_OPEN, "</tool_call>")
        return if (candidates.any { it.startsWith(tail) }) lt else text.length
    }

    /**
     * The part of a streaming on-device reply that is safe to show right now, untrimmed at the end so
     * consecutive deltas keep their spaces. A reply that starts as a bare JSON object is held back
     * entirely (it is usually a tool call written without tags) until [final].
     */
    fun streamVisibleText(raw: String, final: Boolean): String {
        var t = cutAtTurnLeak(stripThinking(raw))
        val tc = t.indexOf(TOOL_CALL_OPEN)
        if (tc >= 0) t = t.substring(0, tc)
        if (!final) {
            t = t.substring(0, safeStreamLength(t))
            if (t.trimStart().startsWith("{")) return ""
        } else {
            t = cleanConversationalText(t)
            if (parseToolCall(t) != null) return ""
        }
        return t.trimStart()
    }

    /**
     * Text of an on-device model's reply that may be shown/spoken: no thinking, no tool-call payload,
     * nothing from a leaked fake turn onwards.
     */
    fun cleanLocalModelText(rawText: String): String =
        cleanConversationalText(cutAtTurnLeak(stripThinking(rawText))).trim()

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
            // Unquoted maths in a value ("minutes": 60*24 - 6*60) is computed first, so Android's strict
            // JSON parser and lenient ones read the same call.
            val json = JSONObject(computeArithmeticValues(jsonString))
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
