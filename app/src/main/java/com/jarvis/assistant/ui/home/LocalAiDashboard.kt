package com.jarvis.assistant.ui.home

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.telemetry.TelemetryRepository
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.RevolutPerformanceChartCard
import com.jarvis.assistant.ui.compose.components.RevolutResourceGaugeCard
import com.jarvis.assistant.ui.compose.feature.home.HomeTelemetry
import com.jarvis.assistant.ui.compose.theme.ledger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.jarvis.assistant.ui.compose.designsystem.BentoIconPlate
import com.jarvis.assistant.ui.compose.designsystem.BentoSectionHeader
import com.jarvis.assistant.ui.compose.designsystem.bentoTokens

/** Fallback scope for the backend singletons (JarvisApp normally creates them first). */
private val dashboardFallbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Truthful snapshot of the optional LiteRT-LM engine (never constructs the engine graph). */
private data class LiteRtStatus(val enabled: Boolean, val loadedLabel: String?)

private fun readLiteRtStatus(context: Context): LiteRtStatus {
    val enabled = runCatching { AndroLlmFlags.isEnabled(context, AndroLlmFlags.LITERT_ENGINE) }.getOrDefault(false)
    val loaded = runCatching { AndroLlmEngines.peek()?.engine?.getLoadedModel() }.getOrNull()
    val label = loaded?.let { info ->
        val backend = when (info.backend.name) {
            "CPU" -> "CPU"
            "GPU" -> "GPU"
            "NPU", "QUALCOMM_QNN" -> "NPU"
            else -> info.backend.name
        }
        val accel = info.accelerator.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        "${info.id.ifBlank { "Model" }} on $backend$accel"
    }
    return LiteRtStatus(enabled, label)
}

/** Same live sources as AndroLLM's HomeViewModel, collected only while the section is shown. */
private fun dashboardFlow(context: Context): Flow<HomeTelemetry> {
    val app = context.applicationContext as Application
    val telemetry = TelemetryRepository.getInstance(app)
    val modelManager = LocalModelManager.getInstance(app, dashboardFallbackScope)
    val engine = LocalAiEngine.getInstance(app, dashboardFallbackScope)
    val turns: Flow<List<ChatTurn>> =
        runCatching { (app as? JarvisApp)?.chatRepository?.turns }.getOrNull() ?: flowOf(emptyList())
    val storageTotal = runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrDefault(0L)

    val modelPart = combine(modelManager.activeModelId, modelManager.downloadState, engine.engineState) { id, _, state ->
        Triple(id, runCatching { modelManager.isModelReady(id) }.getOrDefault(false), state)
    }.flowOn(Dispatchers.IO)

    return combine(telemetry.deviceMetrics, telemetry.history, modelPart, turns) { device, history, model, chat ->
        val descriptor = LocalAiConfig.getModelById(model.first)
        HomeTelemetry(
            deviceMetrics = device,
            history = history,
            activeModelId = model.first,
            activeModelName = descriptor?.name ?: model.first,
            activeModelSizeBytes = descriptor?.downloadSizeBytes ?: 0L,
            activeModelDownloaded = model.second,
            engineState = model.third,
            storageTotalBytes = storageTotal,
            recentTurns = chat.takeLast(3).asReversed(),
            totalTurns = chat.size
        )
    }
}

/**
 * On-device AI section of Jarvis' Home (merged from AndroLLM's home dashboard). Rendered inside a
 * ComposeView below the weather/telemetry cards. Every value is real runtime state; llama.cpp is
 * reported as CPU inference and LiteRT acceleration is only shown when a LiteRT model is loaded.
 */
@Composable
fun LocalAiDashboardSection(
    privacyMode: Boolean,
    onOpenModels: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenChat: () -> Unit
) {
    val context = LocalContext.current
    val flow = remember { dashboardFlow(context) }
    val telemetry by flow.collectAsState(initial = HomeTelemetry())
    var liteRt by remember { mutableStateOf(LiteRtStatus(false, null)) }

    // Sample hardware telemetry only while Home is started (ref-counted in TelemetryRepository).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val repo = TelemetryRepository.getInstance(context.applicationContext)
        var sampling = false
        fun start() { if (!sampling) { repo.startSampling(); sampling = true } }
        fun stop() { if (sampling) { repo.stopSampling(); sampling = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            liteRt = withContext(Dispatchers.Default) { readLiteRtStatus(context) }
            delay(3_000L)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ON-DEVICE AI",
            color = MaterialTheme.ledger.deskInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.96.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        ModelStatusCard(telemetry, onOpenModels)
        EngineHealthCard(telemetry, liteRt)
        RevolutResourceGaugeCard(
            ramUsedGb = telemetry.ramUsedMb / 1024f,
            ramTotalGb = telemetry.ramTotalMb / 1024f,
            tokensPerSecond = telemetry.tokensPerSecond,
            // llama.cpp runs on CPU here; device Vulkan support does not mean GPU inference is active.
            vulkanEnabled = false
        )
        RevolutPerformanceChartCard(
            dataPoints = telemetry.speedHistory.ifEmpty { listOf(0f, 0f) },
            subtitle = "Session tokens/sec — ${telemetry.speedHistory.size} samples"
        )
        StorageRow(telemetry)
        RecentConversations(telemetry, privacyMode, onOpenChat)
        QuickActions(onOpenModels, onOpenPrompts, onOpenMemory, onOpenVoice, onOpenDeveloper)
    }
}

@Composable
private fun ModelStatusCard(telemetry: HomeTelemetry, onClick: () -> Unit) {
    val state = telemetry.engineState
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                CloudChip(
                    text = when {
                        telemetry.isGenerating -> "Generating…"
                        state is LocalEngineState.Loading -> "Loading model…"
                        telemetry.isModelLoaded -> "Local model ready"
                        state is LocalEngineState.Error -> "Engine error"
                        telemetry.activeModelDownloaded -> "Model installed"
                        else -> "No local model"
                    },
                    accentColor = when {
                        state is LocalEngineState.Error -> MaterialTheme.ledger.emberRed
                        telemetry.isModelLoaded || telemetry.isGenerating -> MaterialTheme.ledger.lampDeep
                        else -> MaterialTheme.ledger.deskInk
                    },
                    icon = Icons.Filled.Memory
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (telemetry.activeModelDownloaded || telemetry.isModelLoaded) telemetry.activeModelName
                    else "Download a local model",
                    color = bentoTokens().text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        state is LocalEngineState.Error -> state.message
                        telemetry.isModelLoaded -> "Offline GGUF inference • tap to manage models"
                        telemetry.activeModelDownloaded -> "On device • loads on first offline use"
                        else -> "Tap to open the model manager"
                    },
                    color = MaterialTheme.ledger.deskInk,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BentoIconPlate(
                icon = Icons.Filled.Psychology,
                size = 48.dp,
                iconSize = 26.dp,
                tint = if (telemetry.isModelLoaded) bentoTokens().accent else bentoTokens().muted
            )
        }
    }
}

@Composable
private fun EngineHealthCard(telemetry: HomeTelemetry, liteRt: LiteRtStatus) {
    val state = telemetry.engineState
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BentoSectionHeader(title = "Engine health", subtitle = "Local engines on this phone", icon = Icons.Filled.Speed)
            EngineLine(
                name = "llama.cpp • CPU",
                status = when (state) {
                    is LocalEngineState.Generating -> "Generating"
                    is LocalEngineState.Ready -> "Loaded"
                    is LocalEngineState.Loading -> "Loading"
                    is LocalEngineState.Error -> "Error"
                    else -> if (telemetry.activeModelDownloaded) "Idle" else "No model"
                },
                good = telemetry.isModelLoaded,
                bad = state is LocalEngineState.Error
            )
            EngineLine(
                name = "LiteRT-LM",
                status = liteRt.loadedLabel
                    ?: if (liteRt.enabled) "Enabled • no model loaded" else "Off",
                good = liteRt.loadedLabel != null,
                bad = false
            )
        }
    }
}

@Composable
private fun EngineLine(name: String, status: String, good: Boolean, bad: Boolean) {
    val dot = when {
        bad -> MaterialTheme.ledger.emberRed
        good -> MaterialTheme.ledger.lampGlow
        else -> MaterialTheme.ledger.deskInk
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(name, color = MaterialTheme.ledger.deskPaper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(
            status,
            color = MaterialTheme.ledger.deskInk,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StorageRow(telemetry: HomeTelemetry) {
    val device = telemetry.deviceMetrics
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudGlassCard(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            MiniStat(
                title = "STORAGE",
                icon = Icons.Filled.Storage,
                value = if (device != null) "${formatBytes(device.freeStorageBytes)} free" else "—",
                fraction = telemetry.storageFreeFraction
            )
        }
        CloudGlassCard(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            MiniStat(
                title = "MODEL WEIGHTS",
                icon = Icons.Filled.Speed,
                value = when {
                    telemetry.isModelLoaded -> "Loaded • ${formatBytes(telemetry.activeModelSizeBytes)}"
                    telemetry.activeModelDownloaded -> "On device • ${formatBytes(telemetry.activeModelSizeBytes)}"
                    else -> "Not downloaded"
                },
                fraction = when {
                    telemetry.isModelLoaded -> 1f
                    telemetry.activeModelDownloaded -> 0.5f
                    else -> 0f
                }
            )
        }
    }
}

@Composable
private fun MiniStat(title: String, icon: ImageVector, value: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BentoIconPlate(icon = icon, size = 28.dp, iconSize = 15.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = bentoTokens().text2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            color = bentoTokens().text,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        val animated by animateFloatAsState(targetValue = fraction.coerceIn(0f, 1f), label = "miniStat")
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = bentoTokens().accentFill,
            trackColor = bentoTokens().plate
        )
    }
}

@Composable
private fun RecentConversations(telemetry: HomeTelemetry, privacyMode: Boolean, onOpenChat: () -> Unit) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenChat, contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RECENT CONVERSATIONS",
                    color = MaterialTheme.ledger.deskPaper,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.96.sp,
                    modifier = Modifier.weight(1f)
                )
                Text("${telemetry.totalTurns} turns", color = MaterialTheme.ledger.lampDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            when {
                privacyMode -> Text(
                    "Hidden while Privacy Mode is on",
                    color = MaterialTheme.ledger.deskInk,
                    fontSize = 12.sp
                )
                telemetry.recentTurns.isEmpty() -> Text(
                    "No conversations yet • tap to start a chat",
                    color = MaterialTheme.ledger.deskInk,
                    fontSize = 12.sp
                )
                else -> telemetry.recentTurns.forEach { turn -> TurnRow(turn) }
            }
        }
    }
}

@Composable
private fun TurnRow(turn: ChatTurn) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.ledger.lampGlow,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                turn.userTranscript.ifBlank { "Conversation" },
                color = MaterialTheme.ledger.deskPaper,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                turn.jarvisResponse,
                color = MaterialTheme.ledger.deskInk,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(relativeTime(turn.timestamp), color = MaterialTheme.ledger.deskInk, fontSize = 10.sp)
    }
}

@Composable
private fun QuickActions(
    onOpenModels: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudCapsuleButton(text = "Models", onClick = onOpenModels, icon = Icons.Filled.Download, modifier = Modifier.weight(1f))
            CloudCapsuleButton(text = "Prompts", onClick = onOpenPrompts, icon = Icons.Filled.AutoAwesome, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudCapsuleButton(text = "Memory", onClick = onOpenMemory, icon = Icons.Filled.Psychology, modifier = Modifier.weight(1f))
            CloudCapsuleButton(text = "Voice", onClick = onOpenVoice, icon = Icons.Filled.Mic, modifier = Modifier.weight(1f))
        }
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudCapsuleButton(
                text = "Code Studio",
                onClick = { com.jarvis.assistant.codestudio.CodeStudioActivity.open(context) },
                icon = Icons.Filled.Terminal,
                modifier = Modifier.weight(1f)
            )
            CloudCapsuleButton(text = "Developer", onClick = onOpenDeveloper, icon = Icons.Filled.Code, modifier = Modifier.weight(1f))
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return "now"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
