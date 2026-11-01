package com.jarvis.assistant.voice.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Checks and requests the "Display over other apps" (SYSTEM_ALERT_WINDOW) permission.
 */
object OverlayPermission {

    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun openSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
