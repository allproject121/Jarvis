package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/**
 * Calendar — create and read calendar events through the assistant.
 *
 * Read and write are requested together: the calendar tool needs both to
 * create events and to list upcoming ones.
 */
class CalendarPermissionHandler : PermissionHandler {

    override val id = "calendar"
    override val title = "Calendar"
    override val description = "Create and read calendar events."
    override val explanation = "Lets the assistant add events to your calendar and tell you " +
        "what is coming up. Events are read and written through the system calendar — " +
        "the assistant never sees your other app data."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionChecks.hasAll(context, runtimePermissions(context))) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
