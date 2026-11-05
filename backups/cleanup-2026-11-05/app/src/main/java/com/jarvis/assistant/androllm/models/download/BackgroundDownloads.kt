package com.jarvis.assistant.androllm.models.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Scheduling priority for queued downloads (higher starts first). Ported from AndroLLM. */
enum class DownloadPriority(val value: Int) {
    LOW(0),
    NORMAL(10),
    HIGH(20);

    companion object {
        fun fromValue(value: Int): DownloadPriority = entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

/**
 * "Background downloads" opt-in (default OFF). When OFF, model downloads run
 * in-process exactly as before (LiteRtDownloadManager / LocalModelManager own
 * coroutine scopes). When ON, they are handed to WorkManager
 * ([ModelDownloadWorker]) so they wait for connectivity / storage, survive
 * process death and resume with HTTP Range after a reboot.
 */
object BackgroundDownloadPrefs {
    private const val PREFS = "androllm_model_downloads"
    private const val KEY_ENABLED = "background_downloads_enabled"
    private const val KEY_UNMETERED_ONLY = "background_downloads_unmetered_only"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Only download on Wi-Fi / unmetered networks when running in the background. */
    fun isUnmeteredOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNMETERED_ONLY, false)

    fun setUnmeteredOnly(context: Context, unmetered: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UNMETERED_ONLY, unmetered).apply()
    }
}

/**
 * Enqueues / cancels [ModelDownloadWorker] runs (port of the WorkManager half of
 * AndroLLM's feature/models DownloadManager): one unique work chain per model,
 * connectivity + storage-not-low constraints, exponential backoff, HIGH
 * priority as expedited work.
 */
object ModelDownloadScheduler {

    private const val TAG = "ModelDownloadScheduler"

    const val KIND_LITERT = "litert"
    const val KIND_GGUF = "gguf"

    fun uniqueName(kind: String, modelId: String): String = "androllm_download_${kind}_$modelId"

    private fun constraints(context: Context): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (BackgroundDownloadPrefs.isUnmeteredOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .setRequiresStorageNotLow(true)
        .build()

    fun enqueueLiteRt(context: Context, request: LiteRtDownloadRequest) {
        enqueue(
            context = context,
            kind = KIND_LITERT,
            modelId = request.modelId,
            priority = request.priority,
            data = arrayOf(
                ModelDownloadWorker.KEY_KIND to KIND_LITERT,
                ModelDownloadWorker.KEY_MODEL_ID to request.modelId,
                ModelDownloadWorker.KEY_MODEL_NAME to request.name,
                ModelDownloadWorker.KEY_DOWNLOAD_URL to request.downloadUrl,
                ModelDownloadWorker.KEY_FILE_NAME to request.fileName,
                ModelDownloadWorker.KEY_EXPECTED_SHA256 to (request.sha256 ?: ""),
                ModelDownloadWorker.KEY_EXPECTED_SIZE to request.expectedSize,
                ModelDownloadWorker.KEY_COMPANION_URL to request.companionUrl,
                ModelDownloadWorker.KEY_PRIORITY to request.priority.value
            )
        )
    }

    fun enqueueGguf(context: Context, modelId: String, modelName: String, priority: DownloadPriority = DownloadPriority.NORMAL) {
        enqueue(
            context = context,
            kind = KIND_GGUF,
            modelId = modelId,
            priority = priority,
            data = arrayOf(
                ModelDownloadWorker.KEY_KIND to KIND_GGUF,
                ModelDownloadWorker.KEY_MODEL_ID to modelId,
                ModelDownloadWorker.KEY_MODEL_NAME to modelName,
                ModelDownloadWorker.KEY_PRIORITY to priority.value
            )
        )
    }

    private fun enqueue(
        context: Context,
        kind: String,
        modelId: String,
        priority: DownloadPriority,
        data: Array<Pair<String, Any?>>
    ) {
        val builder = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .addTag("androllm_download")
            .addTag("download_$modelId")
            .addTag("priority_${priority.value}")
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(*data))
        // High-priority downloads jump the queue via expedited work.
        if (priority == DownloadPriority.HIGH) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        // KEEP: a resume never duplicates a queued/running chain.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(kind, modelId), ExistingWorkPolicy.KEEP, builder.build())
        Log.i(TAG, "Enqueued background $kind download $modelId (priority=$priority)")
    }

    fun cancel(context: Context, kind: String, modelId: String) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(kind, modelId))
        }.onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
    }
}
