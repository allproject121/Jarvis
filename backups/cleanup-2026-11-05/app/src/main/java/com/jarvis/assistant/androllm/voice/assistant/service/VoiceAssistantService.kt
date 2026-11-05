package com.jarvis.assistant.androllm.voice.assistant.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.androllm.chat.ChatPrefs
import com.jarvis.assistant.androllm.chat.model.MessageOrigin
import com.jarvis.assistant.androllm.voice.SherpaMicOwner
import com.jarvis.assistant.androllm.voice.VoiceMicArbiter
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.asr.SpeechRecognizer
import com.jarvis.assistant.androllm.voice.assistant.SpeechOutput
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistantController
import com.jarvis.assistant.androllm.voice.assistant.VoiceEngines
import com.jarvis.assistant.androllm.voice.assistant.VoiceOverlayEvent
import com.jarvis.assistant.androllm.voice.assistant.VoicePhase
import com.jarvis.assistant.androllm.voice.assistant.chat.ChatManager
import com.jarvis.assistant.androllm.voice.assistant.commands.VoiceCommand
import com.jarvis.assistant.androllm.voice.assistant.commands.VoiceCommandRouter
import com.jarvis.assistant.androllm.voice.assistant.ui.AndroVoiceOverlayWindow
import com.jarvis.assistant.androllm.voice.audio.SherpaAudioRecorder
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.androllm.voice.vad.Vad
import com.jarvis.assistant.androllm.voice.wakeword.WakeWordEngine
import com.jarvis.assistant.automation.agent.PendingAction
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.compose.feature.settings.ComposeUiPrefs
import com.jarvis.assistant.ui.compose.model.ThemeMode
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.voice.SentenceAssembler
import com.jarvis.assistant.voice.SpokenChunk
import com.jarvis.assistant.voice.VoiceSounds
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service of the OPTIONAL AndroLLM voice assistant (ported from AndroLLM
 * feature/voice `VoiceAssistantService`; Hilt/Timber removed, Jarvis engines and
 * routing wired in).
 *
 * Owns the full loop — wake word → STT → [ChatManager] (Jarvis ModelRouter) →
 * sentence-level streaming TTS — including energy-VAD barge-in while the model is
 * thinking, spoken yes/no confirmations for Jarvis's high-risk tools, battery-saver
 * and charging-only modes, local voice commands and the floating overlay.
 *
 * Jarvis-first rules:
 *  - never starts (and stops itself immediately) while JarvisConversationService is
 *    running or Jarvis owns the microphone ([VoiceMicArbiter]); Jarvis wins any race;
 *  - holds the mic through [VoiceMicArbiter.acquire] ([SherpaMicOwner.VOICE_ASSISTANT]);
 *  - its own notification channel/id ([VoiceNotifications]) — at most one voice
 *    foreground service runs at a time;
 *  - LLM text goes through ModelRouter only; Gemini Live is never touched.
 *
 * Nothing runs on the UI thread: capture, inference and network all run in [scope].
 */
class VoiceAssistantService : Service() {

    private val controller = VoiceAssistantController
    private lateinit var settingsStore: VoiceSettingsStore
    private lateinit var chatManager: ChatManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var watchersStarted = false
    @Volatile private var recorder: SherpaAudioRecorder? = null
    @Volatile private var output: SpeechOutput? = null
    @Volatile private var wakeEngine: WakeWordEngine? = null
    @Volatile private var recognizer: SpeechRecognizer? = null
    private var overlay: AndroVoiceOverlayWindow? = null
    private var micHeld = false

    /** Set by the overlay Cancel button / "stop speaking"; handleTurn checks it between deltas. */
    @Volatile private var cancelRequested: Boolean = false
    private var elapsedJob: Job? = null
    @Volatile private var turnStartMs: Long = 0L

    /**
     * "Mute" voice command: wake hits only accept "unmute" until it is spoken.
     * In-memory only (a transient "do not disturb", not a preference).
     */
    @Volatile private var muted: Boolean = false

    /** Notification text last posted — dedupe so live metrics don't spam. */
    @Volatile private var lastNotifiedText: String? = null

    /** Suppresses barge-in VAD while a spoken confirmation is asking / listening. */
    private val confirmationActive = AtomicBoolean(false)

    /** Last activity instant (answer delta or tool status) for the first-token timeout. */
    @Volatile private var turnActivityAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        settingsStore = VoiceSettingsStore.get(this)
        chatManager = ChatManager.get(this)
        VoiceNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                // startForegroundService() requires startForeground() within 5 s,
                // even when we are about to refuse.
                if (!startForegroundCompat()) return START_NOT_STICKY
                val blocked = jarvisBlockReason()
                if (blocked != null) {
                    Log.i(TAG, "refusing to start: $blocked")
                    controller.setActive(false)
                    controller.setError(blocked)
                    shutdown()
                    return START_NOT_STICKY
                }
                _isRunning.value = true
                startLoop()
            }
        }
        // Not sticky: an optional mode must never resurrect itself next to Jarvis.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        scope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        stopLoop()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        _isRunning.value = false
    }

    private fun jarvisBlockReason(): String? = when {
        JarvisConversationService.isServiceRunning() -> "Jarvis's voice service is running — AndroLLM voice mode stays off."
        VoiceMicArbiter.jarvisOwnsMic() -> "Jarvis is using the microphone."
        else -> null
    }

    private fun startForegroundCompat(): Boolean {
        val ok = runCatching {
            val notification = VoiceNotifications.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    VoiceNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(VoiceNotifications.NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            // e.g. mic permission revoked: Android would kill us anyway; stop cleanly.
            Log.w(TAG, "startForeground failed — stopping")
            stopSelf()
            _isRunning.value = false
        }
        return ok
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
        if (watchersStarted) return
        watchersStarted = true

        // Jarvis always wins: the moment Jarvis's session / standby wants the mic,
        // this optional mode releases it and stops.
        scope.launch {
            VoiceMicArbiter.jarvisMicBusy.collect { busy ->
                if (busy && _isRunning.value) {
                    Log.i(TAG, "Jarvis took the microphone — stopping AndroLLM voice mode")
                    launchMainThread { shutdown() }
                }
            }
        }
        scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(JARVIS_POLL_MS)
                if (_isRunning.value && JarvisConversationService.isServiceRunning()) {
                    Log.i(TAG, "JarvisConversationService started — stopping AndroLLM voice mode")
                    launchMainThread { shutdown() }
                }
            }
        }

        // Spoken confirmations for Jarvis's high-risk tools (calls, deletes, macros):
        // Jarvis's own approval dialog stays live; the first decision wins.
        scope.launch {
            ToolConfirmationManager.pending.collect { action ->
                if (action != null && controller.state.value.turnActive) {
                    scope.launch {
                        val decision = runCatching { runVoiceConfirmation(action) }.getOrNull()
                        if (decision != null) ToolConfirmationManager.submitDecision(action.id, decision)
                    }
                }
            }
        }

        // Floating overlay + notification follow the controller state. A failed
        // emission (window / permission) must never kill the collector.
        scope.launch {
            controller.state.collect { state ->
                runCatching {
                    val settings = settingsStore.state.value
                    val shouldShow = state.active && state.turnActive &&
                        settings.autoOpenOverlay && !state.overlayDismissed
                    if (shouldShow) {
                        if (overlay == null) {
                            overlay = AndroVoiceOverlayWindow(this@VoiceAssistantService).also { it.show() }
                        }
                    } else {
                        overlay?.hide()
                        overlay = null
                    }
                    val text = when {
                        !state.active -> state.error ?: "Assistant is off"
                        state.phase == VoicePhase.RECEIVING_AUDIO -> "Listening…"
                        state.phase == VoicePhase.STARTING_STT -> "Transcribing…"
                        state.phase == VoicePhase.THINK -> "Thinking…"
                        state.phase == VoicePhase.GENERATING -> "Responding…"
                        state.phase == VoicePhase.SPEAK -> "Speaking…"
                        !settings.enableWakeWord -> "Ready"
                        else -> "Listening for the wake word"
                    }
                    if (text != lastNotifiedText) {
                        lastNotifiedText = text
                        NotificationManagerCompat.from(this@VoiceAssistantService)
                            .notify(VoiceNotifications.NOTIFICATION_ID, VoiceNotifications.build(this@VoiceAssistantService, text))
                    }
                }.onFailure { Log.e(TAG, "Overlay/notification update failed", it) }
            }
        }

        // Overlay button intents → service actions.
        scope.launch {
            controller.overlayEvents.collect { event ->
                when (event) {
                    VoiceOverlayEvent.Cancel -> {
                        cancelRequested = true
                        output?.stopNow()
                    }
                    VoiceOverlayEvent.ToggleMute -> {
                        controller.setMuted(!controller.state.value.muted)
                        if (controller.state.value.muted) output?.stopNow()
                    }
                    VoiceOverlayEvent.OpenChat -> launchMain(ComposeRoutes.CHAT)
                    VoiceOverlayEvent.OpenConversation -> {
                        chatManager.lastConversationId?.let { ChatPrefs.setLastConversationId(this@VoiceAssistantService, it) }
                        launchMain(ComposeRoutes.CHAT)
                        controller.dismissOverlay()
                    }
                    VoiceOverlayEvent.Close -> {
                        controller.dismissOverlay()
                        overlay?.hide()
                        overlay = null
                    }
                }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        confirmationActive.set(false)
        stopElapsedTicker()
        recorder?.stopAndJoin()
        recorder = null
        output?.stopNow()
        releaseEngines()
        releaseMic()
        overlay?.hide()
        overlay = null
        val error = controller.state.value.error
        controller.setActive(false)
        controller.resetAll()
        if (error != null) controller.setError(error)
    }

    private fun releaseEngines() {
        val w = wakeEngine
        val r = recognizer
        val o = output
        wakeEngine = null
        recognizer = null
        output = null
        // Native releases off the main thread; the loop was cancelled first.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { w?.release() }
            runCatching { r?.release() }
            runCatching { o?.release() }
        }
    }

    private fun releaseMic() {
        if (micHeld) {
            VoiceMicArbiter.release(SherpaMicOwner.VOICE_ASSISTANT)
            micHeld = false
        }
    }

    private fun startElapsedTicker() {
        stopElapsedTicker()
        elapsedJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                controller.setElapsedMs(SystemClock.elapsedRealtime() - turnStartMs)
                delay(200)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
        controller.setElapsedMs(0L)
    }

    // ── The main loop ────────────────────────────────────────────────────────

    /** Any stage failure ends the loop gracefully instead of crashing the process. */
    private suspend fun runLoop() {
        try {
            runLoopImpl()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Voice loop failed — stopping assistant gracefully", e)
            recorder?.stopAndJoin()
            recorder = null
            releaseMic()
            controller.setActive(false)
            controller.resetAll()
            controller.setError("Assistant stopped: ${e.message}")
        }
        launchMainThread { if (_isRunning.value) shutdown() }
    }

    private suspend fun runLoopImpl() {
        val initial = settingsStore.current()
        if (!initial.enabled) {
            controller.setActive(false)
            controller.setError("AndroLLM voice mode is off — enable it in Settings → Voice")
            return
        }
        if (initial.chargingOnly && !isCharging()) {
            controller.setActive(false)
            controller.setError("Charging-only mode — plug in to enable the assistant")
            return
        }

        // ── Engines (Jarvis engines are the defaults; missing ones fall back) ──
        val wakeBuilt = if (initial.enableWakeWord) VoiceEngines.wakeWord(this, initial) else null
        val sttBuilt = VoiceEngines.recognizer(this, initial)
        val ttsBuilt = VoiceEngines.speech(this, initial)
        listOfNotNull(wakeBuilt?.note, sttBuilt.note, ttsBuilt.note).firstOrNull()?.let { Log.w(TAG, it) }

        val wake = wakeBuilt?.engine
        val wakeLabel = wakeBuilt?.label ?: "off"
        if (wake != null && !wake.ensureInitialized()) {
            controller.setActive(false)
            controller.setError("$wakeLabel wake word failed to load")
            return
        }
        wakeEngine = wake
        val stt = sttBuilt.engine
        if (!stt.ensureInitialized()) {
            controller.setActive(false)
            controller.setError(
                if (initial.sttEngine == VoiceSettings.STT_WHISPER)
                    "No whisper.cpp model installed (or libwhisper.so missing) — download one in Settings → Voice"
                else "${sttBuilt.label} speech recognition failed to load"
            )
            return
        }
        recognizer = stt
        val tts = ttsBuilt.engine
        output = tts
        Log.i(TAG, "Engines ready: wake=$wakeLabel stt=${sttBuilt.label} tts=${ttsBuilt.label}")

        // ── Microphone (Jarvis first, then other AndroLLM components) ──
        withTimeoutOrNull(2_000L) { VoiceMicArbiter.owner.first { it == SherpaMicOwner.NONE } }
        if (!VoiceMicArbiter.acquire(SherpaMicOwner.VOICE_ASSISTANT)) {
            controller.setActive(false)
            controller.setError("Microphone busy (Jarvis or another AndroLLM voice feature is using it)")
            return
        }
        micHeld = true

        val rec = SherpaAudioRecorder(
            noiseSuppression = initial.noiseSuppression,
            echoCancellation = initial.echoCancellation
        )
        if (!runCatching { rec.start() }.getOrDefault(false)) {
            releaseMic()
            controller.setActive(false)
            controller.setError("Microphone unavailable — grant microphone permission")
            return
        }
        recorder = rec
        controller.resetAll()
        controller.setActive(true)
        controller.setModelInfo(chatManager.activeModelLabel().first, chatManager.activeModelLabel().second)

        var resumeListening = !initial.enableWakeWord
        var framesCount = 0L

        while (currentCoroutineContext().isActive) {
            val settings = settingsStore.current()
            if (!settings.enabled) break
            if (settings.chargingOnly && !isCharging()) break
            if (VoiceMicArbiter.jarvisOwnsMic()) break

            if (!resumeListening) {
                if (wake == null) break // single-turn mode finished
                // ── WAKE ──
                controller.resetTurn()
                controller.setPhase(VoicePhase.LISTENING)
                controller.updateDebugMetrics(
                    micOwner = wakeLabel,
                    onnxStatus = "${sttBuilt.label} ready",
                    threshold = settings.sensitivity
                )
                wake.startSession(settings.wakePhrases)
                val hit = awaitWakeWord(rec, wake, settings.batterySaver) { rms, maxAmp, confidence ->
                    framesCount++
                    controller.updateDebugMetrics(
                        micRms = rms,
                        maxAmplitude = maxAmp,
                        confidenceScore = confidence,
                        framesReceived = framesCount,
                        micOwner = wakeLabel
                    )
                } ?: break

                Log.i(TAG, "WAKE DETECTED -> $hit")
                controller.setPhase(VoicePhase.WAKE_DETECTED)
                controller.setWakeWord(hit)
                beginTurnUi(settings)
            } else if (!controller.state.value.turnActive) {
                beginTurnUi(settings)
            }
            resumeListening = false

            // ── LISTEN ──
            controller.setPhase(VoicePhase.RECEIVING_AUDIO)
            controller.updateDebugMetrics(micOwner = sttBuilt.label)
            val transcript = awaitUtterance(rec, stt, settings) { rms, maxAmp ->
                framesCount++
                controller.updateDebugMetrics(micRms = rms, maxAmplitude = maxAmp, framesReceived = framesCount, micOwner = sttBuilt.label)
            } ?: break
            val trimmed = transcript.trim()

            // While muted only "unmute" is accepted.
            if (muted && VoiceCommandRouter.match(trimmed) != VoiceCommand.Unmute) {
                endTurnUi()
                continue
            }

            if (trimmed.length < 2) {
                Log.i(TAG, "STT: no usable transcript='${trimmed.take(40)}'")
                stopElapsedTicker()
                controller.setTranscript("")
                controller.setPhase(VoicePhase.STARTING_STT)
                delay(TRANSCRIBE_HOLD_MS)
                controller.setAnswer(CATCH_FALLBACK)
                if (settings.autoReadAnswers && !controller.state.value.muted) {
                    speakSentences(rec, tts, listOf(CATCH_FALLBACK), settings)
                }
                controller.setTurnActive(false)
                drainMicEcho(rec, POST_SPEECH_GUARD_MS)
                if (wake == null) break
                continue
            }

            controller.setTranscript(trimmed)
            controller.setPartialTranscript("")
            controller.setPhase(VoicePhase.STARTING_STT)
            delay(TRANSCRIBE_HOLD_MS)

            // ── THINK + SPEAK ──
            controller.setPhase(VoicePhase.THINK)
            controller.updateDebugMetrics(micOwner = "Released")
            val interrupted = handleTurn(rec, tts, trimmed, settings)

            stopElapsedTicker()
            if (!interrupted) {
                controller.setTurnActive(false)
                if (settings.playEndSound) VoiceSounds.playEnd()
                drainMicEcho(rec, POST_SPEECH_GUARD_MS)
            }

            if (interrupted) {
                resumeListening = true
                controller.setPhase(VoicePhase.RECEIVING_AUDIO)
                continue
            }
            // Battery saver never keeps the mic open for hands-free follow-ups.
            if (settings.continuousConversation && !settings.batterySaver) {
                resumeListening = true
                continue
            }
            controller.setPhase(VoicePhase.DONE)
            if (wake == null) break // no wake word: one turn per start
        }

        rec.stopAndJoin()
        recorder = null
        releaseMic()
        controller.updateDebugMetrics(micOwner = "Released", onnxStatus = "Idle")
        controller.setActive(false)
        controller.resetAll()
    }

    private fun beginTurnUi(settings: VoiceSettings) {
        controller.setTurnActive(true)
        turnStartMs = SystemClock.elapsedRealtime()
        val label = chatManager.activeModelLabel()
        controller.setModelInfo(label.first, label.second)
        startElapsedTicker()
        if (settings.playStartSound) VoiceSounds.playStart()
    }

    private fun endTurnUi() {
        stopElapsedTicker()
        controller.setTurnActive(false)
        controller.resetTurn()
    }

    /** Consumes mic chunks until a wake word fires. */
    private suspend fun awaitWakeWord(
        rec: SherpaAudioRecorder,
        wake: WakeWordEngine,
        batterySaver: Boolean,
        onAudioStats: (rms: Float, maxAmp: Float, confidence: Float) -> Unit
    ): String? {
        var lastLog = 0L
        var chunkIndex = 0L
        while (currentCoroutineContext().isActive) {
            val chunk = try {
                rec.chunks.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            if (VoiceMicArbiter.jarvisOwnsMic()) return null
            chunkIndex++

            var sumSq = 0.0
            var maxAmp = 0.0f
            for (s in chunk) {
                sumSq += (s * s).toDouble()
                val a = kotlin.math.abs(s)
                if (a > maxAmp) maxAmp = a
            }
            val rms = kotlin.math.sqrt(sumSq / chunk.size.coerceAtLeast(1)).toFloat()

            // Battery saver: skip the (CPU-heavy) wake model on clearly silent audio.
            // Audio is still fed when anything is audible, so wake phrases are never cut.
            val silent = batterySaver && maxAmp < BATTERY_SAVER_SILENCE_AMP
            val keyword = if (silent) {
                if (chunkIndex % BATTERY_SAVER_SILENT_FEED_EVERY == 0L) wake.feed(chunk) else null
            } else {
                wake.feed(chunk)
            }
            val score = if (keyword != null) 1.0f else (if (maxAmp > 0.05f) 0.15f else 0.02f)
            if (chunkIndex % METRIC_EVERY_CHUNKS == 0L || keyword != null) onAudioStats(rms, maxAmp, score)

            val now = System.currentTimeMillis()
            if (now - lastLog > 5_000) {
                lastLog = now
                Log.d(TAG, "wake metrics rms=$rms max=$maxAmp")
            }
            if (keyword != null) return keyword
        }
        return null
    }

    /**
     * Records the user's utterance until trailing silence / max duration and
     * transcribes it. Live partials are shown while speaking.
     */
    private suspend fun awaitUtterance(
        rec: SherpaAudioRecorder,
        stt: SpeechRecognizer,
        settings: VoiceSettings,
        onAudioStats: (rms: Float, maxAmp: Float) -> Unit
    ): String? {
        val session = try {
            stt.startSession(
                language = settings.sttLanguage,
                translate = settings.sttTranslate,
                numThreads = if (settings.batterySaver) 1 else settings.sttThreads,
                beamSize = settings.sttBeamSize,
                temperature = settings.sttTemperature,
                maxSeconds = settings.sttMaxSeconds,
                streamingEnabled = settings.sttStreaming && !settings.batterySaver
            )
        } catch (e: Throwable) {
            Log.e(TAG, "STT: session start failed", e)
            return null
        }

        var lastPartialRun = 0L
        var firstSpeechAt = -1L
        var sawSpeech = false
        var quietChunks = 0
        var ambientFloor = ENDPOINT_SILENCE_RMS
        var speechPeak = 0f
        val startedAt = SystemClock.elapsedRealtime()
        val maxDurationMs = (settings.sttMaxSeconds * 1000L).coerceAtMost(UTTERANCE_MAX_SECONDS * 1000L)
        val silenceQuietChunks = (settings.silenceTimeoutMs / CHUNK_MS).toInt().coerceIn(2, 30)
        val startGuardUntil = startedAt + START_GUARD_MS
        var stopReason = "cancelled"

        val partialScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lastPartialRef = AtomicReference("")
        val streaming = settings.sttStreaming && !settings.batterySaver

        while (currentCoroutineContext().isActive) {
            val chunk = try {
                rec.chunks.receive()
            } catch (e: CancellationException) {
                partialScope.cancel()
                throw e
            } catch (_: Exception) {
                stopReason = "channel-closed"
                break
            }
            if (VoiceMicArbiter.jarvisOwnsMic()) {
                stopReason = "jarvis-took-mic"
                partialScope.cancel()
                session.cancel()
                session.release()
                return null
            }

            var sumSq = 0.0
            var maxAmp = 0.0f
            for (s in chunk) {
                sumSq += (s * s).toDouble()
                val a = kotlin.math.abs(s)
                if (a > maxAmp) maxAmp = a
            }
            val rms = kotlin.math.sqrt(sumSq / chunk.size.coerceAtLeast(1)).toFloat()
            onAudioStats(rms, maxAmp)
            session.append(chunk)

            val nowRt = SystemClock.elapsedRealtime()
            if (!sawSpeech && rms > 0f && rms < ambientFloor) ambientFloor = rms
            if (!sawSpeech && nowRt >= startGuardUntil && rms > SPEECH_RMS) {
                sawSpeech = true
                firstSpeechAt = nowRt - startedAt
            }
            if (sawSpeech && rms > speechPeak) speechPeak = rms

            if (streaming && sawSpeech && nowRt - lastPartialRun > PARTIAL_EVERY_MS) {
                lastPartialRun = nowRt
                partialScope.launch {
                    val res = runCatching { session.partial() }.getOrNull() ?: return@launch
                    val text = stripWakePrefix(res.text.trim())
                    if (text.isNotBlank() && text != lastPartialRef.get()) {
                        lastPartialRef.set(text)
                        controller.setPartialTranscript(text)
                    }
                }
            }

            // Engine endpointers (sherpa / Vosk) end the turn as soon as they fire.
            if (sawSpeech && session.engineEndpoint && nowRt - startedAt - firstSpeechAt >= MIN_SPEECH_MS) {
                stopReason = "engine-endpoint"
                break
            }

            // Adaptive sentence-end detection (ambient floor + share of speech peak).
            val endpointRms = maxOf(
                ENDPOINT_MIN_RMS,
                ambientFloor * ENDPOINT_AMBIENT_MULT,
                speechPeak * ENDPOINT_PEAK_RATIO
            ).coerceAtMost(SPEECH_RMS * 0.9f)
            if (sawSpeech && nowRt - startedAt - firstSpeechAt >= MIN_SPEECH_MS && rms < endpointRms) {
                if (++quietChunks >= silenceQuietChunks) {
                    stopReason = "endpoint"
                    break
                }
            } else {
                quietChunks = 0
            }
            if (nowRt - startedAt >= maxDurationMs) {
                stopReason = "max-duration"
                break
            }
            if (!sawSpeech && nowRt - startedAt > NO_SPEECH_TIMEOUT_MS) {
                stopReason = "no-speech"
                break
            }
        }

        partialScope.cancel()
        Log.i(TAG, "Recording stopped: reason=$stopReason duration=${SystemClock.elapsedRealtime() - startedAt}ms samples=${session.sampleCount}")
        if (!sawSpeech) {
            session.cancel()
            session.release()
            return ""
        }
        controller.setPhase(VoicePhase.STARTING_STT)
        val result = runCatching { session.finish() }.getOrElse {
            if (it is CancellationException) throw it
            Log.e(TAG, "STT: transcription failed", it)
            null
        }
        session.release()
        val text = stripWakePrefix(result?.text?.trim() ?: "")
        Log.i(TAG, "TRANSCRIPT: '${text.take(100)}' (${result?.engine}, ${result?.inferenceMs ?: 0}ms)")
        return text
    }

    /**
     * Routes one transcript. Returns true when the turn was interrupted by a
     * barge-in or cancel (the caller should listen again).
     */
    private suspend fun handleTurn(
        rec: SherpaAudioRecorder,
        tts: SpeechOutput,
        transcript: String,
        settings: VoiceSettings
    ): Boolean {
        val command = VoiceCommandRouter.match(transcript)
        if (command != null) {
            controller.setPhase(VoicePhase.THINK)
            val ack = executeLocalCommand(command)
            if (ack.isNotBlank()) {
                controller.setAnswer(ack)
                if (settings.autoReadAnswers && !controller.state.value.muted && command != VoiceCommand.DisableVoice) {
                    speakSentences(rec, tts, listOf(ack), settings)
                }
            }
            return false
        }

        controller.setPhase(VoicePhase.THINK)
        cancelRequested = false
        val deltas = Channel<String>(Channel.UNLIMITED)
        val speechQueue = Channel<SpokenChunk>(Channel.UNLIMITED)
        // True while OUR speech plays: barge-in VAD is skipped (echo would cancel us).
        val speechActive = AtomicBoolean(false)
        val answer = StringBuilder()

        val genJob = scope.launch {
            try {
                chatManager.sendMessageStream(
                    content = transcript,
                    origin = MessageOrigin.VOICE,
                    lowLatencyMode = settings.lowLatencyMode,
                    onToolStatus = { status ->
                        turnActivityAt = System.currentTimeMillis()
                        controller.setPhase(VoicePhase.GENERATING)
                        if (answer.isEmpty()) controller.setAnswer(status)
                    }
                ).collect { deltas.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.setError("Generation error: ${e.message}")
            }
        }

        // ── Streaming TTS consumer: speaks each chunk while generation continues ──
        val ttsJob = if (settings.autoReadAnswers && !controller.state.value.muted) {
            scope.launch {
                if (!tts.ensureInitialized()) {
                    Log.w(TAG, "TTS: ${tts.label} not initialized — text still streams in the overlay")
                    return@launch
                }
                try {
                    for (chunk in speechQueue) {
                        if (cancelRequested || !currentCoroutineContext().isActive) break
                        if (controller.state.value.muted) continue
                        val prepared = tts.prepare(chunk.text, settings.speakingSpeed)
                        if (prepared == null) {
                            Log.w(TAG, "TTS: synthesis failed for '${chunk.text.take(40)}'")
                            continue
                        }
                        controller.setPhase(VoicePhase.SPEAK)
                        speechActive.set(true)
                        controller.setSpokenRange(chunk.startOffset, chunk.startOffset + chunk.text.length)
                        val playJob = scope.launch(Dispatchers.IO) { prepared.play() }
                        val progressJob = scope.launch { driveWordProgress(chunk, prepared.durationMs) }
                        while (playJob.isActive) {
                            withTimeoutOrNull(300) { rec.chunks.receive() }
                        }
                        playJob.join()
                        progressJob.cancel()
                        if (!genJob.isCompleted && speechQueue.isEmpty) {
                            controller.setPhase(VoicePhase.GENERATING)
                        }
                    }
                } finally {
                    speechActive.set(false)
                    controller.clearSpoken()
                }
            }
        } else {
            null
        }

        val assembler = SentenceAssembler()
        // Settings → "Natural Sentence Assembler". On: speak each sentence as it
        // streams. Off: hold the whole answer and speak it once the stream ends.
        val streamSentences = runCatching {
            com.jarvis.assistant.JarvisApp.instance.preferences.isSentenceAssemblerEnabled
        }.getOrDefault(true)
        // Barge-in while thinking: only real speech near the mic cancels generation.
        val vad = Vad(threshold = GENERATION_BARGE_IN_THRESHOLD)
        var interrupted = false
        var gotFirstDelta = false
        turnActivityAt = System.currentTimeMillis()
        var lastFlushAt = System.currentTimeMillis()
        while (!genJob.isCompleted || !deltas.isEmpty) {
            if (!speechActive.get() && !confirmationActive.get()) {
                var drained = 0
                while (drained < 20 && !speechActive.get() && !confirmationActive.get() && currentCoroutineContext().isActive) {
                    val chunk = rec.chunks.tryReceive().getOrNull() ?: break
                    if (vad.process(chunk)) {
                        Log.i(TAG, "Barge-in detected while generating")
                        interrupted = true
                        break
                    }
                    drained++
                }
                if (interrupted) break
            }
            if (cancelRequested) {
                interrupted = true
                break
            }

            val delta = withTimeoutOrNull(1000) { deltas.receive() }
            if (delta == null) {
                if (!gotFirstDelta && System.currentTimeMillis() - turnActivityAt > FIRST_TOKEN_TIMEOUT_MS) {
                    Log.w(TAG, "Generation: no first token within ${FIRST_TOKEN_TIMEOUT_MS}ms — falling back")
                    break
                }
                if (ttsJob != null && streamSentences && gotFirstDelta && System.currentTimeMillis() - lastFlushAt > STREAM_FLUSH_IDLE_MS) {
                    assembler.flush()?.let { speechQueue.trySend(it) }
                    lastFlushAt = System.currentTimeMillis()
                }
                continue
            }
            gotFirstDelta = true
            turnActivityAt = System.currentTimeMillis()
            if (controller.state.value.phase == VoicePhase.THINK) controller.setPhase(VoicePhase.GENERATING)
            answer.append(delta)
            controller.setAnswer(answer.toString())
            if (ttsJob != null && streamSentences) assembler.feed(delta).forEach { speechQueue.trySend(it) }
        }

        if (ttsJob != null) {
            if (streamSentences) {
                assembler.flush(force = true)?.let { speechQueue.trySend(it) }
            } else {
                answer.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { speechQueue.trySend(SpokenChunk(it, 0)) }
            }
        }

        if (!interrupted && !gotFirstDelta && genJob.isActive) {
            genJob.cancelAndJoin()
        }

        if (interrupted) {
            genJob.cancel()
            ttsJob?.cancel()
            tts.stopNow()
            speechQueue.close()
        } else {
            if (genJob.isActive) genJob.cancelAndJoin()
            val fullAnswer = answer.toString()
            if (fullAnswer.isNotBlank()) {
                controller.setAnswer(fullAnswer)
                speechQueue.close()
                ttsJob?.join()
                if (cancelRequested) {
                    tts.stopNow()
                } else if (ttsJob != null && controller.state.value.phase == VoicePhase.GENERATING) {
                    controller.setPhase(VoicePhase.SPEAK)
                }
            } else if (controller.state.value.error == null) {
                speechQueue.close()
                ttsJob?.cancel()
                val fallback = "I couldn't generate a response. Check the AI model selected in Jarvis settings."
                controller.setAnswer(fallback)
                if (settings.autoReadAnswers && !controller.state.value.muted) {
                    speakSentences(rec, tts, listOf(fallback), settings)
                }
            } else {
                speechQueue.close()
                ttsJob?.cancel()
            }
        }
        return interrupted
    }

    /**
     * Word-level karaoke highlight while [chunk] plays (timing estimated from the
     * audio duration in proportion to word length — engines give no timestamps).
     */
    private suspend fun driveWordProgress(chunk: SpokenChunk, durationMs: Long) {
        val words = chunk.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || durationMs <= 0) return
        val totalWeight = words.sumOf { it.length + 1 }
        val startedAt = SystemClock.elapsedRealtime()
        var lastIndex = -1
        while (currentCoroutineContext().isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= durationMs) break
            val progress = elapsed.toFloat() / durationMs
            var acc = 0f
            var index = 0
            for (i in words.indices) {
                acc += (words[i].length + 1).toFloat() / totalWeight
                index = i
                if (progress <= acc) break
            }
            if (index != lastIndex) {
                lastIndex = index
                controller.setSpokenWordIndex(index)
            }
            delay(60)
        }
        controller.setSpokenWordIndex(-1)
    }

    /**
     * Speaks sentences one by one, draining the mic meanwhile (barge-in is off while
     * our own voice plays — the speaker echo would trigger it). Returns true when
     * cancelled.
     */
    private suspend fun speakSentences(
        rec: SherpaAudioRecorder,
        tts: SpeechOutput,
        sentences: List<String>,
        settings: VoiceSettings
    ): Boolean {
        if (!tts.ensureInitialized()) {
            Log.w(TAG, "TTS: ${tts.label} not initialized — skipping speech")
            return false
        }
        var offset = 0
        for (sentence in sentences) {
            if (!currentCoroutineContext().isActive || cancelRequested) return true
            val prepared = tts.prepare(sentence, settings.speakingSpeed)
            if (prepared == null) {
                offset += sentence.length
                continue
            }
            controller.setSpokenRange(offset, offset + sentence.length)
            val playJob = scope.launch(Dispatchers.IO) { prepared.play() }
            val progressJob = scope.launch { driveWordProgress(SpokenChunk(sentence, offset), prepared.durationMs) }
            offset += sentence.length
            while (playJob.isActive) {
                withTimeoutOrNull(300) { rec.chunks.receive() }
            }
            playJob.join()
            progressJob.cancel()
        }
        controller.clearSpoken()
        return false
    }

    /**
     * Spoken confirmation for a Jarvis high-risk tool: asks the question aloud and
     * listens for one yes/no reply. Returns null (abstain → Jarvis's dialog decides)
     * when the voice surface cannot ask (muted, setting off, interrupted, no reply).
     */
    private suspend fun runVoiceConfirmation(action: PendingAction): Boolean? {
        val rec = recorder ?: return null
        val stt = recognizer ?: return null
        val tts = output ?: return null
        val settings = settingsStore.current()
        if (!settings.spokenConfirmations || !settings.autoReadAnswers || controller.state.value.muted) return null
        if (!confirmationActive.compareAndSet(false, true)) return null
        try {
            val question = action.speakableQuestion.ifBlank { "Should I ${action.description}? Say yes or no." }
            controller.setAnswer(question)
            val interrupted = speakSentences(rec, tts, listOf(question), settings)
            if (interrupted || cancelRequested) return null
            controller.setPhase(VoicePhase.RECEIVING_AUDIO)
            val reply = awaitUtterance(rec, stt, settings) { _, _ -> }
            Log.i(TAG, "Confirmation reply '${reply?.take(40)}' for ${action.toolName}")
            if (cancelRequested || reply.isNullOrBlank()) return null
            return matchYesNo(reply)
        } finally {
            confirmationActive.set(false)
            controller.setPhase(VoicePhase.GENERATING)
        }
    }

    /** Maps a spoken reply to yes/no. Any "no"-word wins over "yes"; unclear = abstain. */
    private fun matchYesNo(reply: String): Boolean? {
        val t = reply.trim().lowercase().replace(Regex("[.,!?]"), "")
        val noWords = listOf("no", "nope", "cancel", "don't", "dont", "stop", "never", "forget it", "no thanks")
        val yesWords = listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "confirm", "send it", "please")
        fun hits(words: List<String>) = words.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
        if (hits(noWords)) return false
        if (hits(yesWords)) return true
        return null
    }

    /**
     * Discards mic audio after the assistant speaks so the echo of its own voice
     * decays before wake-listening / continuous STT resume; exits early on quiet.
     */
    private suspend fun drainMicEcho(rec: SherpaAudioRecorder, maxMs: Long) {
        val end = SystemClock.elapsedRealtime() + maxMs
        val minDrainUntil = SystemClock.elapsedRealtime() + 500L
        var quietChunks = 0
        while (SystemClock.elapsedRealtime() < end && currentCoroutineContext().isActive) {
            val chunk = withTimeoutOrNull(200) { rec.chunks.receive() } ?: continue
            var energy = 0.0
            for (s in chunk) energy += s.toDouble() * s
            energy /= chunk.size.coerceAtLeast(1)
            if (energy < 0.004) {
                if (SystemClock.elapsedRealtime() >= minDrainUntil && ++quietChunks >= 2) break
            } else {
                quietChunks = 0
            }
        }
    }

    // ── Local commands ───────────────────────────────────────────────────────

    private suspend fun executeLocalCommand(command: VoiceCommand): String = when (command) {
        VoiceCommand.OpenSettings -> {
            launchMain(ComposeRoutes.SETTINGS)
            "Opening settings."
        }
        VoiceCommand.OpenModels -> {
            launchMain(ComposeRoutes.MODELS)
            "Opening the models screen."
        }
        VoiceCommand.DeleteConversation -> {
            val active = chatManager.activeConversation()
            if (active != null && chatManager.deleteConversation(active.id)) "Conversation deleted."
            else "There is no conversation to delete."
        }
        VoiceCommand.SummarizeChat -> chatManager.summarizeActiveConversation()
        VoiceCommand.StartNewChat -> {
            chatManager.activeConversation()?.let { chatManager.deleteConversation(it.id) }
            "Starting a new chat."
        }
        VoiceCommand.StopSpeaking -> {
            output?.stopNow()
            "Stopped."
        }
        VoiceCommand.Mute -> {
            muted = true
            "Muted. Say \"unmute\" when you're done."
        }
        VoiceCommand.Unmute -> {
            muted = false
            "Listening again."
        }
        VoiceCommand.EnableOfflineMode -> {
            settingsStore.update { it.copy(offlineOnly = true, cloudFallback = false) }
            "Offline mode is on. Voice engines will stay on the device."
        }
        VoiceCommand.DisableOfflineMode -> {
            settingsStore.update { it.copy(offlineOnly = false, cloudFallback = true) }
            "Offline mode is off. Cloud voice engines are allowed again."
        }
        VoiceCommand.DisableVoice -> {
            settingsStore.update { it.copy(enabled = false) }
            launchMainThread { shutdown() }
            "Voice assistant disabled."
        }
        VoiceCommand.SwitchTheme -> {
            val next = when (ComposeUiPrefs.themeMode(this)) {
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.SYSTEM
                ThemeMode.SYSTEM -> ThemeMode.LIGHT
                ThemeMode.AMOLED -> ThemeMode.LIGHT
            }
            ComposeUiPrefs.setThemeMode(this, next)
            "Theme switched to ${next.name.lowercase()}."
        }
    }

    /**
     * Brings Jarvis to the front. The requested Compose route is passed as
     * [EXTRA_COMPOSE_ROUTE]; MainActivity may honour it (see merge notes).
     */
    private fun launchMain(route: String) {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_COMPOSE_ROUTE, route)
            }
            startActivity(intent)
        }.onFailure { Log.w(TAG, "Could not open Jarvis: ${it.message}") }
    }

    private fun launchMainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching(block) }
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Removes a leading wake phrase replayed into the transcript ("hey jarvis, ..."). */
    private fun stripWakePrefix(text: String): String {
        val m = WAKE_PREFIX.find(text) ?: return text
        return text.substring(m.range.last + 1).trim()
    }

    companion object {
        private const val TAG = "AndroVoiceService"

        const val ACTION_START = "com.jarvis.assistant.androllm.voice.START"
        const val ACTION_STOP = "com.jarvis.assistant.androllm.voice.STOP"

        /** Compose route requested by a voice command / overlay button (optional MainActivity hook). */
        const val EXTRA_COMPOSE_ROUTE = "androllm_voice_compose_route"

        private val _isRunning = MutableStateFlow(false)
        /** True while this optional service is alive (read by the UI and [com.jarvis.assistant.androllm.voice.assistant.VoiceAssistant]). */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private const val CATCH_FALLBACK = "Sorry — I didn't catch that. Could you say it again?"

        private val WAKE_PREFIX = Regex(
            """^\s*(?i:(?:(?:hey|hi|hello|ok|okay|hei|hai)\s+)?(?:jarvis\w*|maya\w*|wake\s+up\s+maya))[,.!?\s]*"""
        )

        private const val CHUNK_MS = 200L
        private const val PARTIAL_EVERY_MS = 1000L
        private const val START_GUARD_MS = 700L
        private const val SPEECH_RMS = 0.04f
        private const val ENDPOINT_SILENCE_RMS = 0.02f
        private const val ENDPOINT_AMBIENT_MULT = 2.5f
        private const val ENDPOINT_PEAK_RATIO = 0.07f
        private const val ENDPOINT_MIN_RMS = 0.012f
        private const val MIN_SPEECH_MS = 600L
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L
        private const val UTTERANCE_MAX_SECONDS = 30
        private const val FIRST_TOKEN_TIMEOUT_MS = 20_000L
        private const val POST_SPEECH_GUARD_MS = 2_000L
        private const val GENERATION_BARGE_IN_THRESHOLD = 0.02f
        private const val STREAM_FLUSH_IDLE_MS = 1_200L
        private const val TRANSCRIBE_HOLD_MS = 400L
        private const val JARVIS_POLL_MS = 2_000L
        private const val METRIC_EVERY_CHUNKS = 2L
        private const val BATTERY_SAVER_SILENCE_AMP = 0.01f
        private const val BATTERY_SAVER_SILENT_FEED_EVERY = 4L
    }
}
