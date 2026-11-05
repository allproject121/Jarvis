package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.util.PermissionHelper

/**
 * Location — current-location weather, nearby places, navigation.
 *
 * Jarvis adaptation: AndroLLM shipped this gate disabled because no AndroLLM
 * feature consumed device location. Jarvis does (weather, directions, the
 * get_location tool), so the feature is enabled and requested lazily — only
 * foreground location, never background.
 */
class LocationPermissionHandler : PermissionHandler {

    override val id = "location"
    override val title = "Location"
    override val description = "Current-location weather and nearby places."
    override val explanation = "Lets the assistant answer with your current location " +
        "(weather, nearby places, navigation). Only read when a request needs it; " +
        "background location is never requested."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionHelper.hasLocationPermission(context)) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
