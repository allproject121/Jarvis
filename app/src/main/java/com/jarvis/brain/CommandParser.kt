package com.jarvis.brain

import org.json.JSONObject

data class JarvisCommand(val action: String, val params: Map<String, Any>) {
    fun str(k: String) = params[k]?.toString().orEmpty()
    fun int(k: String, def: Int = 0) = (params[k] as? Number)?.toInt() ?: str(k).toIntOrNull() ?: def
}

/**
 * Deterministic parser. Pehle fast regex rules (<1ms, no model overhead), phir model ka JSON.
 * Model galat JSON de to bhi crash nahi — null return karta hai.
 */
object CommandParser {

    private val rules: List<Pair<Regex, (MatchResult) -> JarvisCommand>> = listOf(
        // Royal Greetings & Salutations (<1ms instant response)
        Regex("""(?i)^\s*(hello|hi|hey|namaste|pranam|good\s*(?:morning|afternoon|evening|night))\s*(?:jarvis)?\s*[!.]*\s*$""") to { _ ->
            cmd("answer", "text" to "Hello Sir! JARVIS online and all offline systems operational. How may I assist you?")
        },

        // Instant Deterministic Math Evaluator (<1ms, exact precision)
        Regex("""(?i)^\s*(?:what is|calculate|solve)?\s*(\d+(?:\.\d+)?)\s*([\+\-\*\/xX]|plus|minus|multiplied by|times|into|divided by|by)\s*(\d+(?:\.\d+)?)\s*(?:kitna hota hai|\?)?\s*$""") to { m ->
            val a = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val op = m.groupValues[2].lowercase().trim()
            val b = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val res = when {
                op == "+" || op == "plus" -> a + b
                op == "-" || op == "minus" -> a - b
                op == "*" || op == "x" || op == "into" || op == "times" || op.contains("multiplied") -> a * b
                op == "/" || op == "by" || op.contains("divided") -> if (b != 0.0) a / b else Double.NaN
                else -> Double.NaN
            }
            val formattedRes = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
            cmd("answer", "text" to "$formattedRes")
        },

        // Flashlight rules
        Regex("""(?i)^\s*(torch|flashlight)\s*$""") to { _ -> cmd("flashlight", "state" to "on") },
        Regex("""(?i)\b(torch|flash ?light)\b.*\b(on|chalu|jala|start)""") to { _ -> cmd("flashlight", "state" to "on") },
        Regex("""(?i)\b(torch|flash ?light)\b.*\b(off|band|bujha|stop)""") to { _ -> cmd("flashlight", "state" to "off") },

        // Device & Battery diagnostics
        Regex("""(?i)\b(battery|device info|device status|system status|phone status)\b""") to { _ -> cmd("device_info") },

        // Volume controls
        Regex("""(?i)\bvolume\s*(\d{1,3})\s*%?""") to { m -> cmd("volume", "level" to (m.groupValues[1].toIntOrNull() ?: 50)) },
        Regex("""(?i)\b(aawaz|volume)\s*(badhao|up|increase)""") to { _ -> cmd("volume", "level" to 80) },
        Regex("""(?i)\b(aawaz|volume)\s*(kam karo|down|decrease)""") to { _ -> cmd("volume", "level" to 30) },

        // WhatsApp direct messaging
        Regex("""(?i)\b(?:whatsapp|wa)\s*(?:par|pe)?\s*(.+?)\s*(?:ko)?\s*(?:message|msg)?\s*(?:karo|bhejo|send)\s*(?:ki)?\s*(.+)""") to { m ->
            cmd("whatsapp_send", "contact" to m.groupValues[1].trim(), "message" to m.groupValues[2].trim())
        },

        // Wi-Fi rules
        Regex("""(?i)\b(wifi|wi-fi)\s*(?:panel|settings|kholo|open|on|off)?""") to { _ -> cmd("wifi") },

        // Bluetooth rules
        Regex("""(?i)\bbluetooth\s*(?:settings|kholo|open|on|off)?""") to { _ -> cmd("bluetooth") },

        // Timer rules: "timer 30 second", "timer 2 minute", "30 second ka timer lagao"
        Regex("""(?i)\btimer\s*(?:lagao|set|karo)?\s*(\d+)\s*(sec|second|min|minute)s?""") to { m ->
            val num = m.groupValues[1].toIntOrNull() ?: 30
            val unit = m.groupValues[2].lowercase()
            val totalSeconds = if (unit.startsWith("min")) num * 60 else num
            cmd("set_timer", "seconds" to totalSeconds)
        },
        Regex("""(?i)(\d+)\s*(sec|second|min|minute)s?\s*(?:ka)?\s*timer""") to { m ->
            val num = m.groupValues[1].toIntOrNull() ?: 30
            val unit = m.groupValues[2].lowercase()
            val totalSeconds = if (unit.startsWith("min")) num * 60 else num
            cmd("set_timer", "seconds" to totalSeconds)
        },

        // Alarm rules: "alarm 7 baje", "alarm 6:30 am", "set alarm 8"
        Regex("""(?i)\balarm\s*(?:lagao|set|karo)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje)?""") to { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: 7
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            cmd("set_alarm", "hour" to hour, "minute" to min, "label" to "Jarvis Alarm")
        },

        // Notes: "note likho buy milk", "save note meeting at 5", "note banao groceries"
        Regex("""(?i)\b(?:note|likho|save note)\s*(?:likho|banao|kar do)?\s*[:,-]?\s*(.+)""") to { m ->
            cmd("note", "text" to m.groupValues[1].trim())
        },

        // Calling: "call papa", "phone lagao shivam ko", "call 9876543210"
        Regex("""(?i)\b(?:call|phone lagao|phone milao)\s*(?:karo)?\s*(?:to|pe)?\s*(.+)""") to { m ->
            cmd("call", "contact" to m.groupValues[1].replace(Regex("""(?i)\s*(?:ko|par|pe)$"""), "").trim())
        },

        // Media controls: "play song", "pause music", "next track", "gana roko"
        Regex("""(?i)\b(play|chalao)\s*(?:gana|song|music|track)?""") to { _ -> cmd("media", "command" to "play") },
        Regex("""(?i)\b(pause|stop|roko|band karo)\s*(?:gana|song|music|track)""") to { _ -> cmd("media", "command" to "pause") },
        Regex("""(?i)\b(next|agla)\s*(?:gana|song|music|track)""") to { _ -> cmd("media", "command" to "next") },
        Regex("""(?i)\b(previous|pichhla)\s*(?:gana|song|music|track)""") to { _ -> cmd("media", "command" to "previous") },

        // App launch rules
        Regex("""(?i)^(?:open|launch|kholo|chalao)\s+(.+)$""") to { m -> cmd("open_app", "app" to m.groupValues[1].trim()) },
        Regex("""(?i)^(.+?)\s+(?:open|kholo|khol do|launch)\s*(?:karo|do)?$""") to { m -> cmd("open_app", "app" to m.groupValues[1].trim()) },
    )

    fun quickMatch(text: String): JarvisCommand? =
        rules.firstNotNullOfOrNull { (re, f) -> re.find(text.trim())?.let(f) }

    fun extractThinking(raw: String): String {
        val start = raw.indexOf("<think>")
        val end = raw.indexOf("</think>")
        return if (start >= 0 && end > start) {
            raw.substring(start + 7, end).trim()
        } else ""
    }

    fun parse(raw: String): JarvisCommand? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = JSONObject(raw.substring(start, end + 1))
            val action = o.getString("action")
            val p = o.optJSONObject("params") ?: JSONObject()
            val map = p.keys().asSequence().associateWith { p.get(it) }
            JarvisCommand(action, map)
        }.getOrNull()
    }

    private fun cmd(a: String, vararg p: Pair<String, Any>) = JarvisCommand(a, p.toMap())
}
