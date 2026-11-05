package com.jarvis.assistant.androllm.agent.integration

import android.content.Context
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.bridge.JarvisToolBridge
import com.jarvis.assistant.androllm.agent.coordinator.ToolExecutionRecord
import com.jarvis.assistant.automation.guard.ToolLoopGuard
import com.jarvis.assistant.androllm.agent.core.ToolCall
import com.jarvis.assistant.androllm.agent.core.ToolEvent
import com.jarvis.assistant.androllm.agent.core.ToolResult
import com.jarvis.assistant.androllm.agent.validation.StreamingToolCallBuffer
import com.jarvis.assistant.androllm.cloud.CloudGateway
import com.jarvis.assistant.androllm.cloud.model.CloudChatMessage
import com.jarvis.assistant.androllm.cloud.model.CloudGenerationConfig
import com.jarvis.assistant.androllm.cloud.model.CloudStreamEvent
import com.jarvis.assistant.androllm.cloud.model.CloudToolCall
import com.jarvis.assistant.androllm.cloud.model.CloudToolCallFunction
import com.jarvis.assistant.androllm.cloud.pipeline.CloudFallbackToolParser
import com.jarvis.assistant.androllm.engine.api.EngineRepository
import com.jarvis.assistant.androllm.engine.api.EngineState
import com.jarvis.assistant.androllm.engine.api.GenerationState
import com.jarvis.assistant.androllm.engine.core.OutputSanitizer
import com.jarvis.assistant.androllm.engine.models.ChatPromptMessage
import com.jarvis.assistant.androllm.engine.models.GenerationConfig
import com.jarvis.assistant.androllm.engine.support.AlLog
import com.jarvis.assistant.androllm.engine.support.AppConstants
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * Glue between Jarvis' NON-Gemini providers (LiteRtLmProvider,
 * LocalPhoneProvider, HermesAgentProvider, CloudGatewayProvider) and the
 * AndroLLM agent framework. Every entry point is only used when
 * [AgentFlags.AGENT_PIPELINE] is on; with the flag off the providers run
 * their original code paths unchanged.
 *
 * Ported from AndroLLM's ChatViewModel (routed + budgeted tool advertisement,
 * native `<|tool_call|>` loop, cloud tool rounds with streaming tool-call
 * buffering and plain-text fallback recovery) and voice ChatManager
 * (plan → execute → re-plan via [com.jarvis.assistant.androllm.agent.coordinator.ToolRunCoordinator.runLocalWorkflow]).
 */
object AgentPipeline {

    private const val TAG = "AgentPipeline"

    // Budget constants from AndroLLM ChatViewModel.
    private const val MIN_OUTPUT_RESERVE_TOKENS = 256
    private const val MAX_OUTPUT_RESERVE_TOKENS = 4096
    private const val MIN_HISTORY_TOKENS = 128
    private const val CHARS_PER_TOKEN_ESTIMATE = 4
    private const val MAX_CLOUD_CONTINUATIONS = 4

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Per-turn loop guard shared by every call of the current turn. */
    @Volatile
    private var turnGuard: ToolLoopGuard = ToolLoopGuard()

    fun isEnabled(context: Context): Boolean = AgentFlags.isPipelineEnabled(context)

    fun graph(context: Context): AgentToolGraph = AgentToolGraph.getInstance(context).ensureRegistered()

    /**
     * Starts a turn: refreshes the tool registry (skills / MCP may have
     * changed), resets the loop guard and tags traces with [prompt].
     * Workflow variables are reset by JarvisToolDispatcher.startNewTurn.
     */
    fun beginTurn(context: Context, prompt: String) {
        val graph = AgentToolGraph.getInstance(context)
        runCatching { graph.refreshTools() }
            .onFailure { AlLog.tag(TAG).w(it, "tool refresh failed") }
        graph.traceStore.beginTurn(prompt)
        turnGuard = ToolLoopGuard()
    }

    /** Attaches the final assistant text to this turn's traces. */
    fun endTurn(context: Context, output: String?) {
        AgentToolGraph.peek()?.traceStore?.endTurn(output)
    }

    // ── llama.cpp / Hermes (prompt-format tool calls) ───────────────────────

    /**
     * Tool schemas for a Jarvis provider, ROUTED to [query] through the
     * AndroLLM [com.jarvis.assistant.androllm.agent.router.ToolRouter] over the
     * full registry (Jarvis tools + AndroLLM tools), honouring the master
     * switch and per-tool toggles. Empty when the route exposes no tools.
     */
    suspend fun routedSchemas(context: Context, query: String, hasAttachments: Boolean = false): List<JSONObject> {
        val graph = graph(context)
        val settings = graph.settingsStore.current()
        if (!settings.toolCallingEnabled) return emptyList()
        val enabled = graph.registry.all()
            .map { it.spec }
            .filter { settings.isToolEnabled(it.name) && it.isAvailable }
            .sortedBy { it.name }
        if (enabled.isEmpty()) return emptyList()
        val specs = if (query.isBlank()) enabled else graph.router.route(query, hasAttachments, enabled).specs
        return specs.map { JarvisToolBridge.toJarvisSchema(it) }
    }

    /**
     * Executes one provider tool call through the gated coordinator
     * (validation → confirmation → timeout → retry/cache → trace). Jarvis
     * tools still run inside JarvisToolDispatcher via their registry adapter.
     */
    suspend fun dispatch(context: Context, call: AiToolCall): AiToolResult {
        val graph = graph(context)
        if (!graph.registry.contains(call.functionName)) {
            // Not representable in the registry (e.g. a skill/MCP name the
            // registry rejected): keep Jarvis' original dispatch path.
            return com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher.getInstance(context).dispatchTool(call)
        }
        val arguments: JsonObject = runCatching { json.parseToJsonElement(call.arguments.toString()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val toolCall = ToolCall(id = call.callId, name = call.functionName, arguments = arguments)
        val records = graph.coordinator.executeCalls(listOf(toolCall), guard = turnGuard)
        val result: ToolResult = records.firstOrNull()?.result
            ?: ToolResult.Failure(
                turnGuard.stopReason() ?: "Tool '${call.functionName}' was not executed (dependency or loop guard).",
                retryable = false
            )
        return JarvisToolBridge.toAiToolResult(call.callId, call.functionName, result)
    }

    // ── LiteRT-LM ───────────────────────────────────────────────────────────

    /**
     * Full agent turn on the LiteRT-LM engine. [messages] is the provider's
     * history (system first, latest user last). Streams sanitized text through
     * [emit]; returns the final answer text.
     *
     * 1. Routed tool advertisement, budgeted to the container's real context
     *    and the family cap (4500 chars for small Qwen repacks).
     * 2. Families WITHOUT native tool markers: plan → execute → re-plan via
     *    the JSON planner ([ToolRunCoordinator.runLocalWorkflow]) before the
     *    answer generation.
     * 3. Native-marker families: cloud-style loop — execute `<|tool_call|>`
     *    markers, feed results back, continue, up to maxToolRounds.
     */
    suspend fun runLiteRtTurn(
        context: Context,
        repo: EngineRepository,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig,
        emit: suspend (String) -> Unit,
        onEvent: suspend (ToolEvent) -> Unit = {}
    ): String {
        val graph = graph(context)
        val settings = graph.settingsStore.current()
        val ready = repo.engineState.value as? EngineState.Ready
        val contextLength = ready?.model?.contextLength?.takeIf { it > 0 } ?: AppConstants.Model.DEFAULT_CONTEXT_LENGTH
        val reservedOutputTokens = config.maxTokens
            .coerceIn(MIN_OUTPUT_RESERVE_TOKENS, MAX_OUTPUT_RESERVE_TOKENS)
            .coerceAtMost((contextLength / 3).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS))
        val systemCharsBudget = (contextLength - reservedOutputTokens - MIN_HISTORY_TOKENS)
            .coerceAtLeast(0) * CHARS_PER_TOKEN_ESTIMATE
        val familyAdCapChars = ready?.model?.toolAdvertisementCapChars ?: Int.MAX_VALUE
        val query = messages.lastOrNull { it.role == "user" }?.content.orEmpty()

        var history = messages
        if (settings.toolCallingEnabled) {
            val advertisement = runCatching {
                graph.promptBuilder.advertisement(
                    maxChars = minOf(systemCharsBudget, familyAdCapChars),
                    query = query
                )
            }.getOrNull()
            if (advertisement != null) {
                history = insertAfterSystem(history, ChatPromptMessage(role = "system", content = advertisement))
            }
            if (ready?.model?.nativeToolMarkers != true) {
                // JSON-compat planner: plan → execute → observe → re-plan.
                history = graph.coordinator.runLocalWorkflow(history, onEvent = onEvent, guard = turnGuard)
                repo.resetChat()
            }
        }

        val maxRounds = if (settings.toolCallingEnabled) settings.maxToolRounds.coerceAtLeast(1) else 1
        var finalText = ""
        for (round in 0 until maxRounds) {
            val text = generateStreaming(repo, history, config, emit)
            val nativeCalls = runCatching { repo.takeLastNativeToolCalls() }.getOrDefault(emptyList())
            finalText = text
            if (nativeCalls.isEmpty() || !settings.toolCallingEnabled) break
            val calls = nativeCalls.mapIndexed { index, native ->
                ToolCall(
                    id = "native_${native.name.hashCode().toUInt().toString(16)}_$index",
                    name = native.name,
                    arguments = runCatching { json.parseToJsonElement(native.argumentsJson.ifBlank { "{}" }).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                )
            }
            val records: List<ToolExecutionRecord> = graph.coordinator.executeCalls(calls, onEvent, turnGuard)
            val assistantTurn = if (text.isNotBlank()) listOf(ChatPromptMessage(role = "assistant", content = text)) else emptyList()
            if (records.isEmpty()) {
                if (turnGuard.blockedThisTurn) {
                    val guardMessage = turnGuard.stopReason()
                        ?: "The requested tools have already been used and produced no additional useful information. Continue reasoning without further tool calls."
                    history = history + assistantTurn + ChatPromptMessage(role = "system", content = guardMessage)
                    repo.resetChat()
                    finalText = generateStreaming(repo, history, config, emit)
                }
                break
            }
            history = history + assistantTurn + graph.coordinator.buildLocalToolFeedback(records)
            // The engine keeps a stateful conversation; replaying the grown
            // history is only correct from a fresh conversation.
            repo.resetChat()
            if (round == maxRounds - 1) {
                finalText = generateStreaming(repo, history, config, emit)
            }
        }
        var answer = OutputSanitizer.sanitize(finalText)
        if (answer.isBlank() && graph.traceStore.traces.value.isNotEmpty()) {
            answer = graph.traceStore.lastTurnSummary()
            emit(answer)
        }
        endTurn(context, answer)
        return answer
    }

    /** One streaming generation; emits sanitized deltas, returns the raw text. */
    private suspend fun generateStreaming(
        repo: EngineRepository,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig,
        emit: suspend (String) -> Unit
    ): String = coroutineScope {
        val emitted = StringBuilder()
        suspend fun push(raw: String) {
            val visible = OutputSanitizer.streamingReady(raw)
            if (visible.length > emitted.length && visible.startsWith(emitted)) {
                val delta = visible.substring(emitted.length)
                emitted.append(delta)
                emit(delta)
            }
        }
        val watcher = launch {
            repo.generationState.collect { state ->
                if (state is GenerationState.Generating) push(state.streamingText)
            }
        }
        repo.generateChat(messages, addAssistant = true, config = config)
        watcher.cancelAndJoin()
        when (val state = repo.generationState.value) {
            is GenerationState.Completed -> {
                val clean = OutputSanitizer.sanitize(state.text)
                if (clean.startsWith(emitted) && clean.length > emitted.length) emit(clean.substring(emitted.length))
                else if (emitted.isEmpty() && clean.isNotBlank()) emit(clean)
                state.text
            }
            is GenerationState.Failed -> {
                emit(" Sir, LiteRT-LM error: ${state.message}")
                state.partialText
            }
            else -> ""
        }
    }

    private fun insertAfterSystem(messages: List<ChatPromptMessage>, extra: ChatPromptMessage): List<ChatPromptMessage> {
        val firstNonSystem = messages.indexOfFirst { it.role != "system" }.let { if (it < 0) messages.size else it }
        return messages.take(firstNonSystem) + extra + messages.drop(firstNonSystem)
    }

    // ── Cloud Gateway (native function calling) ─────────────────────────────

    /**
     * Cloud tool rounds (AndroLLM ChatViewModel.runCloudRounds): routed tools
     * on the request, streamed tool-call fragments buffered until complete,
     * plain-text tool syntax recovered by [CloudFallbackToolParser], calls run
     * through [com.jarvis.assistant.androllm.agent.cloud.CloudToolRouter]
     * (conditional gating + gated coordinator), results fed back, continued
     * when the provider truncates (`finish_reason=length`). Streams visible
     * text via [emit]; returns the final answer.
     */
    suspend fun runCloudTurn(
        context: Context,
        gateway: CloudGateway,
        initialHistory: List<CloudChatMessage>,
        baseConfig: CloudGenerationConfig,
        sessionId: String?,
        emit: suspend (String) -> Unit,
        onEvent: suspend (ToolEvent) -> Unit = {}
    ): String {
        val graph = graph(context)
        val settings = graph.settingsStore.current()
        val query = initialHistory.lastOrNull { it.role == "user" }?.content.orEmpty()
        val history = initialHistory.toMutableList()
        val tools = if (settings.toolCallingEnabled) {
            runCatching { graph.cloudToolRouter.advertisedTools(query) }.getOrDefault(emptyList())
        } else emptyList()
        if (tools.isNotEmpty()) {
            graph.coordinator.agentContextMessage()?.let { msg ->
                val idx = history.indexOfFirst { it.role != "system" }.let { if (it < 0) history.size else it }
                history.add(idx, CloudChatMessage(role = "system", content = msg.content))
            }
        }
        val maxRounds = settings.maxToolRounds.coerceAtLeast(1) + MAX_CLOUD_CONTINUATIONS
        val answer = StringBuilder()
        var continuationCount = 0
        round@ for (round in 0 until maxRounds) {
            val roundBuffer = StringBuilder()
            val toolBuffer = StreamingToolCallBuffer()
            var finishReason: String? = null
            var emittedVisible = 0
            gateway.streamChat(
                messages = history,
                config = baseConfig.copy(tools = tools),
                sessionId = sessionId
            ).collect { event ->
                when (event) {
                    is CloudStreamEvent.Delta -> {
                        roundBuffer.append(event.text)
                        val visible = OutputSanitizer.streamingReady(roundBuffer.toString())
                        if (visible.length > emittedVisible) {
                            emit(visible.substring(emittedVisible))
                            emittedVisible = visible.length
                        }
                    }
                    is CloudStreamEvent.ToolCallDelta -> toolBuffer.accumulate(event)
                    is CloudStreamEvent.Finish -> finishReason = event.reason
                    else -> Unit
                }
            }
            var buffered = toolBuffer.flushOnFinish()
            if (buffered.isEmpty() && tools.isNotEmpty()) {
                val roundText = roundBuffer.toString()
                val fallback = CloudFallbackToolParser.parse(roundText)
                if (fallback.isNotEmpty()) {
                    roundBuffer.setLength(0)
                    roundBuffer.append(CloudFallbackToolParser.stripToolSyntax(roundText))
                    buffered = fallback.mapIndexed { index, call ->
                        StreamingToolCallBuffer.BufferedToolCall(index, null, call.name, call.argumentsJson)
                    }
                }
            }
            if (buffered.isNotEmpty()) {
                if (tools.isEmpty()) {
                    history += CloudChatMessage(role = "system", content = OutputSanitizer.NO_TOOL_EXECUTOR_INSTRUCTION)
                    continue@round
                }
                val cloudCalls = buffered.map {
                    CloudToolCall(index = it.index, id = it.id, type = "function", function = CloudToolCallFunction(it.name, it.argumentsJson))
                }
                val interim = roundBuffer.toString()
                if (interim.isNotBlank()) answer.append(interim)
                val routed = graph.cloudToolRouter.routeAndExecute(
                    calls = cloudCalls,
                    userQuery = query,
                    assistantContent = interim.takeIf { it.isNotBlank() },
                    onEvent = onEvent,
                    guard = turnGuard
                )
                if (routed.messages.isEmpty() && turnGuard.blockedThisTurn) {
                    history += CloudChatMessage(
                        role = "system",
                        content = turnGuard.stopReason()
                            ?: "The requested tools have already been used and produced no additional useful information. Continue reasoning without further tool calls."
                    )
                    // One final plain answer round without tools.
                    gateway.streamChat(history, baseConfig, sessionId = sessionId).collect { event ->
                        if (event is CloudStreamEvent.Delta) {
                            answer.append(event.text)
                            emit(event.text)
                        }
                    }
                    break@round
                }
                history += routed.messages
                continue@round
            }
            val finalText = roundBuffer.toString()
            answer.append(finalText)
            val truncated = finishReason == "length" || finishReason == "max_tokens"
            if (truncated && finalText.isNotBlank() && continuationCount < MAX_CLOUD_CONTINUATIONS) {
                continuationCount++
                history += CloudChatMessage(role = "assistant", content = finalText)
                history += CloudChatMessage(role = "user", content = "Continue.")
                continue@round
            }
            break@round
        }
        var result = OutputSanitizer.sanitize(answer.toString())
        if (result.isBlank() && graph.traceStore.traces.value.isNotEmpty()) {
            result = graph.traceStore.lastTurnSummary()
            emit(result)
        }
        endTurn(context, result)
        return result
    }
}
