package com.jarvis.assistant.androllm.engine.embedding

import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.androllm.engine.support.map
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LiteRT-backed sentence embedding engine (ported from AndroLLM
 * `engine/embedding/LiteRtEmbeddingEngine`).
 *
 * Pipeline (EmbeddingGemma 300M, `embeddinggemma-300M_seq512_mixed-precision.tflite`):
 *
 *   text → [SentencePieceTokenizer] (Gemma 3 BPE, 262k vocab) → token ids
 *       → pad/truncate to 512 → LiteRT interpreter → L2-normalised embedding
 *
 * The model is a `.tflite` file; the tokenizer is the sibling `tokenizer.model`.
 * All blocking inference runs on [Dispatchers.Default].
 *
 * Jarvis note: Jarvis bundles LiteRT-LM (`litertlm-android`) but NOT the classic
 * LiteRT interpreter (`com.google.ai.edge.litert:litert`). The interpreter is
 * therefore driven reflectively (identical calls to upstream) so this class
 * compiles without the dependency; [isRuntimeAvailable] reports whether the
 * runtime is present, and [loadModel] returns a clear [Result.Error] when not.
 * Optional and unused by default — Jarvis's own TextEmbeddingEngine stays the
 * default embedder.
 */
class LiteRtEmbeddingEngine {

    companion object {
        /** Fully-qualified LiteRT (TFLite) interpreter class, resolved reflectively. */
        const val INTERPRETER_CLASS = "org.tensorflow.lite.Interpreter"

        /** Maximum input sequence length of the EmbeddingGemma model. */
        const val MAX_SEQ_LENGTH = 512

        @Volatile
        private var instance: LiteRtEmbeddingEngine? = null

        /** Process-wide singleton (replaces the upstream Hilt @Singleton binding). */
        fun getInstance(): LiteRtEmbeddingEngine =
            instance ?: synchronized(this) { instance ?: LiteRtEmbeddingEngine().also { instance = it } }

        /** Whether the LiteRT interpreter runtime is on the classpath. */
        fun isRuntimeAvailable(): Boolean = try {
            Class.forName(INTERPRETER_CLASS)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Embedding dimension reported by the loaded model (0 = none). */
    @Volatile
    var dimension: Int = 0
        private set

    /** Absolute path of the loaded `.tflite` model ("" when none). */
    @Volatile
    var loadedModelPath: String = ""
        private set

    /** Absolute path of the loaded tokenizer ("" when none). */
    @Volatile
    var loadedTokenizerPath: String = ""
        private set

    private val mutex = Mutex()

    private var interpreter: ReflectiveInterpreter? = null
    private var tokenizer: SentencePieceTokenizer? = null

    fun isLoaded(): Boolean = interpreter != null && tokenizer != null

    /**
     * Loads the `.tflite` embedding model at [modelPath] and its sibling
     * `tokenizer.model` (or [tokenizerPath]). No-op when the same model is
     * already loaded.
     *
     * @return the embedding dimension on success.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadModel(
        modelPath: String,
        tokenizerPath: String? = null,
        contextLength: Int = 512,
        batchSize: Int = 512,
        threads: Int = 4
    ): Result<Int> = com.jarvis.assistant.androllm.engine.support.runCatching {
        mutex.withLock {
            if (isLoaded() && loadedModelPath == modelPath) {
                dimension
            } else {
                val modelFile = File(modelPath)
                if (!modelFile.exists()) throw IllegalStateException("Embedding model not found: $modelPath")

                val resolvedTokenizer = tokenizerPath
                    ?: modelFile.parentFile?.resolve("tokenizer.model")?.absolutePath
                    ?: ""
                if (resolvedTokenizer.isBlank() || !File(resolvedTokenizer).exists()) {
                    throw IllegalStateException(
                        "Embedding tokenizer not found next to model: $resolvedTokenizer"
                    )
                }
                if (!isRuntimeAvailable()) {
                    throw IllegalStateException(
                        "LiteRT runtime ($INTERPRETER_CLASS) is not bundled in this build; " +
                            "add com.google.ai.edge.litert:litert to enable on-device LiteRT embeddings"
                    )
                }

                withContext(Dispatchers.Default) {
                    closeQuietly()
                    interpreter = ReflectiveInterpreter(modelFile)
                    tokenizer = SentencePieceTokenizer(File(resolvedTokenizer))
                }

                val outputShape = interpreter?.outputShape(0) ?: IntArray(0)
                dimension = outputShape.lastOrNull() ?: 0
                if (dimension <= 0) throw IllegalStateException("Embedding model reported no embedding dimension")
                loadedModelPath = modelFile.absolutePath
                loadedTokenizerPath = resolvedTokenizer
                android.util.Log.i("LiteRtEmbeddingEngine", "Embedding model loaded: dim=$dimension")
                dimension
            }
        }
    }

    /**
     * Encodes [texts] into sentence embeddings (one [FloatArray] per text).
     * Returns an empty list when [texts] is empty. Outputs are L2-normalised.
     */
    suspend fun embed(texts: List<String>): Result<List<FloatArray>> = com.jarvis.assistant.androllm.engine.support.runCatching {
        if (texts.isEmpty()) {
            emptyList()
        } else {
            mutex.withLock {
                val interp = interpreter ?: throw IllegalStateException("Embedding model not loaded")
                val tok = tokenizer ?: throw IllegalStateException("Embedding tokenizer not loaded")
                val dim = dimension

                withContext(Dispatchers.Default) {
                    texts.map { text ->
                        val ids = tok.encodeWithBos(text)
                        val input = IntArray(MAX_SEQ_LENGTH)
                        for (i in 0 until minOf(ids.size, MAX_SEQ_LENGTH)) input[i] = ids[i]

                        val inputArray = arrayOf(input)
                        val outputArray = arrayOf(FloatArray(dim))
                        interp.run(inputArray, outputArray)

                        val raw = outputArray[0]
                        val norm = norm(raw)
                        if (norm > 0f) {
                            FloatArray(raw.size) { i -> raw[i] / norm }
                        } else {
                            raw
                        }
                    }
                }
            }
        }
    }

    /** Encodes a single text. */
    suspend fun embed(text: String): Result<FloatArray> = embed(listOf(text)).map { it.first() }

    /** Unloads the embedding model, freeing its native memory. */
    suspend fun unloadModel(): Result<Unit> = com.jarvis.assistant.androllm.engine.support.runCatching {
        mutex.withLock {
            withContext(Dispatchers.Default) { closeQuietly() }
            dimension = 0
            loadedModelPath = ""
            loadedTokenizerPath = ""
        }
    }

    private fun closeQuietly() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
        interpreter = null
        tokenizer = null
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return kotlin.math.sqrt(sum).toFloat()
    }
}

/**
 * Reflective wrapper over `org.tensorflow.lite.Interpreter` exposing exactly the
 * calls the upstream engine made: `Interpreter(File)`, `getOutputTensor(i).shape()`,
 * `run(input, output)` and `close()`.
 */
private class ReflectiveInterpreter(modelFile: File) {
    private val clazz: Class<*> = Class.forName(LiteRtEmbeddingEngine.INTERPRETER_CLASS)
    private val handle: Any = unwrap { clazz.getConstructor(File::class.java).newInstance(modelFile) }

    fun outputShape(index: Int): IntArray = unwrap {
        val tensor = clazz.getMethod("getOutputTensor", Int::class.javaPrimitiveType).invoke(handle, index)
        if (tensor == null) IntArray(0) else (tensor.javaClass.getMethod("shape").invoke(tensor) as? IntArray ?: IntArray(0))
    }

    fun run(input: Any, output: Any) {
        unwrap { clazz.getMethod("run", Any::class.java, Any::class.java).invoke(handle, input, output) }
    }

    fun close() {
        unwrap { clazz.getMethod("close").invoke(handle) }
    }

    private inline fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        val cause = e.targetException
        if (cause is Exception) throw cause
        throw IllegalStateException(cause?.message ?: "LiteRT interpreter call failed", cause)
    }
}
