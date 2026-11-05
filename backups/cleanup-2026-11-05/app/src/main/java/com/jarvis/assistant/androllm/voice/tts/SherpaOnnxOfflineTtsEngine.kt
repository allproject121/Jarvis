package com.jarvis.assistant.androllm.voice.tts

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.SherpaNative
import com.jarvis.assistant.androllm.voice.TtsFlavor
import com.jarvis.assistant.androllm.voice.VoiceModelPack
import com.jarvis.assistant.voice.normalize.EnglishTtsNormalizer
import com.jarvis.assistant.voice.normalize.NormalizationSettingsStore
import com.jarvis.assistant.voice.normalize.TextNormalizationPipeline
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * sherpa-onnx offline TTS (VITS / Piper) - ported from AndroLLM's
 * `SherpaOnnxOfflineTtsEngine` + `PiperSpeechSynthesizer`, Hilt removed.
 *
 *  - VITS LJSpeech packs use a plain lexicon; words outside it are re-spelled letter
 *    by letter via [EnglishTtsNormalizer.spellOutOfLexicon] (sherpa drops OOV words).
 *  - Piper packs phonemize with espeak-ng (`dataDir`), so no lexicon pass is needed.
 *
 * Text first goes through Jarvis's shared [TextNormalizationPipeline] (honouring the
 * Settings → Voice normalization toggles). When the user disabled normalization
 * entirely, VITS still cannot read digits/symbols, so the compact
 * [EnglishTtsNormalizer.normalize] is used as the minimum safety net.
 */
class SherpaOnnxOfflineTtsEngine(
    private val context: Context,
    private val pack: VoiceModelPack
) : OfflineTtsEngine {

    private var tts: OfflineTts? = null
    private var lexicon: Set<String>? = null

    /**
     * Providers tried in order (AndroLLM): XNNPACK multi-threaded CPU, then plain CPU.
     * AndroLLM also tried NNAPI first; it is skipped here because NNAPI is deprecated
     * on Android 15 and AndroLLM's own probe frequently rejected it as silent.
     */
    private val providerPriority = listOf("xnnpack", "cpu")

    override val isInitialized: Boolean get() = tts != null

    override val sampleRate: Int get() = tts?.sampleRate() ?: 22050

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (tts != null) return true
        if (!SherpaNative.isAvailable()) {
            Log.w(TAG, "sherpa-onnx native library unavailable: ${SherpaNative.errorMessage}")
            return false
        }
        if (!pack.isInstalled(context)) {
            Log.w(TAG, "TTS pack ${pack.id} not installed")
            return false
        }
        for (provider in providerPriority) {
            if (createTts(provider)) {
                if (pack.ttsFlavor == TtsFlavor.VITS_LEXICON) {
                    lexicon = try {
                        File(pack.dir(context), "lexicon.txt").bufferedReader().useLines { lines ->
                            lines.map { it.substringBefore(' ').lowercase() }
                                .filter { it.isNotEmpty() }
                                .toHashSet()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "TTS lexicon read failed - OOV spelling disabled", t)
                        null
                    }
                }
                Log.i(TAG, "initialized ${pack.id} (provider=$provider)")
                return true
            }
        }
        return false
    }

    private fun createTts(provider: String): Boolean = try {
        val dir = pack.dir(context)
        val vits = OfflineTtsVitsModelConfig().apply {
            model = File(dir, "model.onnx").absolutePath
            tokens = File(dir, "tokens.txt").absolutePath
            when (pack.ttsFlavor) {
                TtsFlavor.PIPER_ESPEAK -> {
                    lexicon = ""
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                }
                else -> lexicon = File(dir, "lexicon.txt").absolutePath
            }
            noiseScale = 0.667f
            noiseScaleW = 0.8f
            lengthScale = 1.0f
        }
        val ttsModel = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = 4
            debug = false
            this.provider = provider
        }
        val config = OfflineTtsConfig().apply {
            model = ttsModel
            silenceScale = 0.8f
        }
        val candidate = OfflineTts(null, config)
        // Probe: some providers build a session but only ever return silence.
        val probe = candidate.generate("test", 0, 1.0f)
        val probePeak = peakAbs(probe.samples)
        if (probePeak < 0.01f) {
            Log.w(TAG, "provider $provider produced silent probe - rejecting")
            runCatching { candidate.release() }
            false
        } else {
            tts = candidate
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "TTS init failed (provider=$provider)", t)
        false
    }

    private fun peakAbs(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * Synthesizes [text] to a full PCM buffer (mono floats). The model loads lazily
     * on first use so it is not pinned in RAM while unused.
     */
    override fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        if (!ensureInitialized()) return null
        val t = tts ?: return null
        return try {
            val normalized = if (NormalizationSettingsStore.current().enabled) {
                TextNormalizationPipeline.getInstance().process(text)
            } else {
                EnglishTtsNormalizer.normalize(text)
            }
            if (normalized.isBlank()) return null
            val covered = lexicon?.let { EnglishTtsNormalizer.spellOutOfLexicon(normalized, it) } ?: normalized
            val audio: GeneratedAudio = t.generate(covered, 0, speed.coerceIn(0.5f, 2.0f))
            audio.samples
        } catch (e: Throwable) {
            Log.e(TAG, "TTS synth failed", e)
            null
        }
    }

    @Synchronized
    override fun release() {
        runCatching { tts?.release() }
        tts = null
    }

    private companion object {
        const val TAG = "SherpaTTS"
    }
}
