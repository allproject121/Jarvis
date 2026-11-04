package com.jarvis.assistant.ui.compose.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.androllm.agent.AgentFlags
import com.jarvis.assistant.androllm.agent.AgentToolGraph
import com.jarvis.assistant.androllm.agent.bridge.JarvisDispatcherTool
import com.jarvis.assistant.androllm.agent.core.ToolCategory
import com.jarvis.assistant.androllm.agent.settings.AutomationSettings
import com.jarvis.assistant.androllm.agent.settings.ConfirmationMode
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the per-tool toggle list. */
private data class AgentToolRow(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val requiresConfirmation: Boolean,
    val source: String
)

/**
 * "Agent tools" settings (AndroLLM Settings → Automation, ported):
 *
 * - Agent pipeline switch ([AgentFlags.AGENT_PIPELINE], default ON) — routes
 *   the non-Gemini engines (LiteRT-LM, local core, Hermes, Cloud Gateway)
 *   through the AndroLLM planner / executor. Gemini Live is never affected.
 * - Tool-calling master switch, confirmation mode and the planner round cap
 *   (AutomationSettingsStore).
 * - Per-tool toggles in the five AndroLLM categories. They are honoured by
 *   the agent pipeline AND by JarvisToolDispatcher (a disabled tool is hidden
 *   from the non-Gemini engines' tool list and refused if called).
 * - A link to the Tool Debug execution log via [onOpenToolDebug].
 */
@Composable
fun AgentSettingsSection(onOpenToolDebug: () -> Unit = {}) {
    val context = LocalContext.current
    val graph = remember { AgentToolGraph.getInstance(context) }
    val store = graph.settingsStore
    val settings by store.settings.collectAsState(initial = store.currentBlocking())
    val scope = rememberCoroutineScope()

    var pipelineOn by remember { mutableStateOf(AgentFlags.isPipelineEnabled(context)) }
    var tools by remember { mutableStateOf<List<AgentToolRow>>(emptyList()) }
    var showModeDialog by remember { mutableStateOf(false) }
    var expandedCategory by remember { mutableStateOf<ToolCategory?>(null) }

    LaunchedEffect(graph) {
        tools = withContext(Dispatchers.IO) {
            runCatching { graph.refreshTools() }
            graph.registry.all().map { tool ->
                AgentToolRow(
                    name = tool.spec.name,
                    description = tool.spec.description,
                    category = tool.spec.category,
                    requiresConfirmation = tool.spec.requiresConfirmation,
                    source = if (tool is JarvisDispatcherTool) "Jarvis" else "AndroLLM"
                )
            }.sortedBy { it.name }
        }
    }

    fun update(transform: (AutomationSettings) -> AutomationSettings) {
        scope.launch { runCatching { store.update(transform) } }
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
        Column {
            ToggleRow(
                icon = Icons.Filled.AccountTree,
                title = "Agent pipeline",
                subtitle = "Route LiteRT-LM, local core, Hermes and Cloud Gateway through the AndroLLM planner (plan → execute → re-plan). Gemini Live is unaffected.",
                checked = pipelineOn,
                onCheckedChange = { on ->
                    AgentFlags.setPipelineEnabled(context, on)
                    pipelineOn = on
                }
            )
            ToggleRow(
                icon = Icons.Filled.Build,
                title = "Tool calling",
                subtitle = "Master switch for the agent pipeline's tools. Per-tool toggles below also apply to Jarvis' tool dispatcher.",
                checked = settings.toolCallingEnabled,
                onCheckedChange = { on -> update { it.copy(toolCallingEnabled = on) } }
            )
            SettingRow(
                icon = Icons.Filled.VerifiedUser,
                title = "Confirmations",
                value = settings.confirmationMode.displayName
            ) { showModeDialog = true }
            SliderSetting(
                label = { "Max planning rounds per turn: ${it.toInt()}" },
                value = settings.maxToolRounds.toFloat(),
                valueRange = 1f..6f,
                steps = 4,
                onCommit = { v -> update { it.copy(maxToolRounds = v.toInt().coerceIn(1, 6)) } }
            )
            SettingRow(
                icon = Icons.Filled.BugReport,
                title = "Tool Debug",
                value = "Execution log"
            ) { onOpenToolDebug() }

            HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f))
            Text(
                text = "Tools (${tools.count { settings.isToolEnabled(it.name) }}/${tools.size} enabled)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.ledger.deskInk,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (tools.isEmpty()) {
                Text(
                    text = "Loading tools…",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            ToolCategory.entries.forEach { category ->
                val inCategory = tools.filter { it.category == category }
                if (inCategory.isEmpty()) return@forEach
                val expanded = expandedCategory == category
                val enabledCount = inCategory.count { it.name !in settings.disabledTools }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedCategory = if (expanded) null else category }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.ledger.deskPaper
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$enabledCount/${inCategory.size}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                    TextButton(onClick = {
                        val names = inCategory.map { it.name }.toSet()
                        val allOn = enabledCount == inCategory.size
                        update {
                            it.copy(
                                disabledTools = if (allOn) it.disabledTools + names else it.disabledTools - names
                            )
                        }
                    }) {
                        Text(
                            if (enabledCount == inCategory.size) "All off" else "All on",
                            color = MaterialTheme.ledger.lampGlow
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.ledger.deskInk,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (expanded) {
                    inCategory.forEach { tool ->
                        AgentToolToggle(
                            tool = tool,
                            checked = tool.name !in settings.disabledTools,
                            onCheckedChange = { on ->
                                scope.launch { runCatching { store.setToolEnabled(tool.name, on) } }
                            }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.08f))
            }
        }
    }

    if (showModeDialog) {
        val modes = ConfirmationMode.entries
        ChoiceDialog(
            title = "Confirm tool actions",
            options = modes.map { it.name },
            selected = settings.confirmationMode.name,
            onDismiss = { showModeDialog = false },
            onSelect = { index ->
                val mode = modes[index]
                scope.launch { runCatching { store.setConfirmationMode(mode) } }
                showModeDialog = false
            },
            labelFor = { name ->
                val mode = ConfirmationMode.valueOf(name)
                "${mode.displayName} — ${mode.description}"
            }
        )
    }
}

@Composable
private fun AgentToolToggle(
    tool: AgentToolRow,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.name + if (tool.requiresConfirmation) "  • confirm" else "",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${tool.source} — ${tool.description}",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = settingsSwitchColors()
        )
    }
}
