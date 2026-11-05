package com.jarvis.assistant.automation.guard

import android.util.Log

/**
 * Detects and neutralizes prompt injection attempts that try to:
 * - Invent fake tools or bypass security controls
 * - Modify available tool registries
 * - Inject hidden instructions inside retrieved RAG documents or user notes
 */
object PromptInjectionDetector {

    private const val TAG = "PromptInjectionDetector"

    private val INJECTION_PATTERNS = listOf(
        Regex("""\b(create|register|add|invent|make)\s+(a\s+)?new\s+tool\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnew tool\s*:""", RegexOption.IGNORE_CASE),
        Regex("""\btool\s+name\s*:\s*["']?\w+["']?""", RegexOption.IGNORE_CASE),
        Regex("""\bbypass.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bskip.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisable.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo not validate\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmodify.*tool\s*list\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore\s+previous\s+instructions\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisregard\s+previous\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpretend\s+you\s+are\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+are\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsystem\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\bdeveloper\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\[SYSTEM\]""", RegexOption.IGNORE_CASE),
        Regex("""\[INST\]""", RegexOption.IGNORE_CASE),
        Regex("""<\s*tool_call\b""", RegexOption.IGNORE_CASE),
        Regex("""\{\s*"name"\s*:\s*".*?"\s*,\s*"arguments"\s*:""", RegexOption.IGNORE_CASE)
    )

    private val TOOL_INVENTION_KEYWORDS = listOf(
        "create_tool", "new_tool", "register_tool", "add_tool", "invent_tool"
    )

    /**
     * Returns true if [text] appears to contain an injection attempt.
     */
    fun isInjectionAttempt(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (TOOL_INVENTION_KEYWORDS.any { it in lower }) return true
        return INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Checks if a tool name is hallucinated or unauthorized.
     */
    fun isHallucinatedToolName(name: String, knownTools: Set<String>): Boolean {
        if (name.isBlank()) return true
        if (name !in knownTools) return true
        if (!name.matches(Regex("^[a-z][a-z0-9_]*$"))) return true
        return false
    }

    /**
     * Sanitizes retrieved RAG document content by stripping hidden tool instructions
     * before the content is placed into the system prompt.
     */
    fun sanitizeRetrievedDocument(content: String): String {
        if (content.isBlank()) return content
        var sanitized = content
        // Remove rogue tool call blocks
        sanitized = sanitized.replace(
            Regex("""<\s*tool_call\b[^>]*>.*?<\s*/\s*tool_call\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            "[removed tool call]"
        )
        sanitized = sanitized.replace(
            Regex("""\{\s*"name"\s*:\s*"[^"]*"\s*,\s*"arguments"\s*:\s*\{[^}]*\}\s*\}"""),
            "[removed tool syntax]"
        )
        // Remove prompt override injections
        sanitized = sanitized.replace(
            Regex("""\b(ignore previous instructions|disregard.*|bypass validation)[^\n]*""", RegexOption.IGNORE_CASE),
            "[filtered injection]"
        )
        if (sanitized.length != content.length) {
            Log.w(TAG, "Sanitized retrieved document (removed ${content.length - sanitized.length} potentially dangerous characters)")
        }
        return sanitized
    }

    /**
     * Validates that a user prompt does not attempt tool hijack.
     */
    fun validateUserPrompt(prompt: String): String? {
        if (isInjectionAttempt(prompt)) {
            Log.w(TAG, "Potential injection detected in user prompt: ${prompt.take(60)}")
            return "Potential prompt injection detected. Proceeding safely without tool modification."
        }
        return null
    }
}
