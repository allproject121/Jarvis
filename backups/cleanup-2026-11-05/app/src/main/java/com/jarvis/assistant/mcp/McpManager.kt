package com.jarvis.assistant.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active MCP servers and bridges remote MCP tools into JARVIS.
 *
 * Upgraded with AndroLLM's McpConnectionManager lifecycle: per-server
 * [State] (Connecting / Connected(n) / Failed), idempotent [connect],
 * [connectAll] / [disconnect] / [disconnectAll], all serialized by a Mutex so
 * connect/disconnect races cannot leave stale tools behind. The original
 * public API (registerServer / connectServer / executeMcpTool / ...) is kept.
 */
class McpManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "McpManager"
        private const val PREFS = "jarvis_mcp_servers"
        private const val KEY_SERVERS = "servers"

        @Volatile
        private var instance: McpManager? = null

        /** [scope] is ignored: the manager owns an app-lifetime scope so a caller's cancelled scope can't kill connections. */
        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context, scope: CoroutineScope? = null): McpManager {
            return instance ?: synchronized(this) {
                instance ?: McpManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val servers = ConcurrentHashMap<String, McpServer>()
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val remoteTools = ConcurrentHashMap<String, Pair<McpClient, McpRemoteToolDef>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _availableToolsCount = MutableStateFlow(0)
    val availableToolsCount: StateFlow<Int> = _availableToolsCount.asStateFlow()

    /** Per-server connection state surfaced to Settings → MCP Servers. */
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val toolCount: Int) : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** Server id → connection state. A missing entry means offline / never connected. */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val lock = Mutex()

    init {
        loadServers().forEach { registerServer(it, persist = false) }
    }

    fun registeredServers(): List<McpServer> = servers.values.toList()

    fun registerServer(server: McpServer, persist: Boolean = true) {
        servers[server.id] = server
        if (persist) saveServers()
        if (server.enabled) {
            connectServer(server)
        }
    }

    fun unregisterServer(serverId: String) {
        servers.remove(serverId)
        saveServers()
        // Drop tools synchronously (callers expect them gone immediately), then close the session.
        val client = clients.remove(serverId)
        unregisterTools(serverId)
        _states.update { it - serverId }
        if (client != null) scope.launch { runCatching { client.close() } }
    }

    /** Fire-and-forget [connect] (original Jarvis API). */
    fun connectServer(server: McpServer) {
        scope.launch { connect(server) }
    }

    /** Connects every enabled server (idempotent: connected servers are left alone). */
    suspend fun connectAll() {
        servers.values.filter { it.enabled }.forEach { connect(it) }
    }

    /**
     * initialize handshake → tools/list → import tools as `mcp_<server>_<tool>`.
     * Idempotent unless [force] (an already-connected server is not churned).
     */
    suspend fun connect(server: McpServer, force: Boolean = false) {
        lock.withLock {
            if (!force && _states.value[server.id] is State.Connected && clients.containsKey(server.id)) return@withLock
            unregisterTools(server.id)
            clients.remove(server.id)?.let { runCatching { it.close() } }
            _states.update { it + (server.id to State.Connecting) }
            val client = McpClient(server)
            try {
                if (!client.ensureInitialized()) {
                    throw IllegalStateException("Server rejected the initialize handshake")
                }
                val tools = client.listTools()
                clients[server.id] = client
                for (tool in tools) {
                    remoteTools[tool.name] = client to tool
                }
                _availableToolsCount.value = remoteTools.size
                _states.update { it + (server.id to State.Connected(tools.size)) }
                Log.i(TAG, "Connected to MCP server '${server.name}' (${tools.size} tools imported)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _states.update { it - server.id }
                throw e
            } catch (e: Exception) {
                runCatching { client.close() }
                unregisterTools(server.id)
                _states.update { it + (server.id to State.Failed(e.message ?: e.javaClass.simpleName)) }
                Log.w(TAG, "Failed to connect to MCP server '${server.name}': ${e.message}")
            }
        }
    }

    /** Unregisters [serverId]'s tools and closes its session (the server stays configured). */
    suspend fun disconnect(serverId: String) {
        lock.withLock {
            unregisterTools(serverId)
            clients.remove(serverId)?.let { runCatching { it.close() } }
            // A missing entry renders as "Offline"; removed servers never linger in the map.
            _states.update { it - serverId }
        }
    }

    suspend fun disconnectAll() {
        servers.keys.toList().forEach { disconnect(it) }
    }

    /** Re-runs the handshake + tool import for one server. */
    suspend fun reconnect(serverId: String) {
        val server = servers[serverId] ?: return
        connect(server, force = true)
    }

    /** Removes every tool imported from [serverId] (prefix match). */
    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}_"
        val iterator = remoteTools.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.second.name.startsWith(prefix)) iterator.remove()
        }
        _availableToolsCount.value = remoteTools.size
    }

    fun isMcpTool(toolName: String): Boolean = remoteTools.containsKey(toolName)

    suspend fun executeMcpTool(toolName: String, arguments: JSONObject): Pair<Boolean, String> {
        val entry = remoteTools[toolName] ?: return false to "MCP tool '$toolName' not found"
        val client = entry.first
        val def = entry.second
        return try {
            client.callTool(def.remoteName, arguments)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false to "MCP tool '${def.remoteName}' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Converts all imported MCP tools into standard OpenAI / Hermes JSON schemas.
     */
    fun getMcpToolSchemas(): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        for ((_, pair) in remoteTools) {
            val def = pair.second
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", def.name)
                    put("description", def.description)
                    put("parameters", def.inputSchema)
                })
            })
        }
        return list
    }

    private fun saveServers() {
        val arr = JSONArray()
        servers.values.forEach { srv ->
            arr.put(JSONObject().apply {
                put("id", srv.id)
                put("name", srv.name)
                put("url", srv.url)
                put("authToken", srv.authToken)
                put("enabled", srv.enabled)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun loadServers(): List<McpServer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                McpServer(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    authToken = o.optString("authToken"),
                    enabled = o.optBoolean("enabled", true)
                ).takeIf { it.id.isNotBlank() && it.url.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load saved MCP servers: ${e.message}")
            emptyList()
        }
    }
}
