package com.jarvis.assistant.maya.brain

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.provider.AiProvider
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live status of the Advanced Agentic pipeline (Settings "Voice engine" shows it). */
sealed class AgenticStatus {
    object Off : AgenticStatus()
    data class Listening(val partial: String) : AgenticStatus()
    data class Thinking(val userText: String) : AgenticStatus()
    data class Tool(val name: String) : AgenticStatus()
    data class Speaking(val text: String) : AgenticStatus()
    data class Notice(val message: String) : AgenticStatus()
    data class Error(val message: String) : AgenticStatus()
}

/**
 * Maya's "Advanced Agentic (Beta)" mode controller: transcriber → text LLM with tools → TTS.
 *
 * Two runtime paths, both additive and both leaving Gemini Live untouched:
 *  1. **Explicit session** ([start]/[stop]): a standalone [AgenticVoiceSession] with its own
 *     mic + chosen transcriber + [AgenticCloudProvider] + [MayaTtsRouter]. If a Gemini Live
 *     session is on, its mic is muted via the public `setMicMuted` and restored on [stop].
 *  2. **Offline-engine override**: when the mode is enabled and configured,
 *     `OfflineVoiceConversationEngine` (the loop Jarvis runs when Gemini Live is not
 *     configured / network is gone) asks [offlineLoopProvider] and, if non-null, uses the
 *     cloud brain (with native tools) instead of the local SLM; speech stays on that engine's TTS.
 */
object AdvancedAgenticMode {

    private const val TAG = "AdvancedAgenticMode"

    private val _status = MutableStateFlow<AgenticStatus>(AgenticStatus.Off)
    val status: StateFlow<AgenticStatus> = _status.asStateFlow()

    @Volatile private var session: AgenticVoiceSession? = null
    @Volatile private var mutedGeminiMic = false

    fun isEnabled(context: Context): Boolean = BrainSettings.get(context).isAdvancedAgentic

    fun setEnabled(context: Context, enabled: Boolean) {
        BrainSettings.get(context).mode = if (enabled) BrainSettings.MODE_ADVANCED else BrainSettings.MODE_GEMINI_LIVE
        if (!enabled) stop(context)
    }

    fun isSessionRunning(): Boolean = session?.isRunning == true

    /** Starts the explicit voice session. Returns null on success or a spoken error. */
    fun start(context: Context): String? {
        val app = context.applicationContext
        session?.let { if (it.isRunning) return null }
        val cfg = BrainSettings.get(app).mainBrain()
        if (!cfg.isConfigured) {
            val why = cfg.missingReason() ?: BrainHttpClient.MSG_KEY_OR_MODEL
            _status.value = AgenticStatus.Error(why)
            return why
        }
        if (runCatching { JarvisConversationService.isSessionOn.value }.getOrDefault(false)) {
            runCatching { JarvisConversationService.setMicMuted(true) }
            mutedGeminiMic = true
        }
        val s = AgenticVoiceSession(app) { _status.value = it }
        session = s
        val err = s.start()
        if (err != null) {
            session = null
            restoreGeminiMic()
        }
        return err
    }

    fun stop(context: Context) {
        session?.stop()
        session = null
        restoreGeminiMic()
        _status.value = AgenticStatus.Off
    }

    private fun restoreGeminiMic() {
        if (mutedGeminiMic) {
            mutedGeminiMic = false
            runCatching { JarvisConversationService.setMicMuted(false) }
        }
    }

    /** Transcriber actually in use by the running session (for the UI). */
    fun activeTranscriber(): String = session?.activeTranscriber.orEmpty()

    /**
     * The cloud brain for `OfflineVoiceConversationEngine`, or null to keep the engine's
     * original offline behaviour (mode off, brain unconfigured, or our own session owns the mic).
     */
    fun offlineLoopProvider(context: Context): AiProvider? {
        val app = context.applicationContext
        if (!isEnabled(app) || isSessionRunning()) return null
        val cfg = BrainSettings.get(app).mainBrain()
        if (!cfg.isConfigured) {
            Log.w(TAG, "Advanced Agentic on but unconfigured: ${cfg.missingReason()}")
            return null
        }
        return AgenticCloudProvider.main(app)
    }

    /** Short human summary for Settings / tools. */
    fun describe(context: Context): String {
        val s = BrainSettings.get(context)
        val cfg = s.mainBrain()
        return buildString {
            append(if (s.isAdvancedAgentic) "Advanced Agentic (Beta)" else "Gemini Live")
            append(" | brain=").append(cfg.provider.label).append('/').append(cfg.model.ifBlank { "?" })
            if (cfg.autoPick) append(" (AutoPick free)")
            append(" | transcriber=").append(BrainSettings.transcriberLabel(s.transcriber))
            if (s.backupTranscriber.isNotBlank()) append(" (backup ").append(BrainSettings.transcriberLabel(s.backupTranscriber)).append(')')
            append(" | voice=").append(BrainSettings.ttsLabel(s.ttsEngine))
            if (s.ttsVoice.isNotBlank()) append(" ").append(s.ttsVoice)
            if (isSessionRunning()) append(" | session running")
        }
    }
}
