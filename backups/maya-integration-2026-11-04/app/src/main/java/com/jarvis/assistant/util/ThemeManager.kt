package com.jarvis.assistant.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

object ThemeManager {

    /** AndroLLM's own looks, offered as first-class Jarvis themes. */
    const val THEME_ANDROLLM_NIGHT = "androllm_night"
    const val THEME_ANDROLLM_PARCHMENT = "androllm_parchment"

    /** Resolved theme: XML style, window/background colour and whether it is a light theme. */
    data class ThemeSpec(val styleRes: Int, val bgColor: Int, val isLight: Boolean)

    /** The currently selected (normalised, lower-case) theme key. */
    fun currentThemeKey(): String {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        return (prefs?.appTheme ?: "dark").lowercase()
    }

    /** Resolve the [ThemeSpec] for the theme currently stored in preferences. */
    fun currentThemeSpec(): ThemeSpec {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        return resolveThemeSpec(prefs?.appTheme ?: "dark", prefs?.customThemeColor ?: "#22D3EE")
    }

    /** True when the active Jarvis theme is a light theme (dark text on a light ground). */
    fun isLightTheme(): Boolean = currentThemeSpec().isLight

    /**
     * Apply the selected theme to the activity and optionally refresh the supplied root view.
     * Also applies the optional glass blur effect if enabled.
     */
    fun applyThemeAndBackground(activity: Activity, rootView: View? = null) {
        applyTheme(activity)
        rootView?.let { applyDynamicBackground(it) }
        if (FrostedGlassEffect.isBlurEnabled()) {
            FrostedGlassEffect.applyWindowBlur(activity.window)
        }
    }

    /** Determine if a color is considered light (for contrast decisions). */
    fun isLight(colorInt: Int): Boolean {
        val r = Color.red(colorInt) / 255.0
        val g = Color.green(colorInt) / 255.0
        val b = Color.blue(colorInt) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 0.55
    }

    /** Accent colour used for UI elements (buttons, highlights, icons, badges). */
    fun getThemeAccentColor(context: Context? = null): Int {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "dark"
        val customHex = prefs?.customThemeColor ?: "#22D3EE"

        return when (themeKey.lowercase()) {
            "pearl_white", "white", "light" -> Color.parseColor("#0F172A")
            "white_dark", "monochrome" -> Color.parseColor("#FFFFFF")
            "stealth", "black", "oled" -> Color.parseColor("#FFFFFF")
            "gold", "amber" -> Color.parseColor("#F59E0B")
            "emerald", "green", "lime" -> Color.parseColor("#10B981")
            "pink", "rose", "jarvis_rose" -> Color.parseColor("#EC4899")
            "orange" -> Color.parseColor("#FF7700")
            "indigo" -> Color.parseColor("#6366F1")
            "red", "ruby", "crimson", "arc_red", "crimson_ruby" -> Color.parseColor("#FF4D5E")
            "amethyst", "purple", "neon_purple" -> Color.parseColor("#C084FC")
            "sunset" -> Color.parseColor("#FF6A3D")
            "dark", "obsidian" -> Color.parseColor("#00F0FF")
            THEME_ANDROLLM_NIGHT -> Color.parseColor("#7A5CFF")
            THEME_ANDROLLM_PARCHMENT -> Color.parseColor("#D97757")
            "custom" -> {
                try {
                    val parsed = Color.parseColor(customHex)
                    parsed
                } catch (_: Exception) {
                    Color.parseColor("#00F0FF")
                }
            }
            else -> Color.parseColor("#00F0FF")
        }
    }

    fun getThemeAccentHex(): String {
        val color = getThemeAccentColor()
        return String.format("#%06X", (0xFFFFFF and color))
    }

    /** Background colour for the selected theme (used for windows, surfaces, etc.). */
    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        return when (themeKey.lowercase()) {
            "pearl_white", "white", "light" -> Color.parseColor("#F8FAFC")
            "sunset" -> Color.parseColor("#FFF0EA")
            "stealth", "black", "oled" -> Color.parseColor("#000000")
            "white_dark", "monochrome" -> Color.parseColor("#07070A")
            "gold", "amber" -> Color.parseColor("#0A0802")
            "emerald", "green", "lime" -> Color.parseColor("#020A06")
            "pink", "rose", "jarvis_rose" -> Color.parseColor("#0E0308")
            "orange" -> Color.parseColor("#0E0602")
            "indigo" -> Color.parseColor("#050614")
            "red", "ruby", "crimson", "arc_red", "crimson_ruby" -> Color.parseColor("#140407")
            "amethyst", "purple", "neon_purple" -> Color.parseColor("#0B061A")
            "dark", "obsidian" -> Color.parseColor("#060812")
            THEME_ANDROLLM_NIGHT -> Color.parseColor("#000000")
            THEME_ANDROLLM_PARCHMENT -> Color.parseColor("#F5F4ED")
            "custom" -> {
                try {
                    val parsed = Color.parseColor(customHex ?: "#060812")
                    val hsv = FloatArray(3)
                    Color.colorToHSV(parsed, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (s < 0.15f) {
                        if (v > 0.65f) Color.parseColor("#F8FAFC") else Color.parseColor("#000000")
                    } else {
                        // Hue-based background
                        val h = hsv[0]
                        when {
                            h in 16f..45f -> Color.parseColor("#0E0602") // orange
                            h in 46f..65f -> Color.parseColor("#0A0802") // gold
                            h in 66f..165f -> Color.parseColor("#020A06") // emerald
                            h in 166f..205f -> Color.parseColor("#060812") // cyan
                            h in 206f..255f -> Color.parseColor("#050614") // indigo
                            h in 256f..295f -> Color.parseColor("#0B061A") // amethyst
                            h in 296f..344f -> Color.parseColor("#0E0308") // pink
                            else -> Color.parseColor("#140407") // red
                        }
                    }
                } catch (_: Exception) {
                    Color.parseColor("#070914")
                }
            }
            else -> Color.parseColor("#070914")
        }
    }

    /** Map a theme key (+ custom hex) to its XML style, background colour and light/dark flag. */
    fun resolveThemeSpec(rawThemeKey: String, customHex: String?): ThemeSpec {
        val themeKey = rawThemeKey.lowercase()
        val hex = customHex ?: "#00F0FF"
        val (themeRes, bgColor, isLight) = when (themeKey) {
            "pearl_white", "white", "light" ->
                Triple(R.style.Theme_JarvisAssistant_PearlWhite, Color.parseColor("#F8FAFC"), true)

            "sunset" ->
                Triple(R.style.Theme_JarvisAssistant_Sunset, Color.parseColor("#FFF0EA"), true)

            "stealth", "black", "oled" ->
                Triple(R.style.Theme_JarvisAssistant_Stealth, Color.parseColor("#000000"), false)

            "white_dark", "monochrome" ->
                Triple(R.style.Theme_JarvisAssistant_WhiteDark, Color.parseColor("#07070A"), false)

            "gold", "amber" ->
                Triple(R.style.Theme_JarvisAssistant_Gold, Color.parseColor("#0A0802"), false)

            "emerald", "green", "lime" ->
                Triple(R.style.Theme_JarvisAssistant_Emerald, Color.parseColor("#020A06"), false)

            "pink", "rose", "jarvis_rose" ->
                Triple(R.style.Theme_JarvisAssistant_Pink, Color.parseColor("#0E0308"), false)

            "orange" ->
                Triple(R.style.Theme_JarvisAssistant_Orange, Color.parseColor("#0E0602"), false)

            "indigo" ->
                Triple(R.style.Theme_JarvisAssistant_Indigo, Color.parseColor("#050614"), false)

            "red", "ruby", "crimson", "arc_red", "crimson_ruby" ->
                Triple(R.style.Theme_JarvisAssistant_Red, Color.parseColor("#140407"), false)

            "amethyst", "purple", "neon_purple" ->
                Triple(R.style.Theme_JarvisAssistant_Amethyst, Color.parseColor("#0B061A"), false)

            "dark", "obsidian" ->
                Triple(R.style.Theme_JarvisAssistant_Dark, Color.parseColor("#060812"), false)

            THEME_ANDROLLM_NIGHT ->
                Triple(R.style.Theme_JarvisAssistant_AndroLlmNight, Color.parseColor("#000000"), false)

            THEME_ANDROLLM_PARCHMENT ->
                Triple(R.style.Theme_JarvisAssistant_AndroLlmParchment, Color.parseColor("#F5F4ED"), true)

            "custom" -> {
                try {
                    val parsed = Color.parseColor(hex)
                    val hsv = FloatArray(3)
                    Color.colorToHSV(parsed, hsv)
                    val s = hsv[1]
                    val v = hsv[2]
                    if (s < 0.15f) {
                        if (v > 0.65f) {
                            Triple(R.style.Theme_JarvisAssistant_PearlWhite, Color.parseColor("#F8FAFC"), true)
                        } else {
                            Triple(R.style.Theme_JarvisAssistant_Stealth, Color.parseColor("#000000"), false)
                        }
                    } else {
                        val h = hsv[0]
                        when {
                            h in 16f..45f -> Triple(R.style.Theme_JarvisAssistant_Orange, Color.parseColor("#0E0602"), false)
                            h in 46f..65f -> Triple(R.style.Theme_JarvisAssistant_Gold, Color.parseColor("#0A0802"), false)
                            h in 66f..165f -> Triple(R.style.Theme_JarvisAssistant_Emerald, Color.parseColor("#020A06"), false)
                            h in 166f..205f -> Triple(R.style.Theme_JarvisAssistant_Dark, Color.parseColor("#060812"), false)
                            h in 206f..255f -> Triple(R.style.Theme_JarvisAssistant_Indigo, Color.parseColor("#050614"), false)
                            h in 256f..295f -> Triple(R.style.Theme_JarvisAssistant_Amethyst, Color.parseColor("#0B061A"), false)
                            h in 296f..344f -> Triple(R.style.Theme_JarvisAssistant_Pink, Color.parseColor("#0E0308"), false)
                            else -> Triple(R.style.Theme_JarvisAssistant_Red, Color.parseColor("#140407"), false)
                        }
                    }
                } catch (_: Exception) {
                    Triple(R.style.Theme_JarvisAssistant_Dark, Color.parseColor("#060812"), false)
                }
            }

            else -> Triple(R.style.Theme_JarvisAssistant_Dark, Color.parseColor("#060812"), false)
        }
        return ThemeSpec(themeRes, bgColor, isLight)
    }

    /** Apply the theme resources and window colours. */
    fun applyTheme(activity: Activity) {
        val spec = currentThemeSpec()
        val themeRes = spec.styleRes
        val bgColor = spec.bgColor
        val isLight = spec.isLight

        try {
            activity.setTheme(themeRes)
            activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
            activity.window.decorView.setBackgroundColor(bgColor)
            @Suppress("DEPRECATION")
            try {
                activity.window.statusBarColor = bgColor
                activity.window.navigationBarColor = bgColor
            } catch (_: Throwable) {}

            try {
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                controller.isAppearanceLightStatusBars = isLight
                controller.isAppearanceLightNavigationBars = isLight
            } catch (_: Throwable) {}

            try { activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor) } catch (_: Throwable) {}
            activity.window.decorView.post {
                try { activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            android.util.Log.e("ThemeManager", "Error applying theme to ${activity.localClassName}", e)
        }
    }

    /**
     * Resolve a theme colour attribute (e.g. R.attr.themeTextPrimary) from [context]'s theme.
     * Use this instead of hardcoded Color.parseColor(...) for surfaces/text so code-driven
     * colours follow the active Jarvis theme. Returns [fallback] if the attr is missing.
     */
    @JvmStatic
    fun attrColor(context: Context, attr: Int, fallback: Int = Color.GRAY): Int {
        val tv = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return try {
            if (tv.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                tv.data
            } else if (tv.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
            } else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** Apply background colour to any view based on the current theme. */
    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "dark"
        val customHex = prefs?.customThemeColor ?: "#22D3EE"
        val color = getThemeBgColor(themeKey, customHex)
        view.setBackgroundColor(color)
    }
}
