package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes phone numbers into clearly paced spoken digits.
 * e.g., "+91-9876543210" -> "plus nine one, nine eight seven, six five four, three two one zero".
 */
class PhoneNumberNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val PHONE_PATTERN = Pattern.compile(
            """(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b"""
        )
    }

    private fun replaceAll(input: String): String {
        return PHONE_PATTERN.matcher(input).replaceAllCompat { m ->
            val raw = m.group()
            val sb = StringBuilder()
            if (raw.startsWith("+")) sb.append("plus ")
            for (ch in raw) {
                if (ch.isDigit()) {
                    sb.append(digitToWord(ch)).append(' ')
                } else if (ch == '-' || ch == ' ') {
                    sb.append(", ")
                }
            }
            sb.toString().trim()
        }
    }

    private fun digitToWord(digit: Char): String = when (digit) {
        '0' -> "zero"
        '1' -> "one"
        '2' -> "two"
        '3' -> "three"
        '4' -> "four"
        '5' -> "five"
        '6' -> "six"
        '7' -> "seven"
        '8' -> "eight"
        '9' -> "nine"
        else -> ""
    }
}
