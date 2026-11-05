package com.jarvis.assistant.androllm.attachments.parser

import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import java.io.File
import java.util.regex.Pattern

/**
 * Parsers for plain-text family formats: TXT, Markdown, CSV, JSON and HTML
 * (ported from AndroLLM). Pure JVM.
 */
object TextParsers {

    val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
    val TAG_RE: Pattern = Pattern.compile("<[^>]+>")
    private val WHITESPACE_RE = Regex("[ \\t\\x0B\\f\\r]+")

    /** Trims each line, collapses intra-line whitespace and drops blank lines. */
    fun normalize(text: String): String =
        text.lines()
            .map { it.trim().replace(WHITESPACE_RE, " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    /** Single-page document; throws a readable error when there is no text. */
    fun singlePage(text: String, emptyMessage: String = "No readable text found in file"): ParsedDocument {
        if (text.isEmpty()) throw IllegalArgumentException(emptyMessage)
        return ParsedDocument(text = text, pages = listOf(text), pageCount = 1)
    }

    /**
     * Decodes HTML character references. Fixed vs AndroLLM: the original ran
     * the text through `URLDecoder`, which turned every literal `+` into a
     * space and threw on stray `%` signs. This decoder only touches entities.
     */
    fun decodeHtmlEntities(s: String): String {
        if (!s.contains('&')) return s
        val named = mapOf(
            "nbsp" to " ", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "amp" to "&", "ndash" to "–", "mdash" to "—", "hellip" to "…",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
            "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•",
            "middot" to "·", "laquo" to "«", "raquo" to "»", "euro" to "€"
        )
        return ENTITY_RE.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: m.value
                else -> named[body.lowercase()] ?: m.value
            }
        }
    }

    private val ENTITY_RE = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    private fun codePointToString(cp: Int): String? =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null
}

/** Plain text (.txt / .log / .text): read raw, normalize. */
class TxtParser : DocumentParser {
    override val format = AttachmentType.TXT
    override fun supports(file: File) = file.extension.lowercase() in setOf("txt", "log", "text")

    override fun parse(file: File, ocrLanguage: String): ParsedDocument =
        TextParsers.singlePage(TextParsers.normalize(file.readText()), "Text file is empty")
}

/** Markdown: strip inline markers but KEEP headings (level + text). */
class MarkdownParser : DocumentParser {
    override val format = AttachmentType.MARKDOWN
    override fun supports(file: File) = file.extension.equals("md", true) || file.extension.equals("markdown", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val headings = mutableListOf<ParsedHeading>()
        val cleaned = raw.lines().map { line ->
            val heading = TextParsers.HEADING_RE.find(line.trim())
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2].trim()
                headings += ParsedHeading(level, text, page = null)
                text
            } else {
                line.trim()
                    .replace(Regex("`+"), "")
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
                    .replace(Regex("(?<![*])\\*\\*(.+?)\\*\\*(?![*])"), "$1")
                    .replace(Regex("(?<![*])\\*(?!\\s)(.+?)(?<!\\s)\\*"), "$1")
                    .replace(Regex("__(.+?)__"), "$1")
                    .replace(Regex("(?<!_)_(?!\\s)(.+?)(?<!\\s)_"), "$1")
                    .replace(Regex("~~(.+?)~~"), "$1")
            }
        }.joinToString("\n")
        val text = TextParsers.normalize(cleaned)
        if (text.isEmpty()) throw IllegalArgumentException("Markdown file is empty")
        return ParsedDocument(text, listOf(text), headings, 1)
    }
}

/** CSV: reflow each record as a readable line with a quote-aware splitter. */
class CsvParser : DocumentParser {
    override val format = AttachmentType.CSV
    override fun supports(file: File) = file.extension.equals("csv", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val lines = file.readText().lines().filter { it.isNotBlank() }
        val reflowed = lines.map { line -> splitCsvLine(line).joinToString(" | ") }
        return TextParsers.singlePage(TextParsers.normalize(reflowed.joinToString("\n")), "CSV file is empty")
    }

    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    fields += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString().trim()
        return fields
    }
}

/** JSON: flatten structural characters so key/value pairs read naturally. */
class JsonParser : DocumentParser {
    override val format = AttachmentType.JSON
    override fun supports(file: File) = file.extension.equals("json", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val text = TextParsers.normalize(
            raw.replace(Regex("[{}\\[\\],]"), "\n")
                .replace(Regex("\"(.*?)\"\\s*:"), "$1: ")
                .replace("\"", "")
        )
        return TextParsers.singlePage(text, "JSON file is empty")
    }
}

/**
 * HTML: strip tags, decode entities, preserve the heading outline.
 *
 * Fixed vs AndroLLM:
 *  - closing heading tags were replaced with `</h\1>` in a pattern that has no
 *    capture group (back-reference to a non-existent group → PatternSyntaxException
 *    on Android); now `</h[1-6]>`.
 *  - entities are decoded with [TextParsers.decodeHtmlEntities] instead of
 *    `URLDecoder` (which turned `+` into spaces).
 */
class HtmlParser : DocumentParser {
    override val format = AttachmentType.HTML
    override fun supports(file: File) =
        file.extension.equals("html", true) || file.extension.equals("htm", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val raw = file.readText()
        val headings = mutableListOf<ParsedHeading>()

        var body = raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
        for (m in Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").findAll(body)) {
            val level = m.groupValues[1].toInt()
            val text = TextParsers.decodeHtmlEntities(stripTags(m.groupValues[2])).trim()
            if (text.isNotEmpty()) headings += ParsedHeading(level, text, page = null)
        }
        body = body.replace(Regex("(?i)<h[1-6][^>]*>"), "\n\n")
            .replace(Regex("(?i)</h[1-6]\\s*>"), "\n\n")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|section|article|blockquote)\\s*>"), "\n")
        val text = TextParsers.normalize(TextParsers.decodeHtmlEntities(stripTags(body)))
        if (text.isEmpty()) throw IllegalArgumentException("HTML file contains no readable text")
        return ParsedDocument(text, listOf(text), headings, 1)
    }

    private fun stripTags(s: String): String = TextParsers.TAG_RE.matcher(s).replaceAll(" ")
}
