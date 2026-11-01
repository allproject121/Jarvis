package com.jarvis.assistant.androllm.attachments.model

import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Attachment file types the chat can ingest (ported from AndroLLM).
 * AUDIO and VIDEO are reserved for future multimodal support.
 */
@Serializable
enum class AttachmentType(val label: String) {
    PDF("PDF"),
    DOCX("Word"),
    PPTX("PowerPoint"),
    XLSX("Excel"),
    EPUB("EPUB"),
    TXT("Text"),
    MARKDOWN("Markdown"),
    CSV("CSV"),
    JSON("JSON"),
    HTML("HTML"),
    IMAGE("Image"),
    AUDIO("Audio"),
    VIDEO("Video"),
    UNKNOWN("Unknown");

    val isText: Boolean get() = this in setOf(TXT, MARKDOWN, CSV, JSON, HTML)
    val isImage: Boolean get() = this == IMAGE

    companion object {
        fun fromFileName(name: String): AttachmentType {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                "pdf" -> PDF
                "docx" -> DOCX
                "pptx" -> PPTX
                "xlsx" -> XLSX
                "epub" -> EPUB
                "txt", "text", "log" -> TXT
                "md", "markdown" -> MARKDOWN
                "csv" -> CSV
                "json" -> JSON
                "html", "htm" -> HTML
                "png", "jpg", "jpeg", "heic", "webp", "bmp", "gif" -> IMAGE
                "mp3", "wav", "m4a", "ogg", "aac", "flac" -> AUDIO
                "mp4", "mkv", "webm", "mov", "avi", "3gp" -> VIDEO
                else -> UNKNOWN
            }
        }
    }
}

/** Lifecycle of one attached file within its conversation. */
@Serializable
enum class AttachmentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}

/**
 * A file attached to a single conversation (ported from AndroLLM). Scoped to
 * [conversationId]: nothing is indexed, embedded or shared across chats. The
 * extracted [text] is only ever sent to the model for THIS conversation.
 */
@Serializable
data class ChatAttachment(
    val id: String,
    val conversationId: String,
    val name: String,
    val type: AttachmentType,
    val mimeType: String = "",
    /** Original content URI ("" when copied from a raw path). */
    val sourceUri: String = "",
    /** Absolute path of the conversation-scoped private copy. */
    val filePath: String = "",
    val sizeBytes: Long = 0,
    /** Extracted text for text documents / OCR for images. */
    val text: String = "",
    /** True when [text] came from OCR (images / scanned PDFs). */
    val fromOcr: Boolean = false,
    /** Number of text pages/chunks (0 when not applicable). */
    val pageCount: Int = 0,
    val status: AttachmentStatus = AttachmentStatus.PENDING,
    val error: String? = null,
    val createdAt: Long = 0
) {
    val isReady: Boolean get() = status == AttachmentStatus.READY
    val isFailed: Boolean get() = status == AttachmentStatus.FAILED

    /** Human label: "ProjectProposal.pdf · 1.2 MB". */
    val label: String
        get() = buildString {
            append(name.ifBlank { "Attachment" })
            if (sizeBytes > 0) append(" · ").append(formatSize(sizeBytes))
        }

    companion object {
        /** Fixed: AndroLLM divided MB values by 1024³ (showed "0.0 MB"). */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/** Attachment behavior settings (ported from AndroLLM). */
data class AttachmentSettings(
    /** Image processing quality 0-100 (used when auto-compressing before OCR). */
    val imageQuality: Int = 85,
    /** OCR language hint (ML Kit's bundled recognizer is Latin-script). */
    val ocrLanguage: String = "en",
    /** Maximum single-file size in bytes; larger files fail gracefully. */
    val maxAttachmentBytes: Long = 20L * 1024 * 1024,
    /** Maximum attachments per message. */
    val maxAttachmentsPerMessage: Int = 10,
    /** Auto-compress images before processing. */
    val autoCompressImages: Boolean = true,
    /** Keep the original filename for copied attachments. */
    val preserveFilenames: Boolean = true,
    /** Cache processed text for the active conversation only. */
    val cacheProcessedAttachments: Boolean = true
) {
    companion object {
        const val IMAGE_QUALITY_MIN = 30
        const val IMAGE_QUALITY_MAX = 100
        const val MAX_ATTACHMENTS_MIN = 1
        const val MAX_ATTACHMENTS_MAX = 20
    }
}

/** Result of processing one attachment. */
data class AttachmentProcessingResult(
    val attachment: ChatAttachment,
    val error: String? = null
)

/** Aggregate progress for a multi-file attach operation. */
data class AttachmentBatchProgress(
    val current: Int = 0,
    val total: Int = 0,
    val currentName: String = ""
) {
    val fraction: Float get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}
