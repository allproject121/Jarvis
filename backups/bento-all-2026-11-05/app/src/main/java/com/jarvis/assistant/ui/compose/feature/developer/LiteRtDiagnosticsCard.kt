package com.jarvis.assistant.ui.compose.feature.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.androllm.engine.api.EngineState
import com.jarvis.assistant.androllm.engine.backend.BackendCapabilities
import com.jarvis.assistant.androllm.engine.models.BackendBenchmarkResult
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import java.util.Locale

/**
 * Real diagnostics for the optional AndroLLM LiteRT-LM engine: hardware
 * backend probe (CPU / GPU / NPU), engine lifecycle state, loaded model and
 * backend, last-generation throughput, KV-cache occupancy, and a cross-backend
 * benchmark (NPU → GPU → CPU) using the engine's own benchmark routine.
 */
@Composable
internal fun LiteRtDiagnosticsCard(viewModel: DeveloperViewModel) {
    val state by viewModel.liteRtEngineState.collectAsStateWithLifecycle()
    val stats by viewModel.liteRtStats.collectAsStateWithLifecycle()
    val memory by viewModel.liteRtMemory.collectAsStateWithLifecycle()
    val caps by viewModel.liteRtCaps.collectAsStateWithLifecycle()
    val models by viewModel.liteRtModels.collectAsStateWithLifecycle()
    val benchmarks by viewModel.liteRtBenchmarks.collectAsStateWithLifecycle()
    val busy by viewModel.liteRtBusy.collectAsStateWithLifecycle()
    val message by viewModel.liteRtMessage.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.refreshLiteRt() }

    val loaded = viewModel.liteRt.engine.getLoadedModel()
    val selectedPath = viewModel.liteRt.selectedModelPath
    val selected = models.firstOrNull { it.file.absolutePath == selectedPath } ?: models.firstOrNull()

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LiteRT-LM Engine",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = "Optional AndroLLM engine • backend accelerators & benchmark",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                if (busy != null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.refreshLiteRt() }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh LiteRT diagnostics",
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            AcceleratorChips(caps)
            Spacer(modifier = Modifier.height(8.dp))

            LrRow("Engine state", DeveloperViewModel.liteRtStateLabel(state))
            (state as? EngineState.Failed)?.let { failed ->
                LrRow("Failure", failed.message.take(120))
                failed.suggestion?.let { LrRow("Suggestion", it.take(120)) }
            }
            LrRow("Feature flag", if (viewModel.liteRt.isFeatureEnabled()) "On" else "Off (enable in Settings → Engines)")
            LrRow("Backend preference", viewModel.liteRt.backendPreference.name)
            LrRow("Models found", "${models.size} in ${viewModel.liteRt.modelsDir().name}/")
            LrRow("Selected model", selected?.name ?: "—")
            LrRow("Loaded model", loaded?.id ?: "—")
            if (loaded != null) {
                LrRow("Active backend", loaded.backend.name)
                LrRow("Accelerator", listOf(loaded.vendor, loaded.accelerator).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "—" })
                LrRow("Delegate", loaded.delegate.ifBlank { "—" })
                LrRow("Backend init", if (loaded.backendInitMs > 0) "${loaded.backendInitMs} ms" else "—")
                LrRow("Family / template", listOf(loaded.family, loaded.templateSource).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "—" })
                LrRow("Context length", loaded.contextLength.takeIf { it > 0 }?.toString() ?: "—")
            }
            memory?.let { m ->
                LrRow("KV cache", if (m.kvCacheTokens >= 0) "${m.kvCacheTokens} tokens" else "not readable")
                LrRow("Model size", "${m.modelSizeBytes / (1024L * 1024L)} MB")
            }
            stats?.let { s ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f)
                )
                LrRow("Decode speed", "${lrF1(s.decodeTokensPerSecond.takeIf { it > 0f } ?: s.tokensPerSecond)} tok/s")
                LrRow("Prefill speed", if (s.promptTokensPerSecond > 0f) "${lrF1(s.promptTokensPerSecond)} tok/s" else "—")
                LrRow("First token", if (s.firstTokenMs > 0) "${s.firstTokenMs} ms" else "—")
                LrRow("Tokens (prompt / gen)", "${s.promptTokens} / ${s.generatedTokens}")
                LrRow("Stop reason", s.stopReason.ifBlank { "—" })
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.loadLiteRtModel() },
                    enabled = busy == null && selected != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Load", maxLines = 1) }
                OutlinedButton(
                    onClick = { viewModel.unloadLiteRtModel() },
                    enabled = busy == null && loaded != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Unload", maxLines = 1) }
                OutlinedButton(
                    onClick = { viewModel.benchmarkLiteRtBackends() },
                    enabled = busy == null && selected != null,
                    modifier = Modifier.weight(1.3f)
                ) { Text("Benchmark", maxLines = 1) }
            }
            busy?.let {
                Text(
                    text = "$it…",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampGlow)
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected == null) {
                Text(
                    text = "No .litertlm model in ${viewModel.liteRt.modelsDir().absolutePath} — download one from the Models screen.",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }

            if (benchmarks.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f)
                )
                Text(
                    text = "Backend benchmark",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                benchmarks.forEach { BenchmarkRow(it) }
            }

            val crashes = viewModel.liteRtCrashes()
            if (crashes.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f)
                )
                Text(
                    text = "LiteRT crash guard (${crashes.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.emberRed
                    )
                )
                crashes.takeLast(4).asReversed().forEach { e ->
                    Text(
                        text = "${e.stage} • ${e.backend.ifBlank { "-" }} • ${e.errorType}: ${e.message}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AcceleratorChips(caps: BackendCapabilities?) {
    if (caps == null) {
        Text(
            text = "Probing accelerators…",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        CloudChip(
            text = "CPU ✓",
            accentColor = MaterialTheme.ledger.lampGlow
        )
        CloudChip(
            text = "GPU " + if (caps.gpuAvailable) "✓" else "✗",
            accentColor = if (caps.gpuAvailable) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
        )
        CloudChip(
            text = "NPU " + when {
                caps.npuUsable -> "✓"
                caps.npuAvailable -> "no driver"
                else -> "✗"
            },
            accentColor = if (caps.npuUsable) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
        )
    }
    Spacer(modifier = Modifier.width(4.dp))
    LrRow("GPU", listOfNotNull(caps.gpuVendor, caps.gpuName).joinToString(" ").ifBlank { "—" })
    LrRow("NPU", if (caps.npuAvailable) caps.npuDisplayName + (caps.npuVendor?.let { " ($it)" } ?: "") else "—")
    LrRow("NNAPI", if (caps.nnApiAvailable) "Available" else "Not available")
    caps.probeError?.takeIf { it.isNotBlank() }?.let { LrRow("Probe note", it.take(100)) }
}

@Composable
private fun BenchmarkRow(r: BackendBenchmarkResult) {
    val label = r.backendLabel.ifBlank { r.backend.name }
    val text = if (r.succeeded) {
        "$label: ${lrF1(r.averageTokensPerSecond)} tok/s avg • peak ${lrF1(r.peakTokensPerSecond)} • " +
            "TTFT ${r.firstTokenMs} ms • init ${r.initTimeMs} ms • ${lrF1(r.peakRamMb)} MB"
    } else {
        "$label: failed — ${r.error.take(80)}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            color = if (r.succeeded) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.emberRed
        ),
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun LrRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.ledger.deskPaper,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun lrF1(value: Float): String = String.format(Locale.US, "%.1f", value)
