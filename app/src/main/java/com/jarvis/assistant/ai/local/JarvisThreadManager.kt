package com.jarvis.assistant.ai.local

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardware-adaptive CPU core, threading, and scheduler manager for on-device inference.
 * Dynamically scales threads, context limits, and priorities based on device RAM, core topology,
 * and thermal class.
 */
object JarvisThreadManager {

    private val cachedCores: AtomicInteger = AtomicInteger(0)

    @Volatile
    private var cachedTier: String? = null

    @Volatile
    private var cachedRamGb: Int = 0

    private const val ABSOLUTE_MAX_THREADS = 10
    private const val UI_RESERVE_CORES = 2
    const val INFERENCE_THREAD_PRIORITY = 5

    /**
     * Executes [block] with the calling thread temporarily demoted to [Process.THREAD_PRIORITY_BACKGROUND].
     *
     * Native llama.cpp / SLM inference spawns compute worker threads from the calling thread,
     * which inherit its scheduler priority. Demoting the caller ensures the Android UI thread
     * (foreground cgroup) always wins CPU time, keeping animations at 60 FPS without dropping frames.
     */
    inline fun <T> withBackgroundInferencePriority(block: () -> T): T {
        val tid = Process.myTid()
        val previous = runCatching { Process.getThreadPriority(tid) }.getOrDefault(0)
        // Nice 5, not THREAD_PRIORITY_BACKGROUND (10): Android moves threads at >= 10 into the
        // "background" cgroup, which is pinned to the slow little cores, and llama.cpp's worker
        // threads inherit that. Replies took 50-200 s there. At 5 the UI (nice -10/-4) still wins.
        runCatching { Process.setThreadPriority(tid, INFERENCE_THREAD_PRIORITY) }
        return try {
            block()
        } finally {
            runCatching { Process.setThreadPriority(tid, previous) }
        }
    }

    /**
     * Total hardware CPU cores available. Cached after first calculation.
     */
    fun hardwareCores(): Int {
        val cached = cachedCores.get()
        if (cached > 0) return cached
        val cores = maxOf(2, Runtime.getRuntime().availableProcessors())
        cachedCores.set(cores)
        return cores
    }

    /**
     * Estimated number of performance (big) cores.
     */
    fun performanceCoreCount(): Int {
        val cores = hardwareCores()
        return when {
            cores <= 4 -> cores
            cores <= 6 -> cores / 2 + 1
            else -> cores / 2
        }
    }

    /**
     * Total device RAM in Gigabytes.
     */
    fun totalRamGb(context: Context? = null): Int {
        if (cachedRamGb > 0) return cachedRamGb
        val totalMem = runCatching {
            val ctx = context ?: throw IllegalStateException("no context")
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        }.getOrElse {
            Runtime.getRuntime().maxMemory() * 3
        }
        cachedRamGb = (totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(2)
        return cachedRamGb
    }

    /**
     * Categorizes device into: "low", "low-medium", "medium", "medium-high", "high".
     */
    fun deviceTier(context: Context? = null): String {
        cachedTier?.let { return it }
        val cores = hardwareCores()
        val ramGb = totalRamGb(context)
        val tier = when {
            cores >= 8 && ramGb >= 8 -> "high"
            cores >= 8 && ramGb >= 6 -> "medium-high"
            cores >= 6 && ramGb >= 4 -> "medium"
            cores >= 4 && ramGb >= 3 -> "low-medium"
            else -> "low"
        }
        cachedTier = tier
        return tier
    }

    /**
     * Optimal threads for inference. Uses big cores while leaving UI headroom.
     */
    fun recommendedThreads(context: Context? = null): Int {
        val cores = hardwareCores()
        val tier = deviceTier(context)
        return when (tier) {
            "low" -> maxOf(2, cores - 2).coerceAtMost(4)
            "low-medium" -> maxOf(3, cores - 2).coerceAtMost(5)
            "medium" -> maxOf(4, cores - 2).coerceAtMost(6)
            "medium-high" -> maxOf(4, cores - UI_RESERVE_CORES).coerceAtMost(8)
            "high" -> maxOf(6, cores - UI_RESERVE_CORES).coerceAtMost(ABSOLUTE_MAX_THREADS)
            else -> 4
        }
    }

    /**
     * Recommended context length for local SLM based on device RAM class.
     */
    fun recommendedContextLength(context: Context? = null): Int {
        return when (deviceTier(context)) {
            "low" -> 2048
            "low-medium" -> 2048
            "medium" -> 4096
            "medium-high" -> 4096
            "high" -> 8192
            else -> 2048
        }
    }

    /**
     * Diagnostic string describing device hardware threading parameters.
     */
    fun threadingProfile(context: Context? = null): String {
        val cores = hardwareCores()
        val tier = deviceTier(context)
        val ram = totalRamGb(context)
        val perfCores = performanceCoreCount()
        val threads = recommendedThreads(context)
        val ctx = recommendedContextLength(context)
        return "cores=$cores(big=$perfCores) tier=$tier ram=${ram}GB recThreads=$threads ctx=$ctx"
    }
}
