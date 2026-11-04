package com.jarvis.assistant.maya.screen

import org.json.JSONArray
import org.json.JSONObject

/**
 * A standing "X ho to batana" watch (Maya `watch_for`). Pure Kotlin + org.json so the
 * persistence round-trip is unit-testable; [WatcherEngine] arms them on the device.
 */
data class Watch(
    val id: Int,
    /** screen_text | app_opens | notification | screen_change | battery | charger | bluetooth | wifi | headset */
    val kind: String,
    val value: String,
    val reason: String,
    val createdAt: Long,
    /** For screen_change: the signature hash at arm time; for battery: unused. */
    val baseline: String = ""
) {
    fun describe(): String {
        val what = when (kind) {
            "screen_text" -> "screen pe '$value' dikhe"
            "app_opens" -> "'$value' app khule"
            "notification" -> if (value.isBlank()) "koi notification aaye" else "notification me '$value' aaye"
            "screen_change" -> "screen badle"
            "battery" -> "battery $value"
            "charger" -> if (value.isBlank()) "charger lage/hate" else "charger $value"
            "bluetooth" -> if (value.isBlank()) "bluetooth device connect/disconnect ho" else "bluetooth $value"
            "wifi" -> if (value.isBlank()) "wifi connect/disconnect ho" else "wifi $value"
            "headset" -> if (value.isBlank()) "headphone lage/hate" else "headphone $value"
            else -> "$kind $value"
        }
        return "#$id $what" + (if (reason.isNotBlank()) " → $reason" else "")
    }
}

/** Parsed battery condition: fire when level crosses [threshold] going down ([below]) or up. */
data class BatteryCondition(val threshold: Int, val below: Boolean)

object WatchCodec {

    val KINDS: List<String> = listOf(
        "screen_text", "app_opens", "notification", "screen_change", "battery", "charger", "bluetooth", "wifi", "headset"
    )

    private val KIND_ALIASES = mapOf(
        "text" to "screen_text", "screen" to "screen_text", "word" to "screen_text", "words" to "screen_text",
        "app" to "app_opens", "app_open" to "app_opens", "app_opened" to "app_opens", "open_app" to "app_opens",
        "notif" to "notification", "notifications" to "notification", "change" to "screen_change",
        "screen_changes" to "screen_change", "changed" to "screen_change", "battery_level" to "battery",
        "power" to "charger", "charging" to "charger", "plug" to "charger", "bt" to "bluetooth", "wi-fi" to "wifi",
        "wlan" to "wifi", "headphone" to "headset", "headphones" to "headset", "earphone" to "headset"
    )

    fun normalizeKind(raw: String?): String? {
        val k = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
        if (k in KINDS) return k
        return KIND_ALIASES[k] ?: KIND_ALIASES[k.replace("_", "-")]
    }

    /**
     * "20" / "below 20" / "<20" / "under 20%" → below 20; "above 80" / ">80" → above 80;
     * "full" → above 100 (reached); "low" → below 20; "critical" → below 10.
     */
    fun parseBatteryCondition(value: String?): BatteryCondition? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        if (v.contains("full") || v.contains("100")) return BatteryCondition(100, false)
        if (v == "low" || v.contains("kam")) return BatteryCondition(20, true)
        if (v.contains("critical")) return BatteryCondition(10, true)
        val num = Regex("(\\d{1,3})").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val above = v.contains("above") || v.contains(">") || v.contains("over") || v.contains("upar") || v.contains("zyada") || v.contains("reach")
        return BatteryCondition(num.coerceIn(1, 100), !above)
    }

    fun encode(watches: List<Watch>): String {
        val arr = JSONArray()
        for (w in watches) {
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("kind", w.kind)
                put("value", w.value)
                put("reason", w.reason)
                put("createdAt", w.createdAt)
                put("baseline", w.baseline)
            })
        }
        return JSONObject().put("version", 1).put("watches", arr).toString()
    }

    fun decode(json: String?): List<Watch> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("watches") ?: return emptyList()
            val out = ArrayList<Watch>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val kind = normalizeKind(o.optString("kind")) ?: continue
                out.add(
                    Watch(
                        id = o.optInt("id", i + 1),
                        kind = kind,
                        value = o.optString("value", ""),
                        reason = o.optString("reason", ""),
                        createdAt = o.optLong("createdAt", 0L),
                        baseline = o.optString("baseline", "")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
