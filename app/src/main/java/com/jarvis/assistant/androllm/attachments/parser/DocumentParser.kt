package com.jarvis.assistant.androllm.attachments.parser

import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import java.io.File

/**
 * The result of extracting text from a document (ported from AndroLLM). Keeps
 * the per-page split plus the heading outline when the format provides one.
 */
data class ParsedDocument(
    /** Full normalized text. */
    val text: String,
    /** Per-page text split (single page when the format has no pages). */
    val pages: List<String> = emptyList(),
    /** Heading outline: (level, heading text, page). */
    val headings: List<ParsedHeading> = emptyList(),
    val pageCount: Int = 0,
    /** True when OCR produced the text (images / scanned PDFs). */
    val fromOcr: Boolean = false
) {
    init {
        require(text.isNotEmpty()) { "Parsed document text must not be empty" }
    }
}

data class ParsedHeading(
    val level: Int,
    val text: String,
    val page: Int? = null
)

/** Extracts clean text from a single attachment format. */
interface DocumentParser {
    val format: AttachmentType

    /** True when this parser can handle the given file (by extension). */
    fun supports(file: File): Boolean

    /** Extracts text. Throws [Exception] with a user-readable message on failure. */
    fun parse(file: File, ocrLanguage: String = "en"): ParsedDocument
}
