package com.jarvis.assistant.maya.ui

import android.content.Context
import com.jarvis.assistant.data.model.GeminiConstants

/** One selectable companion character (Maya's "Characters" sheet). */
data class MayaCharacter(
    /** Stable id used for prefs and monogram colours. */
    val id: String,
    /** The value stored in `preferences.jarvisActiveCharacter`. */
    val prefName: String,
    val title: String,
    val tagline: String,
    val description: String,
    /** FREE / PRO / 18+ style badge text. */
    val badge: String,
    val personality: String,
    /** Prefix of the bundled voice previews in assets/voices (`<prefix>_<Voice>.ogg`), or null. */
    val previewPrefix: String?,
    val defaultVoice: String,
    /** Gemini voices offered for this character, in display order. */
    val voices: List<String>,
    /** Fallback line spoken with PocketTTS when no preview file exists. */
    val previewLine: String,
    val adultGate: Boolean = false
) {
    val monogram: String get() = title.take(1).uppercase()
}

/** The character catalogue — Maya (free), Maya Pro, Friday, Venom, Jarvis. */
object MayaCharacters {

    private val FEMALE_VOICES = listOf(
        "Aoede", "Kore", "Leda", "Zephyr", "Autonoe", "Callirrhoe", "Despina",
        "Erinome", "Gacrux", "Laomedeia", "Pulcherrima", "Sulafat", "Vindemiatrix"
    )

    private val MALE_VOICES = listOf(
        "Charon", "Puck", "Fenrir", "Orus", "Enceladus", "Iapetus", "Umbriel",
        "Algieba", "Algenib", "Rasalgethi", "Achernar", "Alnilam", "Schedar",
        "Sadachbia", "Sadaltager", "Zubenelgenubi", "Achird"
    )

    val MAYA = MayaCharacter(
        id = "maya",
        prefName = "Maya",
        title = "Maya",
        tagline = "Warm assistant • Hindi / Hinglish",
        description = "Warm, playful and always on your side. Talks Hinglish, remembers your day.",
        badge = "FREE",
        personality = GeminiConstants.PERSONALITY_ASSISTANT,
        previewPrefix = "maya",
        defaultVoice = "Aoede",
        voices = FEMALE_VOICES,
        previewLine = "नमस्ते! मैं माया हूँ।"
    )

    val MAYA_PRO = MayaCharacter(
        id = "maya_pro",
        prefName = "Maya Pro",
        title = "Maya Pro",
        tagline = "Executive agent • Fast & crisp",
        description = "The full Maya: autonomous directives, tool calls and a sharper, faster register.",
        badge = "PRO",
        personality = GeminiConstants.PERSONALITY_EXECUTIVE,
        previewPrefix = "maya",
        defaultVoice = "Kore",
        voices = FEMALE_VOICES,
        previewLine = "Maya Pro active. Ready for autonomous directives."
    )

    val FRIDAY = MayaCharacter(
        id = "friday",
        prefName = "Friday",
        title = "F.R.I.D.A.Y.",
        tagline = "Cyber protocol • British AI tone",
        description = "Tactical, precise, a little dry. The ops voice for automation and security.",
        badge = "PRO",
        personality = GeminiConstants.PERSONALITY_TACTICAL,
        previewPrefix = "friday",
        defaultVoice = "Aoede",
        voices = FEMALE_VOICES,
        previewLine = "F.R.I.D.A.Y. online and operational."
    )

    val VENOM = MayaCharacter(
        id = "venom",
        prefName = "Venom",
        title = "Venom",
        tagline = "Deep aggressive • Dark symbiote",
        description = "Low, heavy and blunt. Says what it thinks and gets things done.",
        badge = "PRO",
        personality = GeminiConstants.PERSONALITY_AGGRESSIVE,
        previewPrefix = "venom",
        defaultVoice = "Charon",
        voices = MALE_VOICES,
        previewLine = "We are Venom!"
    )

    val JARVIS = MayaCharacter(
        id = "jarvis",
        prefName = "Jarvis",
        title = "Jarvis",
        tagline = "Stark protocol • Calm & capable",
        description = "The original assistant voice: composed, courteous, always two steps ahead.",
        badge = "FREE",
        personality = GeminiConstants.PERSONALITY_ASSISTANT,
        previewPrefix = null,
        defaultVoice = "Puck",
        voices = MALE_VOICES,
        previewLine = "Good evening. All systems are online, sir."
    )

    val ALL: List<MayaCharacter> = listOf(MAYA, MAYA_PRO, FRIDAY, VENOM, JARVIS)

    fun byPrefName(name: String?): MayaCharacter =
        ALL.firstOrNull { it.prefName.equals(name, ignoreCase = true) } ?: MAYA

    fun byId(id: String?): MayaCharacter = ALL.firstOrNull { it.id == id } ?: MAYA

    /** Asset path of the bundled preview for this character + voice, or null when none is bundled. */
    fun previewAsset(context: Context, character: MayaCharacter, voice: String): String? {
        val prefix = character.previewPrefix ?: return null
        val path = "voices/${prefix}_${voice}.ogg"
        return try {
            context.assets.open(path).close()
            path
        } catch (_: Exception) {
            null
        }
    }

    /** Voices of [character] that actually have a bundled preview, in catalogue order. */
    fun voicesWithPreview(context: Context, character: MayaCharacter): List<String> =
        character.voices.filter { previewAsset(context, character, it) != null }
}
