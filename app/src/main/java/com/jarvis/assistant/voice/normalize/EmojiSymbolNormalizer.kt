package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes Markdown, code symbols, bullets, and emojis into clean, TTS-friendly text.
 *
 * Emoji lookup: AndroLLM's full [EmojiDictionary] first (longest-sequence match, so
 * compound glyphs like flags and "❤️" resolve before their parts), then Jarvis's
 * single-code-point fallback table, then unmapped emoji are dropped so the TTS
 * never reads surrogate noise. Jarvis's cleanup rules are kept: apostrophes and
 * sentence punctuation (. ! ?) survive for natural intonation.
 */
class EmojiSymbolNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val MARKDOWN_TAGS = Pattern.compile("""[#*_`~\[\]{}]+""")
        private val MULTI_DOT = Pattern.compile("""\.{2,}""")
        private val BULLET_POINTS = Pattern.compile("""^[\s*•\-–—]+\s*""", Pattern.MULTILINE)
        private val EXCESS_PUNCTUATION = Pattern.compile("""[|/\\<>^]+""")
        private val AMP = Pattern.compile("""\s*&\s*""")
        private val SPACES = Regex("""\s{2,}""")

        /** Longest emoji sequence the dictionary holds (ZWJ / flag pairs). */
        private const val MAX_EMOJI_CHARS = 5

        private const val VARIATION_SELECTOR_16 = 0xFE0F
        private const val VARIATION_SELECTOR_15 = 0xFE0E
        private const val ZERO_WIDTH_JOINER = 0x200D
    }

    private fun replaceAll(input: String): String {
        var text = input

        // Emoji first, before markdown cleanup can split sequences.
        text = describeEmoji(text)

        // Clean markdown syntax
        text = MARKDOWN_TAGS.matcher(text).replaceAll(" ")
        text = BULLET_POINTS.matcher(text).replaceAll("")
        text = AMP.matcher(text).replaceAll(" and ")
        text = EXCESS_PUNCTUATION.matcher(text).replaceAll(" ")
        text = MULTI_DOT.matcher(text).replaceAll(". ")

        return SPACES.replace(text.trim(), " ")
    }

    private fun describeEmoji(text: String): String {
        val sb = StringBuilder(text.length + 32)
        var i = 0
        while (i < text.length) {
            val dict = matchDictionary(text, i)
            if (dict != null) {
                sb.append(' ').append(dict.second).append(' ')
                i += dict.first
                continue
            }
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val fallback = getEmojiDescription(codePoint)
            when {
                fallback != null -> sb.append(' ').append(fallback).append(' ')
                codePoint == VARIATION_SELECTOR_16 ||
                    codePoint == VARIATION_SELECTOR_15 ||
                    codePoint == ZERO_WIDTH_JOINER -> Unit
                isEmojiCodePoint(codePoint) -> sb.append(' ') // unmapped emoji: skip
                else -> sb.appendCodePoint(codePoint)
            }
            i += charCount
        }
        return sb.toString()
    }

    /** Longest-first [EmojiDictionary] lookup at [i]; null when it's plain text. */
    private fun matchDictionary(text: String, i: Int): Pair<Int, String>? {
        // Cheap guard: every dictionary key starts with a non-ASCII char.
        if (text[i].code < 0x80) return null
        for (len in MAX_EMOJI_CHARS downTo 1) {
            if (i + len <= text.length) {
                val words = EmojiDictionary.lookup(text.substring(i, i + len))
                if (words != null) return len to words
            }
        }
        return null
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        return (cp in 0x1F600..0x1F64F) || // Emoticons
                (cp in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
                (cp in 0x1F680..0x1F6FF) || // Transport and Map
                (cp in 0x1F700..0x1F77F) || // Alchemical Symbols
                (cp in 0x1F780..0x1F7FF) || // Geometric Shapes
                (cp in 0x1F800..0x1F8FF) || // Supplemental Arrows-C
                (cp in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
                (cp in 0x1FA00..0x1FA6F) || // Chess Symbols
                (cp in 0x1FA70..0x1FAFF) || // Symbols and Pictographs Extended-A
                (cp in 0x1F1E6..0x1F1FF) || // Regional indicators (unmapped flags)
                (cp in 0x1F3FB..0x1F3FF) || // Skin tone modifiers
                (cp in 0x2600..0x26FF)   || // Misc symbols
                (cp in 0x2700..0x27BF)      // Dingbats
    }

    /** Jarvis's original single-code-point table (used when the dictionary misses). */
    private fun getEmojiDescription(cp: Int): String? = when (cp) {
        0x1F600, 0x1F603, 0x1F604 -> "smiling"
        0x1F602 -> "laughing"
        0x1F609 -> "winking"
        0x1F60D -> "heart eyes"
        0x1F914 -> "thinking"
        0x1F44D -> "thumbs up"
        0x1F44E -> "thumbs down"
        0x1F525 -> "fire"
        0x2764, 0x1F499, 0x1F49A -> "heart"
        0x1F4A1 -> "idea"
        0x26A1 -> "lightning"
        0x1F389 -> "celebration"
        0x2705 -> "check mark"
        0x274C -> "cross mark"
        0x1F680 -> "rocket"
        0x1F4BB -> "laptop"
        0x1F4F1 -> "phone"
        else -> null
    }
}
