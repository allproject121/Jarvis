package com.jarvis.assistant.androllm.memory.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jarvis.assistant.androllm.AndroLlmFlags
import java.util.concurrent.TimeUnit

/**
 * Schedules the background memory indexing job via WorkManager. Idempotent
 * (unique work), opportunistic (battery-not-low), and only ever scheduled when
 * [AndroLlmFlags.MEMORY_BACKGROUND_INDEXING] is enabled — OFF by default.
 */
object MemoryBackgroundScheduler {

    const val UNIQUE_PERIODIC = "androllm_memory_background_indexing"
    const val UNIQUE_ONCE = "androllm_memory_background_indexing_once"
    private const val PERIODIC_HOURS = 6L
    private const val TAG = "MemoryBgScheduler"

    /** Schedules when the flag is on, cancels when it is off. Safe at app start. */
    fun sync(context: Context) {
        if (AndroLlmFlags.isEnabled(context, AndroLlmFlags.MEMORY_BACKGROUND_INDEXING)) schedule(context) else cancel(context)
    }

    /** Ensures the periodic indexing loop exists and kicks off one immediate run. */
    fun schedule(context: Context) {
        if (!AndroLlmFlags.isEnabled(context, AndroLlmFlags.MEMORY_BACKGROUND_INDEXING)) {
            Log.i(TAG, "Not scheduling: background indexing flag is off")
            return
        }
        val workManager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val periodic = PeriodicWorkRequestBuilder<MemoryIndexingWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val once = OneTimeWorkRequestBuilder<MemoryIndexingWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, once)
        Log.i(TAG, "Background memory indexing scheduled (every ${PERIODIC_HOURS}h)")
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
        workManager.cancelUniqueWork(UNIQUE_ONCE)
    }
}
