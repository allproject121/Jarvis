package com.jarvis.assistant.androllm.attachments.parser

import com.jarvis.assistant.androllm.attachments.model.AttachmentType
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Lightweight parsers for the OOXML/EPUB zip containers (ported from AndroLLM).
 * DOCX/PPTX/XLSX and EPUB are ZIP archives of XML; the JDK ZipFile + DOM parser
 * extract the text without Apache POI.
 */
object ZipXml {

    /** Text of every <tag> descendant of [root], joined by [separator]. */
    fun textOf(root: Element, tag: String, separator: String = " "): String {
        val out = StringBuilder()
        val nodes = root.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            if (i > 0) out.append(separator)
            out.append(nodes.item(i).textContent)
        }
        return out.toString().trim()
    }

    /** Returns the first entry whose name equals [name] or ends with [suffix]. */
    fun readEntry(zip: ZipFile, name: String? = null, suffix: String? = null): ByteArray? {
        val entry = zip.entries().asSequence().firstOrNull {
            (name != null && it.name.equals(name, ignoreCase = true)) ||
                (suffix != null && it.name.endsWith(suffix, ignoreCase = true))
        } ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    /**
     * Parses XML bytes into a DOM root.
     *
     * Fixed vs AndroLLM: Android's bundled parser (KXml/Harmony) does not
     * recognise the Xerces `load-external-dtd` feature and throws
     * ParserConfigurationException, which made EVERY Office file fail. Each
     * hardening feature is now applied best-effort.
     */
    fun parseXml(xml: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
    }

    private fun trySetFeature(factory: DocumentBuilderFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Exception) {
            // Unsupported on this platform parser — safe to ignore.
        }
    }
}

/** DOCX: every <w:t> run from word/document.xml, one line per <w:p> paragraph. */
class DocxParser : DocumentParser {
    override val format = AttachmentType.DOCX
    override fun supports(file: File) = file.extension.equals("docx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val documentXml = ZipXml.readEntry(zip, name = "word/document.xml")
                ?: throw IllegalArgumentException("Not a valid Word document (word/document.xml missing)")
            val root = ZipXml.parseXml(documentXml)
            val paragraphs = root.getElementsByTagName("w:p")
            val out = StringBuilder()
            for (i in 0 until paragraphs.length) {
                // Runs inside one paragraph are contiguous text — join without a separator.
                val text = ZipXml.textOf(paragraphs.item(i) as Element, "w:t", separator = "")
                if (text.isNotBlank()) out.append(text).append('\n')
            }
            return TextParsers.singlePage(TextParsers.normalize(out.toString()), "No text found in DOCX document")
        }
    }
}

/** PPTX: text of every slide (<a:t> runs per <a:p> paragraph), slide by slide. */
class PptxParser : DocumentParser {
    override val format = AttachmentType.PPTX
    override fun supports(file: File) = file.extension.equals("pptx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val slides = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .toList()
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).use { it.readBytes() }
                    val text = runCatching {
                        val root = ZipXml.parseXml(xml)
                        val paragraphs = root.getElementsByTagName("a:p")
                        val lines = mutableListOf<String>()
                        for (i in 0 until paragraphs.length) {
                            val line = ZipXml.textOf(paragraphs.item(i) as Element, "a:t", separator = "")
                            if (line.isNotBlank()) lines += line
                        }
                        lines.joinToString("\n")
                    }.getOrDefault("")
                    if (text.isNotBlank()) slides += text
                }
            val normalized = TextParsers.normalize(slides.joinToString("\n\n"))
            if (normalized.isEmpty()) throw IllegalArgumentException("No text found in PPTX presentation")
            return ParsedDocument(
                text = normalized,
                pages = slides.map { TextParsers.normalize(it) }.filter { it.isNotEmpty() },
                pageCount = slides.size
            )
        }
    }
}

/** XLSX: shared strings table plus every worksheet, one row per line. */
class XlsxParser : DocumentParser {
    override val format = AttachmentType.XLSX
    override fun supports(file: File) = file.extension.equals("xlsx", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val sharedStrings = mutableListOf<String>()
            ZipXml.readEntry(zip, name = "xl/sharedStrings.xml")?.let { ssXml ->
                val root = ZipXml.parseXml(ssXml)
                val items = root.getElementsByTagName("si")
                for (i in 0 until items.length) {
                    sharedStrings += ZipXml.textOf(items.item(i) as Element, "t", separator = "")
                }
            }
            val sheets = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .toList()
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).use { it.readBytes() }
                    val root = runCatching { ZipXml.parseXml(xml) }.getOrNull() ?: return@forEach
                    val rows = root.getElementsByTagName("row")
                    val sheetLines = mutableListOf<String>()
                    for (r in 0 until rows.length) {
                        val row = rows.item(r) as Element
                        val cells = row.getElementsByTagName("c")
                        val values = mutableListOf<String>()
                        for (c in 0 until cells.length) {
                            val cell = cells.item(c) as Element
                            val cellType = cell.getAttribute("t")
                            val value = cell.getElementsByTagName("v").let { nodes ->
                                if (nodes.length > 0) nodes.item(0).textContent else ""
                            }
                            val resolved = when {
                                // Inline strings carry the value in <is><t>.
                                cellType == "inlineStr" -> ZipXml.textOf(cell, "t", separator = "")
                                // Shared strings index into the pool via <v>.
                                cellType == "s" -> value.trim().toIntOrNull()
                                    ?.takeIf { it in sharedStrings.indices }
                                    ?.let { sharedStrings[it] } ?: value
                                else -> value
                            }
                            if (resolved.isNotBlank()) values += resolved
                        }
                        if (values.isNotEmpty()) sheetLines += values.joinToString(" | ")
                    }
                    if (sheetLines.isNotEmpty()) sheets += sheetLines.joinToString("\n")
                }
            val normalized = TextParsers.normalize(sheets.joinToString("\n\n"))
            return TextParsers.singlePage(normalized, "No text found in XLSX workbook")
        }
    }
}

/** EPUB: reads the spine from the OPF and concatenates each XHTML chapter in order. */
class EpubParser : DocumentParser {
    override val format = AttachmentType.EPUB
    override fun supports(file: File) = file.extension.equals("epub", true)

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        ZipFile(file).use { zip ->
            val opf = zip.entries().asSequence()
                .firstOrNull { it.name.endsWith(".opf", ignoreCase = true) }
                ?: throw IllegalArgumentException("EPUB has no content.opf")
            val opfXml = zip.getInputStream(opf).use { it.readBytes() }
            val root = ZipXml.parseXml(opfXml)
            val baseDir = opf.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val manifest = mutableMapOf<String, String>()
            val manifestNodes = root.getElementsByTagName("item")
            for (i in 0 until manifestNodes.length) {
                val item = manifestNodes.item(i) as Element
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
            }
            val spine = mutableListOf<String>()
            val spineNodes = root.getElementsByTagName("itemref")
            for (i in 0 until spineNodes.length) {
                val idref = (spineNodes.item(i) as Element).getAttribute("idref")
                manifest[idref]?.let { spine += it }
            }
            val chapters = mutableListOf<String>()
            for (href in spine) {
                val cleanHref = href.substringBefore('#')
                val entry = zip.getEntry(baseDir + cleanHref)
                    ?: zip.getEntry(baseDir + cleanHref.replace("%20", " "))
                    ?: continue
                val xml = runCatching {
                    zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                }.getOrNull() ?: continue
                val chapter = runCatching {
                    val html = xml.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                        .replace(Regex("(?i)</(p|div|h[1-6]|li)\\s*>"), "\n")
                    val text = TextParsers.TAG_RE.matcher(html).replaceAll(" ")
                    TextParsers.normalize(TextParsers.decodeHtmlEntities(text))
                }.getOrDefault("")
                if (chapter.isNotBlank()) chapters += chapter
            }
            return TextParsers.singlePage(chapters.joinToString("\n\n"), "No text found in EPUB book")
        }
    }
}
