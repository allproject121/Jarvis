package com.jarvis.assistant.ui.compose.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.mcp.McpManager
import com.jarvis.assistant.mcp.McpServer
import kotlinx.coroutines.launch
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * "MCP Servers" — backed by Jarvis' [com.jarvis.assistant.mcp.McpManager].
 * Each row shows the live per-server connection state (Connecting /
 * Connected(n tools) / Failed(reason) / Offline, ported from AndroLLM's
 * McpConnectionManager) with a reconnect action; the header shows the live
 * imported-tool count plus "Reconnect all" / "Disconnect all".
 */
@Composable
internal fun McpSection(
    servers: List<McpServer>,
    toolCount: Int,
    bridgeEnabled: Boolean,
    onBridgeToggle: (Boolean) -> Unit,
    onAdd: (name: String, url: String, token: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onToggle: (server: McpServer, enabled: Boolean) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<McpServer?>(null) }
    val context = LocalContext.current
    val manager = remember { McpManager.getInstance(context) }
    val states by manager.states.collectAsState()
    val scope = rememberCoroutineScope()

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("MCP Servers", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                    Text(
                        "Import tools from external MCP servers • $toolCount tool${if (toolCount == 1) "" else "s"} imported",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (servers.any { it.enabled }) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { scope.launch { servers.filter { it.enabled }.forEach { manager.connect(it, force = true) } } }) {
                        Text("Reconnect all", color = MaterialTheme.ledger.lampGlow)
                    }
                    TextButton(onClick = { scope.launch { manager.disconnectAll() } }) {
                        Text("Disconnect all", color = MaterialTheme.ledger.deskInk)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            ToggleRow(
                icon = Icons.Filled.Link,
                title = "Local MCP Bridge",
                subtitle = "Expose Jarvis tools on the on-device bridge (port 3000)",
                checked = bridgeEnabled,
                onCheckedChange = onBridgeToggle
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            if (servers.isEmpty()) {
                Text(
                    text = "No servers configured. Add your MCP server endpoint (Streamable HTTP, e.g. http://127.0.0.1:3000/mcp) to import its tools.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                servers.forEachIndexed { index, server ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                    McpServerRow(
                        server = server,
                        state = states[server.id],
                        onToggle = { onToggle(server, it) },
                        onRemove = { pendingRemove = server },
                        onReconnect = { scope.launch { manager.connect(server, force = true) } }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            CloudCapsuleButton(
                text = "Add MCP Server",
                onClick = { showAddDialog = true },
                icon = Icons.Filled.Add,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, token ->
                showAddDialog = false
                onAdd(name, url, token)
            }
        )
    }

    pendingRemove?.let { server ->
        ConfirmDialog(
            title = "Remove ${server.name}?",
            message = "Its imported tools will be removed from Jarvis.",
            confirmLabel = "Remove",
            onDismiss = { pendingRemove = null },
            onConfirm = {
                pendingRemove = null
                onRemove(server.id)
            }
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServer,
    state: McpManager.State?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.ledger.deskPaper,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                server.url,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val (statusText, statusColor) = when {
                !server.enabled -> "Disabled" to MaterialTheme.ledger.deskInk
                state is McpManager.State.Connecting -> "Connecting…" to MaterialTheme.ledger.lampAmber
                state is McpManager.State.Connected ->
                    "Connected • ${state.toolCount} tool${if (state.toolCount == 1) "" else "s"}" to MaterialTheme.ledger.revolutNeonEmerald
                state is McpManager.State.Failed -> "Failed • ${state.message}" to MaterialTheme.ledger.emberRed
                else -> "Offline" to MaterialTheme.ledger.deskInk
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall.copy(color = statusColor),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (server.enabled && state !is McpManager.State.Connecting && state !is McpManager.State.Connected) {
                TextButton(onClick = onReconnect, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Reconnect", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.ledger.lampGlow)
                }
            }
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = onToggle,
            colors = settingsSwitchColors()
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove ${server.name}",
                tint = MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, token: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP Server", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Column {
                Text(
                    text = "Point at an MCP server exposing the Streamable HTTP transport. Its tools become available to Jarvis as mcp_<server>_<tool>.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (http://…/mcp)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, token) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.lampAmber)
            ) {
                Text("Connect", color = MaterialTheme.ledger.inkOnLamp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.ledger.deskInk) }
        }
    )
}
