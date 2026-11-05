package com.jarvis.assistant.maya.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/** Small styling helpers shared by the Maya screens (Inter type, contrast-safe accents, monograms). */
object MayaUi {

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private var interCache: Array<Typeface?> = arrayOfNulls(4)

    /** Inter at [weight] 400 / 500 / 600 / 700 (falls back to the system face). */
    fun inter(context: Context, weight: Int = 400): Typeface {
        val idx = when {
            weight >= 700 -> 3
            weight >= 600 -> 2
            weight >= 500 -> 1
            else -> 0
        }
        interCache[idx]?.let { return it }
        val res = when (idx) {
            3 -> R.font.inter_bold
            2 -> R.font.inter_semibold
            1 -> R.font.inter_medium
            else -> R.font.inter_regular
        }
        val tf = try {
            ResourcesCompat.getFont(context, res)
        } catch (_: Throwable) {
            null
        } ?: if (idx >= 2) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        interCache[idx] = tf
        return tf
    }

    fun attr(context: Context, attr: Int, fallback: Int): Int = ThemeManager.attrColor(context, attr, fallback)

    fun textPrimary(context: Context) = attr(context, R.attr.themeTextPrimary, MayaPalette.TEXT)
    fun textSecondary(context: Context) = attr(context, R.attr.themeTextSecondary, MayaPalette.TEXT_SECONDARY)
    fun textMuted(context: Context) = attr(context, R.attr.themeTextMuted, MayaPalette.TEXT_MUTED)
    fun accent(context: Context) = attr(context, R.attr.themeAccent, MayaPalette.PRIMARY)

    /**
     * Ink that stays readable on the accent button fill (bg_maya_button = ?attr/themeAccentFill).
     * Uses the theme's own ?attr/themeOnAccent (contrast-audited per theme, 2026-11-05).
     */
    fun onAccent(context: Context): Int = com.jarvis.assistant.util.ThemeManager.onAccentColor(context)

    @Suppress("unused")
    private fun legacyOnAccent(context: Context): Int {
        val acc = accent(context) or 0xFF000000.toInt()
        val bg = attr(context, R.attr.themeBg, MayaPalette.BG) or 0xFF000000.toInt()
        val white = Color.WHITE
        return when {
            MayaPalette.contrastRatio(white, acc) >= 3.0 -> white
            MayaPalette.contrastRatio(bg, acc) >= MayaPalette.contrastRatio(0xFF0B0B10.toInt(), acc) -> bg
            else -> 0xFF0B0B10.toInt()
        }
    }

    /** Style a TextView/Button as the filled accent button with contrast-safe text. */
    fun styleAccentButton(view: TextView) {
        val ctx = view.context
        view.setBackgroundResource(R.drawable.bg_maya_button)
        view.backgroundTintList = null
        view.setTextColor(onAccent(ctx))
        view.typeface = inter(ctx, 600)
        view.isAllCaps = false
        view.letterSpacing = 0.01f
    }

    fun styleOutlineButton(view: TextView) {
        val ctx = view.context
        view.setBackgroundResource(R.drawable.bg_maya_button_outline)
        view.backgroundTintList = null
        view.setTextColor(textPrimary(ctx))
        view.typeface = inter(ctx, 600)
        view.isAllCaps = false
    }

    fun text(context: Context, value: CharSequence, sizeSp: Float, weight: Int = 400, color: Int = textPrimary(context)): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = inter(context, weight)
            setTextColor(color)
            includeFontPadding = false
        }

    /** Circular gradient monogram for a character without artwork. */
    fun monogram(context: Context, character: MayaCharacter, sizeDp: Float): View {
        val size = dp(context, sizeDp)
        val tint = MayaPalette.monogramColor(character.id)
        val frame = FrameLayout(context)
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(MayaPalette.mix(tint, Color.WHITE, 0.12f), MayaPalette.mix(tint, MayaPalette.VIOLET, 0.55f))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(context, 1.5f), MayaPalette.withAlpha(Color.WHITE, 0.35f))
        }
        val letter = TextView(context).apply {
            text = character.monogram
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.42f)
            typeface = inter(context, 700)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(dp(context, 6f).toFloat(), 0f, 0f, MayaPalette.withAlpha(tint, 0.9f))
        }
        frame.addView(letter, FrameLayout.LayoutParams(size, size))
        frame.layoutParams = FrameLayout.LayoutParams(size, size)
        return frame
    }

    fun badge(context: Context, label: String): TextView = text(context, label, 10f, 700, accent(context)).apply {
        setBackgroundResource(R.drawable.bg_maya_badge)
        setPadding(dp(context, 6f), dp(context, 2f), dp(context, 6f), dp(context, 2f))
        letterSpacing = 0.06f
        if (label.contains("18")) {
            setTextColor(MayaPalette.PINK)
            backgroundTintList = ColorStateList.valueOf(MayaPalette.withAlpha(MayaPalette.PINK, 0.25f))
        }
    }
}
