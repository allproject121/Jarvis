package com.jarvis.assistant.androllm.voice.wakeword

/**
 * Pluggable wake-word engine interface for Jarvis (ported from AndroLLM).
 *
 * Implementations must:
 *  - be 100% offline;
 *  - consume ~200 ms float chunks at 16 kHz mono via [feed];
 *  - return the triggered keyword name (e.g. `"HEY_JARVIS"`) when fired, or
 *    `null` while still listening;
 *  - support [startSession] for resetting per listening period.
 *
 * Jarvis's built-in wake word (Vosk grammar inside JarvisConversationService) stays
 * the default; this interface only backs the optional sherpa-onnx alternative.
 */
interface WakeWordEngine {
    val isInitialized: Boolean

    /** Loads the model. Returns false when the model is missing or cannot load. */
    fun ensureInitialized(): Boolean

    /**
     * Opens a new detection session. File-based keywords are authoritative;
     * [phrases] are informational for engines whose keywords are baked in.
     */
    fun startSession(phrases: List<String> = listOf("HEY_JARVIS", "JARVIS"))

    /** Feeds one ~200 ms float chunk sampled at 16 kHz mono. */
    fun feed(samples: FloatArray): String?

    /** Resets the in-flight decoder state (called after a successful hit). */
    fun reset()

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}
