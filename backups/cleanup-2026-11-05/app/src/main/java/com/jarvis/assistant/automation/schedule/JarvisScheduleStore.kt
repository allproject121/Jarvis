package com.jarvis.assistant.automation.schedule

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One thing Jarvis scheduled on the user's behalf (Clock-app timer, focus timer,
 * AlarmManager reminder/alarm). Only items Jarvis itself scheduled are known here.
 */
data class ScheduledTask(
    val kind: String,          // "timer" | "focus_timer" | "reminder" | "alarm"
    val label: String,
    val triggerAtMs: Long,
    val createdAtMs: Long,
    val source: String         // "set_reminder" | "start_focus_timer" | "create_reminder" | "set_alarm"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("label", label)
        .put("trigger_at", triggerAtMs)
        .put("created_at", createdAtMs)
        .put("source", source)

    companion object {
        fun fromJson(o: JSONObject): ScheduledTask? {
            val at = o.optLong("trigger_at", 0L)
            if (at <= 0L) return null
            return ScheduledTask(
                kind = o.optString("kind", "reminder"),
                label = o.optString("label", ""),
                triggerAtMs = at,
                createdAtMs = o.optLong("created_at", 0L),
                source = o.optString("source", "")
            )
        }
    }
}

/** Pure list logic for [JarvisScheduleStore] (unit-testable on the JVM). */
object ScheduledTaskFormat {
    const val MAX_ITEMS = 50

    fun upcoming(items: List<ScheduledTask>, nowMs: Long): List<ScheduledTask> =
        items.filter { it.triggerAtMs > nowMs }.sortedBy { it.triggerAtMs }

    fun add(items: List<ScheduledTask>, task: ScheduledTask, nowMs: Long): List<ScheduledTask> =
        (upcoming(items, nowMs) + task).sortedBy { it.triggerAtMs }.takeLast(MAX_ITEMS)

    fun encode(items: List<ScheduledTask>): String =
        JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()

    fun decode(raw: String?): List<ScheduledTask> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ScheduledTask.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "focus_timer" -> "Focus timer"
        "timer" -> "Timer"
        "alarm" -> "Alarm"
        else -> "Reminder"
    }

    private fun remaining(ms: Long): String {
        val totalMin = (ms + 59_999L) / 60_000L
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "in $m min"
            m == 0L -> "in $h h"
            else -> "in $h h $m min"
        }
    }

    fun describe(items: List<ScheduledTask>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val list = upcoming(items, nowMs)
        if (list.isEmpty()) {
            return "Jarvis has nothing scheduled right now (I only know about timers, reminders and alarms I set myself)."
        }
        val time = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US).withZone(zone)
        val lines = list.joinToString("\n") { t ->
            val label = if (t.label.isNotBlank()) " \"${t.label}\"" else ""
            "- ${kindLabel(t.kind)}$label at ${time.format(Instant.ofEpochMilli(t.triggerAtMs))} (${remaining(t.triggerAtMs - nowMs)})"
        }
        return "Jarvis has ${list.size} upcoming item(s):\n$lines\n" +
            "(Only things I scheduled are listed; a Clock-app timer cancelled by hand may still show here.)"
    }
}

/**
 * Small SharedPreferences record of what Jarvis scheduled, so get_scheduled_tasks can answer
 * honestly. Android gives apps no way to list Clock-app timers or other apps' alarms.
 */
object JarvisScheduleStore {
    private const val TAG = "JarvisScheduleStore"
    private const val PREFS = "jarvis_schedule_store"
    private const val KEY = "tasks"

    @Synchronized
    fun record(context: Context, kind: String, label: String, triggerAtMs: Long, source: String) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val current = ScheduledTaskFormat.decode(prefs.getString(KEY, null))
            val updated = ScheduledTaskFormat.add(current, ScheduledTask(kind, label, triggerAtMs, now, source), now)
            prefs.edit().putString(KEY, ScheduledTaskFormat.encode(updated)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not record scheduled task: ${e.message}")
        }
    }

    @Synchronized
    fun upcoming(context: Context): List<ScheduledTask> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return ScheduledTaskFormat.upcoming(ScheduledTaskFormat.decode(prefs.getString(KEY, null)), now)
    }
}
