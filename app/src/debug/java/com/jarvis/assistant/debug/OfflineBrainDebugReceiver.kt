package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.router.AiModelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG BUILD ONLY. Test hook for the offline brain, driven from a PC:
 *
 *   adb shell am broadcast -a com.jarvis.assistant.debug.OFFLINE_BRAIN -p com.jarvis.assistant \
 *       --es text "torch on karo" [--es mode AUTO_HYBRID] [--es model qwen3-4b-instruct-2507]
 *
 * The text goes through ModelRouter.routeConversationalQuery exactly like a chat message
 * (default mode LOCAL_OFFLINE); the reply is logged under the tag "OfflineBrainTest".
 * The router's previous mode is restored afterwards.
 */
class OfflineBrainDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? JarvisApp ?: return
        val text = intent.getStringExtra("text").orEmpty()
        val mode = AiModelMode.fromString(intent.getStringExtra("mode") ?: AiModelMode.LOCAL_OFFLINE.name)
        val model = intent.getStringExtra("model")
        scope.launch {
            val router = app.modelRouter
            val previous = router.currentMode.value
            val started = System.currentTimeMillis()
            try {
                if (!model.isNullOrBlank()) {
                    com.jarvis.assistant.ai.local.LocalModelManager.getInstance(app, scope).setActiveModel(model)
                }
                if (intent.getBooleanExtra("bench", false)) {
                    val provider = com.jarvis.assistant.ai.provider.LocalPhoneProvider.getInstance(app, scope)
                    val threads = intent.getIntExtra("threads", 0)
                    if (threads > 0) {
                        // Reload the model with a different thread count to compare speeds.
                        provider.unload()
                        provider.configure(com.jarvis.assistant.ai.local.LocalAiConfig(threads = threads))
                    }
                    Log.i(TAG, "RESULT bench " + provider.benchmark())
                    return@launch
                }
                if (text.isBlank()) {
                    Log.i(TAG, "RESULT model-only switch done")
                    return@launch
                }
                router.setMode(mode)
                Log.i(TAG, "REQUEST mode=$mode text='$text'")
                val sb = StringBuilder()
                router.routeConversationalQuery(
                    userPrompt = text,
                    systemPrompt = "You are JARVIS, an ultra-capable AI assistant.",
                    openAiApiKey = app.preferences.openAiApiKey
                ).collect { sb.append(it) }
                val reply = com.jarvis.assistant.ai.local.ToolCallParser.cleanConversationalText(sb.toString())
                Log.i(TAG, "RESULT provider='${router.lastActiveProviderName.value}' ms=${System.currentTimeMillis() - started} reply='$reply' raw='${sb.toString().replace("\n", "\n")}'")
            } catch (e: Throwable) {
                Log.e(TAG, "RESULT error: ${e.message}", e)
            } finally {
                router.setMode(previous)
            }
        }
    }

    companion object {
        private const val TAG = "OfflineBrainTest"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
