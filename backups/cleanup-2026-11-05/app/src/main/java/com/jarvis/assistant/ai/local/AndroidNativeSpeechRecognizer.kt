package com.jarvis.assistant.ai.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Native Android On-Device Speech Recognizer (Option 1).
 * Leverages Google Speech Services offline language packs (hi-IN / en-IN) already installed
 * on Android devices for highly accurate Hindi and Hinglish speech recognition without internet.
 */
class AndroidNativeSpeechRecognizer(
    private val context: Context,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalUtterance: (String) -> Unit,
    private val onOfflineUnavailable: (String) -> Unit,
    private val onRmsChangedCallback: ((Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AndroidNativeASR"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isContinuousModeDesired = false
    private var consecutiveNetworkErrors = 0
    private var consecutiveAudioErrors = 0
    private var consecutiveClientErrors = 0

    init {
        mainHandler.post {
            initRecognizerOnMainThread()
        }
    }

    private fun initRecognizerOnMainThread() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "SpeechRecognizer is not available on this device!")
                onOfflineUnavailable("Speech recognition service not found on device.")
                return
            }

            // Create recognizer: prefer standard createSpeechRecognizer which handles on-device + network seamlessly
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    throw e
                }
            }

            recognizer.setRecognitionListener(createListener())
            speechRecognizer = recognizer
            Log.i(TAG, "Android native SpeechRecognizer successfully initialized on Main thread.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native SpeechRecognizer: ${e.message}", e)
            onOfflineUnavailable("Failed initializing SpeechRecognizer: ${e.message}")
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                isListening = true
                consecutiveAudioErrors = 0
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech: User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                onRmsChangedCallback?.invoke(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech: User paused/stopped speaking")
                isListening = false
            }

            override fun onError(errorCode: Int) {
                isListening = false
                val errorMsg = getErrorDescription(errorCode)
                Log.w(TAG, "SpeechRecognizer onError: code=$errorCode ($errorMsg)")

                when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Normal silence / no match timeout in continuous conversation loop
                        consecutiveNetworkErrors = 0
                        scheduleRestart(250L)
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        consecutiveClientErrors++
                        if (consecutiveClientErrors >= 2) {
                            Log.w(TAG, "Repeated ERROR_CLIENT (running in background service or unsupported). Falling back to Vosk.")
                            onOfflineUnavailable("SpeechRecognizer client error in background service: $errorMsg")
                        } else {
                            scheduleRestart(350L)
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy; safely cancel and restart
                        cancelRecognizer()
                        scheduleRestart(400L)
                    }
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        consecutiveNetworkErrors++
                        Log.w(TAG, "Offline network error ($consecutiveNetworkErrors) - offline language pack might be missing.")
                        if (consecutiveNetworkErrors >= 3) {
                            Log.e(TAG, "Triggering fallback to Vosk due to missing offline Google language pack.")
                            onOfflineUnavailable(errorMsg)
                        } else {
                            scheduleRestart(500L)
                        }
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        consecutiveAudioErrors++
                        Log.w(TAG, "Audio recording conflict ($consecutiveAudioErrors), retrying after delay...")
                        if (consecutiveAudioErrors >= 3) {
                            Log.e(TAG, "Audio conflict persisted. Falling back to Vosk.")
                            onOfflineUnavailable(errorMsg)
                        } else {
                            scheduleRestart(600L)
                        }
                    }
                    else -> {
                        scheduleRestart(500L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                consecutiveNetworkErrors = 0
                consecutiveAudioErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val cleanText = matches?.firstOrNull()?.trim().orEmpty()
                Log.i(TAG, "onResults: '$cleanText'")

                if (cleanText.isNotBlank()) {
                    onFinalUtterance(cleanText)
                } else {
                    scheduleRestart(250L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) {
                    onPartialTranscript(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun isListeningActive(): Boolean = isListening

    fun startContinuousListening() {
        isContinuousModeDesired = true
        mainHandler.post {
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (!isContinuousModeDesired) return
        if (isListening) {
            Log.d(TAG, "startListeningInternal: Already listening, skipping duplicate call.")
            return
        }

        val recognizer = speechRecognizer ?: run {
            initRecognizerOnMainThread()
            speechRecognizer ?: return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "hi-IN", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Prefer on-device offline recognition
                putExtra("android.speech.extra.PREFER_OFFLINE", true)
            }
            recognizer.startListening(intent)
            isListening = true
            Log.i(TAG, "SpeechRecognizer.startListening() initiated with hi-IN & en-IN bilingual preference.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer: ${e.message}", e)
            scheduleRestart(1000L)
        }
    }

    fun pauseListening() {
        isContinuousModeDesired = false
        mainHandler.post {
            cancelRecognizer()
        }
    }

    fun resumeListening() {
        isContinuousModeDesired = true
        mainHandler.post {
            startListeningInternal()
        }
    }

    private fun cancelRecognizer() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isContinuousModeDesired) return
        mainHandler.postDelayed({
            if (isContinuousModeDesired && !isListening) {
                startListeningInternal()
            }
        }, delayMs)
    }

    fun destroy() {
        isContinuousModeDesired = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            isListening = false
        }
    }

    private fun getErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server disconnected / offline unavailable"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
            else -> "SpeechRecognizer error ($errorCode)"
        }
    }
}
