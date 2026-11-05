package com.jarvis.assistant.ai.provider

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.local.JarvisThreadManager
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

    // B4 fix: active inference configuration (threads, sampling, token & context limits)
    @Volatile
    private var currentConfig: LocalAiConfig = LocalAiConfig()

    /** Applies LocalAiConfig settings to subsequent inference runs. */
    fun configure(config: LocalAiConfig) {
        currentConfig = config
    }

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
                    .setThreads(currentConfig.threads)
                    .setCtxSize(currentConfig.contextWindowLimit)

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
        toolDispatcher.startNewTurn(java.util.UUID.randomUUID().toString())
        // Optional AndroLLM agent pipeline (default OFF): routed tool list over
        // Jarvis + AndroLLM tools, calls executed through the gated coordinator.
        val agentPipelineOn = com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)
        if (agentPipelineOn) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.beginTurn(context, cleanPrompt)
        }

        val activeId = loadedModelId.ifBlank { modelManager.activeModelId.value }
        val modelFamily = com.jarvis.assistant.ai.compat.ModelFamily.resolveFromModelId(activeId)

        // 1. Build prompt formatted to the active model family's official chat template
        // The on-device model has a ~2K-token context (~4.4K chars). Even the agent router's routed
        // set is full-length JSON, so the offline brain always gets the compact everyday set unless
        // a caller passes its own list. (Full lists overflowed it: 170K-char prompts, garbage output.)
        val fullTools = when {
            availableTools.isNotEmpty() -> availableTools
            else -> toolDispatcher.getOfflineToolSchemas()
        }
        val fullPrompt = buildPromptWithinContext(modelFamily, cleanPrompt, systemPrompt, memoryContext, chatHistory, fullTools)

        Log.i(TAG, "Starting local on-device inference (family=${modelFamily.displayName}) with prompt length: ${fullPrompt.length}")
        val params = InferenceParameters.of(fullPrompt)
            .withTemperature(currentConfig.temperature)
            .withTopK(currentConfig.topK)
            .withTopP(currentConfig.topP)
            .withNPredict(currentConfig.maxTokens)
            .withStopStrings(*(modelFamily.stopTokens.toTypedArray()))

        val tokenAccumulator = StringBuilder()
        var hasDispatchedTool = false
        var toolCallPending = false
        var emittedLength = 0

        val telemetry = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(context)
        telemetry.beginGeneration()
        try {
            JarvisThreadManager.withBackgroundInferencePriority {
                val iterable = activeInstance.generate(params)
                for (output in iterable) {
                    val token = output.text ?: continue
                    telemetry.onToken()
                    tokenAccumulator.append(token)

                    // Real-time tool call detection during token stream
                    if (!hasDispatchedTool && tokenAccumulator.contains("<tool_call>")) {
                        toolCallPending = true
                        val candidateTool = ToolCallParser.parseToolCall(tokenAccumulator.toString())
                        if (candidateTool != null) {
                            hasDispatchedTool = true
                            Log.i(TAG, "Offline LLM triggered tool call: ${candidateTool.functionName}")

                            // B2 fix: emit only the conversational text streamed before the
                            // structured payload; never speak or persist the raw function JSON.
                            val visiblePrefix = ToolCallParser.cleanConversationalText(tokenAccumulator.toString()).trim()
                            if (visiblePrefix.isNotEmpty()) {
                                emit(AiStreamChunk(token = visiblePrefix, isComplete = false))
                            }

                            emit(AiStreamChunk(token = "", isComplete = false, toolCallDetected = candidateTool))
                            // Continue conversational answer after tool execution
                            val cleanMessage = runOfflineTool(candidateTool, agentPipelineOn)
                            emit(AiStreamChunk(token = "\n$cleanMessage", isComplete = false))
                            break
                        }
                        // Incomplete structured request: keep accumulating without emitting raw JSON
                        continue
                    }

                    if (!toolCallPending && emittedLength < tokenAccumulator.length) {
                        // Stream only the newly appended delta of conversational text
                        emit(AiStreamChunk(token = tokenAccumulator.substring(emittedLength), isComplete = false))
                        emittedLength = tokenAccumulator.length
                    }

                    if (output.stop) break
                }
            }

            // Stream ended (e.g. on the "</tool_call>" stop word) while a request was pending:
            // parse the complete text once more so the action still runs.
            if (!hasDispatchedTool && toolCallPending) {
                val lateTool = ToolCallParser.parseToolCall(tokenAccumulator.toString())
                if (lateTool != null) {
                    hasDispatchedTool = true
                    Log.i(TAG, "Offline LLM triggered tool call at end of stream: ${lateTool.functionName}")
                    val visiblePrefix = ToolCallParser.cleanConversationalText(tokenAccumulator.substring(emittedLength)).trim()
                    if (visiblePrefix.isNotEmpty()) {
                        emit(AiStreamChunk(token = visiblePrefix, isComplete = false))
                    }
                    emit(AiStreamChunk(token = "", isComplete = false, toolCallDetected = lateTool))
                    val cleanMessage = runOfflineTool(lateTool, agentPipelineOn)
                    emit(AiStreamChunk(token = "\n$cleanMessage", isComplete = false))
                }
            }

            // Stream ended while holding an unparsed partial request: emit only its clean text
            if (!hasDispatchedTool && emittedLength < tokenAccumulator.length) {
                val heldText = ToolCallParser.cleanConversationalText(tokenAccumulator.substring(emittedLength)).trim()
                if (heldText.isNotEmpty()) {
                    emit(AiStreamChunk(token = heldText, isComplete = false))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during native llama token generation: ${e.message}", e)
            emit(AiStreamChunk(token = " Sir, offline core error: ${e.message}", isComplete = false))
        } finally {
            telemetry.endGeneration(activeId)
            emit(AiStreamChunk(token = "", isComplete = true))
        }
    }.flowOn(Dispatchers.IO)

    /** Runs a tool the offline model asked for and returns the sentence Jarvis should say about it. */
    private suspend fun runOfflineTool(tool: AiToolCall, agentPipelineOn: Boolean): String {
        val toolResult = if (agentPipelineOn) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, tool)
        } else {
            toolDispatcher.dispatchTool(tool)
        }
        Log.i(TAG, "Offline tool execution finished: isSuccess=${toolResult.isSuccess}")
        return toolResult.resultJson.optString("message", "Action completed.")
    }

    /**
     * Quiet, TOOL-FREE single-shot generation for background pipelines
     * (AndroLLM memory extraction / summarization).
     *
     * Unlike [streamResponse] this never advertises tool schemas, never
     * dispatches tool calls, never auto-loads weights and never touches the
     * chat UI. Returns null when no model is loaded or nothing was generated.
     */
    suspend fun generateQuiet(
        prompt: String,
        systemPrompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.2f
    ): String? = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return@withContext null
        val activeInstance = nativeModel ?: return@withContext null

        val activeId = loadedModelId.ifBlank { modelManager.activeModelId.value }
        val modelFamily = com.jarvis.assistant.ai.compat.ModelFamily.resolveFromModelId(activeId)
        val fullPrompt = com.jarvis.assistant.ai.compat.ChatTemplateRenderer.render(
            family = modelFamily,
            cleanPrompt = cleanPrompt,
            systemPrompt = systemPrompt,
            memoryContext = "",
            chatHistory = emptyList(),
            tools = emptyList()
        )
        val params = InferenceParameters.of(fullPrompt)
            .withTemperature(temperature)
            .withTopK(currentConfig.topK)
            .withTopP(currentConfig.topP)
            .withNPredict(maxTokens)
            .withStopStrings(*(modelFamily.stopTokens.toTypedArray()))

        val sb = StringBuilder()
        try {
            JarvisThreadManager.withBackgroundInferencePriority {
                for (output in activeInstance.generate(params)) {
                    val token = output.text ?: continue
                    sb.append(token)
                    if (output.stop) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Quiet generation failed: ${e.message}")
            return@withContext null
        }
        sb.toString().trim().ifBlank { null }
    }

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult {
        return if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, toolCall)
        } else {
            toolDispatcher.dispatchTool(toolCall)
        }
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

    /**
     * Keeps the rendered prompt inside the model's context window. The full prompt (system + memory +
     * every tool schema + history) can be several thousand tokens, which llama.cpp rejects outright on
     * a 2K context ("request exceeds the available context size"). Each fallback step trims the least
     * important part first: tools down to the ones routed for this request, then memory, then history.
     */
    private suspend fun buildPromptWithinContext(
        family: com.jarvis.assistant.ai.compat.ModelFamily,
        cleanPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        tools: List<JSONObject>
    ): String {
        // ~3 characters per token is a conservative estimate for English/Hinglish with these tokenizers.
        val budgetTokens = (currentConfig.contextWindowLimit - currentConfig.maxTokens - 64).coerceAtLeast(256)
        val budgetChars = budgetTokens * 3
        val routed = runCatching {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.routedSchemas(context, cleanPrompt)
        }.getOrDefault(emptyList())
        val smallTools = (routed.ifEmpty { tools }).take(8)

        data class Attempt(val tools: List<JSONObject>, val memoryChars: Int, val historyTurns: Int)
        val attempts = listOf(
            Attempt(tools, Int.MAX_VALUE, chatHistory.size),
            Attempt(smallTools, 1500, 4),
            Attempt(smallTools.take(4), 600, 2),
            Attempt(emptyList(), 300, 1),
            Attempt(emptyList(), 0, 0)
        )
        var prompt = ""
        for (attempt in attempts) {
            prompt = com.jarvis.assistant.ai.compat.ChatTemplateRenderer.render(
                family = family,
                cleanPrompt = cleanPrompt,
                systemPrompt = systemPrompt,
                memoryContext = memoryContext.take(attempt.memoryChars),
                chatHistory = chatHistory.takeLast(attempt.historyTurns),
                tools = attempt.tools
            )
            if (prompt.length <= budgetChars) break
            Log.w(TAG, "Prompt ${prompt.length} chars exceeds ~$budgetChars for ${currentConfig.contextWindowLimit}-token context; trimming")
        }
        return prompt
    }
}
