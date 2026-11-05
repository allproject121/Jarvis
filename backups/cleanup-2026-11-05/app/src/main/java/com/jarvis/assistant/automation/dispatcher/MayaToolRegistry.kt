package com.jarvis.assistant.automation.dispatcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry for the Maya-derived feature tool packs (screen verb loop, memory/rules, connectors,
 * business/finance, guardian, Termux/PC hub, media ...).
 *
 * Every pack is a plain `object` with `fun install(context: Context)` that calls [register]
 * for each tool. [JarvisToolDispatcher] consults this registry *before* its built-in `when`
 * (dispatchTool) and appends [schemas] to `getAvailableToolSchemas`, so every registered tool
 * automatically reaches Gemini Live, the offline brain, Hermes and the AndroLLM agent pipeline.
 *
 * Schema shape is the dispatcher's OpenAI/Hermes function format:
 * {"type":"function","function":{"name","description","parameters":{type,properties,required}}}
 */
object MayaToolRegistry {

    private const val TAG = "MayaToolRegistry"

    /** Result returned by a tool handler. [json] is merged into the dispatcher's resultJson. */
    data class ToolOutcome(
        val success: Boolean,
        val message: String,
        val json: JSONObject = JSONObject()
    ) {
        companion object {
            fun ok(message: String, extra: JSONObject = JSONObject()) = ToolOutcome(true, message, extra)
            fun fail(message: String, extra: JSONObject = JSONObject()) = ToolOutcome(false, message, extra)
        }
    }

    class RegisteredTool(
        val name: String,
        val description: String,
        val properties: JSONObject,
        val required: List<String>,
        /** Family label used by `find_tool` ("screen", "memory", "connectors", "business", ...). */
        val family: String,
        /** Risky tools go through the Voice Guard like the built-ins (calls, messages, deletes...). */
        val risky: Boolean,
        val handler: suspend (Context, JSONObject) -> ToolOutcome
    ) {
        fun schema(): JSONObject = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JSONArray(required))
                })
            })
        }
    }

    private val tools = linkedMapOf<String, RegisteredTool>()

    @Volatile
    private var installed = false

    /** Registers (or replaces) one tool. Built-in dispatcher names must not be reused. */
    fun register(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
        family: String = "general",
        risky: Boolean = false,
        handler: suspend (Context, JSONObject) -> ToolOutcome
    ) {
        synchronized(tools) {
            tools[name] = RegisteredTool(name, description, properties, required, family, risky, handler)
        }
    }

    /** Convenience for packs: builds a {"type":..,"description":..} property. */
    fun prop(type: String, description: String, enum: List<String>? = null): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enum != null) put("enum", JSONArray(enum))
    }

    fun props(vararg pairs: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    fun has(name: String): Boolean = synchronized(tools) { tools.containsKey(name) }

    fun get(name: String): RegisteredTool? = synchronized(tools) { tools[name] }

    fun all(): List<RegisteredTool> = synchronized(tools) { tools.values.toList() }

    fun schemas(): List<JSONObject> = all().map { it.schema() }

    /**
     * The small, high-frequency set of Maya tools shown to every brain in its tool list.
     * Showing all ~280 tools overloads the models: Gemini picks the wrong tool and the 2K-context
     * offline model gets its prompt truncated into garbage. Maya itself shows a core set and lets
     * the model reach the rest through `find_tool` + `run_tool` — this mirrors that.
     */
    val CORE: Set<String> = setOf(
        "find_tool", "run_tool",
        // screen / automation
        "run_agent_task", "read_screen_elements", "tap_element", "watch_for", "play_macro",
        // memory
        "remember_fact", "recall_memory", "add_rule", "recall_episodes", "import_chatgpt_data",
        // whatsapp / media
        "whatsapp_delete_message", "whatsapp_delete_sent_message", "whatsapp_send_file",
        "generate_image", "play_music", "set_timer", "take_photo", "get_current_time",
        // guardian
        "lock_device", "set_listen_mode",
        // hub
        "termux_run", "smart_home_control", "ask_my_pc",
        // business / brain
        "get_stock_quote", "run_mission", "set_brain"
    )

    /** Schemas for [CORE] tools that are actually registered. */
    fun coreSchemas(): List<JSONObject> = all().filter { it.name in CORE }.map { it.schema() }

    fun isRisky(name: String): Boolean = get(name)?.risky == true

    /** Installs every pack once. Safe to call repeatedly. */
    fun ensureInstalled(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                MayaToolPacks.installAll(context.applicationContext)
                registerRunTool()
                installed = true
                Log.i(TAG, "Installed ${tools.size} Maya feature tools")
            } catch (e: Exception) {
                Log.e(TAG, "Failed installing Maya tool packs: ${e.message}", e)
            }
        }
    }

    /**
     * `run_tool` lets a brain call any registered tool that is not in its visible list, after
     * finding it with `find_tool`. It runs inside the dispatcher's lock, so it only executes
     * registry tools directly (built-in tools are already visible and called by name).
     */
    private fun registerRunTool() {
        register(
            name = "run_tool",
            description = "Run any tool by exact name after finding it with find_tool. Use when the needed tool is not in your list. args = that tool's arguments as a JSON object.",
            properties = props(
                "name" to prop("string", "Exact tool name returned by find_tool"),
                "args" to prop("object", "Arguments for that tool")
            ),
            required = listOf("name"),
            family = "general"
        ) { ctx, a ->
            val target = a.optString("name").trim()
            if (target.isEmpty() || target == "run_tool") return@register ToolOutcome.fail("Give the exact tool name from find_tool.")
            val tool = get(target) ?: return@register ToolOutcome.fail("No tool named '$target'. Call find_tool first, or call built-in tools directly by name.")
            val inner = a.optJSONObject("args")
                ?: a.optString("args").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            if (tool.risky) {
                val block = com.jarvis.assistant.security.guardian.VoiceGuardGate.check(ctx, target, inner)
                if (block != null) return@register ToolOutcome.fail(block)
            }
            dispatch(ctx, target, inner)
        }
    }

    suspend fun dispatch(context: Context, name: String, args: JSONObject): ToolOutcome {
        val tool = get(name) ?: return ToolOutcome.fail("Unknown tool '$name'")
        return try {
            tool.handler(context, args)
        } catch (e: Exception) {
            Log.e(TAG, "Tool '$name' threw: ${e.message}", e)
            ToolOutcome.fail("Tool '$name' failed: ${e.message}")
        }
    }

    /**
     * Fuzzy catalog search used by the `find_tool` tool: scores every registered tool (and the
     * dispatcher built-ins passed in [extraCatalog]) by keyword overlap with [query].
     */
    fun search(query: String, extraCatalog: List<Pair<String, String>> = emptyList(), limit: Int = 8): List<Triple<String, String, Int>> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        val catalog = all().map { Triple(it.name, it.description, it.family) } +
            extraCatalog.map { Triple(it.first, it.second, "builtin") }
        return catalog.map { (n, d, f) ->
            val hay = ("$n $d $f").lowercase()
            val nameWords = n.split('_').toSet()
            var score = 0
            for (w in words) {
                if (w in nameWords) score += 5
                else if (n.contains(w)) score += 3
                if (hay.contains(w)) score += 1
            }
            Triple(n, d, score)
        }.filter { it.third > 0 }.sortedByDescending { it.third }.take(limit)
    }
}
