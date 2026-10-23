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

        val isReady = localPhoneProvider.initialize()
        if (isReady) {
            _engineState.value = LocalEngineState.Ready
            Log.i(TAG, "Local AI Engine ready with model: ${config.modelId}")
            true
        } else {
            val msg = if (!modelManager.isModelReady(config.modelId)) {
                "Local model '${config.modelId}' is not downloaded. Please download it in Settings."
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

        // 1. Direct answer from Academic & Physics Knowledge Vault
        val academicFact = com.jarvis.assistant.ai.memory.AcademicKnowledgeVault.findRelevantFact(cleanPrompt)
        if (academicFact != null) {
            emit("Sir, $academicFact")
            _engineState.value = LocalEngineState.Ready
            return@flow
        }

        // 2. Stream from native LocalPhoneProvider if ready or weights exist
        if (localPhoneProvider.isReady() || modelManager.isModelReady(currentConfig.modelId)) {
            var emittedAny = false
            try {
                localPhoneProvider.streamResponse(
                    prompt = cleanPrompt,
                    systemPrompt = systemPrompt,
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
        emit("Sir, the local AI neural core is not installed yet. You can download the Qwen 2.5 (1.5B) model in Settings under Voice Engine to enable full offline conversations and device actions.")
    }.flowOn(Dispatchers.IO)

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
