package com.jarvis.assistant.ai.local

import org.json.JSONObject
import java.util.Locale

/**
 * Picks the few tools the on-device brain needs for ONE request out of Jarvis' full tool list
 * (~280 tools: built-ins + Maya packs + dynamic tools), and writes them as one short line each.
 *
 * Why: the phone reads prompts at only a few tokens per second, so the brain cannot be shown every
 * tool (or even full JSON schemas). Before this it only ever saw a fixed list of 12 tools, so anything
 * outside that list (alarm, notes, WhatsApp read, screen, news, calendar...) was impossible offline.
 * Matching is keyword based (English + Hinglish), deterministic and instant.
 */
object OfflineToolRouter {

    /** Hinglish / short words -> the English words tool names and descriptions use. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "kholo" to listOf("open", "launch", "app"), "khol" to listOf("open", "app"), "open" to listOf("open", "launch", "app"),
        "chalao" to listOf("play", "open", "start"), "bajao" to listOf("play", "music", "song"), "play" to listOf("play", "music", "video"),
        "band" to listOf("off", "close", "stop", "disable"), "bujhao" to listOf("off", "disable"), "jalao" to listOf("on", "enable"),
        "chalu" to listOf("on", "enable", "start"), "on" to listOf("on", "enable"), "off" to listOf("off", "disable"),
        "bhejo" to listOf("send", "message"), "bhej" to listOf("send", "message"), "msg" to listOf("message"),
        "message" to listOf("message", "sms"), "sms" to listOf("sms", "message"),
        "call" to listOf("call", "dial", "phone"), "phone" to listOf("call", "phone"), "dial" to listOf("call", "dial"),
        "gaana" to listOf("song", "music", "play"), "gana" to listOf("song", "music", "play"), "gaane" to listOf("song", "music", "play"),
        "song" to listOf("song", "music", "play"), "music" to listOf("music", "play", "media"),
        "awaz" to listOf("volume", "sound"), "aawaz" to listOf("volume", "sound"), "volume" to listOf("volume", "sound"),
        "roshni" to listOf("brightness", "screen"), "brightness" to listOf("brightness"),
        "mausam" to listOf("weather", "temperature"), "weather" to listOf("weather"), "barish" to listOf("weather", "rain"),
        "khabar" to listOf("news"), "khabre" to listOf("news"), "news" to listOf("news", "headlines"),
        "yaad" to listOf("remind", "reminder", "remember", "memory"), "reminder" to listOf("reminder", "remind"),
        "alarm" to listOf("alarm", "wake"), "uthana" to listOf("alarm", "wake"), "timer" to listOf("timer", "focus"),
        "samay" to listOf("time"), "time" to listOf("time", "clock"), "baje" to listOf("time", "alarm"),
        "tareekh" to listOf("date", "calendar"), "date" to listOf("date", "calendar"),
        "calendar" to listOf("calendar", "event", "meeting"), "meeting" to listOf("calendar", "event", "meeting"),
        "photo" to listOf("photo", "camera", "picture"), "tasveer" to listOf("photo", "camera", "picture"),
        "selfie" to listOf("camera", "photo", "selfie"), "camera" to listOf("camera", "photo"),
        "dhundo" to listOf("search", "find"), "dhoondo" to listOf("search", "find"), "khojo" to listOf("search", "find"),
        "search" to listOf("search", "find", "web"), "google" to listOf("search", "web"),
        "note" to listOf("note", "notes", "save"), "likho" to listOf("write", "note", "type"), "padho" to listOf("read"),
        "screen" to listOf("screen", "display"), "location" to listOf("location", "gps"), "kahan" to listOf("location", "where"),
        "rasta" to listOf("directions", "maps", "navigate"), "map" to listOf("maps", "directions"),
        "payment" to listOf("pay", "payment", "upi"), "paise" to listOf("pay", "payment", "money"),
        "contact" to listOf("contact", "contacts"), "number" to listOf("contact", "phone", "number"),
        "battery" to listOf("battery", "charge"), "charge" to listOf("battery", "charge"),
        "wifi" to listOf("wifi", "network"), "internet" to listOf("wifi", "network", "data"),
        "bluetooth" to listOf("bluetooth"), "torch" to listOf("flashlight", "torch"), "flash" to listOf("flashlight"),
        "translate" to listOf("translate", "translation"), "anuvad" to listOf("translate"),
        "email" to listOf("email", "mail"), "mail" to listOf("email", "mail"),
        "whatsapp" to listOf("whatsapp", "message"), "youtube" to listOf("youtube", "video", "play"),
        "settings" to listOf("settings", "open"), "silent" to listOf("dnd", "silent", "ringer"),
        "disturb" to listOf("dnd", "disturb"), "dnd" to listOf("dnd", "disturb"),
        "storage" to listOf("storage", "space"), "notification" to listOf("notification", "notifications"),
        "calculate" to listOf("calculate", "math"), "hisab" to listOf("calculate", "math"),
        "copy" to listOf("clipboard", "copy"), "paste" to listOf("clipboard", "paste"),
        "wallpaper" to listOf("wallpaper"),
        "screenshot" to listOf("screenshot", "screen", "capture")
    )

    private val STOP = setOf(
        "the", "a", "an", "to", "of", "and", "or", "for", "in", "is", "it", "me", "my", "i", "you", "your",
        "karo", "kar", "do", "de", "dena", "dijiye", "hai", "hain", "ho", "mujhe", "mere", "mera", "meri",
        "ki", "ka", "ke", "ko", "se", "par", "pe", "please", "jarvis", "sir", "kya", "tum", "aap", "ek",
        "yeh", "ye", "wo", "woh", "bhi", "abhi", "zara", "jara", "sakte", "sakta", "can", "with", "this", "that"
    )

    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in STOP }

    /** Query words plus their English expansions. */
    private fun expand(query: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (t in tokens(query)) {
            out += t
            SYNONYMS[t]?.let { out += it }
        }
        return out
    }

    /**
     * Tool-name words that say nothing about WHAT a tool is for. The audit showed "kitna hota hai"
     * (-> get/status) pulling get_wifi_status, get_bluetooth_status, get_task_status... into a maths
     * question: +180 prompt tokens, +30 s, no benefit. Only specific name words count now.
     */
    private val GENERIC_NAME_PARTS = setOf(
        "get", "set", "status", "info", "current", "check", "toggle", "enable", "disable", "start", "stop",
        "list", "run", "do", "make", "open", "app", "device", "data", "show", "my", "all", "new", "by", "id"
    )

    private fun specificParts(toolName: String): List<String> =
        toolName.lowercase(Locale.ROOT).split('_').filter { it.length >= 2 && it !in GENERIC_NAME_PARTS }

    private fun partMatches(word: String, part: String): Int = when {
        word == part -> 3
        word.length >= 4 && part.length >= 4 && (part.startsWith(word) || word.startsWith(part)) -> 2
        else -> 0
    }

    /** Name score (specific words only) and description score. */
    private fun score(schema: JSONObject, words: Set<String>): Pair<Int, Int> {
        val fn = schema.optJSONObject("function") ?: return 0 to 0
        val parts = specificParts(fn.optString("name"))
        val descWords = tokens(fn.optString("description").take(160)).toSet()
        var name = 0
        var desc = 0
        for (w in words) {
            name += parts.maxOfOrNull { partMatches(w, it) } ?: 0
            if (w in descWords) desc += 1
        }
        return name to desc
    }

    /**
     * Up to [max] tools relevant to [query], best first; empty for plain chat or maths. A tool needs a
     * SPECIFIC name word matching the request. Tools in [exclude] (the always-present core set) are
     * skipped, and so are look-alikes of a core tool the request already matches (get_battery next to
     * get_battery_status confused the model into writing broken JSON).
     */
    fun select(query: String, schemas: List<JSONObject>, max: Int = 6, exclude: Set<String> = emptySet()): List<JSONObject> {
        val words = expand(query)
        if (words.isEmpty()) return emptyList()
        val coveredByCore = exclude.flatMap { specificParts(it) }
            .filter { part -> words.any { partMatches(it, part) > 0 } }
            .toSet()
        return schemas.asSequence()
            .filter { (it.optJSONObject("function")?.optString("name") ?: "") !in exclude }
            // Keep a tool only if one of its matching name words is NOT already covered by a core tool.
            .filter { s ->
                specificParts(s.optJSONObject("function")?.optString("name").orEmpty())
                    .any { part -> part !in coveredByCore && words.any { partMatches(it, part) > 0 } }
            }
            .map { it to score(it, words) }
            .filter { it.second.first >= 2 && it.second.first + it.second.second >= 3 }
            .sortedByDescending { it.second.first * 2 + it.second.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    /**
     * One short line per tool: `name(arg: type, opt?: type) - first sentence of description`.
     * About a fifth of the tokens of the JSON schema, which the brain follows just as well.
     */
    fun toLine(schema: JSONObject): String {
        val fn = schema.optJSONObject("function") ?: return ""
        val params = fn.optJSONObject("parameters")
        val props = params?.optJSONObject("properties")
        val required = params?.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val args = buildList {
            props?.keys()?.forEach { k ->
                val type = props.optJSONObject(k)?.optString("type").orEmpty().ifBlank { "string" }
                add(if (k in required) "$k: $type" else "$k?: $type")
            }
        }.joinToString(", ")
        val desc = fn.optString("description").split(Regex("(?<=[.!?])\\s")).firstOrNull().orEmpty().take(90)
        return "- ${fn.optString("name")}($args) - $desc"
    }
}
