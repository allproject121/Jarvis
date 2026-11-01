package com.jarvis.assistant.ui.compose.feature.voice

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.voice.AndroLlmVoiceConfig
import com.jarvis.assistant.androllm.voice.AndroLlmVoicePrefs
import com.jarvis.assistant.androllm.voice.SherpaVoiceController
import com.jarvis.assistant.androllm.voice.SherpaVoiceStatus
import com.jarvis.assistant.androllm.voice.SherpaWakeState
import com.jarvis.assistant.androllm.voice.VoiceDownloadState
import com.jarvis.assistant.androllm.voice.VoiceModelDownloader
import com.jarvis.assistant.androllm.voice.VoiceModelPack
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistant
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistantController
import com.jarvis.assistant.androllm.voice.assistant.VoiceOverlayEvent
import com.jarvis.assistant.androllm.voice.assistant.VoiceUiState
import com.jarvis.assistant.androllm.voice.assistant.service.VoiceAssistantService
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a notice banner's action button does. */
internal enum class VoiceNoticeAction { NONE, BATTERY_OPTIMIZATION, OPEN_SETTINGS }

/** Transient message shown above the controls (service events, permission hints). */
internal data class VoiceNotice(
    val text: String,
    val actionLabel: String? = null,
    val action: VoiceNoticeAction = VoiceNoticeAction.NONE
)

/**
 * Plain AndroidViewModel bridging the ported AndroLLM voice overlay to Jarvis's
 * real voice pipeline. All live state comes from the companion StateFlows of
 * [JarvisConversationService] (which also mirrors the OfflineVoiceConversationEngine
 * through `updateConversationStateFromOffline`). Session start/stop use exactly the
 * same intent + `ContextCompat.startForegroundService` call as MainViewModel.
 */
internal class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = (application as JarvisApp).preferences

    val isSessionOn: StateFlow<Boolean> = JarvisConversationService.isSessionOn
    val isStandby: StateFlow<Boolean> = JarvisConversationService.isStandby
    val conversationState: StateFlow<ConversationState> = JarvisConversationService.conversationState
    val audioLevel: StateFlow<Float> = JarvisConversationService.audioLevel
    val userTranscript: StateFlow<String> = JarvisConversationService.liveUserTranscript
    val assistantResponse: StateFlow<String> = JarvisConversationService.liveAssistantResponse
    val connectionStatus: StateFlow<String> = JarvisConversationService.connectionStatus
    val liveError = JarvisConversationService.liveError

    private val _micMuted = MutableStateFlow(preferences.isMicMuted)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    /** Client-side measured duration of the current THINKING turn. */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _notice = MutableStateFlow<VoiceNotice?>(null)
    val notice: StateFlow<VoiceNotice?> = _notice.asStateFlow()

    private var tickerJob: Job? = null
    private var noticeJob: Job? = null

    // ── Optional AndroLLM sherpa-onnx engines (all default OFF) ──────────────
    val sherpaConfig: StateFlow<AndroLlmVoiceConfig> = AndroLlmVoicePrefs.config
    val sherpaStatus: StateFlow<SherpaVoiceStatus> = SherpaVoiceController.status
    val voiceDownloads: StateFlow<Map<String, VoiceDownloadState>> = VoiceModelDownloader.states

    // ── Optional AndroLLM voice assistant mode (own service, default OFF) ─────
    val androState: StateFlow<VoiceUiState> = VoiceAssistantController.state
    val androRunning: StateFlow<Boolean> = VoiceAssistantService.isRunning
    val androSettings: StateFlow<VoiceSettings> = VoiceSettingsStore.get(application).state

    /** Starts AndroLLM voice mode (refused while Jarvis's voice service runs). */
    fun startAndroVoice() {
        VoiceAssistant.start(getApplication<Application>())?.let { showNotice(VoiceNotice(it)) }
    }

    fun stopAndroVoice() = VoiceAssistant.stop(getApplication<Application>())

    fun androOverlayEvent(event: VoiceOverlayEvent) = VoiceAssistantController.emitOverlayEvent(event)

    init {
        SherpaVoiceController.init(application)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { SherpaVoiceController.refreshNativeStatus() }
        viewModelScope.launch {
            SherpaVoiceController.events.collect { message -> showNotice(VoiceNotice(message)) }
        }
        viewModelScope.launch {
            JarvisConversationService.conversationState.collect { state ->
                if (state == ConversationState.THINKING) startTicker() else stopTicker()
            }
        }
        viewModelScope.launch {
            JarvisConversationService.events.collect { message -> showNotice(VoiceNotice(message)) }
        }
        viewModelScope.launch {
            JarvisConversationService.wakeEvents.collect { showNotice(VoiceNotice("Wake word detected")) }
        }
    }

    val modelProvider: String
        get() = if (connectionStatus.value.startsWith("OFFLINE", ignoreCase = true)) "Offline" else "Gemini Live"

    val modelName: String
        get() = if (connectionStatus.value.startsWith("OFFLINE", ignoreCase = true)) "Local AI"
        else runCatching { preferences.aiModel }.getOrDefault("")

    /** Starts the conversation exactly like MainViewModel.toggleSession() does. */
    fun startSession() {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasAllRequiredPermissions(context)) {
            if (!PermissionHelper.hasBatteryOptimizationPermission(context) &&
                PermissionHelper.hasRecordAudioPermission(context) &&
                PermissionHelper.hasNotificationPermission(context)
            ) {
                showNotice(
                    VoiceNotice(
                        text = "Jarvis needs to be excluded from battery optimization to run the voice session.",
                        actionLabel = "Allow",
                        action = VoiceNoticeAction.BATTERY_OPTIMIZATION
                    ),
                    sticky = true
                )
            } else {
                showNotice(VoiceNotice("Please allow all permissions in Settings to activate Jarvis."))
            }
            return
        }
        val isOfflineOnly = preferences.aiModelMode == "LOCAL_OFFLINE"
        if (preferences.apiKey.trim().isBlank() && !isOfflineOnly) {
            showNotice(VoiceNotice("Starting Jarvis in Offline Local AI mode..."))
        }
        sendServiceAction(JarvisConversationService.ACTION_START_CONVERSATION)
    }

    fun stopSession() {
        sendServiceAction(JarvisConversationService.ACTION_STOP_ALL)
        showNotice(VoiceNotice("Jarvis Powered Off"))
    }

    /** Overlay "Cancel": stop speech playback and any running tool/task. */
    fun cancelTurn() {
        JarvisConversationService.interruptPlayback()
        JarvisConversationService.cancelActiveTaskFromOverlay()
    }

    fun toggleMicMute() {
        val newMuted = !_micMuted.value
        _micMuted.value = newMuted
        preferences.isMicMuted = newMuted
        JarvisConversationService.setMicMuted(newMuted)
        showNotice(VoiceNotice(if (newMuted) "Microphone Muted (Off)" else "Microphone Active (On)"))
    }

    fun clearLiveError() = JarvisConversationService.clearLiveError()

    /** One-line summary of active engines for the voice screen chip. */
    fun engineSummary(cfg: AndroLlmVoiceConfig, status: SherpaVoiceStatus): String {
        val parts = mutableListOf("Gemini Live", "Vosk wake")
        if (cfg.wakeWordEnabled) {
            parts += if (status.wakeState == SherpaWakeState.LISTENING) "sherpa KWS (listening)" else "sherpa KWS"
        }
        if (cfg.asrEnabled) parts += "sherpa ASR"
        if (cfg.ttsEnabled) parts += "sherpa TTS"
        return parts.joinToString(" · ")
    }

    fun setSherpaFlag(key: String, enabled: Boolean) {
        AndroLlmVoicePrefs.setFlag(getApplication<Application>(), key, enabled)
    }

    fun setSherpaWakeWord(enabled: Boolean) = setSherpaFlag(AndroLlmFlags.SHERPA_WAKE_WORD, enabled)
    fun setSherpaAsr(enabled: Boolean) = setSherpaFlag(AndroLlmFlags.SHERPA_ASR, enabled)
    fun setSherpaTts(enabled: Boolean) = setSherpaFlag(AndroLlmFlags.SHERPA_TTS, enabled)

    fun selectVoicePack(pack: VoiceModelPack) {
        AndroLlmVoicePrefs.selectPack(getApplication<Application>(), pack)
    }

    fun startSherpaDictation() {
        SherpaVoiceController.startDictation(getApplication<Application>())?.let { showNotice(VoiceNotice(it)) }
    }

    fun stopSherpaDictation() = SherpaVoiceController.stopDictation()

    fun recheckSherpa() = SherpaVoiceController.recheck()

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    fun showNotice(notice: VoiceNotice, sticky: Boolean = false) {
        noticeJob?.cancel()
        _notice.value = notice
        if (!sticky) {
            noticeJob = viewModelScope.launch {
                delay(3500)
                _notice.value = null
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val app = getApplication<Application>()
        val intent = Intent(app, JarvisConversationService::class.java).setAction(action)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        val start = System.currentTimeMillis()
        _elapsedMs.value = 0L
        tickerJob = viewModelScope.launch {
            while (isActive) {
                _elapsedMs.value = System.currentTimeMillis() - start
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
