package com.jarvis.assistant.ui.compose.feature.developer

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ai.diagnostics.EngineCrashGuard
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import com.jarvis.assistant.telemetry.DeviceMetrics
import com.jarvis.assistant.telemetry.GenerationStat
import com.jarvis.assistant.telemetry.TelemetrySample
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudBarChart
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import androidx.compose.runtime.remember
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudLineChart
import com.jarvis.assistant.ui.compose.components.CloudUsageBar
import com.jarvis.assistant.ui.compose.components.DeveloperStatsCard
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.StatsCardSubStat
import com.jarvis.assistant.ui.compose.components.StatsDeltaTone
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import com.jarvis.assistant.ui.compose.designsystem.BentoTopBar
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.designsystem.BentoIconPlate
import com.jarvis.assistant.ui.compose.designsystem.BentoSectionHeader
import com.jarvis.assistant.ui.compose.designsystem.bentoTokens

/** Derived view of the telemetry flows (mirrors AndroLLM's DeveloperData). */
private class DeveloperData(
    val history: List<TelemetrySample>,
    val generations: List<GenerationStat>,
    val device: DeviceMetrics?
) {
    val speedHistory: List<Float> = history.map { it.tokensPerSecond }
    val ramHistory: List<Float> = history.map { it.ramUsedMb }
    val peakTokensPerSecond: Float = speedHistory.maxOrNull() ?: 0f
    val avgTokensPerSecond: Float = speedHistory.filter { it > 0f }.let { if (it.isEmpty()) 0f else it.average().toFloat() }
    val lastTokensPerSecond: Float = history.lastOrNull()?.tokensPerSecond ?: 0f
    val lastGeneration: GenerationStat? = generations.lastOrNull()
    val totalTokens: Long = generations.sumOf { it.promptTokens + it.generatedTokens }
    val generationLatencies: List<Float> = generations.map { it.totalTimeMs.toFloat() }
    val generationSpeeds: List<Float> = generations.map { it.tokensPerSecond }
}

private fun f1(value: Float): String = String.format(Locale.US, "%.1f", value)

/**
 * Developer Mode (ported from AndroLLM feature/developer). Every chart is
 * backed by Jarvis' [com.jarvis.assistant.telemetry.TelemetryRepository];
 * AndroLLM-only panels (tool trace log, GPU/KV cache, backend benchmark) are
 * shown in an explicit "not available in Jarvis" state.
 */
@Composable
fun DeveloperRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: DeveloperViewModel = viewModel { DeveloperViewModel(app) }

    // 1 Hz sampling only while this screen is on screen (ref-counted).
    DisposableEffect(viewModel) {
        viewModel.telemetry.startSampling()
        onDispose { viewModel.telemetry.stopSampling() }
    }

    DeveloperScreen(viewModel = viewModel, navigate = navigate, back = back)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeveloperScreen(
    viewModel: DeveloperViewModel,
    navigate: (String) -> Unit,
    back: () -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val generations by viewModel.generations.collectAsStateWithLifecycle()
    val device by viewModel.deviceMetrics.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeModelId.collectAsStateWithLifecycle()
    val aiMode by viewModel.aiMode.collectAsStateWithLifecycle()
    val lastProvider by viewModel.lastProvider.collectAsStateWithLifecycle()
    val systemMemory by viewModel.systemMemory.collectAsStateWithLifecycle()
    val hardware by viewModel.hardware.collectAsStateWithLifecycle()
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val runtimes by viewModel.runtimes.collectAsStateWithLifecycle()
    val crashes by viewModel.crashes.collectAsStateWithLifecycle()
    val crashTotal by viewModel.crashTotal.collectAsStateWithLifecycle()
    val contextLength by viewModel.contextLength.collectAsStateWithLifecycle()
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val toolTraces by viewModel.toolTraces.collectAsStateWithLifecycle()
    val phoneModelReady by viewModel.phoneModelReady.collectAsStateWithLifecycle()
    val liteRtOn = remember { viewModel.liteRtEnabled() }

    val data = DeveloperData(history, generations, device)
    val modelName = viewModel.modelName(activeModelId)
    val modelLoaded = phoneModelReady || engineState is LocalEngineState.Ready || engineState is LocalEngineState.Generating

    // Keep the system-RAM / runtime snapshots fresh while the screen is open.
    LaunchedEffect(viewModel) {
        while (true) {
            delay(5_000)
            viewModel.refreshSystemMemory()
            viewModel.refreshRuntimes()
        }
    }

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.DEVELOPER,
            onTabSelected = { tab -> if (tab.route != ComposeRoutes.DEVELOPER) navigate(tab.route) },
            topBar = {
                BentoTopBar(
                    title = "Developer Mode",
                    subtitle = "Gemini check • tool calls • engine & device telemetry",
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.ledger.deskPaper
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.ledger.deskInk)
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    StaggeredEntrance(index = 0) {
                        SessionStatsHero(
                            data = data,
                            modelLoaded = modelLoaded,
                            modelName = modelName,
                            providerLabel = aiMode.displayName.substringBefore(" (")
                        )
                    }
                }

                item {
                    StaggeredEntrance(index = 1) {
                        DeviceSummaryCard(
                            data = data,
                            modelName = if (modelLoaded) modelName else "No model loaded",
                            modeLabel = aiMode.displayName.substringBefore(" ("),
                            engineState = engineState
                        )
                    }
                }

                item {
                    StaggeredEntrance(index = 2) {
                        GeminiCheckCard(viewModel)
                    }
                }

                // Every tool call Jarvis made (recorded by JarvisToolDispatcher).
                item {
                    StaggeredEntrance(index = 2) {
                        RecentToolCallsCard(traces = toolTraces, onOpen = { navigate(ComposeRoutes.TOOL_DEBUG) })
                    }
                }

                item {
                    StaggeredEntrance(index = 3) {
                        ChartCard(
                            title = "Inference Throughput",
                            liveValue = "${f1(data.lastTokensPerSecond)} tok/s",
                            subtitle = "Peak ${f1(data.peakTokensPerSecond)} • Avg ${f1(data.avgTokensPerSecond)} • ${data.speedHistory.size} samples"
                        ) {
                            CloudLineChart(
                                dataPoints = data.speedHistory.ifEmpty { listOf(0f, 0f) },
                                accent = MaterialTheme.ledger.lampGlow,
                                height = 120.dp
                            )
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 4) {
                        ChartCard(
                            title = "RAM Pressure",
                            liveValue = "${data.history.lastOrNull()?.ramUsedMb?.toInt() ?: 0} MB",
                            subtitle = "of ${data.device?.totalRamMb ?: 0} MB device RAM"
                        ) {
                            CloudLineChart(
                                dataPoints = data.ramHistory.ifEmpty { listOf(0f, 0f) },
                                accent = MaterialTheme.ledger.lampAmber,
                                height = 110.dp
                            )
                        }
                    }
                }

                // Context window: recommended window vs. last generation's tokens.
                item {
                    StaggeredEntrance(index = 6) {
                        val last = data.lastGeneration
                        val used = (last?.promptTokens ?: 0L) + (last?.generatedTokens ?: 0L)
                        val fraction = if (contextLength > 0) (used.toFloat() / contextLength).coerceIn(0f, 1f) else 0f
                        val accent = if (fraction > 0.85f) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.lampGlow
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Context Window",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.ledger.deskPaper
                                        )
                                    )
                                    Text(
                                        text = "$used / $contextLength tokens",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = accent,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                CloudUsageBar(
                                    label = "Used",
                                    valueText = "${(fraction * 100).toInt()}%",
                                    fraction = fraction,
                                    accent = accent
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Last generation: ${last?.promptTokens ?: 0} prompt + ${last?.generatedTokens ?: 0} generated tokens • window = device-recommended context",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                                )
                            }
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 7) {
                        Column {
                            SectionHeader(
                                title = "Generation History",
                                subtitle = "${data.generations.size} runs this session"
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(
                                        text = "Latency per generation (ms)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.ledger.deskInk
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    CloudBarChart(
                                        values = data.generationLatencies.ifEmpty { listOf(0f, 0f) },
                                        accent = MaterialTheme.ledger.lampGlow,
                                        height = 110.dp
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Tokens/sec per generation",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.ledger.deskInk
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    CloudBarChart(
                                        values = data.generationSpeeds.ifEmpty { listOf(0f, 0f) },
                                        accent = MaterialTheme.ledger.lampAmber,
                                        height = 110.dp
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 8) {
                        BackendDiagnosticsCard(
                            data = data,
                            engineState = engineState,
                            modelName = modelName,
                            lastProvider = lastProvider,
                            threads = threads,
                            hardware = hardware,
                            systemMemory = systemMemory,
                            onRefresh = { viewModel.refresh() }
                        )
                    }
                }

                // LiteRT-LM is an optional second engine: only shown when switched on in Engines.
                if (liteRtOn) item {
                    StaggeredEntrance(index = 9) {
                        Column {
                            SectionHeader(
                                title = "LiteRT-LM Backends",
                                subtitle = "CPU / GPU / NPU accelerators • engine state • benchmark"
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LiteRtDiagnosticsCard(viewModel = viewModel)
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 10) {
                        Column {
                            SectionHeader(
                                title = "Memory Inspector",
                                subtitle = "Jarvis long-term memory store"
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            MemoryInspectorCard(
                                snapshot = memory,
                                onRefresh = { viewModel.refreshMemoryInspector() }
                            )
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 11) {
                        Column {
                            SectionHeader(
                                title = "Runtime Registry",
                                subtitle = "Every Jarvis subsystem — live status"
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            RuntimeRegistryCard(
                                runtimes = runtimes,
                                onRefresh = { viewModel.refreshRuntimes() }
                            )
                        }
                    }
                }

                item {
                    StaggeredEntrance(index = 12) {
                        Column {
                            SectionHeader(
                                title = "Engine Crash Guard",
                                subtitle = "$crashTotal guarded failure${if (crashTotal == 1) "" else "s"} this session"
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            CrashGuardCard(
                                crashes = crashes,
                                onRefresh = { viewModel.refreshCrashes() }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

/** Real Gemini checks: a text request through chat's own models and a real Live session. */
@Composable
private fun GeminiCheckCard(viewModel: DeveloperViewModel) {
    val text by viewModel.geminiText.collectAsStateWithLifecycle()
    val live by viewModel.geminiLive.collectAsStateWithLifecycle()
    val busy by viewModel.geminiBusy.collectAsStateWithLifecycle()
    val prefs = remember { com.jarvis.assistant.JarvisApp.instance.preferences }
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            BentoSectionHeader(
                title = "Gemini check",
                subtitle = "Sends a real request with your saved key • Live model ${prefs.aiModel.removePrefix("models/")} • voice ${prefs.voice}" +
                    if (prefs.apiKey.isBlank()) " • NO API KEY SAVED" else "",
                icon = Icons.Filled.Bolt
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CloudCapsuleButton(
                    text = "Test text",
                    enabled = busy == null,
                    onClick = { viewModel.testGeminiText() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                CloudCapsuleButton(
                    text = "Test Live voice",
                    enabled = busy == null,
                    onClick = { viewModel.testGeminiLive() },
                    modifier = Modifier.weight(1f)
                )
            }
            busy?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampGlow))
            }
            text?.let { r ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = (if (r.ok) "✓ " else "✗ ") + r.summary(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (r.ok) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.emberRed,
                        fontWeight = FontWeight.Bold
                    )
                )
                r.steps.forEach { step ->
                    Text(
                        text = (if (step.ok) "✓ " else "✗ ") + step.line(),
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                r.chatReply?.let { reply ->
                    Text(
                        text = "Chat path reply (${r.chatMs} ms): ${reply.take(200)}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                }
            }
            live?.let { r ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = (if (r.ok) "✓ " else "✗ ") + r.summary(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (r.ok) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.emberRed,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "Setup ${r.setupMs ?: "-"} ms • ${r.audioBytes} audio bytes • turn complete: ${r.turnCompleted}" +
                        (if (r.toolCalls.isNotEmpty()) " • tool calls: ${r.toolCalls.joinToString()}" else "") +
                        (r.error?.let { " • error: $it" } ?: "") +
                        " • states: ${r.states.joinToString(" > ")}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The last tool calls Jarvis made (all engines), with a link to the full Tool Debug page. */
@Composable
private fun RecentToolCallsCard(traces: List<com.jarvis.assistant.androllm.agent.trace.ToolExecutionTrace>, onOpen: () -> Unit) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BentoIconPlate(icon = Icons.Filled.Bolt)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Tool calls (${traces.size})",
                    color = bentoTokens().text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.TextButton(onClick = onOpen) {
                    Text("Tool Debug", color = MaterialTheme.ledger.lampGlow)
                }
            }
            if (traces.isEmpty()) {
                Text(
                    text = "No tool calls since Jarvis started. Ask \"torch on karo\" or use Tool Debug → Run.",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            traces.take(6).forEach { t ->
                Text(
                    text = "${if (t.status == "ok") "✓" else "✗"} ${t.toolName} • ${t.durationMs} ms • ${t.result.take(90)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (t.status == "ok") MaterialTheme.ledger.deskInk else MaterialTheme.ledger.emberRed
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SessionStatsHero(
    data: DeveloperData,
    modelLoaded: Boolean,
    modelName: String,
    providerLabel: String
) {
    val samples = data.speedHistory
    val hasSamples = samples.any { it > 0f }
    val last = data.lastTokensPerSecond
    val diff = last - data.avgTokensPerSecond
    val ramUsedMb = data.history.lastOrNull()?.ramUsedMb?.toInt() ?: 0
    val ramTotalMb = data.device?.totalRamMb ?: 0L
    val peak = data.peakTokensPerSecond.takeIf { it > 0f } ?: 1f

    DeveloperStatsCard(
        title = "Session Stats",
        timeFrame = "This session",
        heroLabel = "Throughput",
        heroValue = if (hasSamples) "${f1(last)} tok/s" else "—",
        heroDelta = if (hasSamples) {
            "${if (diff >= 0) "+" else "-"}${f1(kotlin.math.abs(diff))} tok/s vs avg"
        } else {
            "Run a local generation to begin"
        },
        heroDeltaTone = when {
            !hasSamples -> StatsDeltaTone.NEUTRAL
            diff >= 0 -> StatsDeltaTone.UP
            else -> StatsDeltaTone.DOWN
        },
        subStats = listOf(
            StatsCardSubStat(
                value = formatCompactTokens(data.totalTokens),
                label = "tokens",
                subLabel = "${data.generations.size} generations"
            ),
            StatsCardSubStat(
                value = "$ramUsedMb",
                label = "MB RAM",
                subLabel = "of $ramTotalMb MB"
            )
        ),
        rankTitle = if (modelLoaded) "LLAMA.CPP" else providerLabel.uppercase(),
        rankSubtitle = if (modelLoaded) modelName else "No on-device model loaded",
        chartTitle = "Session load",
        bars = samples.takeLast(24).map { sample ->
            val fraction = (sample / peak).coerceIn(0f, 1f)
            if (sample > 0f && fraction < 0.06f) 0.06f else fraction
        },
        chartLabel = "${samples.size} samples this session"
    )
}

private fun formatCompactTokens(n: Long): String = when {
    n >= 1_000_000L -> "${String.format(Locale.US, "%.1f", n / 1_000_000.0)}M"
    n >= 1_000L -> "${String.format(Locale.US, "%.1f", n / 1_000.0)}k"
    else -> n.toString()
}

@Composable
private fun DeviceSummaryCard(
    data: DeveloperData,
    modelName: String,
    modeLabel: String,
    engineState: LocalEngineState
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BentoIconPlate(icon = Icons.Filled.Troubleshoot, iconSize = 22.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = bentoTokens().text
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(data.device?.deviceModel ?: "Device")
                        append(" • Android ${data.device?.androidVersion ?: "?"}")
                        append(" • ${data.device?.cpuCores ?: 0} cores")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                CloudChip(
                    text = modeLabel,
                    accentColor = MaterialTheme.ledger.lampAmber,
                    icon = Icons.Filled.Bolt
                )
                Spacer(modifier = Modifier.height(6.dp))
                CloudChip(
                    text = when (engineState) {
                        is LocalEngineState.Ready -> "● Ready"
                        is LocalEngineState.Generating -> "● Generating"
                        is LocalEngineState.Loading -> "Loading…"
                        is LocalEngineState.Error -> "● Error"
                        is LocalEngineState.Uninitialized -> "Idle"
                    },
                    accentColor = when (engineState) {
                        is LocalEngineState.Ready, is LocalEngineState.Generating -> MaterialTheme.ledger.lampGlow
                        is LocalEngineState.Error -> MaterialTheme.ledger.emberRed
                        else -> MaterialTheme.ledger.lampDeep
                    },
                    icon = Icons.Filled.Memory
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    liveValue: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = bentoTokens().text,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(color = bentoTokens().muted)
                    )
                }
                Text(
                    text = liveValue,
                    color = bentoTokens().accent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

/** Dimmed card for AndroLLM panels Jarvis has no backend for. */
@Composable
private fun UnavailableCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.55f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            Icon(
                imageVector = Icons.Filled.Block,
                contentDescription = "Not available",
                tint = MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BackendDiagnosticsCard(
    data: DeveloperData,
    engineState: LocalEngineState,
    modelName: String,
    lastProvider: String,
    threads: Int,
    hardware: DeviceHardwareInfo?,
    systemMemory: SystemMemoryInfo?,
    onRefresh: () -> Unit
) {
    val last = data.lastGeneration
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Backend Diagnostics",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Speed, contentDescription = "Refresh diagnostics", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                }
            }

            DiagRow("Engine", "llama.cpp (on-device)")
            DiagRow("Engine state", DeveloperViewModel.engineStateLabel(engineState))
            (engineState as? LocalEngineState.Error)?.let { DiagRow("Engine error", it.message.take(60)) }
            DiagRow("Active model", modelName)
            DiagRow("Last provider", lastProvider.ifBlank { "—" })
            DiagRow("Threads (recommended)", threads.takeIf { it > 0 }?.toString() ?: "—")
            DiagRow("CPU ABI", hardware?.abi ?: "—")
            DiagRow("Vulkan", hardware?.let { if (it.isVulkanSupported) "Supported" else "Not supported" } ?: "—")
            DiagRow("Last model", last?.modelName?.ifBlank { null } ?: "—")
            DiagRow("First token latency", last?.firstTokenMs?.takeIf { it > 0 }?.let { "$it ms" } ?: "—")
            DiagRow("Total inference", last?.totalTimeMs?.let { "$it ms" } ?: "—")
            DiagRow("Prompt tokens", last?.promptTokens?.toString() ?: "—")
            DiagRow("Generated tokens", last?.generatedTokens?.toString() ?: "—")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f)
            )
            DiagRow(
                "System RAM",
                systemMemory?.let {
                    "${it.totalRamMb()} MB total • ${it.availableRamMb()} MB free" + if (it.lowMemory) " • LOW" else ""
                } ?: "—"
            )
            DiagRow(
                "Native heap",
                systemMemory?.let { "${it.nativeHeapAllocatedMb()} MB of ${it.nativeHeapSizeMb()} MB" } ?: "—"
            )
            DiagRow(
                "Free storage",
                data.device?.let { "${it.freeStorageBytes / (1024L * 1024L)} MB" } ?: "—"
            )
        }
    }
}

@Composable
private fun MemoryInspectorCard(
    snapshot: MemoryInspectorSnapshot?,
    onRefresh: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Memory Store",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh memory inspector", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                }
            }

            if (snapshot == null) {
                Text(
                    text = "Reading the memory store…",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            } else {
                MemorySnapshotBody(snapshot)
            }
        }
    }
}

@Composable
private fun MemorySnapshotBody(snapshot: MemoryInspectorSnapshot) {
    Column {
            if (!snapshot.enabled) {
                Text(
                    text = "Disabled in Settings",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.lampAmber,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatMini("Memories", snapshot.memoryCount.toString(), Modifier.weight(1f))
                StatMini("Categories", snapshot.categoryCount.toString(), Modifier.weight(1f))
            }

            if (snapshot.categories.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                snapshot.categories.take(6).forEach { (category, count) ->
                    DiagRow(category, count.toString())
                }
            }

            if (snapshot.recent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Context preview (most recent)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskInk
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                snapshot.recent.forEach { memory ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = memory.category.take(4).uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.lampGlow,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.width(44.dp)
                        )
                        Text(
                            text = memory.text.take(80),
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Retrieval timings, embeddings & extraction logs aren't tracked by Jarvis.",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
            )
    }
}

@Composable
private fun RuntimeRegistryCard(
    runtimes: List<RuntimeRow>,
    onRefresh: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Registered Runtimes",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh runtimes", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                }
            }

            if (runtimes.isEmpty()) {
                Text(
                    text = "Collecting runtime status…",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            } else {
                runtimes.forEachIndexed { index, runtime ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = runtime.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                CloudChip(text = runtime.category, accentColor = MaterialTheme.ledger.lampDeep)
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = runtime.summary,
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                            )
                            runtime.detail?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (runtime.available) "● Ready" else "● Off",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (runtime.available) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                            )
                        )
                    }
                    if (index < runtimes.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashGuardCard(
    crashes: List<EngineCrashGuard.CrashEvent>,
    onRefresh: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent guarded failures",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh crash log", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                }
            }
            if (crashes.isEmpty()) {
                Text(
                    text = "No engine failures recorded.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            } else {
                crashes.take(8).forEach { event ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "${timeFormat.format(Date(event.timestamp))} • ${event.stage} • ${event.errorType}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.emberRed,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = event.message.take(120),
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatMini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = bentoTokens().text,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskInk
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.ledger.deskPaper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
