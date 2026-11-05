package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes currency symbols and codes into clear spoken sentences.
 * e.g., "$20" -> "twenty dollars", "₹500" -> "five hundred rupees", "€19.99" -> "nineteen euros and ninety-nine cents".
 */
class CurrencyNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val SYMBOL_TO_CURRENCY = mapOf(
            "$" to "dollars",
            "€" to "euros",
            "£" to "pounds",
            "¥" to "yen",
            "₹" to "rupees",
            "₽" to "rubles",
            "₩" to "won",
            "₺" to "liras"
        )

        private val CODE_TO_CURRENCY = mapOf(
            "USD" to "dollars",
            "EUR" to "euros",
            "GBP" to "pounds",
            "JPY" to "yen",
            "INR" to "rupees",
            "RUB" to "rubles",
            "KRW" to "won",
            "TRY" to "liras",
            "CAD" to "canadian dollars",
            "AUD" to "australian dollars"
        )

        private val SYMBOL_AMOUNT = Pattern.compile(
            """([$€£¥₹₽₩₺])\s*(-?\d+(?:,\d{3})*(?:\.\d+)?)"""
        )

        private val CODE_AMOUNT = Pattern.compile(
            """\b(USD|EUR|GBP|JPY|INR|RUB|KRW|TRY|CAD|AUD)\s+(-?\d+(?:,\d{3})*(?:\.\d+)?)\b"""
        )
    }

    private fun replaceAll(input: String): String {
        var text = input

        text = SYMBOL_AMOUNT.matcher(text).replaceAllCompat { m ->
            amountToWords(m.group(1), m.group(2)) ?: m.group()
        }

        text = CODE_AMOUNT.matcher(text).replaceAllCompat { m ->
            amountToWords(m.group(1), m.group(2)) ?: m.group()
        }

        text = text.replace("$", " dollars ")
        text = text.replace("€", " euros ")
        text = text.replace("₹", " rupees ")
        text = text.replace("£", " pounds ")
        text = text.replace("¥", " yen ")
        return text
    }

    private fun amountToWords(symbol: String, amount: String): String? {
        val name = CODE_TO_CURRENCY[symbol] ?: SYMBOL_TO_CURRENCY[symbol] ?: return null
        val negative = amount.startsWith("-")
        val body = amount.removePrefix("-")
        val whole = body.substringBefore('.').replace(",", "").toLongOrNull() ?: return null
        val centsStr = body.substringAfter('.', missingDelimiterValue = "")
        val cents = if (centsStr.isEmpty()) null else {
            centsStr.padEnd(2, '0').take(2).toLongOrNull()?.takeIf { it > 0 }
        }

        val sb = StringBuilder()
        if (negative) sb.append("minus ")
        sb.append(SpeechNumbers.int(whole))
        sb.append(' ').append(name)
        if (cents != null) {
            sb.append(" and ").append(SpeechNumbers.int(cents)).append(" cents")
        }
        return sb.toString()
    }
}
