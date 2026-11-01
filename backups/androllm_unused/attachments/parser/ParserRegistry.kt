package com.jarvis.assistant.attachments.parser

import java.io.File

object ParserRegistry {

    private val parsers: List<DocumentParser> = listOf(
        TxtParser(),
        MarkdownParser(),
        CsvParser(),
        JsonParser(),
        HtmlParser(),
        DocxParser(),
        PptxParser(),
        XlsxParser(),
        EpubParser()
    )

    fun parse(file: File): ParsedDocument? {
        val parser = parsers.firstOrNull { it.supports(file) } ?: return null
        return runCatching { parser.parse(file) }.getOrNull()
    }

    fun isSupported(file: File): Boolean = parsers.any { it.supports(file) }
}
