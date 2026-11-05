package com.jarvis.assistant.ai.provider

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.engine.api.EngineState
import com.jarvis.assistant.androllm.engine.api.GenerationState
import com.jarvis.assistant.androllm.engine.models.ChatPromptMessage
import com.jarvis.assistant.androllm.engine.models.GenerationConfig
import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Optional secondary on-device provider backed by the AndroLLM LiteRT-LM
 * engine (`.litertlm` models in AndroLlmPaths.liteRtModelsDir, NPU → GPU → CPU
 * backend selection, family chat templates, prefix cache, crash guard).
 *
 * Only used when the user explicitly picks the LOCAL_LITERT router mode.
 * Jarvis's llama.cpp [LocalPhoneProvider] and Gemini Live are unaffected.
 */
class LiteRtLmProvider private constructor(
    private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope
) : AiProvider {

    companion object {
        private const val TAG = "LiteRtLmProvider"

        /** History turns replayed into the stateful LiteRT conversation. */
        private const val MAX_HISTORY_TURNS = 6

        @Volatile
        private var instance: LiteRtLmProvider? = null

        fun getInstance(context: Context, scope: CoroutineScope): LiteRtLmProvider {
            return instance ?: synchronized(this) {
                instance ?: LiteRtLmProvider(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    override val providerType: AiProviderType = AiProviderType.LITERT_LM_LOCAL

    private val engines: AndroLlmEngines get() = AndroLlmEngines.getInstance(context)
    private val toolDispatcher = JarvisToolDispatcher.getInstance(context)

    /** Sampling used for chat turns (engine defaults otherwise). */
    @Volatile
    var generationConfig: GenerationConfig = GenerationConfig(temperature = 0.7f, topP = 0.95f, topK = 40)

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val result = engines.loadSelected()
        if (result is Result.Error) {
            Log.w(TAG, "LiteRT-LM model load failed: ${result.exception.message}")
            false
        } else {
            true
        }
    }

    override fun isReady(): Boolean = AndroLlmEngines.peek()?.isModelLoaded() == true

    override fun checkHealth(): ProviderHealthState {
        val graph = AndroLlmEngines.peek()
        if (graph != null && graph.isModelLoaded()) return ProviderHealthState.Healthy
        val e = graph ?: engines
        return when (val state = e.repository.engineState.value) {
            is EngineState.Failed -> ProviderHealthState.Unavailable(state.message)
            else -> if (e.resolveSelectedModel() == null) {
                ProviderHealthState.Unavailable("No .litertlm model in ${e.modelsDir().absolutePath}")
            } else {
                ProviderHealthState.Degraded("LiteRT-LM model present but not loaded")
            }
        }
    }

    override fun streamResponse(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        availableTools: List<JSONObject>
    ): Flow<AiStreamChunk> = channelFlow {
        val clean = prompt.trim()
        if (clean.isBlank()) return@channelFlow

        if (!isReady() && !initialize()) {
            val reason = (engines.repository.engineState.value as? EngineState.Failed)?.message
                ?: "no LiteRT-LM model is available"
            send(AiStreamChunk(token = "Sir, the LiteRT-LM engine could not start: $reason", isComplete = true))
            return@channelFlow
        }

        val repo = engines.repository
        val messages = buildMessages(clean, systemPrompt, memoryContext, chatHistory)
        // The engine keeps a stateful conversation; replaying the full history
        // each call is only correct from a fresh conversation.
        repo.resetChat()

        // Optional AndroLLM agent pipeline (Settings → Agent tools, default OFF):
        // routed/budgeted tool advertisement, plan → execute → re-plan and the
        // native tool-call loop through the gated executor + Tool Debug trace.
        if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            toolDispatcher.startNewTurn(java.util.UUID.randomUUID().toString())
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.beginTurn(context, clean)
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.runLiteRtTurn(
                context = context,
                repo = repo,
                messages = messages,
                config = generationConfig,
                emit = { delta -> if (delta.isNotEmpty()) send(AiStreamChunk(token = delta)) }
            )
            send(AiStreamChunk(token = "", isComplete = true))
            return@channelFlow
        }

        val emitted = StringBuilder()
        val watcher = launch {
            repo.generationState.collect { state ->
                if (state is GenerationState.Generating) {
                    val text = state.streamingText
                    if (text.length > emitted.length && text.startsWith(emitted)) {
                        val delta = text.substring(emitted.length)
                        emitted.append(delta)
                        send(AiStreamChunk(token = delta))
                    }
                }
            }
        }

        toolDispatcher.startNewTurn(java.util.UUID.randomUUID().toString())
        val result = repo.generateChat(messages, addAssistant = true, config = generationConfig)
        watcher.cancelAndJoin()

        when (val finalState = repo.generationState.value) {
            is GenerationState.Completed -> {
                val finalText = finalState.text
                if (finalText.startsWith(emitted) && finalText.length > emitted.length) {
                    send(AiStreamChunk(token = finalText.substring(emitted.length)))
                } else if (emitted.isEmpty() && finalText.isNotBlank()) {
                    send(AiStreamChunk(token = finalText))
                }
            }
            is GenerationState.Failed -> {
                send(AiStreamChunk(token = " Sir, LiteRT-LM error: ${finalState.message}"))
            }
            else -> if (result is Result.Error) {
                send(AiStreamChunk(token = " Sir, LiteRT-LM error: ${result.exception.message}"))
            }
        }

        // Native `<|tool_call|>` markers (FunctionGemma-style families) run
        // through Jarvis's shared, guarded tool dispatcher.
        val nativeCalls = runCatching { repo.takeLastNativeToolCalls() }.getOrDefault(emptyList())
        for (call in nativeCalls) {
            val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val toolCall = AiToolCall(
                callId = java.util.UUID.randomUUID().toString(),
                functionName = call.name,
                arguments = args
            )
            send(AiStreamChunk(token = "", toolCallDetected = toolCall))
            val toolResult = toolDispatcher.dispatchTool(toolCall)
            send(AiStreamChunk(token = "\n" + toolResult.resultJson.optString("message", "Action completed.")))
        }

        send(AiStreamChunk(token = "", isComplete = true))
    }.flowOn(Dispatchers.IO)

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult =
        if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, toolCall)
        } else {
            toolDispatcher.dispatchTool(toolCall)
        }

    override suspend fun unload() {
        AndroLlmEngines.peek()?.unload()
    }

    /** Cancels an in-flight LiteRT generation (no-op when idle). */
    suspend fun cancel() {
        AndroLlmEngines.peek()?.repository?.cancelGeneration()
    }

    private fun buildMessages(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>
    ): List<ChatPromptMessage> {
        val out = mutableListOf<ChatPromptMessage>()
        val system = buildString {
            append(systemPrompt.ifBlank { "You are JARVIS, a concise and capable on-device AI assistant." }.trim())
            if (memoryContext.isNotBlank()) append("\n\n[PERSISTENT MEMORY CONTEXT]:\n").append(memoryContext.trim())
        }
        out += ChatPromptMessage(role = "system", content = system)
        for (turn in chatHistory.takeLast(MAX_HISTORY_TURNS)) {
            if (turn.userTranscript.isNotBlank()) {
                out += ChatPromptMessage(role = "user", content = turn.userTranscript.trim())
            }
            if (turn.jarvisResponse.isNotBlank()) {
                out += ChatPromptMessage(
                    role = "assistant",
                    content = ToolCallParser.cleanConversationalText(turn.jarvisResponse).trim()
                )
            }
        }
        out += ChatPromptMessage(role = "user", content = prompt)
        return out
    }
}
