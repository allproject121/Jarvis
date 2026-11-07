package com.jarvis.assistant.ai.local

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.provider.LocalPhoneProvider
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

sealed class LocalEngineState {
    object Uninitialized : LocalEngineState()
    object Loading : LocalEngineState()
    object Ready : LocalEngineState()
    object Generating : LocalEngineState()
    data class Error(val message: String) : LocalEngineState()
}

/**
 * On-Device Local AI Engine for Jarvis.
 * Orchestrates llama.cpp native inference via LocalPhoneProvider with seamless fallback
 * to Academic Knowledge Vault when weights are pending download.
 */
class LocalAiEngine private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LocalAiEngine"
        private const val MAX_DIRECT_COMMAND_CHARS = 160

        @Volatile
        private var instance: LocalAiEngine? = null

        fun getInstance(context: Context, scope: CoroutineScope): LocalAiEngine {
            return instance ?: synchronized(this) {
                instance ?: LocalAiEngine(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val modelManager = LocalModelManager.getInstance(context, scope)
    private val localPhoneProvider = LocalPhoneProvider.getInstance(context, scope)

    private val _engineState = MutableStateFlow<LocalEngineState>(LocalEngineState.Uninitialized)
    val engineState: StateFlow<LocalEngineState> = _engineState.asStateFlow()

    private var currentConfig = LocalAiConfig()

    /**
     * Initializes the local inference engine with the specified configuration.
     * Pre-warms GGUF weights into RAM/mmap cache.
     */
    suspend fun initialize(config: LocalAiConfig = LocalAiConfig()): Boolean = withContext(Dispatchers.IO) {
        currentConfig = config
        _engineState.value = LocalEngineState.Loading

        // B4 fix: sync the active model with the requested config and apply inference settings
        if (modelManager.isModelReady(config.modelId)) {
            modelManager.setActiveModel(config.modelId)
        }
        localPhoneProvider.configure(config)

        val isReady = localPhoneProvider.initialize()
        if (isReady) {
            _engineState.value = LocalEngineState.Ready
            Log.i(TAG, "Local AI Engine ready with model: ${config.modelId}")
            true
        } else {
            val activeId = modelManager.activeModelId.value
            val msg = if (!modelManager.isModelReady(activeId)) {
                "Local model '$activeId' is not downloaded. Please download it in Settings."
            } else {
                "Failed to initialize native llama.cpp model."
            }
            Log.w(TAG, msg)
            _engineState.value = LocalEngineState.Error(msg)
            false
        }
    }

    /**
     * Generates a conversational response token-by-token (streaming) for real-time speech and UI rendering.
     */
    fun generateStreaming(
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList()
    ): Flow<String> = flow {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isBlank()) return@flow

        _engineState.value = LocalEngineState.Generating
        val startTime = System.currentTimeMillis()

        // 0. Direct phone command (see [tryDirectCommand]).
        tryDirectCommand(cleanPrompt)?.let { reply ->
            emit(reply)
            _engineState.value = LocalEngineState.Ready
            return@flow
        }

        // 1. Direct answer from Academic & Physics Knowledge Vault
        val academicFact = com.jarvis.assistant.ai.memory.AcademicKnowledgeVault.findRelevantFact(cleanPrompt)
        if (academicFact != null) {
            emit("Sir, $academicFact")
            _engineState.value = LocalEngineState.Ready
            return@flow
        }

        // 2. Direct on-device Uncensored Brain (com.jarvis.brain.LocalLlamaEngine)
        val brainEngine = com.jarvis.brain.LocalLlamaEngine.getInstance(context)
        if (brainEngine.resolveModelFile() != null) {
            if (!brainEngine.isLoaded) {
                brainEngine.load()
            }
            if (brainEngine.isLoaded) {
                try {
                    // Filter out massive 74k cloud prompts to prevent context overflow on on-device LLMs
                    val safeExtra = if (systemPrompt.length > 300 || systemPrompt.contains("<tools>")) "" else systemPrompt
                    val prompt = com.jarvis.brain.IntentPrompt.build(cleanPrompt, extraContext = safeExtra, modelName = brainEngine.activeModelName)
                    val raw = brainEngine.generate(prompt, com.jarvis.brain.IntentPrompt.grammarFor(cleanPrompt))
                    val cmd = com.jarvis.brain.CommandParser.parse(raw)
                    val reply = if (cmd != null) {
                        val res = com.jarvis.brain.ActionExecutor(context).execute(cmd)
                        if (cmd.action == "answer") cmd.str("text") else res
                    } else raw

                    if (reply.isNotBlank()) {
                        emit(reply)
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.i(TAG, "LocalLlamaEngine generation complete in ${elapsed}ms")
                        _engineState.value = LocalEngineState.Ready
                        return@flow
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "LocalLlamaEngine inference failed (${e.message}), falling back to LocalPhoneProvider")
                }
            }
        }

        // 3. Stream from native LocalPhoneProvider if ready or weights exist
        // Check the model the user actually selected (activeModelId), not the config default:
        // with e.g. Llama 3.2 downloaded but not Qwen, the default id wrongly reported "not installed".
        if (localPhoneProvider.isReady() || modelManager.resolveUsableModelId() != null) {
            var emittedAny = false
            // Strip any giant cloud system prompt before feeding into LocalPhoneProvider
            val safeLocalSystem = if (systemPrompt.length > 500 || systemPrompt.contains("<tools>")) {
                "You are JARVIS, an autonomous on-device AI assistant created by Roshan Sir. Answer concisely and assist the user."
            } else {
                systemPrompt
            }
            try {
                localPhoneProvider.streamResponse(
                    prompt = cleanPrompt,
                    systemPrompt = safeLocalSystem,
                    memoryContext = memoryContext,
                    chatHistory = chatHistory
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        emit(chunk.token)
                        emittedAny = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during streaming inference: ${e.message}", e)
                emit(" Sir, offline core error: ${e.message}")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Native local generation complete in ${elapsed}ms")
            _engineState.value = LocalEngineState.Ready
            return@flow
        }

        // 3. Fallback when weights have not been downloaded yet
        _engineState.value = LocalEngineState.Ready
        emit("Sir, the local AI neural core is not installed yet. You can download the Qwen3 4B Offline Brain model in Settings under Voice Engine to enable full offline conversations and device actions.")
    }.flowOn(Dispatchers.IO)

    /**
     * Direct phone command ("torch on karo", "calculator kholo", "volume 50"): runs it through the same
     * CommandParser/CommandExecutor the offline voice path uses and returns what to say, or null when
     * the text is not a command (or the command failed). Instant and exact, and it works even when a
     * model is too small for a reliable tool call or the cloud model has no tools at all.
     * Long texts (planner prompts, pasted documents) are never treated as commands.
     */
    suspend fun tryDirectCommand(prompt: String): String? {
        val clean = prompt.trim()
        if (clean.isBlank() || clean.length > MAX_DIRECT_COMMAND_CHARS) return null

        // Fast brain regex match (< 1ms, greetings, torch, battery, volume, whatsapp, app launcher)
        com.jarvis.brain.CommandParser.quickMatch(clean)?.let { brainCmd ->
            val reply = com.jarvis.brain.ActionExecutor(context).execute(brainCmd)
            if (reply.isNotBlank()) {
                Log.i(TAG, "Brain quickMatch direct command: ${brainCmd.action} -> $reply")
                return reply
            }
        }

        val app = context.applicationContext as? com.jarvis.assistant.JarvisApp ?: return null
        val parsed = runCatching { com.jarvis.assistant.automation.command.CommandParser.parse(clean) }.getOrNull()
        val command = parsed?.command ?: return null
        Log.i(TAG, "Direct command: $command (pattern=${parsed.matchedPattern})")
        return try {
            app.commandExecutor.execute(command).ifBlank { "Done, Sir." }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Direct command failed: ${e.message}", e)
            null
        }
    }

    /**
     * Non-streaming direct text generation.
     */
    suspend fun generateText(
        userPrompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        generateStreaming(userPrompt, systemPrompt, memoryContext, chatHistory).collect { token ->
            sb.append(token)
        }
        sb.toString().trim()
    }

    /**
     * Unloads weights from memory to free RAM.
     */
    suspend fun unload() = withContext(Dispatchers.IO) {
        localPhoneProvider.unload()
        _engineState.value = LocalEngineState.Uninitialized
        Log.i(TAG, "Local AI core unloaded.")
    }
}
