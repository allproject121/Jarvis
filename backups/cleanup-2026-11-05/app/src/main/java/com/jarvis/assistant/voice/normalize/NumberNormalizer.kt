package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes numbers, decimals, negatives, percentages, ordinals, simple fractions,
 * version numbers, IP addresses, and Roman numerals into clear spoken words.
 */
class NumberNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val NUMBER = Pattern.compile("""(?<![\w.])-?\d+(?:,\d{3})*(?:\.\d+)?(?![\w.])""")
        private val PERCENT = Pattern.compile("""(?<![\w.])-?\d+(?:,\d{3})*(?:\.\d+)?\s*%(?![\w.])""")
        private val VERSION = Pattern.compile("""(?<![\w.])v\d+(?:\.\d+)+""", Pattern.CASE_INSENSITIVE)
        private val IP = Pattern.compile("""(?<![\w.])-?\d{1,3}(?:\.\d{1,3}){3}(?![\w.])""")
        private val FRACTION = Pattern.compile("""(?<![\w.])-?\d{1,2}/\d{1,2}(?![\w/])""")
        private val ORDINAL = Pattern.compile("""(?<![\w.])-?\d+(?:st|nd|rd|th)(?![\w])""")
        private val ROMAN = Pattern.compile("""(?<![\w])[IVXLCDM]{2,7}(?![\w])""")
    }

    private fun replaceAll(input: String): String {
        var text = input

        // Percentages: "94%" -> "ninety four percent"
        text = PERCENT.matcher(text).replaceAllCompat { m ->
            percentToWords(m.group())
        }

        // Versions: "v2.4.1" -> "version two point four point one"
        text = VERSION.matcher(text).replaceAllCompat { m -> versionToWords(m.group()) }

        // IP addresses: "192.168.1.1" -> "one nine two dot one six eight dot one dot one"
        text = IP.matcher(text).replaceAllCompat { m -> ipToWords(m.group()) }

        // Fractions: "1/2" -> "one half", "3/4" -> "three quarters"
        text = FRACTION.matcher(text).replaceAllCompat { m -> fractionToWords(m.group()) ?: m.group() }

        // Ordinals: "21st" -> "twenty first"
        text = ORDINAL.matcher(text).replaceAllCompat { m -> ordinalToWords(m.group()) }

        // General integers and decimals: "3.14" -> "three point one four"
        text = NUMBER.matcher(text).replaceAllCompat { m -> numberToWords(m.group()) }

        // Roman numerals: "IV" -> "four"
        text = ROMAN.matcher(text).replaceAllCompat { m ->
            val v = romanValue(m.group())
            if (v != null && m.group().length >= 2) SpeechNumbers.int(v.toLong()) else m.group()
        }

        return text
    }

    fun numberToWords(raw: String): String {
        val negative = raw.startsWith("-")
        val body = raw.trim().removePrefix("-")
        val decimalPart = body.substringAfter('.', missingDelimiterValue = "!")
        val intPart = body.substringBefore('.')
        val intValue = try {
            intPart.replace(",", "").toLong()
        } catch (_: NumberFormatException) {
            return raw
        }
        val sb = StringBuilder()
        if (negative) sb.append("minus ")
        sb.append(SpeechNumbers.int(intValue))
        if (decimalPart != "!" && decimalPart.isNotEmpty() && decimalPart.all { it.isDigit() }) {
            sb.append(" point")
            for (digit in decimalPart) sb.append(' ').append(digitToWord(digit))
        }
        return sb.toString()
    }

    private fun percentToWords(token: String): String {
        val body = token.removeSuffix("%").trim()
        return numberToWords(body) + " percent"
    }

    private fun fractionToWords(raw: String): String? {
        val negative = raw.startsWith("-")
        val parts = raw.removePrefix("-").split("/")
        if (parts.size != 2) return null
        val numerator = parts[0].removeSuffix(".").toLongOrNull() ?: return null
        val denominator = parts[1].removeSuffix(".").toLongOrNull() ?: return null
        if (numerator !in 1..12 || denominator !in 2..12) return null
        val denWords = when (denominator) {
            2L -> if (numerator == 1L) "half" else "halves"
            3L -> "thirds"
            4L -> "quarters"
            8L -> "eighths"
            10L -> "tenths"
            12L -> "twelfths"
            else -> SpeechNumbers.ordinal(denominator) + "s"
        }
        return (if (negative) "minus " else "") + SpeechNumbers.int(numerator) + " " + denWords
    }

    private fun ordinalToWords(raw: String): String {
        val body = raw
            .removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th")
        val n = body.toLongOrNull() ?: return raw
        return SpeechNumbers.ordinal(n)
    }

    private fun versionToWords(raw: String): String {
        val parts = raw.removePrefix("v").removePrefix("V").split(".")
        val sb = StringBuilder("version")
        for (part in parts) {
            val num = part.toLongOrNull()
            if (num != null) {
                sb.append(' ').append(SpeechNumbers.int(num))
            } else {
                sb.append(' ').append(part)
            }
            sb.append(" point")
        }
        return sb.toString().removeSuffix(" point")
    }

    private fun ipToWords(raw: String): String {
        val parts = raw.split(".")
        val sb = StringBuilder()
        for (i in parts.indices) {
            if (i > 0) sb.append(" dot ")
            for (digit in parts[i]) {
                sb.append(digitToWord(digit)).append(' ')
            }
        }
        return sb.toString().trim()
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
        else -> digit.toString()
    }

    private fun romanValue(raw: String): Int? {
        val romanMap = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in raw.uppercase().reversed()) {
            val v = romanMap[c] ?: return null
            if (v < prev) total -= v else total += v
            prev = v
        }
        return if (total in 1..3999) total else null
    }
}
