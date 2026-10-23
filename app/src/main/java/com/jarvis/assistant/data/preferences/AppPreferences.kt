package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val secureApiPrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_API_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure API-key storage could not be initialized", e)
        null
    }
    private val gson = Gson()

    init {
        migrateApiKeyToEncryptedStorage()
        val current = prefs.getString(KEY_MODEL, null)
        if (current == null || current !in GeminiConstants.SUPPORTED_MODELS) {
            prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
        }
    }

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val ENCRYPTED_API_PREF_NAME = "jarvis_secure_api_prefs"
        private const val TAG = "AppPreferences"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
        private const val KEY_OWNER_VOICE_ENABLED = "key_owner_voice_enabled"
        private const val KEY_OWNER_VOICE_PROFILE = "key_owner_voice_profile"
        private const val KEY_OWNER_VOICE_SAMPLES = "key_owner_voice_samples"
        private const val KEY_OWNER_VOICE_THRESHOLD = "key_owner_voice_threshold"
        private const val KEY_OWNER_VOICE_SAMPLE_COUNT = "key_owner_voice_sample_count"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SILENT_EXECUTION_MODE = "key_silent_execution_mode"
        private const val KEY_TASK_OVERLAY_ENABLED = "key_task_overlay_enabled"
        private const val KEY_TASK_OVERLAY_AVATAR = "key_task_overlay_avatar"
        private const val KEY_TASK_OVERLAY_MODE = "key_task_overlay_mode"
        private const val KEY_TASK_OVERLAY_EDGE = "key_task_overlay_edge"
        private const val KEY_TASK_OVERLAY_FREE_POSITION = "key_task_overlay_free_position"
        private const val KEY_TASK_OVERLAY_X = "key_task_overlay_x"
        private const val KEY_TASK_OVERLAY_Y = "key_task_overlay_y"
        private const val KEY_HAS_CUSTOM_OVERLAY_AVATAR = "key_has_custom_overlay_avatar"
        private const val KEY_CUSTOM_OVERLAY_AVATAR_PATH = "key_custom_overlay_avatar_path"
        private const val KEY_APP_THEME = "key_app_theme"
        private const val KEY_CUSTOM_THEME_COLOR = "key_custom_theme_color"
        private const val KEY_ORB_ANIMATION = "key_orb_animation"
        private const val KEY_ORB_SPEED = "key_orb_speed"
        private const val KEY_ORB_GLOW = "key_orb_glow"
        private const val KEY_ORB_SCALE = "key_orb_scale"
        private const val KEY_ORB_HUE = "key_orb_hue"
        private const val KEY_CUSTOM_ENDPOINT = "key_custom_endpoint"
        private const val KEY_SHOW_DEVELOPER_LINKS = "key_show_developer_links"
        private const val KEY_ORB_CORE_PRESET = "key_orb_core_preset"
        private const val KEY_SHOW_WEATHER_WIDGET = "key_show_weather_widget"
        private const val KEY_SHOW_TELEMETRY_WIDGET = "key_show_telemetry_widget"
        private const val KEY_SHOW_UPDATES_WIDGET = "key_show_updates_widget"
        private const val KEY_HUD_SOUND_ENABLED = "key_hud_sound_enabled"
        private const val KEY_HUD_SOUND_VOLUME = "key_hud_sound_volume"
        private const val KEY_VOSK_SENSITIVITY = "key_vosk_sensitivity"
        private const val KEY_BLUR_EFFECT_ENABLED = "key_blur_effect_enabled"
        private const val KEY_HOLO_SCANLINES_ENABLED = "key_holo_scanlines_enabled"
        private const val KEY_ARC_CORE_PULSE_MODE = "key_arc_core_pulse_mode"
        private const val KEY_AUDIO_REACTIVE_ORB = "key_audio_reactive_orb"
        private const val KEY_CORE_RESONANCE_LEVEL = "key_core_resonance_level"
        private const val KEY_HAPTIC_FEEDBACK_ENABLED = "key_haptic_feedback_enabled"
        private const val KEY_GODS_EYE_GOOGLE_API_KEY = "key_gods_eye_google_api_key"
        private const val KEY_GODS_EYE_CESIUM_ION_TOKEN = "key_gods_eye_cesium_ion_token"
        private const val KEY_GODS_EYE_NASA_FIRMS_KEY = "key_gods_eye_nasa_firms_key"
        private const val KEY_GODS_EYE_AISSTREAM_KEY = "key_gods_eye_aisstream_key"
        private const val KEY_GODS_EYE_TOMTOM_KEY = "key_gods_eye_tomtom_key"
        private const val KEY_GODS_EYE_MAPILLARY_TOKEN = "key_gods_eye_mapillary_token"
        private const val KEY_GODS_EYE_OPENSKY_CLIENT_ID = "key_gods_eye_opensky_client_id"
        private const val KEY_GODS_EYE_OPENSKY_CLIENT_SECRET = "key_gods_eye_opensky_client_secret"
        private const val KEY_AI_MODEL_MODE = "key_ai_model_mode"
        private const val KEY_OPENAI_API_KEY = "key_openai_api_key"
        private const val KEY_ORB_ENTITY_TYPE = "key_orb_entity_type"
        private const val KEY_ORB_SHOW_CORE = "key_orb_show_core"
        private const val KEY_ORB_SHOW_GLOW = "key_orb_show_glow"
        private const val KEY_ORB_SHOW_RINGS = "key_orb_show_rings"
        private const val KEY_ORB_SHOW_RING1 = "key_orb_show_ring1"
        private const val KEY_ORB_SHOW_RING2 = "key_orb_show_ring2"
        private const val KEY_ORB_SHOW_RING3 = "key_orb_show_ring3"
        private const val KEY_ORB_SHOW_ORBIT_DUST = "key_orb_show_orbit_dust"
        private const val KEY_ORB_SHOW_SHADOW = "key_orb_show_shadow"
        private const val KEY_ORB_SHOW_IDLE_BREATHING = "key_orb_show_idle_breathing"
        private const val KEY_ORB_TOUCH_DRAG_ENABLED = "key_orb_touch_drag_enabled"
        private const val KEY_ORB_CINEMATIC_50 = "key_orb_cinematic_50"
        private const val KEY_ORB_AUTO_ROTATION = "key_orb_auto_rotation"
        private const val KEY_ORB_COMPANION_COUNT = "key_orb_companion_count"
        private const val KEY_ORB_COMPANION_TYPE = "key_orb_companion_type"
        private const val KEY_DEFAULT_ASSISTANT_SCREEN_CAPTURE = "key_default_assistant_screen_capture"
        private const val KEY_WHATSAPP_READER_ENABLED = "key_whatsapp_reader_enabled"
        private const val KEY_TELEGRAM_READER_ENABLED = "key_telegram_reader_enabled"
        private const val KEY_MESSAGE_AUTO_REPLY_CONFIRM = "key_message_auto_reply_confirm"
        private const val KEY_DRIVING_MODE_AUTO_CAR = "key_driving_mode_auto_car"
        private const val KEY_CALLER_ID_ANNOUNCEMENT = "key_caller_id_announcement"
        private const val KEY_MEM0_MEMORY_ENABLED = "key_mem0_memory_enabled"
        private const val KEY_MUSE_EXPERIENCE_ENABLED = "key_muse_experience_enabled"
        private const val KEY_SECRET_SETTINGS_ENABLED = "key_secret_settings_enabled"
        private const val KEY_SECRET_INTIMACY_LEVEL = "key_secret_intimacy_level"
        private const val KEY_TOUCH_GUARD_ARMED = "key_touch_guard_armed"
        private const val KEY_TOUCH_GUARD_STEALTH = "key_touch_guard_stealth"
        private const val KEY_EMERGENCY_SOS_CONTACTS = "key_emergency_sos_contacts"
        private const val KEY_EMERGENCY_SOS_SIREN = "key_emergency_sos_siren"
        private const val KEY_EMAIL_SIGNATURE = "key_email_signature"
        private const val KEY_EMAIL_CUSTOM_SMTP = "key_email_custom_smtp"
        private const val KEY_SOCIAL_DAILY_STORY = "key_social_daily_story"
        private const val KEY_REELS_AUTO_SCROLL = "key_reels_auto_scroll"
        private const val KEY_PROACTIVE_JARVIS = "key_proactive_jarvis"
        private const val KEY_BATTERY_SAVER = "key_battery_saver"
        private const val KEY_WALLPAPER_MODE = "key_wallpaper_mode"
        private const val KEY_EVENT_TRIGGERS_BATTERY = "key_event_triggers_battery"
        private const val KEY_EVENT_TRIGGERS_CHARGER = "key_event_triggers_charger"
        private const val KEY_EVENT_TRIGGERS_BLUETOOTH = "key_event_triggers_bluetooth"
        private const val KEY_VOICE_ENGINE_TYPE = "key_voice_engine_type"
        private const val KEY_HUMAN_TYPING_SPEED = "key_human_typing_speed"
        private const val KEY_DIRECT_AUTONOMOUS_MODE = "key_direct_autonomous_mode"
        private const val KEY_ASSISTANT_NAME = "key_assistant_name"
        const val MODE_JARVIS = "jarvis"
        const val MODE_JARVIS_COMPANION = "jarvis_companion"
        private const val KEY_EXPERIENCE_MODE = "key_experience_mode"
        private const val KEY_JARVIS_ACTIVE_CHARACTER = "key_jarvis_active_character"
        private const val KEY_PRIVACY_MODE = "key_privacy_mode"
        private const val KEY_HOME_ASSISTANT_URL = "key_home_assistant_url"
        private const val KEY_HOME_ASSISTANT_TOKEN = "key_home_assistant_token"
        private const val KEY_SMART_HOME_WEBHOOK_URL = "key_smart_home_webhook_url"
    }

    var aiModelMode: String
        get() = prefs.getString(KEY_AI_MODEL_MODE, "AUTO_HYBRID") ?: "AUTO_HYBRID"
        set(value) = prefs.edit().putString(KEY_AI_MODEL_MODE, value).apply()

    var openAiApiKey: String
        get() = secureApiPrefs?.getString(KEY_OPENAI_API_KEY, null) ?: prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) {
            if (secureApiPrefs != null) {
                secureApiPrefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()
                prefs.edit().remove(KEY_OPENAI_API_KEY).apply()
            } else {
                prefs.edit().putString(KEY_OPENAI_API_KEY, value).apply()
            }
        }

    var isHoloScanlinesEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOLO_SCANLINES_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HOLO_SCANLINES_ENABLED, value).apply()

    var arcCorePulseMode: String
        get() = prefs.getString(KEY_ARC_CORE_PULSE_MODE, "quantum") ?: "quantum"
        set(value) = prefs.edit().putString(KEY_ARC_CORE_PULSE_MODE, value).apply()

    var isAudioReactiveOrb: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_REACTIVE_ORB, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_REACTIVE_ORB, value).apply()

    var coreResonanceLevel: Int
        get() = prefs.getInt(KEY_CORE_RESONANCE_LEVEL, 84).coerceIn(10, 100)
        set(value) = prefs.edit().putInt(KEY_CORE_RESONANCE_LEVEL, value.coerceIn(10, 100)).apply()

    var isHapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, value).apply()

    var isBlurEffectEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUR_EFFECT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLUR_EFFECT_ENABLED, value).apply()

    var showDeveloperLinks: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEVELOPER_LINKS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DEVELOPER_LINKS, value).apply()

    var isOwnerVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_VOICE_ENABLED, value).apply()

    var ownerVoiceThreshold: Float
        get() {
            val stored = prefs.getFloat(KEY_OWNER_VOICE_THRESHOLD, 0.70f)
            // Auto-migrate older builds that saved overly strict 0.50f threshold
            return if (stored < 0.60f) 0.70f else stored
        }
        set(value) = prefs.edit().putFloat(KEY_OWNER_VOICE_THRESHOLD, value).apply()

    var ownerVoiceSampleCount: Int
        get() = prefs.getInt(KEY_OWNER_VOICE_SAMPLE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, value).apply()

    fun hasOwnerVoiceProfile(): Boolean {
        val str = prefs.getString(KEY_OWNER_VOICE_PROFILE, null)
        return !str.isNullOrBlank()
    }

    fun getOwnerVoiceProfile(): FloatArray? {
        val json = prefs.getString(KEY_OWNER_VOICE_PROFILE, null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun getOwnerVoiceSamples(): List<FloatArray> {
        val json = prefs.getString(KEY_OWNER_VOICE_SAMPLES, null) ?: return emptyList()
        return try {
            val root = org.json.JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (vec.isNotEmpty()) list.add(vec)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOwnerVoiceProfile(masterProfile: FloatArray, sampleVectors: List<FloatArray>) {
        val masterArr = org.json.JSONArray()
        for (v in masterProfile) {
            masterArr.put(v.toDouble())
        }

        val samplesArr = org.json.JSONArray()
        for (vec in sampleVectors) {
            val arr = org.json.JSONArray()
            for (v in vec) {
                arr.put(v.toDouble())
            }
            samplesArr.put(arr)
        }

        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, masterArr.toString())
            .putString(KEY_OWNER_VOICE_SAMPLES, samplesArr.toString())
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleVectors.size)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun saveOwnerVoiceProfile(profile: FloatArray, sampleCount: Int) {
        val arr = org.json.JSONArray()
        for (v in profile) {
            arr.put(v.toDouble())
        }
        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, arr.toString())
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleCount)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun clearOwnerVoiceProfile() {
        prefs.edit()
            .remove(KEY_OWNER_VOICE_PROFILE)
            .remove(KEY_OWNER_VOICE_SAMPLES)
            .remove(KEY_OWNER_VOICE_SAMPLE_COUNT)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, false)
            .apply()
    }

    var apiKey: String
        get() = secureApiPrefs?.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) {
            val secure = checkNotNull(secureApiPrefs) { "Secure API-key storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) secure.edit().remove(KEY_API_KEY).commit()
            else secure.edit().putString(KEY_API_KEY, clean).commit()
            check(saved) { "Could not save API key to encrypted storage." }
        }

    val canStoreApiKeySecurely: Boolean get() = secureApiPrefs != null

    private fun migrateApiKeyToEncryptedStorage() {
        val legacyKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacyKey.isEmpty()) return
        val secure = secureApiPrefs ?: run {
            prefs.edit().remove(KEY_API_KEY).commit()
            return
        }
        try {
            val existing = secure.getString(KEY_API_KEY, null)
            if (existing.isNullOrEmpty()) {
                check(secure.edit().putString(KEY_API_KEY, legacyKey).commit())
            }
            if (!secure.getString(KEY_API_KEY, null).isNullOrEmpty()) prefs.edit().remove(KEY_API_KEY).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy API-key migration to encrypted storage failed", e)
        }
    }

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            if (stored !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
                return GeminiConstants.DEFAULT_MODEL
            }
            return stored
        }
        set(value) {
            val toSave = if (value in GeminiConstants.SUPPORTED_MODELS) value else GeminiConstants.DEFAULT_MODEL
            prefs.edit().putString(KEY_MODEL, toSave).apply()
        }

    var language: String
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, GeminiConstants.DEFAULT_LANGUAGE) ?: GeminiConstants.DEFAULT_LANGUAGE
            return if (stored.contains("Auto", ignoreCase = true)) "Auto" else stored
        }
        set(value) {
            val clean = if (value.contains("Auto", ignoreCase = true)) "Auto" else value.trim()
            prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        }

    var voice: String
        get() = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT) ?: GeminiConstants.PERSONALITY_ASSISTANT
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var userName: String
        get() {
            val name = prefs.getString(KEY_USER_NAME, "") ?: ""
            return if (name.isBlank() || name == "Salaam") "Roshan" else name
        }
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var appTheme: String
        get() {
            val stored = prefs.getString(KEY_APP_THEME, "dark") ?: "dark"
            return when (stored.lowercase()) {
                "light", "pearl_white", "white" -> "pearl_white"
                "dark", "midnight_onyx", "obsidian", "cyan" -> "dark"
                "red", "crimson" -> "red"
                "sunset", "rose_gold" -> "sunset"
                "stealth", "black", "oled" -> "stealth"
                "amethyst", "violet", "purple", "cyberpunk_neon" -> "amethyst"
                "emerald", "green", "emerald_obsidian" -> "emerald"
                "gold", "amber" -> "gold"
                "pink", "rose" -> "pink"
                "orange" -> "orange"
                "indigo" -> "indigo"
                "custom" -> "custom"
                else -> stored
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var customThemeColor: String
        get() = prefs.getString(KEY_CUSTOM_THEME_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_THEME_COLOR, value.trim()).apply()

    var orbAnimation: String
        get() {
            val valid = listOf("holographic_plasma", "cosmic_pulsar", "quantum_core", "cyber_ring", "siri_glow")
            val stored = prefs.getString(KEY_ORB_ANIMATION, "holographic_plasma") ?: "holographic_plasma"
            return if (stored in valid) stored else "holographic_plasma"
        }
        set(value) = prefs.edit().putString(KEY_ORB_ANIMATION, value).apply()

    var orbSpeed: Float
        get() = prefs.getFloat(KEY_ORB_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SPEED, value).apply()

    var orbGlow: Float
        get() = prefs.getFloat(KEY_ORB_GLOW, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_GLOW, value).apply()

    var orbScale: Float
        get() = prefs.getFloat(KEY_ORB_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SCALE, value).apply()

    var orbHue: Float
        get() = prefs.getFloat(KEY_ORB_HUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ORB_HUE, value).apply()

    var orbCorePreset: String
        get() = prefs.getString(KEY_ORB_CORE_PRESET, "cyan") ?: "cyan"
        set(value) = prefs.edit().putString(KEY_ORB_CORE_PRESET, value).apply()

    var orbEntityType: String
        get() = prefs.getString(KEY_ORB_ENTITY_TYPE, "orb") ?: "orb"
        set(value) = prefs.edit().putString(KEY_ORB_ENTITY_TYPE, value).apply()

    var orbShowCore: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_CORE, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_CORE, value).apply()

    var orbShowGlow: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_GLOW, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_GLOW, value).apply()

    var orbShowRings: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_RINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_RINGS, value).apply()

    var orbShowRing1: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_RING1, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_RING1, value).apply()

    var orbShowRing2: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_RING2, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_RING2, value).apply()

    var orbShowRing3: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_RING3, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_RING3, value).apply()

    var orbShowOrbitDust: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_ORBIT_DUST, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_ORBIT_DUST, value).apply()

    var orbShowShadow: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_SHADOW, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_SHADOW, value).apply()

    var wallpaperMode: String
        get() = prefs.getString(KEY_WALLPAPER_MODE, "character") ?: "character"
        set(value) = prefs.edit().putString(KEY_WALLPAPER_MODE, value).apply()

    var orbShowIdleBreathing: Boolean
        get() = prefs.getBoolean(KEY_ORB_SHOW_IDLE_BREATHING, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_SHOW_IDLE_BREATHING, value).apply()

    var orbTouchDragEnabled: Boolean
        get() = prefs.getBoolean(KEY_ORB_TOUCH_DRAG_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_TOUCH_DRAG_ENABLED, value).apply()

    var isOrbCinematic50: Boolean
        get() = prefs.getBoolean(KEY_ORB_CINEMATIC_50, true)
        set(value) = prefs.edit().putBoolean(KEY_ORB_CINEMATIC_50, value).apply()

    var orbAutoRotationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ORB_AUTO_ROTATION, false)
        set(value) = prefs.edit().putBoolean(KEY_ORB_AUTO_ROTATION, value).apply()

    var orbCompanionCount: Int
        get() = prefs.getInt(KEY_ORB_COMPANION_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_ORB_COMPANION_COUNT, value.coerceIn(0, 4)).apply()

    var orbCompanionType: String
        get() = prefs.getString(KEY_ORB_COMPANION_TYPE, "orb") ?: "orb"
        set(value) = prefs.edit().putString(KEY_ORB_COMPANION_TYPE, value).apply()

    var showWeatherWidget: Boolean
        get() = prefs.getBoolean(KEY_SHOW_WEATHER_WIDGET, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_WEATHER_WIDGET, value).apply()

    var showTelemetryWidget: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TELEMETRY_WIDGET, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TELEMETRY_WIDGET, value).apply()

    var showUpdatesWidget: Boolean
        get() = prefs.getBoolean(KEY_SHOW_UPDATES_WIDGET, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_UPDATES_WIDGET, value).apply()

    var isHudSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_HUD_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SOUND_ENABLED, value).apply()

    var hudSoundVolume: Float
        get() = prefs.getFloat(KEY_HUD_SOUND_VOLUME, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_HUD_SOUND_VOLUME, value.coerceIn(0f, 1f)).apply()

    var voskWakeWordSensitivity: Float
        get() = prefs.getFloat(KEY_VOSK_SENSITIVITY, 0.65f)
        set(value) = prefs.edit().putFloat(KEY_VOSK_SENSITIVITY, value.coerceIn(0.1f, 1f)).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "http://192.168.0.101:11434")?.ifBlank { "http://192.168.0.101:11434" } ?: "http://192.168.0.101:11434"
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply()

    var localHostModel: String
        get() = prefs.getString("local_host_model", "qwen3.8:27b") ?: "qwen3.8:27b"
        set(value) = prefs.edit().putString("local_host_model", value.trim()).apply()

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isSilentExecutionMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_EXECUTION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_EXECUTION_MODE, value).apply()

    var isTaskOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_ENABLED, value).apply()

    var taskOverlayAvatar: String
        get() = prefs.getString(KEY_TASK_OVERLAY_AVATAR, "ghost") ?: "ghost"
        set(value) {
            val available = listOf("custom_3d", "alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle")
            prefs.edit().putString(KEY_TASK_OVERLAY_AVATAR, value.takeIf { it in available } ?: "ghost").apply()
        }

    var hasCustomOverlayAvatar: Boolean
        get() = prefs.getBoolean(KEY_HAS_CUSTOM_OVERLAY_AVATAR, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_CUSTOM_OVERLAY_AVATAR, value).apply()

    var customOverlayAvatarPath: String?
        get() = prefs.getString(KEY_CUSTOM_OVERLAY_AVATAR_PATH, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_OVERLAY_AVATAR_PATH, value).apply()

    /** always keeps the avatar visible while the service is alive; working limits it to active tasks. */
    var taskOverlayMode: String
        get() = prefs.getString(KEY_TASK_OVERLAY_MODE, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_MODE, if (value == "working") "working" else "always").apply()

    /** Preferred dock edge. Dragging the overlay updates this to the closest edge. */
    var taskOverlayEdge: String
        get() = prefs.getString(KEY_TASK_OVERLAY_EDGE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_EDGE, value.takeIf { it in setOf("left", "right", "top", "bottom") } ?: "right").apply()

    var taskOverlayFreePosition: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_FREE_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_FREE_POSITION, value).apply()

    var taskOverlayX: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_X, value.coerceAtLeast(0)).apply()

    var taskOverlayY: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_Y, value.coerceAtLeast(0)).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    var godsEyeGoogleApiKey: String
        get() = prefs.getString(KEY_GODS_EYE_GOOGLE_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_GOOGLE_API_KEY, value.trim()).apply()

    var godsEyeCesiumIonToken: String
        get() = prefs.getString(KEY_GODS_EYE_CESIUM_ION_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_CESIUM_ION_TOKEN, value.trim()).apply()

    var godsEyeNasaFirmsKey: String
        get() = prefs.getString(KEY_GODS_EYE_NASA_FIRMS_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_NASA_FIRMS_KEY, value.trim()).apply()

    var godsEyeAisStreamKey: String
        get() = prefs.getString(KEY_GODS_EYE_AISSTREAM_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_AISSTREAM_KEY, value.trim()).apply()

    var godsEyeTomTomKey: String
        get() = prefs.getString(KEY_GODS_EYE_TOMTOM_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_TOMTOM_KEY, value.trim()).apply()

    var godsEyeMapillaryToken: String
        get() = prefs.getString(KEY_GODS_EYE_MAPILLARY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_MAPILLARY_TOKEN, value.trim()).apply()

    var godsEyeOpenSkyClientId: String
        get() = prefs.getString(KEY_GODS_EYE_OPENSKY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_OPENSKY_CLIENT_ID, value.trim()).apply()

    var godsEyeOpenSkyClientSecret: String
        get() = prefs.getString(KEY_GODS_EYE_OPENSKY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GODS_EYE_OPENSKY_CLIENT_SECRET, value.trim()).apply()

    var isDefaultAssistantScreenCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_ASSISTANT_SCREEN_CAPTURE, true)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_ASSISTANT_SCREEN_CAPTURE, value).apply()

    var isWhatsAppReaderEnabled: Boolean
        get() = prefs.getBoolean(KEY_WHATSAPP_READER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WHATSAPP_READER_ENABLED, value).apply()

    var isTelegramReaderEnabled: Boolean
        get() = prefs.getBoolean(KEY_TELEGRAM_READER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TELEGRAM_READER_ENABLED, value).apply()

    var isMessageAutoReplyConfirmEnabled: Boolean
        get() = prefs.getBoolean(KEY_MESSAGE_AUTO_REPLY_CONFIRM, false)
        set(value) = prefs.edit().putBoolean(KEY_MESSAGE_AUTO_REPLY_CONFIRM, value).apply()

    var isDrivingModeAutoCarEnabled: Boolean
        get() = prefs.getBoolean(KEY_DRIVING_MODE_AUTO_CAR, true)
        set(value) = prefs.edit().putBoolean(KEY_DRIVING_MODE_AUTO_CAR, value).apply()

    var isCallerIdAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLER_ID_ANNOUNCEMENT, true)
        set(value) = prefs.edit().putBoolean(KEY_CALLER_ID_ANNOUNCEMENT, value).apply()

    var isMem0MemoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEM0_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEM0_MEMORY_ENABLED, value).apply()

    var isMuseExperienceEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSE_EXPERIENCE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MUSE_EXPERIENCE_ENABLED, value).apply()

    var isSecretSettingsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SECRET_SETTINGS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SECRET_SETTINGS_ENABLED, value).apply()

    var secretIntimacyLevel: Int
        get() = prefs.getInt(KEY_SECRET_INTIMACY_LEVEL, 1) // 1=Friendly, 2=Romantic, 3=Intense (18+)
        set(value) = prefs.edit().putInt(KEY_SECRET_INTIMACY_LEVEL, value).apply()

    var isTouchGuardArmed: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_GUARD_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_GUARD_ARMED, value).apply()

    var isTouchGuardStealth: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_GUARD_STEALTH, false)
        set(value) = prefs.edit().putBoolean(KEY_TOUCH_GUARD_STEALTH, value).apply()

    var emergencySosContacts: String
        get() = prefs.getString(KEY_EMERGENCY_SOS_CONTACTS, "112, 100") ?: "112, 100"
        set(value) = prefs.edit().putString(KEY_EMERGENCY_SOS_CONTACTS, value.trim()).apply()

    var isEmergencySosSirenEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMERGENCY_SOS_SIREN, true)
        set(value) = prefs.edit().putBoolean(KEY_EMERGENCY_SOS_SIREN, value).apply()

    var emailSignature: String
        get() = prefs.getString(KEY_EMAIL_SIGNATURE, "Sent via Jarvis AI Assistant") ?: "Sent via Jarvis AI Assistant"
        set(value) = prefs.edit().putString(KEY_EMAIL_SIGNATURE, value).apply()

    var emailCustomSmtp: String
        get() = prefs.getString(KEY_EMAIL_CUSTOM_SMTP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EMAIL_CUSTOM_SMTP, value.trim()).apply()

    var isSocialDailyStoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOCIAL_DAILY_STORY, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCIAL_DAILY_STORY, value).apply()

    var isReelsAutoScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_REELS_AUTO_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_REELS_AUTO_SCROLL, value).apply()

    var isProactiveJarvisEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVE_JARVIS, true)
        set(value) = prefs.edit().putBoolean(KEY_PROACTIVE_JARVIS, value).apply()

    var isBatterySaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SAVER, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_SAVER, value).apply()

    var isEventTriggersBatteryEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVENT_TRIGGERS_BATTERY, true)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_TRIGGERS_BATTERY, value).apply()

    var isEventTriggersChargerEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVENT_TRIGGERS_CHARGER, true)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_TRIGGERS_CHARGER, value).apply()

    var isEventTriggersBluetoothEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVENT_TRIGGERS_BLUETOOTH, true)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_TRIGGERS_BLUETOOTH, value).apply()

    var voiceEngineType: String
        get() = prefs.getString(KEY_VOICE_ENGINE_TYPE, "AGENTIC_LOCAL") ?: "AGENTIC_LOCAL"
        set(value) = prefs.edit().putString(KEY_VOICE_ENGINE_TYPE, value).apply()

    var humanTypingSpeedMs: Long
        get() = prefs.getLong(KEY_HUMAN_TYPING_SPEED, 35L)
        set(value) = prefs.edit().putLong(KEY_HUMAN_TYPING_SPEED, value).apply()

    var isDirectAutonomousMode: Boolean
        get() = prefs.getBoolean(KEY_DIRECT_AUTONOMOUS_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_DIRECT_AUTONOMOUS_MODE, value).apply()

    var assistantName: String
        get() = prefs.getString(KEY_ASSISTANT_NAME, "JARVIS") ?: "JARVIS"
        set(value) = prefs.edit().putString(KEY_ASSISTANT_NAME, value.trim()).apply()

    var experienceMode: String
        get() = prefs.getString(KEY_EXPERIENCE_MODE, MODE_JARVIS) ?: MODE_JARVIS
        set(value) = prefs.edit().putString(KEY_EXPERIENCE_MODE, value).apply()

    var jarvisActiveCharacter: String
        get() = prefs.getString(KEY_JARVIS_ACTIVE_CHARACTER, "Maya") ?: "Maya"
        set(value) = prefs.edit().putString(KEY_JARVIS_ACTIVE_CHARACTER, value).apply()

    val isJarvisCompanionMode: Boolean
        get() = experienceMode == MODE_JARVIS_COMPANION

    var homeAssistantUrl: String
        get() = prefs.getString(KEY_HOME_ASSISTANT_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_ASSISTANT_URL, value.trim()).apply()

    var homeAssistantToken: String
        get() = secureApiPrefs?.getString(KEY_HOME_ASSISTANT_TOKEN, null) ?: prefs.getString(KEY_HOME_ASSISTANT_TOKEN, "") ?: ""
        set(value) {
            if (secureApiPrefs != null) {
                secureApiPrefs.edit().putString(KEY_HOME_ASSISTANT_TOKEN, value.trim()).apply()
                prefs.edit().remove(KEY_HOME_ASSISTANT_TOKEN).apply()
            } else {
                prefs.edit().putString(KEY_HOME_ASSISTANT_TOKEN, value.trim()).apply()
            }
        }

    var smartHomeWebhookUrl: String
        get() = prefs.getString(KEY_SMART_HOME_WEBHOOK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMART_HOME_WEBHOOK_URL, value.trim()).apply()

    var isPrivacyModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVACY_MODE, value).apply()

    fun activateJarvisCompanionMode(enable: Boolean) {
        if (enable) {
            experienceMode = MODE_JARVIS_COMPANION
            assistantName = "JARVIS"
            personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_COMPANION
            wallpaperMode = "character"
            voice = "Aoede"
        } else {
            experienceMode = MODE_JARVIS
            assistantName = "JARVIS"
            personality = com.jarvis.assistant.data.model.GeminiConstants.PERSONALITY_ASSISTANT
            wallpaperMode = "arc_reactor"
            voice = "Puck"
        }
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
