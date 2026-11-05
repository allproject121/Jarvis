package com.jarvis.assistant.ui.compose.feature.home

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.ui.compose.components.CardSkeletonRow
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.PromptStudioCarousel
import com.jarvis.assistant.ui.compose.components.RevolutPerformanceChartCard
import com.jarvis.assistant.ui.compose.components.RevolutResourceGaugeCard
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home hub — ported from AndroLLM feature/home. Every metric is real runtime
 * telemetry from Jarvis' TelemetryRepository / LocalModelManager / LocalAiEngine.
 */
@Composable
fun HomeRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: HomeViewModel = viewModel { HomeViewModel(application) }
    HomeScreen(viewModel = viewModel, navigate = navigate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: HomeViewModel,
    navigate: (String) -> Unit
) {
    val telemetryOrNull by viewModel.telemetry.collectAsStateWithLifecycle()
    val isLoading = telemetryOrNull == null
    val telemetry = telemetryOrNull ?: HomeTelemetry()

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.HOME,
            onTabSelected = { tab ->
                if (tab.route != ComposeRoutes.HOME) navigate(tab.route)
            },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudBugdroidLogo(size = 30.dp, showMoon = false)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Jarvis",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                                Text(
                                    text = greetingForTimeOfDay(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.ledger.lampDeep,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { navigate(ComposeRoutes.PROFILE) }) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = "Profile",
                                tint = MaterialTheme.ledger.deskPaperDim
                            )
                        }
                        IconButton(onClick = { navigate(ComposeRoutes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.ledger.deskPaperDim
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            if (isLoading) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(6) { index ->
                        CardSkeletonRow(
                            height = when (index) {
                                0 -> 132.dp
                                1 -> 150.dp
                                2 -> 180.dp
                                else -> 96.dp
                            }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1. Model status island — real engine lifecycle + active model
                    item(key = "model_status") {
                        StaggeredEntrance(index = 0) {
                            ModelStatusIsland(
                                telemetry = telemetry,
                                onManageModels = { navigate(ComposeRoutes.MODELS) }
                            )
                        }
                    }

                    // 2. Live hardware gauges — real RAM + real tokens/sec
                    item(key = "gauges") {
                        StaggeredEntrance(index = 1) {
                            RevolutResourceGaugeCard(
                                ramUsedGb = telemetry.ramUsedMb / 1024f,
                                ramTotalGb = telemetry.ramTotalMb / 1024f,
                                tokensPerSecond = telemetry.tokensPerSecond,
                                // Jarvis' on-device engine (llama.cpp) runs on CPU; the phone merely supporting Vulkan does not
                                // mean GPU acceleration is in use (shown separately in Developer → Vulkan).
                                vulkanEnabled = false
                            )
                        }
                    }

                    // 3. Live performance waveform — real tokens/sec history
                    item(key = "perf_chart") {
                        StaggeredEntrance(index = 2) {
                            RevolutPerformanceChartCard(
                                dataPoints = telemetry.speedHistory.ifEmpty { listOf(0f, 0f) },
                                subtitle = "Session tokens/sec — ${telemetry.speedHistory.size} samples"
                            )
                        }
                    }

                    // 4. Storage + active model weights
                    item(key = "system_status") {
                        StaggeredEntrance(index = 3) {
                            SystemStatusRow(telemetry = telemetry)
                        }
                    }

                    // 5. Quick action capsules — the hub
                    item(key = "quick_actions") {
                        StaggeredEntrance(index = 4) {
                            QuickActionsGrid(navigate = navigate)
                        }
                    }

                    // 6. Prompt Studio carousel
                    item(key = "prompt_studio") {
                        StaggeredEntrance(index = 5) {
                            Column {
                                SectionHeader(
                                    title = "Prompt Studio",
                                    subtitle = "One-tap AI templates & presets",
                                    trailing = {
                                        Text(
                                            text = "Open",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                color = MaterialTheme.ledger.lampDeep,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { navigate(ComposeRoutes.PROMPTS) }
                                                .padding(4.dp)
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                PromptStudioCarousel(
                                    onPromptSelected = { _ -> navigate(ComposeRoutes.PROMPTS) }
                                )
                            }
                        }
                    }

                    // 7. Recent conversations
                    item(key = "activity_header") {
                        StaggeredEntrance(index = 6) {
                            SectionHeader(
                                title = "Activity & Chats",
                                subtitle = "Your recent Jarvis conversations",
                                trailing = {
                                    CloudChip(
                                        text = if (telemetry.isModelLoaded) "Offline Ready" else "${telemetry.totalTurns} turns",
                                        accentColor = MaterialTheme.ledger.lampDeep
                                    )
                                }
                            )
                        }
                    }

                    val turns = telemetry.recentTurns
                    if (turns.isEmpty()) {
                        item(key = "empty_chats") {
                            StaggeredEntrance(index = 7) {
                                EmptyChatsIsland(onStartChat = { navigate(ComposeRoutes.CHAT) })
                            }
                        }
                    } else {
                        items(turns, key = { it.id }) { turn ->
                            StaggeredEntrance(index = 0, instant = true) {
                                ChatActivityCard(
                                    turn = turn,
                                    onClick = { navigate(ComposeRoutes.CHAT) }
                                )
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStatusIsland(
    telemetry: HomeTelemetry,
    onManageModels: () -> Unit
) {
    val state = telemetry.engineState
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onManageModels
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                CloudChip(
                    text = when {
                        telemetry.isGenerating -> "Generating…"
                        state is LocalEngineState.Loading -> "Loading Model…"
                        telemetry.isModelLoaded -> "llama.cpp Engine Ready"
                        state is LocalEngineState.Error -> "Engine Error"
                        telemetry.activeModelDownloaded -> "Model Installed"
                        else -> "No Model Installed"
                    },
                    accentColor = when {
                        state is LocalEngineState.Error -> MaterialTheme.ledger.emberRed
                        telemetry.isModelLoaded || telemetry.isGenerating -> MaterialTheme.ledger.lampDeep
                        else -> MaterialTheme.ledger.deskInk
                    },
                    icon = Icons.Filled.Memory
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        telemetry.activeModelDownloaded || telemetry.isModelLoaded -> telemetry.activeModelName
                        else -> "Download a Local Model to Begin"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        telemetry.isModelLoaded -> "Zero cloud dependency • on-device GGUF inference"
                        state is LocalEngineState.Error -> state.message
                        telemetry.activeModelDownloaded -> "Weights verified on device • loads on first offline use"
                        else -> "Tap to explore the model catalog & download"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (telemetry.isModelLoaded) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun SystemStatusRow(telemetry: HomeTelemetry) {
    val device = telemetry.deviceMetrics
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            StatusMiniCard(
                title = "Storage",
                icon = Icons.Filled.Storage,
                value = if (device != null) "${formatBytes(device.freeStorageBytes)} free" else "—",
                fraction = telemetry.storageFreeFraction,
                accent = MaterialTheme.ledger.lampDeep
            )
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            StatusMiniCard(
                title = "Model Weights",
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
                },
                accent = if (telemetry.isModelLoaded) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.lampDeep
            )
        }
    }
}

@Composable
private fun StatusMiniCard(
    title: String,
    icon: ImageVector,
    value: String,
    fraction: Float,
    accent: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskInk
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.ledger.deskPaper
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        val animatedFraction by animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            label = "statusFraction"
        )
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = accent,
            trackColor = MaterialTheme.ledger.deskHairline
        )
    }
}

/** Hub actions — every destination is a ComposeRoutes constant. */
@Composable
private fun QuickActionsGrid(navigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudCapsuleButton(
            text = "Jarvis HUD · Orb, Companion, God's Eye",
            onClick = { navigate(ComposeRoutes.CLASSIC_HUD) },
            icon = Icons.Filled.Dashboard,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "New Chat",
                onClick = { navigate(ComposeRoutes.CHAT) },
                icon = Icons.Filled.Add,
                modifier = Modifier.weight(1.2f)
            )
            CloudCapsuleButton(
                text = "Browse Models",
                onClick = { navigate(ComposeRoutes.MODELS) },
                icon = Icons.Filled.Download,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "Prompt Studio",
                onClick = { navigate(ComposeRoutes.PROMPTS) },
                icon = Icons.Filled.Psychology,
                modifier = Modifier.weight(1f)
            )
            CloudCapsuleButton(
                text = "Developer Mode",
                onClick = { navigate(ComposeRoutes.DEVELOPER) },
                icon = Icons.Filled.Speed,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "Voice",
                onClick = { navigate(ComposeRoutes.VOICE) },
                icon = Icons.Filled.Mic,
                modifier = Modifier.weight(1f)
            )
            CloudCapsuleButton(
                text = "Cloud",
                onClick = { navigate(ComposeRoutes.CLOUD) },
                icon = Icons.Filled.Cloud,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "Profile",
                onClick = { navigate(ComposeRoutes.PROFILE) },
                icon = Icons.Filled.Person,
                modifier = Modifier.weight(1f)
            )
            CloudCapsuleButton(
                text = "Settings",
                onClick = { navigate(ComposeRoutes.SETTINGS) },
                icon = Icons.Filled.Settings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyChatsIsland(onStartChat: () -> Unit) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStartChat
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No Conversations Yet",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start a new conversation with Jarvis",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            CloudCapsuleButton(
                text = "Start First Chat",
                onClick = onStartChat,
                icon = Icons.Filled.Add
            )
        }
    }
}

/** A Jarvis chat turn as an entry in the activity ledger (port of AndroLLM ChatActivityCard). */
@Composable
private fun ChatActivityCard(
    turn: ChatTurn,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTime = rememberFormattedTime(turn.timestamp)
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampGlow,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = turn.userTranscript.ifBlank { "New Conversation" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.08f))
                            .border(1.dp, MaterialTheme.ledger.deskPaper.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "JARVIS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                            color = MaterialTheme.ledger.deskPaperDim
                        )
                    }
                    Text(
                        text = turn.jarvisResponse,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFormattedTime(timestamp: Long): String = remember(timestamp) {
    if (timestamp <= 0) {
        "Just now"
    } else {
        val diff = System.currentTimeMillis() - timestamp
        when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun greetingForTimeOfDay(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning — your private AI is ready"
        in 12..16 -> "Good afternoon — your private AI is ready"
        in 17..21 -> "Good evening — your private AI is ready"
        else -> "Good night — your private AI is ready"
    }
}
