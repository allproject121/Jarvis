package com.jarvis.assistant.androllm.models.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.assistant.ai.local.LocalModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * WorkManager download worker (port of AndroLLM feature/models
 * `ModelDownloadWorker`). The heavy pipeline — preflight HEAD, resume check,
 * Range streaming, size/SHA-256/container verification, repair/retry — is the
 * one already ported into [LiteRtDownloadManager] (LiteRT artifacts) and
 * Jarvis' [LocalModelManager] (GGUF); this worker runs that pipeline inside
 * WorkManager so the download honours network/storage constraints and survives
 * process death.
 *
 * Only ever enqueued when [BackgroundDownloadPrefs] is enabled (default OFF);
 * a stale run after the user disabled it does nothing.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"

        const val KEY_KIND = "kind"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_COMPANION_URL = "companion_url"
        const val KEY_PRIORITY = "priority"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "androllm_background_downloads"
        private const val NOTIFICATION_ID = 4501
    }

    override suspend fun doWork(): Result {
        if (!BackgroundDownloadPrefs.isEnabled(applicationContext)) {
            Log.i(TAG, "Background downloads disabled; skipping stale work")
            return Result.success()
        }
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"

        tryPromoteToForeground(modelName)

        return try {
            when (kind) {
                ModelDownloadScheduler.KIND_LITERT -> runLiteRt(modelId, modelName)
                ModelDownloadScheduler.KIND_GGUF -> runGguf(modelId)
                else -> Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown download kind: $kind"))
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager (constraints lost, pause/cancel): WorkManager reschedules
            // a constraint stop itself; the partial file is kept for Range resume.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background download $modelId failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Download failed")))
        }
    }

    private suspend fun runLiteRt(modelId: String, modelName: String): Result {
        val request = LiteRtDownloadRequest(
            modelId = modelId,
            name = modelName,
            downloadUrl = inputData.getString(KEY_DOWNLOAD_URL).orEmpty(),
            fileName = inputData.getString(KEY_FILE_NAME).orEmpty(),
            expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L),
            sha256 = inputData.getString(KEY_EXPECTED_SHA256)?.takeIf { it.length == 64 },
            companionUrl = inputData.getString(KEY_COMPANION_URL).orEmpty(),
            priority = DownloadPriority.fromValue(inputData.getInt(KEY_PRIORITY, DownloadPriority.NORMAL.value))
        )
        if (request.fileName.isBlank()) return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing file name"))
        val final = LiteRtDownloadManager.getInstance(applicationContext).runInWorker(request)
        return when (final?.status) {
            LiteRtDownloadStatus.ERROR ->
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (final.errorMessage ?: "Download failed")))
            // DOWNLOADED, PAUSED (user pause) or removed (cancel/finished) — nothing left to retry.
            else -> Result.success()
        }
    }

    private suspend fun runGguf(modelId: String): Result {
        val manager = LocalModelManager.getInstance(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val ready = manager.runDownloadInWorker(modelId)
        if (ready) return Result.success()
        val error = manager.downloads.value[modelId]?.errorMessage
        return if (error != null) Result.failure(workDataOf(KEY_ERROR_MESSAGE to error)) else Result.success()
    }

    /**
     * Long downloads run as a foreground (dataSync) worker when the app's manifest
     * allows it (FOREGROUND_SERVICE_DATA_SYNC + SystemForegroundService typed
     * dataSync). Without it the work runs as a normal job and simply resumes
     * from the partial file if the system stops it.
     */
    private suspend fun tryPromoteToForeground(modelName: String) {
        if (!canRunDataSyncForeground()) return
        try {
            createChannel()
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading $modelName")
                .setContentText("Background model download")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
            setForeground(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Foreground promotion unavailable: ${e.message}")
        }
    }

    private fun canRunDataSyncForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return declares(Manifest.permission.FOREGROUND_SERVICE)
        return declares(Manifest.permission.FOREGROUND_SERVICE) && declares(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
    }

    private fun declares(permission: String): Boolean = try {
        val pm = applicationContext.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(applicationContext.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    } catch (_: Exception) {
        false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
