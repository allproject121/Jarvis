package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import android.os.Build
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/**
 * Bluetooth — interacting with connected Bluetooth devices.
 *
 * Jarvis adaptation: unlike the AndroLLM build, Jarvis declares
 * BLUETOOTH_CONNECT (headset / car-mode detection), so on Android 12+ that
 * runtime permission is requested here; older versions use the normal,
 * install-time BLUETOOTH / BLUETOOTH_ADMIN permissions.
 */
class BluetoothPermissionHandler : PermissionHandler {

    override val id = "bluetooth"
    override val title = "Bluetooth"
    override val description = "Interact with connected Bluetooth devices."
    override val explanation = "Used to read Bluetooth state, detect headsets and car " +
        "connections and toggle Bluetooth when you ask."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionChecks.hasAll(context, runtimePermissions(context))) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
