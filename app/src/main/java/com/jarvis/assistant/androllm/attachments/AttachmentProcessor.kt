package com.jarvis.assistant.androllm.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.jarvis.assistant.androllm.attachments.model.AttachmentProcessingResult
import com.jarvis.assistant.androllm.attachments.model.AttachmentSettings
import com.jarvis.assistant.androllm.attachments.model.AttachmentStatus
import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import com.jarvis.assistant.androllm.attachments.model.ChatAttachment
import com.jarvis.assistant.androllm.attachments.parser.ParserRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Processes picked files into conversation-scoped [ChatAttachment]s (ported
 * from AndroLLM; Hilt singleton → process singleton via [get]).
 *
 * For each URI the processor:
 *  1. copies the content into the conversation cache (a stable private copy
 *     that survives SAF grant expiry), enforcing the max-size setting,
 *  2. dispatches to the matching parser from [ParserRegistry] (text formats,
 *     PDF, Office, EPUB) or ML Kit OCR (images),
 *  3. returns the attachment with extracted text ready for prompt assembly.
 */
class AttachmentProcessor private constructor(
    private val context: Context,
    private val registry: ParserRegistry,
    private val settingsStore: AttachmentSettingsStore
) {

    /**
     * Processes a batch of [uris] for [conversationId], invoking [onProgress]
     * before each file. Individual failures never abort the batch.
     */
    suspend fun processBatch(
        conversationId: String,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> }
    ): List<AttachmentProcessingResult> = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        val results = mutableListOf<AttachmentProcessingResult>()
        uris.forEachIndexed { index, uri ->
            val name = displayName(uri)
            onProgress(index + 1, uris.size, name)
            results += processOne(conversationId, uri, name, settings)
        }
        results
    }

    /** Processes a single URI. Never throws — failures become [AttachmentStatus.FAILED]. */
    suspend fun processOne(
        conversationId: String,
        uri: Uri,
        name: String = displayName(uri),
        settings: AttachmentSettings? = null
    ): AttachmentProcessingResult = withContext(Dispatchers.IO) {
        val effective = settings ?: settingsStore.current()
        val attachmentId = UUID.randomUUID().toString()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val base = AttachmentCache.cacheDir(context, conversationId)
        val withExt = ensureExtension(name, mime)
        val safeName = if (effective.preserveFilenames) {
            sanitizeName(withExt)
        } else {
            "attachment" + withExt.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        }
        val target = File(base, "$attachmentId-$safeName")
        try {
            persistReadPermission(uri)
            val declaredSize = querySize(uri)
            if (declaredSize > effective.maxAttachmentBytes) {
                throw IllegalArgumentException(
                    "File is too large (${ChatAttachment.formatSize(declaredSize)}; limit " +
                        "${ChatAttachment.formatSize(effective.maxAttachmentBytes)})"
                )
            }
            copyToFile(uri, target, effective.maxAttachmentBytes)
            if (target.length() <= 0L) throw IllegalArgumentException("File is empty")
            val parser = registry.find(target)
                ?: throw IllegalArgumentException(
                    "Unsupported file type${if (name.isNotBlank()) " \"$name\"" else ""} — " +
                        "attach PDF, Word, PowerPoint, Excel, text, markdown, CSV, JSON, HTML, EPUB or an image"
                )
            val parsed = parser.parse(target, effective.ocrLanguage)
            AttachmentProcessingResult(
                attachment = ChatAttachment(
                    id = attachmentId,
                    conversationId = conversationId,
                    name = safeName,
                    type = parser.format,
                    mimeType = mime,
                    sourceUri = uri.toString(),
                    filePath = target.absolutePath,
                    sizeBytes = target.length(),
                    text = parsed.text,
                    fromOcr = parsed.fromOcr,
                    pageCount = parsed.pageCount,
                    status = AttachmentStatus.READY,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Attachment processing failed: $name", e)
            val message = e.message ?: e.javaClass.simpleName
            AttachmentProcessingResult(
                attachment = ChatAttachment(
                    id = attachmentId,
                    conversationId = conversationId,
                    name = safeName,
                    type = AttachmentType.fromFileName(withExt),
                    mimeType = mime,
                    sourceUri = uri.toString(),
                    filePath = target.absolutePath,
                    sizeBytes = target.length().takeIf { it > 0 } ?: 0,
                    status = AttachmentStatus.FAILED,
                    error = message,
                    createdAt = System.currentTimeMillis()
                ),
                error = message
            )
        }
    }

    private fun copyToFile(uri: Uri, target: File, maxBytes: Long) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read the selected file")
        input.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalArgumentException(
                            "File is too large (limit ${ChatAttachment.formatSize(maxBytes)})"
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /** Keeps the SAF grant so the original can be reopened from history (best-effort). */
    private fun persistReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

    /**
     * Gallery / photo-picker URIs often have no extension in their display
     * name ("1000012345"). The parsers dispatch by extension, so derive one
     * from the MIME type when missing.
     */
    private fun ensureExtension(name: String, mime: String): String {
        val hasExt = name.substringAfterLast('.', "").let { it.isNotEmpty() && it.length <= 8 && it != name }
        if (hasExt) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: when (mime) {
                "text/markdown" -> "md"
                "application/epub+zip" -> "epub"
                else -> null
            }
        return if (ext.isNullOrBlank()) name else "$name.$ext"
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120).ifBlank { "attachment" }

    companion object {
        private const val TAG = "AttachmentProcessor"

        @Volatile
        private var instance: AttachmentProcessor? = null

        fun get(context: Context): AttachmentProcessor =
            instance ?: synchronized(this) {
                instance ?: AttachmentProcessor(
                    context.applicationContext,
                    ParserRegistry.get(context),
                    AttachmentSettingsStore.get(context)
                ).also { instance = it }
            }
    }
}
