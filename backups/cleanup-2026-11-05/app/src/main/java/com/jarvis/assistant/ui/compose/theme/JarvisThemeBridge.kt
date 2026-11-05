package com.jarvis.assistant.ui.compose.theme

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * Bridge between Jarvis' XML theme system (ThemeManager + `?attr/theme*` tokens)
 * and the Compose screens ported from AndroLLM.
 *
 * Every Compose screen reads [MaterialTheme.ledger] / [MaterialTheme.colorScheme];
 * this bridge builds both from the *active Jarvis theme* so Compose and XML UI
 * always wear the same colours (Pearl White, Sunset, Stealth, Gold, ...).
 *
 * The two AndroLLM themes ([ThemeManager.THEME_ANDROLLM_NIGHT] and
 * [ThemeManager.THEME_ANDROLLM_PARCHMENT]) use the exact original AndroLLM
 * ledger palettes instead of the derived mapping.
 */
@Immutable
data class JarvisComposePalette(
    val themeKey: String,
    val isLight: Boolean,
    val ledger: LedgerColors,
    val colorScheme: ColorScheme
)

object JarvisThemeBridge {

    /** Resolve the palette for the theme currently active in Jarvis. */
    fun resolve(context: Context): JarvisComposePalette {
        val key = ThemeManager.currentThemeKey()
        return when (key) {
            ThemeManager.THEME_ANDROLLM_NIGHT ->
                JarvisComposePalette(key, false, DarkLedger, ledgerColorScheme(DarkLedger, isLight = false))
            ThemeManager.THEME_ANDROLLM_PARCHMENT ->
                JarvisComposePalette(key, true, ParchmentLedger, ledgerColorScheme(ParchmentLedger, isLight = true))
            else -> {
                val tokens = readTokens(themedContext(context))
                val ledger = ledgerFromTokens(tokens)
                JarvisComposePalette(key, tokens.isLight, ledger, ledgerColorScheme(ledger, tokens.isLight))
            }
        }
    }

    // ── Token reading ────────────────────────────────────────────────────────

    /** Raw Jarvis theme tokens, read from `R.attr.theme*`. */
    private data class Tokens(
        val bg: Color,
        val surface: Color,
        val cardBg: Color,
        val cardBorder: Color,
        val textPrimary: Color,
        val textSecondary: Color,
        val textMuted: Color,
        val accent: Color,
        val accent2: Color,
        val accentLight: Color,
        val chipBg: Color,
        val inputBg: Color,
        val isLight: Boolean
    )

    /**
     * The context's own theme when it is a Jarvis-themed activity; otherwise
     * (application/service context, e.g. the voice overlay window) wrap it in
     * the style ThemeManager would apply so the attrs still resolve.
     */
    private fun themedContext(context: Context): Context {
        val probe = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.themeBg, probe, true)) {
            context
        } else {
            ContextThemeWrapper(context, ThemeManager.currentThemeSpec().styleRes)
        }
    }

    private fun Context.attrColor(attr: Int, fallback: Color): Color {
        val tv = TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return fallback
        return try {
            when {
                tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> Color(tv.data)
                tv.resourceId != 0 -> Color(ContextCompat.getColor(this, tv.resourceId))
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun readTokens(ctx: Context): Tokens {
        val spec = ThemeManager.currentThemeSpec()
        val bg = ctx.attrColor(R.attr.themeBg, Color(spec.bgColor)).copy(alpha = 1f)
        val light = bg.luminance() > 0.4f
        val defText = if (light) Color(0xFF0F172A) else Color(0xFFF8FAFC)
        val defAccent = Color(ThemeManager.getThemeAccentColor())
        return Tokens(
            bg = bg,
            surface = ctx.attrColor(R.attr.themeSurface, bg),
            cardBg = ctx.attrColor(R.attr.themeCardBg, bg),
            cardBorder = ctx.attrColor(R.attr.themeCardBorder, defText.copy(alpha = 0.18f)),
            textPrimary = ctx.attrColor(R.attr.themeTextPrimary, defText),
            textSecondary = ctx.attrColor(R.attr.themeTextSecondary, defText.copy(alpha = 0.72f)),
            textMuted = ctx.attrColor(R.attr.themeTextMuted, defText.copy(alpha = 0.5f)),
            accent = ctx.attrColor(R.attr.themeAccent, defAccent),
            accent2 = ctx.attrColor(R.attr.themeAccent2, defAccent),
            accentLight = ctx.attrColor(R.attr.themeAccentLight, defAccent.copy(alpha = 0.15f)),
            chipBg = ctx.attrColor(R.attr.themeChipBg, defText.copy(alpha = 0.08f)),
            inputBg = ctx.attrColor(R.attr.themeInputBg, bg),
            isLight = light
        )
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    /** Flatten a (possibly translucent) token onto [under] so Compose surfaces stay opaque. */
    private fun Color.over(under: Color): Color = if (alpha >= 0.999f) this else compositeOver(under)

    private fun readableOn(bg: Color): Color =
        if (bg.luminance() > 0.45f) Color(0xFF0B0B10) else Color.White

    /**
     * Push [accent] towards readable contrast against [bg]: on light grounds a
     * pale accent is darkened; on dark grounds a deep accent is lifted.
     */
    private fun readableAccent(accent: Color, bg: Color, isLight: Boolean, strength: Float): Color =
        if (isLight) lerp(accent, Color.Black, strength) else lerp(accent, Color.White, strength)

    private fun ledgerFromTokens(t: Tokens): LedgerColors {
        val isLight = t.isLight
        val bg = t.bg
        val ink = t.textPrimary.over(bg)
        val shade = if (isLight) Color.Black else Color.White

        val card = t.cardBg.over(bg)
        val surface = t.surface.over(bg)
        val raised = lerp(card, shade, if (isLight) 0.035f else 0.06f)
        val deep = lerp(bg, shade, if (isLight) 0.035f else 0.03f)
        val hairline = t.cardBorder.over(card)
        val hairlineSoft = lerp(hairline, card, 0.4f)

        // A pale accent (e.g. white on Monochrome) is fine on dark; a near-black
        // accent (Pearl White's navy) is fine on light — only nudge for contrast.
        val accent = t.accent.copy(alpha = 1f)
        val accentLum = accent.luminance()
        val glow = when {
            isLight && accentLum > 0.45f -> lerp(accent, Color.Black, 0.45f)
            isLight -> lerp(accent, Color.Black, 0.10f)
            accentLum < 0.12f -> lerp(accent, Color.White, 0.55f)
            else -> lerp(accent, Color.White, 0.35f)
        }
        val deepAccent = readableAccent(accent, bg, isLight, if (isLight) 0.25f else 0.2f)
        val wash = t.accentLight.over(card)

        return LedgerColors(
            deskNight = bg,
            deskNightRaised = lerp(bg, shade, if (isLight) 0.02f else 0.035f),
            deskWalnut = card,
            deskWalnutRaised = raised,
            deskWalnutDeep = if (surface == card) deep else lerp(surface, deep, 0.5f),
            deskPaper = ink,
            deskPaperDim = lerp(ink, t.textSecondary.over(bg), 0.3f),
            deskInk = t.textSecondary.over(bg),
            deskInkFaint = t.textMuted.over(bg),
            deskHairline = hairline,
            deskHairlineSoft = hairlineSoft,
            lampAmber = accent,
            lampGlow = glow,
            lampDeep = deepAccent,
            lampHalo = accent.copy(alpha = 0.25f),
            inkOnLamp = readableOn(accent),
            emberRed = if (isLight) Color(0xFFC7442F) else Color(0xFFF0665F),
            emberRedSoft = if (isLight) Color(0xFFFBE4DF) else Color(0xFF2A1512),
            emberRedHard = if (isLight) Color(0xFF8C2A1C) else Color(0xFFFFB4A0),
            emberOnRed = if (isLight) Color.White else Color.Black,
            terracottaSoft = wash,
            terracottaDeep = ink,
            cloudWhite = ink,
            cloudGlassSurface = card.copy(alpha = 0.8f),
            cloudGlassSurfaceVariant = raised.copy(alpha = 0.9f),
            cloudGlassBorder = hairline.copy(alpha = 0.6f),
            cloudGlassBorderHighlight = accent.copy(alpha = 0.2f),
            cloudMoonGlow = accent.copy(alpha = 0.25f),
            cloudParticleTint = accent.copy(alpha = 0.25f),
            revolutCyberCyan = glow,
            revolutGoldTier = t.textSecondary.over(bg),
            revolutNeonEmerald = if (isLight) Color(0xFF15803D) else Color(0xFF4ADE80),
            sunsetGlowAmber = glow
        )
    }

    /** Material3 scheme derived from a ledger, mirroring AndroLLM's own role mapping. */
    fun ledgerColorScheme(l: LedgerColors, isLight: Boolean): ColorScheme {
        val lowest = if (isLight) lerp(l.deskWalnut, Color.White, 0.5f) else lerp(l.deskNight, Color.Black, 0.5f)
        val highest = lerp(l.deskWalnutRaised, l.deskPaper, if (isLight) 0.04f else 0.05f)
        return if (isLight) {
            lightColorScheme(
                primary = l.lampAmber,
                onPrimary = l.inkOnLamp,
                primaryContainer = l.terracottaSoft,
                onPrimaryContainer = l.terracottaDeep,
                secondary = l.lampDeep,
                onSecondary = l.inkOnLamp,
                secondaryContainer = l.terracottaSoft,
                onSecondaryContainer = l.terracottaDeep,
                tertiary = l.lampGlow,
                onTertiary = l.inkOnLamp,
                tertiaryContainer = l.terracottaSoft,
                onTertiaryContainer = l.terracottaDeep,
                error = l.emberRed,
                onError = l.emberOnRed,
                errorContainer = l.emberRedSoft,
                onErrorContainer = l.emberRedHard,
                background = l.deskNight,
                onBackground = l.deskPaper,
                surface = l.deskWalnut,
                onSurface = l.deskPaper,
                surfaceVariant = l.deskWalnutDeep,
                onSurfaceVariant = l.deskInk,
                surfaceTint = l.lampAmber,
                inverseSurface = l.deskPaper,
                inverseOnSurface = l.deskNight,
                inversePrimary = l.lampGlow,
                surfaceBright = l.deskWalnutRaised,
                surfaceDim = l.deskNightRaised,
                surfaceContainerLowest = lowest,
                surfaceContainerLow = l.deskNightRaised,
                surfaceContainer = l.deskWalnut,
                surfaceContainerHigh = l.deskWalnutRaised,
                surfaceContainerHighest = highest,
                outline = l.deskHairline,
                outlineVariant = l.deskHairlineSoft
            )
        } else {
            darkColorScheme(
                primary = l.lampAmber,
                onPrimary = l.inkOnLamp,
                primaryContainer = l.terracottaSoft,
                onPrimaryContainer = l.terracottaDeep,
                secondary = l.lampGlow,
                onSecondary = l.inkOnLamp,
                secondaryContainer = l.terracottaSoft,
                onSecondaryContainer = l.terracottaDeep,
                tertiary = l.lampAmber,
                onTertiary = l.inkOnLamp,
                tertiaryContainer = l.terracottaSoft,
                onTertiaryContainer = l.terracottaDeep,
                error = l.emberRed,
                onError = l.emberOnRed,
                errorContainer = l.emberRedSoft,
                onErrorContainer = l.emberRedHard,
                background = l.deskNight,
                onBackground = l.deskPaper,
                surface = l.deskWalnut,
                onSurface = l.deskPaper,
                surfaceVariant = l.deskWalnutDeep,
                onSurfaceVariant = l.deskInk,
                surfaceTint = l.lampAmber,
                inverseSurface = l.deskPaper,
                inverseOnSurface = l.deskNight,
                inversePrimary = l.lampDeep,
                surfaceBright = l.deskNightRaised,
                surfaceDim = l.deskNight,
                surfaceContainerLowest = lowest,
                surfaceContainerLow = l.deskWalnutDeep,
                surfaceContainer = l.deskWalnut,
                surfaceContainerHigh = l.deskWalnutRaised,
                surfaceContainerHighest = highest,
                outline = l.deskHairline,
                outlineVariant = l.deskHairlineSoft
            )
        }
    }

    /** Current Jarvis theme key, safe to call before the app object is ready. */
    fun currentKey(): String = try {
        JarvisApp.instance.preferences.appTheme.lowercase()
    } catch (_: Exception) {
        "dark"
    }
}
