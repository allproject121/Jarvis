package com.jarvis.assistant.chat.export

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat {
    MARKDOWN,
    PLAIN_TEXT,
    JSON,
    HTML
}

data class ChatMessageExport(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ConversationExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun export(
        title: String,
        messages: List<ChatMessageExport>,
        format: ExportFormat
    ): String {
        return when (format) {
            ExportFormat.MARKDOWN -> exportToMarkdown(title, messages)
            ExportFormat.PLAIN_TEXT -> exportToPlainText(title, messages)
            ExportFormat.JSON -> exportToJson(title, messages)
            ExportFormat.HTML -> exportToHtml(title, messages)
        }
    }

    private fun exportToMarkdown(title: String, messages: List<ChatMessageExport>): String = buildString {
        append("# ").append(title).append("\n\n")
        append("*Exported on ").append(dateFormat.format(Date())).append(" via Jarvis*\n\n")
        append("---\n\n")

        for (msg in messages) {
            val roleName = if (msg.role.equals("user", true)) "**User**" else "**Jarvis**"
            val time = dateFormat.format(Date(msg.timestamp))
            append("### ").append(roleName).append(" <small>(").append(time).append(")</small>\n\n")
            append(msg.content.trim()).append("\n\n")
            append("---\n\n")
        }
    }

    private fun exportToPlainText(title: String, messages: List<ChatMessageExport>): String = buildString {
        append(title.uppercase(Locale.getDefault())).append("\n")
        append("Exported: ").append(dateFormat.format(Date())).append("\n")
        append("========================================\n\n")

        for (msg in messages) {
            val roleName = msg.role.uppercase(Locale.getDefault())
            val time = dateFormat.format(Date(msg.timestamp))
            append("[").append(time).append("] ").append(roleName).append(":\n")
            append(msg.content.trim()).append("\n\n")
            append("----------------------------------------\n\n")
        }
    }

    private fun exportToJson(title: String, messages: List<ChatMessageExport>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"title\": \"").append(escapeJson(title)).append("\",\n")
        sb.append("  \"exportedAt\": \"").append(dateFormat.format(Date())).append("\",\n")
        sb.append("  \"messageCount\": ").append(messages.size).append(",\n")
        sb.append("  \"messages\": [\n")
        messages.forEachIndexed { i, m ->
            sb.append("    {\n")
            sb.append("      \"role\": \"").append(escapeJson(m.role)).append("\",\n")
            sb.append("      \"content\": \"").append(escapeJson(m.content)).append("\",\n")
            sb.append("      \"timestamp\": \"").append(dateFormat.format(Date(m.timestamp))).append("\"\n")
            sb.append(if (i == messages.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun exportToHtml(title: String, messages: List<ChatMessageExport>): String = buildString {
        append("<!DOCTYPE html>\n<html>\n<head>\n")
        append("<meta charset=\"UTF-8\">\n")
        append("<title>").append(title).append("</title>\n")
        append("<style>body{font-family:-apple-system,sans-serif;margin:2rem;background:#090d16;color:#e0e0e0;}.msg{margin-bottom:1.5rem;padding:1rem 1.5rem;border-radius:12px;background:#121a29;}.user{border-left:4px solid #00E5FF;}.jarvis{border-left:4px solid #7C4DFF;}h1{color:#00E5FF;}</style>\n")
        append("</head>\n<body>\n")
        append("<h1>").append(title).append("</h1>\n")
        for (msg in messages) {
            val cls = if (msg.role.equals("user", true)) "user" else "jarvis"
            val label = if (msg.role.equals("user", true)) "User" else "Jarvis"
            append("<div class=\"msg ").append(cls).append("\">\n")
            append("<strong>").append(label).append("</strong>\n")
            append("<p>").append(msg.content.replace("<", "&lt;").replace(">", "&gt;")).append("</p>\n")
            append("</div>\n")
        }
        append("</body>\n</html>")
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

object ConversationSharer {
    fun share(context: Context, text: String, title: String = "Exported Conversation") {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(shareIntent)
    }
}
