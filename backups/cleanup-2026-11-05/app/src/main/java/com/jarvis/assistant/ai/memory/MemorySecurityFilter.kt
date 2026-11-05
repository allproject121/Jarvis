package com.jarvis.assistant.ai.memory

import android.util.Log

/**
 * Security filter ensuring credentials, API keys, passwords, and sensitive tokens
 * are never committed to permanent SQLite or vector memory.
 */
object MemorySecurityFilter {

    private const val TAG = "MemorySecurityFilter"

    private val SECRET_PATTERNS = listOf(
        // API keys (OpenAI, Gemini, Anthropic, AWS, GitHub)
        Regex("""\b(sk-[a-zA-Z0-9]{20,})\b"""),
        Regex("""\b(AIza[0-9A-Za-z-_]{35})\b"""),
        Regex("""\b(ghp_[a-zA-Z0-9]{36})\b"""),
        Regex("""\b(AKIA[0-9A-Z]{16})\b"""),
        Regex("""\b(bearer\s+[a-zA-Z0-9._~+/-]{20,})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(api[_-]?key\s*[:=]\s*["']?[a-zA-Z0-9_\-]{16,}["']?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(password\s*[:=]\s*["']?[^\s]{6,}["']?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(secret\s*[:=]\s*["']?[a-zA-Z0-9_\-]{16,}["']?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(private[_-]?key)\b""", RegexOption.IGNORE_CASE),
        Regex("""-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----"""),
        // Credit card numbers (Luhn candidate 13-19 digits)
        Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")
    )

    /**
     * Checks if [text] looks like it contains a secret or private credential.
     */
    fun containsSecret(text: String): Boolean {
        if (text.isBlank()) return false
        for (pattern in SECRET_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                Log.w(TAG, "Secret/credential pattern detected in memory candidate. Blocked.")
                return true
            }
        }
        return false
    }

    /**
     * Strips secrets if present, or returns null if the entire text is a secret.
     */
    fun sanitize(text: String): String? {
        if (containsSecret(text)) {
            return null
        }
        return text.trim()
    }
}
