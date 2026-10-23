package com.jarvis.assistant.ai.provider

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ai.local.ToolCallParser
import com.jarvis.assistant.automation.dispatcher.JarvisToolDispatcher
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Native Hermes Autonomous Agent Provider.
 * Implements the official Nous Hermes agentic workflow on-device with multi-step reasoning,
 * autonomous tool calling, and scratchpad execution loops.
 */
class HermesAgentProvider private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) : AiProvider {

    companion object {
        private const val TAG = "HermesAgentProvider"
        private const val MAX_AGENT_STEPS = 5

        @Volatile
        private var instance: HermesAgentProvider? = null

        fun getInstance(context: Context, scope: CoroutineScope): HermesAgentProvider {
            return instance ?: synchronized(this) {
                instance ?: HermesAgentProvider(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    override val providerType: AiProviderType = AiProviderType.HERMES_AGENT_NATIVE

    private val localPhoneProvider = LocalPhoneProvider.getInstance(context, scope)
    private val toolDispatcher = JarvisToolDispatcher.getInstance(context)

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        localPhoneProvider.initialize()
    }

    override fun isReady(): Boolean = localPhoneProvider.isReady()

    override fun checkHealth(): ProviderHealthState = localPhoneProvider.checkHealth()

    override fun streamResponse(
        prompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        availableTools: List<JSONObject>
    ): Flow<AiStreamChunk> = flow {
        val cleanGoal = prompt.trim()
        if (cleanGoal.isBlank()) return@flow

        if (!localPhoneProvider.isReady()) {
            val initialized = localPhoneProvider.initialize()
            if (!initialized) {
                emit(AiStreamChunk(
                    token = "Sir, the Hermes Agent core requires local model weights to be downloaded in Settings.",
                    isComplete = true
                ))
                return@flow
            }
        }

        val tools = if (availableTools.isNotEmpty()) availableTools else toolDispatcher.getAvailableToolSchemas()
        val hermesSystemPrompt = buildHermesSystemPrompt(systemPrompt, memoryContext, tools)

        val workingHistory = chatHistory.toMutableList()
        var currentPrompt = cleanGoal
        var stepCount = 0

        Log.i(TAG, "Hermes Agent started autonomous loop for goal: '$cleanGoal'")

        while (stepCount < MAX_AGENT_STEPS) {
            stepCount++
            val stepAccumulator = StringBuilder()
            var detectedToolCall: AiToolCall? = null

            localPhoneProvider.streamResponse(
                prompt = currentPrompt,
                systemPrompt = hermesSystemPrompt,
                memoryContext = memoryContext,
                chatHistory = workingHistory,
                availableTools = tools
            ).collect { chunk ->
                if (chunk.token.isNotEmpty()) {
                    stepAccumulator.append(chunk.token)
                    emit(chunk)
                }
                if (chunk.toolCallDetected != null) {
                    detectedToolCall = chunk.toolCallDetected
                }
            }

            // Check if step output contained a tool call
            if (detectedToolCall == null && stepAccumulator.contains("<tool_call>")) {
                detectedToolCall = ToolCallParser.parseToolCall(stepAccumulator.toString())
            }

            if (detectedToolCall != null) {
                val call = detectedToolCall!!
                Log.i(TAG, "Hermes Step $stepCount: Executing tool '${call.functionName}' with args: ${call.arguments}")

                emit(AiStreamChunk(token = "\n[Executing ${call.functionName}...]", isComplete = false))
                val result = toolDispatcher.dispatchTool(call)

                val responseMsg = result.resultJson.optString("message", "Success")
                emit(AiStreamChunk(token = "\n$responseMsg", isComplete = false))

                // If tool was task_completed, finish the autonomous loop
                if (call.functionName == "task_completed") {
                    Log.i(TAG, "Hermes Agent finished task successfully at step $stepCount.")
                    break
                }

                // Add step to conversation history for next reasoning loop
                workingHistory.add(ChatTurn(
                    userTranscript = currentPrompt,
                    jarvisResponse = stepAccumulator.toString() + "\n<tool_response>\n${result.resultJson}\n</tool_response>"
                ))

                // Prepare next turn to continue reasoning
                currentPrompt = "Tool '${call.functionName}' executed. Result: ${result.resultJson}. Continue achieving the user goal or call task_completed."
            } else {
                // Conversational answer without more tool calls: task complete
                break
            }
        }

        emit(AiStreamChunk(token = "", isComplete = true))
    }.flowOn(Dispatchers.IO)

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult {
        return toolDispatcher.dispatchTool(toolCall)
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        localPhoneProvider.unload()
    }

    private fun buildHermesSystemPrompt(
        basePrompt: String,
        memoryContext: String,
        tools: List<JSONObject>
    ): String {
        return buildString {
            if (basePrompt.isNotBlank()) append(basePrompt.trim()).append("\n\n")
            else append("You are Hermes, an autonomous AI agent in JARVIS created by Roshan Sir.\n")

            append("You accomplish user instructions by reasoning step-by-step and calling available tools.\n")
            if (memoryContext.isNotBlank()) {
                append("\n[PERSISTENT MEMORY CONTEXT]:\n").append(memoryContext.trim()).append("\n")
            }

            append("\n# Tools Schema\n")
            append("You have access to the following native tools:\n<tools>\n")
            for (t in tools) {
                append(t.toString()).append("\n")
            }
            append("</tools>\n\n")

            append("When you need to execute a tool, reply in this format:\n")
            append("<tool_call>\n{\"name\": \"function_name\", \"arguments\": { ... }}\n</tool_call>\n")
            append("When you have completed all requested steps, call task_completed with a summary of what was accomplished.\n")
        }
    }
}
