package com.jarvis.assistant.androllm.voice.tts

import android.content.Context
import com.jarvis.assistant.androllm.voice.VoiceModelPack

/**
 * Piper / VITS speech synthesizer implementing both [SpeechSynthesizer] and
 * [OfflineTtsEngine] (ported from AndroLLM; Hilt removed). Powered by sherpa-onnx
 * ([SherpaOnnxOfflineTtsEngine]) with the given downloaded voice [pack] - a
 * `TtsFlavor.PIPER_ESPEAK` pack for Piper, a `VITS_LEXICON` pack for the sherpa
 * VITS voice. Text normalization runs inside the sherpa engine (Jarvis pipeline).
 */
class PiperSpeechSynthesizer(
    context: Context,
    val pack: VoiceModelPack,
    private val engine: SherpaOnnxOfflineTtsEngine = SherpaOnnxOfflineTtsEngine(context.applicationContext, pack)
) : SpeechSynthesizer, OfflineTtsEngine {

    override val isInitialized: Boolean
        get() = engine.isInitialized

    override val sampleRate: Int
        get() = engine.sampleRate

    override fun ensureInitialized(): Boolean = engine.ensureInitialized()

    override fun synthesize(text: String, speed: Float): FloatArray? = engine.synthesize(text, speed)

    override fun release() = engine.release()
}
