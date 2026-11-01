package com.jarvis.assistant.ai.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState()
    object Completed : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/** Per-model download lifecycle tracked by [LocalModelManager.downloads]. */
enum class LocalDownloadStatus { DOWNLOADING, VERIFYING, PAUSED, ERROR }

/** Live per-model download snapshot (one entry per in-flight / paused / failed download). */
data class LocalModelDownload(
    val modelId: String,
    val status: LocalDownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val speedBytesPerSec: Float = 0f,
    val etaSeconds: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Manages on-device local AI model files, download status, GGUF file integrity, and storage paths.
 *
 * Besides the curated [LocalAiConfig.AVAILABLE_MODELS], the manager keeps a persisted registry of
 * custom GGUF models (downloaded from Hugging Face search or imported from storage). Every model,
 * curated or custom, lives at `files/local_ai_models/<id>.gguf`, so [getModelFile] /
 * [isModelReady] work for all of them and the llama.cpp provider loads them unchanged.
 *
 * Downloads run per model on the manager's own process-lifetime scope (a screen's lifecycle
 * scope passed to [getInstance] can no longer cancel them) and support pause (partial file kept,
 * resumed with an HTTP Range request) and cancel (partial file removed).
 */
class LocalModelManager(
    private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocalModelManager"
        private const val MODELS_DIR_NAME = "local_ai_models"
        private const val NOTIF_CHANNEL_ID = "jarvis_model_downloads"
        private const val NOTIF_ID = 4040
        private const val PREFS = "jarvis_local_model_manager"
        private const val KEY_ACTIVE_MODEL = "active_model_id"
        private const val KEY_CUSTOM_MODELS = "custom_models_json"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

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

        /** Turns an arbitrary label into a safe model id / file stem. */
        fun sanitizeModelId(raw: String): String =
            raw.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').take(120).ifBlank { "model" }
    }

    /** Process-lifetime scope for downloads; never tied to a fragment / view lifecycle. */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)

    /** Model IDs with an in-flight download; prevents concurrent writes to the same temp file. */
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadCalls = ConcurrentHashMap<String, Call>()
    /** Requested stop reason per model: true = cancel (drop partial), false = pause (keep partial). */
    private val stopRequests = ConcurrentHashMap<String, Boolean>()

    /** Legacy single-value download state (last event of any download). */
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, LocalModelDownload>>(emptyMap())
    /** Per-model download state: in-flight, paused and failed downloads keyed by model id. */
    val downloads: StateFlow<Map<String, LocalModelDownload>> = _downloads.asStateFlow()

    private val _customModels = MutableStateFlow(loadCustomModels())
    /** Custom GGUF models (Hugging Face downloads and storage imports). */
    val customModels: StateFlow<List<LocalModelDescriptor>> = _customModels.asStateFlow()

    private val _activeModelId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_MODEL, null)?.takeIf { id -> id.isNotBlank() && getDescriptor(id) != null }
            ?: LocalAiConfig.DEFAULT_MODEL_ID
    )
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        createNotificationChannel()
        restorePausedDownloads()
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

    private fun notificationIdFor(modelId: String): Int = NOTIF_ID + (modelId.hashCode() and 0x3FF)

    private fun updateNotification(modelId: String, title: String, text: String, progress: Int, indeterminate: Boolean = false) {
        try {
            val icon = android.R.drawable.stat_sys_download
            val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(progress in 0..99)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progress in 0..100) {
                builder.setProgress(100, progress, indeterminate)
            } else {
                builder.setProgress(0, 0, false)
            }

            notificationManager?.notify(notificationIdFor(modelId), builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Could not post download notification: ${e.message}")
        }
    }

    private fun clearNotification(modelId: String) {
        runCatching { notificationManager?.cancel(notificationIdFor(modelId)) }
    }

    // ───────────────────────────── Model registry ─────────────────────────────

    /** Curated models followed by custom (Hugging Face / imported) GGUF models. */
    fun allModels(): List<LocalModelDescriptor> = LocalAiConfig.AVAILABLE_MODELS + _customModels.value

    /** Descriptor for a curated or custom model id. */
    fun getDescriptor(modelId: String): LocalModelDescriptor? =
        LocalAiConfig.getModelById(modelId) ?: _customModels.value.firstOrNull { it.id == modelId }

    fun isCustomModel(modelId: String): Boolean =
        LocalAiConfig.getModelById(modelId) == null && _customModels.value.any { it.id == modelId }

    /** Adds or replaces a custom GGUF model descriptor (persisted). Curated ids are rejected. */
    fun registerCustomModel(descriptor: LocalModelDescriptor): Boolean {
        if (LocalAiConfig.getModelById(descriptor.id) != null) return false
        _customModels.update { list -> list.filterNot { it.id == descriptor.id } + descriptor }
        persistCustomModels()
        return true
    }

    /** Removes a custom model registration (does not touch the file; use [deleteModel] for that). */
    fun removeCustomModel(modelId: String) {
        _customModels.update { list -> list.filterNot { it.id == modelId } }
        persistCustomModels()
    }

    private fun loadCustomModels(): List<LocalModelDescriptor> {
        val raw = prefs.getString(KEY_CUSTOM_MODELS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank() || LocalAiConfig.getModelById(id) != null) return@mapNotNull null
                LocalModelDescriptor(
                    id = id,
                    name = o.optString("name", id),
                    provider = o.optString("provider"),
                    parameters = o.optString("parameters"),
                    quantization = o.optString("quantization"),
                    downloadSizeBytes = o.optLong("downloadSizeBytes", 0L),
                    downloadUrl = o.optString("downloadUrl"),
                    recommendedMinRamGb = o.optInt("recommendedMinRamGb", 4),
                    description = o.optString("description"),
                    sha256 = o.optString("sha256").takeIf { it.length == 64 }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse custom model registry: ${e.message}")
            emptyList()
        }
    }

    private fun persistCustomModels() {
        val arr = JSONArray()
        _customModels.value.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("provider", d.provider)
                    .put("parameters", d.parameters)
                    .put("quantization", d.quantization)
                    .put("downloadSizeBytes", d.downloadSizeBytes)
                    .put("downloadUrl", d.downloadUrl)
                    .put("recommendedMinRamGb", d.recommendedMinRamGb)
                    .put("description", d.description)
                    .put("sha256", d.sha256 ?: "")
            )
        }
        prefs.edit().putString(KEY_CUSTOM_MODELS, arr.toString()).apply()
    }

    // ───────────────────────────── Files / readiness ─────────────────────────────

    fun getModelFile(modelId: String): File {
        return File(modelsDir, "$modelId.gguf")
    }

    /** Partial download file used for HTTP Range resume. */
    fun getPartialFile(modelId: String): File = File(modelsDir, "$modelId.download.tmp")

    /** Directory holding every GGUF model file. */
    fun getModelsDirectory(): File = modelsDir

    /**
     * Checks if the model file is downloaded, meets expected size criteria, and has a valid GGUF header.
     */
    fun isModelReady(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val descriptor = getDescriptor(modelId) ?: return false
        val minSize = if (descriptor.downloadSizeBytes > 0) (descriptor.downloadSizeBytes * 0.75) else 1.0
        val sizeValid = file.exists() && file.length() >= minSize
        return sizeValid && isGgufHeaderValid(file)
    }

    fun getActiveModelFile(): File? {
        val id = _activeModelId.value
        return if (isModelReady(id)) getModelFile(id) else null
    }

    fun setActiveModel(modelId: String) {
        _activeModelId.value = modelId
        prefs.edit().putString(KEY_ACTIVE_MODEL, modelId).apply()
        Log.i(TAG, "Active local model set to: $modelId (ready=${isModelReady(modelId)})")
    }

    // ───────────────────────────── Downloads ─────────────────────────────

    /** Paused downloads survive restarts: every partial file of a known model shows as PAUSED. */
    private fun restorePausedDownloads() {
        val restored = allModels().mapNotNull { d ->
            val partial = getPartialFile(d.id)
            if (!partial.exists() || partial.length() <= 0L) return@mapNotNull null
            val total = d.downloadSizeBytes
            d.id to LocalModelDownload(
                modelId = d.id,
                status = LocalDownloadStatus.PAUSED,
                bytesDownloaded = partial.length(),
                totalBytes = total,
                progressPercent = if (total > 0) ((partial.length() * 100) / total).toInt().coerceIn(0, 100) else 0
            )
        }.toMap()
        if (restored.isNotEmpty()) _downloads.value = restored
    }

    private fun putDownload(info: LocalModelDownload) {
        _downloads.update { it + (info.modelId to info) }
    }

    private fun removeDownload(modelId: String) {
        _downloads.update { it - modelId }
    }

    fun isDownloading(modelId: String): Boolean = activeDownloads.contains(modelId)

    /** Pauses an in-flight download. The partial file is kept and resumed later via HTTP Range. */
    fun pauseDownload(modelId: String) {
        // Opt-in background (WorkManager) downloads: drop the queued/running work too.
        cancelBackgroundWork(modelId)
        if (!activeDownloads.contains(modelId)) {
            if (backgroundQueued.remove(modelId)) markPausedFromPartial(modelId)
            return
        }
        stopRequests[modelId] = false
        downloadCalls[modelId]?.cancel()
        downloadJobs[modelId]?.cancel()
    }

    /** Resumes a paused or failed download from its partial file. */
    fun resumeDownload(modelId: String) = downloadModel(modelId)

    /** Cancels a download (in-flight or paused) and deletes its partial file. */
    fun cancelDownload(modelId: String) {
        cancelBackgroundWork(modelId)
        backgroundQueued.remove(modelId)
        if (activeDownloads.contains(modelId)) {
            stopRequests[modelId] = true
            downloadCalls[modelId]?.cancel()
            downloadJobs[modelId]?.cancel()
        } else {
            getPartialFile(modelId).delete()
            removeDownload(modelId)
            clearNotification(modelId)
            _downloadState.value = ModelDownloadState.Idle
        }
    }

    /**
     * Initiates a resilient chunked download with HTTP Range header support for resuming downloads.
     */
    fun downloadModel(modelId: String) {
        val descriptor = getDescriptor(modelId)
        if (descriptor == null) {
            _downloadState.value = ModelDownloadState.Error("Unknown model ID: $modelId")
            return
        }
        if (descriptor.downloadUrl.isBlank()) {
            _downloadState.value = ModelDownloadState.Error("${descriptor.name} was imported from storage and has no download URL.")
            return
        }

        if (isModelReady(modelId)) {
            _downloadState.value = ModelDownloadState.Completed
            removeDownload(modelId)
            updateNotification(modelId, "Model Ready", "${descriptor.name} is already verified and ready.", 100, false)
            return
        }

        // Opt-in background downloads (AndroLLM ModelDownloadWorker, default OFF):
        // WorkManager runs the same pipeline under network/storage constraints.
        if (com.jarvis.assistant.androllm.models.download.BackgroundDownloadPrefs.isEnabled(context) &&
            !activeDownloads.contains(modelId)
        ) {
            backgroundQueued.add(modelId)
            val partial = getPartialFile(modelId).let { if (it.exists()) it.length() else 0L }
            val total = descriptor.downloadSizeBytes
            putDownload(
                LocalModelDownload(
                    modelId = modelId,
                    status = LocalDownloadStatus.DOWNLOADING,
                    bytesDownloaded = partial,
                    totalBytes = total,
                    progressPercent = if (total > 0) ((partial * 100) / total).toInt().coerceIn(0, 100) else 0,
                    errorMessage = "Queued for background download"
                )
            )
            com.jarvis.assistant.androllm.models.download.ModelDownloadScheduler.enqueueGguf(context, modelId, descriptor.name)
            return
        }

        // B3 fix: guard against double-tap / re-entry launching two parallel downloads
        // that would both write to the same .download.tmp file and corrupt the weights.
        if (!activeDownloads.add(modelId)) {
            Log.w(TAG, "Download of $modelId is already in progress; ignoring duplicate request.")
            return
        }
        stopRequests.remove(modelId)

        val job = workScope.launch {
            runDownload(descriptor)
        }
        downloadJobs[modelId] = job
        job.invokeOnCompletion {
            downloadJobs.remove(modelId)
            downloadCalls.remove(modelId)
            activeDownloads.remove(modelId)
        }
    }

    /** GGUF downloads handed to WorkManager that have not started running yet. */
    private val backgroundQueued = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs one GGUF download inside the caller's coroutine — used by the AndroLLM
     * [com.jarvis.assistant.androllm.models.download.ModelDownloadWorker] for opt-in
     * background downloads. Same pipeline, pause/cancel and notifications as
     * [downloadModel]. Returns true when the verified model file is ready.
     */
    suspend fun runDownloadInWorker(modelId: String): Boolean {
        backgroundQueued.remove(modelId)
        val descriptor = getDescriptor(modelId) ?: return false
        if (isModelReady(modelId)) {
            removeDownload(modelId)
            return true
        }
        if (descriptor.downloadUrl.isBlank()) return false
        if (!activeDownloads.add(modelId)) {
            Log.i(TAG, "Download of $modelId already running in-process; worker yields")
            return false
        }
        stopRequests.remove(modelId)
        val self = currentCoroutineContext()[Job]
        if (self != null) downloadJobs[modelId] = self
        try {
            runDownload(descriptor)
        } finally {
            if (downloadJobs[modelId] == self) downloadJobs.remove(modelId)
            downloadCalls.remove(modelId)
            activeDownloads.remove(modelId)
        }
        return isModelReady(modelId)
    }

    private fun cancelBackgroundWork(modelId: String) {
        com.jarvis.assistant.androllm.models.download.ModelDownloadScheduler.cancel(
            context,
            com.jarvis.assistant.androllm.models.download.ModelDownloadScheduler.KIND_GGUF,
            modelId
        )
    }

    private fun markPausedFromPartial(modelId: String) {
        val descriptor = getDescriptor(modelId) ?: return
        val partial = getPartialFile(modelId).let { if (it.exists()) it.length() else 0L }
        val total = descriptor.downloadSizeBytes
        putDownload(
            LocalModelDownload(
                modelId = modelId,
                status = LocalDownloadStatus.PAUSED,
                bytesDownloaded = partial,
                totalBytes = total,
                progressPercent = if (total > 0) ((partial * 100) / total).toInt().coerceIn(0, 100) else 0
            )
        )
    }

    private suspend fun runDownload(descriptor: LocalModelDescriptor) {
        val modelId = descriptor.id
        val targetFile = getModelFile(modelId)
        val tempFile = getPartialFile(modelId)
        updateNotification(modelId, "Downloading ${descriptor.name}", "Connecting to Hugging Face model mirror...", 0, true)

        try {
            var existingBytes = if (tempFile.exists()) tempFile.length() else 0L
            val declaredSize = descriptor.downloadSizeBytes
            if (declaredSize > 0 && existingBytes >= declaredSize) {
                tempFile.delete()
                existingBytes = 0L
            }

            putDownload(
                LocalModelDownload(
                    modelId = modelId,
                    status = LocalDownloadStatus.DOWNLOADING,
                    bytesDownloaded = existingBytes,
                    totalBytes = declaredSize,
                    progressPercent = if (declaredSize > 0) ((existingBytes * 100) / declaredSize).toInt() else 0
                )
            )
            _downloadState.value = ModelDownloadState.Downloading(
                progressPercent = if (declaredSize > 0) ((existingBytes * 100) / declaredSize).toInt() else 0,
                bytesDownloaded = existingBytes,
                totalBytes = declaredSize
            )

            var attemptedRestart = false
            while (true) {
                val requestBuilder = Request.Builder()
                    .url(descriptor.downloadUrl)
                    .header("Accept-Encoding", "identity")
                if (existingBytes > 0) {
                    Log.i(TAG, "Resuming download of ${descriptor.name} from byte $existingBytes...")
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }
                val call = httpClient.newCall(requestBuilder.build())
                downloadCalls[modelId] = call
                val restart = call.execute().use { response ->
                    if (response.code == HTTP_RANGE_NOT_SATISFIABLE && existingBytes > 0 && !attemptedRestart) {
                        // Partial no longer matches the server file: restart once from zero.
                        Log.w(TAG, "HTTP 416 for $modelId; restarting download from zero")
                        tempFile.delete()
                        existingBytes = 0L
                        attemptedRestart = true
                        return@use true
                    }
                    val isResume = response.code == 206
                    val isOk = response.code == 200

                    if (!isResume && !isOk) {
                        throw Exception("HTTP download error: ${response.code} ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Empty response body from model mirror")
                    val contentLength = body.contentLength()
                    val totalExpected = when {
                        contentLength > 0 && isResume -> existingBytes + contentLength
                        contentLength > 0 -> contentLength
                        declaredSize > 0 -> declaredSize
                        else -> -1L
                    }

                    val append = isResume && existingBytes > 0
                    if (!append && tempFile.exists()) {
                        tempFile.delete()
                        existingBytes = 0L
                    }

                    streamBody(descriptor, body.byteStream(), tempFile, append, existingBytes, totalExpected)
                    false
                }
                if (!restart) break
            }

            putDownload(
                LocalModelDownload(
                    modelId = modelId,
                    status = LocalDownloadStatus.VERIFYING,
                    bytesDownloaded = tempFile.length(),
                    totalBytes = tempFile.length(),
                    progressPercent = 100
                )
            )

            // Verify GGUF header before finalizing
            if (!isGgufHeaderValid(tempFile)) {
                tempFile.delete()
                throw Exception("Downloaded file failed GGUF format validation. Corrupted weights discarded.")
            }
            descriptor.sha256?.takeIf { it.length == 64 }?.let { expected ->
                val actual = sha256Of(tempFile)
                if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                    tempFile.delete()
                    throw Exception("SHA-256 mismatch for ${descriptor.name} (expected ${expected.take(12)}…, got ${actual?.take(12) ?: "unreadable"}…). Corrupted weights discarded.")
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "Model $modelId verified & ready: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                removeDownload(modelId)
                _downloadState.value = ModelDownloadState.Completed
                updateNotification(modelId, "Model Ready: ${descriptor.name}", "On-device AI weights verified and ready for offline inference.", 100, false)
            } else {
                throw Exception("Failed to rename temporary download file to final GGUF model path")
            }
        } catch (e: Throwable) {
            val stop = stopRequests.remove(modelId)
            if (stop != null) {
                // User-requested pause (false) or cancel (true).
                if (stop) {
                    tempFile.delete()
                    removeDownload(modelId)
                    clearNotification(modelId)
                    Log.i(TAG, "Download of $modelId cancelled; partial file removed")
                } else {
                    val partial = if (tempFile.exists()) tempFile.length() else 0L
                    val total = descriptor.downloadSizeBytes
                    putDownload(
                        LocalModelDownload(
                            modelId = modelId,
                            status = LocalDownloadStatus.PAUSED,
                            bytesDownloaded = partial,
                            totalBytes = total,
                            progressPercent = if (total > 0) ((partial * 100) / total).toInt().coerceIn(0, 100) else 0
                        )
                    )
                    updateNotification(modelId, "Download paused", descriptor.name, -1, false)
                    Log.i(TAG, "Download of $modelId paused at $partial bytes")
                }
                _downloadState.value = ModelDownloadState.Idle
                return
            }
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed downloading local model $modelId: ${e.message}", e)
            val partial = if (tempFile.exists()) tempFile.length() else 0L
            val total = descriptor.downloadSizeBytes
            putDownload(
                LocalModelDownload(
                    modelId = modelId,
                    status = LocalDownloadStatus.ERROR,
                    bytesDownloaded = partial,
                    totalBytes = total,
                    progressPercent = if (total > 0) ((partial * 100) / total).toInt().coerceIn(0, 100) else 0,
                    errorMessage = e.message ?: "Download failed"
                )
            )
            _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
            updateNotification(modelId, "Download Failed", e.message ?: "Failed downloading model weights", -1, false)
        }
    }

    private suspend fun streamBody(
        descriptor: LocalModelDescriptor,
        input: java.io.InputStream,
        tempFile: File,
        append: Boolean,
        startBytes: Long,
        totalExpected: Long
    ) {
        val modelId = descriptor.id
        input.use { stream ->
            FileOutputStream(tempFile, append).use { out ->
                val buffer = ByteArray(128 * 1024) // 128KB chunks
                var bytesRead: Int
                var downloadedBytes = startBytes
                var lastReportedPercent = -1
                var lastSampleTime = System.currentTimeMillis()
                var lastSampleBytes = downloadedBytes
                var smoothedSpeed = 0f
                var lastPublish = 0L

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    out.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    val dtMs = now - lastSampleTime
                    if (dtMs >= 500) {
                        val instant = (downloadedBytes - lastSampleBytes) * 1000f / dtMs
                        smoothedSpeed = if (smoothedSpeed <= 0f) instant else smoothedSpeed * 0.7f + instant * 0.3f
                        lastSampleTime = now
                        lastSampleBytes = downloadedBytes
                    }
                    val percent = if (totalExpected > 0) ((downloadedBytes * 100) / totalExpected).toInt().coerceIn(0, 100) else 0
                    if (percent > lastReportedPercent || now - lastPublish >= 1000) {
                        lastPublish = now
                        val remaining = (totalExpected - downloadedBytes).coerceAtLeast(0L)
                        val eta = if (smoothedSpeed > 0f && totalExpected > 0) (remaining / smoothedSpeed).toLong() else 0L
                        putDownload(
                            LocalModelDownload(
                                modelId = modelId,
                                status = LocalDownloadStatus.DOWNLOADING,
                                bytesDownloaded = downloadedBytes,
                                totalBytes = totalExpected,
                                progressPercent = percent,
                                speedBytesPerSec = smoothedSpeed,
                                etaSeconds = eta
                            )
                        )
                        if (percent > lastReportedPercent) {
                            lastReportedPercent = percent
                            _downloadState.value = ModelDownloadState.Downloading(percent, downloadedBytes, totalExpected)
                            val dlMb = downloadedBytes / (1024 * 1024)
                            val totMb = totalExpected.coerceAtLeast(0L) / (1024 * 1024)
                            updateNotification(modelId, "Downloading ${descriptor.name}", "$percent% • ${dlMb}MB / ${totMb}MB", percent, totalExpected <= 0)
                        }
                    }
                }
                out.flush()
            }
        }
    }

    private fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    fun deleteModel(modelId: String): Boolean {
        if (activeDownloads.contains(modelId)) cancelDownload(modelId)
        val file = getModelFile(modelId)
        val deleted = if (file.exists()) file.delete() else true
        getPartialFile(modelId).takeIf { it.exists() && !activeDownloads.contains(modelId) }?.delete()
        removeDownload(modelId)
        // Imported models have no download URL: drop their registration with the file.
        val custom = _customModels.value.firstOrNull { it.id == modelId }
        if (deleted && custom != null && custom.downloadUrl.isBlank()) removeCustomModel(modelId)
        Log.i(TAG, "Deleted model $modelId: $deleted")
        return deleted
    }

    fun getAvailableStorageBytes(): Long {
        return context.filesDir.usableSpace
    }
}
