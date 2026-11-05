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

        /** Tool calls the brain may chain for one request (e.g. find contact -> send message). */
        private const val MAX_AGENT_STEPS = 4

        /** Tool result text fed back to the model (keeps follow-up steps cheap to read). */
        private const val MAX_TOOL_RESULT_CHARS = 700

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

        // Make sure the model is loaded (reload after the user picked another model).
        val ready = generationMutex.withLock {
            if (nativeModel == null || loadedModelId != modelManager.resolveUsableModelId()) loadActiveModel() else true
        }
        if (!ready || nativeModel == null) {
            emit(AiStreamChunk(token = "Sir, the offline neural model is not ready. Please download the weights in Settings.", isComplete = true))
            return@flow
        }

        toolDispatcher.startNewTurn(java.util.UUID.randomUUID().toString())
        val agentPipelineOn = com.jarvis.assistant.androllm.agent.integration.AgentPipeline.isEnabled(context)
        if (agentPipelineOn) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.beginTurn(context, cleanPrompt)
        }

        val activeId = loadedModelId.ifBlank { modelManager.activeModelId.value }
        val modelFamily = com.jarvis.assistant.ai.compat.ModelFamily.resolveFromModelId(activeId)
        // Feeding tool results back needs the ChatML tool_response turn, which only Qwen models know.
        val loopCapable = modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.QWEN2P5 ||
            modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.QWEN2 ||
            modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.QWEN3

        // Tools: the constant everyday set (cached prompt prefix) + up to 6 picked for THIS request
        // from all ~280 Jarvis tools.
        val coreTools = availableTools.ifEmpty { toolDispatcher.getOfflineToolSchemas() }
        val extraTools = if (availableTools.isNotEmpty()) emptyList() else runCatching {
            val coreNames = coreTools.map { it.optJSONObject("function")?.optString("name").orEmpty() }.toSet()
            com.jarvis.assistant.ai.local.OfflineToolRouter.select(
                cleanPrompt, toolDispatcher.getAvailableToolSchemas(includeDisabled = false), max = 6, exclude = coreNames
            )
        }.getOrDefault(emptyList())
        if (extraTools.isNotEmpty()) {
            Log.i(TAG, "Offline tools for this request: " + extraTools.joinToString { it.optJSONObject("function")?.optString("name").orEmpty() })
        }
        val basePrompt = buildPromptWithinContext(modelFamily, cleanPrompt, systemPrompt, memoryContext, chatHistory, coreTools, extraTools)

        val budgetChars = (currentConfig.contextWindowLimit - currentConfig.maxTokens - 64).coerceAtLeast(256) * 3
        val transcript = StringBuilder()   // agent steps appended after basePrompt
        var emittedAny = false
        var steps = 0
        var lastToolMessage: String? = null

        val telemetry = com.jarvis.assistant.telemetry.TelemetryRepository.getInstance(context)
        try {
            while (true) {
                val stepPrompt = basePrompt + transcript
                Log.i(TAG, "Offline step ${steps + 1} (family=${modelFamily.displayName}) prompt length: ${stepPrompt.length}")

                // ---- one generation step (holds the model lock only while generating) ----
                val prefill = if (modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.DEEPSEEK && steps == 0) {
                    com.jarvis.assistant.ai.compat.ChatTemplateRenderer.DEEPSEEK_THINK_PREFILL
                } else ""
                val acc = StringBuilder(prefill)
                var stepVisible = ""
                var stepTool: AiToolCall? = null
                val sepForStep = if (emittedAny) "\n" else ""

                suspend fun emitVisible(final: Boolean) {
                    val visible = ToolCallParser.streamVisibleText(acc.toString(), final)
                    if (visible.length > stepVisible.length && visible.startsWith(stepVisible)) {
                        val delta = visible.substring(stepVisible.length)
                        emit(AiStreamChunk(token = (if (stepVisible.isEmpty()) sepForStep else "") + delta, isComplete = false))
                        stepVisible = visible
                        emittedAny = true
                    }
                }

                val params = InferenceParameters.of(stepPrompt)
                    .withCachePrompt(true)
                    .let { if (slotsEnabled) it.withSlotId(SLOT_CHAT) else it }
                    .withTemperature(currentConfig.temperature)
                    .withTopK(currentConfig.topK)
                    .withTopP(currentConfig.topP)
                    .withNPredict(
                        if (modelFamily == com.jarvis.assistant.ai.compat.ModelFamily.DEEPSEEK) maxOf(currentConfig.maxTokens, 1024)
                        else currentConfig.maxTokens
                    )
                    .withStopStrings(*(modelFamily.stopTokens.toTypedArray()))

                telemetry.beginGeneration()
                generationMutex.lock()
                try {
                    val instance = nativeModel ?: break
                    JarvisThreadManager.withBackgroundInferencePriority {
                        // Closing the iterable cancels the native task when we stop early.
                        instance.generate(params).use { iterable ->
                            for (output in iterable) {
                                val token = output.text ?: continue
                                telemetry.onToken()
                                acc.append(token)
                                val raw = acc.toString()
                                val leakAt = ToolCallParser.findTurnLeak(raw)
                                if (leakAt >= 0) {
                                    Log.w(TAG, "Offline model leaked a turn marker at $leakAt; stopping generation")
                                    acc.setLength(leakAt)
                                    break
                                }
                                if (ToolCallParser.stripThinking(raw).contains("<tool_call>")) {
                                    val candidate = ToolCallParser.parseToolCall(raw)
                                    if (candidate != null) { stepTool = candidate; break }
                                    continue
                                }
                                emitVisible(final = false)
                                if (output.stop) break
                            }
                        }
                    }
                } finally {
                    generationMutex.unlock()
                    telemetry.endGeneration(activeId)
                }
                if (stepTool == null) stepTool = ToolCallParser.parseToolCall(acc.toString())
                emitVisible(final = true)
                Log.i(TAG, "Offline step ${steps + 1} reply: ${stepVisible.take(160)}" + (stepTool?.let { " | tool ${it.functionName} ${it.arguments}" } ?: ""))

                // ---- tool step (lock released: a tool may itself ask the brain) ----
                val tool = stepTool ?: break
                emit(AiStreamChunk(token = "", isComplete = false, toolCallDetected = tool))
                val result = runOfflineTool(tool, agentPipelineOn)
                val message = result.resultJson.optString("message", if (result.isSuccess) "Action completed." else "Action failed.")
                lastToolMessage = message
                steps++

                // Feed the result back so the model can take the next step or answer naturally.
                val call = JSONObject().put("name", tool.functionName).put("arguments", tool.arguments)
                val nextTranscript = StringBuilder(transcript)
                    .append(stepVisible).append(if (stepVisible.isBlank()) "" else "\n")
                    .append("<tool_call>\n").append(call.toString()).append("\n</tool_call><|im_end|>\n")
                    .append("<|im_start|>user\n<tool_response>\n").append(result.resultJson.toString().take(MAX_TOOL_RESULT_CHARS))
                    .append("\n</tool_response><|im_end|>\n<|im_start|>assistant\n")
                if (!loopCapable || steps >= MAX_AGENT_STEPS || (basePrompt.length + nextTranscript.length) > budgetChars) {
                    emit(AiStreamChunk(token = (if (emittedAny) "\n" else "") + message, isComplete = false))
                    emittedAny = true
                    break
                }
                transcript.setLength(0)
                transcript.append(nextTranscript)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Offline generation cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during native llama token generation: ${e.message}", e)
            emit(AiStreamChunk(token = " Sir, offline core error: ${e.message}", isComplete = false))
            emittedAny = true
        }
        // The model went quiet after a tool ran: say what the tool reported.
        if (!emittedAny && lastToolMessage != null) emit(AiStreamChunk(token = lastToolMessage, isComplete = false))
        emit(AiStreamChunk(token = "", isComplete = true))
    }.flowOn(Dispatchers.IO)

    /** Runs a tool the offline model asked for. */
    private suspend fun runOfflineTool(tool: AiToolCall, agentPipelineOn: Boolean): AiToolResult {
        val toolResult = if (agentPipelineOn) {
            com.jarvis.assistant.androllm.agent.integration.AgentPipeline.dispatch(context, tool)
        } else {
            toolDispatcher.dispatchTool(tool)
        }
        Log.i(TAG, "Offline tool ${tool.functionName} finished: isSuccess=${toolResult.isSuccess} ${toolResult.resultJson.toString().take(200)}")
        return toolResult
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
        tools: List<JSONObject>,
        extraTools: List<JSONObject> = emptyList()
    ): String {
        // ~3 characters per token is a conservative estimate for English/Hinglish with these tokenizers.
        val budgetTokens = (currentConfig.contextWindowLimit - currentConfig.maxTokens - 64).coerceAtLeast(256)
        val budgetChars = budgetTokens * 3
        // The everyday tools stay first and unchanged (cached prefix); trimming drops the per-request
        // extras, memory and history before it ever drops them.
        data class Attempt(val tools: List<JSONObject>, val extra: List<JSONObject>, val memoryChars: Int, val historyTurns: Int)
        val attempts = listOf(
            Attempt(tools, extraTools, Int.MAX_VALUE, chatHistory.size),
            Attempt(tools, extraTools.take(3), 1500, 4),
            Attempt(tools, emptyList(), 600, 2),
            Attempt(tools, emptyList(), 0, 0),
            Attempt(emptyList(), emptyList(), 0, 0)
        )
        var prompt = ""
        for (attempt in attempts) {
            prompt = com.jarvis.assistant.ai.compat.ChatTemplateRenderer.render(
                family = family,
                cleanPrompt = cleanPrompt,
                systemPrompt = systemPrompt,
                memoryContext = memoryContext.take(attempt.memoryChars),
                chatHistory = chatHistory.takeLast(attempt.historyTurns),
                tools = attempt.tools,
                extraTools = attempt.extra
            )
            if (prompt.length <= budgetChars) break
            Log.w(TAG, "Prompt ${prompt.length} chars exceeds ~$budgetChars for ${currentConfig.contextWindowLimit}-token context; trimming")
        }
        return prompt
    }
}
