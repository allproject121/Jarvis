package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import com.jarvis.assistant.androllm.permissions.Feature
import com.jarvis.assistant.androllm.permissions.PermissionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun notificationsBlocked(context: Context, what: String): ToolResult =
    ToolResult.Failure(
        PermissionManager.getInstance(context).missingMessage(
            Feature.NOTIFICATIONS, "$what is delivered as a notification."
        ),
        retryable = false
    )

/**
 * Schedules a one-shot reminder that fires a notification at the requested
 * time ("remind me to call the dentist tomorrow at 10"). Uses the system
 * AlarmManager; exact timing falls back to inexact when the OS blocks it.
 *
 * (Complements Jarvis' set_reminder, which starts a countdown timer in the
 * Clock app; this one fires at a wall-clock time as a Jarvis notification.)
 */
class ReminderTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Schedule a wall-clock reminder notification for a specific date/time, e.g. 'tomorrow 9am', 'on Friday at 6pm', an ISO time or epoch millis. NOT for relative countdowns like 'in N minutes' — use set_reminder for those.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("when") {
                    put("type", "string")
                    put("description", "Date/time to fire, e.g. 'tomorrow 9am', 'Friday 18:00' or an ISO time (not 'in N minutes' — that is set_reminder)")
                }
            }
            putJsonArray("required") { add("text"); add("when") }
        },
        permission = ToolPermission.ALARMS,
        category = ToolCategory.PRODUCTIVITY
    )

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "message", "reminder")
            ?: return ToolResult.Failure("Missing required argument: text")
        val whenRaw = ToolArgs.str(arguments, "when", "time", "at")
        // Relative phrases first: "in 20 minutes" must not be read as a clock time.
        val millis = parseRelative(whenRaw) ?: whenRaw?.let { ToolTime.parseMillis(it) }

        if (millis == null || millis <= System.currentTimeMillis()) {
            return ToolResult.Failure("Reminder time '${whenRaw ?: ""}' is missing or in the past.")
        }
        if (!ReminderNotifications.canNotify(context)) return notificationsBlocked(context, "A reminder")
        return runCatching {
            val ok = ReminderNotifications.schedule(
                context, millis, text, ReminderNotifications.REMINDER_CHANNEL, isAlarm = false
            )
            if (!ok) {
                ToolResult.Failure("Android refused to schedule the reminder.")
            } else {
                ToolResult.Success(
                    summary = "Reminder set for ${fmt.format(Date(millis))}: \"$text\".",
                    data = buildJsonObject {
                        put("text", text)
                        put("when", millis)
                        put("status", "scheduled")
                    }
                )
            }
        }.getOrElse {
            ToolResult.Failure("Could not schedule the reminder: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** Handles natural phrases: "in 20 minutes", "in 2 hours", "in 30 seconds". */
    private fun parseRelative(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.lowercase().trim()
        if (!t.contains("in ")) return null
        val m = Regex("""in\s+(\d+)\s*(second|sec|minute|min|hour|hr|day)s?""").find(t) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "second", "sec" -> 1000L
            "minute", "min" -> 60_000L
            "hour", "hr" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        }
        return System.currentTimeMillis() + amount * mult
    }
}

/**
 * Sets an alarm at a wall-clock time ("set an alarm for 7 AM"). Same
 * mechanism as [ReminderTool], with its own high-importance alarm channel.
 */
class AlarmTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm that rings at a clock time. Provide the time ('07:00', '7 AM', ISO time or epoch millis) and an optional label.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("time") {
                    put("type", "string")
                    put("description", "Alarm time, e.g. '07:00' or '7 AM'")
                }
                putJsonObject("label") { put("type", "string") }
            }
            putJsonArray("required") { add("time") }
        },
        permission = ToolPermission.ALARMS,
        category = ToolCategory.PRODUCTIVITY
    )

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val timeRaw = ToolArgs.str(arguments, "time", "when", "at")
            ?: return ToolResult.Failure("Missing required argument: time")
        val label = ToolArgs.str(arguments, "label", "name").orEmpty().ifBlank { "Alarm" }
        val millis = ToolTime.parseMillis(timeRaw)
            ?: return ToolResult.Failure("Could not understand alarm time '$timeRaw'.")
        if (millis <= System.currentTimeMillis()) {
            return ToolResult.Failure("That alarm time is in the past.")
        }
        if (!ReminderNotifications.canNotify(context)) return notificationsBlocked(context, "An alarm")
        return runCatching {
            val ok = ReminderNotifications.schedule(
                context, millis, label, ReminderNotifications.ALARM_CHANNEL, isAlarm = true
            )
            if (!ok) {
                ToolResult.Failure("Android refused to schedule the alarm.")
            } else {
                ToolResult.Success(
                    summary = "Alarm set for ${fmt.format(Date(millis))} ($label).",
                    data = buildJsonObject {
                        put("label", label)
                        put("when", millis)
                        put("status", "scheduled")
                    }
                )
            }
        }.getOrElse {
            ToolResult.Failure("Could not set the alarm: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
