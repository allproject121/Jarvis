package com.jarvis.assistant.androllm.voice.assistant

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.androllm.voice.VoiceMicArbiter
import com.jarvis.assistant.androllm.voice.assistant.service.VoiceAssistantService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.PermissionHelper

/**
 * Public entry point for the OPTIONAL AndroLLM voice assistant (ported from AndroLLM).
 *
 * Jarvis-first gating: [start] refuses (returning a reason) while Jarvis's own
 * JarvisConversationService is running or owns the microphone - exactly one voice
 * foreground service at a time, and Jarvis always wins.
 */
object VoiceAssistant {

    /** True while the AndroLLM voice service is alive. */
    val isRunning: Boolean get() = VoiceAssistantService.isRunning.value

    /** Returns null when the service was asked to start, otherwise why it cannot. */
    fun start(context: Context): String? {
        val app = context.applicationContext
        blockReason(app)?.let { return it }
        val intent = Intent(app, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_START)
        return runCatching { ContextCompat.startForegroundService(app, intent); null }
            .getOrElse { "Could not start the voice assistant: ${it.message}" }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        if (!VoiceAssistantService.isRunning.value) return
        val intent = Intent(app, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP)
        runCatching { app.startService(intent) }
    }

    /** Why the assistant cannot start right now (null = it can). */
    fun blockReason(context: Context): String? = when {
        JarvisConversationService.isServiceRunning() ->
            "Jarvis's voice service is running (session or \"Hey Jarvis\" standby). Power Jarvis off first."
        VoiceMicArbiter.jarvisOwnsMic() -> "Jarvis is using the microphone."
        !PermissionHelper.hasRecordAudioPermission(context) -> "Microphone permission is required."
        else -> null
    }
}
