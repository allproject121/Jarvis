package com.jarvis.assistant.ai.provider

import android.content.Context
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.androllm.cloud.AndroLlmCloud
import com.jarvis.assistant.androllm.cloud.model.CloudChatMessage
import com.jarvis.assistant.androllm.cloud.model.CloudException
import com.jarvis.assistant.androllm.cloud.model.CloudGenerationConfig
import com.jarvis.assistant.androllm.cloud.model.CloudStreamEvent
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/**
 * Optional OpenAI-compatible multi-provider gateway (AndroLLM LiteLLM client:
 * LiteLLM proxy, OpenAI, OpenRouter, Groq, Ollama, any `/v1/chat/completions`
 * endpoint) exposed as a Jarvis [AiProvider].
 *
 * Only reached through the CLOUD_GATEWAY router mode, which additionally
 * requires `AndroLlmFlags.CLOUD_GATEWAY` (default OFF). Gemini Live requests
 * are never routed here.
 */
class CloudGatewayProvider private constructor(
    private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope
) : AiProvider {

    companion object {
        private const val MAX_HISTORY_TURNS = 8

        @Volatile
        private var instance: CloudGatewayProvider? = null

        fun getInstance(context: Context, scope: CoroutineScope): CloudGatewayProvider {
            return instance ?: synchronized(this) {
                instance ?: CloudGatewayProvider(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    override val providerType: AiProviderType = AiProviderType.CLOUD_GATEWAY

    private val cloud: AndroLlmCloud get() = AndroLlmCloud.getInstance(context)
    private val toolDispatcher = JarvisToolDispatcher.getInstance(context)

    @Volatile
    private var configured = false

    @Volatile
    private var lastHealth: ProviderHealthState = ProviderHealthState.Unavailable("Gateway not initialized")

    override suspend fun initialize(): Boolean {
        val resolved = runCatching { cloud.providerManager.resolveChatModel() }.getOrNull()
        lastHealth = when {
            resolved == null -> ProviderHealthState.Unavailable("No gateway provider/model configured")
            !resolved.provider.enabled -> ProviderHealthState.Degraded("Provider '${resolved.provider.name}' is disabled")
            resolved.provider.lastError.isNotBlank() -> ProviderHealthState.Degraded(resolved.provider.lastError)
            else -> ProviderHealthState.Healthy
        }
        configured = resolved != null
        return configured
    }

    override fun isReady(): Boolean = configured

    /** Last health computed by [initialize] (non-blocking). */
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
        val settings = cloud.providerManager.settings.first()
        val ps = settings.providerSettings
        val config = CloudGenerationConfig(
            temperature = ps.defaultTemperature,
            topP = ps.defaultTopP,
            topK = ps.defaultTopK,
            maxTokens = cloud.gateway.maxOutputTokensFor()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            seed = ps.defaultSeed,
            stop = ps.defaultStop
        )
        val sessionId = "jarvis-router"
        // Optional AndroLLM agent pipeline (default OFF): native function
        // calling with routed Jarvis + AndroLLM tools through the gated executor.
        if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            JarvisToolDispatcher.getInstance(context).startNewTurn(java.util.UUID.randomUUID().toString())
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.beginTurn(context, clean)
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.runCloudTurn(
                context = context,
                gateway = cloud.gateway,
                initialHistory = buildMessages(clean, systemPrompt, memoryContext, chatHistory),
                baseConfig = config,
                sessionId = sessionId,
                emit = { delta -> if (delta.isNotEmpty()) emit(AiStreamChunk(token = delta)) }
            )
            configured = true
            emit(AiStreamChunk(token = "", isComplete = true))
            return@flow
        }
        cloud.gateway.streamChat(
                messages = buildMessages(clean, systemPrompt, memoryContext, chatHistory),
                config = config,
                sessionId = sessionId
            ).collect { event ->
                if (event is CloudStreamEvent.Delta && event.text.isNotEmpty()) {
                    emit(AiStreamChunk(token = event.text))
                }
            }
        configured = true
        emit(AiStreamChunk(token = "", isComplete = true))
    }.flowOn(Dispatchers.IO)

    /** Throws [CloudException] on failure so the router can fail over. */
    suspend fun chatOnce(prompt: String, systemPrompt: String = ""): String =
        cloud.gateway.chatOnce(
            messages = listOfNotNull(
                systemPrompt.takeIf { it.isNotBlank() }?.let { CloudChatMessage(role = "system", content = it) },
                CloudChatMessage(role = "user", content = prompt)
            ),
            config = CloudGenerationConfig()
        )

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult =
        if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, toolCall)
        } else {
            toolDispatcher.dispatchTool(toolCall)
        }

    override suspend fun unload() {
        // Stateless HTTP client — nothing to release.
    }

    private fun buildMessages(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>
    ): List<CloudChatMessage> {
        val out = mutableListOf<CloudChatMessage>()
        val system = buildString {
            append(systemPrompt.ifBlank { "You are JARVIS, a sophisticated and concise AI assistant." }.trim())
            if (memoryContext.isNotBlank()) append("\n\n").append(memoryContext.trim())
        }
        out += CloudChatMessage(role = "system", content = system)
        for (turn in chatHistory.takeLast(MAX_HISTORY_TURNS)) {
            if (turn.userTranscript.isNotBlank()) out += CloudChatMessage(role = "user", content = turn.userTranscript.trim())
            if (turn.jarvisResponse.isNotBlank()) {
                out += CloudChatMessage(
                    role = "assistant",
                    content = ToolCallParser.cleanConversationalText(turn.jarvisResponse).trim()
                )
            }
        }
        out += CloudChatMessage(role = "user", content = prompt)
        return out
    }
}
