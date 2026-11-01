package com.jarvis.assistant.ui.compose.feature.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.androllm.runtime.Runtime
import com.jarvis.assistant.androllm.runtime.RuntimeRegistry
import com.jarvis.assistant.androllm.runtime.RuntimeStatus
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * "Runtime Registry" developer card (AndroLLM core/runtime view): every Jarvis
 * subsystem — Gemini Live config, GGUF + LiteRT engines, cloud gateway, offline
 * voice, memory, agent tools, MCP, UI automation — with a live availability
 * snapshot grouped by category. Read-only: the registry never starts or stops
 * anything.
 *
 * Self-contained (no ViewModel dependency): add `RuntimeRegistryCard()` to the
 * Developer screen's column to show it.
 */
@Composable
internal fun RuntimeRegistryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val registry = remember { RuntimeRegistry.getInstance(context) }
    var statuses by remember { mutableStateOf<List<Pair<Runtime, RuntimeStatus>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        statuses = runCatching { registry.statuses() }.getOrDefault(emptyList())
        loading = false
    }

    CloudGlassCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Runtime Registry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                    val up = statuses.count { it.second.available }
                    Text(
                        "$up/${registry.size} subsystems available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.ledger.deskInk)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            statuses.groupBy { it.first.category }
                .toSortedMap(compareBy { it.ordinal })
                .forEach { (category, entries) ->
                    Text(
                        category.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.ledger.lampGlow,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                    entries.forEachIndexed { index, (runtime, status) ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.ledger.deskHairlineSoft)
                        RuntimeRow(runtime, status)
                    }
                }
        }
    }
}

@Composable
private fun RuntimeRow(runtime: Runtime, status: RuntimeStatus) {
    val dotColor = when {
        status.available -> MaterialTheme.ledger.revolutNeonEmerald
        status.disabledByUser -> MaterialTheme.ledger.deskInkFaint
        else -> MaterialTheme.ledger.emberRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("●", color = dotColor, style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                runtime.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.ledger.deskPaper
            )
            Text(
                status.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            status.detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.deskInkFaint,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
