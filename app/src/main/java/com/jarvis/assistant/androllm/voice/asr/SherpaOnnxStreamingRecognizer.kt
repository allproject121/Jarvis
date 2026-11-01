package com.jarvis.assistant.androllm.voice.asr

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.SherpaNative
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.VoiceModelPack
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx streaming recognizer (ported from AndroLLM, Hilt removed). 100% offline:
 * emits partial transcripts while the user speaks and flags an endpoint after the
 * configured trailing silence.
 *
 * The trailing-silence rule is baked into the native recognizer at creation, so a
 * changed silence setting is applied by creating a new instance (the controller does
 * that) - AndroLLM's in-place `updateSilenceTimeout` only mutated the Kotlin config
 * object after the native recognizer had already been built.
 */
class SherpaOnnxStreamingRecognizer(
    private val context: Context,
    private val pack: VoiceModelPack,
    private val trailingSilenceSeconds: Float = DEFAULT_TRAILING_SILENCE_SECONDS
) : StreamingSpeechRecognizer {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    override val isInitialized: Boolean get() = recognizer != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (recognizer != null) return true
        if (!SherpaNative.isAvailable()) {
            Log.w(TAG, "sherpa-onnx native library unavailable: ${SherpaNative.errorMessage}")
            return false
        }
        if (!pack.isInstalled(context)) {
            Log.w(TAG, "ASR pack ${pack.id} not installed")
            return false
        }
        val dir = pack.dir(context)
        return try {
            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig().apply {
                    sampleRate = VoiceModelCatalog.SAMPLE_RATE
                    featureDim = VoiceModelCatalog.FEATURE_DIM
                }
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig().apply {
                        encoder = File(dir, "encoder.onnx").absolutePath
                        decoder = File(dir, "decoder.onnx").absolutePath
                        joiner = File(dir, "joiner.onnx").absolutePath
                    }
                    tokens = File(dir, "tokens.txt").absolutePath
                    numThreads = 2
                    debug = false
                    provider = "cpu"
                    // sherpa-onnx-streaming-zipformer-en-2023-06-26 is a zipformer2 graph.
                    modelType = "zipformer2"
                }
                enableEndpoint = true
                endpointConfig = EndpointConfig().apply {
                    rule1 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = trailingSilenceSeconds,
                        minUtteranceLength = 0.3f
                    )
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 1.0f,
                        minUtteranceLength = 3.0f
                    )
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 2.0f,
                        minUtteranceLength = 5.0f
                    )
                }
            }
            recognizer = OnlineRecognizer(null, config)
            Log.i(TAG, "Recognizer initialized from ${dir.name} (trailingSilence=${trailingSilenceSeconds}s)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ASR init failed", t)
            false
        }
    }

    override fun startSession() {
        val r = recognizer ?: return
        runCatching { stream?.release() }
        stream = r.createStream("")
    }

    override fun feed(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val st = stream ?: return ""
        return try {
            st.acceptWaveform(samples, VoiceModelCatalog.SAMPLE_RATE)
            while (r.isReady(st)) {
                r.decode(st)
            }
            r.getResult(st).text
        } catch (t: Throwable) {
            Log.e(TAG, "ASR feed failed", t)
            ""
        }
    }

    override fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val st = stream ?: return false
        return runCatching { r.isEndpoint(st) }.getOrDefault(false)
    }

    override fun finalText(): String {
        val r = recognizer ?: return ""
        val st = stream ?: return ""
        return runCatching { r.getResult(st).text }.getOrDefault("")
    }

    override fun lastTokenCount(): Int {
        val r = recognizer ?: return 0
        val st = stream ?: return 0
        return runCatching { r.getResult(st).tokens.size }.getOrDefault(0)
    }

    /**
     * Heuristic confidence: mean joiner probability across emitted tokens
     * (the streaming API has no single confidence field). Diagnostic only.
     */
    override fun estimatedConfidence(): Float {
        val r = recognizer ?: return 0f
        val st = stream ?: return 0f
        return runCatching {
            val probs = r.getResult(st).ysProbs
            if (probs.isEmpty()) 0f else probs.average().toFloat().coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }

    override fun reset() {
        runCatching { stream?.release() }
        stream = null
    }

    @Synchronized
    override fun release() {
        reset()
        runCatching { recognizer?.release() }
        recognizer = null
    }

    companion object {
        private const val TAG = "SherpaASR"
        const val DEFAULT_TRAILING_SILENCE_SECONDS = 2.0f
    }
}
