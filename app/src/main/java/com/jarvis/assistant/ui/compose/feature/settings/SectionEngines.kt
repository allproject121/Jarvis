package com.jarvis.assistant.ui.compose.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.engine.LocalLiteRtModel
import com.jarvis.assistant.androllm.engine.api.EngineState
import com.jarvis.assistant.androllm.engine.backend.BackendCapabilities
import com.jarvis.assistant.androllm.engine.models.BackendType
import com.jarvis.assistant.androllm.engine.support.Result
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.feature.cloud.GatewayProvidersCard
import com.jarvis.assistant.ui.compose.feature.cloud.GatewayViewModel
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jarvis.assistant.ui.compose.designsystem.BentoPillShape
import com.jarvis.assistant.ui.compose.designsystem.bentoSecondaryBorder
import com.jarvis.assistant.ui.compose.designsystem.bentoSecondaryButtonColors

/**
 * "Engines" settings section (AndroLLM area A):
 *  - optional LiteRT-LM on-device engine: feature flag, model selection from
 *    AndroLlmPaths.liteRtModelsDir, backend preference AUTO / CPU / GPU / NPU,
 *    load / unload, and "use as chat brain" (router mode LOCAL_LITERT);
 *  - optional OpenAI-compatible cloud gateway: providers, models and the
 *    CLOUD_GATEWAY routing mode.
 *
 * Everything defaults to OFF; Gemini Live and the existing brains stay the default.
 */
@Composable
fun EnginesSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val engines = remember { AndroLlmEngines.getInstance(app) }
    val scope = rememberCoroutineScope()
    val router = remember { runCatching { JarvisApp.instance.modelRouter }.getOrNull() }
    val fallbackMode = remember { kotlinx.coroutines.flow.MutableStateFlow(AiModelMode.AUTO_HYBRID) }
    val currentMode by (router?.currentMode ?: fallbackMode).collectAsState()

    var liteRtEnabled by remember { mutableStateOf(engines.isFeatureEnabled()) }
    var backend by remember { mutableStateOf(engines.backendPreference) }
    var selectedPath by remember { mutableStateOf(engines.selectedModelPath) }
    var models by remember { mutableStateOf<List<LocalLiteRtModel>>(emptyList()) }
    var caps by remember { mutableStateOf<BackendCapabilities?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showBackendPicker by remember { mutableStateOf(false) }
    val engineState by engines.repository.engineState.collectAsState()

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { runCatching { engines.listLocalModels() }.getOrDefault(emptyList()) }
        caps = engines.probeHardware()
    }

    val selectedModel = models.firstOrNull { it.file.absolutePath == selectedPath } ?: models.firstOrNull()

    fun setMode(mode: AiModelMode) {
        router?.setMode(mode)
        runCatching { JarvisApp.instance.preferences.aiModelMode = mode.name }
        status = "Chat brain: ${mode.displayName}"
    }

    var offlineBrainFailover by remember {
        mutableStateOf(AndroLlmFlags.isEnabled(context, AndroLlmFlags.OFFLINE_BRAIN_FAILOVER, default = true))
    }
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        ToggleRow(
            icon = Icons.Filled.Route,
            title = "Offline Brain when Gemini fails",
            subtitle = "If Gemini stops working for any reason (bad or expired API key, quota, server or network error), " +
                "Jarvis keeps talking and doing phone tasks with the on-device model (Qwen3 4B recommended).",
            checked = offlineBrainFailover,
            onCheckedChange = {
                offlineBrainFailover = it
                AndroLlmFlags.setEnabled(context, AndroLlmFlags.OFFLINE_BRAIN_FAILOVER, it)
            }
        )
    }
    Spacer(Modifier.height(12.dp))

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = "LiteRT-LM on-device engine",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.ledger.deskPaper,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Text(
                text = "Optional secondary engine for .litertlm models with NPU / GPU / CPU acceleration. " +
                    "Jarvis' llama.cpp engine and Gemini Live are unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            ToggleRow(
                icon = Icons.Filled.Memory,
                title = "Enable LiteRT-LM engine",
                subtitle = "Off by default. Required for the \"LiteRT-LM On-Device\" routing mode.",
                checked = liteRtEnabled,
                onCheckedChange = {
                    engines.setFeatureEnabled(it)
                    liteRtEnabled = it
                    if (!it && currentMode == AiModelMode.LOCAL_LITERT) setMode(AiModelMode.AUTO_HYBRID)
                }
            )
            SettingRow(
                icon = Icons.Filled.Storage,
                title = "Model",
                value = selectedModel?.let { "${it.name} • ${formatBytes(it.sizeBytes)}" }
                    ?: "None in models/litert",
                enabled = liteRtEnabled && models.isNotEmpty(),
                onClick = { showModelPicker = true }
            )
            SettingRow(
                icon = Icons.Filled.DeveloperBoard,
                title = "Accelerator",
                value = backendLabel(backend, caps),
                enabled = liteRtEnabled,
                onClick = { showBackendPicker = true }
            )
            caps?.let { c ->
                Text(
                    text = "Detected: CPU ✓ • GPU ${if (c.gpuAvailable) "✓" else "✗"}" +
                        (c.gpuName?.let { " ($it)" } ?: "") +
                        " • NPU ${if (c.npuUsable) "✓ ${c.npuDisplayName}" else if (c.npuAvailable) "no vendor driver" else "✗"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.deskInkFaint,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            Text(
                text = "Engine: " + engineStateText(engineState),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.ledger.deskInk,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                OutlinedButton(shape = BentoPillShape, border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(),
                    enabled = liteRtEnabled && selectedModel != null && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { engines.loadSelected(selectedModel) }
                            status = when (r) {
                                is Result.Success -> "Loaded on ${engines.engine.getLoadedModel()?.backend?.name ?: "-"}"
                                is Result.Error -> "Load failed: ${r.exception.message}"
                            }
                            busy = false
                        }
                    }
                ) { Text("Load") }
                OutlinedButton(shape = BentoPillShape, border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(),
                    enabled = !busy && engines.isModelLoaded(),
                    onClick = {
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { engines.unload() }
                            status = "Unloaded"
                            busy = false
                        }
                    }
                ) { Text("Unload") }
                OutlinedButton(shape = BentoPillShape, border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(),
                    enabled = liteRtEnabled && currentMode != AiModelMode.LOCAL_LITERT,
                    onClick = { setMode(AiModelMode.LOCAL_LITERT) }
                ) { Text("Use for chat") }
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), strokeWidth = 2.dp)
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.lampGlow,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            SettingRow(
                icon = Icons.Filled.Route,
                title = "Current chat routing mode",
                value = currentMode.displayName,
                onClick = {}
            )
            if (currentMode == AiModelMode.LOCAL_LITERT || currentMode == AiModelMode.CLOUD_GATEWAY) {
                OutlinedButton(shape = BentoPillShape, border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(),
                    onClick = { setMode(AiModelMode.AUTO_HYBRID) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("Restore Auto Hybrid") }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    val gatewayVm: GatewayViewModel = viewModel { GatewayViewModel(app) }
    val gatewayEnabled by gatewayVm.routingEnabled.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(shape = BentoPillShape, border = bentoSecondaryBorder(), colors = bentoSecondaryButtonColors(),
            enabled = gatewayEnabled && currentMode != AiModelMode.CLOUD_GATEWAY,
            onClick = { setMode(AiModelMode.CLOUD_GATEWAY) }
        ) { Text("Use cloud gateway for chat") }
    }
    GatewayProvidersCard(vm = gatewayVm)

    if (showModelPicker && models.isNotEmpty()) {
        ChoiceDialog(
            title = "LiteRT-LM model",
            options = models.map { it.file.absolutePath },
            selected = selectedModel?.file?.absolutePath,
            onDismiss = { showModelPicker = false },
            onSelect = { index ->
                val m = models[index]
                engines.selectedModelPath = m.file.absolutePath
                selectedPath = m.file.absolutePath
                showModelPicker = false
            },
            labelFor = { path -> models.firstOrNull { it.file.absolutePath == path }?.let { "${it.name} (${formatBytes(it.sizeBytes)})" } ?: path }
        )
    }

    if (showBackendPicker) {
        val options = AndroLlmEngines.USER_BACKENDS
        ChoiceDialog(
            title = "LiteRT accelerator",
            options = options.map { it.name },
            selected = backend.name,
            onDismiss = { showBackendPicker = false },
            onSelect = { index ->
                engines.backendPreference = options[index]
                backend = options[index]
                status = "Accelerator: ${backendLabel(options[index], caps)} (applies on next load)"
                showBackendPicker = false
            },
            labelFor = { name -> backendLabel(BackendType.valueOf(name), caps) }
        )
    }
}

private fun backendLabel(type: BackendType, caps: BackendCapabilities?): String = when (type) {
    BackendType.AUTO -> "Auto (NPU → GPU → CPU)"
    BackendType.CPU -> "CPU (XNNPACK)"
    BackendType.GPU -> "GPU" + if (caps != null && !caps.gpuAvailable) " — not detected" else ""
    BackendType.NPU -> "NPU" + when {
        caps == null -> ""
        caps.npuUsable -> " (${caps.npuDisplayName})"
        else -> " — not available on this device"
    }
    else -> type.name
}

private fun engineStateText(state: EngineState): String = when (state) {
    is EngineState.Unloaded -> "No model loaded"
    is EngineState.Loading -> "Loading — ${state.stage}"
    is EngineState.WarmingUp -> "Warming up — ${state.step}"
    is EngineState.Ready -> "Ready • ${state.model.id} on ${state.model.backend.name}"
    is EngineState.Generating -> "Generating • ${state.model.id}"
    is EngineState.Unloading -> "Unloading"
    is EngineState.Failed -> "Failed (${state.stage}): ${state.message.take(100)}"
}
