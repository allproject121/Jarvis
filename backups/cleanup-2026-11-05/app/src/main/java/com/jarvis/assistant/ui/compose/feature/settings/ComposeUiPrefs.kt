package com.jarvis.assistant.ui.compose.feature.settings

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.ui.compose.model.ChatFontSize
import com.jarvis.assistant.ui.compose.model.ThemeMode
import com.jarvis.assistant.ui.compose.model.UiDensity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Appearance preferences for the Compose (AndroLLM) UI only. Stored in their
 * own SharedPreferences file ("compose_ui_prefs") so the classic Jarvis theme
 * keys are never touched.
 *
 * The host activity reads [themeMode], [density] and [fontSize] to configure
 * `AndroLLMTheme(themeMode = ...)` and `ProvideUiScale(density, fontSize)`.
 * [revision] increments on every write so a host can recompose live by
 * collecting it (`ComposeUiPrefs.revision.collectAsState()`).
 */
object ComposeUiPrefs {

    private const val PREFS_NAME = "compose_ui_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DENSITY = "ui_density"
    private const val KEY_FONT_SIZE = "chat_font_size"
    private const val KEY_REDUCE_MOTION = "reduce_motion"

    private val _revision = MutableStateFlow(0)

    /** Bumped after every successful write — observe to re-read the prefs. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    fun themeMode(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        bump()
    }

    fun density(context: Context): UiDensity {
        val raw = prefs(context).getString(KEY_DENSITY, UiDensity.DEFAULT.name) ?: UiDensity.DEFAULT.name
        return UiDensity.entries.firstOrNull { it.name == raw } ?: UiDensity.DEFAULT
    }

    fun setDensity(context: Context, density: UiDensity) {
        prefs(context).edit().putString(KEY_DENSITY, density.name).apply()
        bump()
    }

    fun fontSize(context: Context): ChatFontSize {
        val raw = prefs(context).getString(KEY_FONT_SIZE, ChatFontSize.MEDIUM.name) ?: ChatFontSize.MEDIUM.name
        return ChatFontSize.entries.firstOrNull { it.name == raw } ?: ChatFontSize.MEDIUM
    }

    fun setFontSize(context: Context, size: ChatFontSize) {
        prefs(context).edit().putString(KEY_FONT_SIZE, size.name).apply()
        bump()
    }

    /** Compose-only "reduce background motion" switch (atmospheric background). */
    fun reduceMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
        bump()
    }
}
