package com.jarvis.assistant.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight Model Context Protocol (MCP) Client for Android.
 * Speaks JSON-RPC 2.0 over the MCP Streamable HTTP transport (initialize handshake,
 * Mcp-Session-Id, JSON or text/event-stream responses).
 */
class McpClient(
    val server: McpServer,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "McpClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val requestId = AtomicInteger(1)
    private val initMutex = Mutex()
    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null

    /**
     * Performs the MCP initialize handshake once per client.
     */
    suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized) return@withLock true
        withContext(Dispatchers.IO) {
            val rpc = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "jarvis-android")
                        put("version", "1.0")
                    })
                })
            }
            val resp = sendRpc(rpc)
            if (resp?.optJSONObject("result") == null) {
                Log.w(TAG, "MCP initialize failed for '${server.name}': ${resp?.optJSONObject("error")?.optString("message")}")
                return@withContext false
            }
            sendRpc(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }, expectResponse = false)
            initialized = true
            true
        }
    }

    /**
     * Queries remote tools supported by the MCP server.
     */
    suspend fun listTools(): List<McpRemoteToolDef> = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) return@withContext emptyList()
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", "tools/list")
            put("params", JSONObject())
        }

        val responseJson = sendRpc(rpc) ?: return@withContext emptyList()
        val result = responseJson.optJSONObject("result") ?: return@withContext emptyList()
        val toolsArray = result.optJSONArray("tools") ?: return@withContext emptyList()

        val list = mutableListOf<McpRemoteToolDef>()
        for (i in 0 until toolsArray.length()) {
            val t = toolsArray.getJSONObject(i)
            val name = t.optString("name")
            val desc = t.optString("description", "")
            if (name.isNotBlank()) {
                list.add(McpRemoteToolDef(
                    name = "mcp_${server.id}_$name".replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64),
                    remoteName = name,
                    description = McpRemoteToolDef.describe(desc, server.name),
                    inputSchema = McpRemoteToolDef.normalizeSchema(t.optJSONObject("inputSchema"))
                ))
            }
        }
        list
    }

    /**
     * Executes a tool on the remote MCP server.
     */
    suspend fun callTool(toolName: String, arguments: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) return@withContext false to "MCP server '${server.name}' did not complete initialization"
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            })
        }

        val responseJson = sendRpc(rpc)
            ?: return@withContext false to "Failed to connect to MCP server '${server.name}'"

        val error = responseJson.optJSONObject("error")
        if (error != null) {
            val errMsg = error.optString("message", "Unknown MCP RPC error")
            return@withContext false to errMsg
        }

        val result = responseJson.optJSONObject("result")
        val isError = result?.optBoolean("isError", false) ?: false
        (!isError) to McpRemoteToolDef.flattenContent(result?.optJSONArray("content"))
    }

    /** True once the initialize handshake succeeded (and the session is still valid). */
    val isInitialized: Boolean get() = initialized

    /**
     * Ends the MCP session: best-effort HTTP DELETE with the session id (MCP
     * Streamable HTTP spec) and resets local handshake state.
     */
    suspend fun close(): Unit = withContext(Dispatchers.IO) {
        val sid = sessionId
        sessionId = null
        initialized = false
        if (sid == null) return@withContext
        try {
            val req = Request.Builder()
                .url(server.url)
                .delete()
                .header("Mcp-Session-Id", sid)
                .apply { if (server.authToken.isNotBlank()) header("Authorization", "Bearer ${server.authToken}") }
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.d(TAG, "MCP session close for '${server.name}' ignored: ${e.message}")
        }
    }

    private fun sendRpc(payload: JSONObject, expectResponse: Boolean = true): JSONObject? {
        val reqBuilder = Request.Builder()
            .url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (server.authToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${server.authToken}")
        }
        sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                if (resp.code == 404 && sessionId != null) {
                    // Session expired on the server: force a fresh handshake next time.
                    sessionId = null
                    initialized = false
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "MCP RPC failed with HTTP ${resp.code}: ${resp.message}")
                    return null
                }
                if (!expectResponse) return null
                val bodyStr = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type").orEmpty()
                if (contentType.contains("text/event-stream")) parseSse(bodyStr, payload.optInt("id")) else JSONObject(bodyStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MCP RPC transport error for '${server.name}': ${e.message}")
            null
        }
    }

    /**
     * Picks the JSON-RPC response matching [id] out of an SSE body.
     */
    private fun parseSse(body: String, id: Int): JSONObject? {
        var last: JSONObject? = null
        for (event in body.split(Regex("""\r?\n\r?\n"""))) {
            val data = event.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isBlank()) continue
            val obj = try { JSONObject(data) } catch (_: Exception) { continue }
            if (obj.optInt("id", -1) == id) return obj
            last = obj
        }
        return last
    }
}

data class McpRemoteToolDef(
    val name: String,
    val remoteName: String,
    val description: String,
    val inputSchema: JSONObject
) {
    /**
     * Remote-tool adaptation rules ported from AndroLLM `core/mcp/McpRemoteTool`.
     */
    companion object {
        /** Description + " (via MCP server '<server>')" so the model knows where a tool runs. */
        fun describe(remoteDescription: String?, serverName: String): String = buildString {
            append(remoteDescription?.trim()?.takeIf { it.isNotBlank() } ?: "Remote tool from MCP server '$serverName'.")
            append(" (via MCP server '").append(serverName).append("')")
        }

        /**
         * Normalizes a remote JSON-Schema `inputSchema` into the
         * `{type: object, properties, required}` shape tool callers expect.
         */
        fun normalizeSchema(schema: JSONObject?): JSONObject = try {
            val properties = schema?.optJSONObject("properties") ?: JSONObject()
            val required = schema?.optJSONArray("required")
            JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                if (required != null && required.length() > 0) put("required", required)
            }
        } catch (_: Exception) {
            JSONObject().apply { put("type", "object") }
        }

        /**
         * Flattens MCP `content` blocks: text is kept, non-text blocks (image /
         * audio / resources) are replaced by a placeholder — raw base64 never
         * reaches the LLM context.
         */
        fun flattenContent(content: JSONArray?): String {
            if (content == null || content.length() == 0) return "OK (no content)"
            val parts = ArrayList<String>(content.length())
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                val part = when (val type = item.optString("type")) {
                    "text", "" -> item.optString("text", "")
                    "image" -> "[ImageContent]"
                    "audio" -> "[AudioContent]"
                    "resource" -> "[EmbeddedResource]"
                    "resource_link" -> "[ResourceLink]"
                    else -> "[$type]"
                }
                if (part.isNotBlank()) parts += part
            }
            return if (parts.isEmpty()) "OK (no content)" else parts.joinToString("\n")
        }
    }
}
