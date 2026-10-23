package com.jarvis.assistant.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.jarvis.assistant.JarvisApp
import java.util.concurrent.Executors

object HudSoundManager {

    private val executor = Executors.newSingleThreadExecutor()
    private var toneGenerator: ToneGenerator? = null
    private var lastVolume: Int = -1

    private fun getToneGen(volumePercent: Int): ToneGenerator? {
        if (toneGenerator == null || lastVolume != volumePercent) {
            try {
                toneGenerator?.release()
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent.coerceIn(0, 100))
                lastVolume = volumePercent
            } catch (_: Exception) {
                toneGenerator = null
            }
        }
        return toneGenerator
    }

    private fun getVibrator(): Vibrator? {
        val app = try { JarvisApp.instance } catch (_: Exception) { null } ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Triggers a subtle tactile haptic click matching Stark HUD touches
     */
    fun triggerHapticClick() {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        if (prefs?.isHapticFeedbackEnabled == false) return
        executor.execute {
            try {
                val vib = getVibrator() ?: return@execute
                if (!vib.hasVibrator()) return@execute
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(18)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Triggers a double-pulse confirmation haptic
     */
    fun triggerHapticSuccess() {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        if (prefs?.isHapticFeedbackEnabled == false) return
        executor.execute {
            try {
                val vib = getVibrator() ?: return@execute
                if (!vib.hasVibrator()) return@execute
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 20, 40, 25)
                    vib.vibrate(VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(40)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays a short futuristic Stark UI tap click
     */
    fun playTapSound() {
        triggerHapticClick()
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null } ?: return
        if (!prefs.isHudSoundEnabled) return
        val volumePercent = (prefs.hudSoundVolume * 100).toInt().coerceIn(1, 100)

        executor.execute {
            try {
                getToneGen(volumePercent)?.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays an activation sweep tone when Jarvis wakes up or starts listening
     */
    fun playWakeSound() {
        triggerHapticClick()
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null } ?: return
        if (!prefs.isHudSoundEnabled) return
        val volumePercent = (prefs.hudSoundVolume * 100).toInt().coerceIn(1, 100)

        executor.execute {
            try {
                getToneGen(volumePercent)?.startTone(ToneGenerator.TONE_PROP_PROMPT, 75)
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays a double-tone melodic confirmation chime on successful action/save
     */
    fun playSuccessSound() {
        triggerHapticSuccess()
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null } ?: return
        if (!prefs.isHudSoundEnabled) return
        val volumePercent = (prefs.hudSoundVolume * 100).toInt().coerceIn(1, 100)

        executor.execute {
            try {
                getToneGen(volumePercent)?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays a low warning tone on error or alert
     */
    fun playAlertSound() {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null } ?: return
        if (!prefs.isHudSoundEnabled) return
        val volumePercent = (prefs.hudSoundVolume * 100).toInt().coerceIn(1, 100)

        executor.execute {
            try {
                getToneGen(volumePercent)?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            } catch (_: Exception) {}
        }
    }
}
