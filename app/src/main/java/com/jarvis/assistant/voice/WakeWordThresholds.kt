package com.jarvis.assistant.voice

/**
 * Vosk standby wake-word confidence thresholds, scaled by the user's wake-word sensitivity
 * (VoiceSettingsStore.sensitivity — the Customization Studio slider, 0.1..1.0).
 * Higher sensitivity → lower thresholds (wakes more easily, more false positives).
 *
 * Both default slider positions (0.50 = fresh VoiceSettings, 0.65 = Studio "reset") sit in a
 * neutral band that keeps the long-standing 0.70 / 0.75 / 0.65 thresholds unchanged.
 */
data class WakeWordThresholds(
    /** Minimum confidence of the word "jarvis" itself. */
    val jarvisWord: Double,
    /** Minimum average confidence when "jarvis" is said on its own. */
    val standalone: Double,
    /** Minimum average confidence for phrases like "hey jarvis". */
    val phrase: Double
) {
    companion object {
        val DEFAULT = WakeWordThresholds(jarvisWord = 0.70, standalone = 0.75, phrase = 0.65)

        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 1.0f
        const val NEUTRAL_LOW = 0.50f
        const val NEUTRAL_HIGH = 0.65f
        private const val BAND_SLACK = 0.001f

        /** Threshold change per unit of sensitivity outside the neutral band. */
        const val SCALE = 0.6

        const val MIN_THRESHOLD = 0.45
        const val MAX_THRESHOLD = 0.90

        fun forSensitivity(sensitivity: Float): WakeWordThresholds {
            val s = if (sensitivity.isNaN()) NEUTRAL_LOW else sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
            // Small slack so slider float noise (0.30f + 7 * 0.05f = 0.65000004f) stays neutral.
            val delta = when {
                s > NEUTRAL_HIGH + BAND_SLACK -> (s - NEUTRAL_HIGH).toDouble()
                s < NEUTRAL_LOW - BAND_SLACK -> (s - NEUTRAL_LOW).toDouble()
                else -> 0.0
            }
            if (delta == 0.0) return DEFAULT
            val shift = delta * SCALE
            return WakeWordThresholds(
                jarvisWord = scaled(DEFAULT.jarvisWord, shift),
                standalone = scaled(DEFAULT.standalone, shift),
                phrase = scaled(DEFAULT.phrase, shift)
            )
        }

        private fun scaled(base: Double, shift: Double): Double =
            (base - shift).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }
}
