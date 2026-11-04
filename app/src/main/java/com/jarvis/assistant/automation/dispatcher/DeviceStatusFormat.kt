package com.jarvis.assistant.automation.dispatcher

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure (Android-free) formatting / parsing helpers for the Batch-4 device tools
 * (battery, storage, WhatsApp notification reading, calendar time, DND mode).
 * Android constants are mirrored as literals so the JVM unit tests don't need android.jar values.
 */
object DeviceStatusFormat {

    // ── Sizes & durations ─────────────────────────────────────────────────

    /** "512 B", "3.4 KB", "12.3 GB" (1024-based). */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unknown"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0
            idx++
        }
        return String.format(Locale.US, "%.1f %s", value, units[idx])
    }

    /** "1 h 5 min", "25 min", "under a minute". */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0 min"
        val totalMin = ms / 60_000L
        if (totalMin == 0L) return "under a minute"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────

    fun usedPercent(totalBytes: Long, freeBytes: Long): Int {
        if (totalBytes <= 0) return 0
        val used = (totalBytes - freeBytes).coerceAtLeast(0)
        return ((used * 100.0) / totalBytes).toInt().coerceIn(0, 100)
    }

    fun storageSummary(totalBytes: Long, freeBytes: Long, appCacheBytes: Long): String {
        val used = (totalBytes - freeBytes).coerceAtLeast(0)
        return "Internal storage: ${formatBytes(used)} used of ${formatBytes(totalBytes)} " +
            "(${usedPercent(totalBytes, freeBytes)}% full), ${formatBytes(freeBytes)} free. " +
            "Jarvis cache: ${formatBytes(appCacheBytes)}."
    }

    // ── Battery ───────────────────────────────────────────────────────────

    /** Mirrors BatteryManager.BATTERY_PLUGGED_* (AC=1, USB=2, WIRELESS=4, DOCK=8). */
    fun plugLabel(plugged: Int): String? = when (plugged) {
        1 -> "AC charger"
        2 -> "USB"
        4 -> "wireless charger"
        8 -> "dock"
        else -> null
    }

    /** Mirrors BatteryManager.BATTERY_HEALTH_* values. */
    fun healthLabel(health: Int): String = when (health) {
        2 -> "good"
        3 -> "overheating"
        4 -> "dead"
        5 -> "over voltage"
        6 -> "failure"
        7 -> "cold"
        else -> "unknown"
    }

    /** BatteryManager.EXTRA_TEMPERATURE is in tenths of a degree Celsius; null when not reported. */
    fun temperatureCelsius(tenths: Int): Double? = if (tenths <= 0 || tenths == Int.MIN_VALUE) null else tenths / 10.0

    fun batterySummary(
        percent: Int,
        charging: Boolean,
        full: Boolean,
        plugLabel: String?,
        temperatureC: Double?,
        health: String,
        timeToFullMs: Long?
    ): String {
        val sb = StringBuilder()
        sb.append(if (percent in 0..100) "Battery is at $percent%" else "Battery level is unknown")
        when {
            full -> sb.append(", fully charged")
            charging -> sb.append(", charging").append(if (plugLabel != null) " via $plugLabel" else "")
            plugLabel != null -> sb.append(", plugged into $plugLabel but not charging")
            else -> sb.append(", not charging")
        }
        sb.append(".")
        if (charging && !full && timeToFullMs != null && timeToFullMs > 0) {
            sb.append(" About ${formatDuration(timeToFullMs)} to full.")
        }
        if (temperatureC != null) sb.append(String.format(Locale.US, " Temperature %.1f°C.", temperatureC))
        if (health != "unknown") sb.append(" Health: $health.")
        return sb.toString()
    }

    // ── Do Not Disturb ────────────────────────────────────────────────────

    /** Normalizes a DND mode argument to "priority" | "alarms" | "total_silence"; null when unrecognized. */
    fun normalizeDndMode(raw: String?): String? {
        val m = raw?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_')?.replace('-', '_').orEmpty()
        return when (m) {
            "", "priority", "priority_only", "important" -> "priority"
            "alarms", "alarms_only", "alarm" -> "alarms"
            "total_silence", "silence", "none", "total", "silent" -> "total_silence"
            else -> null
        }
    }

    // ── Calendar time ─────────────────────────────────────────────────────

    private val LOCAL_PATTERNS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME
    )

    /**
     * Parses "2026-11-05T15:30:00+05:30", "2026-11-05T15:30:00Z", "2026-11-05 15:30" or
     * "2026-11-05T15:30" (local [zone]) into epoch millis. Null when not understood.
     */
    fun parseEventStart(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text).toInstant().toEpochMilli() }
        for (fmt in LOCAL_PATTERNS) {
            runCatching { return LocalDateTime.parse(text, fmt).atZone(zone).toInstant().toEpochMilli() }
        }
        return null
    }

    // ── WhatsApp notifications ────────────────────────────────────────────

    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    data class MessageItem(
        val packageName: String,
        val sender: String,
        val text: String,
        val timestamp: Long
    )

    /**
     * WhatsApp messages only (both apps), newest first, optional case-insensitive sender filter;
     * drops WhatsApp's own group-summary notifications ("WhatsApp: 5 messages from 2 chats").
     */
    fun filterWhatsApp(items: List<MessageItem>, contact: String?, limit: Int): List<MessageItem> {
        val wanted = contact?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return items.asSequence()
            .filter { it.packageName in WHATSAPP_PACKAGES }
            .filterNot { isWhatsAppSummary(it) }
            .filter { wanted.isEmpty() || it.sender.lowercase(Locale.ROOT).contains(wanted) }
            .sortedByDescending { it.timestamp }
            .take(limit.coerceIn(1, 25))
            .toList()
    }

    private fun isWhatsAppSummary(item: MessageItem): Boolean {
        val s = item.sender.trim().lowercase(Locale.ROOT)
        if (s == "whatsapp" || s == "whatsapp business") return true
        return Regex("^\\d+ (new )?messages? from \\d+ chats?$").matches(item.text.trim().lowercase(Locale.ROOT))
    }
}
