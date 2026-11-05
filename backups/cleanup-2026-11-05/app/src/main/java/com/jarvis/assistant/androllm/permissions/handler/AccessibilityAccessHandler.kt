package com.jarvis.assistant.androllm.permissions.handler

import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.util.PermissionHelper

/**
 * Accessibility Automation — operating other apps on the user's behalf.
 *
 * Android does NOT grant accessibility through a runtime dialog: the user must
 * toggle the service in Settings → Accessibility. This handler opens exactly
 * that screen, then re-checks Jarvis' JarvisAccessibilityService (via
 * [PermissionHelper.isAccessibilityServiceEnabled]) when the user returns. The
 * service is never enabled programmatically.
 */
class AccessibilityAccessHandler : PermissionHandler {

    override val id = "accessibility"
    override val title = "Accessibility Automation"
    override val description = "Let Jarvis operate apps for you."
    override val explanation = "Accessibility access allows Jarvis to interact with apps on " +
        "your behalf for automation — opening apps, tapping controls, entering text, scrolling " +
        "and completing multi-step tasks. You can disable it at any time in system settings."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionHelper.isAccessibilityServiceEnabled(context)) PermissionState.GRANTED
        else PermissionState.NEEDS_SETTINGS

    override fun openSettings(context: Context): Boolean = runCatching {
        PermissionHelper.openAccessibilitySettings(context)
        true
    }.getOrDefault(false)
}
