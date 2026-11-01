package com.jarvis.assistant.attachments.parser

import java.io.File

data class ParsedHeading(
    val level: Int,
    val text: String,
    val page: Int? = null
)

data class ParsedDocument(
    val text: String,
    val pages: List<String> = listOf(text),
    val headings: List<ParsedHeading> = emptyList(),
    val pageCount: Int = 1
)

interface DocumentParser {
    fun supports(file: File): Boolean
    fun parse(file: File): ParsedDocument
}
