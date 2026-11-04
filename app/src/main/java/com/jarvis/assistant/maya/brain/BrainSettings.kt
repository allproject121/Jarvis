package com.jarvis.assistant.maya.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sub-agent roles (Maya "Settings → Sub-agents"). */
enum class SubAgentRole(val id: String, val label: String, val blurb: String) {
    CODING("coding", "Coding", "Writes and edits code, scripts and files."),
    RESEARCH("research", "Research", "Web research, summaries and fact checks."),
    MISSION("mission", "Mission", "Plans and runs multi-step background jobs."),
    EYES("eyes", "Eyes (vision)", "Looks at photos and the screen; needs an image model.");

    companion object {
        fun byId(id: String?): SubAgentRole? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * Fully-resolved brain configuration for one request (main brain or a sub-agent role).
 */
data class BrainConfig(
    val provider: BrainProvider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val autoPick: Boolean = false,
    val think: Boolean = false
) {
    val isConfigured: Boolean
        get() = model.isNotBlank() && baseUrl.isNotBlank() && (apiKey.isNotBlank() || provider.customBaseUrl)

    /** Maya-style reason when [isConfigured] is false (Hinglish, spoken as-is). */
    fun missingReason(): String? = when {
        apiKey.isBlank() && !provider.customBaseUrl ->
            "Advanced Agentic Mode on hai par LLM ki API key nahi daali — Settings → Voice engine me key daalo."
        model.isBlank() || baseUrl.isBlank() ->
            "Advanced Agentic Mode on hai par LLM ka model ya URL khaali hai — Settings → Voice engine me bharo."
        else -> null
    }
}

/**
 * Settings for the Advanced Agentic pipeline (Maya "Voice engine" screen).
 *
 * Secrets (API keys) live in the EncryptedSharedPreferences file `maya_brain_secure`;
 * everything else in the plain `maya_brain_prefs` file. Never touches Jarvis's
 * AppPreferences; the Gemini Live key is only *read* as a fallback for Gemini TTS.
 */
class BrainSettings private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            SECURE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure brain-key storage could not be initialized", e)
        null
    }

    private val _revision = MutableStateFlow(0L)

    /** Bumps on every write so Compose screens can re-read. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply(); bump()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply(); bump()
    }

    private fun secureGet(key: String): String = runCatching { secure?.getString(key, "") ?: "" }.getOrDefault("").trim()

    private fun securePut(key: String, value: String) {
        runCatching {
            val editor = secure?.edit() ?: return
            if (value.isBlank()) editor.remove(key) else editor.putString(key, value.trim())
            editor.apply()
        }.onFailure { Log.e(TAG, "Failed writing secure key $key: ${it.message}") }
        bump()
    }

    // ── Mode ────────────────────────────────────────────────────────────────

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_GEMINI_LIVE) ?: MODE_GEMINI_LIVE
        set(value) = putString(KEY_MODE, if (value == MODE_ADVANCED) MODE_ADVANCED else MODE_GEMINI_LIVE)

    val isAdvancedAgentic: Boolean get() = mode == MODE_ADVANCED

    // ── Transcriber ─────────────────────────────────────────────────────────

    var transcriber: String
        get() = (prefs.getString(KEY_TRANSCRIBER, TRANSCRIBER_ANDROID) ?: TRANSCRIBER_ANDROID)
            .takeIf { it in TRANSCRIBERS } ?: TRANSCRIBER_ANDROID
        set(value) = putString(KEY_TRANSCRIBER, value.takeIf { it in TRANSCRIBERS } ?: TRANSCRIBER_ANDROID)

    /** "" = none; otherwise one of [TRANSCRIBERS] different from [transcriber]. */
    var backupTranscriber: String
        get() = prefs.getString(KEY_BACKUP_TRANSCRIBER, "") ?: ""
        set(value) = putString(KEY_BACKUP_TRANSCRIBER, value.takeIf { it in TRANSCRIBERS } ?: "")

    var deepgramKey: String
        get() = secureGet(SKEY_DEEPGRAM)
        set(value) = securePut(SKEY_DEEPGRAM, value)

    /** Trailing-silence endpointing in ms for Deepgram (Maya default 300). */
    var endpointingMs: Int
        get() = prefs.getInt(KEY_ENDPOINTING, 300)
        set(value) {
            prefs.edit().putInt(KEY_ENDPOINTING, value.coerceIn(100, 3000)).apply(); bump()
        }

    // ── Brain (main LLM) ────────────────────────────────────────────────────

    var brainProviderId: String
        get() = prefs.getString(KEY_BRAIN_PROVIDER, BrainProviders.GEMINI_OPENAI) ?: BrainProviders.GEMINI_OPENAI
        set(value) = putString(KEY_BRAIN_PROVIDER, BrainProviders.byIdOrDefault(value).id)

    /** Base URL override (only meaningful for the custom provider; blank = preset). */
    var brainBaseUrl: String
        get() = prefs.getString(KEY_BRAIN_BASE_URL, "") ?: ""
        set(value) = putString(KEY_BRAIN_BASE_URL, value.trim())

    var brainModel: String
        get() = prefs.getString(KEY_BRAIN_MODEL, "") ?: ""
        set(value) = putString(KEY_BRAIN_MODEL, value.trim())

    var autoPickFreeModels: Boolean
        get() = prefs.getBoolean(KEY_AUTOPICK, false)
        set(value) = putBoolean(KEY_AUTOPICK, value)

    var thinkEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINK, false)
        set(value) = putBoolean(KEY_THINK, value)

    /** Per-provider key so switching providers never loses a key. */
    fun providerKey(providerId: String): String = secureGet("$SKEY_PROVIDER_PREFIX$providerId")

    fun setProviderKey(providerId: String, key: String) = securePut("$SKEY_PROVIDER_PREFIX$providerId", key)

    val brainKey: String get() = providerKey(brainProviderId)

    fun setBrainKey(key: String) = setProviderKey(brainProviderId, key)

    /** Main brain config. Blank model falls back to the preset default. */
    fun mainBrain(): BrainConfig {
        val provider = BrainProviders.byIdOrDefault(brainProviderId)
        val baseUrl = if (provider.customBaseUrl) brainBaseUrl.ifBlank { provider.baseUrl } else brainBaseUrl.ifBlank { provider.baseUrl }
        var key = providerKey(provider.id)
        if (key.isBlank() && provider.id == BrainProviders.GEMINI_OPENAI) key = jarvisGeminiKey()
        return BrainConfig(
            provider = provider,
            baseUrl = baseUrl,
            model = brainModel.ifBlank { provider.defaultModel },
            apiKey = key,
            autoPick = autoPickFreeModels && provider.id == BrainProviders.OPENROUTER,
            think = thinkEnabled
        )
    }

    // ── TTS ─────────────────────────────────────────────────────────────────

    var ttsEngine: String
        get() = (prefs.getString(KEY_TTS_ENGINE, TTS_GEMINI) ?: TTS_GEMINI).takeIf { it in TTS_ENGINES } ?: TTS_GEMINI
        set(value) = putString(KEY_TTS_ENGINE, value.takeIf { it in TTS_ENGINES } ?: TTS_GEMINI)

    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(value) = putString(KEY_TTS_VOICE, value.trim())

    var geminiTtsModel: String
        get() = prefs.getString(KEY_GEMINI_TTS_MODEL, GeminiTtsSynthesizer.DEFAULT_MODEL) ?: GeminiTtsSynthesizer.DEFAULT_MODEL
        set(value) = putString(KEY_GEMINI_TTS_MODEL, value.trim().ifBlank { GeminiTtsSynthesizer.DEFAULT_MODEL })

    /** Separate Gemini key for TTS (optional; falls back to brain Gemini key, then Jarvis key). */
    var geminiTtsKey: String
        get() = secureGet(SKEY_GEMINI_TTS)
        set(value) = securePut(SKEY_GEMINI_TTS, value)

    var googleCloudTtsKey: String
        get() = secureGet(SKEY_GOOGLE_TTS)
        set(value) = securePut(SKEY_GOOGLE_TTS, value)

    fun resolvedGeminiTtsKey(): String =
        geminiTtsKey.ifBlank { providerKey(BrainProviders.GEMINI_OPENAI).ifBlank { jarvisGeminiKey() } }

    // ── Eyes model ──────────────────────────────────────────────────────────

    var eyesUseCustom: Boolean
        get() = prefs.getBoolean(KEY_EYES_CUSTOM, false)
        set(value) = putBoolean(KEY_EYES_CUSTOM, value)

    var eyesProviderId: String
        get() = prefs.getString(KEY_EYES_PROVIDER, "") ?: ""
        set(value) = putString(KEY_EYES_PROVIDER, value)

    var eyesModel: String
        get() = prefs.getString(KEY_EYES_MODEL, "") ?: ""
        set(value) = putString(KEY_EYES_MODEL, value.trim())

    // ── Sub-agents ──────────────────────────────────────────────────────────

    fun subAgentUseCustom(role: SubAgentRole): Boolean = prefs.getBoolean("${KEY_SUB_PREFIX}${role.id}_custom", false)
    fun setSubAgentUseCustom(role: SubAgentRole, v: Boolean) = putBoolean("${KEY_SUB_PREFIX}${role.id}_custom", v)

    fun subAgentProviderId(role: SubAgentRole): String = prefs.getString("${KEY_SUB_PREFIX}${role.id}_provider", "") ?: ""
    fun setSubAgentProviderId(role: SubAgentRole, v: String) = putString("${KEY_SUB_PREFIX}${role.id}_provider", v)

    fun subAgentModel(role: SubAgentRole): String = prefs.getString("${KEY_SUB_PREFIX}${role.id}_model", "") ?: ""
    fun setSubAgentModel(role: SubAgentRole, v: String) = putString("${KEY_SUB_PREFIX}${role.id}_model", v.trim())

    fun subAgentBaseUrl(role: SubAgentRole): String = prefs.getString("${KEY_SUB_PREFIX}${role.id}_base_url", "") ?: ""
    fun setSubAgentBaseUrl(role: SubAgentRole, v: String) = putString("${KEY_SUB_PREFIX}${role.id}_base_url", v.trim())

    fun subAgentKey(role: SubAgentRole): String = secureGet("${SKEY_SUB_PREFIX}${role.id}")
    fun setSubAgentKey(role: SubAgentRole, v: String) = securePut("${SKEY_SUB_PREFIX}${role.id}", v)

    /**
     * Config for a sub-agent role: its own provider/key/model when "use custom" is on,
     * otherwise the main brain. The eyes role additionally honours the "Eyes model" field
     * and always prefers a vision-capable model.
     */
    fun brainFor(role: SubAgentRole): BrainConfig {
        val main = mainBrain()
        if (role == SubAgentRole.EYES) {
            val custom = subAgentUseCustom(role) || eyesUseCustom
            val providerId = subAgentProviderId(role).ifBlank { eyesProviderId }.ifBlank { main.provider.id }
            val provider = if (custom) BrainProviders.byIdOrDefault(providerId) else main.provider
            val key = if (custom) subAgentKey(role).ifBlank { providerKey(provider.id) }.ifBlank { main.apiKey } else main.apiKey
            val model = subAgentModel(role).ifBlank { eyesModel }.ifBlank { provider.defaultVisionModel.ifBlank { if (custom) provider.defaultModel else main.model } }
            val base = if (custom) subAgentBaseUrl(role).ifBlank { provider.baseUrl } else main.baseUrl
            return BrainConfig(provider, base, model, key, autoPick = false, think = false)
        }
        if (!subAgentUseCustom(role)) return main.copy(think = false)
        val provider = BrainProviders.byIdOrDefault(subAgentProviderId(role).ifBlank { main.provider.id })
        val key = subAgentKey(role).ifBlank { providerKey(provider.id) }.ifBlank { if (provider.id == main.provider.id) main.apiKey else "" }
        val base = subAgentBaseUrl(role).ifBlank { if (provider.id == main.provider.id) main.baseUrl else provider.baseUrl }
        val model = subAgentModel(role).ifBlank { if (provider.id == main.provider.id) main.model else provider.defaultModel }
        return BrainConfig(provider, base, model, key, autoPick = autoPickFreeModels && provider.id == BrainProviders.OPENROUTER, think = false)
    }

    // ── Transcription engine (audio file → text) ────────────────────────────

    /** OpenAI-compatible base URL for /audio/transcriptions ("" = derive from brain provider). */
    var transcriptionBaseUrl: String
        get() = prefs.getString(KEY_TRANSCRIPTION_BASE, "") ?: ""
        set(value) = putString(KEY_TRANSCRIPTION_BASE, value.trim())

    var transcriptionModel: String
        get() = prefs.getString(KEY_TRANSCRIPTION_MODEL, "") ?: ""
        set(value) = putString(KEY_TRANSCRIPTION_MODEL, value.trim())

    var transcriptionKey: String
        get() = secureGet(SKEY_TRANSCRIPTION)
        set(value) = securePut(SKEY_TRANSCRIPTION, value)

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Jarvis's own Gemini (Live) key, read-only. */
    fun jarvisGeminiKey(): String = runCatching { JarvisApp.instance.preferences.apiKey.trim() }.getOrDefault("")

    fun isGeminiLiveConfigured(): Boolean = jarvisGeminiKey().isNotBlank()

    companion object {
        private const val TAG = "BrainSettings"
        const val PREFS_NAME = "maya_brain_prefs"
        const val SECURE_NAME = "maya_brain_secure"

        const val MODE_GEMINI_LIVE = "gemini_live"
        const val MODE_ADVANCED = "advanced_agentic"

        const val TRANSCRIBER_ANDROID = "android"
        const val TRANSCRIBER_DEEPGRAM = "deepgram"
        const val TRANSCRIBER_SHERPA = "sherpa"
        val TRANSCRIBERS = listOf(TRANSCRIBER_ANDROID, TRANSCRIBER_DEEPGRAM, TRANSCRIBER_SHERPA)

        fun transcriberLabel(id: String): String = when (id) {
            TRANSCRIBER_DEEPGRAM -> "Deepgram (nova-3, multilingual)"
            TRANSCRIBER_SHERPA -> "sherpa-onnx (on-device)"
            else -> "Google / Android (free)"
        }

        const val TTS_GEMINI = "gemini_tts"
        const val TTS_GOOGLE_CLOUD = "google_cloud"
        const val TTS_EDGE = "edge"
        const val TTS_POCKET = "pocket"
        const val TTS_ANDROID = "android"
        val TTS_ENGINES = listOf(TTS_GEMINI, TTS_GOOGLE_CLOUD, TTS_EDGE, TTS_POCKET, TTS_ANDROID)

        fun ttsLabel(id: String): String = when (id) {
            TTS_GEMINI -> "Gemini TTS (gemini-2.5-flash-preview-tts)"
            TTS_GOOGLE_CLOUD -> "Google Cloud Text-to-Speech"
            TTS_EDGE -> "Edge neural voice"
            TTS_POCKET -> "Pocket TTS (on-device)"
            else -> "Android TTS"
        }

        private const val KEY_MODE = "mode"
        private const val KEY_TRANSCRIBER = "transcriber"
        private const val KEY_BACKUP_TRANSCRIBER = "backup_transcriber"
        private const val KEY_ENDPOINTING = "endpointing_ms"
        private const val KEY_BRAIN_PROVIDER = "brain_provider"
        private const val KEY_BRAIN_BASE_URL = "brain_base_url"
        private const val KEY_BRAIN_MODEL = "brain_model"
        private const val KEY_AUTOPICK = "brain_autopick"
        private const val KEY_THINK = "brain_think"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_GEMINI_TTS_MODEL = "gemini_tts_model"
        private const val KEY_EYES_CUSTOM = "eyes_custom"
        private const val KEY_EYES_PROVIDER = "eyes_provider"
        private const val KEY_EYES_MODEL = "eyes_model"
        private const val KEY_SUB_PREFIX = "sub_"
        private const val KEY_TRANSCRIPTION_BASE = "transcription_base_url"
        private const val KEY_TRANSCRIPTION_MODEL = "transcription_model"

        private const val SKEY_DEEPGRAM = "deepgram_key"
        private const val SKEY_PROVIDER_PREFIX = "provider_key_"
        private const val SKEY_GEMINI_TTS = "gemini_tts_key"
        private const val SKEY_GOOGLE_TTS = "google_cloud_tts_key"
        private const val SKEY_SUB_PREFIX = "sub_key_"
        private const val SKEY_TRANSCRIPTION = "transcription_key"

        @Volatile private var instance: BrainSettings? = null

        fun get(context: Context): BrainSettings =
            instance ?: synchronized(this) {
                instance ?: BrainSettings(context.applicationContext).also { instance = it }
            }
    }
}
