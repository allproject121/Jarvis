package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JARVIS Neural Voice Engine.
 * Dual-engine architecture:
 * 1. Primary: On-device Neural Text-To-Speech with automatic Hindi (hi_IN) and English (en_US) detection.
 * 2. High-performance native PCM streaming fallback for low-latency vocoder audio via [PocketTtsWrapper].
 */
class PocketTtsEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PocketTtsEngine"
        private const val SAMPLE_RATE_HZ = PocketTtsWrapper.DEFAULT_SAMPLE_RATE_HZ

        @Volatile
        private var instance: PocketTtsEngine? = null

        fun getInstance(context: Context): PocketTtsEngine {
            return instance ?: synchronized(this) {
                instance ?: PocketTtsEngine(context.applicationContext).also { instance = it }
            }
        }

        fun speak(text: String, context: Context, onComplete: (() -> Unit)? = null) {
            getInstance(context).speak(text, onComplete)
        }
    }

    private val nativeWrapper = PocketTtsWrapper()
    private val isSpeaking = AtomicBoolean(false)
    private val isStopRequested = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false
    private var pendingSpeechText: String? = null
    private var pendingSpeechCallback: (() -> Unit)? = null

    init {
        initSystemTts()
        initNativeEngine()
    }

    private fun initSystemTts() {
        try {
            systemTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isSystemTtsReady = true
                    try {
                        val hindiResult = systemTts?.setLanguage(Locale("hi", "IN"))
                        if (hindiResult == TextToSpeech.LANG_MISSING_DATA || hindiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            systemTts?.setLanguage(Locale.US)
                        }
                    } catch (_: Exception) {
                        systemTts?.setLanguage(Locale.US)
                    }
                    systemTts?.setPitch(1.0f)
                    systemTts?.setSpeechRate(1.05f)
                    Log.i(TAG, "System TextToSpeech engine initialized and ready.")

                    val pending = pendingSpeechText
                    val cb = pendingSpeechCallback
                    if (!pending.isNullOrBlank()) {
                        pendingSpeechText = null
                        pendingSpeechCallback = null
                        speak(pending, cb)
                    }
                } else {
                    Log.w(TAG, "System TextToSpeech initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not initialize system TTS: ${e.message}")
        }
    }

    @Synchronized
    private fun initNativeEngine() {
        if (nativeWrapper.isReady()) return

        val result = nativeWrapper.initialize(threads = 2)
        if (result.isSuccess) {
            Log.i(TAG, "PocketTTS native engine initialized via wrapper (id=${result.getOrNull()})")
        } else {
            Log.d(TAG, "PocketTTS native setup notice: ${result.exceptionOrNull()?.message}. Using system neural fallback.")
        }
    }

    fun isReady(): Boolean = isSystemTtsReady || nativeWrapper.isReady()

    /**
     * Synthesizes and speaks text aloud in Hindi or English.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        stop()
        isStopRequested.set(false)
        isSpeaking.set(true)

        // Detect whether the text contains Devanagari (Hindi) characters
        val isHindi = text.any { it in '\u0900'..'\u097F' }

        // Check if System TTS is still asynchronously initializing
        if (!isSystemTtsReady) {
            Log.i(TAG, "System TTS initializing, queuing speech: $text")
            pendingSpeechText = text
            pendingSpeechCallback = onComplete
            if (systemTts == null) {
                initSystemTts()
            }
            return
        }

        // Try System TTS first as reliable, guaranteed speech
        if (isSystemTtsReady && systemTts != null) {
            try {
                val locale = if (isHindi) Locale("hi", "IN") else Locale.US
                systemTts?.language = locale

                val utteranceId = "jarvis_tts_${System.currentTimeMillis()}"
                systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        isSpeaking.set(true)
                    }

                    override fun onDone(id: String?) {
                        isSpeaking.set(false)
                        onComplete?.invoke()
                    }

                    override fun onError(id: String?) {
                        isSpeaking.set(false)
                        onComplete?.invoke()
                    }
                })

                val params = android.os.Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                }
                val res = systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (res == TextToSpeech.SUCCESS) {
                    Log.i(TAG, "Speech successfully dispatched via System TTS (${locale.language})")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "System TTS speech attempt exception: ${e.message}")
            }
        }

        // Fallback to Native PocketTTS PCM streaming
        if (nativeWrapper.isReady()) {
            scope.launch {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(SAMPLE_RATE_HZ * 2 / 10)

                val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE_HZ)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize,
                        AudioTrack.MODE_STREAM
                    )
                }

                audioTrack = track
                track.play()

                try {
                    val synthResult = nativeWrapper.synthesize(text) { buffer, length ->
                        if (isStopRequested.get() || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            return@synthesize false
                        }
                        if (length > 0) {
                            track.write(buffer, 0, length)
                        }
                        true
                    }
                    if (synthResult.isFailure) {
                        Log.w(TAG, "Native synthesis fallback notice: ${synthResult.exceptionOrNull()?.message}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Native synthesis fallback notice: ${e.message}")
                } finally {
                    try {
                        Thread.sleep(150)
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                    audioTrack = null
                    isSpeaking.set(false)
                    onComplete?.invoke()
                }
            }
        } else {
            isSpeaking.set(false)
            onComplete?.invoke()
        }
    }

    /**
     * Immediately stops ongoing audio playback.
     */
    fun stop() {
        isStopRequested.set(true)
        try {
            systemTts?.stop()
        } catch (_: Exception) {}

        nativeWrapper.stop()

        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                    stop()
                }
            }
        } catch (_: Exception) {}

        isSpeaking.set(false)
    }

    fun shutdown() {
        stop()
        try {
            systemTts?.shutdown()
            systemTts = null
        } catch (_: Exception) {}

        nativeWrapper.close()
        isSystemTtsReady = false
    }
}
