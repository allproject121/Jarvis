package com.jarvis.assistant.androllm.permissions.handler

import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.automation.notification.JarvisNotificationListener

/**
 * Notification Access (Jarvis addition) — lets Jarvis' existing
 * [JarvisNotificationListener] read incoming messages, which the
 * read_notifications tool and WhatsApp/Telegram replies rely on. Special
 * access: granted only from the system "Notification access" screen.
 */
class NotificationAccessHandler : PermissionHandler {

    override val id = "notification_access"
    override val title = "Notification Access"
    override val description = "Read and reply to messages."
    override val explanation = "Allows Jarvis to read incoming chat and call notifications " +
        "aloud and draft replies when you ask. Notifications stay on your device."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (JarvisNotificationListener.isNotificationServiceEnabled(context)) PermissionState.GRANTED
        else PermissionState.NEEDS_SETTINGS

    override fun openSettings(context: Context): Boolean =
        PermissionIntents.notificationListenerSettings(context)
}
