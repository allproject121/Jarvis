package com.jarvis.assistant.androllm.voice.wakeword

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.voice.model.VoiceModels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * openWakeWord engine on LiteRT (`org.tensorflow.lite.Interpreter` from
 * com.google.ai.edge.litert:litert 2.2.0, which loads its own libLiteRt.so).
 *
 * AndroLLM's `OpenWakeWordEngine` was only a thin alias of its sherpa keyword
 * spotter. Jarvis already ships real openWakeWord models in `assets/wakeword/`
 * (melspectrogram.tflite, embedding_model.tflite and the hey_maya / hello_maya /
 * wake_up_maya classifiers), so this engine runs the genuine openWakeWord
 * streaming pipeline (openwakeword/utils.py `AudioFeatures` + `Model.predict`):
 *
 *  1. audio is processed in 1280-sample (80 ms) frames at 16 kHz, int16 scale;
 *  2. melspectrogram model on the last 1280 + 480 samples → new mel frames,
 *     transformed with `x / 10 + 2`;
 *  3. embedding model on the last 76 mel frames → one 96-d embedding per frame;
 *  4. the classifier on the last N embeddings (N read from its input shape,
 *     16 for the standard models) → score in 0..1, fired at the threshold.
 *
 * All tensor shapes are read from the models at load time so non-default
 * classifiers work as long as they follow the openWakeWord layout.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val classifierName: String = "hey_maya",
    sensitivity: Float = 0.5f
) : WakeWordEngine {

    /** Score threshold; sensitivity 0.5 → openWakeWord's default 0.5. */
    private val threshold: Float = (1.0f - sensitivity).coerceIn(0.1f, 0.9f)

    private var melModel: Interpreter? = null
    private var embModel: Interpreter? = null
    private var clsModel: Interpreter? = null

    private var melBins = 32
    private var embWindow = 76
    private var embDim = 96
    private var clsFrames = 16

    // Streaming buffers (int16-scaled float audio, mel frames, embeddings).
    private val raw = FloatArray(FRAME + MEL_CONTEXT)
    private var pending = FloatArray(0)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    private var framesSinceReset = 0

    /** Last classifier score (debug UI). */
    @Volatile var lastScore: Float = 0f
        private set

    @Volatile var lastError: String? = null
        private set

    override val isInitialized: Boolean get() = clsModel != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (clsModel != null) return true
        return try {
            val options = { Interpreter.Options().setNumThreads(1) }
            val mel = Interpreter(loadAsset(VoiceModels.OWW_MELSPECTROGRAM), options())
            val emb = Interpreter(loadAsset(VoiceModels.OWW_EMBEDDING), options())
            val cls = Interpreter(loadAsset(VoiceModels.openWakeWordClassifier(classifierName)), options())

            val embIn = emb.getInputTensor(0).shape()           // [1, 76, 32, 1]
            if (embIn.size >= 3) {
                embWindow = embIn[1]
                melBins = embIn[2]
            }
            embDim = emb.getOutputTensor(0).numElements().coerceAtLeast(1) // 96
            val clsIn = cls.getInputTensor(0).shape()           // [1, 16, 96]
            if (clsIn.size >= 3) clsFrames = clsIn[1]
            if (clsIn.isNotEmpty() && clsIn.last() != embDim) {
                Log.w(TAG, "Classifier expects ${clsIn.last()} features, embedding gives $embDim")
            }
            melModel = mel
            embModel = emb
            clsModel = cls
            lastError = null
            resetBuffers()
            Log.i(TAG, "openWakeWord ready: $classifierName (mel=$melBins, window=$embWindow, emb=$embDim, frames=$clsFrames, threshold=$threshold)")
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError (LiteRT runtime missing) or a malformed model.
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "openWakeWord init failed: $lastError", t)
            releaseModels()
            false
        }
    }

    override fun startSession(phrases: List<String>) {
        // Keywords are baked into the classifier model; phrases are informational.
        synchronized(this) { resetBuffers() }
    }

    @Synchronized
    override fun feed(samples: FloatArray): String? {
        if (clsModel == null) return null
        // Append the new chunk (float -1..1 → int16 scale, as openWakeWord expects).
        val merged = FloatArray(pending.size + samples.size)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        for (i in samples.indices) merged[pending.size + i] = samples[i] * 32767f
        var offset = 0
        var fired: String? = null
        while (merged.size - offset >= FRAME) {
            val score = processFrame(merged, offset)
            offset += FRAME
            if (score != null) {
                lastScore = score
                if (score >= threshold && fired == null) {
                    fired = classifierName.uppercase()
                }
            }
        }
        pending = merged.copyOfRange(offset, merged.size)
        if (fired != null) {
            Log.i(TAG, "Wake word '$fired' fired (score=$lastScore)")
            resetBuffers()
        }
        return fired
    }

    /** Runs one 80 ms frame through the pipeline; returns the classifier score once warm. */
    private fun processFrame(src: FloatArray, offset: Int): Float? {
        // Slide the raw window: keep MEL_CONTEXT samples of history + the new frame.
        System.arraycopy(raw, FRAME, raw, 0, MEL_CONTEXT)
        System.arraycopy(src, offset, raw, MEL_CONTEXT, FRAME)

        val newMel = runMel(raw) ?: return null
        for (frame in newMel) {
            melFrames.addLast(frame)
            if (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()
        }
        if (melFrames.size < embWindow) return null

        val embedding = runEmbedding() ?: return null
        embeddings.addLast(embedding)
        if (embeddings.size > clsFrames) embeddings.removeFirst()
        if (embeddings.size < clsFrames) return null

        framesSinceReset++
        val score = runClassifier() ?: return null
        // openWakeWord ignores the first predictions after a reset (buffer warm-up).
        return if (framesSinceReset <= WARMUP_PREDICTIONS) 0f else score
    }

    private fun runMel(audio: FloatArray): List<FloatArray>? {
        val model = melModel ?: return null
        return try {
            model.resizeInput(0, intArrayOf(1, audio.size))
            model.allocateTensors()
            val input = floatBuffer(audio.size)
            input.asFloatBuffer().put(audio)
            input.rewind()
            val outCount = model.getOutputTensor(0).numElements()
            val output = floatBuffer(outCount)
            model.run(input, output)
            output.rewind()
            val values = FloatArray(outCount)
            output.asFloatBuffer().get(values)
            val frames = outCount / melBins
            List(frames) { f ->
                FloatArray(melBins) { b -> values[f * melBins + b] / 10f + 2f }
            }
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "melspectrogram failed: ${t.message}")
            null
        }
    }

    private fun runEmbedding(): FloatArray? {
        val model = embModel ?: return null
        return try {
            val input = floatBuffer(embWindow * melBins)
            val fb = input.asFloatBuffer()
            val start = melFrames.size - embWindow
            for (i in start until melFrames.size) fb.put(melFrames[i])
            input.rewind()
            val output = floatBuffer(embDim)
            model.run(input, output)
            output.rewind()
            FloatArray(embDim).also { output.asFloatBuffer().get(it) }
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "embedding failed: ${t.message}")
            null
        }
    }

    private fun runClassifier(): Float? {
        val model = clsModel ?: return null
        return try {
            val input = floatBuffer(clsFrames * embDim)
            val fb = input.asFloatBuffer()
            for (e in embeddings) fb.put(e)
            input.rewind()
            val outCount = model.getOutputTensor(0).numElements().coerceAtLeast(1)
            val output = floatBuffer(outCount)
            model.run(input, output)
            output.rewind()
            output.asFloatBuffer().get(0)
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "classifier failed: ${t.message}")
            null
        }
    }

    @Synchronized
    override fun reset() = resetBuffers()

    private fun resetBuffers() {
        raw.fill(0f)
        pending = FloatArray(0)
        melFrames.clear()
        // openWakeWord seeds the mel buffer with ones so the first embedding can run.
        repeat(embWindow) { melFrames.addLast(FloatArray(melBins) { 1f }) }
        embeddings.clear()
        framesSinceReset = 0
        lastScore = 0f
    }

    @Synchronized
    override fun release() = releaseModels()

    private fun releaseModels() {
        runCatching { melModel?.close() }
        runCatching { embModel?.close() }
        runCatching { clsModel?.close() }
        melModel = null
        embModel = null
        clsModel = null
    }

    /** Reads an asset into a direct buffer (assets may be compressed, so no openFd mmap). */
    private fun loadAsset(path: String): ByteBuffer {
        val bytes = context.assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    private fun floatBuffer(count: Int): ByteBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

    private companion object {
        const val TAG = "OpenWakeWord"
        /** 80 ms @ 16 kHz - openWakeWord's native frame. */
        const val FRAME = 1280
        /** Extra history fed to the melspectrogram model (160 * 3). */
        const val MEL_CONTEXT = 480
        const val MAX_MEL_FRAMES = 10 * 97
        const val WARMUP_PREDICTIONS = 5
    }
}
