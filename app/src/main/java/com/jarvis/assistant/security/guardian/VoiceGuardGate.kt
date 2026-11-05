package com.jarvis.assistant.security.guardian

import android.content.Context
import org.json.JSONObject

/**
 * The security / guardian section was removed (2026-11-05). This stub stays only because the
 * protected Gemini Live files call it before every tool; it never blocks anything.
 */
object VoiceGuardGate {
    @Suppress("UNUSED_PARAMETER")
    suspend fun check(context: Context, toolName: String, args: JSONObject): String? = null
}
