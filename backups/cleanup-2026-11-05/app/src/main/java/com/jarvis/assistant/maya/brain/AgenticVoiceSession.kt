package com.jarvis.assistant.maya.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.AndroidNativeSpeechRecognizer
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.androllm.voice.AndroLlmVoicePrefs
import com.jarvis.assistant.androllm.voice.SherpaNative
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.asr.SherpaRecognizer
import com.jarvis.assistant.androllm.voice.asr.StreamingSpeechRecognizer
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.util.DevanagariNormalizer
import com.jarvis.assistant.voice.SentenceAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone Advanced-Agentic voice loop: mic → transcriber (Android / Deepgram / sherpa, with
 * the optional backup transcriber) → [AgenticCloudProvider] with tools → [MayaTtsRouter].
 *
 * Owns its own AudioRecord (16 kHz mono PCM16) for the streaming recognizers; the Android
 * transcriber uses the platform SpeechRecognizer which manages the mic itself. Independent
 * of Gemini Live and of JarvisConversationService.
 */
class AgenticVoiceSession(
    context: Context,
    private val onStatus: (AgenticStatus) -> Unit
) {
    companion object {
        private const val TAG = "AgenticVoice"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 3_200 // 200 ms
        private const val SILENCE_WATCHDOG_MS = 1_400L
        private const val MAX_UTTERANCE_MS = 25_000L
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = BrainSettings.get(app)
    private val provider = AgenticCloudProvider.main(app)
    private val history = mutableListOf<ChatTurn>()

    private val running = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val thinking = AtomicBoolean(false)

    @Volatile private var micJob: Job? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var streamingRecognizer: StreamingSpeechRecognizer? = null
    @Volatile private var androidRecognizer: AndroidNativeSpeechRecognizer? = null
    @Volatile var activeTranscriber: String = ""
        private set

    val isRunning: Boolean get() = running.get()

    /** Starts the loop. Returns null on success or a spoken error text. */
    fun start(): String? {
        if (!running.compareAndSet(false, true)) return null
        val cfg = settings.mainBrain()
        if (!cfg.isConfigured) {
            running.set(false)
            val why = cfg.missingReason() ?: BrainHttpClient.MSG_KEY_OR_MODEL
            onStatus(AgenticStatus.Error(why))
            return why
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            val why = "Mic permission nahi hai — Jarvis ko microphone allow karo."
            onStatus(AgenticStatus.Error(why))
            return why
        }
        val chosen = pickTranscriber()
        if (chosen == null) {
            running.set(false)
            val why = "Koi transcriber chal nahi raha — Settings → Voice engine me transcriber check karo."
            onStatus(AgenticStatus.Error(why))
            return why
        }
        activeTranscriber = chosen
        Log.i(TAG, "Advanced Agentic session started (transcriber=$chosen, brain=${cfg.provider.id}/${cfg.model})")
        onStatus(AgenticStatus.Listening(""))
        if (chosen == BrainSettings.TRANSCRIBER_ANDROID) startAndroidRecognizer() else startMicLoop()
        return null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        micJob?.cancel()
        micJob = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { streamingRecognizer?.release() }
        streamingRecognizer = null
        runCatching { androidRecognizer?.destroy() }
        androidRecognizer = null
        MayaTtsRouter.stop()
        scope.cancel()
        onStatus(AgenticStatus.Off)
        Log.i(TAG, "Advanced Agentic session stopped")
    }

    // ── Transcriber selection (primary → backup → Android) ──────────────────

    private fun pickTranscriber(): String? {
        val order = listOf(settings.transcriber, settings.backupTranscriber, BrainSettings.TRANSCRIBER_ANDROID)
            .filter { it.isNotBlank() }.distinct()
        for (id in order) {
            when (id) {
                BrainSettings.TRANSCRIBER_DEEPGRAM -> {
                    val rec = DeepgramStreamingRecognizer(
                        apiKeyProvider = { settings.deepgramKey },
                        endpointingMs = settings.endpointingMs
                    )
                    if (rec.ensureInitialized()) {
                        streamingRecognizer = rec
                        return id
                    }
                    Log.w(TAG, "Deepgram unavailable: ${rec.lastError}")
                    onStatus(AgenticStatus.Notice(rec.lastError ?: DeepgramStreamingRecognizer.ERR_NETWORK))
                }
                BrainSettings.TRANSCRIBER_SHERPA -> {
                    val pack = VoiceModelCatalog.byId(AndroLlmVoicePrefs.current(app).asrPackId)
                    if (pack != null && pack.isInstalled(app) && SherpaNative.isAvailable()) {
                        val rec = SherpaRecognizer(app, pack, (settings.endpointingMs / 1000f).coerceAtLeast(0.3f))
                        if (rec.ensureInitialized()) {
                            streamingRecognizer = rec
                            return id
                        }
                    }
                    Log.w(TAG, "sherpa ASR not installed / native unavailable")
                }
                BrainSettings.TRANSCRIBER_ANDROID -> return id
            }
        }
        return null
    }

    // ── Android SpeechRecognizer path ───────────────────────────────────────

    private fun startAndroidRecognizer() {
        val rec = AndroidNativeSpeechRecognizer(
            context = app,
            onPartialTranscript = { partial ->
                if (!speaking.get() && !thinking.get()) onStatus(AgenticStatus.Listening(DevanagariNormalizer.normalize(partial)))
            },
            onFinalUtterance = { text ->
                if (!speaking.get() && !thinking.get()) handleUtterance(DevanagariNormalizer.normalize(text))
            },
            onOfflineUnavailable = { reason ->
                Log.w(TAG, "Android recognizer unavailable: $reason")
                onStatus(AgenticStatus.Notice("Google transcriber abhi nahi chal raha ($reason)."))
            },
            onRmsChangedCallback = null
        )
        androidRecognizer = rec
        rec.startContinuousListening()
    }

    // ── AudioRecord + StreamingSpeechRecognizer path ────────────────────────

    private fun startMicLoop() {
        val recognizer = streamingRecognizer ?: return
        micJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_SAMPLES * 2 * 4)
                )
            } catch (e: Exception) {
                onStatus(AgenticStatus.Error("Mic open nahi hua: ${e.message}"))
                running.set(false)
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                onStatus(AgenticStatus.Error("Mic initialize nahi hua."))
                running.set(false)
                return@launch
            }
            record = rec
            rec.startRecording()
            recognizer.startSession()
            val shorts = ShortArray(CHUNK_SAMPLES)
            val floats = FloatArray(CHUNK_SAMPLES)
            var lastPartial = ""
            var lastChangeAt = System.currentTimeMillis()
            var utteranceStartAt = 0L
            while (isActive && running.get()) {
                val n = rec.read(shorts, 0, CHUNK_SAMPLES)
                if (n <= 0) { delay(20); continue }
                if (speaking.get() || thinking.get()) {
                    // Half-duplex: drop mic audio while Jarvis thinks/speaks (no self-hearing).
                    continue
                }
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                val chunk = if (n == CHUNK_SAMPLES) floats else floats.copyOf(n)
                val partial = runCatching { recognizer.feed(chunk) }.getOrDefault("")
                val now = System.currentTimeMillis()
                if (partial.isNotBlank() && partial != lastPartial) {
                    if (lastPartial.isBlank()) utteranceStartAt = now
                    lastPartial = partial
                    lastChangeAt = now
                    onStatus(AgenticStatus.Listening(partial))
                }
                val endpoint = recognizer.isEndpoint()
                val stalled = lastPartial.isNotBlank() && now - lastChangeAt > SILENCE_WATCHDOG_MS
                val tooLong = lastPartial.isNotBlank() && utteranceStartAt > 0 && now - utteranceStartAt > MAX_UTTERANCE_MS
                if (endpoint || stalled || tooLong) {
                    val text = runCatching { recognizer.finalText() }.getOrDefault("").ifBlank { lastPartial }
                    lastPartial = ""
                    utteranceStartAt = 0L
                    recognizer.reset()
                    recognizer.startSession()
                    if (text.isNotBlank()) handleUtterance(DevanagariNormalizer.normalize(text))
                }
                (recognizer as? DeepgramStreamingRecognizer)?.lastError?.let { err ->
                    if (!recognizer.isInitialized) {
                        onStatus(AgenticStatus.Notice(err))
                        if (!recognizer.ensureInitialized()) {
                            // Deepgram is gone: fall back to the Android transcriber for this session.
                            Log.w(TAG, "Deepgram lost; switching to Android transcriber")
                            activeTranscriber = BrainSettings.TRANSCRIBER_ANDROID
                            withContext(Dispatchers.Main) { startAndroidRecognizer() }
                            runCatching { rec.stop(); rec.release() }
                            record = null
                            return@launch
                        }
                    }
                }
            }
            runCatching { rec.stop(); rec.release() }
            record = null
        }
    }

    // ── Brain + voice ───────────────────────────────────────────────────────

    private fun handleUtterance(raw: String) {
        val clean = raw.trim()
        if (clean.length <= 2) return
        if (!thinking.compareAndSet(false, true)) return
        scope.launch {
            try {
                MayaTtsRouter.stop()
                androidRecognizer?.pauseListening()
                onStatus(AgenticStatus.Thinking(clean))
                val memory = runCatching { MemoryManager.getInstance(app).getSystemPromptContext() }.getOrDefault("")
                val systemPrompt = BrainPrompts.defaultSystemPrompt(app)
                val accumulated = StringBuilder()
                val assembler = SentenceAssembler()
                val spokenParts = mutableListOf<String>()
                speaking.set(true)
                provider.streamResponse(
                    prompt = clean,
                    systemPrompt = systemPrompt,
                    memoryContext = memory,
                    chatHistory = synchronized(history) { history.toList() }
                ).collect { chunk ->
                    chunk.toolCallDetected?.let { onStatus(AgenticStatus.Tool(it.functionName)) }
                    if (chunk.token.isNotEmpty()) {
                        accumulated.append(chunk.token)
                        for (part in assembler.feed(chunk.token)) {
                            val s = ToolCallParser.cleanConversationalText(part.text).trim()
                            if (s.isNotBlank()) { spokenParts += s; onStatus(AgenticStatus.Speaking(s)); MayaTtsRouter.speak(app, s) }
                        }
                    }
                }
                assembler.flush(force = true)?.let { tail ->
                    val s = ToolCallParser.cleanConversationalText(tail.text).trim()
                    if (s.isNotBlank()) { spokenParts += s; onStatus(AgenticStatus.Speaking(s)); MayaTtsRouter.speak(app, s) }
                }
                val reply = ToolCallParser.cleanConversationalText(accumulated.toString()).trim()
                if (reply.isNotBlank()) {
                    synchronized(history) {
                        history += ChatTurn(userTranscript = clean, jarvisResponse = reply)
                        while (history.size > 10) history.removeAt(0)
                    }
                    runCatching { (app as? JarvisApp)?.chatRepository?.addTurn(clean, reply) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Turn failed: ${e.message}", e)
                onStatus(AgenticStatus.Notice(BrainHttpClient.MSG_NO_ANSWER))
            } finally {
                speaking.set(false)
                thinking.set(false)
                if (running.get()) {
                    onStatus(AgenticStatus.Listening(""))
                    androidRecognizer?.resumeListening()
                }
            }
        }
    }
}
