package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState

/**
 * SMS — the assistant can send text messages on request.
 *
 * Sending remains gated by the confirmation policy (high-risk actions always
 * ask before actually sending); this gate only unlocks the capability.
 */
class SmsPermissionHandler : PermissionHandler {

    override val id = "sms"
    override val title = "SMS"
    override val description = "Send text messages for you."
    override val explanation = "Allows the assistant to send SMS messages when you ask, " +
        "including emergency SOS messages. High-risk actions still require your " +
        "confirmation before anything is sent."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.SEND_SMS)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionChecks.has(context, Manifest.permission.SEND_SMS)) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
