package com.jarvis.assistant.ai.router

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.provider.HermesAgentProvider
import com.jarvis.assistant.network.LocalHostLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext

/**
 * Registry of every AI brain that lives inside JARVIS.
 *
 * JARVIS used to be a single-brain system: the live session was hard-wired to
 * Gemini and the model had no way of knowing that a local Ollama server, an
 * OpenAI key, an on-device SLM and the Hermes planner were all sitting right
 * there in the app. This service makes those brains discoverable and lets the
 * live assistant hand a task to any of them.
 */
object AiBrainService {

    private const val TAG = "AiBrainService"

    const val KEY_GEMINI = "gemini"
    const val KEY_CHATGPT = "chatgpt"
    const val KEY_LOCAL_HOST = "local_host"
    const val KEY_LOCAL_OFFLINE = "local_offline"
    const val KEY_HERMES = "hermes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localHostClient = LocalHostLlmClient()

    data class BrainInfo(
        val key: String,
        val displayName: String,
        val role: String,
        val available: Boolean,
        val status: String
    )

    private fun app(context: Context): JarvisApp? = context.applicationContext as? JarvisApp

    private fun endpoint(context: Context): String {
        val prefs = app(context)?.preferences
        return prefs?.customEndpoint?.ifBlank { ModelRouter.DEFAULT_LOCAL_ENDPOINT } ?: ModelRouter.DEFAULT_LOCAL_ENDPOINT
    }

    private fun localModel(context: Context): String {
        val prefs = app(context)?.preferences
        return prefs?.localHostModel?.ifBlank { ModelRouter.DEFAULT_LOCAL_MODEL } ?: ModelRouter.DEFAULT_LOCAL_MODEL
    }

    /**
     * Inspects every brain and reports whether it is usable right now.
     */
    suspend fun listBrains(context: Context): List<BrainInfo> = withContext(Dispatchers.IO) {
        val brains = mutableListOf<BrainInfo>()

        brains += BrainInfo(
            key = KEY_GEMINI,
            displayName = "Gemini Live",
            role = "Primary conversational brain — real-time voice, vision grounding and native tools.",
            available = true,
            status = "Active — this conversation is running on it."
        )

        val openAiKey = app(context)?.preferences?.openAiApiKey?.trim().orEmpty()
        val hasNet = app(context)?.modelRouter?.isNetworkAvailable() ?: false
        brains += BrainInfo(
            key = KEY_CHATGPT,
            displayName = "ChatGPT (OpenAI GPT-4o)",
            role = "Deep reasoning, drafting and structured problem solving.",
            available = openAiKey.isNotBlank() && hasNet,
            status = when {
                openAiKey.isBlank() -> "No OpenAI API key configured."
                !hasNet -> "Offline — no internet connection."
                else -> "Ready — GPT-4o is configured."
            }
        )

        val hostInfo = runCatching { localHostClient.pingServer(endpoint(context)) }.getOrNull()
        val hostModels = hostInfo?.availableModels.orEmpty()
        brains += BrainInfo(
            key = KEY_LOCAL_HOST,
            displayName = "Local Host AI (Ollama on your PC)",
            role = "High-performance private inference running on your own machine over ADB reverse or Wi-Fi.",
            available = hostInfo?.isOnline == true,
            status = when {
                hostInfo == null -> "Could not reach the local host."
                hostInfo.isOnline && hostModels.isNotEmpty() ->
                    "Online at ${endpoint(context)} — models: ${hostModels.joinToString(", ")}"
                hostInfo.isOnline -> "Online at ${endpoint(context)} — no models reported by the server."
                else -> "Offline — ${endpoint(context)} not reachable. Is Ollama running?"
            }
        )

        val localEngineState = runCatching {
            LocalAiEngine.getInstance(context, scope).engineState.value
        }.getOrNull()
        brains += BrainInfo(
            key = KEY_LOCAL_OFFLINE,
            displayName = "Local Offline AI (on-device SLM)",
            role = "Complete privacy — runs entirely on the phone with no network.",
            available = localEngineState == LocalEngineState.Ready,
            status = when {
                localEngineState == LocalEngineState.Ready -> "Ready — model loaded on device."
                localEngineState == LocalEngineState.Loading -> "Loading the on-device model…"
                localEngineState == LocalEngineState.Generating -> "Ready — currently generating."
                localEngineState is LocalEngineState.Error -> "Error — ${localEngineState.message}"
                else -> "Not initialised yet."
            }
        )

        val hermesReady = runCatching {
            HermesAgentProvider.getInstance(context, scope).isReady()
        }.getOrDefault(false)
        brains += BrainInfo(
            key = KEY_HERMES,
            displayName = "Hermes Autonomous Agent",
            role = "Multi-step autonomous planning and on-device tool execution.",
            available = hermesReady,
            status = if (hermesReady) "Ready." else "On-device tool provider is not initialised."
        )

        brains
    }

    /**
     * Hands a task to the requested brain and returns its answer.
     *
     * When [brainKey] is blank the user's pinned preference is used, so
     * "ab se sab kuch local model se karo" actually sticks.
     *
     * Returns a human-readable status string on failure so the caller can
     * report it verbatim instead of throwing.
     */
    suspend fun delegate(context: Context, brainKey: String, task: String): String = withContext(Dispatchers.IO) {
        val cleanTask = task.trim()
        if (cleanTask.isBlank()) return@withContext "No task was provided."

        val prefs = app(context)?.preferences
        val requested = brainKey.trim()
        val key = (requested.ifBlank { prefs?.preferredBrain.orEmpty() })
            .trim().lowercase().replace(Regex("[^a-z_]"), "")
        val memoryContext = runCatching {
            app(context)?.vectorMemoryEngine?.buildMemoryRAGContext(cleanTask).orEmpty()
        }.getOrDefault("")

        return@withContext try {
            when (key) {
                KEY_LOCAL_HOST -> {
                    val info = localHostClient.pingServer(endpoint(context))
                    if (!info.isOnline) {
                        "Local Host AI is offline (${endpoint(context)}). Is Ollama running on your PC?"
                    } else {
                        val model = localModel(context)
                        val answer = localHostClient.generateChatCompletion(
                            rawEndpoint = endpoint(context),
                            modelName = model,
                            userPrompt = cleanTask,
                            systemPrompt = "You are JARVIS's local neural core. Answer directly and concisely.",
                            memoryContext = memoryContext
                        )
                        "[Local Host AI · $model]\n$answer"
                    }
                }

                KEY_CHATGPT -> {
                    val apiKey = prefs?.openAiApiKey?.trim().orEmpty()
                    if (apiKey.isBlank()) {
                        "ChatGPT is not configured — no OpenAI API key is set."
                    } else {
                        val answer = ChatGptClient().directChatCompletion(
                            apiKey = apiKey,
                            userPrompt = cleanTask,
                            systemPrompt = "You are JARVIS's cloud reasoning core. Answer directly and concisely.",
                            memoryContext = memoryContext
                        )
                        "[ChatGPT · GPT-4o]\n$answer"
                    }
                }

                KEY_LOCAL_OFFLINE -> {
                    val answer = LocalAiEngine.getInstance(context, scope)
                        .generateStreaming(
                            userPrompt = cleanTask,
                            systemPrompt = "You are JARVIS's on-device core. Answer directly and concisely.",
                            memoryContext = memoryContext
                        )
                        .last()
                    "[Local Offline AI]\n$answer"
                }

                KEY_HERMES -> {
                    val answer = HermesAgentProvider.getInstance(context, scope)
                        .streamResponse(
                            prompt = cleanTask,
                            systemPrompt = "You are JARVIS's autonomous planner. Execute the task and report the result.",
                            memoryContext = memoryContext,
                            chatHistory = emptyList(),
                            availableTools = emptyList()
                        )
                        .last()
                        .token
                    "[Hermes Agent]\n$answer"
                }

                KEY_GEMINI -> {
                    "Gemini Live is already handling this conversation — you are speaking to it directly."
                }

                else ->
                    "Unknown brain '$brainKey'. Available brains: ${listBrains(context).joinToString(", ") { it.key }}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delegation to $key failed", e)
            "The $key brain could not complete the task: ${e.message}"
        }
    }

    /**
     * Pins JARVIS to a specific core for delegated work.
     *
     * Returns a status message describing the result, including whether the
     * chosen core is actually usable right now.
     */
    suspend fun switchBrain(context: Context, brainKey: String): String = withContext(Dispatchers.IO) {
        val key = brainKey.trim().lowercase().replace(Regex("[^a-z_]"), "")
        if (key.isBlank()) {
            app(context)?.preferences?.preferredBrain = ""
            return@withContext "Cleared — JARVIS will keep using Gemini Live for conversation and delegate only when asked."
        }
        val info = listBrains(context).firstOrNull { it.key == key }
            ?: return@withContext "Unknown core '$brainKey'. Available: ${listBrains(context).joinToString(", ") { it.key }}"
        if (!info.available) {
            return@withContext "${info.displayName} is currently UNAVAILABLE — ${info.status}"
        }
        app(context)?.preferences?.preferredBrain = key
        "Switched. ${info.displayName} is now JARVIS's preferred core for delegated work. ${info.status}"
    }

    /**
     * Renders the brain list as a compact block the live assistant can read.
     */
    suspend fun describeBrains(context: Context): String {
        val brains = listBrains(context)
        val sb = StringBuilder()
        sb.append("JARVIS AI CORES:\n")
        for (brain in brains) {
            sb.append("- ").append(brain.key).append(" | ").append(brain.displayName)
            if (brain.available) sb.append(" | READY") else sb.append(" | UNAVAILABLE")
            sb.append(" | ").append(brain.status).append("\n")
        }
        return sb.toString().trim()
    }
}
