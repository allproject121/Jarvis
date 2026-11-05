package com.jarvis.assistant.androllm.voice.wakeword

import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * "Jarvis default" wake word for the optional AndroLLM voice assistant: a Vosk
 * phrase-grammar spotter on Jarvis's bundled Vosk model, mirroring the checks
 * JarvisConversationService uses in standby (grammar of wake phrases + `[unk]`,
 * final results only, word-confidence gate).
 *
 * It owns its own [Recognizer] and never touches JarvisConversationService; the
 * assistant service only runs while Jarvis's own service is stopped.
 */
class VoskWakeWordEngine(
    private val context: Context,
    sensitivity: Float = 0.5f
) : WakeWordEngine {

    /** Required average word confidence; sensitivity 0.5 → 0.65 (Jarvis's value). */
    private val minConfidence: Double = (0.9 - sensitivity * 0.5).coerceIn(0.4, 0.9).toDouble()

    private var recognizer: Recognizer? = null
    private var phrases: List<String> = DEFAULT_PHRASES
    private var shortBuf = ShortArray(0)

    override val isInitialized: Boolean get() = recognizer != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (recognizer != null) return true
        val model = VoskModelManager.model ?: run {
            VoskModelManager.init(context.applicationContext, initScope)
            runBlocking { withTimeoutOrNull(60_000L) { VoskModelManager.isModelReady.first { it } } }
            VoskModelManager.model
        } ?: return false
        return try {
            recognizer = Recognizer(model, 16000.0f, grammarFor(phrases)).apply { setWords(true) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Vosk wake recognizer init failed: ${t.message}", t)
            false
        }
    }

    @Synchronized
    override fun startSession(phrases: List<String>) {
        val wanted = phrases.map { it.trim().lowercase() }.filter { it.isNotBlank() }.ifEmpty { DEFAULT_PHRASES }
        if (wanted != this.phrases) {
            this.phrases = wanted
            recognizer?.let { r -> runCatching { r.setGrammar(grammarFor(wanted)) } }
        }
        recognizer?.reset()
    }

    @Synchronized
    override fun feed(samples: FloatArray): String? {
        val r = recognizer ?: return null
        if (shortBuf.size != samples.size) shortBuf = ShortArray(samples.size)
        for (i in samples.indices) shortBuf[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return try {
            if (r.acceptWaveForm(shortBuf, shortBuf.size)) check(r.result) else null
        } catch (t: Throwable) {
            Log.w(TAG, "Vosk wake decode failed: ${t.message}")
            null
        }
    }

    private fun check(hypothesis: String?): String? {
        val json = runCatching { JSONObject(hypothesis ?: "{}") }.getOrNull() ?: return null
        val text = json.optString("text", "").trim().lowercase()
        if (text.isBlank()) return null
        val hit = phrases.firstOrNull { text == it || text.endsWith(" $it") || text.startsWith("$it ") } ?: return null
        val words = json.optJSONArray("result")
        if (words != null && words.length() > 0) {
            var sum = 0.0
            for (i in 0 until words.length()) sum += words.getJSONObject(i).optDouble("conf", 0.0)
            val avg = sum / words.length()
            if (avg < minConfidence) {
                Log.d(TAG, "Wake '$hit' rejected (conf=$avg < $minConfidence)")
                return null
            }
        }
        return hit.uppercase().replace(' ', '_')
    }

    @Synchronized
    override fun reset() {
        recognizer?.reset()
    }

    @Synchronized
    override fun release() {
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun grammarFor(phrases: List<String>): String =
        (phrases.map { "\"${it.replace("\"", "")}\"" } + "\"[unk]\"").joinToString(", ", "[", "]")

    private companion object {
        const val TAG = "VoskWake"
        val DEFAULT_PHRASES = listOf("hey jarvis", "okay jarvis", "jarvis")
        val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
