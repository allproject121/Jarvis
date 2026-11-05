package com.jarvis.assistant.androllm.tools.impl

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.core.ToolPermission
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.core.ToolSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** App-specific exports dir (external files when mounted, internal otherwise). */
internal fun exportsDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }

/**
 * Generates a paginated PDF from plain text using Android's built-in
 * [PdfDocument] — no third-party library, works fully offline. The file is
 * written to the app's exports dir and its path returned (share_text /
 * the share sheet can offer it afterwards).
 */
class PdfExportTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "export_pdf",
        description = "Save text content as a PDF file and return its path.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") { put("type", "string"); put("description", "The text to put in the PDF") }
                putJsonObject("title") { put("type", "string"); put("description", "Optional document title") }
            }
            putJsonArray("required") { add("content") }
        },
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val content = ToolArgs.str(arguments, "content", "text", "markdown")
            ?: return ToolResult.Failure("Missing required argument: content")
        val title = ToolArgs.str(arguments, "title", "filename") ?: "document"
        val file = exportFile(title)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                writePdf(file, title, content)
                file.exists() && file.length() > 0
            }.getOrDefault(false)
        }
        if (!ok) return ToolResult.Failure("Could not write the PDF file.")
        return ToolResult.Success(
            "PDF saved (${file.length() / 1024} KB): ${file.absolutePath}",
            buildJsonObject { put("path", file.absolutePath); put("bytes", file.length()) }
        )
    }

    private fun exportFile(title: String): File {
        val safe = title.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "document" }
        return File(exportsDir(context), "${safe}_${System.currentTimeMillis()}.pdf")
    }

    private fun writePdf(file: File, title: String, content: String) {
        val pageWidth = 595
        val pageHeight = 842 // A4 @ 72dpi
        val margin = 48
        val lineHeight = 16
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
        }
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
        }
        val doc = PdfDocument()
        try {
            val lines = content.lines().flatMap { wrap(it, pageWidth - margin * 2, paint) }
            var page: PdfDocument.Page? = null
            var pageCount = 0
            var y = 0
            fun ensurePage(): PdfDocument.Page {
                val current = page
                if (current == null || y > pageHeight - margin - lineHeight) {
                    current?.let { doc.finishPage(it) }
                    pageCount++
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageCount).create()
                    val newPage = doc.startPage(info)
                    newPage.canvas.drawText(title, margin.toFloat(), 72f, titlePaint)
                    y = 96
                    page = newPage
                    return newPage
                }
                return current
            }
            lines.forEach { line ->
                val p = ensurePage()
                p.canvas.drawText(line, margin.toFloat(), y.toFloat(), paint)
                y += lineHeight
            }
            if (page == null) ensurePage() // empty content still yields one titled page
            page?.let { doc.finishPage(it) }
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun wrap(text: String, maxWidth: Int, paint: Paint): List<String> {
        if (text.isBlank()) return listOf(" ")
        val words = text.split(Regex("(?<=\\s)|(?=\\s)"))
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current$word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current.append(word)
            } else {
                lines += current.toString()
                current.setLength(0)
                current.append(word.trimStart())
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}

/**
 * Saves content as a Markdown (.md) file and returns its path — the raw
 * material behind "export this as Markdown".
 */
class MarkdownExportTool(
    private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "export_markdown",
        description = "Save text content as a Markdown (.md) file and return its path.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") { put("type", "string") }
                putJsonObject("filename") { put("type", "string") }
            }
            putJsonArray("required") { add("content") }
        },
        permission = ToolPermission.FILES,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val content = ToolArgs.str(arguments, "content", "text", "markdown")
            ?: return ToolResult.Failure("Missing required argument: content")
        val filename = ToolArgs.str(arguments, "filename", "title") ?: "document"
        val safe = filename.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "document" }
        val file = File(exportsDir(context), "${safe}_${System.currentTimeMillis()}.md")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(content)
                file.exists() && file.length() > 0
            }.getOrDefault(false)
        }
        if (!ok) return ToolResult.Failure("Could not write the Markdown file.")
        return ToolResult.Success(
            "Markdown saved (${file.length() / 1024} KB): ${file.absolutePath}",
            buildJsonObject { put("path", file.absolutePath); put("bytes", file.length()) }
        )
    }
}
