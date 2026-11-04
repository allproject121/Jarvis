package com.jarvis.assistant.ui.island

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.MariaPersona
import com.jarvis.assistant.data.preferences.JarvisExtraPrefs
import com.jarvis.assistant.ui.agent.TaskProgressOverlay
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.voice.PocketTtsEngine
import com.jarvis.assistant.voice.edge.EdgeTtsSpeaker
import com.jarvis.assistant.voice.overlay.OverlayPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Drives the Jarvis Dynamic Island overlay (Settings / Customization toggle, default OFF).
 *
 * Reads JarvisConversationService's public companion flows READ-ONLY (conversationState,
 * isSessionOn, audioLevel, liveUserTranscript, liveAssistantResponse) plus Edge speech state.
 * Never touches Gemini Live. When Jarvis' own floating task overlay is on screen the island
 * hides, so only one Jarvis overlay is visible at a time.
 */
object DynamicIslandController {

    private const val TAG = "DynamicIsland"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshTick = MutableStateFlow(0)
    private var jobs: List<Job> = emptyList()
    private var overlay: DynamicIslandOverlay? = null

    private data class Snapshot(
        val enabled: Boolean,
        val state: ConversationState,
        val sessionOn: Boolean,
        val edgeSpeaking: Boolean
    )

    /** Starts observing (call once from Application.onCreate). Cheap when the toggle is off. */
    fun init(context: Context) {
        val app = context.applicationContext
        JarvisExtraPrefs.warmUp(app)
        if (jobs.isNotEmpty()) return
        val ticker = flow {
            while (true) {
                emit(Unit)
                // Fast polling only while the island is enabled (task-overlay / permission checks).
                delay(if (JarvisExtraPrefs.dynamicIslandEnabledFlow.value) 1_000L else 10_000L)
            }
        }
        val stateJob = scope.launch {
            combine(
                JarvisExtraPrefs.dynamicIslandEnabledFlow,
                JarvisConversationService.conversationState,
                JarvisConversationService.isSessionOn,
                EdgeTtsSpeaker.speaking,
                combine(ticker, refreshTick) { _, tick -> tick }
            ) { enabled, state, sessionOn, edgeSpeaking, _ ->
                Snapshot(enabled, state, sessionOn, edgeSpeaking)
            }.collect { apply(app, it) }
        }
        val levelJob = scope.launch {
            JarvisConversationService.audioLevel.collect { overlay?.setLevel(it) }
        }
        val transcriptJob = scope.launch {
            JarvisConversationService.liveUserTranscript.collect { overlay?.setTranscript(it) }
        }
        val responseJob = scope.launch {
            JarvisConversationService.liveAssistantResponse.collect { overlay?.setResponse(it) }
        }
        jobs = listOf(stateJob, levelJob, transcriptJob, responseJob)
    }

    /** Re-evaluates visibility right away (e.g. after the toggle changed). */
    fun refresh(context: Context) {
        if (jobs.isEmpty()) init(context)
        refreshTick.value = refreshTick.value + 1
    }

    private fun apply(app: Context, s: Snapshot) {
        val active = s.sessionOn || s.state != ConversationState.IDLE || s.edgeSpeaking
        val taskOverlayShowing = runCatching { TaskProgressOverlay.isShowing() }.getOrDefault(false)
        val shouldShow = s.enabled && active && !taskOverlayShowing && OverlayPermission.isGranted(app)
        if (!shouldShow) {
            overlay?.hide()
            return
        }
        val island = overlay ?: DynamicIslandOverlay(
            context = app,
            onStop = { stopJarvisSpeech(app) },
            onOpen = { openJarvis(app) }
        ).also {
            overlay = it
            it.setTranscript(JarvisConversationService.liveUserTranscript.value)
            it.setResponse(JarvisConversationService.liveAssistantResponse.value)
        }
        val (mode, text) = statusFor(s)
        island.setStatus(mode, text)
        island.show()
    }

    private fun statusFor(s: Snapshot): Pair<DynamicIslandOverlay.Mode, String> {
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        val maria = MariaPersona.isActive(prefs?.personality)
        val name = prefs?.assistantName?.ifBlank { "JARVIS" } ?: "JARVIS"
        return when {
            s.edgeSpeaking || s.state == ConversationState.SPEAKING ->
                DynamicIslandOverlay.Mode.SPEAKING to (if (maria) MariaPersona.STATUS_SPEAKING else "$name · Speaking…")
            s.state == ConversationState.THINKING ->
                DynamicIslandOverlay.Mode.THINKING to (if (maria) MariaPersona.STATUS_THINKING else "$name · Thinking…")
            s.state == ConversationState.LISTENING ->
                DynamicIslandOverlay.Mode.LISTENING to (if (maria) MariaPersona.STATUS_LISTENING else "$name · Listening…")
            else -> DynamicIslandOverlay.Mode.IDLE to "$name · Ready"
        }
    }

    /** Stop: silence Jarvis' speech and move the live session to standby (existing service action). */
    private fun stopJarvisSpeech(app: Context) {
        EdgeTtsSpeaker.stop()
        runCatching { PocketTtsEngine.getInstance(app).stop() }
        if (JarvisConversationService.isServiceRunning()) {
            try {
                app.startService(
                    Intent(app, JarvisConversationService::class.java)
                        .setAction(JarvisConversationService.ACTION_ENTER_STANDBY)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not move Jarvis to standby: ${e.message}")
            }
        }
        refreshTick.value = refreshTick.value + 1
    }

    private fun openJarvis(app: Context) {
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Jarvis: ${e.message}")
        }
    }
}
