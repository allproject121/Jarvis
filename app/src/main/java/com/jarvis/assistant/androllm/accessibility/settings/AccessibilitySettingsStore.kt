package com.jarvis.assistant.androllm.accessibility.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.jarvisAccessibilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "androllm_accessibility_preferences"
)

/**
 * Ported from AndroLLM (core/accessibility/settings/AccessibilitySettingsStore).
 * Persists [AccessibilitySettings] in its own DataStore file. Singleton via
 * [getInstance] (Jarvis has no DI).
 */
class AccessibilitySettingsStore private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AccessibilitySettingsStore? = null

        fun getInstance(context: Context): AccessibilitySettingsStore =
            instance ?: synchronized(this) {
                instance ?: AccessibilitySettingsStore(context.applicationContext).also { instance = it }
            }
    }

    private object Keys {
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll_into_view")
        val LLM_PLANNING = booleanPreferencesKey("llm_planning")
        val CONFIRM_HIGH_RISK = booleanPreferencesKey("confirm_high_risk")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val OCR_ENABLED = booleanPreferencesKey("ocr_enabled")
    }

    private val dataStore: DataStore<Preferences> = context.applicationContext.jarvisAccessibilityDataStore

    val settings: Flow<AccessibilitySettings> = dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p -> fromPrefs(p) }

    suspend fun current(): AccessibilitySettings =
        runCatching { settings.first() }.getOrDefault(AccessibilitySettings())

    /** Atomically applies [transform] to the persisted settings. */
    suspend fun update(transform: (AccessibilitySettings) -> AccessibilitySettings) {
        dataStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.AUTO_SCROLL] = next.autoScrollIntoView
            p[Keys.LLM_PLANNING] = next.llmPlanning
            p[Keys.CONFIRM_HIGH_RISK] = next.confirmHighRisk
            p[Keys.MAX_STEPS] = next.maxSteps.coerceIn(AccessibilitySettings.MIN_STEPS, AccessibilitySettings.MAX_STEPS)
            p[Keys.DEVELOPER_MODE] = next.developerMode
            p[Keys.OCR_ENABLED] = next.ocrEnabled
        }
    }

    private fun fromPrefs(p: Preferences): AccessibilitySettings {
        val d = AccessibilitySettings()
        return AccessibilitySettings(
            autoScrollIntoView = p[Keys.AUTO_SCROLL] ?: d.autoScrollIntoView,
            llmPlanning = p[Keys.LLM_PLANNING] ?: d.llmPlanning,
            confirmHighRisk = p[Keys.CONFIRM_HIGH_RISK] ?: d.confirmHighRisk,
            maxSteps = (p[Keys.MAX_STEPS] ?: d.maxSteps)
                .coerceIn(AccessibilitySettings.MIN_STEPS, AccessibilitySettings.MAX_STEPS),
            developerMode = p[Keys.DEVELOPER_MODE] ?: d.developerMode,
            ocrEnabled = p[Keys.OCR_ENABLED] ?: d.ocrEnabled
        )
    }
}
