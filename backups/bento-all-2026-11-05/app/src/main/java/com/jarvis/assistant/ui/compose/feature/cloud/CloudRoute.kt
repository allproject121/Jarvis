package com.jarvis.assistant.ui.compose.feature.cloud

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.bounceClick
import com.jarvis.assistant.ui.compose.theme.ledger

private const val PAGE_PROVIDERS = "providers"
private const val PAGE_MODELS = "models"
private const val PAGE_USAGE = "usage"

/**
 * Cloud Brains (ported from AndroLLM's Cloud Providers / Cloud Models / Cloud
 * Usage screens). Shows the three built-in remote brains (Gemini, OpenAI,
 * Local Host / Ollama), the [AiModelMode] routing mode, and the optional
 * AndroLLM OpenAI-compatible gateway (custom providers / models, usage analytics).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: CloudViewModel = viewModel { CloudViewModel(application) }
    val gatewayViewModel: GatewayViewModel = viewModel { GatewayViewModel(application) }
    val gatewayUsage by gatewayViewModel.usage.collectAsStateWithLifecycle()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val usage by viewModel.usageStats.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var page by rememberSaveable { mutableStateOf(PAGE_PROVIDERS) }
    var modelsForName by rememberSaveable { mutableStateOf(CloudProviderId.GEMINI.name) }
    val modelsFor = CloudProviderId.valueOf(modelsForName)

    var editing by remember { mutableStateOf<CloudProviderId?>(null) }
    var confirmRemove by remember { mutableStateOf<CloudProviderId?>(null) }

    BackHandler(enabled = page != PAGE_PROVIDERS) { page = PAGE_PROVIDERS }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    CloudAtmosphericBackground(reduceMotion = false) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        when (page) {
                            PAGE_MODELS -> Column {
                                Text(
                                    text = "Cloud Models",
                                    color = MaterialTheme.ledger.deskPaper,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = modelsFor.title,
                                    color = MaterialTheme.ledger.deskInk,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            PAGE_USAGE -> Text(
                                text = "Cloud Usage",
                                color = MaterialTheme.ledger.deskPaper,
                                style = MaterialTheme.typography.titleLarge
                            )
                            else -> Text(
                                text = "Cloud Brains",
                                color = MaterialTheme.ledger.deskPaper,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (page != PAGE_PROVIDERS) page = PAGE_PROVIDERS else back()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.ledger.deskPaper
                            )
                        }
                    },
                    actions = {
                        when (page) {
                            PAGE_PROVIDERS -> IconButton(onClick = { page = PAGE_USAGE }) {
                                Icon(
                                    Icons.Filled.Insights,
                                    contentDescription = "Usage dashboard",
                                    tint = MaterialTheme.ledger.deskPaper
                                )
                            }
                            PAGE_MODELS -> {
                                val refreshing = uiState.testingId == modelsFor
                                IconButton(
                                    onClick = { viewModel.testConnection(modelsFor) },
                                    enabled = uiState.testingId == null
                                ) {
                                    if (refreshing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.ledger.lampGlow,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = "Refresh models",
                                            tint = MaterialTheme.ledger.deskPaper
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (page) {
                    PAGE_MODELS -> CloudModelsPage(
                        provider = modelsFor,
                        uiState = uiState,
                        onSetGeminiModel = viewModel::setGeminiModel,
                        onSetLocalModel = viewModel::setLocalHostModel
                    )
                    PAGE_USAGE -> CloudUsagePage(
                        usage = usage,
                        uiState = uiState,
                        gatewayUsage = gatewayUsage,
                        onClearGatewayUsage = gatewayViewModel::clearUsage,
                        onExportGatewayUsage = gatewayViewModel::exportUsage
                    )
                    else -> ProvidersPage(
                        uiState = uiState,
                        gatewayViewModel = gatewayViewModel,
                        onSetMode = viewModel::setMode,
                        onTest = viewModel::testConnection,
                        onOpenModels = { id ->
                            modelsForName = id.name
                            page = PAGE_MODELS
                        },
                        onEdit = { editing = it },
                        onRemove = { confirmRemove = it }
                    )
                }
            }
        }
    }

    editing?.let { id ->
        when (id) {
            CloudProviderId.LOCAL_HOST -> LocalHostFormDialog(
                endpoint = uiState.localEndpoint,
                model = uiState.localModel,
                onDismiss = { editing = null },
                onSave = { endpoint, model ->
                    viewModel.saveLocalHost(endpoint, model)
                    editing = null
                }
            )
            else -> ApiKeyFormDialog(
                provider = id,
                hasExistingKey = uiState.hasKey(id),
                canStoreSecurely = uiState.canStoreSecurely,
                onDismiss = { editing = null },
                onSave = { key ->
                    if (id == CloudProviderId.GEMINI) viewModel.saveGeminiKey(key) else viewModel.saveOpenAiKey(key)
                    editing = null
                }
            )
        }
    }

    confirmRemove?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${id.title} key?") },
            text = { Text("The stored API key will be deleted from this device. Jarvis will fall back to other brains until you add a new key.") },
            confirmButton = {
                TextButton(onClick = {
                    if (id == CloudProviderId.GEMINI) viewModel.saveGeminiKey("") else viewModel.saveOpenAiKey("")
                    confirmRemove = null
                }) { Text("Remove", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Providers page ─────────────────────────────────────────────────────────

@Composable
private fun ProvidersPage(
    uiState: CloudUiState,
    gatewayViewModel: GatewayViewModel,
    onSetMode: (AiModelMode) -> Unit,
    onTest: (CloudProviderId) -> Unit,
    onOpenModels: (CloudProviderId) -> Unit,
    onEdit: (CloudProviderId) -> Unit,
    onRemove: (CloudProviderId) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StaggeredEntrance(0) {
                CloudModeCard(uiState = uiState)
            }
        }

        item {
            StaggeredEntrance(1) {
                SectionHeader(
                    title = "Routing Mode",
                    subtitle = "How Jarvis' model router picks a brain"
                )
            }
        }

        item {
            StaggeredEntrance(1) {
                CloudGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column {
                        AiModelMode.entries.forEach { mode ->
                            ModeRow(
                                mode = mode,
                                selected = mode == uiState.mode,
                                onClick = { onSetMode(mode) }
                            )
                        }
                    }
                }
            }
        }

        item {
            StaggeredEntrance(2) {
                SectionHeader(
                    title = "Providers",
                    subtitle = "Your own keys, stored on this device"
                )
            }
        }

        items(CloudProviderId.entries.toList(), key = { it.name }) { id ->
            StaggeredEntrance(0, instant = true) {
                ProviderCard(
                    id = id,
                    uiState = uiState,
                    health = uiState.health[id],
                    isTesting = uiState.testingId == id,
                    onTest = { onTest(id) },
                    onOpenModels = { onOpenModels(id) },
                    onEdit = { onEdit(id) },
                    onRemove = { onRemove(id) }
                )
            }
        }

        item {
            StaggeredEntrance(3) {
                GatewayProvidersCard(vm = gatewayViewModel)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CloudModeCard(uiState: CloudUiState) {
    val cloudCapable = uiState.mode == AiModelMode.GEMINI_LIVE ||
        uiState.mode == AiModelMode.CHATGPT ||
        uiState.mode == AiModelMode.AUTO_HYBRID ||
        uiState.mode == AiModelMode.CLOUD_GATEWAY
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = if (cloudCapable) MaterialTheme.ledger.lampAmber.copy(alpha = 0.18f) else MaterialTheme.ledger.deskHairline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (cloudCapable) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (cloudCapable) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = uiState.mode.displayName,
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = uiState.mode.description,
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (uiState.lastActiveProvider.isNotBlank() || uiState.offlineFallbackActive) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.lastActiveProvider.isNotBlank()) {
                        Text(
                            text = "Last used: ${uiState.lastActiveProvider}",
                            color = MaterialTheme.ledger.deskInk,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (uiState.offlineFallbackActive) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "OFFLINE FALLBACK ACTIVE",
                            color = MaterialTheme.ledger.lampAmber,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(mode: AiModelMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = mode.displayName,
                color = if (selected) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskPaperDim,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = mode.description,
                color = MaterialTheme.ledger.deskInk,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun providerIcon(id: CloudProviderId): ImageVector = when (id) {
    CloudProviderId.LOCAL_HOST -> Icons.Filled.Computer
    else -> Icons.Filled.Cloud
}

/** Whether the current routing mode will actually use this provider. */
private fun usedByMode(id: CloudProviderId, mode: AiModelMode): Boolean = when (id) {
    CloudProviderId.GEMINI -> mode == AiModelMode.GEMINI_LIVE || mode == AiModelMode.AUTO_HYBRID
    CloudProviderId.OPENAI -> mode == AiModelMode.CHATGPT || mode == AiModelMode.AUTO_HYBRID
    CloudProviderId.LOCAL_HOST -> mode == AiModelMode.LOCAL_HOST || mode == AiModelMode.AUTO_HYBRID
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    id: CloudProviderId,
    uiState: CloudUiState,
    health: ProviderHealth?,
    isTesting: Boolean,
    onTest: () -> Unit,
    onOpenModels: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val configured = uiState.hasKey(id)
    val subtitle = when (id) {
        CloudProviderId.GEMINI ->
            if (configured) "Key ${uiState.geminiKeyMasked} · ${CloudViewModel.prettyModel(uiState.geminiModel)}"
            else "No API key — tap Edit to add one"
        CloudProviderId.OPENAI ->
            if (configured) "Key ${uiState.openAiKeyMasked} · ${uiState.openAiModel}"
            else "No API key — tap Edit to add one"
        CloudProviderId.LOCAL_HOST -> "${uiState.localEndpoint} · ${uiState.localModel}"
    }
    val endpointLine = if (id == CloudProviderId.LOCAL_HOST) "Ollama /api/tags" else id.endpointLabel

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = providerIcon(id),
                    contentDescription = null,
                    tint = if (configured) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = id.title,
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusChip(enabled = configured)
                Spacer(Modifier.width(6.dp))
                HealthChip(health = health)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = endpointLine,
                    color = MaterialTheme.ledger.deskInkFaint,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                health?.takeIf { it.latencyMs > 0 }?.let { h ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${h.latencyMs} ms",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                health?.modelIds?.size?.takeIf { it > 0 }?.let { count ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$count models",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (usedByMode(id, uiState.mode)) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "IN USE",
                        color = MaterialTheme.ledger.lampAmber,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            health?.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = "⚠ $error",
                    color = MaterialTheme.ledger.emberRed,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionButton(
                    icon = {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.ledger.deskPaper,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    label = "Test",
                    onClick = onTest,
                    enabled = !isTesting && configured
                )
                ActionButton(
                    icon = { Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = "Models",
                    onClick = onOpenModels
                )
                ActionButton(
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = "Edit",
                    onClick = onEdit
                )
                if (id != CloudProviderId.LOCAL_HOST) {
                    ActionButton(
                        icon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = "Remove key",
                        onClick = onRemove,
                        enabled = configured,
                        destructive = true
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(enabled: Boolean) {
    val bg: Color
    val fg: Color
    val label: String
    if (enabled) {
        bg = MaterialTheme.ledger.lampAmber.copy(alpha = 0.18f)
        fg = MaterialTheme.ledger.lampGlow
        label = "ON"
    } else {
        bg = MaterialTheme.ledger.deskHairline
        fg = MaterialTheme.ledger.deskPaperDim
        label = "OFF"
    }
    Box(
        modifier = Modifier
            .background(bg, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HealthChip(health: ProviderHealth?) {
    val bg: Color
    val fg: Color
    val label: String
    when {
        health == null -> {
            bg = MaterialTheme.ledger.deskHairline; fg = MaterialTheme.ledger.deskInk; label = "—"
        }
        health.reachable && health.authorized -> {
            bg = MaterialTheme.ledger.lampGlow.copy(alpha = 0.15f); fg = MaterialTheme.ledger.lampGlow; label = "LIVE"
        }
        health.reachable -> {
            bg = MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f); fg = MaterialTheme.ledger.lampAmber; label = "REJECTED"
        }
        else -> {
            bg = MaterialTheme.ledger.emberRed.copy(alpha = 0.15f); fg = MaterialTheme.ledger.emberRed; label = "DOWN"
        }
    }
    Box(
        modifier = Modifier
            .background(bg, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) MaterialTheme.ledger.emberRed.copy(alpha = 0.16f) else MaterialTheme.ledger.deskHairline,
            contentColor = if (destructive) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskPaper,
            disabledContainerColor = MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.ledger.deskInkFaint
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(5.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ── Dialogs ────────────────────────────────────────────────────────────────

@Composable
private fun ApiKeyFormDialog(
    provider: CloudProviderId,
    hasExistingKey: Boolean,
    canStoreSecurely: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasExistingKey) "Edit ${provider.title}" else "Add ${provider.title}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    placeholder = {
                        Text(if (provider == CloudProviderId.GEMINI) "AIza..." else "sk-...")
                    },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (canStoreSecurely) {
                        "Stored in encrypted storage on this device. Leave blank and use Remove key to delete it."
                    } else {
                        "Secure key storage is unavailable on this device" +
                            if (provider == CloudProviderId.GEMINI) " — the Gemini key cannot be saved." else "."
                    },
                    color = if (canStoreSecurely) MaterialTheme.ledger.deskInk else MaterialTheme.ledger.emberRed,
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let {
                    Text(text = it, color = MaterialTheme.ledger.emberRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = apiKey.trim()
                when {
                    clean.isEmpty() -> error = "Enter an API key"
                    clean.any { it.isWhitespace() } -> error = "API keys cannot contain spaces"
                    else -> onSave(clean)
                }
            }) { Text("Save", color = MaterialTheme.ledger.lampAmber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LocalHostFormDialog(
    endpoint: String,
    model: String,
    onDismiss: () -> Unit,
    onSave: (endpoint: String, model: String) -> Unit
) {
    var url by remember { mutableStateOf(endpoint) }
    var modelTag by remember { mutableStateOf(model) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Local Host (Ollama)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://127.0.0.1:11434") },
                    supportingText = { Text("Use `adb reverse tcp:11434 tcp:11434` with 127.0.0.1, or your PC's LAN IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelTag,
                    onValueChange = { modelTag = it },
                    label = { Text("Model tag") },
                    placeholder = { Text("qwen3:8b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(text = it, color = MaterialTheme.ledger.emberRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = url.trim()
                when {
                    !clean.startsWith("http://") && !clean.startsWith("https://") ->
                        error = "URL must start with http:// or https://"
                    else -> onSave(clean, modelTag.trim())
                }
            }) { Text("Save", color = MaterialTheme.ledger.lampAmber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
