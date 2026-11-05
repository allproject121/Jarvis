package com.jarvis.assistant.maya.screen

/** One entry of the combined tool catalog (registry packs + dispatcher built-ins). */
data class ToolCatalogEntry(
    val name: String,
    val description: String,
    val family: String,
    /** "param:type" or "param?:type" for optional. */
    val params: List<String> = emptyList()
)

data class ToolMatch(val entry: ToolCatalogEntry, val score: Int)

/**
 * Pure-Kotlin fuzzy search behind the `find_tool` tool (Maya's find_tool / find_tool_fuzzy).
 * Scores by name-token hits (strong), description hits (weak) and a small synonym table so
 * "torch" finds set_flashlight, "song" finds the YouTube/media tools, etc.
 */
object ToolFinder {

    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "torch" to listOf("flashlight"), "flash" to listOf("flashlight"), "light" to listOf("flashlight", "brightness"),
        "lamp" to listOf("flashlight"), "bright" to listOf("brightness"), "dim" to listOf("brightness"),
        "wifi" to listOf("wifi", "wi-fi"), "internet" to listOf("wifi", "website", "web"), "net" to listOf("wifi"),
        "bt" to listOf("bluetooth"), "sound" to listOf("volume", "media", "dnd"), "loud" to listOf("volume"),
        "mute" to listOf("volume", "dnd"), "silent" to listOf("dnd", "volume"), "quiet" to listOf("dnd"),
        "song" to listOf("youtube", "media", "play"), "music" to listOf("youtube", "media", "play"),
        "video" to listOf("youtube", "play"), "play" to listOf("media", "youtube"), "pause" to listOf("media"),
        "message" to listOf("whatsapp", "reply", "sms"), "msg" to listOf("whatsapp"), "chat" to listOf("whatsapp"),
        "text" to listOf("whatsapp", "type"), "phone" to listOf("call", "contact"), "dial" to listOf("call"),
        "ring" to listOf("call"), "number" to listOf("contact"), "alarm" to listOf("reminder", "timer"),
        "remind" to listOf("reminder"), "timer" to listOf("focus", "reminder"), "clock" to listOf("reminder"),
        "photo" to listOf("screenshot"), "picture" to listOf("screenshot"), "capture" to listOf("screenshot"),
        "screen" to listOf("screen", "screenshot", "inspect"), "see" to listOf("look", "screen", "inspect"),
        "look" to listOf("look", "screen"), "read" to listOf("read", "screen"), "paste" to listOf("clipboard"),
        "copy" to listOf("clipboard"), "note" to listOf("note"), "memo" to listOf("note"), "calendar" to listOf("calendar", "event"),
        "meeting" to listOf("calendar"), "weather" to listOf("weather"), "rain" to listOf("weather"), "temperature" to listOf("weather"),
        "battery" to listOf("battery", "event"), "charge" to listOf("battery", "charger"), "storage" to listOf("storage"),
        "space" to listOf("storage"), "home" to listOf("smart", "home"), "fan" to listOf("smart"), "bulb" to listOf("smart"),
        "app" to listOf("app", "open"), "launch" to listOf("open"), "start" to listOf("open"), "settings" to listOf("settings"),
        "setting" to listOf("settings"), "toggle" to listOf("settings", "switch"), "switch" to listOf("set", "settings"),
        "tap" to listOf("tap", "click"), "press" to listOf("tap", "click", "key"), "type" to listOf("type"),
        "search" to listOf("search", "web"), "google" to listOf("search", "chrome"), "browser" to listOf("website", "chrome"),
        "site" to listOf("website"), "url" to listOf("website"), "macro" to listOf("macro"), "routine" to listOf("macro"),
        "teach" to listOf("record", "macro"), "learn" to listOf("record", "macro"), "watch" to listOf("watch"),
        "alert" to listOf("watch", "event"), "notify" to listOf("watch", "event"), "tell" to listOf("watch"),
        "automate" to listOf("agent", "task"), "automation" to listOf("agent", "task"), "do" to listOf("agent"),
        "dnd" to listOf("dnd"), "disturb" to listOf("dnd"),
        "share" to listOf("share"), "send" to listOf("whatsapp", "share"), "memory" to listOf("memory"),
        "remember" to listOf("memory"), "forget" to listOf("memory"), "crypto" to listOf("crypto"), "bitcoin" to listOf("crypto"),
        "price" to listOf("crypto", "price"), "wiki" to listOf("wikipedia"), "traffic" to listOf("traffic"),
        "camera" to listOf("gods_eye", "camera"), "cctv" to listOf("gods_eye"), "radar" to listOf("gods_eye"),
        "usage" to listOf("usage", "stats"), "github" to listOf("github"), "repo" to listOf("github"), "clone" to listOf("github"),
        "webhook" to listOf("webhook"), "lead" to listOf("leads"), "customer" to listOf("leads"), "review" to listOf("review"),
        "model" to listOf("model"), "iss" to listOf("iss"), "space-station" to listOf("iss"), "aqi" to listOf("aqi", "weather"),
        "pollution" to listOf("aqi"), "hotspot" to listOf("settings", "hotspot"), "nfc" to listOf("settings"),
        "airplane" to listOf("settings", "airplane", "event"), "flight" to listOf("airplane")
    )

    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "please", "can", "you", "karo", "kar", "do", "kardo", "mere", "mera",
        "meri", "liye", "hai", "ko", "ka", "ki", "ke", "se", "me", "mein", "par", "pe", "ek", "jo", "kya", "turn", "make",
        "want", "need", "want", "some", "any", "just", "tool"
    )

    fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9\\-]+")).map { it.trim() }.filter { it.length > 1 && it !in STOP }

    fun search(query: String, catalog: List<ToolCatalogEntry>, limit: Int = 6): List<ToolMatch> {
        val words = tokens(query).distinct()
        if (words.isEmpty()) return emptyList()
        val expanded = LinkedHashMap<String, Int>() // word → weight
        for (w in words) {
            expanded[w] = 3
            SYNONYMS[w]?.forEach { s -> if (!expanded.containsKey(s)) expanded[s] = 2 }
            // light stemming: "opening"→"open", "messages"→"message"
            val stem = stem(w)
            if (stem != w && !expanded.containsKey(stem)) expanded[stem] = 2
        }
        val results = ArrayList<ToolMatch>()
        for (e in catalog) {
            val nameTokens = e.name.lowercase().split('_').filter { it.isNotBlank() }
            val descLower = e.description.lowercase()
            val familyLower = e.family.lowercase()
            var score = 0
            var nameHits = 0
            for ((w, weight) in expanded) {
                when {
                    nameTokens.contains(w) -> { score += 5 * weight; nameHits++ }
                    e.name.lowercase().contains(w) -> { score += 3 * weight; nameHits++ }
                    w.length >= 3 && nameTokens.any { it.startsWith(w) || (w.startsWith(it) && it.length > 3) } -> { score += 2 * weight; nameHits++ }
                }
                if (descLower.contains(w)) score += weight
                if (familyLower == w) score += weight
            }
            // exact name query
            if (e.name.equals(query.trim(), ignoreCase = true)) score += 100
            if (nameHits >= 2) score += 6
            if (score > 0) results.add(ToolMatch(e, score))
        }
        return results.sortedWith(compareByDescending<ToolMatch> { it.score }.thenBy { it.entry.name }).take(limit)
    }

    private fun stem(w: String): String = when {
        w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("es") && w.length > 4 -> w.dropLast(2)
        w.endsWith("s") && w.length > 3 -> w.dropLast(1)
        else -> w
    }

    /** `set_flashlight(enabled:boolean) — Turns the phone flashlight ...` */
    fun usageLine(e: ToolCatalogEntry, maxDesc: Int = 150): String {
        val desc = e.description.replace(Regex("\\s+"), " ").trim()
        val short = if (desc.length > maxDesc) desc.substring(0, maxDesc - 1) + "…" else desc
        return e.name + "(" + e.params.joinToString(", ") + ")" + (if (short.isNotEmpty()) " — $short" else "")
    }

    /** Family overview shown when nothing matched (Maya: "your words missed, not the capability"). */
    fun familiesSummary(catalog: List<ToolCatalogEntry>): String {
        val byFamily = catalog.groupBy { it.family.ifBlank { "general" } }
        return byFamily.entries.sortedBy { it.key }.joinToString("\n") { (fam, list) ->
            "$fam: " + list.map { it.name }.sorted().take(14).joinToString(", ") + (if (list.size > 14) ", …" else "")
        }
    }
}
