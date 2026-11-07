package com.jarvis.brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Foreground service: text input leta hai (aapke STT/Vosk se), brain se JSON nikalta hai, action chalata hai.
 *
 * Bhejne ka tarika:
 *   JarvisBrainService.send(context, "torch on karo")
 * Result sunne ka tarika:
 *   JarvisBrainService.results.collect { reply -> tts.speak(reply) }
 */
class JarvisBrainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engine: LocalLlamaEngine
    private lateinit var executor: ActionExecutor
    private lateinit var logger: BrainMemoryLogger
    private var loadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        engine = LocalLlamaEngine.getInstance(this)
        executor = ActionExecutor(this)
        logger = BrainMemoryLogger(this)
        loadJob = scope.launch {
            when (val r = engine.load()) {
                is LocalLlamaEngine.LoadResult.Ok -> emit("Jarvis brain ready")
                is LocalLlamaEngine.LoadResult.Error -> { Log.e(TAG, r.reason); emit("Brain error: ${r.reason}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text ->
            scope.launch { handle(text) }
        }
        return START_STICKY
    }

    private suspend fun handle(text: String) {
        val t0 = System.currentTimeMillis()

        // 1) Simple commands bina model ke (instant regex match)
        CommandParser.quickMatch(text)?.let { quickCmd ->
            val result = withContext(Dispatchers.Main) { executor.execute(quickCmd) }
            logger.logInteraction(
                userInput = text,
                rawModelOutput = "",
                actionName = quickCmd.action,
                actionParams = quickCmd.params,
                executionResult = result,
                durationMs = System.currentTimeMillis() - t0,
                isQuickMatch = true
            )
            emit(result)
            return
        }

        // 2) Model se JSON inference
        loadJob?.join()
        if (!engine.isLoaded) {
            val err = "Brain not loaded"
            logger.logInteraction(
                userInput = text,
                rawModelOutput = "N/A",
                actionName = "error",
                actionParams = emptyMap(),
                executionResult = err,
                durationMs = System.currentTimeMillis() - t0
            )
            emit(err)
            return
        }

        val raw = withTimeoutOrNull(20_000) {
            engine.generate(IntentPrompt.build(text, modelName = engine.activeModelName), IntentPrompt.GRAMMAR)
        } ?: run {
            engine.cancel()
            val timeoutMsg = "Sochne me zyada time lag gaya (Timeout)"
            logger.logInteraction(
                userInput = text,
                rawModelOutput = "TIMEOUT",
                actionName = "timeout",
                actionParams = emptyMap(),
                executionResult = timeoutMsg,
                durationMs = System.currentTimeMillis() - t0
            )
            emit(timeoutMsg)
            return
        }
        val duration = System.currentTimeMillis() - t0
        Log.i(TAG, "LLM ${duration}ms -> $raw")

        val thinking = CommandParser.extractThinking(raw)
        if (thinking.isNotBlank()) {
            Log.i(TAG, "Jarvis Thinking: $thinking")
        }

        val cmd = CommandParser.parse(raw) ?: run {
            val parseError = "Samajh nahi aaya (Invalid JSON)"
            logger.logInteraction(
                userInput = text,
                rawModelOutput = raw,
                actionName = "parse_failure",
                actionParams = emptyMap(),
                executionResult = parseError,
                durationMs = duration,
                thinking = thinking
            )
            emit(parseError)
            return
        }

        val result = withContext(Dispatchers.Main) { executor.execute(cmd) }
        logger.logInteraction(
            userInput = text,
            rawModelOutput = raw,
            actionName = cmd.action,
            actionParams = cmd.params,
            executionResult = result,
            durationMs = duration,
            isQuickMatch = false,
            thinking = thinking
        )
        emit(result)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        scope.launch { engine.onTrimMemory(level) }
    }

    override fun onDestroy() {
        runBlocking { engine.unload() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis Brain", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis").setContentText("Offline brain active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private suspend fun emit(s: String) = _results.emit(s)

    companion object {
        private const val TAG = "JarvisBrainService"
        private const val CHANNEL = "jarvis_brain"
        const val EXTRA_TEXT = "text"
        private val _results = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val results = _results.asSharedFlow()

        fun send(ctx: Context, text: String) {
            val i = Intent(ctx, JarvisBrainService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
