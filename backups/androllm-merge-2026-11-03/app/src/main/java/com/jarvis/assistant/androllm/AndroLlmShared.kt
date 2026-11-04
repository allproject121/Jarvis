package com.jarvis.assistant.androllm

import android.content.Context
import java.io.File

/**
 * Shared contract for the AndroLLM feature layer.
 *
 * Rules: Gemini Live stays the primary brain. Every AndroLLM feature is additive and optional;
 * features that own the microphone, run background work or change routing default to OFF.
 */
object AndroLlmPaths {
    /** LiteRT-LM model files (.litertlm / .task / .tflite). */
    fun liteRtModelsDir(context: Context): File =
        File(context.filesDir, "models/litert").apply { mkdirs() }

    /** sherpa-onnx voice models (wake word, streaming ASR, offline TTS). */
    fun voiceModelsDir(context: Context): File =
        File(context.filesDir, "models/voice").apply { mkdirs() }

    /** Cached/extracted chat attachments. */
    fun attachmentsDir(context: Context): File =
        File(context.cacheDir, "androllm_attachments").apply { mkdirs() }
}

/** Opt-in switches for AndroLLM features, stored in their own SharedPreferences file. */
object AndroLlmFlags {
    private const val PREFS = "androllm_feature_flags"

    const val LITERT_ENGINE = "litert_engine_enabled"
    const val CLOUD_GATEWAY = "cloud_gateway_enabled"
    const val MEMORY_EXTRACTION = "memory_extraction_enabled"
    const val MEMORY_BACKGROUND_INDEXING = "memory_background_indexing_enabled"
    const val SHERPA_WAKE_WORD = "sherpa_wake_word_enabled"
    const val SHERPA_ASR = "sherpa_asr_enabled"
    const val SHERPA_TTS = "sherpa_tts_enabled"
    const val LLM_UI_PLANNER = "llm_ui_planner_enabled"
    const val CHAT_ATTACHMENTS = "chat_attachments_enabled"
    /** ON by default: any Gemini failure (bad key, quota, server, network) switches to the offline brain. */
    const val OFFLINE_BRAIN_FAILOVER = "offline_brain_failover_enabled"

    fun isEnabled(context: Context, key: String, default: Boolean = false): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, default)

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }
}

/**
 * Which UI Jarvis' own Home and Chat tabs show. Jarvis' classic screens are the default;
 * "androllm" shows the AndroLLM screen inside the same tab (chosen in Customization Studio).
 */
object AndroLlmUiStyle {
    private const val PREFS = "androllm_ui_style"
    private const val KEY_HOME = "home_style"
    private const val KEY_CHAT = "chat_style"
    const val JARVIS = "jarvis"
    const val ANDROLLM = "androllm"

    // Enforce unified Jarvis Stark HUD across Home and Chat tabs
    fun homeStyle(context: Context): String = JARVIS

    fun chatStyle(context: Context): String = JARVIS

    fun setHomeStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOME, JARVIS).apply()
    }

    fun setChatStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHAT, JARVIS).apply()
    }

    private const val KEY_AI_HUB_TAB = "show_ai_hub_tab"
    private const val KEY_SPLASH = "show_androllm_splash"

    /** AI Hub tab in dock is merged into Home Dual-Brain Hub to prevent duplicate interfaces. */
    fun showAiHubTab(context: Context): Boolean = false

    fun setShowAiHubTab(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AI_HUB_TAB, false).apply()
    }

    fun showSplash(context: Context): Boolean = false

    fun setShowSplash(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SPLASH, false).apply()
    }
}
