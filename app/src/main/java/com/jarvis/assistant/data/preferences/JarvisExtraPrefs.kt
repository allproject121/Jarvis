package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Jarvis assistant extras: speech engine for the non-Gemini voice paths (Edge neural voices),
 * Dynamic Island overlay and the Jarvis Chat AI mode.
 *
 * Stored in Jarvis' own `jarvis_prefs` file with dedicated keys so it never collides with
 * [AppPreferences]. Everything optional is OFF by default:
 *  - speech engine = Jarvis default (Android TTS / PocketTTS)
 *  - Dynamic Island = off
 *  - chat mode = Jarvis (no preset)
 *
 * Gemini Live never reads these values - it keeps speaking with its own audio.
 */
object JarvisExtraPrefs {

    private const val PREF_NAME = "jarvis_prefs"

    const val TTS_ENGINE_JARVIS = "jarvis"
    const val TTS_ENGINE_EDGE = "edge"

    const val DEFAULT_EDGE_VOICE = "en-IN-NeerjaNeural"

    private const val KEY_TTS_ENGINE = "key_extra_tts_engine"
    private const val KEY_EDGE_VOICE = "key_extra_edge_voice"
    private const val KEY_EDGE_RATE = "key_extra_edge_rate_percent"
    private const val KEY_EDGE_PITCH = "key_extra_edge_pitch_hz"
    private const val KEY_DYNAMIC_ISLAND = "key_extra_dynamic_island_enabled"
    private const val KEY_CHAT_AI_MODE = "key_extra_chat_ai_mode"

    private val _dynamicIslandEnabled = MutableStateFlow(false)

    /** Live value of the Dynamic Island toggle (updated on every write through this object). */
    val dynamicIslandEnabledFlow: StateFlow<Boolean> = _dynamicIslandEnabled.asStateFlow()

    @Volatile private var loaded = false

    private fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!loaded) {
            loaded = true
            _dynamicIslandEnabled.value = p.getBoolean(KEY_DYNAMIC_ISLAND, false)
        }
        return p
    }

    // ── Speech engine (non-Gemini paths only) ───────────────────────────────

    fun ttsEngine(context: Context): String =
        prefs(context).getString(KEY_TTS_ENGINE, TTS_ENGINE_JARVIS) ?: TTS_ENGINE_JARVIS

    fun setTtsEngine(context: Context, engine: String) {
        val value = if (engine == TTS_ENGINE_EDGE) TTS_ENGINE_EDGE else TTS_ENGINE_JARVIS
        prefs(context).edit().putString(KEY_TTS_ENGINE, value).apply()
    }

    fun isEdgeTtsSelected(context: Context): Boolean = ttsEngine(context) == TTS_ENGINE_EDGE

    fun edgeVoice(context: Context): String =
        prefs(context).getString(KEY_EDGE_VOICE, DEFAULT_EDGE_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_EDGE_VOICE

    fun setEdgeVoice(context: Context, voice: String) {
        prefs(context).edit().putString(KEY_EDGE_VOICE, voice.trim()).apply()
    }

    /** Speaking-rate offset in percent (-50..+50). */
    fun edgeRatePercent(context: Context): Int = prefs(context).getInt(KEY_EDGE_RATE, 0).coerceIn(-50, 50)

    fun setEdgeRatePercent(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_EDGE_RATE, value.coerceIn(-50, 50)).apply()
    }

    /** Pitch offset in Hz (-30..+30). */
    fun edgePitchHz(context: Context): Int = prefs(context).getInt(KEY_EDGE_PITCH, 0).coerceIn(-30, 30)

    fun setEdgePitchHz(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_EDGE_PITCH, value.coerceIn(-30, 30)).apply()
    }

    // ── Dynamic Island ──────────────────────────────────────────────────────

    fun isDynamicIslandEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DYNAMIC_ISLAND, false)

    fun setDynamicIslandEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_ISLAND, enabled).apply()
        _dynamicIslandEnabled.value = enabled
    }

    /** Makes [dynamicIslandEnabledFlow] reflect the stored value (call once at startup). */
    fun warmUp(context: Context) {
        _dynamicIslandEnabled.value = prefs(context).getBoolean(KEY_DYNAMIC_ISLAND, false)
    }

    // ── Jarvis Chat AI mode ─────────────────────────────────────────────────

    fun chatAiMode(context: Context): String =
        prefs(context).getString(KEY_CHAT_AI_MODE, "") ?: ""

    fun setChatAiMode(context: Context, modeId: String) {
        prefs(context).edit().putString(KEY_CHAT_AI_MODE, modeId).apply()
    }
}
