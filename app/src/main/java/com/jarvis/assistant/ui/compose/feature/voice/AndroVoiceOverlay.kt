package com.jarvis.assistant.ui.compose.feature.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistant
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistantController
import com.jarvis.assistant.androllm.voice.assistant.VoiceOverlayEvent
import com.jarvis.assistant.androllm.voice.assistant.VoicePhase
import com.jarvis.assistant.androllm.voice.assistant.VoiceUiState
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.data.model.ConversationState

/**
 * Maps the optional AndroLLM voice assistant state ([VoiceUiState]) onto the
 * overlay snapshot used by the voice screen, so the same Gemini Live-style visuals
 * render both Jarvis's voice session and AndroLLM voice mode.
 */
internal fun androSnapshot(state: VoiceUiState): VoiceSnapshot {
    val conv = when (state.phase) {
        VoicePhase.LISTEN, VoicePhase.LISTENING, VoicePhase.RECEIVING_AUDIO,
        VoicePhase.WAKE_DETECTED -> ConversationState.LISTENING
        VoicePhase.STARTING_STT, VoicePhase.RUNNING_INFERENCE, VoicePhase.THINK,
        VoicePhase.GENERATING -> ConversationState.THINKING
        VoicePhase.SPEAK, VoicePhase.SPEAKING -> ConversationState.SPEAKING
        VoicePhase.IDLE, VoicePhase.WAKE, VoicePhase.DONE -> ConversationState.IDLE
    }
    // Between turns the service sits in LISTENING for the wake word (turnActive=false).
    val waitingForWake = state.active && !state.turnActive
    return VoiceSnapshot(
        sessionOn = state.active,
        standby = false,
        conversationState = conv,
        micLevel = (state.micRms * 8f).coerceIn(0f, 1f),
        transcript = state.transcript.ifBlank { state.partialTranscript },
        answerText = state.answerText,
        connectionStatus = state.error ?: if (waitingForWake) "AndroLLM voice · say the wake word" else "AndroLLM voice",
        micMuted = state.muted,
        elapsedMs = state.elapsedMs,
        modelProvider = state.modelProvider,
        modelName = state.modelName,
        wakeListening = waitingForWake && state.answerText.isBlank()
    )
}

/**
 * Content of the floating AndroLLM overlay window
 * ([com.jarvis.assistant.androllm.voice.assistant.ui.AndroVoiceOverlayWindow]).
 * Buttons never touch the service directly: they emit [VoiceOverlayEvent]s.
 */
@Composable
internal fun AndroVoiceOverlay() {
    val context = LocalContext.current
    val state by VoiceAssistantController.state.collectAsStateWithLifecycle()
    val settings by VoiceSettingsStore.get(context).state.collectAsStateWithLifecycle()
    VoiceOverlayScreen(
        state = androSnapshot(state),
        liveError = null,
        notice = null,
        engineSummary = androEngineSummary(settings),
        onOpenEngines = {},
        onStart = {},
        onStop = { VoiceAssistant.stop(context) },
        onCancel = { VoiceAssistantController.emitOverlayEvent(VoiceOverlayEvent.Cancel) },
        onToggleMute = { VoiceAssistantController.emitOverlayEvent(VoiceOverlayEvent.ToggleMute) },
        onOpenChat = { VoiceAssistantController.emitOverlayEvent(VoiceOverlayEvent.OpenChat) },
        onOpenConversation = { VoiceAssistantController.emitOverlayEvent(VoiceOverlayEvent.OpenConversation) },
        onClose = { VoiceAssistantController.emitOverlayEvent(VoiceOverlayEvent.Close) },
        onDismissError = {},
        onFixError = {},
        onNoticeAction = {},
        onDismissNotice = {},
        animationSpeed = settings.animationSpeed,
        transparency = settings.overlayTransparency,
        overlaySize = settings.overlaySize,
        showEnginesChip = false
    )
}

internal fun androEngineSummary(s: VoiceSettings): String = listOf(
    if (s.enableWakeWord) VoiceSettings.wakeLabel(s.wakeEngine) else "No wake word",
    VoiceSettings.sttLabel(s.sttEngine),
    VoiceSettings.ttsLabel(s.ttsEngine)
).joinToString(" · ")
