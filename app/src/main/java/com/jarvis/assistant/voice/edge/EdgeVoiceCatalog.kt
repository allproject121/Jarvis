package com.jarvis.assistant.voice.edge

/**
 * Curated Microsoft Edge read-aloud neural voices offered in Jarvis Settings.
 * Indian voices first (Hindi / Indian English), then a few global English voices.
 */
object EdgeVoiceCatalog {

    data class EdgeVoice(
        val id: String,
        val displayName: String,
        val language: String,
        val female: Boolean
    ) {
        val label: String get() = "$displayName · $language · ${if (female) "Female" else "Male"}"
    }

    val VOICES: List<EdgeVoice> = listOf(
        EdgeVoice("hi-IN-SwaraNeural", "Swara", "Hindi (India)", female = true),
        EdgeVoice("hi-IN-MadhurNeural", "Madhur", "Hindi (India)", female = false),
        EdgeVoice("en-IN-NeerjaNeural", "Neerja", "English (India)", female = true),
        EdgeVoice("en-IN-NeerjaExpressiveNeural", "Neerja Expressive", "English (India)", female = true),
        EdgeVoice("en-IN-PrabhatNeural", "Prabhat", "English (India)", female = false),
        EdgeVoice("ta-IN-PallaviNeural", "Pallavi", "Tamil (India)", female = true),
        EdgeVoice("ta-IN-ValluvarNeural", "Valluvar", "Tamil (India)", female = false),
        EdgeVoice("te-IN-ShrutiNeural", "Shruti", "Telugu (India)", female = true),
        EdgeVoice("te-IN-MohanNeural", "Mohan", "Telugu (India)", female = false),
        EdgeVoice("bn-IN-TanishaaNeural", "Tanishaa", "Bengali (India)", female = true),
        EdgeVoice("bn-IN-BashkarNeural", "Bashkar", "Bengali (India)", female = false),
        EdgeVoice("mr-IN-AarohiNeural", "Aarohi", "Marathi (India)", female = true),
        EdgeVoice("mr-IN-ManoharNeural", "Manohar", "Marathi (India)", female = false),
        EdgeVoice("gu-IN-DhwaniNeural", "Dhwani", "Gujarati (India)", female = true),
        EdgeVoice("kn-IN-SapnaNeural", "Sapna", "Kannada (India)", female = true),
        EdgeVoice("ml-IN-SobhanaNeural", "Sobhana", "Malayalam (India)", female = true),
        EdgeVoice("ur-IN-GulNeural", "Gul", "Urdu (India)", female = true),
        EdgeVoice("en-US-AriaNeural", "Aria", "English (US)", female = true),
        EdgeVoice("en-US-JennyNeural", "Jenny", "English (US)", female = true),
        EdgeVoice("en-US-AvaNeural", "Ava", "English (US)", female = true),
        EdgeVoice("en-US-GuyNeural", "Guy", "English (US)", female = false),
        EdgeVoice("en-US-AndrewNeural", "Andrew", "English (US)", female = false),
        EdgeVoice("en-US-ChristopherNeural", "Christopher", "English (US)", female = false),
        EdgeVoice("en-GB-SoniaNeural", "Sonia", "English (UK)", female = true),
        EdgeVoice("en-GB-RyanNeural", "Ryan", "English (UK)", female = false)
    )

    fun byId(id: String): EdgeVoice? = VOICES.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun labelFor(id: String): String = byId(id)?.label ?: id

    /** Speaking-rate presets shown in Settings (label to percent offset). */
    val RATE_PRESETS: List<Pair<String, Int>> = listOf(
        "Slow (-20%)" to -20,
        "Relaxed (-10%)" to -10,
        "Normal" to 0,
        "Brisk (+10%)" to 10,
        "Fast (+20%)" to 20
    )
}
