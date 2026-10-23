package com.jarvis.assistant.ai.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState()
    object Completed : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Manages on-device local AI model files, download status, GGUF file integrity, and storage paths.
 */
class LocalModelManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocalModelManager"
        private const val MODELS_DIR_NAME = "local_ai_models"
        private const val NOTIF_CHANNEL_ID = "jarvis_model_downloads"
        private const val NOTIF_ID = 4040

        @Volatile
        private var instance: LocalModelManager? = null

        fun getInstance(context: Context, scope: CoroutineScope): LocalModelManager {
            return instance ?: synchronized(this) {
                instance ?: LocalModelManager(context.applicationContext, scope).also { instance = it }
            }
        }

        /**
         * Validates whether a file has valid GGUF magic header bytes ("GGUF" = 0x47 0x47 0x55 0x46).
         */
        fun isGgufHeaderValid(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    val read = input.read(header)
                    read == 4 &&
                            header[0] == 0x47.toByte() && // 'G'
                            header[1] == 0x47.toByte() && // 'G'
                            header[2] == 0x55.toByte() && // 'U'
                            header[3] == 0x46.toByte()   // 'F'
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _activeModelId = MutableStateFlow(LocalAiConfig.DEFAULT_MODEL_ID)
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "JARVIS Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download progress of on-device local AI models"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, text: String, progress: Int, indeterminate: Boolean = false) {
        try {
            val icon = android.R.drawable.stat_sys_download
            val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(progress in 0..99)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progress in 0..100) {
                builder.setProgress(100, progress, indeterminate)
            } else {
                builder.setProgress(0, 0, false)
            }

            notificationManager?.notify(NOTIF_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Could not post download notification: ${e.message}")
        }
    }

    fun getModelFile(modelId: String): File {
        return File(modelsDir, "$modelId.gguf")
    }

    /**
     * Checks if the model file is downloaded, meets expected size criteria, and has a valid GGUF header.
     */
    fun isModelReady(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val descriptor = LocalAiConfig.AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val sizeValid = file.exists() && file.length() >= (descriptor.downloadSizeBytes * 0.75)
        return sizeValid && isGgufHeaderValid(file)
    }

    fun getActiveModelFile(): File? {
        val id = _activeModelId.value
        return if (isModelReady(id)) getModelFile(id) else null
    }

    fun setActiveModel(modelId: String) {
        _activeModelId.value = modelId
        Log.i(TAG, "Active local model set to: $modelId (ready=${isModelReady(modelId)})")
    }

    /**
     * Initiates a resilient chunked download with HTTP Range header support for resuming downloads.
     */
    fun downloadModel(modelId: String) {
        val descriptor = LocalAiConfig.AVAILABLE_MODELS.find { it.id == modelId }
        if (descriptor == null) {
            _downloadState.value = ModelDownloadState.Error("Unknown model ID: $modelId")
            return
        }

        if (isModelReady(modelId)) {
            _downloadState.value = ModelDownloadState.Completed
            updateNotification("Model Ready", "${descriptor.name} is already verified and ready.", 100, false)
            return
        }

        scope.launch(Dispatchers.IO) {
            val targetFile = getModelFile(modelId)
            val tempFile = File(modelsDir, "$modelId.download.tmp")
            updateNotification("Downloading ${descriptor.name}", "Connecting to Hugging Face model mirror...", 0, true)

            try {
                var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val requestBuilder = Request.Builder().url(descriptor.downloadUrl)
                if (existingBytes > 0 && existingBytes < descriptor.downloadSizeBytes) {
                    Log.i(TAG, "Resuming download of ${descriptor.name} from byte $existingBytes...")
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                } else if (existingBytes >= descriptor.downloadSizeBytes) {
                    tempFile.delete()
                    existingBytes = 0L
                }

                _downloadState.value = ModelDownloadState.Downloading(
                    progressPercent = if (descriptor.downloadSizeBytes > 0) ((existingBytes * 100) / descriptor.downloadSizeBytes).toInt() else 0,
                    bytesDownloaded = existingBytes,
                    totalBytes = descriptor.downloadSizeBytes
                )

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val isResume = response.code == 206
                    val isOk = response.code == 200

                    if (!isResume && !isOk) {
                        throw Exception("HTTP download error: ${response.code} ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Empty response body from model mirror")
                    val totalExpected = if (isResume) existingBytes + body.contentLength() else body.contentLength()

                    val append = isResume && existingBytes > 0
                    if (!append && tempFile.exists()) {
                        tempFile.delete()
                        existingBytes = 0L
                    }

                    body.byteStream().use { input ->
                        val output = FileOutputStream(tempFile, append)
                        output.use { out ->
                            val buffer = ByteArray(128 * 1024) // 128KB chunks
                            var bytesRead: Int
                            var downloadedBytes = existingBytes
                            var lastReportedPercent = ((downloadedBytes * 100) / totalExpected.coerceAtLeast(1L)).toInt()

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val percent = ((downloadedBytes * 100) / totalExpected.coerceAtLeast(1L)).toInt()
                                if (percent > lastReportedPercent) {
                                    lastReportedPercent = percent
                                    _downloadState.value = ModelDownloadState.Downloading(percent, downloadedBytes, totalExpected)
                                    val dlMb = downloadedBytes / (1024 * 1024)
                                    val totMb = totalExpected / (1024 * 1024)
                                    updateNotification("Downloading ${descriptor.name}", "$percent% • ${dlMb}MB / ${totMb}MB", percent, false)
                                }
                            }
                            out.flush()
                        }
                    }

                    // Verify GGUF header before finalizing
                    if (!isGgufHeaderValid(tempFile)) {
                        tempFile.delete()
                        throw Exception("Downloaded file failed GGUF format validation. Corrupted weights discarded.")
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        Log.i(TAG, "Model $modelId verified & ready: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                        _downloadState.value = ModelDownloadState.Completed
                        updateNotification("Model Ready: ${descriptor.name}", "On-device AI weights verified and ready for offline inference.", 100, false)
                    } else {
                        throw Exception("Failed to rename temporary download file to final GGUF model path")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading local model $modelId: ${e.message}", e)
                _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
                updateNotification("Download Failed", e.message ?: "Failed downloading model weights", -1, false)
            }
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val deleted = if (file.exists()) file.delete() else true
        Log.i(TAG, "Deleted model $modelId: $deleted")
        return deleted
    }

    fun getAvailableStorageBytes(): Long {
        return context.filesDir.usableSpace
    }
}
