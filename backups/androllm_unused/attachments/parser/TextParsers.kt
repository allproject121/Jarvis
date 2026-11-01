package com.jarvis.assistant.attachments.parser

import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.regex.Pattern

object TextParsers {

    val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
    val TAG_RE = Pattern.compile("<[^>]+>")
    private val WHITESPACE_RE = Regex("[ \\t\\x0B\\f\\r]+")

    fun normalize(text: String): String =
        text.lines()
            .map { it.trim().replace(WHITESPACE_RE, " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    fun singlePage(text: String): ParsedDocument = ParsedDocument(
        text = text,
        pages = listOf(text),
        pageCount = 1
    )
}

class TxtParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("txt", true) || file.extension.equals("log", true)
    override fun parse(file: File): ParsedDocument = TextParsers.singlePage(TextParsers.normalize(file.readText()))
}

class MarkdownParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("md", true) || file.extension.equals("markdown", true)

    override fun parse(file: File): ParsedDocument {
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
            }
        }.joinToString("\n")
        val text = TextParsers.normalize(cleaned)
        return ParsedDocument(text, listOf(text), headings, 1)
    }
}

class CsvParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("csv", true)

    override fun parse(file: File): ParsedDocument {
        val lines = file.readText().lines().filter { it.isNotBlank() }
        val reflowed = lines.map { line -> splitCsvLine(line).joinToString(" | ") }
        val text = TextParsers.normalize(reflowed.joinToString("\n"))
        return TextParsers.singlePage(text)
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

class JsonParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("json", true)

    override fun parse(file: File): ParsedDocument {
        val raw = file.readText()
        val text = TextParsers.normalize(
            raw.replace(Regex("[{}\\[\\],]"), "\n")
                .replace(Regex("\"(.*?)\"\\s*:"), "$1: ")
                .replace("\"", "")
        )
        return TextParsers.singlePage(text)
    }
}

class HtmlParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("html", true) || file.extension.equals("htm", true)

    override fun parse(file: File): ParsedDocument {
        val raw = file.readText()
        val headings = mutableListOf<ParsedHeading>()
        var body = raw.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        val headingMatches = Regex("(?is)<h([1-6])[^>]*>(.*?)</h\\1>").findAll(body)
        for (m in headingMatches) {
            val level = m.groupValues[1].toInt()
            val text = decodeEntities(stripTags(m.groupValues[2])).trim()
            if (text.isNotEmpty()) headings += ParsedHeading(level, text, page = null)
        }
        body = body.replace(Regex("(?is)<h([1-6])[^>]*>"), "\n\n")
            .replace(Regex("(?is)</h\\1>"), "\n\n")
        val text = TextParsers.normalize(decodeEntities(stripTags(body)))
        return ParsedDocument(text, listOf(text), headings, 1)
    }

    private fun stripTags(s: String): String = TextParsers.TAG_RE.matcher(s).replaceAll(" ")

    private fun decodeEntities(s: String): String = try {
        URLDecoder.decode(s.replace("&amp;", "&"), Charset.defaultCharset().name())
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    } catch (_: Exception) {
        s
    }
}
