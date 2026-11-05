package com.jarvis.assistant.security.guardian

/**
 * The security / guardian section was removed (2026-11-05). The protected Gemini Live service
 * still reports owner-voice matches here, so only that entry point is kept; nothing reads it.
 */
object VoiceGuardPolicy {
    @Volatile
    var lastOwnerVerifiedAtMs: Long = 0L
        private set

    fun markOwnerVerified(nowMs: Long = System.currentTimeMillis()) {
        lastOwnerVerifiedAtMs = nowMs
    }
}
