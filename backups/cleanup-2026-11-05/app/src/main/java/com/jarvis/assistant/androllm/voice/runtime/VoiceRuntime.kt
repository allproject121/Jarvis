package com.jarvis.assistant.androllm.voice.runtime

import android.content.Context
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.model.VoiceSettings

/** Availability snapshot of a runtime (AndroLLM `RuntimeStatus` shape). */
data class VoiceRuntimeStatus(
    val available: Boolean,
    val summary: String,
    val detail: String? = null
)

/**
 * Describes the optional AndroLLM voice assistant runtime (ported from AndroLLM
 * `VoiceRuntime`). Jarvis has no central runtime registry, so this is a plain
 * object the settings / developer screens can query. It mirrors the persisted
 * [VoiceSettingsStore] and never starts or stops any voice engine.
 */
object VoiceRuntime {

    const val id = "androllm_voice"
    const val displayName = "AndroLLM Voice Assistant (optional)"
    const val description =
        "Optional voice pipeline: wake word (Vosk / sherpa-onnx KWS / openWakeWord) → " +
            "ASR (Vosk / sherpa / whisper.cpp / Gemini REST) → ModelRouter → TTS. " +
            "Jarvis's Gemini Live voice stays the default."

    suspend fun status(context: Context): VoiceRuntimeStatus = runCatching {
        val settings = VoiceSettingsStore.get(context).current()
        if (settings.enabled) {
            VoiceRuntimeStatus(
                available = true,
                summary = "Enabled — ${if (settings.enableWakeWord) VoiceSettings.wakeLabel(settings.wakeEngine) else "wake word off"}, " +
                    "${VoiceSettings.sttLabel(settings.sttEngine)} STT, ${VoiceSettings.ttsLabel(settings.ttsEngine)} TTS"
            )
        } else {
            VoiceRuntimeStatus(
                available = false,
                summary = "Disabled in Settings",
                detail = "Enable the AndroLLM voice assistant in Settings → Voice to use it instead of Jarvis's voice."
            )
        }
    }.getOrElse { e ->
        VoiceRuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
