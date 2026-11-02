package com.jarvis.assistant.androllm.voice.assistant

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.PermissionHelper

/**
 * Unified Voice Assistant entry point.
 * Merged into JarvisConversationService: All voice triggers route to Jarvis's
 * central bidirectional voice engine (Gemini Live cloud + local PocketTTS/Whisper offline).
 */
object VoiceAssistant {

    /** True while the unified Jarvis voice service is alive. */
    val isRunning: Boolean get() = JarvisConversationService.isServiceRunning()

    /** Starts the unified Jarvis voice conversation session. */
    fun start(context: Context): String? {
        val app = context.applicationContext
        blockReason(app)?.let { return it }
        val intent = Intent(app, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_START_CONVERSATION)
        return runCatching {
            ContextCompat.startForegroundService(app, intent)
            null
        }.getOrElse { "Could not start the voice assistant: ${it.message}" }
    }

    /** Stops the unified Jarvis voice conversation session. */
    fun stop(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_STOP_ALL)
        runCatching { app.startService(intent) }
    }

    /** Why the assistant cannot start right now (null = it can). */
    fun blockReason(context: Context): String? = when {
        !PermissionHelper.hasRecordAudioPermission(context) -> "Microphone permission is required."
        else -> null
    }
}
