package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/*
 * Jarvis-only gates for AI tools that the AndroLLM list never covered: phone calls, drawing over
 * other apps, Do Not Disturb, system settings (brightness), app usage and battery optimisation.
 * Each one reads the real Android state and opens the exact system screen / dialog that grants it.
 */

private fun openScreen(context: Context, intent: Intent, fallback: Intent? = null): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrElse {
    fallback != null && runCatching {
        context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

/** CALL_PHONE + READ_PHONE_STATE — "call Mom", WhatsApp / phone call tools, call state. */
class PhoneCallsPermissionHandler : PermissionHandler {
    override val id = "phone"
    override val title = "Phone calls"
    override val description = "Place calls when you ask."
    override val explanation = "Lets Jarvis dial a contact directly when you say \"call Mom\" and know " +
        "whether a call is active. Without it Jarvis can only open the dialer."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionChecks.hasAll(context, runtimePermissions(context))) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}

/** SYSTEM_ALERT_WINDOW — confirmations over other apps, Dynamic Island, avatar overlay. */
class OverlayAccessHandler : PermissionHandler {
    override val id = "overlay"
    override val title = "Display over other apps"
    override val description = "Show Jarvis on top of other apps."
    override val explanation = "Needed for the Allow / Deny question while Jarvis automates another app, " +
        "the Dynamic Island and the floating avatar. Without it those only work inside Jarvis."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) PermissionState.GRANTED
        else PermissionState.NEEDS_SETTINGS

    override fun openSettings(context: Context): Boolean = openScreen(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    )
}

/** ACCESS_NOTIFICATION_POLICY — the set_dnd / focus tools. */
class DndAccessHandler : PermissionHandler {
    override val id = "dnd"
    override val title = "Do Not Disturb"
    override val description = "Turn Do Not Disturb on or off."
    override val explanation = "Lets Jarvis switch Do Not Disturb when you ask (\"DND on karo\", focus timer). " +
        "Android grants this only from its Do Not Disturb access screen."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return PermissionState.UNAVAILABLE
        return if (nm.isNotificationPolicyAccessGranted) PermissionState.GRANTED else PermissionState.NEEDS_SETTINGS
    }

    override fun openSettings(context: Context): Boolean =
        openScreen(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
}

/** WRITE_SETTINGS — the brightness tool. */
class SystemSettingsAccessHandler : PermissionHandler {
    override val id = "write_settings"
    override val title = "Modify system settings"
    override val description = "Change screen brightness."
    override val explanation = "Lets Jarvis set the screen brightness when you ask. " +
        "Android grants this only from the \"Modify system settings\" screen."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (Settings.System.canWrite(context)) PermissionState.GRANTED else PermissionState.NEEDS_SETTINGS

    override fun openSettings(context: Context): Boolean = openScreen(
        context,
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
    )
}

/** PACKAGE_USAGE_STATS — screen time / app usage tools. */
class UsageAccessHandler : PermissionHandler {
    override val id = "usage"
    override val title = "Usage access"
    override val description = "Screen time and app usage."
    override val explanation = "Lets Jarvis answer \"aaj kitna phone chalaya\" and which apps you used most. " +
        "Android grants this only from the Usage access screen."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return PermissionState.UNAVAILABLE
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return if (mode == AppOpsManager.MODE_ALLOWED) PermissionState.GRANTED else PermissionState.NEEDS_SETTINGS
    }

    override fun openSettings(context: Context): Boolean =
        openScreen(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

/** Battery optimisation exemption — keeps the wake word / voice service alive in the background. */
class BatteryOptimizationHandler : PermissionHandler {
    override val id = "battery"
    override val title = "Run in background"
    override val description = "Keep \"Hey Jarvis\" listening."
    override val explanation = "Excludes Jarvis from battery optimisation so the phone doesn't kill the " +
        "wake word, reminders and the voice session in the background."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()
    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return PermissionState.UNAVAILABLE
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermissionState.GRANTED
        else PermissionState.NEEDS_SETTINGS
    }

    override fun openSettings(context: Context): Boolean = openScreen(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )
}
