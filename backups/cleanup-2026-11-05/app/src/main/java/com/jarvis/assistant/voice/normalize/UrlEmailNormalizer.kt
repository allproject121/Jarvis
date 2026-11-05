package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern

/**
 * Normalizes URLs and emails into clear spoken phrases.
 * e.g., "google.com" -> "google dot com", "roshan@gmail.com" -> "roshan at gmail dot com".
 */
class UrlEmailNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val URL_PATTERN = Pattern.compile(
            """\b(https?://)?([a-zA-Z0-9-]+\.)+(com|org|net|edu|gov|io|ai|in|co|dev|app)\b(/[a-zA-Z0-9._~:/?#\[\]@!$&'()*+,;=-]*)?""",
            Pattern.CASE_INSENSITIVE
        )

        private val EMAIL_PATTERN = Pattern.compile(
            """\b([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\.[a-zA-Z]{2,})\b"""
        )
    }

    private fun replaceAll(input: String): String {
        var text = input

        text = EMAIL_PATTERN.matcher(text).replaceAllCompat { m ->
            val user = m.group(1).replace(".", " dot ")
            val domain = m.group(2).replace(".", " dot ")
            "$user at $domain"
        }

        text = URL_PATTERN.matcher(text).replaceAllCompat { m ->
            var url = m.group()
            url = url.removePrefix("https://").removePrefix("http://")
            url = url.replace(".", " dot ")
            url = url.replace("/", " slash ")
            url
        }

        return text
    }
}
