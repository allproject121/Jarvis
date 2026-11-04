package com.jarvis.assistant.androllm.voice.model

/**
 * Persisted configuration of the OPTIONAL AndroLLM voice assistant mode
 * (ported from AndroLLM core/voice `VoiceSettings`).
 *
 * Jarvis-first: [enabled] defaults to false, and every engine selector defaults
 * to the Jarvis engine (Vosk wake word / Vosk ASR / Jarvis Android-TTS+PocketTTS).
 * Jarvis's own voice session (Gemini Live, JarvisConversationService) never reads
 * these settings - they only drive [com.jarvis.assistant.androllm.voice.assistant.service.VoiceAssistantService].
 *
 * Text-normalization toggles (AndroLLM `tn*` fields) are NOT duplicated here:
 * Jarvis keeps one normalization configuration for every offline TTS path in
 * [com.jarvis.assistant.voice.normalize.NormalizationSettingsStore].
 */
data class VoiceSettings(
    /** Master switch for the AndroLLM voice assistant mode (own foreground service). */
    val enabled: Boolean = false,
    /** Listen for a wake word between turns (otherwise one turn per manual start). */
    val enableWakeWord: Boolean = true,
    /** Custom wake phrases (lowercase). Used by the Vosk grammar engine. */
    val wakePhrases: List<String> = listOf("hey jarvis", "okay jarvis", "jarvis"),
    /** Wake-word sensitivity (0..1). Higher = more responsive, more false positives. */
    val sensitivity: Float = 0.5f,
    /** Battery saver: single-thread STT, no continuous conversation, slower wake polling. */
    val batterySaver: Boolean = false,
    /** Only run the listener while the device is charging. */
    val chargingOnly: Boolean = false,
    /** Trailing silence (ms) that ends a voice turn (endpoint detection). */
    val silenceTimeoutMs: Int = 2000,
    /** Speech language id ("en"). */
    val language: String = "en",
    /** Automatically detect speech language. */
    val autoLanguageDetection: Boolean = true,
    /** Gemini TTS voice name ("Kore", "Puck", "Fenrir", "Aoede", "Charon"). */
    val ttsVoice: String = "Kore",
    /** TTS speaking speed (0.5..2.0). */
    val speakingSpeed: Float = 1.0f,
    /** TTS pitch (0.5..1.5). */
    val pitch: Float = 1.0f,
    /** Voice playback volume (0.0..1.0). */
    val volume: Float = 1.0f,
    /** Never use cloud services (Gemini REST STT/TTS are refused). */
    val offlineOnly: Boolean = false,
    /** Keep listening after an answer ends (hands-free conversation). */
    val continuousConversation: Boolean = false,
    /** Speak answers aloud. */
    val autoReadAnswers: Boolean = true,
    /** Fall back to cloud inference when no local model is loaded (informational for ModelRouter). */
    val cloudFallback: Boolean = true,
    /** Skip conversation history for the fastest possible first token. */
    val lowLatencyMode: Boolean = false,
    /** System noise suppression / echo cancellation on the audio capture. */
    val noiseSuppression: Boolean = false,
    val echoCancellation: Boolean = false,
    /** Ask high-risk tool confirmations aloud and listen for yes / no. */
    val spokenConfirmations: Boolean = true,

    // ── Engine selection (Jarvis engines are the defaults) ───────────────────

    /** Wake engine id: [WAKE_VOSK] (default), [WAKE_SHERPA], [WAKE_OPENWAKEWORD]. */
    val wakeEngine: String = WAKE_VOSK,
    /** openWakeWord classifier asset name (under assets/wakeword/, without extension). */
    val openWakeWordModel: String = "hey_maya",

    /** STT engine id: [STT_JARVIS] (Vosk, default), [STT_SHERPA], [STT_WHISPER], [STT_GEMINI]. */
    val sttEngine: String = STT_JARVIS,
    /** TTS engine id: [TTS_JARVIS] (Android TTS / PocketTTS, default), [TTS_SHERPA], [TTS_PIPER], [TTS_GEMINI]. */
    val ttsEngine: String = TTS_JARVIS,

    // ── Speech recognition (Whisper.cpp) ─────────────────────────────────────

    /** Installed whisper.cpp model id, e.g. "base.en" / "small" / "large-v3-turbo". */
    val whisperModel: String = "base.en",
    /** Whisper language ("auto" = auto-detect) or ISO-639 code like "en". */
    val sttLanguage: String = "auto",
    /** Translate non-English speech to English. */
    val sttTranslate: Boolean = false,
    /** Whisper CPU threads (-1 = auto-detect). */
    val sttThreads: Int = -1,
    /** Beam search size (1 = greedy). */
    val sttBeamSize: Int = 1,
    /** Sampling temperature (0.0 = deterministic). */
    val sttTemperature: Float = 0.0f,
    /** Maximum recording length in seconds per turn. */
    val sttMaxSeconds: Int = 30,
    /** Live partial transcripts while speaking (sliding window). */
    val sttStreaming: Boolean = true,
    /** GPU acceleration (Vulkan) when built in. Reserved; CPU-only for now. */
    val sttGpu: Boolean = false,

    // ── Floating overlay ─────────────────────────────────────────────────────

    /** Auto-launch the floating AndroLLM overlay when a turn starts. */
    val autoOpenOverlay: Boolean = true,
    /** Play a short chime when listening starts. */
    val playStartSound: Boolean = true,
    /** Play a short chime when a turn finishes. */
    val playEndSound: Boolean = true,
    /** Overlay background opacity (0.25 = very transparent .. 1.0 = solid). */
    val overlayTransparency: Float = 0.78f,
    /** Overlay content scale (0.8 = compact .. 1.2 = large). */
    val overlaySize: Float = 1.0f,
    /** Animation speed multiplier (0.5 = calm .. 2.0 = snappy). */
    val animationSpeed: Float = 1.0f
) {
    companion object {
        const val WAKE_VOSK = "vosk"
        const val WAKE_SHERPA = "sherpa"
        const val WAKE_OPENWAKEWORD = "openwakeword"

        const val STT_JARVIS = "jarvis"
        const val STT_SHERPA = "sherpa"
        const val STT_WHISPER = "whisper"
        const val STT_GEMINI = "gemini"
        const val STT_DEEPGRAM = "deepgram"

        const val TTS_JARVIS = "jarvis"
        const val TTS_SHERPA = "sherpa"
        const val TTS_PIPER = "piper"
        const val TTS_GEMINI = "gemini"

        val WAKE_ENGINES = listOf(WAKE_VOSK, WAKE_SHERPA, WAKE_OPENWAKEWORD)
        val STT_ENGINES = listOf(STT_JARVIS, STT_SHERPA, STT_WHISPER, STT_GEMINI, STT_DEEPGRAM)
        val TTS_ENGINES = listOf(TTS_JARVIS, TTS_SHERPA, TTS_PIPER, TTS_GEMINI)

        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 1.0f
        const val MIN_SILENCE_TIMEOUT_MS = 500
        const val MAX_SILENCE_TIMEOUT_MS = 5000
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 1.5f
        const val MIN_VOLUME = 0.0f
        const val MAX_VOLUME = 1.0f
        const val MIN_OVERLAY_TRANSPARENCY = 0.25f
        const val MAX_OVERLAY_TRANSPARENCY = 1.0f
        const val MIN_OVERLAY_SIZE = 0.8f
        const val MAX_OVERLAY_SIZE = 1.2f
        const val MIN_ANIMATION_SPEED = 0.5f
        const val MAX_ANIMATION_SPEED = 2.0f
        const val MIN_STT_THREADS = 1
        const val MAX_STT_THREADS = 16
        const val MIN_STT_BEAM = 1
        const val MAX_STT_BEAM = 5
        const val MIN_STT_TEMPERATURE = 0.0f
        const val MAX_STT_TEMPERATURE = 1.0f
        const val MIN_STT_MAX_SECONDS = 5
        const val MAX_STT_MAX_SECONDS = 120

        fun wakeLabel(id: String): String = when (id) {
            WAKE_SHERPA -> "sherpa-onnx keyword spotter"
            WAKE_OPENWAKEWORD -> "openWakeWord (TFLite)"
            else -> "Vosk (Jarvis default)"
        }

        fun sttLabel(id: String): String = when (id) {
            STT_SHERPA -> "sherpa-onnx streaming"
            STT_WHISPER -> "whisper.cpp"
            STT_GEMINI -> "Gemini REST"
            STT_DEEPGRAM -> "Deepgram nova-3 (cloud)"
            else -> "Vosk (Jarvis default)"
        }

        fun ttsLabel(id: String): String = when (id) {
            TTS_SHERPA -> "sherpa-onnx VITS"
            TTS_PIPER -> "Piper (sherpa-onnx)"
            TTS_GEMINI -> "Gemini REST TTS"
            else -> "Android TTS / PocketTTS (Jarvis default)"
        }
    }
}
