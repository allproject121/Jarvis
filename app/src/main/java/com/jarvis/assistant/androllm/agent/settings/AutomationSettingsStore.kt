package com.jarvis.assistant.androllm.agent.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.androLlmAgentToolsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "androllm_agent_automation_preferences"
)

/**
 * Persists [AutomationSettings] in DataStore. Same pattern as the voice
 * settings store: a private preferences file owned by this module.
 *
 * Jarvis port: Hilt singleton → [getInstance]. Adds [snapshot], an in-memory
 * mirror kept fresh by a collector, so synchronous callers (e.g.
 * JarvisToolDispatcher.getAvailableToolSchemas) can honour the per-tool
 * toggles without blocking. The defaults (tool calling ON, nothing disabled,
 * HIGH_RISK confirmations) mean an untouched install changes nothing.
 */
class AutomationSettingsStore(
    private val context: Context
) {

    private object Keys {
        val TOOL_CALLING_ENABLED = booleanPreferencesKey("tool_calling_enabled")
        val CONFIRMATION_MODE = stringPreferencesKey("confirmation_mode")
        val DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val VOICE_CONFIRMATIONS = booleanPreferencesKey("voice_confirmations")
        val MAX_TOOL_ROUNDS = intPreferencesKey("max_tool_rounds")
        val SHOW_TOOL_ACTIVITY = booleanPreferencesKey("show_tool_activity")
    }

    private val dataStore: DataStore<Preferences> = context.androLlmAgentToolsDataStore

    val settings: Flow<AutomationSettings> = dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            val defaults = AutomationSettings()
            AutomationSettings(
                toolCallingEnabled = p[Keys.TOOL_CALLING_ENABLED] ?: defaults.toolCallingEnabled,
                confirmationMode = runCatching {
                    ConfirmationMode.valueOf(p[Keys.CONFIRMATION_MODE] ?: defaults.confirmationMode.name)
                }.getOrDefault(defaults.confirmationMode),
                disabledTools = p[Keys.DISABLED_TOOLS] ?: defaults.disabledTools,
                voiceConfirmations = p[Keys.VOICE_CONFIRMATIONS] ?: defaults.voiceConfirmations,
                maxToolRounds = (p[Keys.MAX_TOOL_ROUNDS] ?: defaults.maxToolRounds).coerceIn(1, 6),
                showToolActivity = p[Keys.SHOW_TOOL_ACTIVITY] ?: defaults.showToolActivity
            )
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(AutomationSettings())

    /** Last known settings (defaults until the first DataStore read lands). */
    val snapshot: StateFlow<AutomationSettings> = _snapshot.asStateFlow()

    /** Starts the in-memory mirror (done once for the shared [getInstance] store). */
    private fun startMirror() {
        scope.launch { settings.collect { _snapshot.value = it } }
    }

    suspend fun current(): AutomationSettings = settings.first().also { _snapshot.value = it }

    /** Non-suspending read of the mirrored settings (may lag a write by one emission). */
    fun currentBlocking(): AutomationSettings = _snapshot.value

    /** Atomically applies [transform] to the persisted settings. */
    suspend fun update(transform: (AutomationSettings) -> AutomationSettings) {
        dataStore.edit { p ->
            val next = transform(current())
            p[Keys.TOOL_CALLING_ENABLED] = next.toolCallingEnabled
            p[Keys.CONFIRMATION_MODE] = next.confirmationMode.name
            p[Keys.DISABLED_TOOLS] = next.disabledTools
            p[Keys.VOICE_CONFIRMATIONS] = next.voiceConfirmations
            p[Keys.MAX_TOOL_ROUNDS] = next.maxToolRounds
            p[Keys.SHOW_TOOL_ACTIVITY] = next.showToolActivity
            _snapshot.value = next
        }
    }

    suspend fun setToolCallingEnabled(enabled: Boolean) {
        update { it.copy(toolCallingEnabled = enabled) }
    }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        update {
            it.copy(
                disabledTools = if (enabled) it.disabledTools - toolName
                else it.disabledTools + toolName
            )
        }
    }

    suspend fun setConfirmationMode(mode: ConfirmationMode) {
        update { it.copy(confirmationMode = mode) }
    }

    suspend fun setMaxToolRounds(rounds: Int) {
        update { it.copy(maxToolRounds = rounds.coerceIn(1, 6)) }
    }

    companion object {
        @Volatile
        private var instance: AutomationSettingsStore? = null

        fun getInstance(context: Context): AutomationSettingsStore =
            instance ?: synchronized(this) {
                instance ?: AutomationSettingsStore(context.applicationContext).also {
                    it.startMirror()
                    instance = it
                }
            }

        /** Returns the store only if it was already created (never constructs it). */
        fun peek(): AutomationSettingsStore? = instance
    }
}
