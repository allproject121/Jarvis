package com.jarvis.assistant.androllm.voice.asr

import android.content.Context
import com.jarvis.assistant.androllm.voice.VoiceModelPack

/**
 * sherpa-onnx streaming Zipformer ASR as a [StreamingSpeechRecognizer] (ported from
 * AndroLLM `SherpaRecognizer`; Hilt removed).
 *
 * [updateSilenceTimeout] rebuilds the native recognizer because sherpa bakes the
 * endpoint rule into the native object at creation (see [SherpaOnnxStreamingRecognizer]).
 */
class SherpaRecognizer(
    private val context: Context,
    private val pack: VoiceModelPack,
    trailingSilenceSeconds: Float = SherpaOnnxStreamingRecognizer.DEFAULT_TRAILING_SILENCE_SECONDS
) : StreamingSpeechRecognizer {

    @Volatile private var silenceSeconds: Float = trailingSilenceSeconds
    @Volatile private var recognizer = SherpaOnnxStreamingRecognizer(context, pack, trailingSilenceSeconds)

    override val isInitialized: Boolean
        get() = recognizer.isInitialized

    override fun ensureInitialized(): Boolean = recognizer.ensureInitialized()

    @Synchronized
    override fun updateSilenceTimeout(seconds: Float) {
        if (seconds == silenceSeconds) return
        val wasInitialized = recognizer.isInitialized
        recognizer.release()
        silenceSeconds = seconds
        recognizer = SherpaOnnxStreamingRecognizer(context, pack, seconds)
        if (wasInitialized) recognizer.ensureInitialized()
    }

    override fun startSession() = recognizer.startSession()

    override fun feed(samples: FloatArray): String = recognizer.feed(samples)

    override fun isEndpoint(): Boolean = recognizer.isEndpoint()

    override fun finalText(): String = recognizer.finalText()

    override fun lastTokenCount(): Int = recognizer.lastTokenCount()

    override fun estimatedConfidence(): Float = recognizer.estimatedConfidence()

    override fun reset() = recognizer.reset()

    override fun release() = recognizer.release()
}
