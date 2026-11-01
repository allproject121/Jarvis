package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes technical abbreviations and acronyms into natural spoken words or
 * spelled-out letters, e.g. "AI" -> "a i", "GPU" -> "g p u", "Wi-Fi" -> "wi fi".
 *
 * Dictionary first ([AbbreviationDictionary], shared with AndroLLM's table), then
 * unknown ALL-CAPS tokens of 2..5 characters fall back to letter-by-letter speech.
 */
class AbbreviationNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val TERM = Pattern.compile("""(?<![\w])[A-Za-z0-9.\-#]{2,8}(?![\w])""")
        private val ALL_CAPS = Pattern.compile("""^[A-Z0-9-]+$""")
    }

    private fun replaceAll(input: String): String {
        val sb = StringBuilder(input.length)
        val m = TERM.matcher(input)
        var last = 0
        while (m.find()) {
            sb.append(input, last, m.start())
            val token = m.group()
            val resolved = AbbreviationDictionary.resolve(token)
            if (resolved != null) {
                sb.append(resolved)
            } else if (ALL_CAPS.matcher(token).matches() && token.length in 2..5) {
                for (ch in token.lowercase()) {
                    if (ch in 'a'..'z') sb.append(ch).append(' ')
                }
            } else {
                sb.append(token)
            }
            last = m.end()
        }
        sb.append(input, last, input.length)
        return sb.toString()
    }
}
