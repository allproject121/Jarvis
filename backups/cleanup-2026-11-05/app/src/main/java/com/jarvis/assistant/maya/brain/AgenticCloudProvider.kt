package com.jarvis.assistant.maya.brain

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.ai.provider.AiProvider
import com.jarvis.assistant.ai.provider.AiProviderType
import com.jarvis.assistant.ai.provider.AiStreamChunk
import com.jarvis.assistant.ai.provider.AiToolCall
import com.jarvis.assistant.ai.provider.AiToolResult
import com.jarvis.assistant.ai.provider.ProviderHealthState
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Advanced-Agentic brain: OpenAI-compatible chat completions (or Anthropic messages) with
 * NATIVE tool calling. Every Jarvis tool schema is sent as `tools`; `tool_calls` in the
 * reply are dispatched through [JarvisToolDispatcher] and the loop continues until the
 * model answers in plain text (max [MAX_TOOL_ROUNDS] rounds).
 *
 * Never touches Gemini Live. One instance per [role]; [configProvider] is re-read on
 * every request so Settings changes apply immediately.
 */
class AgenticCloudProvider(
    private val context: Context,
    private val role: SubAgentRole? = null,
    private val configProvider: () -> BrainConfig = {
        val s = BrainSettings.get(context)
        if (role == null) s.mainBrain() else s.brainFor(role)
    }
) : AiProvider {

    companion object {
        private const val TAG = "AgenticBrain"
        const val MAX_TOOL_ROUNDS = 8
        private const val MAX_HISTORY_TURNS = 10
        private const val MAX_TOOLS = 128
        private const val MAX_AUTOPICK_ROTATIONS = 4

        @Volatile private var mainInstance: AgenticCloudProvider? = null

        /** Shared main-brain instance. */
        fun main(context: Context): AgenticCloudProvider =
            mainInstance ?: synchronized(this) {
                mainInstance ?: AgenticCloudProvider(context.applicationContext).also { mainInstance = it }
            }

        fun forRole(context: Context, role: SubAgentRole): AgenticCloudProvider =
            AgenticCloudProvider(context.applicationContext, role)
    }

    override val providerType: AiProviderType = AiProviderType.CLOUD_GATEWAY

    @Volatile private var lastHealth: ProviderHealthState = ProviderHealthState.Unavailable("Not initialized")

    /** Last error explanation (Maya-style, speakable) from the most recent request. */
    @Volatile var lastError: String? = null
        private set

    /** Model actually used by the last successful request (AutoPick may differ from settings). */
    @Volatile var lastModelUsed: String = ""
        private set

    val config: BrainConfig get() = configProvider()

    override suspend fun initialize(): Boolean {
        val c = config
        lastHealth = if (c.isConfigured) ProviderHealthState.Healthy
        else ProviderHealthState.Unavailable(c.missingReason() ?: "Brain not configured")
        return c.isConfigured
    }

    override fun isReady(): Boolean = config.isConfigured

    override fun checkHealth(): ProviderHealthState = lastHealth

    override fun streamResponse(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        availableTools: List<JSONObject>
    ): Flow<AiStreamChunk> = flow {
        val clean = prompt.trim()
        if (clean.isBlank()) return@flow
        val cfg = config
        if (!cfg.isConfigured) {
            val why = cfg.missingReason() ?: BrainHttpClient.MSG_KEY_OR_MODEL
            lastError = why
            emit(AiStreamChunk(token = why))
            emit(AiStreamChunk(token = "", isComplete = true))
            return@flow
        }
        val tools = if (availableTools.isNotEmpty()) availableTools else allToolSchemas()
        val messages = buildMessages(clean, systemPrompt, memoryContext, chatHistory)
        val dispatcher = JarvisToolDispatcher.getInstance(context)
        dispatcher.startNewTurn(UUID.randomUUID().toString())

        var rounds = 0
        while (true) {
            val result = requestWithRotation(cfg, messages, tools)
            when (result) {
                is BrainResult.Failure -> {
                    lastError = result.userMessage
                    lastHealth = ProviderHealthState.Degraded(result.detail)
                    emit(AiStreamChunk(token = result.userMessage))
                    emit(AiStreamChunk(token = "", isComplete = true))
                    return@flow
                }
                is BrainResult.Success -> {
                    lastHealth = ProviderHealthState.Healthy
                    lastModelUsed = result.modelUsed
                    val turn = result.turn
                    if (!turn.hasToolCalls || rounds >= MAX_TOOL_ROUNDS) {
                        val text = ToolCallParser.cleanConversationalText(turn.text).trim()
                        val spoken = if (text.isNotBlank()) text
                        else if (turn.hasToolCalls) "Sir, tool steps complete ho gaye par model ne final jawab nahi diya."
                        else BrainHttpClient.MSG_NO_ANSWER
                        emit(AiStreamChunk(token = spoken))
                        emit(AiStreamChunk(token = "", isComplete = true))
                        return@flow
                    }
                    rounds++
                    // Echo the assistant turn (with its tool calls) then every tool result.
                    messages.put(turn.rawAssistantMessage ?: BrainJson.openAiAssistantWithToolCalls(turn.text, turn.toolCalls))
                    for (call in turn.toolCalls) {
                        val aiCall = AiToolCall(call.id, call.name, call.arguments)
                        emit(AiStreamChunk(token = "", toolCallDetected = aiCall))
                        val toolResult = executeToolCall(aiCall)
                        val payload = toolResult.resultJson.takeIf { it.length() > 0 } ?: JSONObject().apply {
                            put("success", toolResult.isSuccess)
                            put("message", toolResult.errorMessage ?: "")
                        }
                        messages.put(BrainJson.openAiToolResult(call.id, call.name, payload))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult = try {
        JarvisToolDispatcher.getInstance(context).dispatchTool(toolCall)
    } catch (e: Exception) {
        Log.e(TAG, "Tool ${toolCall.functionName} failed: ${e.message}", e)
        AiToolResult(toolCall.callId, toolCall.functionName, false, JSONObject().put("success", false).put("message", e.message ?: "tool failed"), e.message)
    }

    override suspend fun unload() {
        // Stateless HTTP client.
    }

    // ── One-shot helpers (sub-agents, tests, translation) ───────────────────

    /**
     * Single request without tools. [imagePath] (jpeg/png/webp file) is attached as an
     * OpenAI `image_url` data URI for vision models. Returns the answer or throws with the
     * Maya-style explanation.
     */
    suspend fun chatOnce(
        prompt: String,
        systemPrompt: String = "",
        imagePath: String? = null,
        withTools: Boolean = false,
        maxTokens: Int = 2048
    ): String = withContext(Dispatchers.IO) {
        val cfg = config
        if (!cfg.isConfigured) throw IllegalStateException(cfg.missingReason() ?: BrainHttpClient.MSG_KEY_OR_MODEL)
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        val userContent: Any = if (!imagePath.isNullOrBlank()) {
            val f = File(imagePath)
            if (!f.exists() || f.length() == 0L) throw IllegalArgumentException("Image file not found: $imagePath")
            val mime = when (f.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
            }
        } else prompt
        messages.put(JSONObject().put("role", "user").put("content", userContent))
        val tools = if (withTools) allToolSchemas() else emptyList()
        var rounds = 0
        while (true) {
            when (val r = requestWithRotation(cfg, messages, tools, maxTokens)) {
                is BrainResult.Failure -> {
                    lastError = r.userMessage
                    val detail = if (r.detail.isNotBlank()) " (${r.detail.take(160)})" else ""
                    throw IllegalStateException(r.userMessage + detail)
                }
                is BrainResult.Success -> {
                    lastModelUsed = r.modelUsed
                    val turn = r.turn
                    if (!turn.hasToolCalls || rounds >= MAX_TOOL_ROUNDS || !withTools) {
                        return@withContext ToolCallParser.cleanConversationalText(turn.text).trim()
                    }
                    rounds++
                    messages.put(turn.rawAssistantMessage ?: BrainJson.openAiAssistantWithToolCalls(turn.text, turn.toolCalls))
                    for (call in turn.toolCalls) {
                        val res = executeToolCall(AiToolCall(call.id, call.name, call.arguments))
                        messages.put(BrainJson.openAiToolResult(call.id, call.name, res.resultJson))
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return@withContext ""
    }

    /** Maya "Test" button: one tiny request. */
    data class TestOutcome(val ok: Boolean, val latencyMs: Long, val model: String, val message: String)

    suspend fun test(): TestOutcome = withContext(Dispatchers.IO) {
        val cfg = config
        if (!cfg.isConfigured) return@withContext TestOutcome(false, 0, cfg.model, cfg.missingReason() ?: "Brain not configured")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word OK."))
        when (val r = requestWithRotation(cfg, messages, emptyList(), maxTokens = 16)) {
            is BrainResult.Failure -> TestOutcome(false, 0, cfg.model, r.userMessage + (if (r.detail.isNotBlank()) "\n${r.detail.take(200)}" else ""))
            is BrainResult.Success -> TestOutcome(true, r.latencyMs, r.modelUsed, r.turn.text.trim().ifBlank { "OK" })
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun allToolSchemas(): List<JSONObject> {
        MayaToolRegistry.ensureInstalled(context)
        val all = runCatching { JarvisToolDispatcher.getInstance(context).getAvailableToolSchemas() }.getOrDefault(emptyList())
        return if (all.size > MAX_TOOLS) all.take(MAX_TOOLS) else all
    }

    /**
     * Performs the request; with AutoPick on OpenRouter it picks a free tool-capable model and
     * rotates on 429/5xx. Without AutoPick a 429/5xx is retried once after a short pause.
     */
    private fun requestWithRotation(
        cfg: BrainConfig,
        messages: JSONArray,
        tools: List<JSONObject>,
        maxTokens: Int = 2048
    ): BrainResult {
        if (cfg.autoPick) {
            var lastFailure: BrainResult.Failure? = null
            repeat(MAX_AUTOPICK_ROTATIONS) {
                val model = OpenRouterAutoPick.pick(context, cfg.apiKey)
                    ?: return lastFailure ?: BrainResult.Failure(0, OpenRouterAutoPick.NO_FREE_MODEL_WORKING, "no free tool-capable models listed", retryable = true)
                Log.i(TAG, "AutoPick(model=$model)")
                val r = BrainHttpClient.chat(cfg, model, messages, tools, maxTokens = maxTokens)
                if (r is BrainResult.Success) {
                    OpenRouterAutoPick.reportSuccess(model)
                    return r
                }
                val f = r as BrainResult.Failure
                if (f.httpCode == 429 && OpenRouterAutoPick.isNegativeBalance(f.detail)) return f
                if (f.httpCode == 429 || f.httpCode >= 500 || f.httpCode == 404 || f.httpCode == 0) {
                    OpenRouterAutoPick.reportFailure(model, f.httpCode)
                    lastFailure = f
                } else return f
            }
            return BrainResult.Failure(
                lastFailure?.httpCode ?: 429,
                OpenRouterAutoPick.NO_FREE_MODEL_WORKING,
                lastFailure?.detail ?: "all free models rate-limited",
                retryable = true
            )
        }
        val first = BrainHttpClient.chat(cfg, cfg.model, messages, tools, maxTokens = maxTokens)
        if (first is BrainResult.Failure && first.retryable) {
            try { Thread.sleep(if (first.httpCode == 429) 2500L else 1200L) } catch (_: InterruptedException) { return first }
            return BrainHttpClient.chat(cfg, cfg.model, messages, tools, maxTokens = maxTokens)
        }
        return first
    }

    private fun buildMessages(prompt: String, systemPrompt: String, memoryContext: String, history: List<ChatTurn>): JSONArray {
        val out = JSONArray()
        val system = buildString {
            append(systemPrompt.ifBlank { BrainPrompts.defaultSystemPrompt(context) }.trim())
            if (memoryContext.isNotBlank()) append("\n\n").append(memoryContext.trim())
            append("\n\n").append(BrainPrompts.TOOL_RULES)
        }
        out.put(JSONObject().put("role", "system").put("content", system))
        for (turn in history.takeLast(MAX_HISTORY_TURNS)) {
            if (turn.userTranscript.isNotBlank()) out.put(JSONObject().put("role", "user").put("content", turn.userTranscript.trim()))
            if (turn.jarvisResponse.isNotBlank()) {
                out.put(JSONObject().put("role", "assistant").put("content", ToolCallParser.cleanConversationalText(turn.jarvisResponse).trim()))
            }
        }
        out.put(JSONObject().put("role", "user").put("content", prompt))
        return out
    }
}
