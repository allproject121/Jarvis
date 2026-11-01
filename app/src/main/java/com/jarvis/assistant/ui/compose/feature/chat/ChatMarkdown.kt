package com.jarvis.assistant.ui.compose.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Markdown model + parser (ported from AndroLLM MarkdownRenderer, no libraries)
// ─────────────────────────────────────────────────────────────────────────────

internal sealed interface MarkdownNode {
    data class Header(val level: Int, val content: String) : MarkdownNode
    data class Paragraph(val text: String) : MarkdownNode
    data class CodeBlock(val language: String, val code: String) : MarkdownNode
    data class Blockquote(val text: String) : MarkdownNode
    data class BulletList(val items: List<String>) : MarkdownNode
    data class NumberedList(val items: List<String>) : MarkdownNode
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownNode
    data class Callout(val kind: String, val text: String) : MarkdownNode
    data object HorizontalRule : MarkdownNode
    data class ImagePlaceholder(val altText: String, val url: String) : MarkdownNode
    data class MathBlock(val expression: String) : MarkdownNode
}

/** Markdown rendering with Compose Text / AnnotatedString only. */
@Composable
internal fun MarkdownRenderer(
    markdownText: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    codeWrapping: Boolean = false,
    warnBeforeOpeningAiLinks: Boolean = true
) {
    val nodes = remember(markdownText) { parseMarkdown(markdownText) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEach { node ->
            when (node) {
                is MarkdownNode.Header -> HeaderItem(node, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Paragraph -> ParagraphItem(node.text, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.CodeBlock -> CodeBlockCard(code = node.code, language = node.language, initialWrapLines = codeWrapping)
                is MarkdownNode.Blockquote -> BlockquoteItem(node.text, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.BulletList -> BulletListItem(node.items, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.NumberedList -> NumberedListItem(node.items, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Table -> TableItem(node, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.Callout -> CalloutItem(node, textColor, warnBeforeOpeningAiLinks)
                is MarkdownNode.HorizontalRule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.ledger.deskHairline
                )
                is MarkdownNode.ImagePlaceholder -> ImagePlaceholderItem(node)
                is MarkdownNode.MathBlock -> MathBlockItem(node)
            }
        }
    }
}

@Composable
private fun HeaderItem(header: MarkdownNode.Header, textColor: Color, warn: Boolean) {
    val style = when (header.level) {
        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = textColor)
        2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor)
        3 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = textColor)
        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = textColor)
    }
    LinkAwareClickableText(
        text = header.content,
        textColor = textColor,
        style = style,
        warnBeforeOpeningAiLinks = warn,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun ParagraphItem(text: String, textColor: Color, warn: Boolean) {
    LinkAwareClickableText(
        text = text,
        textColor = textColor,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        warnBeforeOpeningAiLinks = warn
    )
}

@Composable
private fun BlockquoteItem(text: String, textColor: Color, warn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .background(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.ledger.deskWalnutRaised,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
        ) {
            LinkAwareClickableText(
                text = text,
                textColor = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                warnBeforeOpeningAiLinks = warn,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun BulletListItem(items: List<String>, textColor: Color, warn: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = "• ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                LinkAwareClickableText(
                    text = item,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    warnBeforeOpeningAiLinks = warn,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NumberedListItem(items: List<String>, textColor: Color, warn: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}. ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                LinkAwareClickableText(
                    text = item,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    warnBeforeOpeningAiLinks = warn,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableItem(table: MarkdownNode.Table, textColor: Color, warn: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnutRaised),
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.ledger.deskNightRaised, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                table.headers.forEach { header ->
                    LinkAwareClickableText(
                        text = header,
                        textColor = textColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        warnBeforeOpeningAiLinks = warn,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.ledger.deskHairline)
            table.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { cell ->
                        LinkAwareClickableText(
                            text = cell,
                            textColor = textColor,
                            style = MaterialTheme.typography.bodySmall,
                            warnBeforeOpeningAiLinks = warn,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePlaceholderItem(image: MarkdownNode.ImagePlaceholder) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnutRaised),
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.ledger.deskPaperDim)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = image.altText.ifBlank { "Image Attachment" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.ledger.deskPaper
                )
                if (image.url.isNotBlank()) {
                    Text(
                        text = image.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
            }
        }
    }
}

@Composable
private fun MathBlockItem(math: MarkdownNode.MathBlock) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Functions, contentDescription = "Math", tint = MaterialTheme.ledger.deskPaperDim)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = math.expression,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ledger.deskPaperDim
            )
        }
    }
}

private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.+)")
private val IMAGE_REGEX = Regex("!\\[(.*?)\\]\\((.*?)\\)")
private val NUMBERED_REGEX = Regex("^(\\d+)\\.\\s+(.+)")

/** Simple line-by-line Markdown parser into a structured node list. */
internal fun parseMarkdown(text: String): List<MarkdownNode> {
    val nodes = mutableListOf<MarkdownNode>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block (an unclosed fence while streaming runs to the end).
        if (line.trimStart().startsWith("```")) {
            val language = line.trimStart().removePrefix("```").trim()
            val codeBuilder = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeBuilder.append(lines[i]).append("\n")
                i++
            }
            nodes.add(MarkdownNode.CodeBlock(language, codeBuilder.toString().trimEnd()))
            i++
            continue
        }

        // Math block ($$ ... $$)
        if (line.trim().startsWith("$$")) {
            val trimmed = line.trim()
            if (trimmed.endsWith("$$") && trimmed.length > 4) {
                nodes.add(MarkdownNode.MathBlock(trimmed.removePrefix("$$").removeSuffix("$$").trim()))
                i++
                continue
            }
            val mathBuilder = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                mathBuilder.append(lines[i]).append("\n")
                i++
            }
            nodes.add(MarkdownNode.MathBlock(mathBuilder.toString().trim()))
            i++
            continue
        }

        val trimmedLine = line.trim()
        if (trimmedLine == "---" || trimmedLine == "***" || trimmedLine == "___") {
            nodes.add(MarkdownNode.HorizontalRule)
            i++
            continue
        }

        val headerMatch = HEADER_REGEX.find(line)
        if (headerMatch != null) {
            nodes.add(MarkdownNode.Header(headerMatch.groupValues[1].length, headerMatch.groupValues[2]))
            i++
            continue
        }

        val callout = parseCallout(line)
        if (callout != null) {
            val textBuilder = StringBuilder(callout.second)
            i++
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val rest = lines[i].trimStart().removePrefix(">").trim()
                if (rest.isNotBlank()) textBuilder.append('\n').append(rest)
                i++
            }
            nodes.add(MarkdownNode.Callout(callout.first, textBuilder.toString().trim()))
            continue
        }

        if (line.trimStart().startsWith(">")) {
            nodes.add(MarkdownNode.Blockquote(line.trimStart().removePrefix(">").trim()))
            i++
            continue
        }

        val imageMatch = IMAGE_REGEX.find(line)
        if (imageMatch != null) {
            nodes.add(MarkdownNode.ImagePlaceholder(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
            continue
        }

        if (line.contains("|") && i + 1 < lines.size && lines[i + 1].contains("---")) {
            val headers = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|")) {
                val rowCells = lines[i].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (rowCells.isNotEmpty()) rows.add(rowCells)
                i++
            }
            nodes.add(MarkdownNode.Table(headers, rows))
            continue
        }

        if (line.trimStart().startsWith("* ") || line.trimStart().startsWith("- ")) {
            val listItems = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith("* ") || lines[i].trimStart().startsWith("- "))) {
                listItems.add(lines[i].trimStart().substring(2))
                i++
            }
            nodes.add(MarkdownNode.BulletList(listItems))
            continue
        }

        if (NUMBERED_REGEX.find(line.trimStart()) != null) {
            val listItems = mutableListOf<String>()
            while (i < lines.size && NUMBERED_REGEX.containsMatchIn(lines[i].trimStart())) {
                val match = NUMBERED_REGEX.find(lines[i].trimStart())
                if (match != null) listItems.add(match.groupValues[2])
                i++
            }
            nodes.add(MarkdownNode.NumberedList(listItems))
            continue
        }

        if (line.isNotBlank()) nodes.add(MarkdownNode.Paragraph(line.trim()))
        i++
    }
    return nodes
}

private val CALLOUT_ALIASES = mapOf(
    "tip" to "Tip",
    "note" to "Note",
    "warning" to "Warning",
    "warn" to "Warning",
    "caution" to "Warning",
    "important" to "Important",
    "info" to "Note",
    "example" to "Example",
    "question" to "Note",
    "danger" to "Warning"
)

private val ALERT_REGEX = Regex("^>\\s*\\[!\\s*([A-Za-z]+)\\]\\s*(.*)$")
private val BOLD_LEAD_REGEX = Regex("^\\*\\*([A-Za-z]+)\\s*:\\*\\*\\s*(.+)$")
private val LEADING_SYMBOLS_REGEX = Regex("^[^\\p{L}\\p{N}]+\\s*")

private fun parseCallout(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    ALERT_REGEX.find(trimmed)?.let { alert ->
        val kind = CALLOUT_ALIASES[alert.groupValues[1].lowercase()] ?: return null
        return kind to alert.groupValues[2].trim()
    }
    BOLD_LEAD_REGEX.find(trimmed)?.let { bold ->
        val kind = CALLOUT_ALIASES[bold.groupValues[1].lowercase()] ?: return null
        return kind to bold.groupValues[2].trim()
    }
    val emojiKind = when {
        trimmed.startsWith("💡") || trimmed.startsWith("✨") || trimmed.startsWith("📌") -> "Tip"
        trimmed.startsWith("⚠") || trimmed.startsWith("❗") || trimmed.startsWith("🔴") -> "Warning"
        trimmed.startsWith("📝") || trimmed.startsWith("📖") -> "Note"
        trimmed.startsWith("🎯") || trimmed.startsWith("🧩") -> "Example"
        else -> null
    }
    if (emojiKind != null) {
        return emojiKind to trimmed.replaceFirst(LEADING_SYMBOLS_REGEX, "")
    }
    return null
}

/** Colours + glyph of one callout kind (ported from AndroLLM MarkdownRenderer). */
private data class CalloutPalette(
    val bg: Color,
    val border: Color,
    val accent: Color,
    val glyph: String
)

@Composable
private fun CalloutItem(callout: MarkdownNode.Callout, textColor: Color, warn: Boolean) {
    val palette = when (callout.kind) {
        "Tip" -> CalloutPalette(MaterialTheme.ledger.deskWalnutRaised, MaterialTheme.ledger.deskHairline, MaterialTheme.ledger.deskPaperDim, "💡")
        "Warning" -> CalloutPalette(MaterialTheme.ledger.deskWalnutRaised, MaterialTheme.ledger.emberRedSoft, MaterialTheme.ledger.lampGlow, "⚠️")
        "Example" -> CalloutPalette(MaterialTheme.ledger.deskWalnutRaised, MaterialTheme.ledger.deskHairline, MaterialTheme.ledger.deskPaperDim, "✨")
        else -> CalloutPalette(MaterialTheme.ledger.deskWalnutRaised, MaterialTheme.ledger.deskHairline, MaterialTheme.ledger.deskPaperDim, "📌")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        color = palette.bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = palette.glyph, fontSize = 13.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = callout.kind.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = palette.accent
                    )
                )
            }
            if (callout.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                LinkAwareClickableText(
                    text = callout.text,
                    textColor = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 21.sp,
                        color = textColor.copy(alpha = 0.92f)
                    ),
                    warnBeforeOpeningAiLinks = warn
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline formatting + links
// ─────────────────────────────────────────────────────────────────────────────

private const val URL_TAG = "URL"

private fun parseFormattedText(
    text: String,
    defaultColor: Color,
    inlineCodeBackground: Color,
    inlineCodeForeground: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    val segments = AiLinkUtils.splitTextWithLinks(text)
    if (segments.none { it is AiLinkUtils.Segment.Link }) {
        appendFormattedInline(text, defaultColor, inlineCodeBackground, inlineCodeForeground)
        return@buildAnnotatedString
    }
    for (segment in segments) {
        when (segment) {
            is AiLinkUtils.Segment.Plain ->
                appendFormattedInline(segment.text, defaultColor, inlineCodeBackground, inlineCodeForeground)
            is AiLinkUtils.Segment.Link -> {
                val linkStart = length
                append(segment.displayText)
                append(" ↗")
                val linkEnd = length
                addStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Medium
                    ),
                    linkStart,
                    linkEnd
                )
                addStringAnnotation(URL_TAG, segment.url, linkStart, linkEnd)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendFormattedInline(
    text: String,
    defaultColor: Color,
    inlineCodeBackground: Color,
    inlineCodeForeground: Color
) {
    val tokens = text.split("`")
    if (tokens.size > 1) {
        for (i in tokens.indices) {
            if (i % 2 == 1) {
                val start = length
                append(" ${tokens[i]} ")
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineCodeBackground,
                        color = inlineCodeForeground,
                        fontSize = 13.sp
                    ),
                    start,
                    length
                )
            } else {
                appendStyleFormatted(tokens[i], defaultColor)
            }
        }
    } else {
        appendStyleFormatted(text, defaultColor)
    }
}

private fun AnnotatedString.Builder.appendStyleFormatted(text: String, defaultColor: Color) {
    val parts = text.split("**")
    for (i in parts.indices) {
        if (i % 2 == 1) {
            val start = length
            append(parts[i])
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor), start, length)
        } else {
            val subParts = parts[i].split("*")
            for (j in subParts.indices) {
                val start = length
                append(subParts[j])
                if (j % 2 == 1) {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor), start, length)
                } else {
                    addStyle(SpanStyle(color = defaultColor), start, length)
                }
            }
        }
    }
}

/** Text with inline markdown, tappable (warned) links and a long-press link menu. */
@Composable
internal fun LinkAwareClickableText(
    text: String,
    textColor: Color,
    style: TextStyle,
    warnBeforeOpeningAiLinks: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val codeChipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeChipFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedString = remember(text, textColor, codeChipBg, codeChipFg, linkColor) {
        parseFormattedText(text, textColor, codeChipBg, codeChipFg, linkColor)
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var longPressUrl by remember { mutableStateOf<String?>(null) }

    pendingUrl?.let { url ->
        AiLinkWarningDialog(
            url = url,
            onOpen = { toOpen ->
                if (AiLinkUtils.isValidForOpening(toOpen)) {
                    openAiLinkSafely(context, toOpen)
                } else {
                    Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                }
                pendingUrl = null
            },
            onDismiss = { pendingUrl = null }
        )
    }

    longPressUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { longPressUrl = null },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            },
            title = {
                Text("Link options", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Choose an action for this AI-generated link.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    longPressUrl = null
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share link")) }
                        longPressUrl = null
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                    TextButton(onClick = { longPressUrl = null }) { Text("Cancel") }
                }
            }
        )
    }

    Text(
        text = annotatedString,
        style = style,
        modifier = modifier.pointerInput(annotatedString, warnBeforeOpeningAiLinks) {
            detectTapGestures(
                onTap = { pos ->
                    val result = layoutResult ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(pos)
                    val url = annotatedString.getStringAnnotations(tag = URL_TAG, start = offset, end = offset)
                        .firstOrNull()?.item ?: return@detectTapGestures
                    if (!AiLinkUtils.isValidForOpening(url)) {
                        Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                    } else if (warnBeforeOpeningAiLinks) {
                        pendingUrl = url
                    } else if (AiLinkUtils.isAllowedScheme(url)) {
                        openAiLinkSafely(context, url)
                    } else {
                        Toast.makeText(context, "Blocked unsafe URL", Toast.LENGTH_SHORT).show()
                    }
                },
                onLongPress = { pos ->
                    val result = layoutResult ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(pos)
                    annotatedString.getStringAnnotations(tag = URL_TAG, start = offset, end = offset)
                        .firstOrNull()?.let { longPressUrl = it.item }
                }
            )
        },
        onTextLayout = { layoutResult = it }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// AI link safety (ported from AndroLLM AiLinkUtils / AiLinkWarningDialog)
// ─────────────────────────────────────────────────────────────────────────────

internal object AiLinkUtils {

    private val allowedSchemes = setOf("http", "https")
    private val dangerousSchemes = setOf("javascript", "file", "intent", "data", "vbscript", "about", "blob", "ftp")
    private val markdownLinkRegex = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val plainUrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    data class AiLink(
        val url: String,
        val displayText: String? = null,
        val rangeStart: Int,
        val rangeEnd: Int,
        val trailingPunctuation: String = ""
    )

    sealed interface Segment {
        data class Plain(val text: String) : Segment
        data class Link(val displayText: String, val url: String, val isMarkdown: Boolean = false) : Segment
    }

    private fun schemeOf(url: String): String? {
        val trimmed = url.trim()
        return try {
            java.net.URI(trimmed).scheme?.lowercase()
        } catch (_: Exception) {
            try {
                Uri.parse(trimmed).scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isAllowedScheme(url: String): Boolean {
        if (url.isBlank()) return false
        return schemeOf(url)?.let { it in allowedSchemes } == true
    }

    fun isDangerousScheme(url: String): Boolean {
        if (url.isBlank()) return false
        return schemeOf(url)?.let { it in dangerousSchemes } == true
    }

    fun isValidForOpening(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        if (isDangerousScheme(trimmed) || !isAllowedScheme(trimmed)) return false
        try {
            val uri = java.net.URI(trimmed)
            if (!uri.host.isNullOrBlank() || !uri.authority.isNullOrBlank()) return true
        } catch (_: Exception) {
            // fall through to android.net.Uri
        }
        return try {
            val uri = Uri.parse(trimmed)
            !uri.host.isNullOrBlank() || !uri.authority.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun stripTrailingPunctuation(raw: String): Pair<String, String> {
        if (raw.isEmpty()) return raw to ""
        var end = raw.length
        loop@ while (end > 0) {
            val c = raw[end - 1]
            when (c) {
                '.', ',', '!', '?', ':', ';', '\'', '"', '`' -> end--
                ')', ']', '}', '>' -> {
                    val prefix = raw.substring(0, end - 1)
                    val open = when (c) {
                        ')' -> '('
                        ']' -> '['
                        '}' -> '{'
                        else -> '<'
                    }
                    val openCount = prefix.count { it == open }
                    val closeCount = prefix.count { it == c } + 1
                    if (closeCount > openCount) end-- else break@loop
                }
                else -> break@loop
            }
        }
        if (end == raw.length) return raw to ""
        return raw.substring(0, end) to raw.substring(end)
    }

    fun extractLinks(text: String): List<AiLink> {
        if (text.isBlank()) return emptyList()
        val links = mutableListOf<AiLink>()
        for (match in markdownLinkRegex.findAll(text)) {
            val (clean, trailing) = stripTrailingPunctuation(match.groupValues[2].trim())
            if (clean.isBlank() || !isValidForOpening(clean)) continue
            links.add(
                AiLink(
                    url = clean,
                    displayText = match.groupValues[1],
                    rangeStart = match.range.first,
                    rangeEnd = match.range.last + 1,
                    trailingPunctuation = trailing
                )
            )
        }
        val markdownLinks = links.toList()
        for (match in plainUrlRegex.findAll(text)) {
            val start = match.range.first
            if (markdownLinks.any { start in it.rangeStart until it.rangeEnd }) continue
            val (clean, trailing) = stripTrailingPunctuation(match.value)
            if (clean.isBlank() || !isValidForOpening(clean)) continue
            links.add(
                AiLink(
                    url = clean,
                    displayText = null,
                    rangeStart = start,
                    rangeEnd = start + clean.length,
                    trailingPunctuation = trailing
                )
            )
        }
        return links.sortedBy { it.rangeStart }
    }

    fun splitTextWithLinks(text: String): List<Segment> {
        val links = extractLinks(text)
        if (links.isEmpty()) return listOf(Segment.Plain(text))
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (link in links) {
            if (link.rangeStart < cursor) continue
            if (link.rangeStart > cursor) segments.add(Segment.Plain(text.substring(cursor, link.rangeStart)))
            val display = link.displayText
            if (display != null) {
                segments.add(Segment.Link(displayText = display, url = link.url, isMarkdown = true))
                cursor = link.rangeEnd
            } else {
                segments.add(Segment.Link(displayText = link.url, url = link.url))
                if (link.trailingPunctuation.isNotEmpty()) segments.add(Segment.Plain(link.trailingPunctuation))
                cursor = link.rangeStart + link.url.length + link.trailingPunctuation.length
            }
        }
        if (cursor < text.length) segments.add(Segment.Plain(text.substring(cursor)))
        return segments
    }
}

@Composable
internal fun AiLinkWarningDialog(
    url: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isValid = AiLinkUtils.isValidForOpening(url)
    val isDangerous = AiLinkUtils.isDangerousScheme(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(text = "External Link", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "You are about to open an external link found by AI.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This link was generated or discovered by the assistant and may be outside Jarvis. Jarvis does not verify every external site. Only continue if you trust this link.",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (!isValid) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isDangerous) {
                            "This link uses a blocked scheme and cannot be opened safely."
                        } else {
                            "This link appears malformed and cannot be opened."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isValid || !AiLinkUtils.isAllowedScheme(url)) {
                        Toast.makeText(context, "Blocked unsafe URL", Toast.LENGTH_SHORT).show()
                    } else {
                        onOpen(url)
                    }
                },
                enabled = isValid
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open link")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

internal fun openAiLinkSafely(context: Context, url: String): Boolean {
    if (!AiLinkUtils.isValidForOpening(url)) return false
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Code block card with lightweight syntax highlighting
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun CodeBlockCard(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier,
    initialWrapLines: Boolean = false
) {
    val context = LocalContext.current
    var isWrapped by remember { mutableStateOf(initialWrapLines) }
    var isCopied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val displayLanguage = language.ifBlank { "code" }.lowercase(Locale.getDefault())
    val highlighted = remember(code, displayLanguage) { highlightSyntax(code) }

    val isDarkCanvas = MaterialTheme.ledger.deskNight.luminance() < 0.5f
    val containerColor = if (isDarkCanvas) Color(0xFF0B0B0B) else Color(0xFF262624)
    val headerColor = if (isDarkCanvas) Color(0xFF121212) else Color(0xFF1E1E1B)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = Color(0xFFEDEAE1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = headerColor,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLanguage,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE69D81)
                        )
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isWrapped = !isWrapped }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = "Toggle line wrap",
                                tint = if (isWrapped) Color(0xFFA8C07A) else Color(0xFF9A9891),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(displayLanguage, code))
                                isCopied = true
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    delay(2000)
                                    isCopied = false
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isCopied) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Copied",
                                        tint = Color(0xFFA8C07A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy code",
                                        tint = Color(0xFF9A9891),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SelectionContainer {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .then(if (isWrapped) Modifier else Modifier.horizontalScroll(scrollState))
                ) {
                    Text(
                        text = highlighted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        softWrap = isWrapped,
                        color = Color(0xFFEDEAE1)
                    )
                }
            }
        }
    }
}

private val COMMENT_REGEX = Regex("(//.*$|#.*$|/\\*.*?\\*/)", RegexOption.MULTILINE)
private val STRING_REGEX = Regex("(\"[^\"]*\"|'[^']*'|`[^`]*`)")
private val NUMBER_REGEX = Regex("\\b\\d+(_\\d+)*(\\.\\d+)?\\b")
private val WORD_REGEX = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
private val CODE_KEYWORDS = setOf(
    "val", "var", "fun", "class", "object", "interface", "package", "import",
    "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "public", "private", "protected", "internal", "override", "abstract", "sealed",
    "data", "enum", "companion", "by", "init", "suspend",
    "def", "lambda", "yield", "async", "await", "from", "as",
    "let", "const", "function", "export", "default", "type", "typeof",
    "struct", "impl", "trait", "pub", "use", "fn", "match", "mod", "mut",
    "select", "insert", "update", "delete", "where", "join", "group", "order",
    "void", "int", "double", "float", "boolean", "char", "long", "short", "byte"
)

private fun highlightSyntax(code: String): AnnotatedString = buildAnnotatedString {
    append(code)
    val keywordColor = Color(0xFFE69D81)
    val stringColor = Color(0xFFA8C07A)
    val commentColor = Color(0xFF7E7E7E)
    val numberColor = Color(0xFFE0A33D)
    val typeColor = Color(0xFF9DB8D2)
    val fnColor = Color(0xFFD0B49A)

    COMMENT_REGEX.findAll(code).forEach { addStyle(SpanStyle(color = commentColor), it.range.first, it.range.last + 1) }
    STRING_REGEX.findAll(code).forEach { addStyle(SpanStyle(color = stringColor), it.range.first, it.range.last + 1) }
    NUMBER_REGEX.findAll(code).forEach { addStyle(SpanStyle(color = numberColor), it.range.first, it.range.last + 1) }
    WORD_REGEX.findAll(code).forEach { match ->
        val word = match.value
        val start = match.range.first
        val end = match.range.last + 1
        when {
            word in CODE_KEYWORDS -> addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold), start, end)
            word.first().isUpperCase() -> addStyle(SpanStyle(color = typeColor), start, end)
            end < code.length && code[end] == '(' -> addStyle(SpanStyle(color = fnColor), start, end)
        }
    }
}
