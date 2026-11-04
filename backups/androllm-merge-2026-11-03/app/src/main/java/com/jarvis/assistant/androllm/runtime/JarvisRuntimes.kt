package com.jarvis.assistant.androllm.runtime

import android.content.Context
import android.provider.Settings
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.memory.TextEmbeddingEngine
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.AndroLlmPaths
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.cloud.AndroLlmCloud
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.memory.store.MemoryMetaRegistry
import com.jarvis.assistant.androllm.voice.SherpaVoiceController
import com.jarvis.assistant.mcp.McpManager

/**
 * Read-only [Runtime] adapters for Jarvis' subsystems (the upstream
 * CloudRuntime / McpRuntime / AutomationRuntime / ... equivalents). Every
 * status is a cheap snapshot of state the subsystem already exposes: no model
 * is loaded, no microphone opened, no network call made, and Gemini Live is
 * only described (its API-key presence), never touched.
 */
object JarvisRuntimes {

    fun all(context: Context): List<Runtime> = listOf(
        GeminiLiveRuntime(context),
        GgufRuntime(context),
        LiteRtRuntime(context),
        CloudGatewayRuntime(context),
        SherpaVoiceRuntime(context),
        MemoryRuntime(context),
        AgentToolsRuntime(context),
        McpRuntime(context),
        AutomationRuntime(context)
    )

    private fun jarvisApp(context: Context): JarvisApp? = context.applicationContext as? JarvisApp

    /** Gemini Live (primary brain) — configuration only; the websocket is never touched. */
    class GeminiLiveRuntime(private val context: Context) : Runtime {
        override val id = "gemini_live"
        override val displayName = "Gemini Live"
        override val category = RuntimeCategory.ASSISTANT
        override val description = "Primary real-time voice brain (websocket)."
        override suspend fun status(): RuntimeStatus {
            val key = jarvisApp(context)?.preferences?.apiKey.orEmpty()
            return if (key.isNotBlank()) RuntimeStatus(true, "API key configured")
            else RuntimeStatus(false, "No Gemini API key", "Add a key in Settings to use Gemini Live.")
        }
    }

    class GgufRuntime(private val context: Context) : Runtime {
        override val id = "gguf"
        override val displayName = "Local GGUF (llama.cpp)"
        override val category = RuntimeCategory.LLM
        override val description = "Jarvis' on-device llama.cpp engine."
        override suspend fun status(): RuntimeStatus {
            val app = jarvisApp(context) ?: return RuntimeStatus(false, "App not initialised")
            return when (val state = app.localAiEngine.engineState.value) {
                is LocalEngineState.Ready -> RuntimeStatus(true, "Model loaded")
                is LocalEngineState.Generating -> RuntimeStatus(true, "Generating")
                is LocalEngineState.Loading -> RuntimeStatus(false, "Loading model…")
                is LocalEngineState.Error -> RuntimeStatus(false, "Error", state.message)
                else -> RuntimeStatus(false, "Idle (no model loaded)", "Loads on demand from the Models screen or offline routing.")
            }
        }
    }

    class LiteRtRuntime(private val context: Context) : Runtime {
        override val id = "litert"
        override val displayName = "LiteRT-LM engine"
        override val category = RuntimeCategory.LLM
        override val description = "Optional AndroLLM LiteRT-LM engine (NPU / GPU / CPU)."
        override suspend fun status(): RuntimeStatus {
            if (!AndroLlmFlags.isEnabled(context, AndroLlmFlags.LITERT_ENGINE)) {
                return RuntimeStatus(false, "Disabled", disabledByUser = true)
            }
            val engines = AndroLlmEngines.getInstance(context)
            val models = runCatching { engines.listLocalModels().size }.getOrDefault(0)
            return when {
                engines.isModelLoaded() -> RuntimeStatus(true, "Model loaded", "$models model file(s) installed")
                models > 0 -> RuntimeStatus(true, "Ready ($models model file(s))", "Loads on first use")
                else -> RuntimeStatus(false, "No LiteRT model installed", AndroLlmPaths.liteRtModelsDir(context).absolutePath)
            }
        }
    }

    class CloudGatewayRuntime(private val context: Context) : Runtime {
        override val id = "cloud"
        override val displayName = "Cloud gateway"
        override val category = RuntimeCategory.CLOUD
        override val description = "Optional OpenAI-compatible / LiteLLM multi-provider gateway."
        override suspend fun status(): RuntimeStatus {
            if (!AndroLlmFlags.isEnabled(context, AndroLlmFlags.CLOUD_GATEWAY)) {
                return RuntimeStatus(false, "Disabled", disabledByUser = true)
            }
            val gateway = AndroLlmCloud.getInstance(context).gateway
            val target = runCatching { gateway.resolveChatTarget() }.getOrNull()
            return if (target != null && gateway.isConfigured()) RuntimeStatus(true, "Ready", "${target.first} · ${target.second}")
            else RuntimeStatus(false, "No provider / model configured", "Configure a provider in the Cloud screen.")
        }
    }

    class SherpaVoiceRuntime(private val context: Context) : Runtime {
        override val id = "voice_sherpa"
        override val displayName = "Offline voice (sherpa-onnx)"
        override val category = RuntimeCategory.VOICE
        override val description = "Optional wake word, streaming ASR and offline TTS."
        override suspend fun status(): RuntimeStatus {
            val enabled = listOf(
                AndroLlmFlags.SHERPA_WAKE_WORD to "wake word",
                AndroLlmFlags.SHERPA_ASR to "ASR",
                AndroLlmFlags.SHERPA_TTS to "TTS"
            ).filter { AndroLlmFlags.isEnabled(context, it.first) }.map { it.second }
            if (enabled.isEmpty()) return RuntimeStatus(false, "Disabled", disabledByUser = true)
            val status = SherpaVoiceController.status.value
            return when {
                status.nativeAvailable == false -> RuntimeStatus(false, "Native runtime unavailable", status.nativeError)
                status.ttsError != null -> RuntimeStatus(false, "TTS error", status.ttsError)
                else -> RuntimeStatus(true, "Enabled: ${enabled.joinToString()}", "Wake: ${status.wakeState.label}")
            }
        }
    }

    class MemoryRuntime(private val context: Context) : Runtime {
        override val id = "memory"
        override val displayName = "Memory core"
        override val category = RuntimeCategory.MEMORY
        override val description = "Jarvis memories + vector index + AndroLLM memory intelligence."
        override suspend fun status(): RuntimeStatus {
            val app = jarvisApp(context) ?: return RuntimeStatus(false, "App not initialised")
            val indexed = runCatching { app.vectorMemoryEngine.indexedMemoryCount() }.getOrDefault(0)
            val signature = runCatching { TextEmbeddingEngine.getInstance(context).embedderSignature() }.getOrDefault("?")
            val extraction = AndroLlmFlags.isEnabled(context, AndroLlmFlags.MEMORY_EXTRACTION)
            val pinned = MemoryMetaRegistry.snapshot().values.count { it.isPinned }
            return RuntimeStatus(
                available = true,
                summary = "$indexed indexed · $signature",
                detail = "LLM extraction ${if (extraction) "on" else "off"} · $pinned pinned"
            )
        }
    }

    class AgentToolsRuntime(private val context: Context) : Runtime {
        override val id = "tools"
        override val displayName = "Agent tool pipeline"
        override val category = RuntimeCategory.TOOLS
        override val description = "AndroLLM routed tool calling for non-Gemini providers."
        override suspend fun status(): RuntimeStatus {
            if (!AgentFlags.isPipelineEnabled(context)) {
                return RuntimeStatus(false, "Disabled (providers use their own tool path)", disabledByUser = true)
            }
            val graph = AgentToolGraph.peek()
                ?: return RuntimeStatus(true, "Enabled", "Tool registry initialises on the first agent turn")
            val tools = graph.registry.all()
            return RuntimeStatus(true, "${tools.size} tool(s) registered")
        }
    }

    class McpRuntime(private val context: Context) : Runtime {
        override val id = "mcp"
        override val displayName = "MCP servers"
        override val category = RuntimeCategory.MCP
        override val description = "Remote tools imported from Model Context Protocol servers."
        override suspend fun status(): RuntimeStatus {
            val manager = McpManager.getInstance(context)
            val servers = manager.registeredServers()
            if (servers.isEmpty()) return RuntimeStatus(false, "No servers configured")
            val states = manager.states.value
            val connected = servers.count { states[it.id] is McpManager.State.Connected }
            val failed = servers.filter { states[it.id] is McpManager.State.Failed }
            return RuntimeStatus(
                available = connected > 0,
                summary = "$connected/${servers.size} connected · ${manager.availableToolsCount.value} tool(s)",
                detail = failed.takeIf { it.isNotEmpty() }?.joinToString { "${it.name}: ${(states[it.id] as McpManager.State.Failed).message}" }
            )
        }
    }

    class AutomationRuntime(private val context: Context) : Runtime {
        override val id = "automation"
        override val displayName = "UI automation"
        override val category = RuntimeCategory.AUTOMATION
        override val description = "Accessibility-service screen reading and gestures."
        override suspend fun status(): RuntimeStatus {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val on = enabledServices.split(':').any { it.startsWith(context.packageName + "/") }
            return if (on) RuntimeStatus(true, "Accessibility service enabled")
            else RuntimeStatus(false, "Accessibility service off", "Enable Jarvis in Android Settings → Accessibility.")
        }
    }
}
