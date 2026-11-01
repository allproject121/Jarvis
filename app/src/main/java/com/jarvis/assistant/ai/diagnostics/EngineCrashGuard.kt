package com.jarvis.assistant.ai.diagnostics

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crash Guard for AI execution pipelines.
 * Wraps critical boundary calls, tracks error rates, and prevents app crashes.
 */
object EngineCrashGuard {

    private const val TAG = "EngineCrashGuard"
    private const val MAX_RECENT_EVENTS = 20

    data class CrashEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val stage: String,
        val errorType: String,
        val message: String,
        val stackTrace: String = ""
    )

    private val recentEvents = ConcurrentLinkedDeque<CrashEvent>()
    private val failureCount = AtomicInteger(0)

    fun <T> guard(stage: String, block: () -> T): T {
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val event = CrashEvent(
                stage = stage,
                errorType = e.javaClass.simpleName,
                message = e.message ?: "Unknown error",
                stackTrace = Log.getStackTraceString(e).take(500)
            )
            recentEvents.add(event)
            while (recentEvents.size > MAX_RECENT_EVENTS) {
                recentEvents.pollFirst()
            }
            failureCount.incrementAndGet()
            Log.e(TAG, "[$stage] Crash guarded: ${event.message}", e)
            throw e
        }
    }

    fun <T> guardOrNull(stage: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "[$stage] Non-critical failure suppressed: ${e.message}")
            null
        }
    }

    fun recentCrashes(): List<CrashEvent> = recentEvents.toList()

    fun totalGuardedFailures(): Int = failureCount.get()
}
