package com.jarvis.assistant.androllm.permissions.handler

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Small intent helpers for the permission handlers (ported from AndroLLM
 * core/permissions). All launchers use NEW_TASK so they work from any context
 * (including the accessibility settings flow).
 */
internal object PermissionIntents {

    /** App detail page (a runtime permission can be toggled there). */
    fun appDetails(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Per-app notification settings. */
    fun appNotificationSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** System "Notification access" list (Jarvis' notification listener). */
    fun notificationListenerSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/** Grant checks shared by the handlers (subset of AndroLLM's core/utils PermissionUtils). */
internal object PermissionChecks {

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAll(context: Context, permissions: List<String>): Boolean =
        permissions.all { has(context, it) }
}
