package com.jarvis.assistant.ui.compose.feature.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ai.cloud.CloudUsageStats
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.bounceClick
import com.jarvis.assistant.ui.compose.theme.ledger
import java.util.Locale

// ── Models page ────────────────────────────────────────────────────────────

/**
 * Model browser for one provider (port of AndroLLM's CloudModelsScreen).
 * Selectable rows write the real Jarvis preference (Gemini Live model /
 * Ollama model tag); discovered-but-unsupported models are informational.
 */
@Composable
internal fun CloudModelsPage(
    provider: CloudProviderId,
    uiState: CloudUiState,
    onSetGeminiModel: (String) -> Unit,
    onSetLocalModel: (String) -> Unit
) {
    val discovered = uiState.health[provider]?.modelIds.orEmpty()

    // (id, selectable, isDefault)
    val selectable: List<String>
    val selected: String
    val informational: List<String>
    val hint: String
    when (provider) {
        CloudProviderId.GEMINI -> {
            selectable = uiState.geminiModels
            selected = uiState.geminiModel
            informational = discovered.filter { it !in uiState.geminiModels }
            hint = "Tap a model to make it the Gemini Live default."
        }
        CloudProviderId.OPENAI -> {
            selectable = emptyList()
            selected = uiState.openAiModel
            informational = discovered
            hint = "Jarvis' ChatGPT client always uses ${uiState.openAiModel}; model selection isn't configurable."
        }
        CloudProviderId.LOCAL_HOST -> {
            selectable = (listOf(uiState.localModel).filter { it.isNotBlank() } + discovered).distinct()
            selected = uiState.localModel
            informational = emptyList()
            hint = "Tap a model to make it the Local Host default."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StaggeredEntrance(0) {
                Text(
                    text = hint,
                    color = MaterialTheme.ledger.deskInk,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (provider == CloudProviderId.OPENAI) {
            item {
                StaggeredEntrance(0, instant = true) {
                    ModelRow(
                        id = selected,
                        label = selected,
                        meta = "Used by Jarvis",
                        isDefault = true,
                        enabled = true,
                        onClick = null
                    )
                }
            }
        }

        items(selectable, key = { "sel_$it" }) { model ->
            StaggeredEntrance(0, instant = true) {
                ModelRow(
                    id = model,
                    label = CloudViewModel.prettyModel(model),
                    meta = if (model != CloudViewModel.prettyModel(model)) model else null,
                    isDefault = model == selected,
                    enabled = true,
                    onClick = {
                        if (provider == CloudProviderId.GEMINI) onSetGeminiModel(model) else onSetLocalModel(model)
                    }
                )
            }
        }

        if (informational.isNotEmpty()) {
            item {
                StaggeredEntrance(1) {
                    SectionHeader(
                        title = "Discovered on your key",
                        subtitle = "${informational.size} models · not selectable in Jarvis"
                    )
                }
            }
            items(informational, key = { "info_$it" }) { model ->
                StaggeredEntrance(0, instant = true) {
                    ModelRow(
                        id = model,
                        label = CloudViewModel.prettyModel(model),
                        meta = null,
                        isDefault = false,
                        enabled = false,
                        onClick = null
                    )
                }
            }
        }

        if (discovered.isEmpty()) {
            item {
                StaggeredEntrance(2) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = if (uiState.hasKey(provider)) "No models discovered yet" else "Not configured",
                                color = MaterialTheme.ledger.deskPaper,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (uiState.hasKey(provider)) {
                                    "Refresh (top right) to pull the live model list from ${provider.title}."
                                } else {
                                    "Add a key for ${provider.title} in Cloud Brains first, then refresh to browse its models."
                                },
                                color = MaterialTheme.ledger.deskInk,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ModelRow(
    id: String,
    label: String,
    meta: String?,
    isDefault: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?
) {
    val base = Modifier
        .fillMaxWidth()
        .alpha(if (enabled) 1f else 0.55f)
    CloudGlassCard(
        modifier = if (onClick != null) base.bounceClick(onClick = onClick) else base,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = MaterialTheme.ledger.deskPaper,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!meta.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta,
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isDefault) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Default chat model",
                        color = MaterialTheme.ledger.lampAmber,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (isDefault) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Default: $id",
                        tint = MaterialTheme.ledger.lampAmber,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ── Usage page ─────────────────────────────────────────────────────────────

/**
 * Port of AndroLLM's Cloud Usage dashboard. Session counters come from Jarvis'
 * own CloudUsageMeter (all routed cloud calls); the analytics below come from
 * the AndroLLM gateway's persistent usage meter (tokens, cost, latency, cache,
 * per-provider / per-model breakdowns, history and CSV export).
 */
@Composable
internal fun CloudUsagePage(
    usage: CloudUsageStats,
    uiState: CloudUiState,
    gatewayUsage: com.jarvis.assistant.androllm.cloud.usage.CloudUsageSnapshot,
    onClearGatewayUsage: () -> Unit,
    onExportGatewayUsage: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StaggeredEntrance(1) {
                SectionHeader(
                    title = "Session Counters",
                    subtitle = "From Jarvis' CloudUsageMeter · resets on app restart"
                )
            }
        }

        item {
            StaggeredEntrance(1) {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Metric("Requests", usage.totalRequests.toString(), Modifier.weight(1f))
                            Metric("Tokens", formatCount(usage.totalTokens), Modifier.weight(1f))
                            Metric(
                                "Est. cost",
                                "$" + String.format(Locale.US, "%.4f", usage.totalEstimatedCostUsd),
                                Modifier.weight(1f)
                            )
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Metric("Prompt", formatCount(usage.totalPromptTokens), Modifier.weight(1f))
                            Metric("Completion", formatCount(usage.totalCompletionTokens), Modifier.weight(1f))
                            Metric(
                                "Last used",
                                uiState.lastActiveProvider.ifBlank { "—" },
                                Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "Only calls that report to CloudUsageMeter are counted (gateway calls included).",
                            color = MaterialTheme.ledger.deskInkFaint,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        if (gatewayUsage.total.requests == 0) {
            item {
                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Insights,
                            contentDescription = null,
                            tint = MaterialTheme.ledger.deskInk
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "No gateway requests recorded yet. Add a custom provider, enable the gateway " +
                                "and select the Cloud Gateway routing mode to collect analytics.",
                            color = MaterialTheme.ledger.deskInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        gatewayUsageItems(
            snapshot = gatewayUsage,
            onClear = onClearGatewayUsage,
            onExport = onExportGatewayUsage
        )

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            color = MaterialTheme.ledger.deskPaper,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = MaterialTheme.ledger.deskInk,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

