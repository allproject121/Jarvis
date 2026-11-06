package com.jarvis.assistant.ui.compose.designsystem

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.compose.theme.LedgerColors
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.util.ThemeManager

/*
 * Dark Bento design system (2026-11-05) — the shared version of Media Studio's look.
 *
 * Section cards: 24dp radius, vertical gradient lighter at the top, a 64dp top sheen, a soft
 * radial glow in one top corner (violet / copper, alternating), and a 1dp hairline. Icon plates,
 * 19sp semibold card titles, pill chips (accent fill when selected), accent pill buttons and
 * plate text fields.
 *
 * Every colour comes from the active Jarvis theme attrs (the same `?attr/theme*` tokens the XML
 * screens and Media Studio use), so light themes (Pearl White, Sunset, Parchment) keep dark ink
 * on light cards. Nothing here hard-codes white text.
 */

@Immutable
data class BentoTokens(
    val cardTop: Color,
    val cardBase: Color,
    val border: Color,
    val plate: Color,
    val plateBorder: Color,
    val glow1: Color,
    val glow2: Color,
    val text: Color,
    val text2: Color,
    val muted: Color,
    val accent: Color,
    val accentFill: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val success: Color,
    val danger: Color,
    val isLight: Boolean
)

/** Optional override (a screen may provide its tokens once). */
val LocalBentoTokens = staticCompositionLocalOf<BentoTokens?> { null }

/** 0 = on the page, 1+ = inside a bento card (nested cards render as plates). */
val LocalBentoDepth = compositionLocalOf { 0 }

@Composable
fun bentoTokens(): BentoTokens {
    val provided = LocalBentoTokens.current
    val ctx = LocalContext.current
    val ledger = MaterialTheme.ledger
    val themeKey = remember { runCatching { ThemeManager.currentThemeKey() }.getOrDefault("") }
    val computed = remember(ctx, ledger, themeKey) { resolveBentoTokens(ctx, ledger) }
    return provided ?: computed
}

/** Computes the tokens once and provides them to everything below (cheaper on long screens). */
@Composable
fun ProvideBento(content: @Composable () -> Unit) {
    val tokens = bentoTokens()
    CompositionLocalProvider(LocalBentoTokens provides tokens, content = content)
}

private fun themedContext(context: Context): Context {
    val probe = TypedValue()
    return if (context.theme.resolveAttribute(R.attr.themeBg, probe, true)) {
        context
    } else {
        runCatching { ContextThemeWrapper(context, ThemeManager.currentThemeSpec().styleRes) as Context }
            .getOrDefault(context)
    }
}

private fun Color.isLightInk(): Boolean = luminance() > 0.5f

private fun ledgerTokens(l: LedgerColors): BentoTokens {
    val light = !l.deskPaper.isLightInk()
    return BentoTokens(
        cardTop = l.deskWalnutRaised,
        cardBase = l.deskWalnut,
        border = l.deskHairline,
        plate = l.deskWalnutDeep,
        plateBorder = l.deskHairline,
        glow1 = l.lampAmber.copy(alpha = if (light) 0.14f else 0.30f),
        glow2 = l.lampGlow.copy(alpha = if (light) 0.10f else 0.22f),
        text = l.deskPaper,
        text2 = l.deskInk,
        muted = l.deskInk,
        accent = if (light) l.lampDeep else l.lampGlow,
        accentFill = l.lampAmber,
        onAccent = l.inkOnLamp,
        accentSoft = l.lampAmber.copy(alpha = 0.16f),
        success = l.revolutNeonEmerald,
        danger = l.emberRed,
        isLight = light
    )
}

private fun resolveBentoTokens(context: Context, l: LedgerColors): BentoTokens {
    val fallback = ledgerTokens(l)
    val themed = themedContext(context)
    fun a(attr: Int, fb: Color): Color = Color(ThemeManager.attrColor(themed, attr, fb.toArgb()))

    val text = a(R.attr.themeTextPrimary, l.deskPaper)
    // Compose may run with its own palette (e.g. followJarvis = false). If the Jarvis attrs
    // disagree with it on light vs dark, stay on the Compose palette so ink keeps its contrast.
    if (text.isLightInk() != l.deskPaper.isLightInk()) return fallback

    return BentoTokens(
        cardTop = a(R.attr.themeCardGlassStart, fallback.cardTop),
        cardBase = a(R.attr.themeCardGlassEnd, fallback.cardBase),
        border = a(R.attr.themeCardBorder, fallback.border),
        plate = a(R.attr.themeChipBg, fallback.plate),
        plateBorder = a(R.attr.themePlateBorder, fallback.plateBorder),
        glow1 = a(R.attr.themeGlow1, fallback.glow1),
        glow2 = a(R.attr.themeGlow2, fallback.glow2),
        text = text,
        text2 = a(R.attr.themeTextSecondary, fallback.text2),
        muted = a(R.attr.themeTextMuted, fallback.muted),
        accent = a(R.attr.themeAccent, fallback.accent),
        accentFill = a(R.attr.themeAccentFill, fallback.accentFill),
        onAccent = a(R.attr.themeOnAccent, fallback.onAccent),
        accentSoft = a(R.attr.themeAccentLight, fallback.accentSoft),
        success = a(R.attr.themeSuccess, fallback.success),
        danger = l.emberRed,
        isLight = fallback.isLight
    )
}

// ── Shapes ───────────────────────────────────────────────────────────────────

val BentoCardShape = RoundedCornerShape(24.dp)
val BentoTileShape = RoundedCornerShape(18.dp)
val BentoPlateShape = RoundedCornerShape(14.dp)
val BentoPillShape = RoundedCornerShape(50)

// ── Surfaces ─────────────────────────────────────────────────────────────────

/**
 * The bento card surface: vertical gradient (lighter top), corner radial glow, 64dp top
 * sheen and a 1dp hairline. [nested] = a plate tile inside another card (no glow).
 */
fun Modifier.bentoSurface(
    t: BentoTokens,
    shape: Shape = BentoCardShape,
    warmGlow: Boolean = false,
    glowLeft: Boolean = false,
    nested: Boolean = false,
    borderColor: Color = Color.Unspecified
): Modifier {
    val stroke = if (borderColor == Color.Unspecified) (if (nested) t.plateBorder else t.border) else borderColor
    if (nested) {
        return this
            .clip(shape)
            .background(t.plate)
            .border(1.dp, stroke, shape)
    }
    val glow = if (warmGlow) t.glow2 else t.glow1
    val sheen = Color.White.copy(alpha = if (t.isLight) 0.55f else 0.08f)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(t.cardTop, t.cardBase)))
        .drawBehind {
            val r = maxOf(size.minDimension * 1.2f, 1f)
            drawRect(
                Brush.radialGradient(
                    listOf(glow, Color.Transparent),
                    center = Offset(if (glowLeft) 0f else size.width, 0f),
                    radius = r
                )
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(sheen, Color.Transparent),
                    startY = 0f,
                    endY = 64.dp.toPx()
                )
            )
        }
        .border(1.dp, stroke, shape)
}

/**
 * Low-level bento container ([BoxScope] content, caller lays out). Cards placed inside another
 * bento card automatically render as plate tiles. [warmGlow] / [glowLeft] default to a stable
 * per-call-site choice so a page alternates violet / copper corners like Media Studio.
 */
@Composable
fun BentoSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    warmGlow: Boolean? = null,
    glowLeft: Boolean? = null,
    borderColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val t = bentoTokens()
    val depth = LocalBentoDepth.current
    val nested = depth > 0
    val hash = currentCompositeKeyHash
    val warm = warmGlow ?: (((hash ushr 3) and 1) == 1)
    val left = glowLeft ?: (((hash ushr 5) and 1) == 1)
    Box(
        modifier
            .bentoSurface(
                t,
                shape = if (nested) BentoTileShape else BentoCardShape,
                warmGlow = warm,
                glowLeft = left,
                nested = nested,
                borderColor = borderColor
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides t.text,
            LocalBentoDepth provides depth + 1
        ) { content() }
    }
}

/** Section card with Media Studio's 12dp vertical rhythm. */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    warmGlow: Boolean? = null,
    glowLeft: Boolean? = null,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    BentoSurface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = contentPadding,
        warmGlow = warmGlow,
        glowLeft = glowLeft
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

/** Bento card with the icon-plate + 19sp title + muted subtitle header. */
@Composable
fun BentoSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    glyph: String? = null,
    warmGlow: Boolean? = null,
    glowLeft: Boolean? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BentoCard(modifier = modifier, warmGlow = warmGlow, glowLeft = glowLeft) {
        BentoSectionHeader(title = title, subtitle = subtitle, icon = icon, glyph = glyph, trailing = trailing)
        content()
    }
}

/**
 * Drop-in for Material `Card` / `ElevatedCard` call sites: same named parameters, but the
 * container is the bento surface. [shape], [colors], [elevation] and [border] are accepted
 * for source compatibility and intentionally ignored (the bento tokens own the look).
 */
@Composable
fun BentoCardCompat(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    BentoSurface(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Column(content = content)
    }
}

/** Inner rounded plate (rows, tiles, previews) inside a card. */
@Composable
fun BentoPlate(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    shape: Shape = BentoTileShape,
    content: @Composable BoxScope.() -> Unit
) {
    val t = bentoTokens()
    Box(
        modifier
            .clip(shape)
            .background(t.plate)
            .border(1.dp, t.plateBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding)
    ) {
        CompositionLocalProvider(LocalContentColor provides t.text) { content() }
    }
}

// ── Headers ──────────────────────────────────────────────────────────────────

/** Rounded plate holding an icon (or an emoji glyph). */
@Composable
fun BentoIconPlate(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.Unspecified
) {
    val t = bentoTokens()
    val shape = RoundedCornerShape(size / 3)
    Box(
        modifier.size(size).clip(shape).background(t.accentSoft).border(1.dp, t.plateBorder, shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) t.accent else tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun BentoGlyphPlate(glyph: String, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val t = bentoTokens()
    val shape = RoundedCornerShape(size / 3)
    Box(
        modifier.size(size).clip(shape).background(t.accentSoft).border(1.dp, t.plateBorder, shape),
        contentAlignment = Alignment.Center
    ) { Text(glyph, fontSize = (size.value * 0.45f).sp) }
}

/** Card header: icon plate + 19sp semibold title + muted subtitle (+ optional trailing). */
@Composable
fun BentoSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    glyph: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val t = bentoTokens()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            icon != null -> { BentoIconPlate(icon); Spacer(Modifier.width(12.dp)) }
            !glyph.isNullOrEmpty() -> { BentoGlyphPlate(glyph); Spacer(Modifier.width(12.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = t.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = t.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Page header: optional badge, 28sp semibold title, muted subtitle. */
@Composable
fun BentoPageTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: (@Composable () -> Unit)? = null
) {
    val t = bentoTokens()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (badge != null) {
            badge()
            Spacer(Modifier.height(4.dp))
        }
        Text(
            title, color = t.text, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = t.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Drop-in replacement for a transparent `TopAppBar` on bento pages: a row with the
 * navigation icon (on a round plate) and the actions, then the big page title.
 * Applies the status-bar inset itself, like TopAppBar does.
 */
@Composable
fun BentoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val t = bentoTokens()
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (navigationIcon != null) {
                Box(
                    Modifier.clip(CircleShape).background(t.plate).border(1.dp, t.plateBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides t.text) { navigationIcon() }
                }
            }
            Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalContentColor provides t.text2) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        BentoPageTitle(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp)
        )
    }
}

/** Field label inside a card. */
@Composable
fun BentoLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = bentoTokens().text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

/** Muted helper text inside a card. */
@Composable
fun BentoHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = bentoTokens().muted, style = MaterialTheme.typography.bodySmall)
}

// ── Pills, chips, badges ────────────────────────────────────────────────────

/** Selectable pill: plate + hairline when idle, accent fill + on-accent ink when selected. */
@Composable
fun BentoChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    label: @Composable () -> Unit
) {
    val t = bentoTokens()
    val ink = if (selected) t.onAccent else t.text
    Row(
        modifier
            .clip(BentoPillShape)
            .background(if (selected) t.accentFill else t.plate)
            .border(1.dp, if (selected) t.accentFill else t.plateBorder, BentoPillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

@Composable
fun BentoChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) = BentoChip(selected, onClick, modifier, enabled, leadingIcon) { Text(text) }

/** Static pill (status / count). [tint] colours the label; the fill stays the plate. */
@Composable
fun BentoPill(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val t = bentoTokens()
    val ink = if (tint == Color.Unspecified) t.text2 else tint
    Row(
        modifier
            .clip(BentoPillShape)
            .background(t.plate)
            .border(1.dp, t.plateBorder, BentoPillShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pill badge with a coloured status dot, e.g. "● Maya media pack". */
@Composable
fun BentoBadge(text: String, modifier: Modifier = Modifier, dot: Color = Color.Unspecified) {
    val t = bentoTokens()
    Row(
        modifier
            .clip(BentoPillShape)
            .background(t.plate)
            .border(1.dp, t.plateBorder, BentoPillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (dot == Color.Unspecified) t.success else dot))
        Spacer(Modifier.width(8.dp))
        Text(text, color = t.text2, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Buttons ─────────────────────────────────────────────────────────────────

@Composable
fun bentoPrimaryButtonColors(): ButtonColors = bentoTokens().let { t ->
    ButtonDefaults.buttonColors(
        containerColor = t.accentFill, contentColor = t.onAccent,
        disabledContainerColor = t.plate, disabledContentColor = t.muted
    )
}

@Composable
fun bentoSecondaryButtonColors(): ButtonColors = bentoTokens().let { t ->
    ButtonDefaults.outlinedButtonColors(
        containerColor = t.plate, contentColor = t.text,
        disabledContainerColor = t.plate, disabledContentColor = t.muted
    )
}

@Composable
fun bentoSecondaryBorder(): BorderStroke = BorderStroke(1.dp, bentoTokens().plateBorder)

/** Primary pill: saturated accent fill, on-accent label. */
@Composable
fun BentoPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = BentoPillShape,
        colors = bentoPrimaryButtonColors(), content = content
    )
}

/** Secondary pill: plate fill + hairline + primary ink. */
@Composable
fun BentoSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = BentoPillShape,
        border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(), content = content
    )
}

// ── Stats ───────────────────────────────────────────────────────────────────

/** Plate tile with a big number (28sp), optional unit and a muted caption. */
@Composable
fun BentoStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    valueColor: Color = Color.Unspecified
) {
    val t = bentoTokens()
    BentoPlate(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value, color = if (valueColor == Color.Unspecified) t.text else valueColor,
                    fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(unit, color = t.text2, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Text(label, color = t.text2, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!caption.isNullOrBlank()) {
                Text(caption, color = t.muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Control colours ─────────────────────────────────────────────────────────

@Composable
fun bentoSwitchColors(): SwitchColors = bentoTokens().let { t ->
    SwitchDefaults.colors(
        checkedThumbColor = t.onAccent, checkedTrackColor = t.accentFill, checkedBorderColor = t.accentFill,
        uncheckedThumbColor = t.muted, uncheckedTrackColor = t.plate, uncheckedBorderColor = t.plateBorder
    )
}

@Composable
fun bentoSliderColors(): SliderColors = bentoTokens().let { t ->
    SliderDefaults.colors(
        thumbColor = t.accentFill,
        activeTrackColor = t.accentFill,
        inactiveTrackColor = t.plate,
        activeTickColor = t.onAccent.copy(alpha = 0.6f),
        inactiveTickColor = t.muted.copy(alpha = 0.6f)
    )
}

@Composable
fun bentoFieldColors(): TextFieldColors = bentoTokens().let { t ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text, unfocusedTextColor = t.text,
        focusedContainerColor = t.plate, unfocusedContainerColor = t.plate,
        focusedBorderColor = t.accent, unfocusedBorderColor = t.plateBorder,
        focusedLabelColor = t.accent, unfocusedLabelColor = t.muted,
        focusedPlaceholderColor = t.muted, unfocusedPlaceholderColor = t.muted,
        focusedLeadingIconColor = t.accent, unfocusedLeadingIconColor = t.text2,
        focusedTrailingIconColor = t.accent, unfocusedTrailingIconColor = t.text2,
        cursorColor = t.accent
    )
}

/** Plate text-field shape (14dp). */
val BentoFieldShape = BentoPlateShape
