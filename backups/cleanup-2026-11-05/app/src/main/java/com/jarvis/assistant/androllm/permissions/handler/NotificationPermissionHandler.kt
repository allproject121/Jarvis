package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/**
 * Notifications — status updates from the assistant.
 *
 * On Android 13+ the runtime permission is requested through the dialog; on
 * every version the user may also have disabled the app's notifications in
 * system settings, which is surfaced as a settings action, not a dialog.
 */
class NotificationPermissionHandler : PermissionHandler {

    override val id = "notifications"
    override val title = "Notifications"
    override val description = "Status updates from the assistant."
    override val explanation = "Notifications surface the listening status, model download " +
        "progress, automation results and scheduled reminders and alarms. No content is ever " +
        "shared with third parties."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (needsRuntimePermission &&
            !PermissionChecks.has(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Dialog path (Android 13+).
            return PermissionState.DENIED
        }
        // Permission granted (or not needed below 13): can the app actually
        // post? A user can disable notifications per-app in system settings
        // at any time — that's a settings action, not a dialog.
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            PermissionState.GRANTED
        } else {
            PermissionState.NEEDS_SETTINGS
        }
    }

    override fun openSettings(context: Context): Boolean =
        PermissionIntents.appNotificationSettings(context)
}
