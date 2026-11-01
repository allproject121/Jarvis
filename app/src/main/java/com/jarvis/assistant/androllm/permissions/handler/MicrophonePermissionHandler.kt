package com.jarvis.assistant.androllm.permissions.handler

import android.Manifest
import android.content.Context
import android.os.Build
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.util.PermissionHelper

/**
 * Voice Assistant — wake word, voice input and hands-free replies.
 *
 * Requests the microphone (plus notifications on Android 13+ so the
 * always-on service can surface status) through one system dialog. The
 * foreground-microphone service permission is a manifest declaration, not a
 * runtime gate, so it is never requested here.
 */
class MicrophonePermissionHandler : PermissionHandler {

    override val id = "voice_assistant"
    override val title = "Voice Assistant"
    override val description = "Talk to Jarvis hands-free and use voice features."
    override val explanation = "The microphone powers the wake word, Gemini Live voice, " +
        "spoken questions and voice notes. Audio is only captured while Jarvis is listening."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionHelper.hasRecordAudioPermission(context)) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
