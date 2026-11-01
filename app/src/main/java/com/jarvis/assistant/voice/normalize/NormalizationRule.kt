package com.jarvis.assistant.voice.normalize

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * One user-definable normalization rule (ported from AndroLLM).
 *
 * Rules apply in [priority] order (lower first); [enabled] can be flipped
 * at runtime without touching code. This is the extension point for custom
 * normalizations (new symbols, formulas, domain words, ...).
 */
data class NormalizationRule(
    val name: String,
    val pattern: Pattern,
    val replacement: String,
    val priority: Int = 100,
    val enabled: Boolean = true
) {
    companion object {
        /** Compile-safe factory - a bad regex yields null, not a crash. */
        fun of(
            name: String,
            regex: String,
            replacement: String,
            priority: Int = 100,
            enabled: Boolean = true
        ): NormalizationRule? = try {
            if (regex.isEmpty()) null
            else NormalizationRule(name, Pattern.compile(regex), replacement, priority, enabled)
        } catch (e: PatternSyntaxException) {
            null
        }

        /** Default roll of extra rules layered on top of the stage processors. */
        fun builtIns(): List<NormalizationRule> = listOfNotNull(
            of("arrow", """->|=>|&#8594;""", "to ", 3),
            of("em dash", "—", ", ", 10),
            of("en dash", "–", "- ", 10),
            of("bullet", "•", " ", 10),
            of("ellipsis char", "…", " ", 10),
            of("nbsp", " ", " ", 5),
            of("section", "§", " section ", 10),
            of("copyright", "©", " copyright ", 10),
            of("registered", "®", " registered ", 10),
            of("tm", "™", " trademark ", 10)
        )
    }
}

/**
 * Ordered regex rules applied as one pipeline stage - the "custom rule engine":
 * rules are data, not code, so future normalizers slot in without touching
 * stage implementations. A rule whose replacement is invalid for its match
 * (e.g. a bad group reference in a user rule) is skipped, never crashes TTS.
 */
class CustomRuleProcessor(
    rules: List<NormalizationRule>
) : TextProcessor {

    private val enabledRules: List<NormalizationRule> = rules
        .filter { it.enabled }
        .sortedBy { it.priority }

    override fun process(input: String): String {
        var text = input
        for (rule in enabledRules) {
            text = try {
                rule.pattern.matcher(text).replaceAll(rule.replacement)
            } catch (e: RuntimeException) {
                text
            }
        }
        return text
    }
}
