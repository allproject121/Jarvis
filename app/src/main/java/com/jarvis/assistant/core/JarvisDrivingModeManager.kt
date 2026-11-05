package com.jarvis.assistant.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService

/**
 * Phone-call helper for Jarvis (caller-ID announcement + answer / decline).
 *
 * Driving mode was removed (Nov 2026). The class keeps its old name because the protected
 * JarvisConversationService calls `JarvisDrivingModeManager.getInstance(ctx).answerCall()` /
 * `.declineCall()` for the `handle_phone_call` tool - do not rename or drop those three members.
 */
class JarvisDrivingModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisCallControl"
        private const val TTS_SAFETY_SHUTDOWN_MS = 20_000L

        @Volatile
        private var instance: JarvisDrivingModeManager? = null

        fun getInstance(context: Context): JarvisDrivingModeManager {
            return instance ?: synchronized(this) {
                instance ?: JarvisDrivingModeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Handles an incoming call (called by JarvisPhoneReceiver on RINGING): announces the caller
     * through the live voice session, or a standalone TTS instance when no session is running,
     * when the caller-ID announcement toggle is on.
     */
    fun onIncomingCall(callerNameOrNumber: String) {
        val enabled = try { JarvisApp.instance.preferences.isCallerIdAnnouncementEnabled } catch (_: Throwable) { false }
        if (!enabled) return

        Log.i(TAG, "Announcing incoming call from: $callerNameOrNumber")
        if (JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.sendTextMessage(
                "[CALL SENTINEL: Incoming telephone call from '$callerNameOrNumber'. Announce clearly to user: 'Sir, incoming call from $callerNameOrNumber. Would you like me to answer or decline?']"
            )
        } else {
            speakStandalone("Sir, incoming call from $callerNameOrNumber.")
        }
    }

    /** One-shot announcement with its own TextToSpeech instance; shut down once spoken. */
    private fun speakStandalone(text: String) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var tts: TextToSpeech? = null
            var released = false
            val release = {
                if (!released) {
                    released = true
                    try { tts?.shutdown() } catch (_: Exception) {}
                }
            }
            try {
                tts = TextToSpeech(context) { status ->
                    val engine = tts
                    if (status != TextToSpeech.SUCCESS || engine == null) {
                        Log.w(TAG, "Standalone TTS init failed (status=$status)")
                        mainHandler.post { release() }
                    } else {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { mainHandler.post { release() } }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) { mainHandler.post { release() } }
                        })
                        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_caller_id")
                        if (queued != TextToSpeech.SUCCESS) mainHandler.post { release() }
                    }
                }
                // Safety net in case the engine never reports completion.
                mainHandler.postDelayed({ release() }, TTS_SAFETY_SHUTDOWN_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Standalone TTS unavailable: ${e.message}")
                release()
            }
        }
    }

    /**
     * Answers ringing phone call (requires ANSWER_PHONE_CALLS permission).
     * Used by the protected JarvisConversationService (handle_phone_call tool).
     */
    @SuppressLint("MissingPermission") // SecurityException caught
    fun answerCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            return try {
                telecomManager.acceptRingingCall()
                Log.i(TAG, "Incoming call answered via TelecomManager.")
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                false
            }
        }
        return false
    }

    /**
     * Declines/ends ringing phone call. Returns TelecomManager's real result.
     * Used by the protected JarvisConversationService (handle_phone_call tool).
     */
    @SuppressLint("MissingPermission") // SecurityException caught
    fun declineCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            return try {
                @Suppress("DEPRECATION")
                val ended = telecomManager.endCall()
                Log.i(TAG, "Incoming call decline via TelecomManager: ended=$ended")
                ended
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                false
            }
        }
        return false
    }
}
