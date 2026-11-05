package com.jarvis.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * DEBUG BUILD ONLY. Runs one tool through Jarvis' real JarvisToolDispatcher (same path as
 * Gemini Live / offline brain / Tool Debug "Run", including the user's switches and
 * confirmations) and logs the result under tag "JarvisToolTest":
 *
 *   adb shell am broadcast -a com.jarvis.assistant.debug.RUN_TOOL -p com.jarvis.assistant \
 *       --es name get_battery_status --es args '{}'
 *
 * Extra "list" (--ez list true) logs every tool name Gemini is currently offered instead.
 */
class ToolTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val dispatcher = JarvisToolDispatcher.getInstance(app)
                if (intent.getBooleanExtra("list", false)) {
                    val names = dispatcher.getAvailableToolSchemas(includeDisabled = false)
                        .mapNotNull { (it.optJSONObject("function") ?: it).optString("name").takeIf { n -> n.isNotBlank() } }
                    Log.i(TAG, "RESULT offered=${names.size} offline=${dispatcher.getOfflineToolSchemas().size} tools=${names.joinToString(",")}")
                    return@launch
                }
                val name = intent.getStringExtra("name").orEmpty().trim()
                if (name.isEmpty()) {
                    Log.e(TAG, "RESULT error: pass --es name <tool>")
                    return@launch
                }
                val rawArgs = intent.getStringExtra("args").orEmpty().ifBlank { "{}" }
                val args = runCatching { JSONObject(rawArgs) }.getOrElse {
                    Log.e(TAG, "RESULT error: args is not JSON: $rawArgs (${it.message})")
                    return@launch
                }
                Log.i(TAG, "REQUEST name=$name args=$args")
                val started = System.currentTimeMillis()
                val result = dispatcher.dispatchTool(AiToolCall("adb_${started}", name, args))
                Log.i(
                    TAG,
                    "RESULT name=$name success=${result.isSuccess} ms=${System.currentTimeMillis() - started} " +
                        "error=${result.errorMessage ?: "-"} json=${result.resultJson}"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "RESULT crash: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "JarvisToolTest"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
