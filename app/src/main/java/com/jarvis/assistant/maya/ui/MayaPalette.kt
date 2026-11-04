package com.jarvis.assistant.maya.ui

import java.util.Locale
import kotlin.math.pow

/**
 * The Maya palette as plain ARGB ints plus small colour helpers.
 *
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM and
 * shared by the wallpaper engine, the overlay, the stage HTML query string and
 * the View layer alike. The values mirror `res/values/colors.xml` (`maya_*`)
 * and `Theme.JarvisAssistant.Maya`.
 */
object MayaPalette {
    const val BG = 0xFF14161C.toInt()
    const val BG_EDGE = 0xFF000000.toInt()
    const val SURFACE = 0xFF1A1D26.toInt()
    const val SURFACE_RAISED = 0xFF1F2330.toInt()
    const val HAIRLINE = 0xFF2A2F3C.toInt()
    const val TEXT = 0xFFF8FAFC.toInt()
    const val TEXT_SECONDARY = 0xFF94A3B8.toInt()
    const val TEXT_MUTED = 0xFF64748B.toInt()
    const val PRIMARY = 0xFF1E90FF.toInt()
    const val ACCENT = 0xFF4F7CFF.toInt()
    const val LIGHT_BLUE = 0xFF5FC8FF.toInt()
    const val SKY = 0xFF33B5FF.toInt()
    const val PERIWINKLE = 0xFF6EA8FF.toInt()
    const val CYAN_GLOW = 0xFF29E7E7.toInt()
    const val TINT_SOFT = 0xFFD6F0FF.toInt()
    const val TINT_SOFTER = 0xFFEAF6FF.toInt()
    const val VIOLET = 0xFF6C5CE7.toInt()
    const val VIOLET_LIGHT = 0xFF8B5CF6.toInt()
    const val SUCCESS = 0xFF34D399.toInt()
    const val WARNING = 0xFFFBBF24.toInt()
    const val WARNING_DEEP = 0xFFF59E0B.toInt()
    const val DANGER = 0xFFEF4444.toInt()
    const val DANGER_SOFT = 0xFFFF5A6E.toInt()
    const val ORANGE = 0xFFFF7A18.toInt()
    const val PINK = 0xFFFF4FD8.toInt()
    const val PINK_DEEP = 0xFFFF3DBE.toInt()

    /** Status colour for a conversation state key (idle / listening / thinking / speaking). */
    fun stateColor(stateKey: String): Int = when (stateKey.lowercase()) {
        "listening" -> SUCCESS
        "thinking" -> WARNING
        "speaking" -> LIGHT_BLUE
        else -> PRIMARY
    }

    /** Monogram tint for a character id, so cards without art still read distinctly. */
    fun monogramColor(characterId: String): Int = when (characterId.lowercase()) {
        "maya" -> PRIMARY
        "maya_pro" -> ACCENT
        "maya_intimate" -> PINK
        "friday" -> CYAN_GLOW
        "venom" -> VIOLET
        "jarvis" -> SKY
        else -> PERIWINKLE
    }

    // ── Hex parsing ──────────────────────────────────────────────────────────

    /**
     * Parse `#RGB`, `#RRGGBB`, `#AARRGGBB` (with or without the `#`) into ARGB.
     * Returns [fallback] for anything else instead of throwing, so a bad
     * preference value can never crash a wallpaper engine or overlay.
     */
    fun parseHex(hex: String?, fallback: Int = PRIMARY): Int {
        val raw = hex?.trim()?.removePrefix("#") ?: return fallback
        if (raw.isEmpty() || !raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return fallback
        return try {
            when (raw.length) {
                3 -> {
                    val r = raw[0].digitToInt(16) * 17
                    val g = raw[1].digitToInt(16) * 17
                    val b = raw[2].digitToInt(16) * 17
                    argb(255, r, g, b)
                }
                6 -> (0xFF000000L or raw.toLong(16)).toInt()
                8 -> raw.toLong(16).toInt()
                else -> fallback
            }
        } catch (_: NumberFormatException) {
            fallback
        }
    }

    /** `#RRGGBB` (alpha dropped), always upper-case. */
    fun toHex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** `#AARRGGBB`, always upper-case. */
    fun toHexWithAlpha(argb: Int): String = String.format(Locale.US, "#%08X", argb)

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    /** Same colour with [alpha01] (0..1) opacity. */
    fun withAlpha(argb: Int, alpha01: Float): Int {
        val a = (alpha01.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    /** Linear blend between two colours (t = 0 → [from], t = 1 → [to]). */
    fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + (b - a) * k + 0.5f).toInt().coerceIn(0, 255)
        return argb(ch(alpha(from), alpha(to)), ch(red(from), red(to)), ch(green(from), green(to)), ch(blue(from), blue(to)))
    }

    // ── Contrast (WCAG 2.x) ──────────────────────────────────────────────────

    private fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance 0 (black) .. 1 (white); alpha is ignored. */
    fun luminance(argb: Int): Double =
        0.2126 * linear(red(argb)) + 0.7152 * linear(green(argb)) + 0.0722 * linear(blue(argb))

    /** WCAG contrast ratio between two opaque colours, 1.0 .. 21.0. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** True when the colour reads as light (so dark ink belongs on top of it). */
    fun isLight(argb: Int): Boolean = luminance(argb) > 0.4

    /** Ink colour (Maya text or near-black) that is readable on [background]. */
    fun readableOn(background: Int): Int = if (isLight(background)) 0xFF0B0B10.toInt() else TEXT

    /** True when [fg] on [bg] meets WCAG AA for normal text (4.5:1). */
    fun meetsAa(fg: Int, bg: Int): Boolean = contrastRatio(fg, bg) >= 4.5
}
