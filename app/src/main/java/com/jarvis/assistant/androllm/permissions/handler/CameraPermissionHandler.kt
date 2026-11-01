package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.util.PermissionHelper

/**
 * Camera — Live vision, QR scanning and the flashlight toggle (the flashlight
 * runs through the camera unit, so it shares this gate).
 */
class CameraPermissionHandler : PermissionHandler {

    override val id = "camera"
    override val title = "Camera"
    override val description = "Vision, QR scanning and flashlight."
    override val explanation = "The camera is used for Live vision, to scan QR codes and to " +
        "control the flashlight. Nothing is recorded unless you start a session or a scan."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.CAMERA)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionHelper.hasCameraPermission(context)) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
