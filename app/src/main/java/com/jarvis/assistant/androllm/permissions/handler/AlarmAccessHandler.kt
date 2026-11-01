package com.jarvis.assistant.androllm.permissions.handler

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/**
 * Exact Alarm / Scheduling — one-shot alarms and reminders.
 *
 * Jarvis' manifest declares USE_EXACT_ALARM (auto-granted on Android 13+) and
 * SCHEDULE_EXACT_ALARM. On Android 12/12L the live
 * [AlarmManager.canScheduleExactAlarms] answer is reported; the reminder and
 * alarm tools fall back to inexact alarms when exact ones are blocked, so this
 * gate never opens a dialog and never blocks a feature.
 */
class AlarmAccessHandler : PermissionHandler {

    override val id = "alarms"
    override val title = "Alarms & Scheduling"
    override val description = "Set alarms and reminders."
    override val explanation = "Lets the assistant set one-shot alarms and reminders. " +
        "Exact-alarm access is granted automatically by Android 13+ — nothing is requested here."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = emptyList()

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> PermissionState.GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am?.canScheduleExactAlarms() == true) PermissionState.GRANTED else PermissionState.NOT_REQUIRED
        }
        else -> PermissionState.GRANTED
    }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
