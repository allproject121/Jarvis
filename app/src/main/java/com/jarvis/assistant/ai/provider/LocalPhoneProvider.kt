package com.jarvis.assistant.ai.provider

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import org.json.JSONObject
import java.io.File

/**
 * On-Device Local LLM Provider powered by llama.cpp C++ native engine (libjllama.so).
 * Executes quantized GGUF small language models (Qwen 2.5 1.5B, Hermes 2 Pro 1.5B, SmolLM2 1.7B)
 * 100% offline with zero cloud dependency and native tool execution.
 */
class LocalPhoneProvider private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) : AiProvider {

    companion object {
        private const val TAG = "LocalPhoneProvider"

        @Volatile
        private var instance: LocalPhoneProvider? = null

        fun getInstance(context: Context, scope: CoroutineScope): LocalPhoneProvider {
            return instance ?: synchronized(this) {
                instance ?: LocalPhoneProvider(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    override val providerType: AiProviderType = AiProviderType.LOCAL_PHONE_OFFLINE

    private val modelManager = LocalModelManager.getInstance(context, scope)
    private val toolDispatcher = JarvisToolDispatcher.getInstance(context)
    private val mutex = Mutex()

    @Volatile
    private var nativeModel: LlamaModel? = null
    @Volatile
    private var loadedModelId: String = ""

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeId = modelManager.activeModelId.value
            if (nativeModel != null && loadedModelId == activeId) {
                Log.i(TAG, "Local phone provider already initialized with model: $loadedModelId")
                return@withLock true
            }

            val modelFile = modelManager.getModelFile(activeId)
            if (!modelManager.isModelReady(activeId)) {
                Log.w(TAG, "Local model '$activeId' is not ready or missing GGUF weights at ${modelFile.absolutePath}")
                return@withLock false
            }

            try {
                Log.i(TAG, "Loading native GGUF model into memory from ${modelFile.absolutePath}...")
                nativeModel?.close()
                nativeModel = null

                val modelParams = ModelParameters()
                    .setModel(modelFile.absolutePath)
                    .setThreads(4)

                val loaded = LlamaModel(modelParams)
                nativeModel = loaded
                loadedModelId = activeId

                Log.i(TAG, "Successfully loaded native GGUF core: $activeId (llama.cpp build: ${LlamaModel.getLlamaCppBuildInfo()})")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading native llama.cpp model: ${e.message}", e)
                nativeModel = null
                loadedModelId = ""
                false
            }
        }
    }

    override fun isReady(): Boolean {
        val model = nativeModel
        return model != null && modelManager.isModelReady(loadedModelId)
    }

    override fun checkHealth(): ProviderHealthState {
        val model = nativeModel
        return if (model != null) {
            ProviderHealthState.Healthy
        } else {
            val activeId = modelManager.activeModelId.value
            if (!modelManager.isModelReady(activeId)) {
                ProviderHealthState.Unavailable("Model weights not downloaded")
            } else {
                ProviderHealthState.Degraded("Model file exists but not loaded into memory")
            }
        }
    }

    override fun streamResponse(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        availableTools: List<JSONObject>
    ): Flow<AiStreamChunk> = flow {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return@flow

        val model = nativeModel
        if (model == null) {
            // Auto-warm if weights exist
            val initialized = initialize()
            if (!initialized || nativeModel == null) {
                emit(AiStreamChunk(
                    token = "Sir, the offline neural model is not ready. Please download the weights in Settings.",
                    isComplete = true
                ))
                return@flow
            }
        }

        val activeInstance = nativeModel ?: return@flow

        // 1. Build ChatML formatted instruction prompt
        val fullPrompt = buildChatMlPrompt(
            cleanPrompt = cleanPrompt,
            systemPrompt = systemPrompt,
            memoryContext = memoryContext,
            chatHistory = chatHistory,
            tools = if (availableTools.isNotEmpty()) availableTools else toolDispatcher.getAvailableToolSchemas()
        )

        Log.i(TAG, "Starting local on-device inference with prompt length: ${fullPrompt.length}")
        val params = InferenceParameters.of(fullPrompt)
            .withTemperature(0.7f)
            .withTopK(40)
            .withTopP(0.9f)
            .withNPredict(384)
            .withStopStrings("<|im_end|>", "<|endoftext|>", "</tool_call>")

        val tokenAccumulator = StringBuilder()
        var hasDispatchedTool = false

        try {
            val iterable = activeInstance.generate(params)
            for (output in iterable) {
                val token = output.text ?: continue
                tokenAccumulator.append(token)
                emit(AiStreamChunk(token = token, isComplete = false))
                if (output.stop) break

                // Real-time tool call detection during token stream
                if (!hasDispatchedTool && tokenAccumulator.contains("<tool_call>")) {
                    val candidateTool = ToolCallParser.parseToolCall(tokenAccumulator.toString())
                    if (candidateTool != null) {
                        hasDispatchedTool = true
                        Log.i(TAG, "Offline LLM triggered tool call: ${candidateTool.functionName}")
                        emit(AiStreamChunk(token = "", isComplete = false, toolCallDetected = candidateTool))
                        val toolResult = toolDispatcher.dispatchTool(candidateTool)
                        Log.i(TAG, "Offline tool execution finished: isSuccess=${toolResult.isSuccess}")

                        // Continue conversational answer after tool execution
                        val cleanMessage = toolResult.resultJson.optString("message", "Action completed.")
                        emit(AiStreamChunk(token = "\n$cleanMessage", isComplete = false))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during native llama token generation: ${e.message}", e)
            emit(AiStreamChunk(token = " Sir, offline core error: ${e.message}", isComplete = false))
        } finally {
            emit(AiStreamChunk(token = "", isComplete = true))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult {
        return toolDispatcher.dispatchTool(toolCall)
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (nativeModel != null) {
                Log.i(TAG, "Unloading native llama.cpp model from memory.")
                try {
                    nativeModel?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing native model: ${e.message}")
                }
                nativeModel = null
                loadedModelId = ""
            }
        }
    }

    /**
     * Formats user input into standardized ChatML instructions compliant with Qwen 2.5 and Hermes 2 Pro.
     */
    private fun buildChatMlPrompt(
        cleanPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        val sb = StringBuilder()

        // 1. System Directive
        sb.append("<|im_start|>system\n")
        if (systemPrompt.isNotBlank()) {
            sb.append(systemPrompt.trim())
        } else {
            sb.append("You are JARVIS, an ultra-intelligent, concise, and capable offline AI assistant created by Roshan Sir.")
        }

        if (memoryContext.isNotBlank()) {
            sb.append("\n\n[PERSISTENT MEMORY CONTEXT]:\n").append(memoryContext.trim())
        }

        if (tools.isNotEmpty()) {
            sb.append("\n\n# Tools Available\nYou have access to the following device control tools:\n<tools>\n")
            for (t in tools) {
                sb.append(t.toString()).append("\n")
            }
            sb.append("</tools>\n")
            sb.append("When you need to execute a tool, reply ONLY with:\n")
            sb.append("<tool_call>\n{\"name\": \"function_name\", \"arguments\": { ... }}\n</tool_call>\n")
        }
        sb.append("<|im_end|>\n")

        // 2. Recent Short-Term Turns
        val recentHistory = chatHistory.takeLast(4)
        for (turn in recentHistory) {
            if (turn.userTranscript.isNotBlank()) {
                sb.append("<|im_start|>user\n").append(turn.userTranscript.trim()).append("<|im_end|>\n")
            }
            if (turn.jarvisResponse.isNotBlank()) {
                val cleanAssistant = ToolCallParser.cleanConversationalText(turn.jarvisResponse)
                sb.append("<|im_start|>assistant\n").append(cleanAssistant).append("<|im_end|>\n")
            }
        }

        // 3. Current User Turn
        sb.append("<|im_start|>user\n").append(cleanPrompt).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }
}
