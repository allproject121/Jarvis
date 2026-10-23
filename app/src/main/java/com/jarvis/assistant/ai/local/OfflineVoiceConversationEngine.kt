package com.jarvis.assistant.ai.local

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.learning.HabitPatternProfiler
import com.jarvis.assistant.ai.learning.UserCorrectionTracker
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.Locale

sealed class OfflineSessionState {
    object Idle : OfflineSessionState()
    object Listening : OfflineSessionState()
    object Thinking : OfflineSessionState()
    data class Speaking(val text: String) : OfflineSessionState()
    data class Error(val message: String) : OfflineSessionState()
}

/**
 * Complete Offline Voice-to-Voice Conversation Engine for Jarvis.
 * Orchestrates:
 *  1. Vosk Continuous Speech Recognition (Offline Audio -> Text)
 *  2. Semantic RAG Context & Self-Learning Injection
 *  3. Local Small Language Model Inference (Gemma 2B / Qwen 1.5B / Fallback Engine)
 *  4. Low-latency Android Native TTS Voice Playback (Offline Text -> Audio)
 *  5. Direct synchronization with JarvisConversationService UI/Notification state
 */
class OfflineVoiceConversationEngine private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "OfflineVoiceEngine"

        @Volatile
        private var instance: OfflineVoiceConversationEngine? = null

        fun getInstance(context: Context, scope: CoroutineScope): OfflineVoiceConversationEngine {
            return instance ?: synchronized(this) {
                instance ?: OfflineVoiceConversationEngine(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val localAiEngine = LocalAiEngine.getInstance(context, scope)
    private val modelRouter = ModelRouter.getInstance(context, scope)
    private val vectorMemoryEngine = VectorMemoryEngine.getInstance(context, scope)
    private val correctionTracker = UserCorrectionTracker.getInstance(context)
    private val habitProfiler = HabitPatternProfiler.getInstance(context)

    @Volatile
    private var voskRecognizer: Recognizer? = null
    @Volatile
    private var nativeTts: TextToSpeech? = null
    @Volatile
    private var nativeAndroidRecognizer: AndroidNativeSpeechRecognizer? = null
    @Volatile
    private var useNativeRecognizer: Boolean = false

    private val _sessionState = MutableStateFlow<OfflineSessionState>(OfflineSessionState.Idle)
    val sessionState: StateFlow<OfflineSessionState> = _sessionState.asStateFlow()

    private val _lastRecognizedTranscript = MutableStateFlow("")
    val lastRecognizedTranscript: StateFlow<String> = _lastRecognizedTranscript.asStateFlow()

    private val recentTurns = mutableListOf<ChatTurn>()

    init {
        // Automatically create recognizer as soon as Vosk model finishes loading from assets
        scope.launch(Dispatchers.IO) {
            VoskModelManager.isModelReady.collect { ready ->
                if (ready && voskRecognizer == null) {
                    val m = VoskModelManager.model
                    if (m != null) {
                        try {
                            voskRecognizer = createRecognizerInstance(m)
                            Log.i(TAG, "Vosk Recognizer successfully created via isModelReady flow.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed creating Vosk Recognizer on model ready: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun isUsingNativeRecognizer(): Boolean = false
    fun isNativeListening(): Boolean = nativeAndroidRecognizer?.isListeningActive() == true

    private fun createRecognizerInstance(model: Model): Recognizer {
        return try {
            val rec = Recognizer(model, 16000.0f)
            rec.setWords(true)
            Log.i(TAG, "Offline Vosk Recognizer created with open vocabulary successfully.")
            rec
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating open-vocabulary Recognizer: ${e.message}")
            Recognizer(model, 16000.0f)
        }
    }

    /**
     * Ensures Vosk Recognizer is available on demand.
     */
    private fun ensureRecognizer(): Recognizer? {
        voskRecognizer?.let { return it }
        val model = VoskModelManager.model
        if (model != null) {
            try {
                voskRecognizer = createRecognizerInstance(model)
                Log.i(TAG, "Vosk Recognizer lazily initialized on demand.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to lazily create Recognizer: ${e.message}")
            }
        } else {
            VoskModelManager.init(context, scope)
        }
        return voskRecognizer
    }

    /**
     * Initializes the continuous offline recognizers (Android Native SpeechRecognizer primary,
     * Vosk secondary fallback) and TTS voice output.
     */
    suspend fun initialize(tts: TextToSpeech?): Boolean = withContext(Dispatchers.IO) {
        if (tts != null) {
            setTts(tts)
        } else {
            ensureTts()
        }
        ensureRecognizer()
        initNativeSpeechRecognizer()
        return@withContext true
    }

    private fun initNativeSpeechRecognizer() {
        if (nativeAndroidRecognizer != null) return
        try {
            nativeAndroidRecognizer = AndroidNativeSpeechRecognizer(
                context = context,
                onPartialTranscript = { partial ->
                    val normalized = com.jarvis.assistant.util.DevanagariNormalizer.normalize(partial)
                    if (normalized.isNotBlank()) {
                        _sessionState.value = OfflineSessionState.Listening
                        _lastRecognizedTranscript.value = normalized
                        JarvisConversationService.updateConversationStateFromOffline(
                            state = ConversationState.LISTENING,
                            userText = normalized
                        )
                    }
                },
                onFinalUtterance = { finalSpeech ->
                    val normalized = com.jarvis.assistant.util.DevanagariNormalizer.normalize(finalSpeech)
                    if (normalized.isNotBlank()) {
                        _lastRecognizedTranscript.value = normalized
                        Log.i(TAG, "NATIVE ASR → User Utterance Recognized: '$normalized' (raw='$finalSpeech')")
                        handleOfflineUtterance(normalized)
                    }
                },
                onOfflineUnavailable = { reason ->
                    Log.w(TAG, "Native Android SpeechRecognizer offline unavailable ($reason). Falling back to Vosk.")
                    useNativeRecognizer = false
                    JarvisConversationService.resumeAudioRecorderForVosk()
                },
                onRmsChangedCallback = { rmsdB ->
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    JarvisConversationService.updateAudioLevelFromNativeAsr(normalized)
                }
            )
            Log.i(TAG, "Native Android SpeechRecognizer registered successfully in OfflineVoiceConversationEngine.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating Native Android SpeechRecognizer: ${e.message}")
            useNativeRecognizer = false
        }
    }

    fun startListening() {
        if (useNativeRecognizer) {
            nativeAndroidRecognizer?.startContinuousListening()
        }
    }

    fun stopListening() {
        nativeAndroidRecognizer?.pauseListening()
    }

    fun setTts(tts: TextToSpeech) {
        nativeTts = tts
        attachTtsListener(tts)
    }

    private fun ensureTts(): TextToSpeech? {
        if (nativeTts != null) return nativeTts
        try {
            var instance: TextToSpeech? = null
            instance = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val inLocale = Locale("en", "IN")
                    val result = instance?.setLanguage(inLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val hiLocale = Locale("hi", "IN")
                        val hiResult = instance?.setLanguage(hiLocale)
                        if (hiResult == TextToSpeech.LANG_MISSING_DATA || hiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            instance?.language = Locale.US
                        }
                    }
                    instance?.setPitch(1.0f)
                    instance?.setSpeechRate(1.0f)
                    Log.i(TAG, "Self-initialized fallback TextToSpeech instance with Indian phonetics.")
                }
            }
            nativeTts = instance
            instance?.let { attachTtsListener(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating fallback TTS: ${e.message}")
        }
        return nativeTts
    }

    private fun attachTtsListener(tts: TextToSpeech) {
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "Offline TTS onStart: $utteranceId")
                    nativeAndroidRecognizer?.pauseListening()
                    JarvisConversationService.updateConversationStateFromOffline(ConversationState.SPEAKING)
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "Offline TTS onDone: $utteranceId")
                    _sessionState.value = OfflineSessionState.Idle
                    JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
                    nativeAndroidRecognizer?.resumeListening()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "Offline TTS onError: $utteranceId")
                    _sessionState.value = OfflineSessionState.Idle
                    JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
                    nativeAndroidRecognizer?.resumeListening()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed attaching utterance listener to TTS: ${e.message}")
        }
    }

    /**
     * Feeds incoming microphone PCM audio chunks into the continuous offline recognizer.
     * When native Android SpeechRecognizer is actively transcribing, PCM feeding to Vosk
     * is suppressed to prevent duplicated decoding.
     */
    fun processIncomingPcmChunk(pcmData: ByteArray, length: Int) {
        val recognizer = ensureRecognizer() ?: return
        try {
            val isFinalUtterance = recognizer.acceptWaveForm(pcmData, length)
            if (isFinalUtterance) {
                val jsonResult = recognizer.result
                Log.i(TAG, "Vosk acceptWaveForm endpoint detected: $jsonResult")
                handleRecognitionResultJson(jsonResult)
            } else {
                val partialJson = recognizer.partialResult
                extractPartialText(partialJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing offline audio frame: ${e.message}")
        }
    }

    private fun extractPartialText(partialJson: String) {
        try {
            val json = JSONObject(partialJson)
            val rawPartial = json.optString("partial", "").trim()
            val partial = rawPartial.replace("[unk]", "").replace(Regex("\\s+"), " ").trim()
            if (partial.isNotBlank()) {
                _sessionState.value = OfflineSessionState.Listening
                _lastRecognizedTranscript.value = partial
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.LISTENING,
                    userText = partial
                )
            }
        } catch (_: Exception) {}
    }

    /**
     * Called when the silence detector in JarvisConversationService detects the end of user speech.
     * Forces Vosk to return its recognized hypothesis and triggers Local AI response.
     */
    fun finalizeCurrentUtterance() {
        val recognizer = ensureRecognizer() ?: run {
            if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
            return
        }
        try {
            val finalJson = recognizer.finalResult
            Log.i(TAG, "finalizeCurrentUtterance: Vosk finalResult = $finalJson")
            val handled = handleRecognitionResultJson(finalJson)
            if (!handled) {
                if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                    _sessionState.value = OfflineSessionState.Idle
                    JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in finalizeCurrentUtterance: ${e.message}")
            if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
        }
    }

    private fun handleRecognitionResultJson(resultJson: String): Boolean {
        try {
            val json = JSONObject(resultJson)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                val clean = text.replace("[unk]", "").replace(Regex("\\s+"), " ").trim()
                if (clean.isBlank()) {
                    Log.d(TAG, "Ignoring unknown speech/noise result: '$text'")
                    return false
                }
                val words = clean.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
                val noiseWords = setOf("a", "the", "i", "um", "uh", "oh", "to", "so", "is")
                if (words.all { noiseWords.contains(it) } || clean.length <= 2) {
                    Log.d(TAG, "Ignoring noise artifact utterance: '$clean'")
                    return false
                }
                _lastRecognizedTranscript.value = clean
                Log.i(TAG, "OFFLINE ASR → User Utterance Recognized: '$clean'")
                handleOfflineUtterance(clean)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing recognizer result: ${e.message}")
        }
        return false
    }

    /**
     * Executes the conversational loop for recognized offline user speech.
     */
    fun handleOfflineUtterance(userText: String) {
        val normalized = com.jarvis.assistant.util.DevanagariNormalizer.normalize(userText)
        val clean = normalized.trim()
        if (clean.isBlank()) {
            if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
            return
        }
        if (clean.equals("[unk]", ignoreCase = true) || clean.contains("[unk]")) {
            Log.d(TAG, "Ignoring [unk] token safely without disruptive announcement.")
            if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
            return
        }
        val words = clean.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val noiseWords = setOf("a", "the", "i", "um", "uh", "oh", "to", "so", "is")
        if (words.all { noiseWords.contains(it) } || clean.length <= 2) {
            if (_sessionState.value !is OfflineSessionState.Speaking && nativeTts?.isSpeaking != true) {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
            return
        }

        scope.launch(Dispatchers.IO) {

            stopSpeaking()

            // ── 0. OFFLINE COMMAND EXECUTION ENGINE ────────────────────────────
            val app = context.applicationContext as? JarvisApp
            val parsedResult = com.jarvis.assistant.automation.command.CommandParser.parse(clean)

            if (parsedResult.command != null && app != null) {
                Log.i(TAG, "OFFLINE COMMAND DETECTED: ${parsedResult.command} (pattern=${parsedResult.matchedPattern})")
                try {
                    val executionResult = kotlinx.coroutines.withContext(Dispatchers.Main) {
                        app.commandExecutor.execute(parsedResult.command)
                    }
                    val finalReply = if (executionResult.isNotBlank()) executionResult else "Sir, command executed successfully."

                    _sessionState.value = OfflineSessionState.Speaking(finalReply)
                    JarvisConversationService.updateConversationStateFromOffline(
                        state = ConversationState.SPEAKING,
                        replyText = finalReply
                    )
                    speakOfflineChunk(finalReply)

                    synchronized(recentTurns) {
                        recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = finalReply))
                        if (recentTurns.size > 10) recentTurns.removeAt(0)
                    }
                    app.chatRepository.addTurn(userText = clean, jarvisText = finalReply)
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Failed executing offline command: ${e.message}", e)
                }
            }

            // ── 0b. Secondary Direct Intent Handlers ──
            val lower = clean.lowercase(Locale.ROOT)
            val hasOpenVerb = lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") ||
                    lower.contains(" kholo") || lower.contains(" chalao") || lower.contains(" open karo")
            val hasToggleVerb = lower.contains("on") || lower.contains("off") || lower.contains("jalao") || lower.contains("bujhao") ||
                    lower.contains("chalu") || lower.contains("band")

            // Direct Flashlight / Torch Intent
            val hasTorchKeyword = lower.contains("torch") || lower.contains("flashlight") || lower.contains("batti") || lower.contains("flash light") || lower == "torch" || lower == "flashlight"
            if (hasTorchKeyword || (hasToggleVerb && (lower.contains("flash") || lower.contains("light")))) {
                val isTurnOff = lower.contains("off") || lower.contains("band") || lower.contains("bujhao") || lower.contains("stop") || lower.contains("close")
                val turnOn = !isTurnOff
                val torchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    app?.phoneControlManager?.setFlashlight(turnOn) ?: if (turnOn) "Flashlight turned on." else "Flashlight turned off."
                }
                _sessionState.value = OfflineSessionState.Speaking(torchMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = torchMsg
                )
                speakOfflineChunk(torchMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = torchMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = torchMsg)
                return@launch
            }

            // Direct Camera Intent
            val hasCameraKeyword = lower.contains("camera") || lower.contains("camra") || lower.contains("photo") || lower.contains("selfie") || lower.contains("tasveer")
            if (hasCameraKeyword || lower == "camera") {
                val launchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    app?.phoneControlManager?.openApp("camera") ?: "Opening camera."
                }
                _sessionState.value = OfflineSessionState.Speaking(launchMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = launchMsg
                )
                speakOfflineChunk(launchMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = launchMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = launchMsg)
                return@launch
            }

            // Direct WhatsApp Intent
            if (lower.contains("whatsapp") || lower.contains("watsap") || lower == "whatsapp") {
                val launchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    app?.phoneControlManager?.openApp("whatsapp") ?: "Opening WhatsApp."
                }
                _sessionState.value = OfflineSessionState.Speaking(launchMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = launchMsg
                )
                speakOfflineChunk(launchMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = launchMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = launchMsg)
                return@launch
            }

            // Direct YouTube Intent
            if (lower.contains("youtube") || lower.contains("you tube") || lower == "youtube") {
                val launchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    app?.phoneControlManager?.openApp("youtube") ?: "Opening YouTube."
                }
                _sessionState.value = OfflineSessionState.Speaking(launchMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = launchMsg
                )
                speakOfflineChunk(launchMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = launchMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = launchMsg)
                return@launch
            }

            // Direct Payments & Finance Intent
            if (lower.contains("phonepe") || lower.contains("phone pe") || lower.contains("paytm") ||
                lower.contains("gpay") || lower.contains("google pay") || lower.contains("payment app")) {
                val targetApp = when {
                    lower.contains("paytm") -> "paytm"
                    lower.contains("gpay") || lower.contains("google pay") -> "gpay"
                    else -> "phonepe"
                }
                val launchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    app?.phoneControlManager?.openApp(targetApp) ?: "Opening $targetApp."
                }
                _sessionState.value = OfflineSessionState.Speaking(launchMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = launchMsg
                )
                speakOfflineChunk(launchMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = launchMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = launchMsg)
                return@launch
            }

            // Direct Volume & Audio Intent (STRICT: Requires explicit action or status inquiry)
            val hasVolumeWord = lower.contains("volume") || lower.contains("aawaz") || lower.contains("awaz")
            val hasVolumeAction = lower.contains("up") || lower.contains("down") || lower.contains("badhao") ||
                    lower.contains("kam") || lower.contains("tez") || lower.contains("mute") || lower.contains("chup") ||
                    lower.contains("kitna") || lower.contains("check") || lower.contains("level")
            if (hasVolumeWord && hasVolumeAction) {
                val volMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    if (audioManager != null) {
                        if (lower.contains("up") || lower.contains("badhao") || lower.contains("tez") || lower.contains("increase") || lower.contains("raise")) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                            "Sir, media volume badha di gayi hai."
                        } else if (lower.contains("down") || lower.contains("kam") || lower.contains("ghatao") || lower.contains("decrease") || lower.contains("slow")) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                            "Sir, media volume kam kar di gayi hai."
                        } else if (lower.contains("mute") || lower.contains("chup") || lower.contains("shant")) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                            "Sir, audio mute kar diya gaya hai."
                        } else {
                            val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val pct = ((current.toFloat() / max.coerceAtLeast(1).toFloat()) * 100).toInt()
                            "Sir, current media volume $pct percent par hai."
                        }
                    } else {
                        "Volume controls unavailable."
                    }
                }
                _sessionState.value = OfflineSessionState.Speaking(volMsg)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = volMsg
                )
                speakOfflineChunk(volMsg)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = volMsg))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = volMsg)
                return@launch
            }

            // Direct App Launching fallback (e.g., "kholo", "open", "launch", "chalao")
            val openMatch = Regex("(?i)^(?:kripya\\s+|please\\s+)?(?:open|launch|start|kholo|chalao)\\s+([a-zA-Z0-9 ]+)").find(clean)
                ?: Regex("(?i)([a-zA-Z0-9 ]+)\\s+(?:kholo|chalao|open\\s+karo)$").find(clean)

            if (openMatch != null && app != null) {
                val appName = openMatch.groupValues[1].trim()
                if (appName.isNotBlank() && appName.length > 2 && !appName.equals("jarvis", ignoreCase = true)) {
                    val launchMsg = kotlinx.coroutines.withContext(Dispatchers.Main) {
                        app.phoneControlManager.openApp(appName)
                    }
                    _sessionState.value = OfflineSessionState.Speaking(launchMsg)
                    JarvisConversationService.updateConversationStateFromOffline(
                        state = ConversationState.SPEAKING,
                        replyText = launchMsg
                    )
                    speakOfflineChunk(launchMsg)

                    synchronized(recentTurns) {
                        recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = launchMsg))
                    }
                    app.chatRepository.addTurn(userText = clean, jarvisText = launchMsg)
                    return@launch
                }
            }

            // Direct Battery Status Intent (STRICT: Must have battery or specific charge query)
            if (lower.contains("battery") || lower.contains("kitna charge") || lower.contains("battery kitni") || lower.contains("battery level") || lower.contains("charge kitna") || lower.contains("device charge")) {
                val telem = com.jarvis.assistant.util.DeviceTelemetryHelper.getTelemetry(context)
                val status = if (telem.isCharging) ", aur device charge ho raha hai" else ""
                val reply = "Sir, current battery level ${telem.batteryPercent}% hai$status. Power cores normal hain."

                _sessionState.value = OfflineSessionState.Speaking(reply)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = reply
                )
                speakOfflineChunk(reply)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = reply))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = reply)
                return@launch
            }

            // Direct Academic / Physics / Science Lookup (STRICT: only triggers if explicit science intent)
            val academicFact = com.jarvis.assistant.ai.memory.AcademicKnowledgeVault.findRelevantFact(clean)
            if (academicFact != null) {
                val reply = "Sir, $academicFact"
                _sessionState.value = OfflineSessionState.Speaking(reply)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = reply
                )
                speakOfflineChunk(reply)

                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = reply))
                }
                app?.chatRepository?.addTurn(userText = clean, jarvisText = reply)
                return@launch
            }

            // 1. Check for user corrections (Self-learning loop)
            val lastAssistantReply = recentTurns.lastOrNull()?.jarvisResponse.orEmpty()
            val wasCorrection = correctionTracker.analyzeSpeechForCorrection(clean, lastAssistantReply)
            if (wasCorrection) {
                Log.i(TAG, "User correction detected and stored in memory!")
                (context.applicationContext as? JarvisApp)?.museExperienceEngine?.recordTrajectory(
                    goal = lastAssistantReply.take(40),
                    stepsTaken = listOf("voice_turn"),
                    wasSuccessful = false,
                    feedback = clean
                )
            }

            // 2. Track habit profile
            habitProfiler.recordAction(clean.take(30))

            // 3. Assemble dynamic system prompt with Vector RAG, Learned Corrections & MUSE Experiences
            val ragContext = vectorMemoryEngine.buildMemoryRAGContext(clean)
            val correctionContext = correctionTracker.buildLearnedCorrectionContext()
            val habitContext = habitProfiler.buildHabitContext()
            val museContext = (context.applicationContext as? JarvisApp)?.museExperienceEngine?.buildExperienceContext(clean).orEmpty()

            val combinedSystemPrompt = buildString {
                append("You are JARVIS, an ultra-capable offline AI assistant created by Roshan Sir.\n")
                append("Speak concisely, confidently, and naturally in 1-2 short sentences.\n")
                if (ragContext.isNotBlank()) append("\n").append(ragContext)
                if (correctionContext.isNotBlank()) append("\n").append(correctionContext)
                if (habitContext.isNotBlank()) append("\n").append(habitContext)
                if (museContext.isNotBlank()) append("\n").append(museContext)
            }

            // 4. Generate streaming response from Local AI
            val responseAccumulator = StringBuilder()
            val sentenceAccumulator = StringBuilder()

            modelRouter.routeConversationalQuery(
                userPrompt = clean,
                systemPrompt = combinedSystemPrompt,
                openAiApiKey = (context.applicationContext as? JarvisApp)?.preferences?.apiKey.orEmpty(),
                chatHistory = recentTurns
            ).collect { token ->
                responseAccumulator.append(token)
                sentenceAccumulator.append(token)

                // Low-latency sentence boundary voice synthesis (speaks immediately upon punctuation)
                val currentText = sentenceAccumulator.toString()
                if (currentText.contains(".") || currentText.contains("!") || currentText.contains("?") || currentText.contains("\n")) {
                    speakOfflineChunk(currentText.trim())
                    sentenceAccumulator.clear()
                }
            }

            // Flush remaining text
            if (sentenceAccumulator.isNotBlank()) {
                speakOfflineChunk(sentenceAccumulator.toString().trim())
            }

            val finalReply = responseAccumulator.toString().trim()
            if (finalReply.isNotBlank()) {
                _sessionState.value = OfflineSessionState.Speaking(finalReply)
                JarvisConversationService.updateConversationStateFromOffline(
                    state = ConversationState.SPEAKING,
                    replyText = finalReply
                )

                // 5. Store turn into recent history
                synchronized(recentTurns) {
                    recentTurns.add(ChatTurn(userTranscript = clean, jarvisResponse = finalReply))
                    if (recentTurns.size > 10) recentTurns.removeAt(0)
                }

                // 6. Persist to App Chat Repository so conversation displays in Chat History
                (context.applicationContext as? JarvisApp)?.chatRepository?.addTurn(
                    userText = clean,
                    jarvisText = finalReply
                )

                Log.i(TAG, "OFFLINE AI Response generated: '$finalReply'")
            } else {
                _sessionState.value = OfflineSessionState.Idle
                JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
            }
        }
    }

    private fun speakOfflineChunk(text: String) {
        if (text.isBlank()) return
        Log.d(TAG, "TTS Offline Speaking: '$text'")
        val tts = ensureTts()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "offline_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        nativeTts?.stop()
        _sessionState.value = OfflineSessionState.Idle
        JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
    }

    fun reset() {
        stopSpeaking()
        voskRecognizer?.reset()
        _lastRecognizedTranscript.value = ""
        _sessionState.value = OfflineSessionState.Idle
        JarvisConversationService.updateConversationStateFromOffline(ConversationState.IDLE)
    }
}
