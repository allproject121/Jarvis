package com.jarvis.assistant.maya.ui

import android.content.Context
import android.media.MediaPlayer
import com.jarvis.assistant.voice.PocketTtsEngine

/**
 * Plays the bundled Gemini voice samples (assets/voices/<character>_<Voice>.ogg),
 * falling back to PocketTTS reading the character's line when no sample exists.
 */
class MayaVoicePreview(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    /** @return true when a bundled sample is playing, false when PocketTTS was used. */
    fun play(character: MayaCharacter, voice: String, onDone: (() -> Unit)? = null): Boolean {
        stop()
        val asset = MayaCharacters.previewAsset(appContext, character, voice)
        if (asset != null) {
            try {
                val mp = MediaPlayer()
                appContext.assets.openFd(asset).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                mp.setOnCompletionListener { onDone?.invoke() }
                mp.prepare()
                mp.start()
                player = mp
                return true
            } catch (_: Exception) {
                stop()
            }
        }
        try {
            PocketTtsEngine.speak(character.previewLine, appContext) { onDone?.invoke() }
        } catch (_: Throwable) {
        }
        return false
    }

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Throwable) { false }

    fun stop() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }
}
