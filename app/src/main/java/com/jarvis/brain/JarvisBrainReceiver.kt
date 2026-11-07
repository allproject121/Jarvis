package com.jarvis.brain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ADB & Automated Testing Broadcast Receiver.
 * Allows executing instant on-device AI prompts and tool calls without UI dependency:
 * adb shell am broadcast -p com.jarvis.assistant -a com.jarvis.brain.TEST --es prompt "torch on karo"
 * adb shell am broadcast -p com.jarvis.assistant -a com.jarvis.brain.TEST --es prompt torch_on_karo
 */
class JarvisBrainReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_TEST_BRAIN) {
            val raw = intent.getStringExtra(EXTRA_PROMPT) ?: "system diagnosis"
            // Support underscores as spaces for hassle-free ADB CLI inputs (e.g. torch_on_karo)
            val prompt = if (!raw.contains(" ") && raw.contains("_")) raw.replace('_', ' ') else raw
            Log.i(TAG, "Executing ADB test prompt: '$prompt'")

            val pending = goAsync()
            scope.launch {
                val t0 = System.currentTimeMillis()
                try {
                    val appContext = context.applicationContext
                    val engine = LocalLlamaEngine.getInstance(appContext)
                    val executor = ActionExecutor(appContext)
                    val logger = BrainMemoryLogger(appContext)

                    // 1. Quick regex check (<1ms)
                    val quickCmd = CommandParser.quickMatch(prompt)
                    if (quickCmd != null) {
                        val res = kotlinx.coroutines.withContext(Dispatchers.Main) { executor.execute(quickCmd) }
                        val dur = System.currentTimeMillis() - t0
                        Log.i(TAG, "[QUICK_MATCH] ${dur}ms -> Action: ${quickCmd.action}, Result: $res")
                        logger.logInteraction(prompt, "", quickCmd.action, quickCmd.params, res, dur, isQuickMatch = true)
                        return@launch
                    }

                    // 2. On-device LLM inference
                    if (!engine.isLoaded) {
                        when (val lr = engine.load()) {
                            is LocalLlamaEngine.LoadResult.Ok -> Log.i(TAG, "LocalLlamaEngine loaded: ${engine.activeModelName}")
                            is LocalLlamaEngine.LoadResult.Error -> {
                                Log.e(TAG, "LocalLlamaEngine load failed: ${lr.reason}")
                                logger.logInteraction(prompt, "N/A", "error", emptyMap(), lr.reason, System.currentTimeMillis() - t0)
                                return@launch
                            }
                        }
                    }

                    val fullPrompt = IntentPrompt.build(prompt, modelName = engine.activeModelName)
                    val rawOutput = engine.generate(fullPrompt, IntentPrompt.grammarFor(prompt))
                    val dur = System.currentTimeMillis() - t0
                    Log.i(TAG, "[LLM_INFERENCE] ${dur}ms -> Output: $rawOutput")

                    val thinking = CommandParser.extractThinking(rawOutput)
                    val cmd = CommandParser.parse(rawOutput)
                    val result = if (cmd != null) {
                        val r = kotlinx.coroutines.withContext(Dispatchers.Main) { executor.execute(cmd) }
                        if (cmd.action == "answer") cmd.str("text").ifBlank { r } else r
                    } else {
                        rawOutput
                    }

                    Log.i(TAG, "[EXECUTION_RESULT] (${dur}ms) -> Action: ${cmd?.action ?: "answer"} | Result: $result | Thinking: $thinking")
                    logger.logInteraction(
                        userInput = prompt,
                        rawModelOutput = rawOutput,
                        actionName = cmd?.action ?: "answer",
                        actionParams = cmd?.params ?: emptyMap(),
                        executionResult = result,
                        durationMs = dur,
                        thinking = thinking
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Test failed: ${e.message}", e)
                } finally {
                    try {
                        pending.finish()
                    } catch (e: Exception) {
                        Log.w(TAG, "PendingResult finish error: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "JarvisBrainReceiver"
        const val ACTION_TEST_BRAIN = "com.jarvis.brain.TEST"
        const val EXTRA_PROMPT = "prompt"
    }
}
