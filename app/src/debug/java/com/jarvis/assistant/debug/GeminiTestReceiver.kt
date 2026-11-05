package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.androllm.diagnostics.GeminiSelfTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG BUILD ONLY. Real Gemini check with the user's saved key + model (logcat tag "GeminiTest"):
 *
 *   adb shell am broadcast -a com.jarvis.assistant.debug.GEMINI_TEST -p com.jarvis.assistant \
 *       --es text "'India ki capital kya hai?'" [--es mode text|live|both]   (default both)
 *
 * text: each model Jarvis' text chat tries (MultiModelAiRouter saved model, then AiTextSynthesizer
 *       fallbacks) with HTTP status, Google's error message, latency and reply, then the full chat
 *       call (AiTextSynthesizer.synthesizeText).
 * live: opens a real Gemini Live WebSocket (GeminiLiveWebSocket, unmodified) with the saved
 *       key / model / voice, sends one text turn, logs audio bytes / transcript / errors.
 * The key itself is never logged (last 4 characters only).
 */
class GeminiTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: "Reply with exactly: Jarvis online."
        val mode = intent.getStringExtra("mode")?.lowercase() ?: "both"
        scope.launch {
            try {
                if (mode == "text" || mode == "both") {
                    Log.i(TAG, "TEXT REQUEST text='$text'")
                    val r = GeminiSelfTest.runText(app, text)
                    Log.i(TAG, "TEXT key=${if (r.keyConfigured) "set ${r.keyHint}" else "MISSING"} provider=${r.provider} savedModel=${r.savedModel} modelsVisibleToKey=${r.modelsVisible ?: "?"}")
                    r.steps.forEach { Log.i(TAG, "TEXT STEP ${it.line()}") }
                    Log.i(TAG, "TEXT CHAT ms=${r.chatMs} reply='${r.chatReply ?: "<none - chat would fail over to the offline brain>"}'")
                    Log.i(TAG, "TEXT RESULT ok=${r.ok} workingModel=${r.workingModel ?: "-"} :: ${r.summary()}")
                }
                if (mode == "live" || mode == "both") {
                    Log.i(TAG, "LIVE REQUEST text='$text'")
                    val r = GeminiSelfTest.runLive(app, text)
                    Log.i(
                        TAG,
                        "LIVE RESULT ok=${r.ok} model=${r.model} voice=${r.voice} connected=${r.connected} setupMs=${r.setupMs ?: "-"} " +
                            "firstReplyMs=${r.firstResponseMs ?: "-"} audioBytes=${r.audioBytes} turnCompleted=${r.turnCompleted} " +
                            "toolCalls=${r.toolCalls} error=${r.error ?: "-"} states=${r.states.joinToString(">")} transcript='${r.text}' :: ${r.summary()}"
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "RESULT crash: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "GeminiTest"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
