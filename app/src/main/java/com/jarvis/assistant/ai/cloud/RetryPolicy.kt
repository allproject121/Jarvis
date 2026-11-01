package com.jarvis.assistant.ai.cloud

import kotlin.random.Random

/**
 * Exponential backoff with jitter retry policy for cloud and local host network calls.
 * Gracefully handles 408 (timeout), 429 (rate limited), and 5xx (server error) states.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val maxDelayMs: Long = 10_000L,
    val factor: Double = 2.0,
    val jitterMs: Long = 200L
) {
    /**
     * Calculates sleep delay for a given attempt (1-based).
     */
    fun delayMsForAttempt(failedAttempt: Int): Long {
        if (failedAttempt >= maxAttempts || failedAttempt < 1) return 0L
        var base = initialDelayMs.toDouble()
        repeat(failedAttempt - 1) { base *= factor }
        val capped = base.coerceAtMost(maxDelayMs.toDouble())
        val jitter = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs) else 0L
        return (capped + jitter).coerceAtLeast(0.0).toLong()
    }

    /**
     * Executes a suspending block with automatic retry on retryable exceptions.
     */
    suspend fun <T> executeWithRetry(block: suspend (attempt: Int) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (e: Exception) {
                if (attempt >= maxAttempts) throw e
                val delayMs = delayMsForAttempt(attempt)
                kotlinx.coroutines.delay(delayMs)
                attempt++
            }
        }
    }
}
