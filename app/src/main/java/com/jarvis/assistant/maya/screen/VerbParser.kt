package com.jarvis.assistant.maya.screen

import org.json.JSONObject

/**
 * One decision of the screen-automation executor (ported from Maya's
 * `{"verb":...,"id":..,"text":..,"x":..,"y":..,"space":..,"direction":..,"realistic":..,
 * "condition":..,"timeout_ms":..,"say":..}` contract).
 */
data class ScreenVerb(
    val verb: String,
    val id: Int? = null,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val space: String = "device",
    val direction: String? = null,
    val realistic: Boolean = false,
    val condition: String? = null,
    val timeoutMs: Long = 20_000L,
    val say: String? = null,
    val raw: String = ""
) {
    /** Stable key used for repeat-action detection ("same verb + same target twice"). */
    fun actionKey(): String = when (verb) {
        "tap_id" -> "tap_id:${id ?: -1}"
        "tap_text", "open_app", "type" -> "$verb:${(text ?: "").trim().lowercase()}"
        "tap_coords" -> "tap_coords:${x ?: 0},${y ?: 0},$space"
        "swipe", "scroll" -> "$verb:${(direction ?: "down").lowercase()}"
        "wait_for" -> "wait_for:${(condition ?: text ?: "").trim().lowercase()}"
        else -> verb
    }

    fun describe(): String = when (verb) {
        "tap_id" -> "tap_id #${id ?: "?"}"
        "tap_text" -> "tap_text '${text ?: ""}'"
        "tap_coords" -> "tap_coords (${x ?: 0},${y ?: 0}) [$space]"
        "type" -> "type '${(text ?: "").take(60)}'" + (if (realistic) " (realistic)" else "")
        "swipe", "scroll" -> "$verb ${direction ?: "down"}"
        "open_app" -> "open_app '${text ?: ""}'"
        "wait_for" -> "wait_for '${condition ?: text ?: ""}' (≤${timeoutMs}ms)"
        "ask_user" -> "ask_user '${(text ?: "").take(80)}'"
        else -> verb
    }
}

object VerbParser {

    val VERBS: Set<String> = setOf(
        "tap_id", "tap_text", "tap_coords", "type", "swipe", "scroll", "open_app", "go_back", "go_home",
        "wait_for", "need_vision", "step_done", "task_done", "ask_user"
    )

    private val VERB_ALIASES = mapOf(
        "tap" to "tap_text", "click" to "tap_text", "click_text" to "tap_text", "tap_label" to "tap_text",
        "tap_element" to "tap_id", "click_id" to "tap_id", "tap_by_id" to "tap_id",
        "tap_coordinates" to "tap_coords", "tap_xy" to "tap_coords", "click_coords" to "tap_coords",
        "type_text" to "type", "input" to "type", "enter_text" to "type",
        "back" to "go_back", "home" to "go_home", "press_back" to "go_back", "press_home" to "go_home",
        "wait" to "wait_for", "wait_for_text" to "wait_for", "wait_for_screen_text" to "wait_for",
        "look" to "need_vision", "look_at_screen" to "need_vision", "vision" to "need_vision",
        "done" to "task_done", "finish" to "task_done", "complete" to "task_done", "finished" to "task_done",
        "ask" to "ask_user", "question" to "ask_user",
        "launch_app" to "open_app", "open" to "open_app",
        "scroll_down" to "scroll", "scroll_up" to "scroll", "swipe_up" to "swipe", "swipe_down" to "swipe",
        "swipe_left" to "swipe", "swipe_right" to "swipe"
    )

    /**
     * Finds the first balanced `{...}` object in [text], ignoring code fences and prose around it.
     * Returns null when there is no object.
     */
    fun extractJsonObject(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var s = text.trim()
        // strip ```json ... ``` fences (anywhere)
        s = s.replace(Regex("```[a-zA-Z]*"), "```")
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        // unbalanced: best effort — close it
        return s.substring(start) + "}".repeat(depth.coerceAtLeast(0))
    }

    /** Parses the model output into a [ScreenVerb]; null when no usable JSON/verb is found. */
    fun parse(raw: String?): ScreenVerb? {
        val jsonText = extractJsonObject(raw) ?: return parseBareVerb(raw)
        val obj = try { JSONObject(jsonText) } catch (_: Exception) { return parseBareVerb(raw) }
        var verb = firstString(obj, "verb", "action", "type_of_action", "command")?.trim()?.lowercase()?.replace(' ', '_')
            ?: return parseBareVerb(raw)
        verb = VERB_ALIASES[verb] ?: verb
        val text = firstString(obj, "text", "label", "value", "app", "app_name", "query", "message", "question")
        var id = optInt(obj, "id") ?: optInt(obj, "element_id") ?: optInt(obj, "n")
        if (verb == "tap_text" && id == null && text != null) {
            // "#7" or "7" given as text → tap_id
            val m = Regex("^#?(\\d{1,3})$").find(text.trim())
            if (m != null) { id = m.groupValues[1].toInt(); verb = "tap_id" }
        }
        if (verb == "tap_id" && id == null && text != null) {
            val m = Regex("#?(\\d{1,3})").find(text)
            if (m != null) id = m.groupValues[1].toInt()
        }
        if (verb == "tap_coords" && id != null && !obj.has("x")) {
            // model put an element id on tap_coords → treat as tap_id
            verb = "tap_id"
        }
        if (verb !in VERBS) {
            // unknown verb: try to salvage common intents
            verb = when {
                verb.contains("tap") || verb.contains("click") -> if (id != null) "tap_id" else "tap_text"
                verb.contains("type") || verb.contains("input") -> "type"
                verb.contains("scroll") -> "scroll"
                verb.contains("swipe") -> "swipe"
                verb.contains("open") -> "open_app"
                verb.contains("wait") -> "wait_for"
                verb.contains("vision") || verb.contains("look") -> "need_vision"
                verb.contains("done") || verb.contains("complete") -> "task_done"
                verb.contains("ask") -> "ask_user"
                verb.contains("back") -> "go_back"
                verb.contains("home") -> "go_home"
                else -> return null
            }
        }
        var direction = firstString(obj, "direction", "dir")?.trim()?.lowercase()
        if (direction == null && (verb == "scroll" || verb == "swipe")) {
            val rawVerb = firstString(obj, "verb", "action")?.lowercase() ?: ""
            direction = listOf("up", "down", "left", "right").firstOrNull { rawVerb.endsWith(it) } ?: "down"
        }
        val space = (firstString(obj, "space", "coord_space") ?: "device").trim().lowercase().let {
            if (it.startsWith("img") || it == "image" || it == "screenshot") "image" else "device"
        }
        val timeout = (optLong(obj, "timeout_ms") ?: optLong(obj, "timeout")?.let { t -> if (t < 1000) t * 1000 else t }
            ?: optLong(obj, "timeout_seconds")?.let { it * 1000 } ?: 20_000L).coerceIn(500L, 15 * 60_000L)
        return ScreenVerb(
            verb = verb,
            id = id,
            text = text,
            x = optInt(obj, "x"),
            y = optInt(obj, "y"),
            space = space,
            direction = direction,
            realistic = obj.optBoolean("realistic", false),
            condition = firstString(obj, "condition", "until", "wait_for") ?: if (verb == "wait_for") text else null,
            timeoutMs = timeout,
            say = firstString(obj, "say", "note", "comment"),
            raw = jsonText
        )
    }

    /** Splits a free-form plan ("1. open X\n2. tap Y" or "a; b; c") into steps. */
    fun splitSteps(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val byLines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (byLines.size > 1) byLines else raw.split(Regex(";|\\s+then\\s+|\\s+phir\\s+|→")).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { it.replace(Regex("^(\\d+[.)]|[-*•])\\s*"), "").trim() }.filter { it.isNotEmpty() }.take(25)
    }

    /** A bare word like `go_back` or `task_done` with no JSON at all. */
    private fun parseBareVerb(raw: String?): ScreenVerb? {
        val word = raw?.trim()?.lowercase()?.replace(Regex("[^a-z_ ]"), "")?.trim()?.replace(' ', '_') ?: return null
        val v = VERB_ALIASES[word] ?: word
        return if (v in VERBS && v in setOf("go_back", "go_home", "need_vision", "step_done", "task_done")) ScreenVerb(verb = v, raw = raw ?: "") else null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.opt(k)
                val s = when (v) {
                    is String -> v
                    is Number, is Boolean -> v.toString()
                    else -> v?.toString()
                }
                if (!s.isNullOrBlank()) return s
            }
        }
        return null
    }

    private fun optInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.opt(key)) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.trim().removePrefix("#").toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun optLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}
