package com.jarvis.assistant.util

/**
 * Normalizes Devanagari Hindi text to Romanized Hinglish equivalents.
 * Ensures that offline voice commands spoken in Hindi/Hinglish (whether transcribed in
 * Devanagari script or Latin script by speech engines) map seamlessly to CommandParser
 * and LocalAiEngine intent matchers.
 */
object DevanagariNormalizer {

    private val DEVANAGARI_WORD_MAP = mapOf(
        // Actions
        "खोलो" to "kholo",
        "खोल" to "kholo",
        "खोलें" to "kholo",
        "खोलिये" to "kholo",
        "खोलिए" to "kholo",
        "ओपन" to "open",
        "स्टार्ट" to "start",
        "शुरू" to "start",
        "लॉन्च" to "launch",
        "चलाओ" to "chalao",
        "चला" to "chalao",
        "चलाएं" to "chalao",
        "चलाइए" to "chalao",
        "चालू" to "chalu",
        "ऑन" to "on",
        "ऑफ" to "off",
        "जलाओ" to "jalao",
        "जला" to "jalao",
        "जलाएं" to "jalao",
        "जलाइए" to "jalao",
        "बंद" to "band",
        "बुझाओ" to "bujhao",
        "बुझा" to "bujhao",
        "बुझाएं" to "bujhao",
        "बुझाइए" to "bujhao",
        "बढ़ाओ" to "badhao",
        "बढ़ा" to "badhao",
        "बढ़ाइए" to "badhao",
        "तेज" to "tez",
        "कम" to "kam",
        "घटाओ" to "ghatao",
        "धीमा" to "slow",
        "करो" to "karo",
        "कर" to "karo",
        "करें" to "karo",
        "दो" to "do",
        "दीजिए" to "do",
        "कीजिए" to "karo",
        "खींचो" to "khincho",
        "खींच" to "khincho",
        "ले" to "lo",
        "लो" to "lo",
        "लीजिए" to "lo",
        "सुनाओ" to "sunao",
        "सुना" to "sunao",
        "सुनाइए" to "sunao",
        "बताओ" to "batao",
        "बता" to "batao",
        "बताइए" to "batao",
        "दिखाओ" to "show",
        "दिखा" to "show",
        "रोको" to "stop",
        "रुक" to "stop",
        "रुकिए" to "stop",
        "रुक जाओ" to "stop",
        "बस" to "stop",

        // Targets / Apps / Entities
        "कैमरा" to "camera",
        "कैमरे" to "camera",
        "फोटो" to "photo",
        "तस्वीर" to "photo",
        "तस्वीरें" to "photo",
        "सेल्फी" to "selfie",
        "टॉर्च" to "torch",
        "टार्च" to "torch",
        "फ्लैशलाइट" to "flashlight",
        "फ्लैश" to "flash",
        "बत्ती" to "batti",
        "लाइट" to "light",
        "व्हाट्सएप" to "whatsapp",
        "वॉट्सएप" to "whatsapp",
        "व्हाट्सअप" to "whatsapp",
        "यूट्यूब" to "youtube",
        "फोनपे" to "phonepe",
        "पेटीएम" to "paytm",
        "गूगल" to "google",
        "पे" to "pay",
        "जीपे" to "gpay",
        "सेटिंग्स" to "settings",
        "सेटिंग" to "settings",
        "गैलरी" to "gallery",
        "क्रोम" to "chrome",
        "ब्राउज़र" to "browser",
        "कैलकुलेटर" to "calculator",
        "घड़ी" to "clock",
        "क्लॉक" to "clock",
        "नक्शा" to "maps",
        "मैप्स" to "maps",
        "कैलेंडर" to "calendar",
        "फाइल्स" to "files",

        // Controls
        "वॉल्यूम" to "volume",
        "आवाज" to "aawaz",
        "ध्वनि" to "volume",
        "म्यूट" to "mute",
        "शांत" to "mute",
        "चुप" to "mute",
        "ब्राइटनेस" to "brightness",
        "रोशनी" to "brightness",
        "बैटरी" to "battery",
        "चार्ज" to "charge",
        "चार्जिंग" to "charging",
        "समय" to "time",
        "टाइम" to "time",
        "बजे" to "baje",
        "तारीख" to "date",
        "दिन" to "date",
        "डेट" to "date",
        "चुटकुला" to "joke",
        "जोक" to "joke",

        // Greetings & Conversational
        "नमस्ते" to "hello",
        "नमस्कार" to "hello",
        "हेलो" to "hello",
        "हाय" to "hi",
        "जार्विस" to "jarvis",
        "सुनो" to "suno",
        "सुन" to "suno",
        "कैसे" to "kaise",
        "हो" to "ho",
        "हाल" to "haal",
        "क्या" to "kya",
        "है" to "hai",
        "हैं" to "hain",
        "कौन" to "kaun",
        "सकते" to "sakte",
        "सकता" to "sakta",
        "काम" to "kaam",
        "धन्यवाद" to "thank you",
        "शुक्रिया" to "thank you",
        "अच्छा" to "accha",
        "ठीक" to "theek",
        "पैसे" to "paise",
        "रुपये" to "paise",
        "रुपए" to "paise",
        "निकाल" to "nikal",
        "भेजो" to "bhejo"
    )

    /**
     * Checks if the given text contains any Devanagari script characters.
     */
    fun containsDevanagari(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    /**
     * Replaces Devanagari Hindi words with their standardized Hinglish equivalents.
     * Leaves English words intact.
     */
    fun normalize(text: String): String {
        if (!containsDevanagari(text)) return text.trim()

        val tokens = text.trim().split(Regex("\\s+"))
        val normalizedTokens = tokens.map { token ->
            val cleanToken = token.replace(Regex("[.,!?:;\"'।]"), "")
            DEVANAGARI_WORD_MAP[cleanToken] ?: cleanToken
        }
        return normalizedTokens.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }
}
