package com.jarvis.assistant.attachments.parser

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object ZipXml {

    fun textOf(root: Element, tag: String): String {
        val out = StringBuilder()
        val nodes = root.getElementsByTagName(tag)
        for (i in 0 until nodes.length) out.append(nodes.item(i).textContent).append(' ')
        return out.toString().trim()
    }

    fun readEntry(zip: ZipFile, name: String? = null, suffix: String? = null): String? {
        val entry = zip.entries().asSequence().firstOrNull {
            (name != null && it.name.equals(name, ignoreCase = true)) ||
                (suffix != null && it.name.endsWith(suffix, ignoreCase = true))
        } ?: return null
        return zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
    }

    fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
    }
}

class DocxParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("docx", true)

    override fun parse(file: File): ParsedDocument {
        ZipFile(file).use { zip ->
            val documentXml = ZipXml.readEntry(zip, name = "word/document.xml") ?: return TextParsers.singlePage("")
            val root = ZipXml.parseXml(documentXml)
            val paragraphs = root.getElementsByTagName("w:p")
            val out = StringBuilder()
            for (i in 0 until paragraphs.length) {
                val text = ZipXml.textOf(paragraphs.item(i) as Element, "w:t")
                if (text.isNotBlank()) out.append(text).append('\n')
            }
            val normalized = TextParsers.normalize(out.toString())
            return TextParsers.singlePage(normalized)
        }
    }
}

class PptxParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("pptx", true)

    override fun parse(file: File): ParsedDocument {
        ZipFile(file).use { zip ->
            val slides = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val text = runCatching { ZipXml.textOf(ZipXml.parseXml(xml), "a:t") }.getOrDefault("")
                    if (text.isNotBlank()) slides += text
                }
            val normalized = TextParsers.normalize(slides.joinToString("\n\n"))
            return ParsedDocument(normalized, slides, emptyList(), slides.size)
        }
    }
}

class XlsxParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("xlsx", true)

    override fun parse(file: File): ParsedDocument {
        ZipFile(file).use { zip ->
            val sharedXml = ZipXml.readEntry(zip, name = "xl/sharedStrings.xml")
            val sharedStrings = mutableListOf<String>()
            if (sharedXml != null) {
                val root = ZipXml.parseXml(sharedXml)
                val nodes = root.getElementsByTagName("si")
                for (i in 0 until nodes.length) {
                    sharedStrings += ZipXml.textOf(nodes.item(i) as Element, "t")
                }
            }

            val sheets = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val root = runCatching { ZipXml.parseXml(xml) }.getOrNull() ?: return@forEach
                    val rows = root.getElementsByTagName("row")
                    val sheetLines = mutableListOf<String>()
                    for (r in 0 until rows.length) {
                        val row = rows.item(r) as Element
                        val cells = row.getElementsByTagName("c")
                        val values = mutableListOf<String>()
                        for (c in 0 until cells.length) {
                            val cell = cells.item(c) as Element
                            val value = cell.getElementsByTagName("v").let { if (it.length > 0) it.item(0).textContent else "" }
                            if (value.isNotBlank()) {
                                val inline = runCatching { ZipXml.textOf(cell, "t") }.getOrDefault("")
                                val resolved = when {
                                    inline.isNotBlank() -> inline
                                    value.toIntOrNull() != null && value.toInt() in sharedStrings.indices -> sharedStrings[value.toInt()]
                                    else -> value
                                }
                                if (resolved.isNotBlank()) values += resolved
                            }
                        }
                        if (values.isNotEmpty()) sheetLines += values.joinToString(" | ")
                    }
                    if (sheetLines.isNotEmpty()) sheets += sheetLines.joinToString("\n")
                }
            val normalized = TextParsers.normalize(sheets.joinToString("\n\n"))
            return TextParsers.singlePage(normalized)
        }
    }
}

class EpubParser : DocumentParser {
    override fun supports(file: File) = file.extension.equals("epub", true)

    override fun parse(file: File): ParsedDocument {
        ZipFile(file).use { zip ->
            val chapters = mutableListOf<String>()
            zip.entries().asSequence()
                .filter { it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) }
                .forEach { entry ->
                    val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val clean = xml.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                    val text = TextParsers.TAG_RE.matcher(clean).replaceAll(" ")
                    val norm = TextParsers.normalize(text)
                    if (norm.isNotBlank()) chapters += norm
                }
            val normalized = TextParsers.normalize(chapters.joinToString("\n\n"))
            return ParsedDocument(normalized, chapters, emptyList(), chapters.size)
        }
    }
}
