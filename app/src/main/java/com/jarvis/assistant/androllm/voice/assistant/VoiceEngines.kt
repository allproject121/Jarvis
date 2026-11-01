package com.jarvis.assistant.androllm.voice.assistant

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.AndroLlmVoicePrefs
import com.jarvis.assistant.androllm.voice.SherpaNative
import com.jarvis.assistant.androllm.voice.TtsFlavor
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.VoiceModelKind
import com.jarvis.assistant.androllm.voice.asr.GeminiStreamingRecognizer
import com.jarvis.assistant.androllm.voice.asr.SherpaRecognizer
import com.jarvis.assistant.androllm.voice.asr.SpeechRecognizer
import com.jarvis.assistant.androllm.voice.asr.StreamingRecognizerAdapter
import com.jarvis.assistant.androllm.voice.asr.VoskStreamingRecognizer
import com.jarvis.assistant.androllm.voice.audio.PcmAudioPlayer
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.androllm.voice.tts.GeminiOfflineTtsEngine
import com.jarvis.assistant.androllm.voice.tts.PiperSpeechSynthesizer
import com.jarvis.assistant.androllm.voice.tts.SpeechSynthesizer
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiApiKeyProvider
import com.jarvis.assistant.androllm.voice.wakeword.OpenWakeWordEngine
import com.jarvis.assistant.androllm.voice.wakeword.SherpaOnnxWakeWordEngine
import com.jarvis.assistant.androllm.voice.wakeword.VoskWakeWordEngine
import com.jarvis.assistant.androllm.voice.wakeword.WakeWordEngine
import com.jarvis.assistant.androllm.voice.whisper.WhisperSpeechRecognizer
import com.jarvis.assistant.voice.PocketTtsEngine
import com.jarvis.assistant.voice.normalize.TextNormalizationEngine
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One prepared (synthesized) utterance ready to play. */
interface PreparedSpeech {
    /** Duration estimate in ms (exact for PCM engines) - drives the karaoke highlight. */
    val durationMs: Long

    /** Plays it; suspends until done. Returns false when interrupted. */
    suspend fun play(): Boolean
}

/**
 * Speech output used by the voice service: either a PCM [SpeechSynthesizer]
 * (sherpa VITS / Piper / Gemini REST) played through [PcmAudioPlayer], or Jarvis's
 * own Android-TTS / PocketTTS engine (the default).
 */
interface SpeechOutput {
    val label: String
    fun ensureInitialized(): Boolean
    /** Synthesizes [text]; null when the engine failed (the text still shows on screen). */
    fun prepare(text: String, speed: Float): PreparedSpeech?
    fun stopNow()
    fun release()
}

/** PCM synthesizer + AudioTrack player. */
class PcmSpeechOutput(
    private val synthesizer: SpeechSynthesizer,
    private val player: PcmAudioPlayer,
    override val label: String
) : SpeechOutput {
    override fun ensureInitialized(): Boolean = synthesizer.ensureInitialized()

    override fun prepare(text: String, speed: Float): PreparedSpeech? {
        val samples = synthesizer.synthesize(text, speed) ?: return null
        if (samples.isEmpty()) return null
        val rate = synthesizer.sampleRate
        return object : PreparedSpeech {
            override val durationMs: Long = samples.size.toLong() * 1000L / rate.coerceAtLeast(1)
            override suspend fun play(): Boolean = player.play(samples, rate)
        }
    }

    override fun stopNow() = player.stopNow()

    override fun release() {
        player.release()
        synthesizer.release()
    }
}

/** Jarvis's default speech: [PocketTtsEngine] (Android TextToSpeech first, PocketTTS native fallback). */
class JarvisSystemSpeechOutput(private val context: Context) : SpeechOutput {
    override val label: String = "Android TTS / PocketTTS"

    private val engine: PocketTtsEngine get() = PocketTtsEngine.getInstance(context)

    override fun ensureInitialized(): Boolean = runCatching { engine; true }.getOrDefault(false)

    override fun prepare(text: String, speed: Float): PreparedSpeech? {
        if (text.isBlank()) return null
        // Same text-normalization pipeline Jarvis's own offline TTS uses.
        val spoken = TextNormalizationEngine.normalize(text).text.ifBlank { text }
        // System TTS gives no duration; estimate ~65 ms per character at 1x.
        val estimate = (spoken.length * 65L / speed.coerceIn(0.5f, 2.0f)).toLong().coerceAtLeast(400L)
        return object : PreparedSpeech {
            override val durationMs: Long = estimate
            override suspend fun play(): Boolean {
                val done = withTimeoutOrNull(estimate * 4 + 8_000L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        engine.speak(spoken) { if (cont.isActive) cont.resume(true) }
                        cont.invokeOnCancellation { runCatching { engine.stop() } }
                    }
                }
                if (done == null) runCatching { engine.stop() }
                return done == true
            }
        }
    }

    override fun stopNow() {
        runCatching { engine.stop() }
    }

    override fun release() = stopNow()
}

/**
 * Builds the engines selected in [VoiceSettings]. Jarvis engines are the defaults;
 * cloud (Gemini REST) engines are skipped when `offlineOnly` is set or no key exists,
 * and missing optional models fall back to the Jarvis engine with a logged reason.
 */
object VoiceEngines {

    private const val TAG = "VoiceEngines"

    data class Built<T>(val engine: T, val label: String, val note: String? = null)

    fun wakeWord(context: Context, s: VoiceSettings): Built<WakeWordEngine> {
        val app = context.applicationContext
        when (s.wakeEngine) {
            VoiceSettings.WAKE_SHERPA -> {
                val pack = VoiceModelCatalog.byId(AndroLlmVoicePrefs.current(app).wakeWordPackId)
                if (pack != null && pack.isInstalled(app) && SherpaNative.isAvailable()) {
                    return Built(SherpaOnnxWakeWordEngine(app, pack, s.sensitivity), "sherpa KWS")
                }
                return fallbackWake(app, s, "sherpa wake model not installed / native unavailable")
            }
            VoiceSettings.WAKE_OPENWAKEWORD ->
                return Built(OpenWakeWordEngine(app, s.openWakeWordModel, s.sensitivity), "openWakeWord")
        }
        return Built(VoskWakeWordEngine(app, s.sensitivity), "Vosk")
    }

    private fun fallbackWake(app: Context, s: VoiceSettings, why: String): Built<WakeWordEngine> {
        Log.w(TAG, "Wake engine ${s.wakeEngine} unavailable ($why) - using Vosk")
        return Built(VoskWakeWordEngine(app, s.sensitivity), "Vosk", "Using Vosk wake word: $why")
    }

    fun recognizer(context: Context, s: VoiceSettings): Built<SpeechRecognizer> {
        val app = context.applicationContext
        val silence = s.silenceTimeoutMs / 1000f
        when (s.sttEngine) {
            VoiceSettings.STT_WHISPER -> return Built(WhisperSpeechRecognizer(app), "whisper.cpp")
            VoiceSettings.STT_SHERPA -> {
                val pack = VoiceModelCatalog.byId(AndroLlmVoicePrefs.current(app).asrPackId)
                if (pack != null && pack.isInstalled(app) && SherpaNative.isAvailable()) {
                    return Built(StreamingRecognizerAdapter(SherpaRecognizer(app, pack, silence), "sherpa-onnx"), "sherpa-onnx")
                }
                return fallbackStt(app, silence, "sherpa ASR model not installed / native unavailable")
            }
            VoiceSettings.STT_GEMINI -> {
                if (s.offlineOnly) return fallbackStt(app, silence, "offline-only mode is on")
                if (!GeminiApiKeyProvider.isConfigured()) return fallbackStt(app, silence, "no Gemini API key")
                val lang = if (s.sttLanguage == "auto") s.language else s.sttLanguage
                return Built(StreamingRecognizerAdapter(GeminiStreamingRecognizer(language = lang), "Gemini REST"), "Gemini REST")
            }
        }
        return Built(StreamingRecognizerAdapter(VoskStreamingRecognizer(app, silence), "Vosk"), "Vosk")
    }

    private fun fallbackStt(app: Context, silence: Float, why: String): Built<SpeechRecognizer> {
        Log.w(TAG, "STT engine unavailable ($why) - using Vosk")
        return Built(StreamingRecognizerAdapter(VoskStreamingRecognizer(app, silence), "Vosk"), "Vosk", "Using Vosk speech recognition: $why")
    }

    fun speech(context: Context, s: VoiceSettings): Built<SpeechOutput> {
        val app = context.applicationContext
        when (s.ttsEngine) {
            VoiceSettings.TTS_SHERPA -> {
                val pack = VoiceModelCatalog.byId(AndroLlmVoicePrefs.current(app).ttsPackId)
                if (pack != null && pack.isInstalled(app) && SherpaNative.isAvailable()) {
                    return Built(PcmSpeechOutput(PiperSpeechSynthesizer(app, pack), PcmAudioPlayer(app), "sherpa-onnx"), "sherpa-onnx")
                }
                return fallbackTts(app, "sherpa voice not installed / native unavailable")
            }
            VoiceSettings.TTS_PIPER -> {
                val pack = VoiceModelCatalog.ofKind(VoiceModelKind.TTS)
                    .firstOrNull { it.ttsFlavor == TtsFlavor.PIPER_ESPEAK && it.isInstalled(app) }
                if (pack != null && SherpaNative.isAvailable()) {
                    return Built(PcmSpeechOutput(PiperSpeechSynthesizer(app, pack), PcmAudioPlayer(app), "Piper"), "Piper")
                }
                return fallbackTts(app, "no Piper voice installed / native unavailable")
            }
            VoiceSettings.TTS_GEMINI -> {
                if (s.offlineOnly) return fallbackTts(app, "offline-only mode is on")
                if (!GeminiApiKeyProvider.isConfigured()) return fallbackTts(app, "no Gemini API key")
                return Built(
                    PcmSpeechOutput(GeminiOfflineTtsEngine(voiceName = s.ttsVoice), PcmAudioPlayer(app), "Gemini TTS"),
                    "Gemini TTS"
                )
            }
        }
        return Built(JarvisSystemSpeechOutput(app), "Jarvis TTS")
    }

    private fun fallbackTts(app: Context, why: String): Built<SpeechOutput> {
        Log.w(TAG, "TTS engine unavailable ($why) - using Jarvis TTS")
        return Built(JarvisSystemSpeechOutput(app), "Jarvis TTS", "Using Jarvis TTS: $why")
    }
}
