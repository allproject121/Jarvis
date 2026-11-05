package com.jarvis.assistant.androllm.voice.tts

/**
 * Pluggable offline text-to-speech engine (ported from AndroLLM).
 *
 * [synthesize] is called once per complete sentence so playback can start while
 * later sentences are still being synthesized.
 */
interface OfflineTtsEngine {

    /** True once the model is loaded. */
    val isInitialized: Boolean

    /** Loads the model. Returns false when the model is missing or cannot load. */
    fun ensureInitialized(): Boolean

    /** PCM sample rate of synthesized audio (e.g. 22050). */
    val sampleRate: Int

    /**
     * Synthesizes [text] into a mono float PCM buffer.
     * [speed] is the speaking rate (0.5..2.0). Returns null on failure.
     */
    fun synthesize(text: String, speed: Float): FloatArray?

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}
