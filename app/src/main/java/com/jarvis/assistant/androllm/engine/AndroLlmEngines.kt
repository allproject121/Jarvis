package com.jarvis.assistant.androllm.engine

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.AndroLlmPaths
import com.jarvis.assistant.androllm.engine.api.DefaultEngineRepository
import com.jarvis.assistant.androllm.engine.api.EngineRepository
import com.jarvis.assistant.androllm.engine.backend.BackendCapabilities
import com.jarvis.assistant.androllm.engine.backend.HardwareBackendProbe
import com.jarvis.assistant.androllm.engine.core.LiteRtLmEngine
import com.jarvis.assistant.androllm.engine.embedding.LiteRtEmbeddingEngine
import com.jarvis.assistant.androllm.engine.models.BackendType
import com.jarvis.assistant.androllm.engine.models.ModelLoadConfig
import com.jarvis.assistant.androllm.engine.support.DownloadStatus
import com.jarvis.assistant.androllm.engine.support.Model
import com.jarvis.assistant.androllm.engine.support.ModelFormat
import com.jarvis.assistant.androllm.engine.support.Result
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One LiteRT-LM model file found in [AndroLlmPaths.liteRtModelsDir]. */
data class LocalLiteRtModel(
    val id: String,
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val format: ModelFormat
)

/**
 * Manual object graph for the AndroLLM LiteRT-LM engine (replaces the Hilt
 * `EngineModule`): one [LiteRtLmEngine] behind one [DefaultEngineRepository],
 * plus the optional [LiteRtEmbeddingEngine].
 *
 * Optional secondary engine — nothing here loads a model until a caller asks
 * (the LOCAL_LITERT router mode, the Developer screen or the Engines settings
 * section). Jarvis's llama.cpp LocalAiEngine and Gemini Live are untouched.
 *
 * Preferences (engine-private SharedPreferences `androllm_engine_prefs`):
 * - selected model file (absolute path; blank = first model found),
 * - backend preference AUTO / CPU / GPU / NPU (default AUTO = NPU → GPU → CPU).
 */
class AndroLlmEngines private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val engine: LiteRtLmEngine = LiteRtLmEngine(appContext)
    val repository: EngineRepository = DefaultEngineRepository(engine)

    /** Optional LiteRT embedder (requires the LiteRT interpreter runtime; see class docs). */
    val embeddingEngine: LiteRtEmbeddingEngine get() = LiteRtEmbeddingEngine.getInstance()

    private val loadMutex = Mutex()

    @Volatile
    private var initialized = false

    /** Whether the user enabled the LiteRT engine feature (default OFF). */
    fun isFeatureEnabled(): Boolean = AndroLlmFlags.isEnabled(appContext, AndroLlmFlags.LITERT_ENGINE)

    fun setFeatureEnabled(enabled: Boolean) =
        AndroLlmFlags.setEnabled(appContext, AndroLlmFlags.LITERT_ENGINE, enabled)

    // ── preferences ──────────────────────────────────────────────────────

    var backendPreference: BackendType
        get() = runCatching { BackendType.valueOf(prefs.getString(KEY_BACKEND, BackendType.AUTO.name)!!) }
            .getOrDefault(BackendType.AUTO)
            .let { if (it in USER_BACKENDS) it else BackendType.AUTO }
        set(value) {
            prefs.edit().putString(KEY_BACKEND, (if (value in USER_BACKENDS) value else BackendType.AUTO).name).apply()
        }

    var selectedModelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MODEL_PATH, value).apply()
        }

    // ── discovery ────────────────────────────────────────────────────────

    /** Models directory shared with the model catalog/downloads feature. */
    fun modelsDir(): File = AndroLlmPaths.liteRtModelsDir(appContext)

    /**
     * Scans [modelsDir] (recursively, depth ≤ 3) for LiteRT-LM chat containers
     * (`.litertlm`, `.task`). Partial downloads (`.part`, `.tmp`, `.download`)
     * and plain `.tflite` files (embedding models) are skipped.
     */
    fun listLocalModels(): List<LocalLiteRtModel> {
        val dir = modelsDir()
        return dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { f ->
                val ext = f.extension.lowercase(Locale.US)
                val format = when (ext) {
                    "litertlm" -> ModelFormat.LITERTLM
                    "task" -> ModelFormat.LITERTLM
                    else -> null
                } ?: return@mapNotNull null
                LocalLiteRtModel(
                    id = f.nameWithoutExtension,
                    name = f.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim(),
                    file = f,
                    sizeBytes = f.length(),
                    format = format
                )
            }
            .sortedBy { it.name.lowercase(Locale.US) }
            .toList()
    }

    /** `.tflite` embedding models with a sibling `tokenizer.model` (for [embeddingEngine]). */
    fun listEmbeddingModels(): List<File> =
        modelsDir().walkTopDown().maxDepth(3)
            .filter { it.isFile && it.extension.equals("tflite", ignoreCase = true) }
            .filter { File(it.parentFile, "tokenizer.model").exists() }
            .toList()

    /** The model the user picked, else the first discovered one. */
    fun resolveSelectedModel(): LocalLiteRtModel? {
        val all = listLocalModels()
        val path = selectedModelPath
        return all.firstOrNull { it.file.absolutePath == path } ?: all.firstOrNull()
    }

    /**
     * Builds the engine's [Model] descriptor for a discovered file. NPU is
     * only attempted for files whose name marks a SoC-specific NPU build, or
     * when the user explicitly asked for NPU (the engine then reports a clear
     * error instead of silently falling back).
     */
    fun toEngineModel(local: LocalLiteRtModel, preference: BackendType = backendPreference): Model {
        val lower = local.file.name.lowercase(Locale.US)
        val npuBuild = NPU_NAME_HINTS.any { lower.contains(it) }
        return Model(
            id = local.id,
            name = local.name,
            filePath = local.file.absolutePath,
            fileSize = local.sizeBytes,
            format = local.format,
            isDownloaded = true,
            downloadStatus = DownloadStatus.DOWNLOADED,
            architecture = guessArchitecture(lower),
            family = guessFamily(lower),
            supportsCpu = true,
            supportsGpu = true,
            supportsNpu = npuBuild || preference == BackendType.NPU
        )
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /** Initializes the engine once (hardware probe; no model load). */
    suspend fun ensureInitialized(): Result<Unit> {
        if (initialized) return Result.Success(Unit)
        val r = repository.initialize()
        if (r is Result.Success) initialized = true
        return r
    }

    /** Probes CPU/GPU/NPU availability without initializing the engine. */
    suspend fun probeHardware(): BackendCapabilities = withContext(Dispatchers.Default) {
        runCatching { HardwareBackendProbe.probe(appContext) }.getOrDefault(BackendCapabilities.UNKNOWN)
    }

    /**
     * Loads [local] (or the selected model) with the user's backend preference.
     * No-op when that model is already resident on the requested backend.
     */
    suspend fun loadSelected(local: LocalLiteRtModel? = null): Result<Unit> = loadMutex.withLock {
        val target = local ?: resolveSelectedModel()
            ?: return Result.Error(
                IllegalStateException("No LiteRT-LM model found in ${modelsDir().absolutePath}")
            )
        val init = ensureInitialized()
        if (init is Result.Error) return init
        val preference = backendPreference
        val loaded = engine.getLoadedModel()
        if (loaded != null && loaded.filePath == target.file.absolutePath &&
            (preference == BackendType.AUTO || loaded.backend == preference)
        ) {
            return Result.Success(Unit)
        }
        selectedModelPath = target.file.absolutePath
        repository.loadModel(
            toEngineModel(target, preference),
            ModelLoadConfig(backend = if (preference == BackendType.AUTO) null else preference)
        )
    }

    suspend fun unload(): Result<Unit> = loadMutex.withLock { repository.unloadModel() }

    fun isModelLoaded(): Boolean = engine.isLoaded()

    private fun guessFamily(lowerName: String): String = when {
        lowerName.contains("functiongemma") -> "FunctionGemma"
        lowerName.contains("gemma") -> "Gemma"
        lowerName.contains("qwen") -> "Qwen"
        lowerName.contains("deepseek") -> "DeepSeek"
        lowerName.contains("phi") -> "Phi"
        lowerName.contains("smol") -> "SmolLM"
        lowerName.contains("tinyllama") -> "TinyLlama"
        lowerName.contains("llama") -> "Llama"
        lowerName.contains("mistral") -> "Mistral"
        else -> ""
    }

    private fun guessArchitecture(lowerName: String): String = when {
        lowerName.contains("gemma-3") || lowerName.contains("gemma3") -> "gemma3"
        lowerName.contains("gemma-4") || lowerName.contains("gemma4") -> "gemma4"
        lowerName.contains("qwen3") -> "qwen3"
        lowerName.contains("qwen2") -> "qwen2"
        lowerName.contains("phi-4") || lowerName.contains("phi4") -> "phi4"
        lowerName.contains("llama") -> "llama"
        else -> ""
    }

    companion object {
        private const val PREFS = "androllm_engine_prefs"
        private const val KEY_BACKEND = "litert_backend_preference"
        private const val KEY_MODEL_PATH = "litert_selected_model_path"

        /** Backends a user may pick. */
        val USER_BACKENDS = listOf(BackendType.AUTO, BackendType.CPU, BackendType.GPU, BackendType.NPU)

        /** File-name markers of SoC-specific LiteRT NPU builds. */
        private val NPU_NAME_HINTS = listOf("npu", "qualcomm", "qnn", "htp", "mediatek", "mtk", "tensor_g", "sm8")

        @Volatile
        private var instance: AndroLlmEngines? = null

        fun getInstance(context: Context): AndroLlmEngines =
            instance ?: synchronized(this) {
                instance ?: AndroLlmEngines(context.applicationContext).also { instance = it }
            }

        /** Returns the graph only if it was already created (never constructs it). */
        fun peek(): AndroLlmEngines? = instance
    }
}
