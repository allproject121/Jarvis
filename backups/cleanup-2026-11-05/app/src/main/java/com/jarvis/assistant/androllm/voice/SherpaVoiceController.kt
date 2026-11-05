package com.jarvis.assistant.androllm.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.voice.asr.SherpaOnnxStreamingRecognizer
import com.jarvis.assistant.androllm.voice.audio.PcmAudioPlayer
import com.jarvis.assistant.androllm.voice.audio.SherpaAudioRecorder
import com.jarvis.assistant.androllm.voice.tts.SherpaOnnxOfflineTtsEngine
import com.jarvis.assistant.androllm.voice.vad.Vad
import com.jarvis.assistant.androllm.voice.wakeword.SherpaOnnxWakeWordEngine
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Live state of the sherpa wake-word listener. */
enum class SherpaWakeState(val label: String) {
    OFF("Off"),
    MODEL_MISSING("Model not downloaded"),
    NATIVE_UNAVAILABLE("sherpa-onnx native library unavailable"),
    NO_PERMISSION("Microphone permission missing"),
    APP_IN_BACKGROUND("Paused - app in background"),
    JARVIS_USING_MIC("Paused - Jarvis is using the microphone"),
    DICTATION("Paused - dictation in progress"),
    STARTING("Starting"),
    LISTENING("Listening for \"Hey Jarvis\""),
    ERROR("Error")
}

data class SherpaDictationState(
    val active: Boolean = false,
    val partial: String = "",
    val finalText: String = "",
    val error: String? = null
)

data class SherpaVoiceStatus(
    val nativeAvailable: Boolean? = null,
    val nativeError: String? = null,
    val wakeState: SherpaWakeState = SherpaWakeState.OFF,
    val wakeDetail: String? = null,
    val lastWakeKeyword: String? = null,
    val dictation: SherpaDictationState = SherpaDictationState(),
    val speaking: Boolean = false,
    val ttsError: String? = null
)

/**
 * Orchestrates the optional sherpa-onnx voice components (AndroLLM's
 * VoiceAssistantService wake → ASR → TTS loop, reduced to Jarvis's needs and
 * converted from Hilt to a singleton). No new foreground service is used.
 *
 * Wake word: runs only while ALL hold - [AndroLlmFlags.SHERPA_WAKE_WORD] is on, the
 * KWS pack is installed, mic permission granted, Jarvis's session AND Vosk standby
 * are both off ([VoiceMicArbiter.jarvisOwnsMic]), the app is in the foreground
 * (Android forbids background mic capture without a microphone-type foreground
 * service, and JarvisConversationService is off-limits), and no dictation is
 * running. On a hit it frees the mic first, then starts Jarvis's normal Gemini Live
 * session with the same `ACTION_START_CONVERSATION` intent the UI uses.
 *
 * Dictation (ASR): one-shot offline speech-to-text for callers such as chat, gated by
 * [AndroLlmFlags.SHERPA_ASR] and the same mic rules.
 *
 * TTS: [speak] reads text with the selected offline voice, gated by
 * [AndroLlmFlags.SHERPA_TTS]; refused while a Jarvis session is on so it never talks
 * over Gemini Live.
 */
object SherpaVoiceController {

    private const val TAG = "SherpaVoice"
    private const val DICTATION_NO_SPEECH_TIMEOUT_MS = 8_000L
    private const val DICTATION_MAX_MS = 30_000L
    private const val HANDOFF_WAIT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Context? = null
    private var supervisorJob: Job? = null
    private var dictationJob: Job? = null
    private var speakJob: Job? = null

    private val dictationRequested = MutableStateFlow(false)
    private val recheckTick = MutableStateFlow(0)

    private val _status = MutableStateFlow(SherpaVoiceStatus())
    val status: StateFlow<SherpaVoiceStatus> = _status.asStateFlow()

    /** One-shot events (wake hits, dictation results) for UI notices. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Final dictation transcripts, for consumers such as the chat input. */
    private val _dictationResults = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val dictationResults: SharedFlow<String> = _dictationResults.asSharedFlow()

    // Cached engines (native models are heavy); rebuilt when their config changes.
    private var wakeEngine: SherpaOnnxWakeWordEngine? = null
    private var wakeKey: String? = null
    private var asrEngine: SherpaOnnxStreamingRecognizer? = null
    private var asrKey: String? = null
    private var ttsEngine: SherpaOnnxOfflineTtsEngine? = null
    private var ttsKey: String? = null
    private var player: PcmAudioPlayer? = null

    /** Idempotent. Safe to call from Application.onCreate, a ViewModel or a composable. */
    @Synchronized
    fun init(context: Context) {
        if (supervisorJob != null) return
        val app = context.applicationContext
        appContext = app
        AndroLlmVoicePrefs.init(app)
        VoiceModelDownloader.refresh(app)
        supervisorJob = scope.launch { superviseWakeWord(app) }
    }

    // ── Wake word ────────────────────────────────────────────────────────────

    private suspend fun superviseWakeWord(app: Context) {
        combine(
            AndroLlmVoicePrefs.config,
            VoiceMicArbiter.jarvisMicBusy,
            JarvisApp.isAppForeground,
            combine(dictationRequested, recheckTick) { dictating, tick -> dictating to tick },
            // Only installed-set changes matter; raw progress ticks must not restart the loop.
            VoiceModelDownloader.states
                .map { states -> states.filterValues { it is VoiceDownloadState.Installed }.keys }
                .distinctUntilChanged()
        ) { cfg, jarvisBusy, foreground, local, installed ->
            WakeInputs(cfg, jarvisBusy, foreground, local.first, local.second, installed)
        }.distinctUntilChanged().collectLatest { inputs ->
            val blocked = wakeBlockReason(app, inputs)
            if (blocked != null) {
                setWake(blocked.first, blocked.second)
                // Safe: collectLatest has already joined the previous (feeding) block.
                if (blocked.first == SherpaWakeState.OFF || blocked.first == SherpaWakeState.MODEL_MISSING) {
                    releaseWakeEngine()
                }
                return@collectLatest
            }
            runWakeLoop(app, inputs.cfg)
        }
    }

    private data class WakeInputs(
        val cfg: AndroLlmVoiceConfig,
        val jarvisBusy: Boolean,
        val foreground: Boolean,
        val dictating: Boolean,
        val recheckTick: Int,
        val installedPacks: Set<String>
    )

    private fun wakeBlockReason(app: Context, i: WakeInputs): Pair<SherpaWakeState, String?>? {
        if (!i.cfg.wakeWordEnabled) return SherpaWakeState.OFF to null
        val pack = VoiceModelCatalog.byId(i.cfg.wakeWordPackId) ?: return SherpaWakeState.MODEL_MISSING to null
        if (!pack.isInstalled(app)) return SherpaWakeState.MODEL_MISSING to pack.title
        if (!SherpaNative.isAvailable()) return SherpaWakeState.NATIVE_UNAVAILABLE to SherpaNative.errorMessage
        if (!PermissionHelper.hasRecordAudioPermission(app)) return SherpaWakeState.NO_PERMISSION to null
        if (i.jarvisBusy) return SherpaWakeState.JARVIS_USING_MIC to null
        if (i.dictating) return SherpaWakeState.DICTATION to null
        if (!i.foreground) return SherpaWakeState.APP_IN_BACKGROUND to null
        return null
    }

    private suspend fun runWakeLoop(app: Context, cfg: AndroLlmVoiceConfig) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val keyword = listenForWakeWordOnce(app, cfg) ?: return
            _status.update { it.copy(lastWakeKeyword = keyword) }
            _events.tryEmit("Wake word detected: ${keyword.replace('_', ' ').lowercase()}")
            handOffToJarvis(app)
            // Jarvis taking the mic cancels this block via collectLatest. If the
            // service did not start (e.g. blocked), resume listening.
            val tookOver = withTimeoutOrNull(HANDOFF_WAIT_MS) {
                VoiceMicArbiter.jarvisMicBusy.first { it }
            }
            if (tookOver == true) return
            Log.w(TAG, "Jarvis did not take the microphone after wake hit - re-arming")
        }
    }

    /** Returns the detected keyword, or null when listening ended without a hit. */
    private suspend fun listenForWakeWordOnce(app: Context, cfg: AndroLlmVoiceConfig): String? {
        if (!VoiceMicArbiter.acquire(SherpaMicOwner.WAKE_WORD)) {
            setWake(SherpaWakeState.JARVIS_USING_MIC, null)
            return null
        }
        var recorder: SherpaAudioRecorder? = null
        try {
            setWake(SherpaWakeState.STARTING, null)
            val engine = wakeEngineFor(app, cfg) ?: run {
                setWake(SherpaWakeState.ERROR, "Wake word model failed to load")
                return null
            }
            engine.startSession()
            val rec = SherpaAudioRecorder()
            recorder = rec
            if (!rec.start()) {
                setWake(SherpaWakeState.ERROR, "Microphone unavailable")
                return null
            }
            setWake(SherpaWakeState.LISTENING, null)
            for (chunk in rec.chunks) {
                currentCoroutineContext().ensureActive()
                if (VoiceMicArbiter.jarvisOwnsMic()) return null
                val hit = engine.feed(chunk)
                if (hit != null) return hit
            }
            return null
        } finally {
            recorder?.stopAndJoin()
            VoiceMicArbiter.release(SherpaMicOwner.WAKE_WORD)
        }
    }

    private fun handOffToJarvis(app: Context) {
        if (VoiceMicArbiter.jarvisOwnsMic()) return
        try {
            val intent = Intent(app, JarvisConversationService::class.java)
                .setAction(JarvisConversationService.ACTION_START_CONVERSATION)
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start Jarvis conversation after wake word", e)
            _events.tryEmit("Wake word heard, but Jarvis could not start: ${e.message}")
        }
    }

    @Synchronized
    private fun wakeEngineFor(app: Context, cfg: AndroLlmVoiceConfig): SherpaOnnxWakeWordEngine? {
        val pack = VoiceModelCatalog.byId(cfg.wakeWordPackId) ?: return null
        val key = "${pack.id}|${cfg.wakeSensitivity}"
        if (wakeKey != key) {
            wakeEngine?.release()
            wakeEngine = SherpaOnnxWakeWordEngine(app, pack, cfg.wakeSensitivity)
            wakeKey = key
        }
        val engine = wakeEngine ?: return null
        return if (engine.ensureInitialized()) engine else null
    }

    @Synchronized
    private fun releaseWakeEngine() {
        wakeEngine?.release()
        wakeEngine = null
        wakeKey = null
    }

    private fun setWake(state: SherpaWakeState, detail: String?) {
        _status.update {
            it.copy(
                wakeState = state,
                wakeDetail = detail,
                nativeAvailable = if (state == SherpaWakeState.NATIVE_UNAVAILABLE) false else it.nativeAvailable,
                nativeError = if (state == SherpaWakeState.NATIVE_UNAVAILABLE) detail else it.nativeError
            )
        }
    }

    // ── Dictation (streaming ASR) ────────────────────────────────────────────

    /**
     * Starts one offline dictation turn. Returns a reason string when it cannot start,
     * or null on success. Results arrive on [status] (partials) and [dictationResults].
     */
    fun startDictation(context: Context): String? {
        val app = context.applicationContext
        init(app)
        val cfg = AndroLlmVoicePrefs.current(app)
        if (!cfg.asrEnabled) return "Enable sherpa speech recognition in Settings → Voice first."
        val pack = VoiceModelCatalog.byId(cfg.asrPackId) ?: return "No speech model selected."
        if (!pack.isInstalled(app)) return "Download the ${pack.title} model first."
        if (!SherpaNative.isAvailable()) return "sherpa-onnx unavailable: ${SherpaNative.errorMessage}"
        if (!PermissionHelper.hasRecordAudioPermission(app)) return "Microphone permission is required."
        if (VoiceMicArbiter.jarvisOwnsMic()) return "Jarvis is using the microphone - stop the voice session first."
        synchronized(this) {
            if (dictationJob?.isActive == true) return "Dictation already running."
            dictationRequested.value = true
            _status.update { it.copy(dictation = SherpaDictationState(active = true)) }
            dictationJob = scope.launch {
                try {
                    runDictation(app, cfg, pack)
                } finally {
                    dictationRequested.value = false
                    _status.update { it.copy(dictation = it.dictation.copy(active = false)) }
                }
            }
        }
        return null
    }

    fun stopDictation() {
        dictationJob?.cancel()
    }

    private suspend fun runDictation(app: Context, cfg: AndroLlmVoiceConfig, pack: VoiceModelPack) {
        // Wait for the wake-word loop (if any) to let go of the mic.
        withTimeoutOrNull(2_000L) { VoiceMicArbiter.owner.first { it != SherpaMicOwner.WAKE_WORD } }
        if (!VoiceMicArbiter.acquire(SherpaMicOwner.DICTATION)) {
            setDictationError("Microphone is busy.")
            return
        }
        var recorder: SherpaAudioRecorder? = null
        try {
            val recognizer = asrEngineFor(app, cfg, pack) ?: run {
                setDictationError("Speech model failed to load.")
                return
            }
            recognizer.startSession()
            val rec = SherpaAudioRecorder()
            recorder = rec
            if (!rec.start()) {
                setDictationError("Microphone unavailable.")
                return
            }
            val vad = Vad()
            val startedAt = System.currentTimeMillis()
            var lastSpeechAt = startedAt
            var text = ""
            for (chunk in rec.chunks) {
                currentCoroutineContext().ensureActive()
                if (VoiceMicArbiter.jarvisOwnsMic()) break
                text = recognizer.feed(chunk)
                val now = System.currentTimeMillis()
                if (vad.process(chunk)) lastSpeechAt = now
                _status.update { it.copy(dictation = it.dictation.copy(partial = text)) }
                if (recognizer.isEndpoint() && text.isNotBlank()) break
                if (text.isBlank() && now - lastSpeechAt > DICTATION_NO_SPEECH_TIMEOUT_MS) break
                if (now - startedAt > DICTATION_MAX_MS) break
            }
            val finalText = recognizer.finalText().ifBlank { text }.trim()
            Log.i(TAG, "Dictation finished: '$finalText' (confidence~${recognizer.estimatedConfidence()})")
            recognizer.reset()
            _status.update { it.copy(dictation = it.dictation.copy(partial = "", finalText = finalText, error = null)) }
            if (finalText.isNotBlank()) {
                _dictationResults.tryEmit(finalText)
            } else {
                setDictationError("No speech recognized.")
            }
        } finally {
            recorder?.stopAndJoin()
            VoiceMicArbiter.release(SherpaMicOwner.DICTATION)
        }
    }

    private fun setDictationError(message: String) {
        _status.update { it.copy(dictation = it.dictation.copy(error = message, partial = "")) }
    }

    @Synchronized
    private fun asrEngineFor(app: Context, cfg: AndroLlmVoiceConfig, pack: VoiceModelPack): SherpaOnnxStreamingRecognizer? {
        val key = "${pack.id}|${cfg.asrSilenceSeconds}"
        if (asrKey != key) {
            asrEngine?.release()
            asrEngine = SherpaOnnxStreamingRecognizer(app, pack, cfg.asrSilenceSeconds)
            asrKey = key
        }
        val engine = asrEngine ?: return null
        return if (engine.ensureInitialized()) engine else null
    }

    // ── Offline TTS ──────────────────────────────────────────────────────────

    /**
     * Speaks [text] with the selected sherpa voice. Returns a reason string when it
     * cannot, or null when playback was queued. Sentences are synthesized one at a
     * time so playback starts after the first sentence.
     */
    fun speak(context: Context, text: String): String? {
        val app = context.applicationContext
        init(app)
        val cfg = AndroLlmVoicePrefs.current(app)
        if (!cfg.ttsEnabled) return "Enable sherpa offline TTS in Settings → Voice first."
        val pack = VoiceModelCatalog.byId(cfg.ttsPackId) ?: return "No voice selected."
        if (!pack.isInstalled(app)) return "Download the ${pack.title} voice first."
        if (!SherpaNative.isAvailable()) return "sherpa-onnx unavailable: ${SherpaNative.errorMessage}"
        if (JarvisConversationService.isSessionOn.value) return "Jarvis voice session is active - not speaking over it."
        if (text.isBlank()) return "Nothing to speak."
        synchronized(this) {
            val previous = speakJob
            stopSpeakingLocked()
            speakJob = scope.launch(Dispatchers.IO) {
                // A sentence already inside native synthesize() cannot be interrupted;
                // wait for it so the engine is never swapped/released mid-call.
                previous?.join()
                _status.update { it.copy(speaking = true, ttsError = null) }
                try {
                    val engine = ttsEngineFor(app, pack)
                    if (engine == null) {
                        _status.update { it.copy(ttsError = "Voice model failed to load.") }
                        return@launch
                    }
                    val p = player ?: PcmAudioPlayer(app).also { player = it }
                    for (sentence in splitSentences(text)) {
                        currentCoroutineContext().ensureActive()
                        if (JarvisConversationService.isSessionOn.value) break
                        val pcm = engine.synthesize(sentence, cfg.ttsSpeed) ?: continue
                        currentCoroutineContext().ensureActive()
                        if (!p.play(pcm, engine.sampleRate)) break
                    }
                } finally {
                    _status.update { it.copy(speaking = false) }
                }
            }
        }
        return null
    }

    fun stopSpeaking() = synchronized(this) { stopSpeakingLocked() }

    private fun stopSpeakingLocked() {
        speakJob?.cancel()
        speakJob = null
        player?.stopNow()
    }

    @Synchronized
    private fun ttsEngineFor(app: Context, pack: VoiceModelPack): SherpaOnnxOfflineTtsEngine? {
        if (ttsKey != pack.id) {
            ttsEngine?.release()
            ttsEngine = SherpaOnnxOfflineTtsEngine(app, pack)
            ttsKey = pack.id
        }
        val engine = ttsEngine ?: return null
        return if (engine.ensureInitialized()) engine else null
    }

    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+|\n+""")

    private fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    // ── Housekeeping ─────────────────────────────────────────────────────────

    /**
     * Releases any cached engine that uses [pack] (call before deleting its files).
     * Stops dictation/speech that might be using it.
     */
    fun releaseEnginesFor(pack: VoiceModelPack) {
        when (pack.kind) {
            // The wake loop is cancelled by the downloader state change (MODEL_MISSING)
            // and releases its engine itself once the feeding block has finished.
            VoiceModelKind.WAKE_WORD -> Unit
            VoiceModelKind.ASR -> scope.launch {
                // Never release native memory while a feed() may still be running.
                dictationJob?.let { it.cancel(); it.join() }
                synchronized(this@SherpaVoiceController) {
                    if (asrKey?.startsWith(pack.id + "|") == true) {
                        asrEngine?.release(); asrEngine = null; asrKey = null
                    }
                }
            }
            VoiceModelKind.TTS -> scope.launch {
                val job = synchronized(this@SherpaVoiceController) { speakJob.also { stopSpeakingLocked() } }
                job?.join()
                synchronized(this@SherpaVoiceController) {
                    if (ttsKey == pack.id) {
                        ttsEngine?.release(); ttsEngine = null; ttsKey = null
                    }
                }
            }
        }
    }

    /** Re-evaluates wake-word preconditions (e.g. after the mic permission was granted). */
    fun recheck() {
        recheckTick.update { it + 1 }
    }

    /** Re-probes native availability for the status UI. */
    fun refreshNativeStatus() {
        val ok = SherpaNative.isAvailable()
        _status.update { it.copy(nativeAvailable = ok, nativeError = SherpaNative.errorMessage) }
    }
}
