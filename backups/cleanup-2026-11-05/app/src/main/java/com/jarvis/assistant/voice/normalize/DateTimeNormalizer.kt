package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes dates, ISO timestamps, years, and times into spoken format.
 * e.g., "14:30" -> "two thirty pm", "2026-10-31" -> "October thirty first twenty twenty six".
 */
class DateTimeNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val MONTHS = arrayOf(
            "", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        private val DATE_SLASH = Pattern.compile("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")
        private val DATE_ISO = Pattern.compile("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
        private val YEAR = Pattern.compile("""(?<![0-9A-Za-z])(19[7-9][0-9]|20[0-3][0-9])(?![0-9A-Za-z])""")
        private val TIME = Pattern.compile(
            """\b(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([ap])\.?m\.?)?\b""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private fun replaceAll(input: String): String {
        var text = input

        text = DATE_ISO.matcher(text).replaceAllCompat { m ->
            dateToWords(m.group(2), m.group(3), m.group(1)) ?: m.group()
        }

        text = DATE_SLASH.matcher(text).replaceAllCompat { m ->
            dateToWords(m.group(1), m.group(2), m.group(3)) ?: m.group()
        }

        text = TIME.matcher(text).replaceAllCompat { m ->
            timeToWords(m) ?: m.group()
        }

        text = YEAR.matcher(text).replaceAllCompat { m ->
            yearToWords(m.group().toLongOrNull() ?: return@replaceAllCompat m.group())
        }

        return text
    }

    private fun dateToWords(monthStr: String, dayStr: String, yearStr: String): String? {
        val m = monthStr.toIntOrNull() ?: return null
        val d = dayStr.toIntOrNull() ?: return null
        val y = yearStr.toLongOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return MONTHS[m] + " " + SpeechNumbers.ordinal(d.toLong()) + " " + yearToWords(y)
    }

    private fun yearToWords(y: Long): String = when {
        y in 2000..2009 -> "two thousand " + SpeechNumbers.int(y - 2000)
        y in 1900..2099 -> SpeechNumbers.int(y / 100) +
                if (y % 100 == 0L) "" else " " + SpeechNumbers.int(y % 100)
        else -> SpeechNumbers.int(y)
    }

    private fun timeToWords(m: java.util.regex.MatchResult): String? {
        val hour = m.group(1).toIntOrNull() ?: return null
        val minute = m.group(2).toIntOrNull() ?: return null
        val second = m.group(3)?.toIntOrNull()
        if (hour < 0 || hour > 23 || minute !in 0..59) return null
        val marker = m.group(4)?.lowercase()

        var h = hour
        var suffix = ""
        when (marker) {
            "a" -> { if (h == 12) h = 0; suffix = " am" }
            "p" -> { if (h != 12) h %= 12; suffix = " pm" }
            else -> if (h >= 12) {
                if (h > 12) h -= 12
                suffix = " pm"
            } else {
                if (h == 0) h = 12
                suffix = " am"
            }
        }
        val sb = StringBuilder(SpeechNumbers.int(h.toLong()))
        when {
            minute == 0 && second == null -> Unit
            minute < 10 -> sb.append(" oh ").append(SpeechNumbers.int(minute.toLong()))
            else -> sb.append(' ').append(SpeechNumbers.int(minute.toLong()))
        }
        if (second != null && second > 0) {
            sb.append(' ').append(SpeechNumbers.int(second.toLong())).append(" seconds")
        }
        sb.append(suffix)
        return sb.toString()
    }
}
