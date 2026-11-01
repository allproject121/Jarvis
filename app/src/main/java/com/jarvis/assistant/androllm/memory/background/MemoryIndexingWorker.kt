package com.jarvis.assistant.androllm.memory.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.androllm.AndroLlmFlags

/**
 * Background memory maintenance executed by WorkManager so it never blocks
 * chat. Does nothing (and succeeds) when [AndroLlmFlags.MEMORY_BACKGROUND_INDEXING]
 * is off, so a stale schedule can never do work the user disabled.
 *
 * Extraction/summarization are NOT run here — they run after each exchange
 * through [com.jarvis.assistant.androllm.memory.AndroMemoryPipeline].
 */
class MemoryIndexingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AndroLlmFlags.isEnabled(applicationContext, AndroLlmFlags.MEMORY_BACKGROUND_INDEXING)) {
            Log.i(TAG, "Background memory indexing disabled; skipping")
            return Result.success()
        }
        return try {
            val report = MemoryMaintenance.run(applicationContext)
            Log.i(TAG, "Background indexing: ${report.describe()}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background indexing failed: ${e.message}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "MemoryIndexingWorker"
        const val MAX_RETRIES = 3
    }
}
