package com.jarvis.assistant.voice.normalize

import android.util.Log

/**
 * Facade used by TTS engines (ported from AndroLLM `TextNormalizationEngine`).
 *
 * Jarvis keeps a single normalization configuration ([NormalizationSettingsStore])
 * and the existing [TextNormalizationPipeline] API, so this engine simply owns the
 * shared pipeline instance, times each call and logs per-stage traces when debug
 * mode is on. Never call it on the UI thread (TTS threads only).
 */
object TextNormalizationEngine {

    private const val TAG = "TextNormalization"

    private val pipeline: TextNormalizationPipeline get() = TextNormalizationPipeline.getInstance()

    /** Normalizes [input] using the persisted settings snapshot. */
    fun normalize(input: String): NormalizationResult {
        val settings = NormalizationSettingsStore.current()
        if (input.isBlank() || !settings.enabled) return NormalizationResult(input)
        val start = System.nanoTime()
        val result = pipeline.processDetailed(input)
        if (settings.debug) {
            Log.i(TAG, "TN engine done in ${(System.nanoTime() - start) / 1_000_000L}ms (${result.traces.size} stages)")
        }
        return result
    }

    /** Explicit-settings probe (tests / settings preview); leaves the live snapshot alone. */
    fun with(settings: NormalizationSettings, input: String): NormalizationResult =
        TextNormalizationPipeline(
            stages = TextNormalizationPipeline.defaultStages(),
            settingsProvider = { settings }
        ).processDetailed(input)
}
