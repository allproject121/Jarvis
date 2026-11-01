package com.jarvis.assistant.androllm.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Per-pack download state shown in Settings → Voice. */
sealed class VoiceDownloadState {
    object NotInstalled : VoiceDownloadState()
    data class Downloading(
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String
    ) : VoiceDownloadState() {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }
    object Installed : VoiceDownloadState()
    data class Failed(val message: String) : VoiceDownloadState()
}

/**
 * User-initiated downloader for [VoiceModelPack]s (never auto-downloads).
 *
 * - Streams each file to `<name>.part`, resuming with an HTTP Range request when a
 *   partial file exists, then verifies size (+ sha256 when published) before the
 *   atomic rename.
 * - A `.complete` marker is written only after every file of the pack verified.
 * - Progress is exposed as a [StateFlow] keyed by pack id.
 */
object VoiceModelDownloader {

    private const val TAG = "VoiceModelDownloader"
    private const val BUFFER = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _states = MutableStateFlow<Map<String, VoiceDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, VoiceDownloadState>> = _states.asStateFlow()

    /** Re-reads install state from disk for every pack (cheap: file sizes only). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        _states.update { current ->
            VoiceModelCatalog.all.associate { pack ->
                val existing = current[pack.id]
                pack.id to when {
                    existing is VoiceDownloadState.Downloading && isRunning(pack.id) -> existing
                    pack.isInstalled(app) -> VoiceDownloadState.Installed
                    existing is VoiceDownloadState.Failed -> existing
                    else -> VoiceDownloadState.NotInstalled
                }
            }
        }
    }

    fun isRunning(packId: String): Boolean = synchronized(jobs) { jobs[packId]?.isActive == true }

    /** Starts (or resumes) downloading [pack]. No-op when already running or installed. */
    fun download(context: Context, pack: VoiceModelPack) {
        val app = context.applicationContext
        synchronized(jobs) {
            if (jobs[pack.id]?.isActive == true) return
            if (pack.isInstalled(app)) {
                setState(pack.id, VoiceDownloadState.Installed)
                return
            }
            setState(pack.id, VoiceDownloadState.Downloading(0L, pack.totalBytes, ""))
            jobs[pack.id] = scope.launch {
                try {
                    runDownload(app, pack)
                    setState(pack.id, VoiceDownloadState.Installed)
                    Log.i(TAG, "Installed ${pack.id}")
                } catch (e: CancellationException) {
                    setState(pack.id, VoiceDownloadState.NotInstalled)
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Download of ${pack.id} failed", e)
                    setState(pack.id, VoiceDownloadState.Failed(e.message ?: e.javaClass.simpleName))
                } finally {
                    synchronized(jobs) { jobs.remove(pack.id) }
                }
            }
        }
    }

    fun cancel(packId: String) {
        synchronized(jobs) { jobs.remove(packId) }?.cancel()
        setState(packId, VoiceDownloadState.NotInstalled)
    }

    /** Deletes a pack from disk. The caller must make sure no engine is using it. */
    fun delete(context: Context, pack: VoiceModelPack) {
        cancel(pack.id)
        pack.dir(context.applicationContext).deleteRecursively()
        setState(pack.id, VoiceDownloadState.NotInstalled)
    }

    private fun setState(id: String, state: VoiceDownloadState) {
        _states.update { it + (id to state) }
    }

    private suspend fun runDownload(context: Context, pack: VoiceModelPack) {
        val dir = pack.dir(context).apply { mkdirs() }
        pack.completeMarker(context).delete()

        // Resolve the directory listing first so the progress total is exact.
        val dirFiles: List<Pair<String, Long>> = pack.remoteDir?.let { listRemoteDir(it) } ?: emptyList()
        val total = pack.files.sumOf { it.sizeBytes } + dirFiles.sumOf { it.second }
        var doneBefore = 0L

        for (rf in pack.files) {
            val target = File(dir, rf.localName)
            downloadFile(rf.url, target, rf.sizeBytes, rf.sha256) { fileDone ->
                setState(pack.id, VoiceDownloadState.Downloading(doneBefore + fileDone, total, rf.localName))
            }
            doneBefore += rf.sizeBytes
        }

        val remoteDir = pack.remoteDir
        if (remoteDir != null) {
            for ((path, size) in dirFiles) {
                val target = File(dir, path)
                val url = "https://huggingface.co/${remoteDir.repo}/resolve/${remoteDir.revision}/$path"
                downloadFile(url, target, size, null) { fileDone ->
                    setState(pack.id, VoiceDownloadState.Downloading(doneBefore + fileDone, total, path))
                }
                doneBefore += size
            }
        }

        for ((name, content) in pack.generatedFiles) {
            File(dir, name).writeText(content, Charsets.UTF_8)
        }
        pack.completeMarker(context).writeText(System.currentTimeMillis().toString())
    }

    /** Lists every file under [dir] at its pinned revision via the Hugging Face tree API. */
    private suspend fun listRemoteDir(dir: RemoteDir): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        var url: String? = "https://huggingface.co/api/models/${dir.repo}/tree/${dir.revision}/${dir.path}?recursive=true"
        while (url != null) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val request = Request.Builder().url(url).build()
            val next: String? = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Listing ${dir.path} failed: HTTP ${resp.code}")
                val arr = JSONArray(resp.body?.string() ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") != "file") continue
                    val path = o.optString("path")
                    if (!isSafeRelativePath(path, dir.path)) {
                        throw IOException("Refusing unsafe path from listing: $path")
                    }
                    out.add(path to o.optLong("size", -1L))
                }
                parseNextLink(resp.header("Link"))
            }
            url = next
        }
        if (out.isEmpty()) throw IOException("Listing ${dir.path} returned no files")
        return out
    }

    private fun isSafeRelativePath(path: String, prefix: String): Boolean =
        path.startsWith("$prefix/") && !path.contains("..") && !path.startsWith("/") && !path.contains('\\')

    private fun parseNextLink(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return header.split(',').map { it.trim() }
            .firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<')?.substringBefore('>')
            ?.takeIf { it.startsWith("https://huggingface.co/") }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSize: Long,
        sha256: String?,
        onProgress: (Long) -> Unit
    ) {
        if (expectedSize > 0 && target.length() == expectedSize && (sha256 == null || sha256Of(target) == sha256)) {
            onProgress(expectedSize)
            return
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        if (expectedSize > 0 && existing > expectedSize) {
            part.delete(); existing = 0L
        }

        val builder = Request.Builder().url(url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${target.name}")
            val append = existing > 0 && resp.code == 206
            if (!append) existing = 0L
            val body = resp.body ?: throw IOException("Empty body for ${target.name}")
            val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
            if (append && digest != null) {
                FileInputStream(part).use { input -> feedDigest(digest, input) }
            }
            var done = existing
            onProgress(done)
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress(done)
                        }
                    }
                }
            }
            onProgress(done)
            if (expectedSize > 0 && part.length() != expectedSize) {
                throw IOException("${target.name}: size ${part.length()} != expected $expectedSize")
            }
            if (digest != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(sha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException("${target.name}: sha256 mismatch")
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
    }

    private fun feedDigest(digest: MessageDigest, input: java.io.InputStream) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { feedDigest(digest, it) }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
