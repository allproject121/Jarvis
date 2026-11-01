package com.jarvis.assistant.androllm.voice.vad

/**
 * Energy-based [VoiceActivityDetector] used for endpointing and barge-in (ported
 * from AndroLLM `SherpaVad`; despite the name it wraps the energy [Vad], exactly
 * like upstream - no Silero model is loaded).
 */
class SherpaVad(threshold: Float = Vad.DEFAULT_THRESHOLD) : VoiceActivityDetector {

    private val vad = Vad(threshold = threshold)

    override val isSpeech: Boolean
        get() = vad.isSpeech

    override fun process(samples: FloatArray): Boolean = vad.process(samples)

    override fun reset() = vad.reset()
}
