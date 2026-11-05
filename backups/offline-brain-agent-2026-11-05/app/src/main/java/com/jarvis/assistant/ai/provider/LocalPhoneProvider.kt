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
import kotlinx.coroutines.launch
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

        /**
         * llama.cpp keeps one prompt cache per slot. Conversation turns share a long constant prefix
         * (tool list + rules, ~70% of the prompt), so a cached prefix makes a reply ~10 s instead of
         * ~3 min on a phone CPU. Background prompts (memory extraction, warm-up probes) used to run
         * in the same slot and wipe that cache, so they get their own slot.
         */
        private const val SLOT_CHAT = 0
        private const val SLOT_QUIET = 1

        /** Extra KV cells (shared, unified cache) for the background slot's short prompts. */
        private const val QUIET_SLOT_CTX = 1024

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

    /**
     * One llama.cpp context serves every caller (chat, offline voice, memory extraction). Runs used
     * to overlap: the voice engine started a new run per utterance while older ones were still going,
     * so all of them crawled (100 s+ replies), and a model switch closed the model under a running
     * request ("Model is not loaded"). Every run and every (re)load now holds this lock.
     */
    private val generationMutex = Mutex()

    // B4 fix: active inference configuration (threads, sampling, token & context limits)
    @Volatile
    private var currentConfig: LocalAiConfig = LocalAiConfig()

    /** Applies LocalAiConfig settings to subsequent inference runs. */
    fun configure(config: LocalAiConfig) {
        currentConfig = config
    }

    @Volatile
    private var nativeModel: LlamaModel? = null

    /** True when the loaded model has the separate chat / background slots. */
    @Volatile
    private var slotsEnabled = false
    @Volatile
    private var loadedModelId: String = ""

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        generationMutex.withLock { loadActiveModel() }
    }

    /** Loads the usable model; caller must hold [generationMutex]. */
    private suspend fun loadActiveModel(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val selectedId = modelManager.activeModelId.value
            val activeId = modelManager.resolveUsableModelId()
            if (activeId == null) {
                Log.w(TAG, "No offline model is downloaded (selected '$selectedId' missing at ${modelManager.getModelFile(selectedId).absolutePath})")
                return@withLock false
            }
            if (activeId != selectedId) {
                Log.w(TAG, "Selected offline model '$selectedId' is not downloaded; using '$activeId' instead")
            }
            if (nativeModel != null && loadedModelId == activeId) {
                Log.i(TAG, "Local phone provider already initialized with model: $loadedModelId")
                return@withLock true
            }

            val modelFile = modelManager.getModelFile(activeId)

            try {
                Log.i(TAG, "Loading native GGUF model into memory from ${modelFile.absolutePath}...")
                nativeModel?.close()
                nativeModel = null

                // Prompt reading is parallel work: let it use every fast core (all but the 3 little ones).
                val batchThreads = maxOf(currentConfig.threads, JarvisThreadManager.hardwareCores() - 3)
                val loaded = try {
                    LlamaModel(
                        ModelParameters()
                            .setModel(modelFile.absolutePath)
                            .setThreads(currentConfig.threads)
                            .setThreadsBatch(batchThreads)
                            .setParallel(2)
                            .setKvUnified(true)
                            .setCtxSize(currentConfig.contextWindowLimit + QUIET_SLOT_CTX)
                    ).also { slotsEnabled = true }
                } catch (e: Throwable) {
                    // Older/other llama builds: fall back to the original single-slot setup.
                    Log.w(TAG, "Two-slot llama setup failed (${e.message}); using a single slot")
                    slotsEnabled = false
                    LlamaModel(
                        ModelParameters()
                            .setModel(modelFile.absolutePath)
                            .setThreads(currentConfig.threads)
                            .setCtxSize(currentConfig.contextWindowLimit)
                    )
                }
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

    @Volatile
    private var warmUpJob: kotlinx.coroutines.Job? = null

    /**
     * Loads the GGUF model and runs one token in the background so its weights are paged into RAM
     * before the user's first question. On a phone short of free RAM that first question otherwise
     * waited 1-3 minutes while the 2.4 GB model was read back from storage. Never blocks a real
     * request: the token run uses tryLock and is skipped when a reply is already being generated.
     */
    fun warmUpAsync() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = scope.launch(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                if (initialize() && primeChatSlot()) {
                    Log.i(TAG, "Offline brain warmed up in ${System.currentTimeMillis() - started} ms")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Offline brain warm-up failed: ${e.message}")
            }
        }
    }

    /**
     * Reads the constant part of a conversation prompt (tool list + rules) into the chat slot's
     * cache and generates one token, so the user's first real question only reads its own words.
     * Skipped (returns false) when a real reply is already running.
     */
    /**
     * Diagnostics: measures prompt-reading speed and reply-writing speed separately on the background
     * slot (the chat cache is untouched). Used from the debug test hook to find where time goes.
     */
    suspend fun benchmark(): String = withContext(Dispatchers.IO) {
        if (!initialize()) return@withContext "model not loaded"
        generationMutex.withLock {
            val instance = nativeModel ?: return@withLock "model not loaded"
            val family = com.jarvis.assistant.ai.compat.ModelFamily.resolveFromModelId(loadedModelId)
            fun run(prompt: String, nPredict: Int): Pair<Long, Int> {
                val params = InferenceParameters.of(prompt)
                    .withCachePrompt(false)
                    .let { if (slotsEnabled) it.withSlotId(SLOT_QUIET) else it }
                    .withNPredict(nPredict)
                    .withTemperature(0.7f)
                val t0 = System.nanoTime()
                var n = 0
                JarvisThreadManager.withBackgroundInferencePriority {
                    instance.generate(params).use { it.forEach { o -> n++; if (o.stop) return@forEach } }
                }
                return (System.nanoTime() - t0) / 1_000_000 to n
            }
            val filler = (1..60).joinToString(" ") { "The quick brown fox number $it jumps over the lazy dog." }
            val longPrompt = "<|im_start|>user\n$filler\nSay ok.<|im_end|>\n<|im_start|>assistant\n"
            val (readMs, _) = run(longPrompt, 1)
            val (writeMs, written) = run("<|im_start|>user\nCount from 1 to 60 in words.<|im_end|>\n<|im_start|>assistant\n", 64)
            val promptTokens = 60 * 13
            "threads=${currentConfig.threads} batchThreads=${maxOf(currentConfig.threads, JarvisThreadManager.hardwareCores() - 3)} " +
                "slots=$slotsEnabled ctx=${currentConfig.contextWindowLimit} family=${family.displayName} | " +
                "read ~$promptTokens tok in $readMs ms = ${promptTokens * 1000L / readMs.coerceAtLeast(1)} tok/s | " +
                "write $written tok in $writeMs ms = ${written * 1000L / writeMs.coerceAtLeast(1)} tok/s"
        }
    }

    private suspend fun primeChatSlot(): Boolean {
        if (!generationMutex.tryLock()) return false
        try {
            val instance = nativeModel ?: return false
            val family = com.jarvis.assistant.ai.compat.ModelFamily.resolveFromModelId(loadedModelId)
            val prompt = buildPromptWithinContext(
                family, "hi", "", "", emptyList(), toolDispatcher.getOfflineToolSchemas()
            )
            val params = InferenceParameters.of(prompt)
                .withCachePrompt(true)
                .let { if (slotsEnabled) it.withSlotId(SLOT_CHAT) else it }
                .withNPredict(1)
                .withStopStrings(*(family.stopTokens.toTypedArray()))
            JarvisThreadManager.withBackgroundInferencePriority {
                instance.generate(params).use { iterable -> for (output in iterable) { if (output.stop) break } }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Chat slot priming failed: ${e.message}")
            return false
        } finally {
            generationMutex.unlock()
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
            if (modelManager.resolveUsableModelId() == null) {
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

        // A tool the model asked for runs AFTER the lock is released (a tool may itself ask the brain).
        var deferredTool: AiToolCall? = null
        var deferredSep = ""
        var deferredPipeline = false
        generationMutex.lock()
        try {
        val model = nativeModel
        if (model == null || loadedModelId != modelManager.resolveUsableModelId()) {
            // Auto-warm if weights exist (or reload after the user picked another model)
            val initialized = loadActiveModel()
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
            .withCachePrompt(true)
            .let { if (slotsEnabled) it.withSlotId(SLOT_CHAT) else it }
            .withTemperature(currentConfig.temperature)
            .withTopK(currentConfig.topK)
            .withTopP(currentConfig.topP)
            // A reasoning model spends most tokens inside <think>; give it room to reach the answer.
            .withNPredict(
                if (modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.DEEPSEEK) maxOf(currentConfig.maxTokens, 1024)
                else currentConfig.maxTokens
            )
            .withStopStrings(*(modelFamily.stopTokens.toTypedArray()))

        // DeepSeek's prompt already opens the reasoning block; seed the buffer with it so the
        // whole block is recognised and stripped.
        val prefill = if (modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.DEEPSEEK) {
            com.jarvis.assistant.ai.compat.ChatTemplateRenderer.DEEPSEEK_THINK_PREFILL
        } else ""
        val tokenAccumulator = StringBuilder(prefill)
        var hasDispatchedTool = false
        var emittedVisible = ""

        // Emits only the newly visible part of the reply (no thinking, no tool JSON, no leaked turn tags).
        suspend fun emitVisible(final: Boolean) {
            val visible = ToolCallParser.streamVisibleText(tokenAccumulator.toString(), final)
            if (visible.length > emittedVisible.length && visible.startsWith(emittedVisible)) {
                emit(AiStreamChunk(token = visible.substring(emittedVisible.length), isComplete = false))
                emittedVisible = visible
            }
        }

        suspend fun runTool(tool: AiToolCall, whenFound: String) {
            hasDispatchedTool = true
            Log.i(TAG, "Offline LLM triggered tool call $whenFound: ${tool.functionName} ${tool.arguments}")
            emitVisible(final = true)
            deferredTool = tool
            deferredSep = if (emittedVisible.isBlank()) "" else "\n"
            deferredPipeline = agentPipelineOn
        }

        val telemetry = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(context)
        telemetry.beginGeneration()
        try {
            var pendingTool: AiToolCall? = null
            JarvisThreadManager.withBackgroundInferencePriority {
                // Closing the iterable cancels the native task, so breaking out early (tool call,
                // leaked fake turn) never leaves llama.cpp generating in the background.
                activeInstance.generate(params).use { iterable ->
                    for (output in iterable) {
                        val token = output.text ?: continue
                        telemetry.onToken()
                        tokenAccumulator.append(token)
                        val raw = tokenAccumulator.toString()

                        // The model started writing the next turn itself: keep only what came before.
                        val leakAt = ToolCallParser.findTurnLeak(raw)
                        if (leakAt >= 0) {
                            Log.w(TAG, "Offline model leaked a turn marker at $leakAt; stopping generation")
                            tokenAccumulator.setLength(leakAt)
                            break
                        }

                        if (ToolCallParser.stripThinking(raw).contains("<tool_call>")) {
                            val candidate = ToolCallParser.parseToolCall(raw)
                            if (candidate != null) {
                                pendingTool = candidate
                                break
                            }
                            // Incomplete structured request: keep accumulating without emitting raw JSON
                            continue
                        }
                        emitVisible(final = false)
                        if (output.stop) break
                    }
                }
            }
            // Flow emissions happen outside the native-priority block.
            pendingTool?.let { runTool(it, "during stream") }

            // Stream ended (e.g. on the "</tool_call>" stop word, or a bare JSON call): parse once more.
            if (!hasDispatchedTool) {
                ToolCallParser.parseToolCall(tokenAccumulator.toString())?.let { runTool(it, "at end of stream") }
            }
            if (!hasDispatchedTool) emitVisible(final = true)
            Log.i(TAG, "Offline reply (${tokenAccumulator.length} raw chars): ${emittedVisible.take(200)}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A newer request replaced this one; closing the iterable already stopped llama.cpp.
            Log.i(TAG, "Offline generation cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during native llama token generation: ${e.message}", e)
            emit(AiStreamChunk(token = " Sir, offline core error: ${e.message}", isComplete = false))
        } finally {
            telemetry.endGeneration(activeId)
        }
        } finally {
            generationMutex.unlock()
        }
        deferredTool?.let { tool ->
            emit(AiStreamChunk(token = "", isComplete = false, toolCallDetected = tool))
            val cleanMessage = runOfflineTool(tool, deferredPipeline)
            emit(AiStreamChunk(token = deferredSep + cleanMessage, isComplete = false))
        }
        emit(AiStreamChunk(token = "", isComplete = true))
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
        // Background work never waits behind (or slows down) a reply the user is waiting for.
        if (!generationMutex.tryLock()) return@withContext null
        try {
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
            .let { if (slotsEnabled) it.withSlotId(SLOT_QUIET) else it }
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
        } finally {
            generationMutex.unlock()
        }
    }

    override suspend fun executeToolCall(toolCall: AiToolCall): AiToolResult {
        return if (com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, toolCall)
        } else {
            toolDispatcher.dispatchTool(toolCall)
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        generationMutex.withLock {
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
