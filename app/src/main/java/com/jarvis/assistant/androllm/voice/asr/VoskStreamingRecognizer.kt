package com.jarvis.assistant.androllm.voice.asr

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.androllm.voice.model.VoiceModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * "Jarvis default" speech recognizer for the optional AndroLLM voice assistant:
 * Jarvis's own bundled Vosk model (assets/model-en-us, loaded by [VoskModelManager])
 * driven through the [StreamingSpeechRecognizer] contract.
 *
 * The shared [org.vosk.Model] is only read; each instance owns its own
 * [Recognizer]. The assistant only runs while JarvisConversationService is stopped,
 * so Jarvis's own Vosk recognizers are never active at the same time.
 */
class VoskStreamingRecognizer(
    private val context: Context,
    private val trailingSilenceSeconds: Float = 1.0f
) : StreamingSpeechRecognizer {

    private var recognizer: Recognizer? = null
    private var shortBuf = ShortArray(0)
    private var committed = StringBuilder()
    private var partial = ""
    private var endpoint = false

    override val isInitialized: Boolean get() = recognizer != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (recognizer != null) return true
        val model = VoskModelManager.model ?: run {
            VoskModelManager.init(context.applicationContext, initScope)
            runBlocking { withTimeoutOrNull(MODEL_WAIT_MS) { VoskModelManager.isModelReady.first { it } } }
            VoskModelManager.model
        } ?: run {
            Log.w(TAG, "Vosk model (assets/${VoiceModels.VOSK_MODEL_ASSET}) not ready")
            return false
        }
        return try {
            recognizer = Recognizer(model, VoiceModels.SAMPLE_RATE.toFloat()).apply {
                setWords(true)
                // Vosk endpointer: (start max, end silence (s), max utterance)
                setEndpointerDelays(5.0f, trailingSilenceSeconds.coerceIn(0.3f, 5.0f), 30.0f)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Vosk recognizer init failed: ${t.message}", t)
            false
        }
    }

    @Synchronized
    override fun startSession() {
        recognizer?.reset()
        committed = StringBuilder()
        partial = ""
        endpoint = false
    }

    @Synchronized
    override fun feed(samples: FloatArray): String {
        val r = recognizer ?: return ""
        if (shortBuf.size != samples.size) shortBuf = ShortArray(samples.size)
        for (i in samples.indices) {
            shortBuf[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return try {
            if (r.acceptWaveForm(shortBuf, shortBuf.size)) {
                // Vosk finalized an utterance segment (its own silence endpoint).
                val text = parse(r.result, "text")
                if (text.isNotBlank()) {
                    if (committed.isNotEmpty()) committed.append(' ')
                    committed.append(text)
                    endpoint = true
                }
                partial = ""
            } else {
                partial = parse(r.partialResult, "partial")
            }
            current()
        } catch (t: Throwable) {
            Log.w(TAG, "Vosk decode failed: ${t.message}")
            current()
        }
    }

    private fun current(): String =
        listOf(committed.toString(), partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    override fun isEndpoint(): Boolean = endpoint

    @Synchronized
    override fun finalText(): String {
        val r = recognizer ?: return current()
        val tail = runCatching { parse(r.finalResult, "text") }.getOrDefault("")
        if (tail.isNotBlank()) {
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(tail)
        }
        partial = ""
        return committed.toString().trim()
    }

    @Synchronized
    override fun reset() {
        recognizer?.reset()
        committed = StringBuilder()
        partial = ""
        endpoint = false
    }

    @Synchronized
    override fun release() {
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun parse(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key, "") }.getOrDefault("").trim()

    private companion object {
        const val TAG = "VoskASR"
        const val MODEL_WAIT_MS = 60_000L
        val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
