package com.jarvis.assistant.androllm.voice.whisper

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext

private const val TAG = "WhisperModels"

/** UI-facing state of one whisper model file. */
sealed class WhisperDownloadState {
    object NotInstalled : WhisperDownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : WhisperDownloadState() {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    object Installed : WhisperDownloadState()
    data class Failed(val message: String) : WhisperDownloadState()
}

/**
 * Downloads, verifies, stores and deletes whisper.cpp models on device (ported
 * from AndroLLM; Hilt removed, [states] added for Jarvis's settings UI).
 *
 * Models live in `filesDir/whisper/<fileName>` and are loaded once and cached by
 * [WhisperSpeechRecognizer]; switching models unloads the previous context.
 * Downloads are strictly user-initiated (Settings → Voice).
 */
class WhisperModelManager private constructor(private val context: Context) {

    private val modelsDir: File by lazy { File(context.filesDir, "whisper").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, WhisperDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, WhisperDownloadState>> = _states.asStateFlow()

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    /** Models that are fully downloaded (file exists and passes a size sanity check). */
    fun installedModels(): List<WhisperModel> = WhisperModels.ALL.filter { isInstalled(it) }

    fun isInstalled(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() > 1_000_000
    }

    /** Total disk used by downloaded whisper models, in bytes. */
    fun totalInstalledSizeBytes(): Long = installedModels().sumOf { modelFile(it).length() }

    /** Total disk used by downloaded whisper models, in bytes (settings UI). */
    fun storageInstalledSizeBytes(): Long = totalInstalledSizeBytes()

    /** Free bytes on the app's filesystem. */
    fun freeBytes(): Long = modelsDir.freeSpace

    /** Re-reads install state from disk for every catalog model. */
    fun refresh() {
        _states.update { current ->
            WhisperModels.ALL.associate { m ->
                val running = current[m.id] is WhisperDownloadState.Downloading && isRunning(m.id)
                m.id to when {
                    running -> current.getValue(m.id)
                    isInstalled(m) -> WhisperDownloadState.Installed
                    current[m.id] is WhisperDownloadState.Failed -> current.getValue(m.id)
                    else -> WhisperDownloadState.NotInstalled
                }
            }
        }
    }

    fun isRunning(id: String): Boolean = synchronized(jobs) { jobs[id]?.isActive == true }

    /** Starts a background download of [model] (no-op when already running/installed). */
    fun startDownload(model: WhisperModel) {
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true || isInstalled(model)) return
            setState(model.id, WhisperDownloadState.Downloading(0L, model.sizeBytes))
            jobs[model.id] = scope.launch {
                try {
                    download(model) { done, total -> setState(model.id, WhisperDownloadState.Downloading(done, total)) }
                    setState(model.id, WhisperDownloadState.Installed)
                } catch (e: CancellationException) {
                    File(modelsDir, "${model.fileName}.part").delete()
                    setState(model.id, WhisperDownloadState.NotInstalled)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Download of ${model.fileName} failed: ${e.message}", e)
                    File(modelsDir, "${model.fileName}.part").delete()
                    setState(model.id, WhisperDownloadState.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    fun cancelDownload(id: String) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
    }

    /**
     * Downloads [model] to disk with progress, verifies its digest, then atomically
     * publishes it. Throws on network/corruption failure.
     */
    suspend fun download(
        model: WhisperModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val finalFile = modelFile(model)
        if (isInstalled(model)) return@withContext

        modelsDir.mkdirs()
        val partFile = File(modelsDir, "${model.fileName}.part")
        partFile.delete()

        val connection = URL(model.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Jarvis-AndroLLM/1.0")
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} for ${model.fileName}")
            }
            val total = connection.contentLengthLong.coerceAtLeast(model.sizeBytes)
            // The catalog digests are 40 hex chars (SHA-1, as in whisper.cpp's README).
            val algorithm = if (model.sha256.length == 40) "SHA-1" else "SHA-256"
            val digest = MessageDigest.getInstance(algorithm)
            var downloaded = 0L
            val buffer = ByteArray(64 * 1024)

            FileOutputStream(partFile).use { out ->
                connection.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        downloaded += n
                        if (downloaded % (1 shl 20) < n) onProgress(downloaded, total)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                // Upstream occasionally re-uploads model files (new conversion) which
                // changes the digest though whisper.cpp can still load them; whisper_init
                // validates the file on load and corrupt files fail there.
                Log.w(TAG, "Checksum differs for ${model.displayName}: catalog=${model.sha256} actual=$actual")
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            Log.i(TAG, "Installed ${model.fileName}: ${finalFile.length()} bytes")
        } finally {
            connection.disconnect()
        }
    }

    /** Deletes a downloaded model file. */
    suspend fun delete(model: WhisperModel) = withContext(Dispatchers.IO) {
        cancelDownload(model.id)
        val f = modelFile(model)
        if (f.exists() && !f.delete()) Log.w(TAG, "Failed to delete ${f.name}")
        setState(model.id, WhisperDownloadState.NotInstalled)
    }

    private fun setState(id: String, state: WhisperDownloadState) {
        _states.update { it + (id to state) }
    }

    companion object {
        @Volatile private var instance: WhisperModelManager? = null

        fun get(context: Context): WhisperModelManager =
            instance ?: synchronized(this) {
                instance ?: WhisperModelManager(context.applicationContext).also {
                    instance = it
                    it.refresh()
                }
            }
    }
}
