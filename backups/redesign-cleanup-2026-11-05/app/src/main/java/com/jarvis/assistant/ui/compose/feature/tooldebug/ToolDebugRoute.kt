package com.jarvis.assistant.ui.compose.feature.tooldebug

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.androllm.agent.trace.ToolExecutionTrace
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.bounceClick
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tool Debug — the per-call execution log of the agent tool pipeline
 * (ported from AndroLLM feature/developer ToolDebugScreen). Every row
 * answers: which prompt, which tool, what arguments, did it succeed, what
 * did it return, how long, and what the LLM finally said.
 *
 * Traces are recorded by the agent ToolExecutor, i.e. for the non-Gemini
 * engines while Settings → Agent tools → "Agent pipeline" is on, and for
 * AndroLLM-native tools invoked through JarvisToolDispatcher.
 */
@Composable
fun ToolDebugRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: ToolDebugViewModel = viewModel { ToolDebugViewModel(app) }
    ToolDebugScreen(viewModel = viewModel, navigate = navigate, back = back)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDebugScreen(
    viewModel: ToolDebugViewModel,
    navigate: (String) -> Unit,
    back: () -> Unit
) {
    val traces by viewModel.traces.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.DEVELOPER,
            onTabSelected = { tab -> if (tab.route != ComposeRoutes.DEVELOPER) navigate(tab.route) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.ledger.deskPaper
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "Tool Execution Log",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.ledger.deskPaper
                                )
                            )
                            Text(
                                text = "${traces.size} call(s) • prompt → tool → result → LLM output",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampDeep,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear log", tint = MaterialTheme.ledger.deskInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StaggeredEntrance(index = 0) {
                        ToolRegistryStatusCard(
                            diagnostics = diagnostics,
                            onRefresh = { viewModel.refresh() },
                            onProbeCapability = { viewModel.probeCapability() }
                        )
                    }
                }

                if (traces.isEmpty()) {
                    item {
                        StaggeredEntrance(index = 1) {
                            Column {
                                Spacer(modifier = Modifier.height(24.dp))
                                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(4.dp)) {
                                        Icon(
                                            imageVector = Icons.Filled.Bolt,
                                            contentDescription = null,
                                            tint = MaterialTheme.ledger.lampGlow,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "No tool executions yet",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.ledger.deskPaper
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (diagnostics.agentPipelineFlag) {
                                                "Ask an on-device or cloud-gateway engine something like “Open Discord” or “Search the web for NVIDIA news”. Every tool call — the arguments, status, result, timing and final LLM output — will appear here."
                                            } else {
                                                "The agent pipeline is off (Settings → Agent tools). Turn it on to route the LiteRT-LM, local core, Hermes and Cloud Gateway engines through it and log every tool call here. Gemini Live is never routed through it."
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(traces, key = { it.id }) { trace ->
                        StaggeredEntrance(index = 0, instant = true) {
                            TraceCard(trace)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolRegistryStatusCard(
    diagnostics: ToolDiagnostics,
    onRefresh: () -> Unit,
    onProbeCapability: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tool Registry",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = "Registered tools • pipeline • prompt injection",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh tool registry", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CloudChip(
                    text = if (diagnostics.agentPipelineFlag) "● Agent pipeline ON" else "○ Agent pipeline OFF",
                    accentColor = if (diagnostics.agentPipelineFlag) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                )
                CloudChip(
                    text = if (diagnostics.pipelineEnabled) "● Tool calling ON" else "○ Tool calling OFF",
                    accentColor = if (diagnostics.pipelineEnabled) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                )
                CloudChip(
                    text = "${diagnostics.registeredCount} registered",
                    accentColor = MaterialTheme.ledger.lampDeep
                )
                CloudChip(
                    text = "${diagnostics.advertisedCount} advertised",
                    accentColor = if (diagnostics.advertisedCount > 0) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (diagnostics.advertisementPreview.isNotBlank())
                    "PROMPT INJECTION — the system prompt advertises these tools:"
                else
                    "PROMPT INJECTION — nothing advertised (tool calling off, or every tool blocked)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (diagnostics.advertisementPreview.isNotBlank()) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                    fontWeight = FontWeight.Bold
                )
            )
            if (diagnostics.advertisementPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diagnostics.advertisementPreview,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.deskInk,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Model tool-call capability (LiteRT-LM planner) ─────────────
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            val cap = diagnostics.capability
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Model Tool Capability",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.ledger.deskPaper,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = when {
                            cap.probeStatus.isNotBlank() -> "Probe: ${cap.probeStatus}"
                            cap.planningRounds == 0 -> "No planning rounds yet"
                            cap.nativeJsonSupport -> "Native JSON tool calling"
                            else -> "Parser compatibility mode"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (cap.nativeJsonSupport) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                        )
                    )
                }
                TextButton(onClick = onProbeCapability) {
                    Text("Probe model", color = MaterialTheme.ledger.lampGlow)
                }
            }
            if (cap.planningRounds > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Model: ${cap.modelName.ifBlank { "—" }} • " +
                        "${cap.planningRounds} round${if (cap.planningRounds == 1) "" else "s"}: " +
                        "${cap.cleanParses} clean / ${cap.fallbackParses} parser-salvaged",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            if (cap.lastOutputSample.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = cap.lastOutputSample,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.deskInk,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Registered tools (tap a row to expand its function schema)
            if (diagnostics.tools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(8.dp))
                var expanded by remember { mutableStateOf<String?>(null) }
                diagnostics.tools.forEach { tool ->
                    val isExpanded = expanded == tool.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick { expanded = if (isExpanded) null else tool.name }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (tool.enabled) "●" else "○",
                            color = if (tool.enabled) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.deskPaper
                            ),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tool.requiresConfirmation) {
                            Text(
                                text = "confirm",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampAmber,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = "${tool.source} • ${tool.category}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.lampDeep,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = tool.parametersJson.ifBlank { "{}" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.ledger.lampDeep,
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceCard(trace: ToolExecutionTrace) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trace.toolName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${trace.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CloudChip(
                        text = trace.status,
                        accentColor = when (trace.status) {
                            "ok" -> MaterialTheme.ledger.lampGlow
                            "blocked" -> MaterialTheme.ledger.deskInk
                            else -> MaterialTheme.ledger.emberRed
                        }
                    )
                }
            }

            val prompt = trace.prompt
            if (!prompt.isNullOrBlank()) TraceRow(label = "Prompt", value = prompt)
            if (trace.arguments.isNotBlank()) TraceRow(label = "Args", value = trace.arguments)
            if (trace.result.isNotBlank()) TraceRow(label = "Result", value = trace.result, color = MaterialTheme.ledger.deskPaper)
            val error = trace.error
            if (!error.isNullOrBlank()) TraceRow(label = "Error", value = error, color = MaterialTheme.ledger.emberRed)
            val llmOutput = trace.llmOutput
            if (!llmOutput.isNullOrBlank()) TraceRow(label = "LLM out", value = llmOutput)

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = TIME.format(Date(trace.at)),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.ledger.lampDeep,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun TraceRow(label: String, value: String, color: Color = MaterialTheme.ledger.deskInk) {
    Spacer(modifier = Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.ledger.lampDeep,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = color,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
