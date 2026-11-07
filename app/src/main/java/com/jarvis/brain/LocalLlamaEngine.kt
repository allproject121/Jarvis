package com.jarvis.brain

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device GGUF model manager. Model context.filesDir/models/ ya local_ai_models/ se load hota hai.
 * Thread-safe Singleton; saare heavy calls Dispatchers.Default par.
 */
class LocalLlamaEngine(context: Context) {

    private val appContext = context.applicationContext

    data class Config(
        val modelFileName: String = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        val contextSize: Int = 2048,
        val threads: Int = defaultThreads(),
        val maxTokens: Int = 512,
        val temperature: Float = 0f,          // 0 = greedy, deterministic JSON
    )

    sealed class LoadResult {
        object Ok : LoadResult()
        data class Error(val reason: String) : LoadResult()
    }

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L
    private var config = Config()
    var activeModelName: String = ""
        private set

    val isLoaded: Boolean get() = handle != 0L

    fun modelDir(): File = File(appContext.filesDir, "models").apply { mkdirs() }
    fun localAiModelsDir(): File = File(appContext.filesDir, "local_ai_models").apply { mkdirs() }

    /**
     * Finds the best usable GGUF model file across internal storage and downloads.
     */
    fun resolveModelFile(preferredName: String? = null): File? {
        val targets = mutableListOf<File>()

        // 1. Check preferred or config file name in priority directories
        val nameToCheck = preferredName ?: config.modelFileName
        if (nameToCheck.isNotBlank()) {
            targets.add(File(modelDir(), nameToCheck))
            targets.add(File(localAiModelsDir(), nameToCheck))
        }

        // 2. Standard known models
        targets.add(File(modelDir(), "qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        targets.add(File(localAiModelsDir(), "llama-3.2-1b-instruct.gguf"))
        targets.add(File(localAiModelsDir(), "qwen3-4b-instruct-2507.gguf"))
        targets.add(File(modelDir(), "llama-3.2-1b-instruct.gguf"))

        for (target in targets) {
            if (target.exists() && isGguf(target)) {
                return target
            }
        }

        // 3. Scan search directories for any valid .gguf
        val searchDirs = listOf(
            modelDir(),
            localAiModelsDir(),
            File(Environment.getExternalStorageDirectory(), "Download")
        )

        for (dir in searchDirs) {
            val found = dir.listFiles { _, name -> name.endsWith(".gguf", ignoreCase = true) }
                ?.firstOrNull { isGguf(it) }
            if (found != null) return found
        }

        return null
    }

    suspend fun load(cfg: Config = Config()): LoadResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (handle != 0L && cfg == config) return@withLock LoadResult.Ok
            unloadLocked()

            val file = resolveModelFile(cfg.modelFileName)
                ?: return@withLock LoadResult.Error("No valid GGUF model found in files/models or local_ai_models")

            if (!isGguf(file)) return@withLock LoadResult.Error("Not a GGUF file: ${file.name}")

            // Physical RAM check for mmap: KV cache + buffers
            val minRamRequired = (250L shl 20) + kvCacheBytes(cfg.contextSize)
            val avail = availableRam()
            if (avail < minRamRequired) {
                return@withLock LoadResult.Error("Low RAM: need at least ${minRamRequired shr 20} MB free, available ${avail shr 20} MB")
            }

            Log.i(TAG, "Loading native model: ${file.absolutePath} (ctx=${cfg.contextSize}, threads=${cfg.threads})")
            val h = LlamaNative.nativeLoad(file.absolutePath, cfg.contextSize, cfg.threads)
            if (h == 0L) return@withLock LoadResult.Error("Native load failed (check logcat JarvisBrainJNI)")

            handle = h
            config = cfg
            activeModelName = file.canonicalFile.name
            Log.i(TAG, "Successfully loaded $activeModelName (${file.length() shr 20} MB)")
            LoadResult.Ok
        }
    }

    /** Prompt -> raw text. grammar khali ho to free text. */
    suspend fun generate(prompt: String, grammar: String = ""): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                check(handle != 0L) { "Engine not loaded" }
                LlamaNative.nativeGenerate(handle, prompt, grammar, config.maxTokens, config.temperature)
            }
        }

    /** Chal rahe generation ko rokta hai. */
    fun cancel() { handle.takeIf { it != 0L }?.let(LlamaNative::nativeCancel) }

    suspend fun unload() = withContext(Dispatchers.Default) { cancel(); mutex.withLock { unloadLocked() } }

    /** ComponentCallbacks2.onTrimMemory se call karein. */
    suspend fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) unload()
    }

    private fun unloadLocked() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0L
            activeModelName = ""
        }
    }

    private fun isGguf(f: File): Boolean = runCatching {
        f.inputStream().use { val m = ByteArray(4); it.read(m) == 4 && String(m) == "GGUF" }
    }.getOrDefault(false)

    private fun availableRam(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(am::getMemoryInfo).availMem
    }

    // KV Cache approx bytes
    private fun kvCacheBytes(ctx: Int): Long = ctx.toLong() * 28 * 2 * 256 * 2 + (64L shl 20)

    companion object {
        private const val TAG = "LocalLlamaEngine"

        @Volatile
        private var instance: LocalLlamaEngine? = null

        fun getInstance(context: Context): LocalLlamaEngine {
            return instance ?: synchronized(this) {
                instance ?: LocalLlamaEngine(context.applicationContext).also { instance = it }
            }
        }

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    }
}
