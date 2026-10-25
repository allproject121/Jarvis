package com.jarvis.assistant.ai.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Smart Unified Model Router for Jarvis.
 * Dynamically routes user queries between:
 *  1. Gemini Live (Real-time voice streaming)
 *  2. ChatGPT (OpenAI GPT-4o / GPT-4o-mini API)
 *  3. Local On-Device SLM (Gemma 2B / Qwen 1.5B)
 *
 * Implements instant zero-downtime offline failover.
 */
class ModelRouter private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ModelRouter"

        @Volatile
        private var instance: ModelRouter? = null

        fun getInstance(context: Context, scope: CoroutineScope): ModelRouter {
            return instance ?: synchronized(this) {
                instance ?: ModelRouter(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val localAiEngine = LocalAiEngine.getInstance(context, scope)
    private val hermesAgentProvider = com.jarvis.assistant.ai.provider.HermesAgentProvider.getInstance(context, scope)
    private val vectorMemoryEngine = VectorMemoryEngine.getInstance(context, scope)
    private val chatGptClient = ChatGptClient()
    private val localHostClient = com.jarvis.assistant.network.LocalHostLlmClient()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _currentMode = MutableStateFlow(AiModelMode.AUTO_HYBRID)
    val currentMode: StateFlow<AiModelMode> = _currentMode.asStateFlow()

    private val _isOfflineFallbackActive = MutableStateFlow(false)
    val isOfflineFallbackActive: StateFlow<Boolean> = _isOfflineFallbackActive.asStateFlow()

    private val _lastActiveProviderName = MutableStateFlow("Auto")
    val lastActiveProviderName: StateFlow<String> = _lastActiveProviderName.asStateFlow()

    fun getLocalHostClient(): com.jarvis.assistant.network.LocalHostLlmClient = localHostClient

    fun setMode(mode: AiModelMode) {
        _currentMode.value = mode
        Log.i(TAG, "Switched AI Model Mode to: ${mode.displayName}")
    }

    /**
     * Checks whether device currently has active internet connectivity.
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Routes a conversational query to the optimal model based on mode and connectivity.
     * Injects Vector Semantic Memory into every request.
     */
    fun routeConversationalQuery(
        userPrompt: String,
        systemPrompt: String = "",
        openAiApiKey: String = "",
        chatHistory: List<ChatTurn> = emptyList()
    ): Flow<String> = flow {
        val clean = userPrompt.trim()
        if (clean.isBlank()) return@flow

        // 1. Retrieve RAG memory context dynamically
        val memoryContext = vectorMemoryEngine.buildMemoryRAGContext(clean)

        val mode = _currentMode.value
        val hasNet = isNetworkAvailable()
        val app = context.applicationContext as? com.jarvis.assistant.JarvisApp
        val endpoint = app?.preferences?.customEndpoint?.ifBlank { "http://127.0.0.1:11434" } ?: "http://127.0.0.1:11434"
        val localModel = app?.preferences?.localHostModel?.ifBlank { "qwen2.5:14b" } ?: "qwen2.5:14b"
        val geminiKey = app?.preferences?.apiKey?.trim().orEmpty()

        when (mode) {
            AiModelMode.LOCAL_HOST -> {
                Log.i(TAG, "Routing query strictly to Local Host AI ($endpoint, model=$localModel)")
                _lastActiveProviderName.value = "Local Host AI ($localModel)"
                _isOfflineFallbackActive.value = false
                localHostClient.streamChatCompletion(
                    rawEndpoint = endpoint,
                    modelName = localModel,
                    userPrompt = clean,
                    systemPrompt = systemPrompt,
                    memoryContext = memoryContext,
                    chatHistory = chatHistory
                ).catch { e ->
                    Log.w(TAG, "Local Host AI failed (${e.message}) -> Zero-delay failover to Local Offline AI")
                    triggerLocalFallback(clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                }.collect { emit(it) }
            }

            AiModelMode.LOCAL_OFFLINE -> {
                Log.i(TAG, "Routing query strictly to Local Offline AI (User Forced)")
                _lastActiveProviderName.value = "Local Offline AI"
                _isOfflineFallbackActive.value = false
                localAiEngine.generateStreaming(clean, systemPrompt, memoryContext, chatHistory).collect { emit(it) }
            }

            AiModelMode.HERMES_AGENT -> {
                Log.i(TAG, "Routing query strictly to Hermes Autonomous Agent")
                _lastActiveProviderName.value = "Hermes Agent"
                _isOfflineFallbackActive.value = false
                hermesAgentProvider.streamResponse(clean, systemPrompt, memoryContext, chatHistory).collect { chunk ->
                    if (chunk.token.isNotEmpty()) emit(chunk.token)
                }
            }

            AiModelMode.CHATGPT -> {
                if (hasNet && openAiApiKey.isNotBlank()) {
                    Log.i(TAG, "Routing query to ChatGPT (OpenAI)")
                    _lastActiveProviderName.value = "ChatGPT (GPT-4o)"
                    _isOfflineFallbackActive.value = false
                    chatGptClient.streamChatCompletion(openAiApiKey, clean, systemPrompt, memoryContext, chatHistory)
                        .catch { e ->
                            Log.w(TAG, "ChatGPT failed: ${e.message}. Executing auto-fallback to Local Host or Offline.")
                            tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                        }
                        .collect { emit(it) }
                } else {
                    Log.w(TAG, "ChatGPT unavailable (hasNet=$hasNet, hasKey=${openAiApiKey.isNotBlank()}). Triggering Local Host / Offline.")
                    tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                }
            }

            AiModelMode.GEMINI_LIVE -> {
                if (hasNet && geminiKey.isNotBlank()) {
                    Log.i(TAG, "Routing text chat to Gemini REST Core")
                    _lastActiveProviderName.value = "Gemini Flash (Online)"
                    _isOfflineFallbackActive.value = false
                    try {
                        val fullSysPrompt = (systemPrompt.ifBlank { "You are JARVIS, Tony Stark's sophisticated AI operating system." }) +
                            (if (memoryContext.isNotBlank()) "\n\n$memoryContext" else "")
                        val responseText = com.jarvis.assistant.ai.AiTextSynthesizer.synthesizeText(
                            context = context,
                            systemInstruction = fullSysPrompt,
                            prompt = clean
                        ) {
                            "Sir, external Gemini telemetry returned empty. Falling back to local neural core."
                        }
                        emit(responseText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Gemini online text error: ${e.message} -> Falling back")
                        tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                    }
                } else if (!hasNet) {
                    tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                } else {
                    _lastActiveProviderName.value = "Gemini Live"
                    _isOfflineFallbackActive.value = false
                    emit("Sir, Gemini API Key is not configured in Settings. Please set your Gemini API Key or switch to Local Offline mode.")
                }
            }

            AiModelMode.AUTO_HYBRID -> {
                if (hasNet && openAiApiKey.isNotBlank()) {
                    Log.i(TAG, "AUTO_HYBRID: Online network active -> Routing to Cloud ChatGPT")
                    _lastActiveProviderName.value = "ChatGPT (Online)"
                    _isOfflineFallbackActive.value = false
                    chatGptClient.streamChatCompletion(openAiApiKey, clean, systemPrompt, memoryContext, chatHistory)
                        .catch { e ->
                            Log.w(TAG, "Online request failed (${e.message}) -> Zero-delay failover to Local Host / Offline")
                            tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                        }
                        .collect { emit(it) }
                } else if (hasNet && geminiKey.isNotBlank()) {
                    Log.i(TAG, "AUTO_HYBRID: Online network active -> Routing to Gemini Online")
                    _lastActiveProviderName.value = "Gemini Flash (Online)"
                    _isOfflineFallbackActive.value = false
                    try {
                        val fullSysPrompt = (systemPrompt.ifBlank { "You are JARVIS, Tony Stark's sophisticated AI operating system." }) +
                            (if (memoryContext.isNotBlank()) "\n\n$memoryContext" else "")
                        val responseText = com.jarvis.assistant.ai.AiTextSynthesizer.synthesizeText(
                            context = context,
                            systemInstruction = fullSysPrompt,
                            prompt = clean
                        ) {
                            "Sir, external telemetry offline."
                        }
                        emit(responseText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Gemini synthesis failed: ${e.message} -> Falling back")
                        tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                    }
                } else {
                    Log.i(TAG, "AUTO_HYBRID: Offline or no cloud key -> Engaging Local Host or Offline AI")
                    tryLocalHostOrOffline(endpoint, localModel, clean, systemPrompt, memoryContext, chatHistory) { emit(it) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun tryLocalHostOrOffline(
        endpoint: String,
        model: String,
        userPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        emitBlock: suspend (String) -> Unit
    ) {
        try {
            _lastActiveProviderName.value = "Local Host AI ($model)"
            _isOfflineFallbackActive.value = false
            var receivedAnyChunk = false
            localHostClient.streamChatCompletion(
                rawEndpoint = endpoint,
                modelName = model,
                userPrompt = userPrompt,
                systemPrompt = systemPrompt,
                memoryContext = memoryContext,
                chatHistory = chatHistory
            ).catch { e ->
                Log.w(TAG, "Local Host unreachable: ${e.message} -> Falling back to on-device offline tools")
                triggerLocalFallback(userPrompt, systemPrompt, memoryContext, chatHistory, emitBlock)
            }.collect { chunk ->
                receivedAnyChunk = true
                emitBlock(chunk)
            }
            if (!receivedAnyChunk) {
                triggerLocalFallback(userPrompt, systemPrompt, memoryContext, chatHistory, emitBlock)
            }
        } catch (e: Exception) {
            triggerLocalFallback(userPrompt, systemPrompt, memoryContext, chatHistory, emitBlock)
        }
    }

    private suspend fun triggerLocalFallback(
        userPrompt: String,
        systemPrompt: String,
        memoryContext: String,
        chatHistory: List<ChatTurn>,
        emitBlock: suspend (String) -> Unit
    ) {
        _isOfflineFallbackActive.value = true
        _lastActiveProviderName.value = "Local Offline AI (Fallback)"
        localAiEngine.generateStreaming(userPrompt, systemPrompt, memoryContext, chatHistory)
            .collect { emitBlock(it) }
    }
}
