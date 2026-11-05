package com.jarvis.assistant.androllm.voice.wakeword

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.SherpaNative
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.VoiceModelPack
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx keyword-spotter backend for [WakeWordEngine] (ported from AndroLLM,
 * Hilt removed). Loads the downloaded pack from `AndroLlmPaths.voiceModelsDir`
 * (AssetManager = null -> sherpa reads plain file paths).
 *
 * Differences from AndroLLM, on purpose:
 *  - Model files come from a user-downloaded pack, not APK assets.
 *  - AndroLLM's phoneme-substring fallback ("any token containing HH/EY/... counts as
 *    a hit") was a debug workaround for its ARPABET zh-en model and would fire on
 *    almost any speech with this BPE model; only real `result.keyword` hits count.
 *  - Sensitivity maps to the keyword threshold (lower threshold = easier trigger).
 */
class SherpaOnnxWakeWordEngine(
    private val context: Context,
    private val pack: VoiceModelPack,
    private val sensitivity: Float
) : WakeWordEngine {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    override val isInitialized: Boolean get() = spotter != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (spotter != null) return true
        if (!SherpaNative.isAvailable()) {
            Log.w(TAG, "sherpa-onnx native library unavailable: ${SherpaNative.errorMessage}")
            return false
        }
        if (!pack.isInstalled(context)) {
            Log.w(TAG, "KWS pack ${pack.id} not installed")
            return false
        }
        val dir = pack.dir(context)
        val keywords = File(dir, "keywords.txt")
        if (!keywords.exists()) {
            runCatching { keywords.writeText(VoiceModelCatalog.JARVIS_KEYWORDS, Charsets.UTF_8) }
        }
        return try {
            val config = KeywordSpotterConfig().apply {
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
                    numThreads = 1
                    debug = false
                    provider = "cpu"
                    modelType = "zipformer2"
                }
                // AndroLLM's robustness settings: strong per-token boost and a wider
                // beam keep the keyword path alive in beam search.
                maxActivePaths = 10
                keywordsFile = keywords.absolutePath
                keywordsScore = 2.0f
                keywordsThreshold = thresholdFor(sensitivity)
                numTrailingBlanks = 1
            }
            spotter = KeywordSpotter(null, config)
            Log.i(TAG, "KWS loaded from ${dir.name} (threshold=${config.keywordsThreshold})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "KWS init failed", t)
            false
        }
    }

    override fun startSession(phrases: List<String>) {
        val s = spotter ?: return
        runCatching { stream?.release() }
        // Default stream monitors every keyword configured in keywords.txt.
        stream = s.createStream("")
    }

    override fun feed(samples: FloatArray): String? {
        val s = spotter ?: return null
        val st = stream ?: return null
        return try {
            st.acceptWaveform(samples, VoiceModelCatalog.SAMPLE_RATE)
            while (s.isReady(st)) {
                s.decode(st)
            }
            val keyword = s.getResult(st).keyword
            if (keyword.isNotBlank()) {
                Log.i(TAG, "DETECTED: $keyword")
                s.reset(st)
                keyword
            } else null
        } catch (t: Throwable) {
            Log.e(TAG, "KWS feed failed", t)
            null
        }
    }

    override fun reset() {
        runCatching { stream?.let { spotter?.reset(it) } }
    }

    @Synchronized
    override fun release() {
        runCatching { stream?.release() }
        stream = null
        runCatching { spotter?.release() }
        spotter = null
    }

    companion object {
        private const val TAG = "SherpaKWS"

        /** sensitivity 0.1 -> 0.455 (strict) ... 1.0 -> 0.05 (very responsive). */
        fun thresholdFor(sensitivity: Float): Float =
            (0.5f - 0.45f * sensitivity.coerceIn(0.1f, 1.0f)).coerceIn(0.01f, 0.9f)
    }
}
