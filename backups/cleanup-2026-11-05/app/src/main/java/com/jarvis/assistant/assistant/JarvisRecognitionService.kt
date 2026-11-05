package com.jarvis.assistant.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Production-ready Speech Recognition provider for JARVIS.
 * Registered in Android Manifest to serve as a system-level or app-level
 * Speech Recognition provider when JARVIS is invoked by Android or third-party apps.
 */
class JarvisRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "JarvisRecognitionSvc"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var internalRecognizer: SpeechRecognizer? = null
    private var activeCallback: Callback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisRecognitionService created.")
    }

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        activeCallback = callback
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    Log.w(TAG, "No system speech recognition available on device.")
                    callback?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    return@post
                }

                // Clean up any stale recognizer instance
                destroyRecognizer()

                val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "onReadyForSpeech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "onBeginningOfSpeech")
                        try {
                            callback?.beginningOfSpeech()
                        } catch (e: Exception) {
                            Log.w(TAG, "Callback error: ${e.message}")
                        }
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        try {
                            callback?.rmsChanged(rmsdB)
                        } catch (_: Exception) {}
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        try {
                            if (buffer != null) callback?.bufferReceived(buffer)
                        } catch (_: Exception) {}
                    }

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "onEndOfSpeech")
                        try {
                            callback?.endOfSpeech()
                        } catch (_: Exception) {}
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "Internal recognizer error: $error")
                        try {
                            callback?.error(error)
                        } catch (_: Exception) {}
                    }

                    override fun onResults(results: Bundle?) {
                        Log.d(TAG, "onResults")
                        try {
                            if (results != null) {
                                callback?.results(results)
                            } else {
                                val emptyBundle = Bundle().apply {
                                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf())
                                }
                                callback?.results(emptyBundle)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error returning results: ${e.message}")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        try {
                            if (partialResults != null) {
                                callback?.partialResults(partialResults)
                            }
                        } catch (_: Exception) {}
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                internalRecognizer = recognizer

                // Prepare forward intent
                val forwardIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    val lang = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "en-US"
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL) ?: RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) ?: true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, intent?.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3) ?: 3)
                }

                recognizer.startListening(forwardIntent)
                Log.i(TAG, "JarvisRecognitionService successfully started listening.")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onStartListening: ${e.message}", e)
                try {
                    callback?.error(SpeechRecognizer.ERROR_CLIENT)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onStopListening(callback: Callback?) {
        Log.i(TAG, "onStopListening requested")
        mainHandler.post {
            try {
                internalRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error in stopListening: ${e.message}")
            }
        }
    }

    override fun onCancel(callback: Callback?) {
        Log.i(TAG, "onCancel requested")
        mainHandler.post {
            try {
                internalRecognizer?.cancel()
                destroyRecognizer()
            } catch (e: Exception) {
                Log.w(TAG, "Error in cancel: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyRecognizer()
        Log.i(TAG, "JarvisRecognitionService destroyed.")
    }

    private fun destroyRecognizer() {
        try {
            internalRecognizer?.destroy()
        } catch (_: Exception) {}
        internalRecognizer = null
        activeCallback = null
    }
}
