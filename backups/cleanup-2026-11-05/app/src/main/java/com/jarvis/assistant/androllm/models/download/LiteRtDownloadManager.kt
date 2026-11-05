package com.jarvis.assistant.androllm.models.download

import com.jarvis.assistant.androllm.engine.support.formatBytes
import com.jarvis.assistant.androllm.engine.support.ValidationFailure
import com.jarvis.assistant.androllm.engine.support.RepairAction
import com.jarvis.assistant.androllm.engine.support.PreflightInfo
import com.jarvis.assistant.androllm.engine.support.FileCheck
import com.jarvis.assistant.androllm.engine.support.FailureReason
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.androllm.AndroLlmPaths
import com.jarvis.assistant.androllm.models.catalog.CatalogModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

/** Lifecycle of one LiteRT artifact download. */
enum class LiteRtDownloadStatus { QUEUED, DOWNLOADING, VERIFYING, PAUSED, DOWNLOADED, ERROR }

/** What to download and where (inside [AndroLlmPaths.liteRtModelsDir]). */
data class LiteRtDownloadRequest(
    val modelId: String,
    val name: String,
    val downloadUrl: String,
    /** Final file name in the LiteRT models dir (e.g. `gemma-4-E2B-it.litertlm`). */
    val fileName: String,
    val expectedSize: Long = 0L,
    val sha256: String? = null,
    /** Optional companion artifact saved next to the model as `tokenizer.model`. */
    val companionUrl: String = "",
    /** Queue priority (HIGH runs as expedited work when background downloads are on). */
    val priority: DownloadPriority = DownloadPriority.NORMAL
)

/** Real-time snapshot of a LiteRT download (mirrors AndroLLM's `DownloadProgress`). */
data class LiteRtDownloadProgress(
    val modelId: String,
    val name: String,
    val fileName: String,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Float = 0f,
    val etaSeconds: Long = 0,
    val progressPercent: Int = 0,
    val status: LiteRtDownloadStatus = LiteRtDownloadStatus.QUEUED,
    val errorMessage: String? = null
)

/**
 * Downloads LiteRT-LM (`.litertlm`), TFLite and MediaPipe `.task` artifacts into
 * [AndroLlmPaths.liteRtModelsDir] for the LiteRT engine.
 *
 * Port of AndroLLM's WorkManager `DownloadManager` + `ModelDownloadWorker` pipeline onto an
 * OkHttp coroutine engine (Jarvis has no Hilt/Room model table):
 *
 * 1. GATE — [ModelCompatibility] rejects unrunnable catalog models before any byte moves.
 * 2. PREFLIGHT — HEAD handshake with manual redirects (captures HuggingFace's `X-Linked-ETag`
 *    LFS SHA-256 on the first hop), falling back to a 1-byte Range probe.
 * 3. RESUME CHECK — the `.part` file is assessed before appending; oversized / wrong-magic
 *    partials are truncated and restarted.
 * 4. STREAM — GET with Range resume, identity encoding, 416 repair, progress/speed/ETA.
 * 5. VERIFY — server size, SHA-256 (catalog, else LFS hash), container header; only then the
 *    `.part` is renamed to the final name, so the engine never sees a partial artifact.
 * 6. REPAIR — transient failures retry with exponential backoff, keeping the partial.
 *
 * Pause keeps the `.part` file (resumed via HTTP Range, also after an app restart); cancel
 * removes it. Process singleton — use [getInstance].
 */
class LiteRtDownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LiteRtDownloadManager"
        private const val CHANNEL_ID = "androllm_litert_downloads"
        private const val NOTIFICATION_BASE_ID = 4100
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SPEED_SMOOTHING_FACTOR = 0.3f
        private const val MIN_SPEED_UPDATE_MS = 500L
        private const val MAX_REDIRECTS = 10
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val BACKOFF_BASE_MS = 30_000L
        private const val PREFS = "androllm_litert_downloads"
        private const val KEY_REQUESTS = "pending_requests"
        const val PART_SUFFIX = ".part"

        @Volatile
        private var instance: LiteRtDownloadManager? = null

        fun getInstance(context: Context): LiteRtDownloadManager =
            instance ?: synchronized(this) {
                instance ?: LiteRtDownloadManager(context.applicationContext).also { instance = it }
            }

        /**
         * True when an HTTP status code is a permanent client error (4xx) that retry
         * cannot fix. 408 and 429 are transient, as is any 5xx.
         */
        internal fun isHttpFailurePermanent(responseCode: Int): Boolean =
            responseCode in 400..499 && responseCode != 408 && responseCode != 429

        /** Parses the total size from a Content-Range header ("bytes 0-0/497664000"). */
        internal fun parseContentRangeTotal(contentRange: String?): Long? {
            if (contentRange.isNullOrBlank()) return null
            val total = contentRange.substringAfterLast('/', "").trim()
            if (total == "*") return null
            return total.toLongOrNull()?.takeIf { it > 0 }
        }

        /** True when the URL is non-blank, parseable and http(s). */
        internal fun isValidDownloadUrl(url: String): Boolean {
            val uri = try {
                URI.create(url)
            } catch (e: Exception) {
                null
            }
            return uri != null && (uri.scheme == "http" || uri.scheme == "https")
        }

        /** File-system safe artifact name (keeps the extension). */
        fun safeFileName(raw: String): String =
            raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifBlank { "model.litertlm" }
    }

    private class StopSignal(val cancel: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Redirects handled manually so the first hop's headers (X-Linked-ETag) are visible. */
    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val cache = DownloadCache(context.cacheDir)

    private val requests = ConcurrentHashMap<String, LiteRtDownloadRequest>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val stopSignals = ConcurrentHashMap<String, StopSignal>()
    /** Downloads handed to WorkManager ([ModelDownloadWorker]) instead of [scope]. */
    private val workerBacked = ConcurrentHashMap.newKeySet<String>()

    private val _downloads = MutableStateFlow<Map<String, LiteRtDownloadProgress>>(emptyMap())
    /** Every queued / running / paused / failed LiteRT download keyed by model id. */
    val downloads: StateFlow<Map<String, LiteRtDownloadProgress>> = _downloads.asStateFlow()

    private val _installedVersion = MutableStateFlow(0L)
    /** Bumped whenever an artifact finishes or is removed — observers rescan the models dir. */
    val installedVersion: StateFlow<Long> = _installedVersion.asStateFlow()

    init {
        createNotificationChannel()
        restorePersistedRequests()
    }

    fun modelsDir(): File = AndroLlmPaths.liteRtModelsDir(context)

    fun targetFile(fileName: String): File = File(modelsDir(), safeFileName(fileName))

    fun partFile(fileName: String): File = File(modelsDir(), safeFileName(fileName) + PART_SUFFIX)

    // ───────────────────────────── Public API ─────────────────────────────

    /**
     * Enqueues a [CatalogModel] after the compatibility gate. Models that can never run on
     * the LiteRT runtime are rejected with the reason and never downloaded.
     */
    fun startCatalogDownload(
        catalogModel: CatalogModel,
        priority: DownloadPriority = DownloadPriority.NORMAL
    ): ModelCompatibility.GateResult {
        val gate = ModelCompatibility.check(catalogModel)
        if (!gate.compatible) {
            Log.e(TAG, "Blocking download of ${catalogModel.name}: ${gate.reason}")
            publish(
                LiteRtDownloadProgress(
                    modelId = catalogModel.id,
                    name = catalogModel.name,
                    fileName = catalogModel.fileName,
                    status = LiteRtDownloadStatus.ERROR,
                    errorMessage = gate.reason
                )
            )
            return gate
        }
        startDownload(
            LiteRtDownloadRequest(
                modelId = catalogModel.id,
                name = catalogModel.name,
                downloadUrl = catalogModel.downloadUrl,
                fileName = catalogModel.fileName,
                expectedSize = catalogModel.sizeBytes,
                sha256 = catalogModel.sha256,
                companionUrl = catalogModel.companionUrl,
                priority = priority
            )
        )
        return gate
    }

    /** Starts (or restarts) a download. A running download of the same id is kept. */
    fun startDownload(request: LiteRtDownloadRequest) {
        if (jobs[request.modelId]?.isActive == true) {
            Log.w(TAG, "Download ${request.modelId} already running; ignoring duplicate request")
            return
        }
        if (!isValidDownloadUrl(request.downloadUrl)) {
            publish(
                LiteRtDownloadProgress(
                    modelId = request.modelId,
                    name = request.name,
                    fileName = request.fileName,
                    status = LiteRtDownloadStatus.ERROR,
                    errorMessage = "Invalid download URL: ${request.downloadUrl.ifBlank { "(empty)" }}"
                )
            )
            return
        }
        requests[request.modelId] = request
        persistRequests()
        stopSignals.remove(request.modelId)
        val existing = partFile(request.fileName).let { if (it.exists()) it.length() else 0L }
        publish(
            LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = request.fileName,
                bytesDownloaded = existing,
                totalBytes = request.expectedSize,
                progressPercent = percentOf(existing, request.expectedSize),
                status = LiteRtDownloadStatus.QUEUED
            )
        )
        if (BackgroundDownloadPrefs.isEnabled(context)) {
            // Opt-in background mode: WorkManager owns the run (constraints, process death).
            workerBacked.add(request.modelId)
            ModelDownloadScheduler.enqueueLiteRt(context, request)
            return
        }
        workerBacked.remove(request.modelId)
        val job = scope.launch { runWithRetries(request) }
        jobs[request.modelId] = job
        job.invokeOnCompletion {
            jobs.remove(request.modelId)
            calls.remove(request.modelId)
        }
    }

    /** Pauses an active download; the `.part` file is kept for Range resume. */
    fun pauseDownload(modelId: String) {
        // Harmless when nothing is queued; also covers work restored after a process restart.
        ModelDownloadScheduler.cancel(context, ModelDownloadScheduler.KIND_LITERT, modelId)
        if (modelId in workerBacked) {
            if (jobs[modelId]?.isActive != true) {
                requests[modelId]?.let { markPaused(it) }
                return
            }
        }
        val job = jobs[modelId] ?: return
        stopSignals[modelId] = StopSignal(cancel = false)
        calls[modelId]?.cancel()
        job.cancel()
    }

    /** Resumes a paused or failed download. */
    fun resumeDownload(modelId: String) {
        val request = requests[modelId] ?: return
        startDownload(request)
    }

    /** Retries a failed download — keeps the partial file so the retry resumes. */
    fun retryDownload(modelId: String) = resumeDownload(modelId)

    /** Cancels a download and removes its partial file. */
    fun cancelDownload(modelId: String) {
        ModelDownloadScheduler.cancel(context, ModelDownloadScheduler.KIND_LITERT, modelId)
        workerBacked.remove(modelId)
        val job = jobs[modelId]
        if (job != null && job.isActive) {
            stopSignals[modelId] = StopSignal(cancel = true)
            calls[modelId]?.cancel()
            job.cancel()
        } else {
            finishCancel(modelId)
        }
    }

    fun pauseAll() = jobs.keys.toList().forEach { pauseDownload(it) }

    fun resumeAll() = _downloads.value.values
        .filter { it.status == LiteRtDownloadStatus.PAUSED || it.status == LiteRtDownloadStatus.ERROR }
        .forEach { resumeDownload(it.modelId) }

    fun cancelAll() = _downloads.value.keys.toList().forEach { cancelDownload(it) }

    /** Clears a terminal (error) entry from the list without touching files. */
    fun dismiss(modelId: String) {
        if (jobs[modelId]?.isActive == true) return
        _downloads.update { it - modelId }
        if (!partFile(requests[modelId]?.fileName ?: return).exists()) {
            requests.remove(modelId)
            persistRequests()
        }
    }

    /** Deletes an installed LiteRT artifact (and its companion tokenizer if no other model uses the dir). */
    fun deleteInstalled(file: File): Boolean {
        val ok = !file.exists() || file.delete()
        if (ok) _installedVersion.value = System.currentTimeMillis()
        return ok
    }

    /**
     * Runs one download inside the caller's coroutine — used by [ModelDownloadWorker]
     * for opt-in background downloads. Same pipeline as in-process downloads; pause /
     * cancel from the UI work as usual. Returns the final snapshot (null when the
     * entry was removed, e.g. cancelled).
     */
    suspend fun runInWorker(request: LiteRtDownloadRequest): LiteRtDownloadProgress? {
        if (targetFile(request.fileName).exists()) {
            _downloads.update { it - request.modelId }
            requests.remove(request.modelId)
            persistRequests()
            return LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = request.fileName,
                progressPercent = 100,
                status = LiteRtDownloadStatus.DOWNLOADED
            )
        }
        val running = jobs[request.modelId]
        val self = currentCoroutineContext()[Job]
        if (running != null && running.isActive && running != self) {
            Log.i(TAG, "${request.name} already downloading in-process; worker yields")
            return _downloads.value[request.modelId]
        }
        if (!isValidDownloadUrl(request.downloadUrl)) {
            failPermanent(request, "Invalid download URL: ${request.downloadUrl.ifBlank { "(empty)" }}")
            return _downloads.value[request.modelId]
        }
        requests[request.modelId] = request
        persistRequests()
        workerBacked.add(request.modelId)
        stopSignals.remove(request.modelId)
        if (self != null) jobs[request.modelId] = self
        try {
            runWithRetries(request)
        } catch (e: CancellationException) {
            // System stop (constraints lost): WorkManager re-runs it; keep the partial.
            updateEntry(request.modelId) {
                it.copy(
                    status = LiteRtDownloadStatus.QUEUED,
                    speedBytesPerSec = 0f,
                    etaSeconds = 0L,
                    errorMessage = "Waiting for network / system conditions — will resume automatically"
                )
            }
            throw e
        } finally {
            if (jobs[request.modelId] == self) jobs.remove(request.modelId)
            calls.remove(request.modelId)
        }
        return _downloads.value[request.modelId]
    }

    /** True when [modelId] is handled by WorkManager rather than the in-process scope. */
    fun isBackgroundDownload(modelId: String): Boolean = modelId in workerBacked

    /** Signals observers that the LiteRT models dir changed (e.g. after an import). */
    fun notifyInstalledChanged() {
        _installedVersion.value = System.currentTimeMillis()
    }

    // ───────────────────────────── Pipeline ─────────────────────────────

    private sealed interface Outcome {
        data object Success : Outcome
        data class Transient(val message: String) : Outcome
        data class Permanent(val message: String) : Outcome
    }

    private suspend fun runWithRetries(request: LiteRtDownloadRequest) {
        var attempt = 0
        try {
            while (true) {
                when (val outcome = runOnce(request)) {
                    Outcome.Success -> return
                    is Outcome.Permanent -> {
                        failPermanent(request, outcome.message)
                        return
                    }
                    is Outcome.Transient -> {
                        if (attempt >= MAX_TRANSIENT_RETRIES) {
                            failPermanent(request, outcome.message)
                            return
                        }
                        val backoff = BACKOFF_BASE_MS shl attempt
                        attempt++
                        Log.w(TAG, "Transient failure for ${request.name}: ${outcome.message} — retry $attempt in ${backoff / 1000}s")
                        updateEntry(request.modelId) {
                            it.copy(
                                status = LiteRtDownloadStatus.QUEUED,
                                speedBytesPerSec = 0f,
                                etaSeconds = 0L,
                                errorMessage = "${outcome.message.lineSequence().firstOrNull().orEmpty()} — retrying in ${backoff / 1000}s ($attempt/$MAX_TRANSIENT_RETRIES)"
                            )
                        }
                        delay(backoff)
                    }
                }
            }
        } catch (e: Throwable) {
            val signal = stopSignals.remove(request.modelId)
            when {
                signal?.cancel == true -> finishCancel(request.modelId)
                signal != null -> markPaused(request)
                e is CancellationException -> throw e
                else -> failPermanent(request, e.message ?: "Unexpected download error")
            }
        }
    }

    private suspend fun runOnce(request: LiteRtDownloadRequest): Outcome {
        val modelName = request.name
        val target = targetFile(request.fileName)
        val part = partFile(request.fileName)
        val parentDir = part.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            return Outcome.Permanent("Cannot create download directory: ${parentDir.absolutePath}")
        }
        if (parentDir != null && !parentDir.canWrite()) {
            return Outcome.Permanent("Download directory is not writable: ${parentDir.absolutePath}")
        }
        val expectedFormat = ArtifactValidator.expectedFormatFor(request.fileName)
            ?: return Outcome.Permanent(
                "'${request.fileName}' is not a LiteRT artifact (.litertlm / .tflite / .task)."
            )

        var downloadedBytes = if (part.exists()) part.length() else 0L
        val catalogSize = request.expectedSize

        // ---- Stage 1: preflight (network truth; best-effort, cached) ----
        val preflight = cache.getPreflight(request.downloadUrl)
            ?: performPreflight(request.downloadUrl, modelName)?.also {
                if (!it.gated) cache.putPreflight(request.downloadUrl, it)
            }
        val effectiveSha = request.sha256?.takeIf { it.length == 64 }
            ?: preflight?.recoveredSha256()?.also {
                Log.i(TAG, "Preflight $modelName: recovered LFS sha256 ${it.take(16)}… from headers")
            }
        val preflightSize = preflight?.authoritativeSize
        if (preflightSize != null && catalogSize > 0 && preflightSize != catalogSize) {
            Log.w(TAG, "Preflight $modelName: server declares $preflightSize bytes but catalog says $catalogSize — trusting server")
        }
        if (preflight?.gated == true) {
            val failure = ValidationFailure(
                reason = FailureReason.GATED_MODEL,
                url = request.downloadUrl,
                detail = "Gated model — HuggingFace answered ${preflight.httpStatus}. A user access " +
                    "token is required to download this artifact.",
                suggestedAction = "Accept the model's license on huggingface.co with a token-enabled account, then retry.",
                repairAction = RepairAction.REQUIRE_USER_ACTION,
            )
            return Outcome.Permanent(failure.formatReport(modelName))
        }

        // ---- Stage 2: assess the partial before resuming ----
        val resumeBudget = preflightSize ?: catalogSize.takeIf { it > 0 } ?: -1L
        val partialProblem = if (downloadedBytes > 0) {
            ArtifactValidator.assessPartial(part.absolutePath, downloadedBytes, resumeBudget)
        } else {
            null
        }
        if (partialProblem != null) {
            Log.w(TAG, "Resume check $modelName: ${partialProblem.detail} — truncating partial and restarting")
            truncateInPlace(part)
            downloadedBytes = 0L
        }

        // Fast path: bytes already complete (previous attempt stopped during verification).
        val completeSize = preflightSize ?: catalogSize.takeIf { it > 0 }
        if (completeSize != null && downloadedBytes == completeSize) {
            Log.i(TAG, "Fast path $modelName: file already complete ($downloadedBytes bytes) — verifying")
            return verifyAndFinish(request, part, target, preflightSize, effectiveSha, expectedFormat)
        }

        // Storage check before downloading.
        val sizeHint = preflightSize ?: catalogSize.takeIf { it > 0 } ?: 0L
        if (sizeHint > 0 && parentDir != null) {
            val needed = (sizeHint - downloadedBytes).coerceAtLeast(0L)
            val usable = parentDir.usableSpace
            if (usable in 1 until needed) {
                val failure = ValidationFailure(
                    reason = FailureReason.INSUFFICIENT_STORAGE,
                    expectedBytes = needed,
                    actualBytes = usable,
                    detail = "Insufficient storage space.",
                    suggestedAction = "Free ${needed.formatBytes()} and retry.",
                    repairAction = RepairAction.REQUIRE_USER_ACTION,
                )
                return Outcome.Permanent(failure.formatReport(modelName))
            }
        }

        Log.i(TAG, "Download start: model=$modelName url=${request.downloadUrl} target=${target.absolutePath} resume=$downloadedBytes catalogSize=$catalogSize sha256=${effectiveSha ?: "none"}")

        var restarted = false
        while (true) {
            val builder = Request.Builder()
                .url(request.downloadUrl)
                .header("User-Agent", "Jarvis-AndroLLM/1.0 (Android)")
                .header("Accept-Encoding", "identity")
            if (downloadedBytes > 0) builder.header("Range", "bytes=$downloadedBytes-")
            val call = client.newCall(builder.build())
            calls[request.modelId] = call

            val outcome: Outcome? = try {
                call.execute().use { response ->
                    val code = response.code
                    val isPartial = code == 206
                    when {
                        code == 200 -> {
                            if (downloadedBytes > 0) {
                                Log.w(TAG, "Server ignored Range request; restarting download from 0")
                                truncateInPlace(part)
                                downloadedBytes = 0
                            }
                        }
                        isPartial -> Unit
                        code == HTTP_RANGE_NOT_SATISFIABLE && !restarted -> {
                            Log.w(TAG, "HTTP 416 for $modelName — partial no longer matches server file; restarting")
                            truncateInPlace(part)
                            downloadedBytes = 0
                            restarted = true
                            return@use null
                        }
                        code == 401 || code == 403 -> {
                            val failure = ValidationFailure(
                                reason = FailureReason.GATED_MODEL,
                                url = request.downloadUrl,
                                detail = "Gated model — server answered HTTP $code. A user access token is required.",
                                suggestedAction = "Accept the model's license on huggingface.co, then retry.",
                                repairAction = RepairAction.REQUIRE_USER_ACTION,
                            )
                            return@use Outcome.Permanent(failure.formatReport(modelName))
                        }
                        isHttpFailurePermanent(code) -> return@use Outcome.Permanent("HTTP Error $code")
                        else -> return@use Outcome.Transient("HTTP Error $code")
                    }

                    val body = response.body ?: return@use Outcome.Transient("Empty response body")
                    val contentLength = body.contentLength()
                    val serverTotal = when {
                        contentLength > 0 && isPartial -> contentLength + downloadedBytes
                        contentLength > 0 -> contentLength
                        else -> null
                    }
                    val totalBytes = serverTotal ?: preflightSize ?: catalogSize.takeIf { it > 0 } ?: -1L
                    publishProgress(request, downloadedBytes, totalBytes, 0f, 0L)

                    val append = isPartial && downloadedBytes > 0
                    body.byteStream().use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var lastProgressTime = System.currentTimeMillis()
                            var lastReportedBytes = downloadedBytes
                            var smoothed = 0f
                            while (input.read(buffer).also { read = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = System.currentTimeMillis()
                                val elapsed = now - lastProgressTime
                                if (elapsed >= MIN_SPEED_UPDATE_MS) {
                                    val instant = (downloadedBytes - lastReportedBytes) / (elapsed / 1000f).coerceAtLeast(0.001f)
                                    smoothed = if (smoothed == 0f) instant
                                    else SPEED_SMOOTHING_FACTOR * instant + (1f - SPEED_SMOOTHING_FACTOR) * smoothed
                                    val remaining = totalBytes - downloadedBytes
                                    val eta = if (smoothed > 0f && remaining > 0L) (remaining / smoothed).toLong() else 0L
                                    publishProgress(request, downloadedBytes, totalBytes, smoothed, eta)
                                    lastProgressTime = now
                                    lastReportedBytes = downloadedBytes
                                }
                            }
                            output.flush()
                        }
                    }
                    Log.i(TAG, "Download complete $modelName: $downloadedBytes bytes (server=${serverTotal ?: "unknown"} catalog=$catalogSize)")
                    verifyAndFinish(request, part, target, serverTotal ?: preflightSize, effectiveSha, expectedFormat)
                }
            } catch (e: IOException) {
                if (stopSignals.containsKey(request.modelId)) throw CancellationException("stopped by user")
                Log.e(TAG, "Network failure downloading $modelName: ${e.message}")
                Outcome.Transient(e.message ?: "Network failure")
            }
            if (outcome != null) return outcome
        }
    }

    /**
     * Verification + import: network size, SHA-256, container header. Only a fully verified
     * file is renamed from `.part` to its final name.
     */
    private suspend fun verifyAndFinish(
        request: LiteRtDownloadRequest,
        part: File,
        target: File,
        networkSize: Long?,
        effectiveSha: String?,
        expectedFormat: String
    ): Outcome {
        updateEntry(request.modelId) { it.copy(status = LiteRtDownloadStatus.VERIFYING, speedBytesPerSec = 0f, etaSeconds = 0L) }
        when (val check = ArtifactValidator.validateFile(
            path = part.absolutePath,
            authoritativeSize = networkSize,
            catalogSize = request.expectedSize,
            expectedSha256 = effectiveSha,
            expectedFormat = expectedFormat,
            modelName = request.name,
        )) {
            is FileCheck.Valid -> {
                if (networkSize == null && request.expectedSize > 0 && part.length() != request.expectedSize) {
                    Log.w(TAG, "Verify ${request.name}: no server size — catalog says ${request.expectedSize} but file is ${part.length()}; accepting on hash/container verdict")
                }
            }
            is FileCheck.Invalid -> {
                val failure = check.failure
                Log.e(TAG, "Verify ${request.name} failed: ${failure.formatReport(request.name)}")
                return when (failure.repairAction) {
                    RepairAction.RESUME_MISSING_BYTES -> Outcome.Transient(failure.formatReport(request.name))
                    RepairAction.DELETE_AND_FULL_REDOWNLOAD,
                    RepairAction.DELETE_PARTIAL_AND_RESTART -> {
                        truncateInPlace(part)
                        cache.invalidatePreflight(request.downloadUrl)
                        Outcome.Transient(failure.formatReport(request.name))
                    }
                    else -> Outcome.Permanent(failure.formatReport(request.name))
                }
            }
        }
        effectiveSha?.let { cache.putHash(part, it.lowercase()) }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            return Outcome.Permanent("Could not move verified download into place: ${target.absolutePath}")
        }
        Log.i(TAG, "Download verified for ${request.name}: ${target.absolutePath} (${target.length()} bytes)")

        if (request.companionUrl.isNotBlank()) {
            downloadCompanion(request.companionUrl, File(target.parentFile, "tokenizer.model"), request.name)
        }

        requests.remove(request.modelId)
        persistRequests()
        publish(
            LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = target.name,
                bytesDownloaded = target.length(),
                totalBytes = target.length(),
                progressPercent = 100,
                status = LiteRtDownloadStatus.DOWNLOADED
            )
        )
        _downloads.update { it - request.modelId }
        _installedVersion.value = System.currentTimeMillis()
        showDoneNotification(request.modelId, "✓ ${request.name} downloaded", "Verified and saved for the LiteRT engine.", android.R.drawable.stat_sys_download_done)
        return Outcome.Success
    }

    /**
     * Preflight: HEAD with manual redirect following so the FIRST response's headers
     * (HuggingFace X-Linked-ETag) are captured; falls back to a 1-byte Range probe.
     * Best-effort — returns null when both fail (the GET pass recovers).
     */
    private fun performPreflight(initialUrl: String, modelName: String): PreflightInfo? {
        try {
            var currentUrl = initialUrl
            var hops = 0
            var firstLinkedETag: String? = null
            var firstStatus = 200
            var done = false
            while (!done && hops <= MAX_REDIRECTS) {
                val request = Request.Builder().url(currentUrl).head()
                    .header("User-Agent", "Jarvis-AndroLLM/1.0 (Android)")
                    .header("Accept-Encoding", "identity")
                    .build()
                noRedirectClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (hops == 0) {
                        firstStatus = code
                        firstLinkedETag = response.header("X-Linked-ETag")
                    }
                    if (code == 401 || code == 403) {
                        return PreflightInfo(url = initialUrl, httpStatus = code, gated = true)
                    }
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val next = response.header("Location")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { response.request.url.resolve(it)?.toString() }
                        if (next == null) {
                            done = true
                        } else {
                            currentUrl = next
                            hops++
                        }
                        return@use
                    }
                    val headers = linkedMapOf<String, String>()
                    response.headers.names().forEach { name -> response.header(name)?.let { headers[name] = it } }
                    val hex = Regex("[0-9a-fA-F]{64}")
                    val info = PreflightInfo(
                        url = initialUrl,
                        finalUrl = currentUrl,
                        httpStatus = code,
                        contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 },
                        supportsRanges = (response.header("Accept-Ranges") ?: "").equals("bytes", ignoreCase = true),
                        eTag = response.header("ETag"),
                        lfsSha256 = firstLinkedETag?.trim()?.trim('"')?.takeIf { it.matches(hex) }?.lowercase()
                            ?: response.header("X-Linked-ETag")?.trim()?.trim('"')?.takeIf { it.matches(hex) }?.lowercase(),
                        lastModified = response.header("Last-Modified"),
                        mimeType = response.header("Content-Type")?.substringBefore(";")?.trim(),
                        chunked = "chunked".equals(response.header("Transfer-Encoding"), ignoreCase = true),
                        responseHeaders = headers,
                    )
                    if (code in 200..299) return info
                    if (firstStatus == 401 || firstStatus == 403) return info.copy(gated = true)
                    done = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preflight HEAD failed for $modelName — trying Range probe: ${e.message}")
        }

        try {
            val request = Request.Builder().url(initialUrl)
                .header("User-Agent", "Jarvis-AndroLLM/1.0 (Android)")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 401 || code == 403) {
                    return PreflightInfo(url = initialUrl, httpStatus = code, gated = true)
                }
                if (code == 206 || code == 200) {
                    val total = parseContentRangeTotal(response.header("Content-Range"))
                        ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 && code == 200 }
                    val headers = linkedMapOf<String, String>()
                    response.headers.names().forEach { name -> response.header(name)?.let { headers[name] = it } }
                    return PreflightInfo(
                        url = initialUrl,
                        finalUrl = response.request.url.toString(),
                        httpStatus = code,
                        contentLength = total,
                        supportsRanges = code == 206 ||
                            (response.header("Accept-Ranges") ?: "").equals("bytes", ignoreCase = true),
                        mimeType = response.header("Content-Type")?.substringBefore(";")?.trim(),
                        responseHeaders = headers,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preflight Range probe failed for $modelName — GET will recover: ${e.message}")
        }
        return null
    }

    /**
     * Companion artifact (e.g. the sentencepiece tokenizer for an embedding `.tflite`), saved
     * next to the model as `tokenizer.model`. Failures don't fail the model download.
     */
    private fun downloadCompanion(url: String, target: File, modelName: String) {
        try {
            target.parentFile?.mkdirs()
            if (target.exists() && target.length() > 0) return
            val request = Request.Builder().url(url)
                .header("User-Agent", "Jarvis-AndroLLM/1.0 (Android)")
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    Log.w(TAG, "Companion download for $modelName failed: HTTP ${response.code}")
                    return
                }
                val tmp = File(target.parentFile, target.name + PART_SUFFIX)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (tmp.exists() && tmp.length() > 0) tmp.renameTo(target)
            }
            Log.i(TAG, "Companion artifact downloaded for $modelName: ${target.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Companion download for $modelName failed: ${e.message}")
        }
    }

    // ───────────────────────────── State helpers ─────────────────────────────

    private fun percentOf(bytes: Long, total: Long): Int =
        if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

    private fun publish(progress: LiteRtDownloadProgress) {
        _downloads.update { it + (progress.modelId to progress) }
    }

    private fun updateEntry(modelId: String, transform: (LiteRtDownloadProgress) -> LiteRtDownloadProgress) {
        _downloads.update { map ->
            val current = map[modelId] ?: return@update map
            map + (modelId to transform(current))
        }
    }

    private fun publishProgress(request: LiteRtDownloadRequest, bytes: Long, total: Long, speed: Float, eta: Long) {
        val percent = percentOf(bytes, total)
        publish(
            LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = request.fileName,
                bytesDownloaded = bytes,
                totalBytes = total,
                speedBytesPerSec = speed,
                etaSeconds = eta,
                progressPercent = percent,
                status = LiteRtDownloadStatus.DOWNLOADING
            )
        )
        showProgressNotification(request, percent, bytes, total, total <= 0)
    }

    private fun markPaused(request: LiteRtDownloadRequest) {
        val partial = partFile(request.fileName).let { if (it.exists()) it.length() else 0L }
        val total = _downloads.value[request.modelId]?.totalBytes?.takeIf { it > 0 } ?: request.expectedSize
        publish(
            LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = request.fileName,
                bytesDownloaded = partial,
                totalBytes = total,
                progressPercent = percentOf(partial, total),
                status = LiteRtDownloadStatus.PAUSED
            )
        )
        cancelNotification(request.modelId)
        Log.i(TAG, "Paused ${request.name} at $partial bytes")
    }

    private fun finishCancel(modelId: String) {
        val request = requests.remove(modelId)
        persistRequests()
        val fileName = request?.fileName ?: _downloads.value[modelId]?.fileName
        if (fileName != null) partFile(fileName).delete()
        _downloads.update { it - modelId }
        cancelNotification(modelId)
        Log.i(TAG, "Cancelled download $modelId")
    }

    private fun failPermanent(request: LiteRtDownloadRequest, message: String) {
        Log.e(TAG, "Download failure for ${request.name}: $message")
        val partial = partFile(request.fileName).let { if (it.exists()) it.length() else 0L }
        val total = _downloads.value[request.modelId]?.totalBytes?.takeIf { it > 0 } ?: request.expectedSize
        publish(
            LiteRtDownloadProgress(
                modelId = request.modelId,
                name = request.name,
                fileName = request.fileName,
                bytesDownloaded = partial,
                totalBytes = total,
                progressPercent = percentOf(partial, total),
                status = LiteRtDownloadStatus.ERROR,
                errorMessage = message
            )
        )
        showDoneNotification(request.modelId, "⚠ Download failed: ${request.name}", message.take(200), android.R.drawable.stat_notify_error)
    }

    /** Truncates a stale partial in place (zero bytes, same path). */
    private fun truncateInPlace(file: File) {
        runCatching { FileOutputStream(file, false).close() }
            .onFailure { Log.e(TAG, "Truncate failed for ${file.absolutePath}: ${it.message}") }
    }

    /** Paused/failed downloads survive restarts: requests are persisted and shown as PAUSED. */
    private fun restorePersistedRequests() {
        val raw = prefs.getString(KEY_REQUESTS, null) ?: return
        try {
            val arr = JSONArray(raw)
            val restored = mutableMapOf<String, LiteRtDownloadProgress>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val request = LiteRtDownloadRequest(
                    modelId = o.optString("modelId"),
                    name = o.optString("name"),
                    downloadUrl = o.optString("downloadUrl"),
                    fileName = o.optString("fileName"),
                    expectedSize = o.optLong("expectedSize", 0L),
                    sha256 = o.optString("sha256").takeIf { it.length == 64 },
                    companionUrl = o.optString("companionUrl"),
                    priority = DownloadPriority.fromValue(o.optInt("priority", DownloadPriority.NORMAL.value))
                )
                if (request.modelId.isBlank() || request.fileName.isBlank()) continue
                if (targetFile(request.fileName).exists()) continue
                requests[request.modelId] = request
                val partial = partFile(request.fileName).let { if (it.exists()) it.length() else 0L }
                restored[request.modelId] = LiteRtDownloadProgress(
                    modelId = request.modelId,
                    name = request.name,
                    fileName = request.fileName,
                    bytesDownloaded = partial,
                    totalBytes = request.expectedSize,
                    progressPercent = percentOf(partial, request.expectedSize),
                    status = LiteRtDownloadStatus.PAUSED
                )
            }
            _downloads.value = restored
            persistRequests()
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore pending downloads: ${e.message}")
        }
    }

    private fun persistRequests() {
        val arr = JSONArray()
        requests.values.forEach { r ->
            arr.put(
                JSONObject()
                    .put("modelId", r.modelId)
                    .put("name", r.name)
                    .put("downloadUrl", r.downloadUrl)
                    .put("fileName", r.fileName)
                    .put("expectedSize", r.expectedSize)
                    .put("sha256", r.sha256 ?: "")
                    .put("companionUrl", r.companionUrl)
                    .put("priority", r.priority.value)
            )
        }
        prefs.edit().putString(KEY_REQUESTS, arr.toString()).apply()
    }

    // ───────────────────────────── Notifications ─────────────────────────────

    private fun notificationId(modelId: String): Int = NOTIFICATION_BASE_ID + (modelId.hashCode() and 0x3FF)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "LiteRT Model Downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows live LiteRT model download progress" }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private val lastNotifyAt = ConcurrentHashMap<String, Long>()

    private fun showProgressNotification(request: LiteRtDownloadRequest, percent: Int, bytes: Long, total: Long, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (now - (lastNotifyAt[request.modelId] ?: 0L) < 1000L && percent < 100) return
        lastNotifyAt[request.modelId] = now
        runCatching {
            val text = if (total > 0) "$percent% • ${bytes / (1024 * 1024)} MB / ${total / (1024 * 1024)} MB"
            else "${bytes / (1024 * 1024)} MB"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Downloading ${request.name}")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            notificationManager?.notify(notificationId(request.modelId), notification)
        }
    }

    private fun showDoneNotification(modelId: String, title: String, text: String, icon: Int) {
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(icon)
                .setAutoCancel(true)
                .build()
            notificationManager?.notify(notificationId(modelId), notification)
        }
    }

    private fun cancelNotification(modelId: String) {
        runCatching { notificationManager?.cancel(notificationId(modelId)) }
    }
}
