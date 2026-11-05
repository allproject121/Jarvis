package com.jarvis.assistant.androllm.chat.export

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.jarvis.assistant.androllm.attachments.ChatAttachmentJson
import com.jarvis.assistant.androllm.chat.model.Message
import com.jarvis.assistant.androllm.chat.model.MessageRole
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Export format options for conversation history (ported from AndroLLM). */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    MARKDOWN("Markdown", "md", "text/markdown"),
    PLAIN_TEXT("Plain text", "txt", "text/plain"),
    JSON("JSON", "json", "application/json"),
    HTML("HTML", "html", "text/html"),
    PDF("PDF", "pdf", "application/pdf");

    /** PDF is binary — it can be saved as a file but not shared as plain text. */
    val isText: Boolean get() = this != PDF
}

@Serializable
private data class ExportData(
    val title: String,
    val exportedAt: String,
    val messageCount: Int,
    val messages: List<ExportMessage>
)

@Serializable
private data class ExportMessage(
    val role: String,
    val content: String,
    val timestamp: String,
    val timestampMillis: Long,
    val model: String? = null,
    val bookmarked: Boolean = false,
    val attachments: List<String> = emptyList()
)

/**
 * Serialises a conversation (ported from AndroLLM feature/chat export).
 *
 * Fixed vs AndroLLM / the earlier Jarvis copy:
 *  - HTML escapes `&`, `<`, `>`, `"` and `'` in BOTH the title and every
 *    message (AndroLLM left the title raw and never escaped `&`), and keeps
 *    line breaks with `white-space: pre-wrap`.
 *  - PDF is a real paginated document (AndroLLM returned a placeholder string).
 *  - JSON is produced by kotlinx.serialization, so all strings are escaped.
 */
object ConversationExporter {

    private val json = Json { prettyPrint = true }

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun speaker(role: MessageRole): String = when (role) {
        MessageRole.USER -> "You"
        MessageRole.ASSISTANT -> "Jarvis"
        MessageRole.SYSTEM -> "System"
    }

    private fun attachmentNames(message: Message): List<String> =
        ChatAttachmentJson.decodeFromString(message.attachmentsJson).map { it.name }

    /** Text formats only; use [exportPdf] for [ExportFormat.PDF]. */
    fun export(title: String, messages: List<Message>, format: ExportFormat): String = when (format) {
        ExportFormat.MARKDOWN -> exportToMarkdown(title, messages)
        ExportFormat.PLAIN_TEXT -> exportToPlainText(title, messages)
        ExportFormat.JSON -> exportToJson(title, messages)
        ExportFormat.HTML -> exportToHtml(title, messages)
        ExportFormat.PDF -> exportToPlainText(title, messages)
    }

    /** Raw bytes of the export in [format] (UTF-8 for text formats). */
    fun exportBytes(title: String, messages: List<Message>, format: ExportFormat): ByteArray =
        if (format == ExportFormat.PDF) exportPdf(title, messages)
        else export(title, messages, format).toByteArray(Charsets.UTF_8)

    /** A filesystem-safe default file name for the export. */
    fun suggestedFileName(title: String, format: ExportFormat): String {
        val base = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "_")
            .take(60).ifBlank { "conversation" }
        return "$base.${format.extension}"
    }

    private fun singleLine(s: String) = s.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun exportToMarkdown(title: String, messages: List<Message>): String = buildString {
        val df = dateFormat()
        append("# ").append(singleLine(title)).append("\n\n")
        append("*Exported on ").append(df.format(Date())).append("*\n\n")
        append("---\n\n")
        for (msg in messages) {
            append("### ").append(speaker(msg.role))
            append(" · ").append(df.format(Date(msg.timestamp)))
            if (msg.isBookmarked) append(" · ★")
            append("\n\n")
            val files = attachmentNames(msg)
            if (files.isNotEmpty()) {
                append("> Attachments: ").append(files.joinToString(", ") { "`${it.replace("`", "")}`" }).append("\n\n")
            }
            append(msg.content.trim()).append("\n\n")
            append("---\n\n")
        }
    }

    private fun exportToPlainText(title: String, messages: List<Message>): String = buildString {
        val df = dateFormat()
        append(singleLine(title).uppercase(Locale.getDefault())).append("\n")
        append("Exported: ").append(df.format(Date())).append("\n")
        append("========================================\n\n")
        for (msg in messages) {
            append("[").append(df.format(Date(msg.timestamp))).append("] ")
            append(speaker(msg.role).uppercase(Locale.getDefault())).append(":\n")
            val files = attachmentNames(msg)
            if (files.isNotEmpty()) append("(Attachments: ").append(files.joinToString(", ")).append(")\n")
            append(msg.content.trim()).append("\n\n")
            append("----------------------------------------\n\n")
        }
    }

    private fun exportToJson(title: String, messages: List<Message>): String {
        val df = dateFormat()
        val data = ExportData(
            title = title,
            exportedAt = df.format(Date()),
            messageCount = messages.size,
            messages = messages.map { msg ->
                ExportMessage(
                    role = msg.role.name.lowercase(Locale.ROOT),
                    content = msg.content,
                    timestamp = df.format(Date(msg.timestamp)),
                    timestampMillis = msg.timestamp,
                    model = msg.modelId,
                    bookmarked = msg.isBookmarked,
                    attachments = attachmentNames(msg)
                )
            }
        )
        return json.encodeToString(data)
    }

    /** Escapes text for safe inclusion in HTML element content and attribute values. */
    fun escapeHtml(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun exportToHtml(title: String, messages: List<Message>): String = buildString {
        val df = dateFormat()
        val safeTitle = escapeHtml(singleLine(title))
        append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        append("<meta charset=\"UTF-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(safeTitle).append("</title>\n")
        append(
            "<style>body{font-family:sans-serif;margin:2rem;background:#121212;color:#e0e0e0;}" +
                ".msg{margin-bottom:1.5rem;padding:1rem;border-radius:8px;background:#1e1e1e;}" +
                ".user{border-left:4px solid #d9a441;}.assistant{border-left:4px solid #03dac6;}" +
                ".meta{font-size:.8rem;color:#9e9e9e;margin-bottom:.5rem;}" +
                ".content{white-space:pre-wrap;word-wrap:break-word;line-height:1.5;}" +
                ".files{font-size:.8rem;color:#bdbdbd;margin-bottom:.5rem;}</style>\n"
        )
        append("</head>\n<body>\n")
        append("<h1>").append(safeTitle).append("</h1>\n")
        append("<p class=\"meta\">Exported ").append(escapeHtml(df.format(Date()))).append("</p>\n")
        for (msg in messages) {
            val cls = if (msg.role == MessageRole.USER) "user" else "assistant"
            append("<div class=\"msg ").append(cls).append("\">\n")
            append("<div class=\"meta\"><strong>").append(escapeHtml(speaker(msg.role))).append("</strong> · ")
                .append(escapeHtml(df.format(Date(msg.timestamp))))
            if (msg.isBookmarked) append(" · &#9733;")
            append("</div>\n")
            val files = attachmentNames(msg)
            if (files.isNotEmpty()) {
                append("<div class=\"files\">Attachments: ")
                    .append(files.joinToString(", ") { escapeHtml(it) })
                    .append("</div>\n")
            }
            append("<div class=\"content\">").append(escapeHtml(msg.content.trim())).append("</div>\n")
            append("</div>\n")
        }
        append("</body>\n</html>\n")
    }

    // ── PDF ────────────────────────────────────────────────────────────────

    private const val PAGE_WIDTH = 595 // A4 @ 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40

    /**
     * Renders the conversation into a paginated A4 PDF using the platform
     * [PdfDocument] + [StaticLayout] (long messages flow across pages line by line).
     */
    fun exportPdf(title: String, messages: List<Message>): ByteArray {
        val df = dateFormat()
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 90, 90)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
        }

        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN.toFloat()
        }

        /** Draws [text] line by line, breaking onto new pages as needed. */
        fun drawBlock(text: String, paint: TextPaint, spacingAfter: Float) {
            if (text.isEmpty()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
            for (line in 0 until layout.lineCount) {
                val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (page == null || y + lineHeight > PAGE_HEIGHT - MARGIN) newPage()
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), y - layout.getLineTop(line))
                canvas.clipRect(0f, layout.getLineTop(line).toFloat(), contentWidth.toFloat(), layout.getLineBottom(line).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += lineHeight
            }
            y += spacingAfter
        }

        try {
            newPage()
            drawBlock(singleLine(title).ifBlank { "Conversation" }, titlePaint, 4f)
            drawBlock("Exported ${df.format(Date())} · ${messages.size} messages", headerPaint, 14f)
            for (msg in messages) {
                val header = buildString {
                    append(speaker(msg.role).uppercase(Locale.getDefault()))
                    append("  ·  ").append(df.format(Date(msg.timestamp)))
                    if (msg.isBookmarked) append("  ·  bookmarked")
                }
                drawBlock(header, headerPaint, 3f)
                val files = attachmentNames(msg)
                if (files.isNotEmpty()) drawBlock("Attachments: ${files.joinToString(", ")}", headerPaint, 3f)
                drawBlock(msg.content.trim().ifEmpty { " " }, bodyPaint, 14f)
            }
            page?.let { document.finishPage(it) }
            page = null
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }
}
