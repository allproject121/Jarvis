package com.jarvis.assistant.androllm.voice.voicecloud

import android.util.Log
import com.jarvis.assistant.JarvisApp

/**
 * Resolves the user's Gemini API key for the OPTIONAL Gemini REST speech engines
 * (ported from AndroLLM `GeminiApiKeyProvider`).
 *
 * Jarvis stores the key once, encrypted, in [com.jarvis.assistant.data.preferences.AppPreferences.apiKey]
 * (the same key Gemini Live uses). This provider only READS it - it never writes,
 * rotates or caches it, and it never touches the Gemini Live session. AndroLLM's
 * `set()` is intentionally not ported: key management stays in Jarvis's settings.
 */
object GeminiApiKeyProvider {

    private const val TAG = "GeminiVoiceKey"

    /** Returns the configured Gemini API key, or null when missing. */
    fun get(): String? = runCatching {
        JarvisApp.instance.preferences.apiKey.trim().ifBlank { null }
    }.onFailure { Log.w(TAG, "Gemini key unavailable: ${it.message}") }
        .getOrNull()

    fun isConfigured(): Boolean = !get().isNullOrBlank()
}
