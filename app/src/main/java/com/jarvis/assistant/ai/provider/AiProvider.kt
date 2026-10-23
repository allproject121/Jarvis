package com.jarvis.assistant.ai.provider

import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

enum class AiProviderType(val displayName: String) {
    GEMINI_LIVE_ONLINE("Gemini Live (Online)"),
    LOCAL_PHONE_OFFLINE("Local Core (Offline SLM)"),
    HERMES_AGENT_NATIVE("Hermes Agent (Autonomous)"),
    LAPTOP_HERMES_BRIDGE("Laptop Core (Workstation)")
}

sealed class ProviderHealthState {
    object Healthy : ProviderHealthState()
    data class Degraded(val reason: String) : ProviderHealthState()
    data class Unavailable(val error: String) : ProviderHealthState()
}

data class AiToolCall(
    val callId: String,
    val functionName: String,
    val arguments: JSONObject
)

data class AiToolResult(
    val callId: String,
    val functionName: String,
    val isSuccess: Boolean,
    val resultJson: JSONObject,
    val errorMessage: String? = null
)

data class AiStreamChunk(
    val token: String,
    val isComplete: Boolean = false,
    val toolCallDetected: AiToolCall? = null
)

/**
 * Standardized interface for all Jarvis AI providers.
 * Unifies online Gemini Live, on-device llama.cpp SLM, and Hermes autonomous agent.
 */
interface AiProvider {
    val providerType: AiProviderType

    suspend fun initialize(): Boolean
    fun isReady(): Boolean
    fun checkHealth(): ProviderHealthState

    fun streamResponse(
        prompt: String,
        systemPrompt: String = "",
        memoryContext: String = "",
        chatHistory: List<ChatTurn> = emptyList(),
        availableTools: List<JSONObject> = emptyList()
    ): Flow<AiStreamChunk>

    suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult

    suspend fun unload()
}
