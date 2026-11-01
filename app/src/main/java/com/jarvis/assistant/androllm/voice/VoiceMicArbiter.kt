package com.jarvis.assistant.androllm.voice

import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/** Which AndroLLM component currently holds the microphone. */
enum class SherpaMicOwner { NONE, WAKE_WORD, DICTATION, VOICE_ASSISTANT }

/**
 * Microphone ownership rules for the optional sherpa-onnx voice components.
 *
 * Jarvis always wins: its voice session ([JarvisConversationService.isSessionOn],
 * which also covers the OfflineVoiceConversationEngine/Vosk ASR that runs inside the
 * session) and its Vosk standby wake word ([JarvisConversationService.isStandby])
 * own the mic whenever either flag is true. Those flows are only READ here.
 *
 * Among AndroLLM components only one holder is allowed at a time ([acquire]).
 */
object VoiceMicArbiter {

    private val _owner = MutableStateFlow(SherpaMicOwner.NONE)
    val owner: StateFlow<SherpaMicOwner> = _owner.asStateFlow()

    /** True while Jarvis's own voice pipeline (session or Vosk standby) uses the mic. */
    fun jarvisOwnsMic(): Boolean =
        JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value

    /** Emits whenever Jarvis's mic ownership changes. */
    val jarvisMicBusy: Flow<Boolean> =
        combine(JarvisConversationService.isSessionOn, JarvisConversationService.isStandby) { session, standby ->
            session || standby
        }

    /**
     * Claims the mic for [who]. Fails when Jarvis is using it or another AndroLLM
     * component already holds it (re-acquiring by the same owner succeeds).
     */
    @Synchronized
    fun acquire(who: SherpaMicOwner): Boolean {
        if (who == SherpaMicOwner.NONE) return false
        if (jarvisOwnsMic()) return false
        val current = _owner.value
        if (current != SherpaMicOwner.NONE && current != who) return false
        _owner.value = who
        return true
    }

    @Synchronized
    fun release(who: SherpaMicOwner) {
        if (_owner.value == who) _owner.value = SherpaMicOwner.NONE
    }
}
