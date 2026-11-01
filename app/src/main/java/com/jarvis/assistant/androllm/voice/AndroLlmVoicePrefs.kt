package com.jarvis.assistant.androllm.voice

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.androllm.AndroLlmFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the optional sherpa-onnx voice configuration. */
data class AndroLlmVoiceConfig(
    val wakeWordEnabled: Boolean = false,
    val asrEnabled: Boolean = false,
    val ttsEnabled: Boolean = false,
    val wakeWordPackId: String = VoiceModelCatalog.KWS_GIGASPEECH.id,
    val asrPackId: String = VoiceModelCatalog.ASR_ZIPFORMER_EN.id,
    val ttsPackId: String = VoiceModelCatalog.TTS_VITS_LJS_INT8.id,
    /** Keyword-spotter sensitivity (0.1..1.0). Higher = more responsive, more false positives. */
    val wakeSensitivity: Float = 0.5f,
    /** Trailing silence (s) that ends a sherpa ASR utterance (endpoint rule 1). */
    val asrSilenceSeconds: Float = 2.0f,
    /** sherpa TTS speaking rate (0.5..2.0). */
    val ttsSpeed: Float = 1.0f
) {
    companion object {
        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 1.0f
        const val MIN_SILENCE = 0.5f
        const val MAX_SILENCE = 5.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}

/**
 * Persistence for the AndroLLM voice layer. The three on/off switches live in
 * [AndroLlmFlags] (shared contract, all default OFF); model selection and tuning
 * live in this module's own prefs file. [config] is kept in sync with both files
 * through SharedPreferences listeners, so a toggle flipped anywhere is observed.
 */
object AndroLlmVoicePrefs {

    private const val PREFS = "androllm_voice_prefs"
    private const val FLAG_PREFS = "androllm_feature_flags" // same file AndroLlmFlags uses

    private const val KEY_KWS_PACK = "kws_pack"
    private const val KEY_ASR_PACK = "asr_pack"
    private const val KEY_TTS_PACK = "tts_pack"
    private const val KEY_SENSITIVITY = "kws_sensitivity"
    private const val KEY_SILENCE = "asr_silence_s"
    private const val KEY_SPEED = "tts_speed"

    private val _config = MutableStateFlow(AndroLlmVoiceConfig())
    val config: StateFlow<AndroLlmVoiceConfig> = _config.asStateFlow()

    @Volatile private var appContext: Context? = null

    // Held strongly: SharedPreferences keeps listeners in a WeakHashMap.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        app.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        reload()
    }

    fun current(context: Context): AndroLlmVoiceConfig {
        init(context)
        return _config.value
    }

    fun setFlag(context: Context, key: String, enabled: Boolean) {
        init(context)
        AndroLlmFlags.setEnabled(context, key, enabled)
        reload()
    }

    fun selectPack(context: Context, pack: VoiceModelPack) {
        init(context)
        val key = when (pack.kind) {
            VoiceModelKind.WAKE_WORD -> KEY_KWS_PACK
            VoiceModelKind.ASR -> KEY_ASR_PACK
            VoiceModelKind.TTS -> KEY_TTS_PACK
        }
        prefs(context).edit().putString(key, pack.id).apply()
        reload()
    }

    fun setWakeSensitivity(context: Context, value: Float) {
        init(context)
        prefs(context).edit().putFloat(
            KEY_SENSITIVITY,
            value.coerceIn(AndroLlmVoiceConfig.MIN_SENSITIVITY, AndroLlmVoiceConfig.MAX_SENSITIVITY)
        ).apply()
        reload()
    }

    fun setAsrSilenceSeconds(context: Context, value: Float) {
        init(context)
        prefs(context).edit().putFloat(
            KEY_SILENCE,
            value.coerceIn(AndroLlmVoiceConfig.MIN_SILENCE, AndroLlmVoiceConfig.MAX_SILENCE)
        ).apply()
        reload()
    }

    fun setTtsSpeed(context: Context, value: Float) {
        init(context)
        prefs(context).edit().putFloat(
            KEY_SPEED,
            value.coerceIn(AndroLlmVoiceConfig.MIN_SPEED, AndroLlmVoiceConfig.MAX_SPEED)
        ).apply()
        reload()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun reload() {
        val ctx = appContext ?: return
        val p = prefs(ctx)
        val defaults = AndroLlmVoiceConfig()
        _config.value = AndroLlmVoiceConfig(
            wakeWordEnabled = AndroLlmFlags.isEnabled(ctx, AndroLlmFlags.SHERPA_WAKE_WORD),
            asrEnabled = AndroLlmFlags.isEnabled(ctx, AndroLlmFlags.SHERPA_ASR),
            ttsEnabled = AndroLlmFlags.isEnabled(ctx, AndroLlmFlags.SHERPA_TTS),
            wakeWordPackId = p.getString(KEY_KWS_PACK, null)
                ?.takeIf { VoiceModelCatalog.byId(it)?.kind == VoiceModelKind.WAKE_WORD }
                ?: defaults.wakeWordPackId,
            asrPackId = p.getString(KEY_ASR_PACK, null)
                ?.takeIf { VoiceModelCatalog.byId(it)?.kind == VoiceModelKind.ASR }
                ?: defaults.asrPackId,
            ttsPackId = p.getString(KEY_TTS_PACK, null)
                ?.takeIf { VoiceModelCatalog.byId(it)?.kind == VoiceModelKind.TTS }
                ?: defaults.ttsPackId,
            wakeSensitivity = p.getFloat(KEY_SENSITIVITY, defaults.wakeSensitivity),
            asrSilenceSeconds = p.getFloat(KEY_SILENCE, defaults.asrSilenceSeconds),
            ttsSpeed = p.getFloat(KEY_SPEED, defaults.ttsSpeed)
        )
    }
}
