package com.jarvis.assistant.androllm.models.download

import com.jarvis.assistant.androllm.engine.support.formatBytes
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jarvis.assistant.ai.local.LocalModelDescriptor
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.androllm.models.ModelArtifactFormat
import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.catalog.ModelInspector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Result of importing a model file picked with the Storage Access Framework. */
data class ImportedModel(
    val format: ModelArtifactFormat,
    val file: File,
    /** Jarvis GGUF model id (registered with [LocalModelManager]); null for LiteRT artifacts. */
    val ggufModelId: String?,
    val displayName: String,
    val inspection: ModelInspector.Inspection?
)

/**
 * Imports a model file from shared storage (SAF `content://` Uri). The container type is
 * detected from the file bytes — never trusted from the name:
 *
 * - GGUF → copied into Jarvis' llama.cpp models dir and registered as a custom model with
 *   [LocalModelManager.registerCustomModel] (metadata read by [ModelInspector] / GgufReader).
 * - `.litertlm` / `.tflite` / `.task` → copied into the LiteRT models dir.
 */
object ModelImporter {

    /** A process-wide scope so an import keeps running if the screen is left. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): ModelResult<ImportedModel> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported-model"
        var totalSize = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) c.getString(nameIdx)?.let { displayName = it }
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) totalSize = c.getLong(sizeIdx)
                }
            }
        }

        val header = ByteArray(12)
        val headerRead = try {
            resolver.openInputStream(uri)?.use { input ->
                var off = 0
                while (off < header.size) {
                    val r = input.read(header, off, header.size - off)
                    if (r < 0) break
                    off += r
                }
                off
            } ?: return@withContext ModelResult.error<ImportedModel>("Cannot open the selected file")
        } catch (e: Exception) {
            return@withContext ModelResult.error<ImportedModel>("Cannot read the selected file: ${e.message}")
        }
        if (headerRead < 12) return@withContext ModelResult.error<ImportedModel>("The selected file is too small to be a model")

        val format = detectFormat(header)
            ?: return@withContext ModelResult.error<ImportedModel>(
                "Unsupported file: \"$displayName\" is not a GGUF, LiteRT-LM (.litertlm), TFLite or MediaPipe .task model."
            )

        when (format) {
            ModelArtifactFormat.GGUF -> importGguf(context, uri, displayName, totalSize, onProgress)
            else -> importLiteRt(context, uri, displayName, format, totalSize, onProgress)
        }
    }

    /** Container detection from the first 12 bytes. */
    fun detectFormat(header: ByteArray): ModelArtifactFormat? {
        if (header.size < 12) return null
        val ascii = { from: Int, to: Int -> String(header.copyOfRange(from, to), Charsets.US_ASCII) }
        return when {
            ascii(0, 8) == "LITERTLM" -> ModelArtifactFormat.LITERTLM
            ascii(4, 8) == "TFL3" -> ModelArtifactFormat.TFLITE
            ascii(0, 4) == "GGUF" -> ModelArtifactFormat.GGUF
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte() -> ModelArtifactFormat.TASK
            else -> null
        }
    }

    private suspend fun importGguf(
        context: Context,
        uri: Uri,
        displayName: String,
        totalSize: Long,
        onProgress: (Long, Long) -> Unit
    ): ModelResult<ImportedModel> {
        val manager = LocalModelManager.getInstance(context, scope)
        val stem = displayName.substringBeforeLast('.', displayName)
        val id = LocalModelManager.sanitizeModelId("import-$stem")
        val target = manager.getModelFile(id)
        val tmp = manager.getPartialFile(id)
        val copy = copyUri(context, uri, tmp, totalSize, onProgress)
        if (copy is ModelResult.Error) {
            tmp.delete()
            return copy
        }
        if (!LocalModelManager.isGgufHeaderValid(tmp)) {
            tmp.delete()
            return ModelResult.error("Copied file failed GGUF header validation")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ModelResult.error("Could not move the imported model into place")
        }
        val inspection = ModelInspector().inspect(target).getOrNull()
        val sizeGb = target.length() / (1024.0 * 1024.0 * 1024.0)
        val descriptor = LocalModelDescriptor(
            id = id,
            name = stem,
            provider = inspection?.family?.let { "$it · imported" } ?: "Imported",
            parameters = inspection?.parameters ?: "",
            quantization = "GGUF ${inspection?.quantization ?: ""}".trim(),
            downloadSizeBytes = target.length(),
            downloadUrl = "",
            // llama.cpp maps the whole file; plus ~1 GB for KV cache and runtime.
            recommendedMinRamGb = ceil(sizeGb + 1.0).toInt().coerceAtLeast(2),
            description = buildString {
                append("Imported from storage (")
                append(displayName)
                append(").")
                inspection?.let {
                    append(" Architecture ${it.architecture}, context ${it.contextLength} tokens, ${it.tensorCount} tensors.")
                }
            }
        )
        manager.registerCustomModel(descriptor)
        return ModelResult.success(ImportedModel(ModelArtifactFormat.GGUF, target, id, stem, inspection))
    }

    private suspend fun importLiteRt(
        context: Context,
        uri: Uri,
        displayName: String,
        format: ModelArtifactFormat,
        totalSize: Long,
        onProgress: (Long, Long) -> Unit
    ): ModelResult<ImportedModel> {
        val downloads = LiteRtDownloadManager.getInstance(context)
        val name = if (displayName.lowercase().endsWith(format.extension)) displayName
        else displayName.substringBeforeLast('.', displayName) + format.extension
        val target = downloads.targetFile(name)
        val tmp = downloads.partFile(name)
        val copy = copyUri(context, uri, tmp, totalSize, onProgress)
        if (copy is ModelResult.Error) {
            tmp.delete()
            return copy
        }
        val header = ArtifactValidator.validateHeader(tmp.absolutePath)
        if (!header.isValid || header.format == "gguf") {
            tmp.delete()
            return ModelResult.error(header.errorMessage.ifBlank { "Not a LiteRT artifact" })
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ModelResult.error("Could not move the imported model into place")
        }
        downloads.notifyInstalledChanged()
        val inspection = ModelInspector().inspectLiteRt(target).getOrNull()
        return ModelResult.success(ImportedModel(format, target, null, target.nameWithoutExtension, inspection))
    }

    private suspend fun copyUri(
        context: Context,
        uri: Uri,
        dest: File,
        totalSize: Long,
        onProgress: (Long, Long) -> Unit
    ): ModelResult<Unit> {
        dest.parentFile?.mkdirs()
        if (totalSize > 0) {
            val usable = dest.parentFile?.usableSpace ?: 0L
            if (usable in 1 until totalSize) {
                return ModelResult.error("Not enough free storage: need ${totalSize.formatBytes()}, have ${usable.formatBytes()}")
            }
        }
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return ModelResult.error("Cannot open the selected file")
            input.use { stream ->
                FileOutputStream(dest, false).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= 8L * 1024 * 1024) {
                            lastReport = copied
                            onProgress(copied, totalSize)
                        }
                    }
                    out.flush()
                    onProgress(copied, totalSize)
                }
            }
            ModelResult.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ModelResult.error("Import failed: ${e.message}")
        }
    }
}
