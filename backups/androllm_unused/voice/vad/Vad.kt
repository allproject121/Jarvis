package com.jarvis.assistant.voice.vad

/**
 * Clean abstraction for Voice Activity Detection (VAD).
 */
interface VoiceActivityDetector {
    val isSpeech: Boolean
    fun process(samples: FloatArray): Boolean
    fun reset()
}

/**
 * Lightweight energy-based voice activity detector used for barge-in:
 * while Jarvis is speaking, the mic samples audio and VAD detects when the user interrupts.
 * Triggers immediate TTS cancellation and restarts speech recognition.
 */
class Vad(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES
) : VoiceActivityDetector {

    private var speechActive = false
    private var quietFrames = 0

    override val isSpeech: Boolean get() = speechActive

    override fun process(samples: FloatArray): Boolean {
        var energy = 0.0
        for (s in samples) energy += s.toDouble() * s
        energy /= samples.size.coerceAtLeast(1)

        val speaking = energy > threshold
        if (speaking) {
            speechActive = true
            quietFrames = 0
        } else if (speechActive) {
            quietFrames++
            if (quietFrames > hangoverFrames) {
                speechActive = false
                quietFrames = 0
            }
        }
        return speechActive
    }

    override fun reset() {
        speechActive = false
        quietFrames = 0
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.005f
        const val DEFAULT_HANGOVER_FRAMES = 4
    }
}
