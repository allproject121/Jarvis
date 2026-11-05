package com.jarvis.assistant.voice.normalize

import android.util.Log
import java.util.regex.Pattern

/**
 * Named pipeline stage: id (for debug traces), the [TextProcessor], and a gate
 * deciding whether it runs for a given [NormalizationSettings] snapshot.
 * (Ported from AndroLLM.)
 */
class NamedStage(
    val id: String,
    val processor: TextProcessor,
    val enabled: (NormalizationSettings) -> Boolean
)

/** Result of one pipeline pass; [traces] populated when debug mode is on. */
data class NormalizationResult(
    val text: String,
    val traces: List<Trace> = emptyList()
) {
    data class Trace(val stageId: String, val input: String, val output: String)
}

/**
 * Sequential Text Normalization Pipeline for Text-to-Speech (TTS).
 * Converts raw LLM outputs (decimals, code tokens, math, symbols, currencies, numbers, dates)
 * into high-fidelity, natural spoken words.
 *
 * Each stage is gated by the persisted [NormalizationSettings] (Settings → Voice →
 * Text normalization). `process(String): String` keeps its original contract for
 * existing callers (OfflineVoiceConversationEngine).
 */
class TextNormalizationPipeline(
    private val stages: List<NamedStage> = defaultStages(),
    private val settingsProvider: () -> NormalizationSettings = { NormalizationSettingsStore.current() }
) {

    /** Normalizes [input] for speech; returns [input] untouched when the master switch is off. */
    fun process(input: String): String = processDetailed(input).text

    fun processDetailed(input: String): NormalizationResult {
        if (input.isBlank()) return NormalizationResult("")
        val settings = settingsProvider()
        if (!settings.enabled) return NormalizationResult(input)
        val start = System.nanoTime()
        val traces = if (settings.debug) ArrayList<NormalizationResult.Trace>(stages.size) else null
        var current = input
        for (stage in stages) {
            if (!stage.enabled(settings)) continue
            val before = current
            val after = try {
                stage.processor.process(before)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Stage '${stage.id}' failed: ${e.message}")
                before
            }
            traces?.add(NormalizationResult.Trace(stage.id, before, after))
            current = after
        }
        val result = NormalizationResult(finish(current), traces ?: emptyList())
        if (traces != null) {
            for (t in traces) Log.i(TAG, "TN [${t.stageId}]: '${t.input}' -> '${t.output}'")
            Log.i(TAG, "TN done in ${(System.nanoTime() - start) / 1_000_000L}ms: '${result.text}'")
        }
        return result
    }

    private fun finish(text: String): String {
        var t = text.trim()
            .replace(' ', ' ')
            .replace(Regex("""\s{2,}"""), " ")
        t = TRIM_PUNCT.matcher(t).replaceAll("")
        t = STRAY_QUOTES.matcher(t).replaceAll("")
        return t.trim()
    }

    companion object {
        private const val TAG = "TtsNormalization"

        // Keep . ! ? so TTS gets sentence/question intonation; keep apostrophes ("I'm", "we'll").
        private val TRIM_PUNCT = Pattern.compile("""[,;:]+$""")
        private val STRAY_QUOTES = Pattern.compile("""["`]""")

        @Volatile
        private var sharedPipeline: TextNormalizationPipeline? = null

        fun getInstance(): TextNormalizationPipeline {
            return sharedPipeline ?: synchronized(this) {
                sharedPipeline ?: TextNormalizationPipeline().also { sharedPipeline = it }
            }
        }

        /**
         * The standard stage bank arranged in load-bearing priority order:
         * 0. Rules (built-in + user regex rules) - run on the raw text so arrows
         *    ("->") and user domain words are seen before symbol cleanup.
         * 1. URLs & Emails (protect "google.com" before "." becomes "point")
         * 2. Phone numbers (digit runs before single number spelling)
         * 3. Currency ("$19.99" -> "nineteen dollars and ninety-nine cents")
         * 4. Dates & Times ("14:30" consumed before numbers)
         * 5. Units ("10km" -> "10 kilometers")
         * 6. Math ("2+2", "16:9")
         * 7. Numbers (integers, decimals, %, ordinals, versions, Roman numerals)
         * 8. Abbreviations ("GPU" -> "g p u", "Wi-Fi" -> "wi fi")
         * 9. Emoji & Symbols (full emoji dictionary, markdown and glyph cleanup)
         */
        fun defaultStages(): List<NamedStage> = listOf(
            NamedStage("Custom rules", UserRuleProcessor(), { it.customRules }),
            NamedStage("Built-in rules", CustomRuleProcessor(NormalizationRule.builtIns()), { it.builtInRules }),
            NamedStage("URLs & Emails", UrlEmailNormalizer(), { it.urlsEmails }),
            NamedStage("Phone numbers", PhoneNumberNormalizer(), { it.phones }),
            NamedStage("Currency", CurrencyNormalizer(), { it.currency }),
            NamedStage("Dates & Times", DateTimeNormalizer(), { it.dates }),
            NamedStage("Units", UnitNormalizer(), { it.units }),
            NamedStage("Math", MathExpressionNormalizer(), { it.math }),
            NamedStage("Numbers", NumberNormalizer(), { it.numbers }),
            NamedStage("Abbreviations", AbbreviationNormalizer(), { it.abbreviations }),
            NamedStage("Emoji & Symbols", EmojiSymbolNormalizer(), { it.emoji })
        )
    }
}

/**
 * Applies the user's persisted regex rules; recompiles only when the store's
 * revision changes.
 */
private class UserRuleProcessor : TextProcessor {
    @Volatile private var compiledRevision = -1
    @Volatile private var processor = CustomRuleProcessor(emptyList())

    override fun process(input: String): String {
        val rules = NormalizationSettingsStore.currentRules()
        val revision = NormalizationSettingsStore.revision
        if (revision != compiledRevision) {
            processor = CustomRuleProcessor(rules.mapNotNull { it.toRule() })
            compiledRevision = revision
        }
        return processor.process(input)
    }
}
