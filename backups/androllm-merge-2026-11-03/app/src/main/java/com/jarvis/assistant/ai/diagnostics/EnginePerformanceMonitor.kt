package com.jarvis.assistant.ai.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * High-precision lock-free stage timer and profiler.
 * Measures Time-to-First-Token (TTFT), Tokens Per Second (TPS), and stage latencies.
 */
object EnginePerformanceMonitor {

    private val stageStarts = ConcurrentHashMap<String, Long>()
    private val lastLatenciesMs = ConcurrentHashMap<String, Double>()

    fun startStage(stage: String) {
        stageStarts[stage] = System.nanoTime()
    }

    fun endStage(stage: String): Double {
        val start = stageStarts.remove(stage) ?: return 0.0
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        lastLatenciesMs[stage] = elapsedMs
        return elapsedMs
    }

    fun getLastLatency(stage: String): Double = lastLatenciesMs[stage] ?: 0.0

    /**
     * Calculates tokens per second (TPS).
     */
    fun calculateTokensPerSecond(tokenCount: Int, durationMs: Long): Double {
        if (durationMs <= 0) return 0.0
        return (tokenCount.toDouble() / durationMs.toDouble()) * 1000.0
    }
}
