package com.jarvis.assistant.codestudio.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Request to scroll the editor so that [offset] is visible ([nonce] makes repeated requests distinct). */
data class ScrollRequest(val offset: Int, val nonce: Long = System.nanoTime())

@Composable
fun rememberSyntaxColors(): SyntaxColors {
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.luminance() < 0.45f) SyntaxColors.Dark else SyntaxColors.Light
}

/**
 * Code editor: line-number gutter + monospace text field with syntax highlighting and search-match
 * highlighting. No soft wrap (horizontal scroll), gutter and text share one vertical scroll so they
 * always stay aligned.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: CodeLanguage,
    modifier: Modifier = Modifier,
    matches: List<IntRange> = emptyList(),
    currentMatch: Int = -1,
    scrollRequest: ScrollRequest? = null,
    fontSizeSp: Float = 13f,
    readOnly: Boolean = false
) {
    val colors = rememberSyntaxColors()
    val ink = MaterialTheme.colorScheme.onSurface
    val gutterInk = ink.copy(alpha = 0.38f)
    val lineHeight = (fontSizeSp * 1.45f).sp
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, lineHeight = lineHeight, color = ink)
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSizeSp) { measurer.measure("M", style).size.width.toFloat().coerceAtLeast(1f) }
    val lineHeightPx = with(density) { lineHeight.toPx() }

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val transformation = remember(language, colors, matches, currentMatch) {
        CodeVisualTransformation(language, colors, matches, currentMatch)
    }

    BoxWithConstraints(modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))) {
        val viewportH = constraints.maxHeight
        val viewportW = constraints.maxWidth
        LaunchedEffect(scrollRequest) {
            val req = scrollRequest ?: return@LaunchedEffect
            val off = req.offset.coerceIn(0, value.text.length)
            val line = value.text.substring(0, off).count { it == '\n' }
            val col = off - (value.text.lastIndexOf('\n', (off - 1).coerceAtLeast(0)).let { if (off == 0) -1 else it } + 1)
            val y = (line * lineHeightPx - viewportH / 3f).toInt().coerceAtLeast(0)
            vScroll.animateScrollTo(y.coerceAtMost(vScroll.maxValue))
            val x = (col * charWidthPx - viewportW / 3f).toInt().coerceAtLeast(0)
            hScroll.animateScrollTo(x.coerceAtMost(hScroll.maxValue))
        }
        Row(modifier = Modifier.fillMaxSize().verticalScroll(vScroll)) {
            Text(
                text = gutterText,
                style = style.copy(color = gutterInk, textAlign = TextAlign.End),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 120.dp)
                    .widthIn(min = 22.dp)
            )
            Box(modifier = Modifier.horizontalScroll(hScroll)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii
                    ),
                    modifier = Modifier
                        .widthIn(min = with(density) { viewportW.toDp() })
                        .heightIn(min = with(density) { viewportH.toDp() })
                        .padding(start = 8.dp, end = 48.dp, top = 8.dp, bottom = 120.dp)
                )
            }
        }
    }
}
