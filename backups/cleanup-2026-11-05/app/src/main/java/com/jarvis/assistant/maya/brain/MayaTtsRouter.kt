package com.jarvis.assistant.maya.brain

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.androllm.voice.audio.PcmAudioPlayer
import com.jarvis.assistant.androllm.voice.tts.SpeechSynthesizer
import com.jarvis.assistant.voice.PocketTtsEngine
import com.jarvis.assistant.voice.edge.EdgeTtsSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Picks the Advanced-Agentic voice per Settings → Voice engine → Voice and speaks with the
 * fallback chain `gemini_tts → google_cloud → edge → pocket → android` (the chosen engine is
 * tried first, then the rest of the chain). Calls are serialized so sentence chunks never
 * overlap. Never touches Gemini Live audio.
 */
object MayaTtsRouter {

    private const val TAG = "MayaTtsRouter"

    private val queueMutex = Mutex()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Engine that actually produced the last utterance (for the Settings "Test" line). */
    @Volatile var lastEngineUsed: String = ""
        private set

    @Volatile var lastError: String? = null
        private set

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var player: PcmAudioPlayer? = null
    @Volatile private var androidTts: TextToSpeech? = null
    @Volatile private var androidTtsReady = false
    @Volatile private var geminiEngine: GeminiTtsSynthesizer? = null
    @Volatile private var googleEngine: GoogleCloudTtsSynthesizer? = null

    private val FALLBACK_CHAIN = listOf(
        BrainSettings.TTS_GEMINI, BrainSettings.TTS_GOOGLE_CLOUD, BrainSettings.TTS_EDGE,
        BrainSettings.TTS_POCKET, BrainSettings.TTS_ANDROID
    )

    /**
     * Speaks [text]; suspends until playback ends. Returns the engine id that spoke, or null
     * when every engine failed. [engineOverride] forces one engine (Settings "Test").
     */
    suspend fun speak(
        context: Context,
        text: String,
        engineOverride: String? = null,
        voiceOverride: String? = null,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {}
    ): String? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val app = context.applicationContext
        val settings = BrainSettings.get(app)
        val preferred = engineOverride ?: settings.ttsEngine
        val chain = if (engineOverride != null) listOf(engineOverride) else listOf(preferred) + FALLBACK_CHAIN.filter { it != preferred }
        return queueMutex.withLock {
            stopRequested.set(false)
            _speaking.value = true
            onStart()
            try {
                for (engine in chain) {
                    if (stopRequested.get()) break
                    val ok = runCatching { speakWith(app, engine, clean, voiceOverride) }
                        .onFailure { Log.w(TAG, "$engine threw: ${it.message}") }
                        .getOrDefault(false)
                    if (ok) {
                        lastEngineUsed = engine
                        lastError = null
                        return@withLock engine
                    }
                    Log.w(TAG, "TTS engine '$engine' failed (${lastError ?: "no detail"}); trying next")
                }
                null
            } finally {
                _speaking.value = false
                onDone()
            }
        }
    }

    /** Stops current playback and drops the chain for the in-flight utterance. */
    fun stop() {
        stopRequested.set(true)
        runCatching { player?.stopNow() }
        runCatching { EdgeTtsSpeaker.stop() }
        runCatching { androidTts?.stop() }
        runCatching { PocketTtsEngine.getInstance(com.jarvis.assistant.JarvisApp.instance).stop() }
        _speaking.value = false
    }

    private suspend fun speakWith(app: Context, engine: String, text: String, voiceOverride: String?): Boolean = when (engine) {
        BrainSettings.TTS_GEMINI -> playPcm(app, geminiEngine ?: GeminiTtsSynthesizer(app).also { geminiEngine = it }, text, voiceOverride)
        BrainSettings.TTS_GOOGLE_CLOUD -> playPcm(app, googleEngine ?: GoogleCloudTtsSynthesizer(app).also { googleEngine = it }, text, voiceOverride)
        BrainSettings.TTS_EDGE -> speakEdge(app, text, voiceOverride)
        BrainSettings.TTS_POCKET -> speakPocket(app, text)
        BrainSettings.TTS_ANDROID -> speakAndroid(app, text)
        else -> false
    }

    private suspend fun playPcm(app: Context, synth: SpeechSynthesizer, text: String, voiceOverride: String?): Boolean = withContext(Dispatchers.IO) {
        if (!synth.ensureInitialized()) {
            lastError = (synth as? GeminiTtsSynthesizer)?.lastError ?: (synth as? GoogleCloudTtsSynthesizer)?.lastError ?: "no API key"
            return@withContext false
        }
        val samples = if (voiceOverride.isNullOrBlank()) synth.synthesize(text, 1.0f) else when (synth) {
            is GeminiTtsSynthesizer -> GeminiTtsSynthesizer(app, voiceProvider = { voiceOverride }).synthesize(text, 1.0f)
            is GoogleCloudTtsSynthesizer -> GoogleCloudTtsSynthesizer(app, voiceProvider = { voiceOverride }).synthesize(text, 1.0f)
            else -> synth.synthesize(text, 1.0f)
        }
        if (samples == null || samples.isEmpty()) {
            lastError = (synth as? GeminiTtsSynthesizer)?.lastError ?: (synth as? GoogleCloudTtsSynthesizer)?.lastError ?: "no audio"
            return@withContext false
        }
        if (stopRequested.get()) return@withContext true
        val p = player ?: PcmAudioPlayer(app).also { player = it }
        p.play(samples, synth.sampleRate)
        true
    }

    private suspend fun speakEdge(app: Context, text: String, voiceOverride: String?): Boolean {
        if (!com.jarvis.assistant.voice.edge.EdgeTtsClient.isNetworkAvailable(app)) {
            lastError = "no network for Edge TTS"
            return false
        }
        val settings = BrainSettings.get(app)
        val voice = voiceOverride?.takeIf { it.isNotBlank() }
            ?: settings.ttsVoice.takeIf { settings.ttsEngine == BrainSettings.TTS_EDGE && it.isNotBlank() }
        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                EdgeTtsSpeaker.speak(
                    context = app,
                    text = text,
                    flush = false,
                    voiceOverride = voice,
                    onDone = { if (cont.isActive) cont.resume(true) },
                    onFailed = { if (cont.isActive) cont.resume(false) }
                )
                cont.invokeOnCancellation { EdgeTtsSpeaker.stop() }
            }
        }
        if (result != true) lastError = "Edge TTS failed"
        return result == true
    }

    private suspend fun speakPocket(app: Context, text: String): Boolean {
        val engine = PocketTtsEngine.getInstance(app)
        if (!engine.isReady()) {
            lastError = "Pocket TTS not ready"
            return false
        }
        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                PocketTtsEngine.speak(text, app) { if (cont.isActive) cont.resume(true) }
                cont.invokeOnCancellation { runCatching { engine.stop() } }
            }
        }
        return result == true
    }

    private suspend fun speakAndroid(app: Context, text: String): Boolean {
        val tts = ensureAndroidTts(app) ?: run { lastError = "Android TTS unavailable"; return false }
        val id = "maya_${System.currentTimeMillis()}"
        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation { tts.stop() }
            }
        }
        return result == true
    }

    private suspend fun ensureAndroidTts(app: Context): TextToSpeech? {
        androidTts?.let { if (androidTtsReady) return it }
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(app) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val inst = created
                        if (inst != null) {
                            val r = inst.setLanguage(Locale("en", "IN"))
                            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) inst.language = Locale.US
                        }
                        androidTts = created
                        androidTtsReady = true
                        if (cont.isActive) cont.resume(created)
                    } else if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
