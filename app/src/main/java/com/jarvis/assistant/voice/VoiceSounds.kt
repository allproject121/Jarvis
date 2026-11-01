package com.jarvis.assistant.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * System audio cues for voice interactions:
 * - Start listening chime
 * - Success/completion chime
 * - ToneGenerator based (requires zero asset bundles, pure Android framework)
 */
object VoiceSounds {

    private val handler = Handler(Looper.getMainLooper())

    fun playStart() {
        play(tone = ToneGenerator.TONE_PROP_ACK, durationMs = 140, volume = 55)
    }

    fun playEnd() {
        play(tone = ToneGenerator.TONE_PROP_BEEP, durationMs = 180, volume = 60)
    }

    fun playError() {
        play(tone = ToneGenerator.TONE_PROP_NACK, durationMs = 220, volume = 65)
    }

    private fun play(tone: Int, durationMs: Int, volume: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            generator.startTone(tone, durationMs)
            handler.postDelayed({
                runCatching { generator.release() }
            }, durationMs + 80L)
        }
    }
}
