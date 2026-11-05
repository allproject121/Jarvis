package com.jarvis.assistant.ui.compose.model

/** Theme modes supported by the Compose (AndroLLM) UI. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    /** True-black AMOLED mode — pure #000000 backgrounds for OLED displays. */
    AMOLED
}

/** Spacing preset for cards, rows and messages. */
enum class UiDensity {
    COMFORTABLE,
    DEFAULT,
    COMPACT
}

enum class ChatFontSize {
    SMALL, MEDIUM, LARGE
}

/**
 * Display-only model description used by the Compose model cards.
 * Built from Jarvis' LocalAiConfig model entries.
 */
data class Model(
    val name: String,
    val fileSize: Long = 0,
    val parameters: String = "",
    val quantization: String = "",
    val contextLength: Int = 4096,
    val family: String = "Llama",
    val recommendedRamGb: Float = 8.0f
)
