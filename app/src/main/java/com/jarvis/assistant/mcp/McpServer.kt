package com.jarvis.assistant.mcp

/**
 * Model Context Protocol (MCP) server configuration.
 * Allows JARVIS to connect to external MCP servers (running locally on device, termux, or remote PC)
 * to dynamically import tools into the JARVIS tool ecosystem.
 */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val authToken: String = "",
    val enabled: Boolean = true
) {
    companion object {
        fun fromName(name: String, url: String, authToken: String = ""): McpServer {
            val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "server" }
            return McpServer(
                id = slug,
                name = name.trim(),
                url = url.trim(),
                authToken = authToken.trim()
            )
        }
    }
}
