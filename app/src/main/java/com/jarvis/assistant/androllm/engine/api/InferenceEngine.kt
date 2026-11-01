package com.jarvis.assistant.androllm.engine.api

import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.androllm.engine.support.getOrNull
import com.jarvis.assistant.androllm.engine.support.getOrThrow
import com.jarvis.assistant.androllm.engine.support.Model
import com.jarvis.assistant.androllm.engine.backend.BackendCapabilities
import com.jarvis.assistant.androllm.engine.models.BenchmarkResult
import com.jarvis.assistant.androllm.engine.models.ChatPromptMessage
import com.jarvis.assistant.androllm.engine.models.EngineCapabilities
import com.jarvis.assistant.androllm.engine.models.EngineConfig
import com.jarvis.assistant.androllm.engine.models.EngineDebugInfo
import com.jarvis.assistant.androllm.engine.models.EngineModelInfo
import com.jarvis.assistant.androllm.engine.models.EngineException
import com.jarvis.assistant.androllm.engine.models.EngineStats
import com.jarvis.assistant.androllm.engine.models.GenerationConfig
import com.jarvis.assistant.androllm.engine.models.MemoryStats
import com.jarvis.assistant.androllm.engine.models.ModelLoadConfig
import com.jarvis.assistant.androllm.engine.models.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll

/**
 * Abstraction over an on-device LLM runtime.
 *
 * The chat layer only depends on this interface. The sole production
 * implementation is [com.jarvis.assistant.androllm.engine.core.LiteRtLmEngine] (LiteRT-LM),
 * but additional runtimes can be added without touching the UI.
 */
interface InferenceEngine {

    /**
     * Static capabilities of this engine implementation.
     */
    val capabilities: EngineCapabilities

    /**
     * Result of the startup hardware probe (SoC/GPU/NPU detection). Populated
     * by [initialize] and never changes afterwards — backend selection and the
     * adaptive settings UI read from here.
     */
    val backendCapabilities: StateFlow<BackendCapabilities>

    /**
     * State of the native engine lifecycle.
     */
    val engineState: Flow<EngineState>

    /**
     * Performance stats of the last generation.
     */
    val stats: Flow<EngineStats?>

    /**
     * Live runtime memory statistics. Production engines refresh this while a
     * model is loaded; the default keeps test/fallback engines source-compatible.
     */
    val memoryStats: StateFlow<MemoryStats?>
        get() = EMPTY_MEMORY_STATS

    /**
     * Initializes the runtime (loads native libraries, resolves backends).
     */
    suspend fun initialize(config: EngineConfig): Result<Unit>

    /**
     * True when a model is currently loaded.
     */
    fun isLoaded(): Boolean

    /**
     * Returns the currently loaded model, or null.
     */
    fun getLoadedModel(): EngineModelInfo?

    /**
     * Loads a model artifact (.litertlm container) from disk.
     */
    suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo>

    /**
     * Unloads the current model and frees native memory.
     */
    suspend fun unloadModel(): Result<Unit>

    /**
     * Resets the engine's conversational state (messages + KV cache). Called on
     * conversation switch so a new conversation never continues from a stale
     * KV prefix (mirrors upstream ai_chat.cpp reset_long_term_states()).
     */
    suspend fun resetChat(): Result<Unit>

    /**
     * Streams tokens for the given prompt. The flow emits one element per token
     * and completes when generation finishes. Cancel the flow (or call [cancel])
     * to stop generation.
     */
    fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>>

    /**
     * Renders a chat history as plain text (LiteRT-LM applies the bundled chat
     * template internally, so this is used for diagnostics/logging only).
     */
    suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean = true
    ): Result<String>

    /**
     * Runs a full generation and returns the final result. Prefer [tokenStream]
     * for interactive use.
     */
    suspend fun generate(prompt: String, config: GenerationConfig): Result<String>

    /**
     * Runs a multi-turn chat generation from the FULL message history, keeping
     * the engine's conversational state (messages + KV cache) across turns —
     * the LiteRT-LM stateful conversation pattern. The engine reuses the live
     * conversation when the incoming history is a strict continuation and
     * re-seeds it whenever the history is not (edit, delete, regenerate, new
     * conversation, changed system prompt).
     *
     * The default implementation renders the prompt via [buildChatPrompt] and
     * delegates to [generate]; production engines override it with the native
     * stateful path.
     */
    suspend fun generateChat(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean = true,
        config: GenerationConfig = GenerationConfig()
    ): Result<String> = com.jarvis.assistant.androllm.engine.support.runCatching {
        val prompt = buildChatPrompt(messages, addAssistant).getOrThrow()
        generate(prompt, config).getOrThrow()
    }

    /**
     * Streaming variant of [generateChat]. Emits one element per token and
     * completes when generation finishes. Cancel the flow (or call [cancel])
     * to stop generation.
     */
    fun generateChatStream(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Flow<Result<StreamChunk>> = kotlinx.coroutines.flow.flow {
        val prompt = buildChatPrompt(messages, addAssistant).getOrNull()
        if (prompt == null) {
            emit(Result.Error(EngineException("Chat template failed")))
            return@flow
        }
        emitAll(tokenStream(prompt, config))
    }

    /**
     * Returns (and clears) the native tool calls the model emitted as
     * `<|tool_call|>` markers during the LAST chat generation. Empty when the
     * model did not call any tools (or the runtime has no marker support).
     * The chat layer reads this right after [generateChat]/[generateChatStream]
     * completes and executes the calls through the gated tool executor — the
     * same flow as a cloud provider's `tool_calls`.
     */
    fun takeLastNativeToolCalls(): List<com.jarvis.assistant.androllm.engine.core.NativeToolCall> = emptyList()

    /**
     * Requests cancellation of an in-flight generation.
     */
    fun cancel(): Result<Unit>

    /**
     * Runs a quick benchmark against the loaded model.
     */
    fun benchmark(iterations: Int): Flow<Result<BenchmarkResult>>

    /**
     * Returns full diagnostics of the loaded model and the last generation
     * (chat template, tokenizer framing, token IDs, first-token latency), or
     * null when unavailable. Used by the hidden debug panel.
     */
    suspend fun getDebugInfo(): Result<EngineDebugInfo?>

    /**
     * Releases all native resources. The engine cannot be used afterwards
     * without a new [initialize].
     */
    fun release()

    private companion object {
        val EMPTY_MEMORY_STATS = MutableStateFlow<MemoryStats?>(null)
    }
}
