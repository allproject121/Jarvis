package com.jarvis.assistant.androllm.attachments.parser

import android.content.Context
import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import java.io.File

/**
 * Registry of all document parsers (ported from AndroLLM; Hilt singleton →
 * process singleton via [get]). Adding a format is a one-line registration.
 */
class ParserRegistry private constructor(context: Context) {

    private val imageOcr: MlKitOcrEngine = MlKitOcrEngine(context)

    private val parsers: List<DocumentParser> = buildList {
        add(TxtParser())
        add(MarkdownParser())
        add(CsvParser())
        add(JsonParser())
        add(HtmlParser())
        add(DocxParser())
        add(PptxParser())
        add(XlsxParser())
        add(EpubParser())
        add(PdfParser(context, imageOcr))
        add(ImageParser(imageOcr))
    }

    /** Finds a parser for [file], or null when the format is unsupported. */
    fun find(file: File): DocumentParser? = parsers.firstOrNull { it.supports(file) }

    /** True when any registered parser can handle [file]. */
    fun supports(file: File): Boolean = find(file) != null

    /** The type the registry would assign to [name]. */
    fun typeFor(name: String): AttachmentType = AttachmentType.fromFileName(name)

    companion object {
        @Volatile
        private var instance: ParserRegistry? = null

        fun get(context: Context): ParserRegistry =
            instance ?: synchronized(this) {
                instance ?: ParserRegistry(context.applicationContext).also { instance = it }
            }
    }
}
