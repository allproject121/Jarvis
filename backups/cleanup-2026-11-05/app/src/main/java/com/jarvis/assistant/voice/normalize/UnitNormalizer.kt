package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes units of measurement into spoken words.
 * e.g., "10km" -> "10 kilometers", "500MB" -> "500 megabytes", "60fps" -> "60 frames per second".
 */
class UnitNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val UNIT_EXPANSIONS = mapOf(
            "km" to "kilometers",
            "m" to "meters",
            "cm" to "centimeters",
            "mm" to "millimeters",
            "kg" to "kilograms",
            "g" to "grams",
            "mg" to "milligrams",
            "kb" to "kilobytes",
            "mb" to "megabytes",
            "gb" to "gigabytes",
            "tb" to "terabytes",
            "hz" to "hertz",
            "khz" to "kilohertz",
            "mhz" to "megahertz",
            "ghz" to "gigahertz",
            "fps" to "frames per second",
            "ms" to "milliseconds",
            "sec" to "seconds",
            "min" to "minutes",
            "hr" to "hours",
            "hrs" to "hours"
        )

        private val UNIT_PATTERN = Pattern.compile(
            """(?<=\d)\s*(km|cm|mm|kg|mg|kb|mb|gb|tb|khz|mhz|ghz|fps|ms|sec|min|hrs?)\b""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private fun replaceAll(input: String): String {
        return UNIT_PATTERN.matcher(input).replaceAllCompat { m ->
            val unit = m.group(1).lowercase()
            val expanded = UNIT_EXPANSIONS[unit] ?: unit
            " $expanded"
        }
    }
}
