package com.jarvis.assistant.voice.wakeword

/**
 * Pluggable wake-word engine interface for Jarvis.
 * 100% offline, operates on 16kHz mono audio float arrays.
 */
interface WakeWordEngine {
    val isInitialized: Boolean
    fun ensureInitialized(): Boolean
    fun startSession(phrases: List<String> = listOf("HEY_JARVIS", "JARVIS"))
    fun feed(samples: FloatArray): String?
    fun reset()
    fun release()
}
