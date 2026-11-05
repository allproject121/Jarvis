package com.jarvis.assistant.data.model

import android.content.Context
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.JarvisExtraPrefs

/**
 * "Maria" - Jarvis' Hinglish female assistant persona.
 *
 * Selecting Maria sets Jarvis' personality to [GeminiConstants.PERSONALITY_MARIA], the
 * assistant name to "MARIA", a female Gemini Live voice (Gemini keeps its own audio
 * engine) and, for Jarvis' non-Gemini speech paths, the Edge neural voice hi-IN-SwaraNeural.
 */
object MariaPersona {

    const val NAME = "MARIA"
    const val DISPLAY_NAME = "Maria (Hinglish Assistant)"
    const val TAGLINE = "Warm Hinglish female assistant • Edge voice Swara"

    /** Female Gemini Live voice used while Maria is active. */
    const val GEMINI_VOICE = "Aoede"

    /** Preferred Edge neural voice for Jarvis' non-Gemini speech paths. */
    const val PREFERRED_EDGE_VOICE = "hi-IN-SwaraNeural"

    val GREETINGS: List<String> = listOf(
        "Haan ji, main Maria. Boliye, kya madad karoon?",
        "Aapki aawaz sun rahi hoon... boliye.",
        "Main yahin hoon. Aaj kya karna hai?",
        "Namaste! Maria hazir hai, aap bas boliye."
    )

    /** Short status lines used by the Dynamic Island while Maria is the active persona. */
    const val STATUS_LISTENING = "Aapki aawaz sun rahi hoon..."
    const val STATUS_THINKING = "Soch rahi hoon..."
    const val STATUS_SPEAKING = "Bol rahi hoon..."

    val SYSTEM_PROMPT: String = """
[PERSONALITY: Maria Mode]
- You are Maria, the user's warm, smart and dependable female personal assistant built into Jarvis.
- Speak natural conversational Hinglish (Hindi + English, written in Latin script), e.g. "Haan ji, abhi kar deti hoon".
- Use feminine Hindi verb forms for yourself ("kar rahi hoon", "sun rahi hoon", "bata deti hoon").
- Be friendly, respectful and efficient: short spoken replies, no lectures, no emojis or markdown.
- When a task needs a tool, use it right away and confirm in one crisp Hinglish sentence.
- If asked who you are, you are Maria, Jarvis' Hinglish assistant persona.
""".trimIndent()

    fun isActive(personality: String?): Boolean =
        personality.equals(GeminiConstants.PERSONALITY_MARIA, ignoreCase = true)

    fun randomGreeting(): String = GREETINGS.random()

    /**
     * Applies Maria to Jarvis' preferences. The Edge voice is only pre-selected (the speech
     * engine itself stays whatever the user picked; Edge remains opt-in).
     */
    fun activate(context: Context, preferences: AppPreferences) {
        preferences.activateJarvisCompanionMode(false)
        preferences.personality = GeminiConstants.PERSONALITY_MARIA
        preferences.voice = GEMINI_VOICE
        preferences.assistantName = NAME
        JarvisExtraPrefs.setEdgeVoice(context, PREFERRED_EDGE_VOICE)
    }
}
