package com.jarvis.assistant.androllm.voice.tts

/**
 * Clean abstraction for speech synthesis (TTS) returning mono float PCM
 * (ported from AndroLLM core/voice).
 */
interface SpeechSynthesizer {
    val isInitialized: Boolean
    val sampleRate: Int
    fun ensureInitialized(): Boolean
    fun synthesize(text: String, speed: Float = 1.0f): FloatArray?
    fun release()
}
