package com.jarvis.assistant.androllm.agent

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherTool
import com.jarvis.assistant.androllm.agent.bridge.JarvisToolBridge
import com.jarvis.assistant.androllm.agent.cloud.CloudToolRouter
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import com.jarvis.assistant.androllm.agent.context.AgentContextBuilder
import com.jarvis.assistant.androllm.agent.context.AgentPlanner
import com.jarvis.assistant.androllm.agent.context.DeviceContextProvider
import com.jarvis.assistant.androllm.agent.coordinator.ToolResultCache
import com.jarvis.assistant.androllm.agent.coordinator.ToolRunCoordinator
import com.jarvis.assistant.androllm.agent.core.Tool
import com.jarvis.assistant.androllm.agent.executor.ToolExecutor
import com.jarvis.assistant.androllm.agent.monitoring.ToolHealthMonitor
import com.jarvis.assistant.androllm.agent.monitoring.ToolRanker
import com.jarvis.assistant.androllm.agent.planner.ToolPlanner
import com.jarvis.assistant.androllm.agent.prompt.ToolPromptBuilder
import com.jarvis.assistant.androllm.agent.registry.ToolRegistry
import com.jarvis.assistant.androllm.agent.router.ToolRouter
import com.jarvis.assistant.androllm.agent.runtime.ToolRuntime
import com.jarvis.assistant.androllm.agent.settings.AutomationSettingsStore
import com.jarvis.assistant.androllm.agent.trace.ToolExecutionTraceStore
import com.jarvis.assistant.androllm.agent.validation.ToolCallValidator
import com.jarvis.assistant.androllm.agent.validation.ToolCallingPipeline
import com.jarvis.assistant.androllm.agent.validation.ToolExecutionLogger
import com.jarvis.assistant.androllm.agent.validation.ToolExecutionPipeline
import com.jarvis.assistant.androllm.agent.validation.ToolOutputValidator
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.engine.support.AlLog
import com.jarvis.assistant.androllm.tools.impl.AndroLlmToolCatalog
import com.jarvis.assistant.automation.agent.AgentVariableStore
import com.jarvis.assistant.automation.agent.ClarificationEngine

/**
 * Feature flags owned by the agent/tool framework (stored in the shared
 * [AndroLlmFlags] preferences file). The agent pipeline is ON by default;
 * Settings → Agent tools can switch it off.
 */
object AgentFlags {
    /**
     * Routes the non-Gemini providers (LiteRT-LM, llama.cpp local core,
     * Hermes agent, Cloud Gateway) through the AndroLLM agent pipeline:
     * routed tool advertisement, plan → execute → re-plan, validation,
     * confirmations, retries, result cache and the Tool Debug trace.
     * Default ON; switched off, the providers keep their legacy tool path.
     */
    const val AGENT_PIPELINE = "agent_pipeline_enabled"

    fun isPipelineEnabled(context: Context): Boolean =
        AndroLlmFlags.isEnabled(context, AGENT_PIPELINE, default = true)

    fun setPipelineEnabled(context: Context, enabled: Boolean) =
        AndroLlmFlags.setEnabled(context, AGENT_PIPELINE, enabled)
}

/**
 * Manual object graph for the AndroLLM tool framework (replaces the Hilt
 * `ToolsModule` + `@Singleton` injection). One instance per process.
 *
 * Registration order is Jarvis-first: every tool Jarvis' dispatcher already
 * advertises is registered as a [JarvisDispatcherTool] adapter BEFORE the
 * AndroLLM tool implementations, and the registry rejects duplicate names —
 * so a Jarvis tool is never shadowed by an AndroLLM tool of the same name.
 */
class AgentToolGraph private constructor(context: Context) {

    private val appContext = context.applicationContext

    val settingsStore: AutomationSettingsStore = AutomationSettingsStore.getInstance(appContext)
    val registry = ToolRegistry()
    val router = ToolRouter()
    val traceStore = ToolExecutionTraceStore()
    val logger = ToolExecutionLogger()
    val healthMonitor = ToolHealthMonitor()
    val ranker = ToolRanker(healthMonitor)
    val resultCache = ToolResultCache()
    val validator = ToolCallValidator(registry)
    val outputValidator = ToolOutputValidator()
    val confirmationManager = ToolConfirmationManager
    val deviceContext = DeviceContextProvider(appContext)
    val agentContext = AgentContextBuilder(deviceContext, AgentVariableStore)
    val agentPlanner = AgentPlanner(logger, AgentVariableStore)
    val executionPipeline = ToolExecutionPipeline(registry, validator, logger)
    val runtime = ToolRuntime(registry)

    val executor = ToolExecutor(
        registry = registry,
        settingsStore = settingsStore,
        confirmationManager = confirmationManager,
        traceStore = traceStore,
        validator = validator,
        logger = logger,
        healthMonitor = healthMonitor,
        outputValidator = outputValidator,
        clarificationEngine = ClarificationEngine
    )

    /** Planner over the LiteRT-LM engine repository (lazy: never builds the engine graph eagerly). */
    val planner: ToolPlanner by lazy {
        ToolPlanner(
            registry = registry,
            settingsStore = settingsStore,
            engineRepository = AndroLlmEngines.getInstance(appContext).repository,
            agentContext = agentContext,
            router = router,
            validator = validator,
            logger = logger,
            agentVariableStore = AgentVariableStore,
            agentPlanner = agentPlanner
        )
    }

    val promptBuilder: ToolPromptBuilder by lazy { ToolPromptBuilder(planner) }

    val coordinator: ToolRunCoordinator by lazy {
        ToolRunCoordinator(
            planner = planner,
            executor = executor,
            settingsStore = settingsStore,
            agentContext = agentContext,
            registry = registry,
            resultCache = resultCache,
            validator = validator,
            logger = logger,
            pipeline = executionPipeline,
            variableStore = AgentVariableStore,
            agentPlanner = agentPlanner,
            healthMonitor = healthMonitor,
            ranker = ranker,
            outputValidator = outputValidator,
            clarificationEngine = ClarificationEngine
        )
    }

    val cloudToolRouter: CloudToolRouter by lazy {
        CloudToolRouter(coordinator, planner, agentPlanner, AgentVariableStore)
    }

    val callingPipeline: ToolCallingPipeline by lazy {
        ToolCallingPipeline(registry, router, validator, executor, confirmationManager, logger)
    }

    private val lock = Any()
    private val externalTools = LinkedHashMap<String, Tool>()

    /**
     * Registers (or refreshes) the tool set: Jarvis dispatcher adapters first
     * (skills / MCP servers may have changed since the last call — new names
     * are added, removed Jarvis names are dropped), then every AndroLLM tool
     * handed to [registerAndroLlmTools]. Cheap; call at the start of a turn.
     */
    fun refreshTools() {
        synchronized(lock) {
            val adapters = JarvisToolBridge.buildAdapters(appContext)
            val adapterNames = adapters.map { it.spec.name }.toSet()
            // Drop stale Jarvis adapters (e.g. an MCP server disconnected).
            registry.all()
                .filter { it is JarvisDispatcherTool && it.spec.name !in adapterNames }
                .forEach { registry.unregister(it.spec.name) }
            adapters.forEach { adapter ->
                val existing = registry.get(adapter.spec.name)
                if (existing == null) {
                    registry.register(adapter)
                } else if (existing !is JarvisDispatcherTool) {
                    // An AndroLLM tool took the name before the Jarvis tool
                    // appeared (MCP/skill added later): Jarvis wins.
                    registry.unregister(adapter.spec.name)
                    registry.register(adapter)
                }
            }
            if (!builtInsLoaded) {
                builtInsLoaded = true
                // AndroLLM's built-in tools (Hilt ToolsModule replacement).
                runCatching { AndroLlmToolCatalog.builtInTools(appContext) }
                    .onSuccess { tools -> tools.forEach { externalTools.putIfAbsent(it.spec.name.trim(), it) } }
                    .onFailure { AlLog.tag(TAG).w(it, "could not build the AndroLLM built-in tools") }
            }
            externalTools.values.forEach { tool ->
                if (!registry.contains(tool.spec.name)) registry.register(tool)
            }
        }
    }

    @Volatile
    private var builtInsLoaded = false

    /**
     * Plugin seam for the AndroLLM tool implementations
     * (com.jarvis.assistant.androllm.tools.*). Tools whose name a Jarvis tool
     * already uses are kept aside and never shadow the Jarvis tool. Returns
     * the names that are NOT active because Jarvis owns them.
     */
    fun registerAndroLlmTools(tools: Collection<Tool>): List<String> {
        synchronized(lock) {
            if (registry.size() == 0) refreshTools()
            val shadowed = mutableListOf<String>()
            for (tool in tools) {
                val name = tool.spec.name.trim()
                externalTools[name] = tool
                val existing = registry.get(name)
                when {
                    existing == null -> registry.register(tool)
                    existing is JarvisDispatcherTool -> shadowed += name
                }
            }
            if (shadowed.isNotEmpty()) {
                AlLog.tag(TAG).i("AndroLLM tools shadowed by Jarvis tools of the same name: %s", shadowed.joinToString())
            }
            return shadowed
        }
    }

    /** AndroLLM-native tools registered through [registerAndroLlmTools] (not Jarvis adapters). */
    fun androLlmTool(name: String): Tool? {
        val tool = registry.get(name) ?: return null
        return if (tool is JarvisDispatcherTool) null else tool
    }

    /** Ensures the registry is populated (first access on a cold process). */
    fun ensureRegistered(): AgentToolGraph {
        if (registry.size() == 0) refreshTools()
        return this
    }

    companion object {
        private const val TAG = "AgentToolGraph"

        @Volatile
        private var instance: AgentToolGraph? = null

        fun getInstance(context: Context): AgentToolGraph =
            instance ?: synchronized(this) {
                instance ?: AgentToolGraph(context.applicationContext).also { instance = it }
            }

        /** Returns the graph only if it was already created (never constructs it). */
        fun peek(): AgentToolGraph? = instance
    }
}
