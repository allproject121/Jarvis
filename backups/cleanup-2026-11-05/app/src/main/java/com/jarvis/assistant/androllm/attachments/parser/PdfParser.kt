package com.jarvis.assistant.androllm.attachments.parser

import android.content.Context
import android.graphics.Bitmap
import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF parser (ported from AndroLLM): extracts the text layer with
 * pdfbox-android. When the text layer is empty (scanned documents) pages are
 * rendered and OCR'd via [ocr].
 */
class PdfParser(
    context: Context,
    private val ocr: AttachmentOcrEngine? = null
) : DocumentParser {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override val format = AttachmentType.PDF
    override fun supports(file: File) = file.extension.equals("pdf", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val pageTexts = mutableListOf<String>()
        val pageCount = PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                val pageText = stripper.getText(doc).trim()
                if (pageText.isNotEmpty()) pageTexts += pageText
            }
            doc.numberOfPages
        }
        val joined = pageTexts.joinToString("\n\n")
        if (joined.isNotBlank()) {
            val normalized = TextParsers.normalize(joined)
            if (normalized.isNotEmpty()) {
                return ParsedDocument(
                    text = normalized,
                    pages = pageTexts.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
                    pageCount = pageCount
                )
            }
        }
        return ocrPdf(file, pageCount)
    }

    private fun ocrPdf(file: File, pageCount: Int): ParsedDocument {
        val engine = ocr ?: throw IllegalArgumentException("Scanned PDF requires OCR, which is unavailable")
        val pageTexts = mutableListOf<String>()
        // Bounded so a huge scanned book can't stall the chat for minutes.
        for (i in 0 until minOf(pageCount, MAX_OCR_PAGES)) {
            val pageText = engine.ocrPdfPage(file, i)
            if (!pageText.isNullOrBlank()) pageTexts += pageText
        }
        val normalized = TextParsers.normalize(pageTexts.joinToString("\n\n"))
        if (normalized.isEmpty()) throw IllegalArgumentException("PDF contains no readable text")
        return ParsedDocument(
            text = normalized,
            pages = pageTexts.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
            pageCount = pageCount,
            fromOcr = true
        )
    }

    /** Renders a page to a bitmap (used by the OCR fallback). */
    fun renderPage(file: File, pageIndex: Int, dpi: Float = 200f): Bitmap? = try {
        PDDocument.load(file).use { doc -> PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi) }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_OCR_PAGES = 50
    }
}
