package com.jarvis.assistant.ui.compose.feature.cloud

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.cloud.AndroLlmCloud
import com.jarvis.assistant.androllm.cloud.model.CloudHealth
import com.jarvis.assistant.androllm.cloud.model.CloudModelProvider
import com.jarvis.assistant.androllm.cloud.model.CloudProvider
import com.jarvis.assistant.androllm.cloud.model.CloudSettings
import com.jarvis.assistant.androllm.cloud.usage.CloudUsageAlert
import com.jarvis.assistant.androllm.cloud.usage.CloudUsageSnapshot
import com.jarvis.assistant.ui.compose.components.CloudBarChart
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.theme.ledger
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * View model for the optional AndroLLM OpenAI-compatible gateway: custom
 * providers (LiteLLM proxy, OpenRouter, Groq, Ollama `/v1`...), custom models,
 * defaults/favourites, connection tests and the persistent usage meter.
 * Everything is backed by the real [AndroLlmCloud] graph.
 */
internal class GatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val cloud = AndroLlmCloud.getInstance(application)
    private val manager = cloud.providerManager

    val settings: StateFlow<CloudSettings> =
        manager.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSettings())

    val models: StateFlow<List<CloudModelProvider>> =
        manager.cloudModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val health: StateFlow<Map<String, CloudHealth>> = cloud.healthMonitor.status

    val usage: StateFlow<CloudUsageSnapshot> = cloud.usageMeter.snapshots

    private val _routingEnabled = MutableStateFlow(AndroLlmFlags.isEnabled(application, AndroLlmFlags.CLOUD_GATEWAY))
    val routingEnabled: StateFlow<Boolean> = _routingEnabled.asStateFlow()

    private val _busyProviderId = MutableStateFlow<String?>(null)
    val busyProviderId: StateFlow<String?> = _busyProviderId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { runCatching { cloud.usageMeter.init() } }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setRoutingEnabled(enabled: Boolean) {
        AndroLlmFlags.setEnabled(getApplication(), AndroLlmFlags.CLOUD_GATEWAY, enabled)
        _routingEnabled.value = enabled
        viewModelScope.launch { runCatching { manager.setCloudModeEnabled(enabled) } }
        _message.value = if (enabled) "Cloud gateway enabled — select the Cloud Gateway routing mode to use it"
        else "Cloud gateway disabled"
    }

    private fun act(label: String, providerId: String? = null, block: suspend () -> String?) {
        viewModelScope.launch {
            _busyProviderId.value = providerId
            val msg = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "$label failed: ${e.message}"
            }
            _busyProviderId.value = null
            if (msg != null) _message.value = msg
        }
    }

    fun saveProvider(existingId: String?, name: String, baseUrl: String, apiKey: String, header: String) =
        act("Save provider") {
            if (existingId == null) {
                val p = manager.addProvider(name = name, baseUrl = baseUrl, apiKey = apiKey, apiKeyHeader = header)
                "Added ${p.name}"
            } else {
                manager.updateProvider(
                    id = existingId,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey.ifBlank { null },
                    apiKeyHeader = header
                )
                "Updated $name"
            }
        }

    fun deleteProvider(id: String) = act("Delete provider") {
        manager.deleteProvider(id)
        "Provider removed"
    }

    fun setEnabled(id: String, enabled: Boolean) = act("Toggle provider") {
        manager.setEnabled(id, enabled)
        null
    }

    fun setDefaultProvider(id: String) = act("Set default") {
        manager.setDefaultProvider(id)
        "Default provider updated"
    }

    fun setDefaultModel(providerId: String, modelId: String) = act("Set default model") {
        manager.setDefaultProvider(providerId)
        manager.setDefaultModel(modelId)
        "Default model: $modelId"
    }

    fun toggleFavorite(modelId: String) = act("Favourite") {
        manager.toggleFavorite(modelId)
        null
    }

    fun testConnection(id: String) = act("Connection test", id) {
        val r = manager.testConnection(id)
        cloud.healthMonitor.check(id)
        if (r.ok) "OK — ${r.modelCount} models (${r.latencyMs} ms)" else "Failed: ${r.error.ifBlank { "unreachable" }}"
    }

    fun refreshModels(id: String) = act("Refresh models", id) {
        "Discovered ${manager.refreshModels(id)} models"
    }

    fun addCustomModel(providerId: String, name: String, modelId: String, baseUrl: String, apiKey: String) =
        act("Add model") {
            manager.addCustomModel(
                providerId = providerId,
                modelName = name,
                modelId = modelId,
                apiBaseUrl = baseUrl.ifBlank { null },
                apiKey = apiKey
            )
            "Custom model $name added"
        }

    fun deleteCustomModel(providerId: String, customId: String) = act("Delete model") {
        manager.deleteCustomModel(providerId, customId)
        "Custom model removed"
    }

    fun clearUsage() = act("Clear usage") {
        cloud.usageMeter.clear()
        "Gateway usage cleared"
    }

    fun exportUsage() = act("Export usage") {
        val dir = File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "exports")
        val file = cloud.usageMeter.exportCsvTo(dir)
        if (file != null) "Usage exported to ${file.absolutePath}" else "Nothing to export"
    }
}

// ── Providers card ─────────────────────────────────────────────────────────

private data class ProviderDraft(
    val id: String?,
    val name: String,
    val baseUrl: String,
    val header: String
)

@Composable
internal fun GatewayProvidersCard(vm: GatewayViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val enabled by vm.routingEnabled.collectAsStateWithLifecycle()
    val busy by vm.busyProviderId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf<ProviderDraft?>(null) }
    var confirmDelete by remember { mutableStateOf<CloudProvider?>(null) }
    var addModelFor by remember { mutableStateOf<CloudProvider?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Custom gateway providers",
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "LiteLLM / OpenAI-compatible endpoints • keys encrypted in Android Keystore",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(checked = enabled, onCheckedChange = { vm.setRoutingEnabled(it) })
            }
            Text(
                text = if (enabled) "Gateway enabled. Pick \"Cloud Gateway\" in Routing Mode to send chats here."
                else "Off by default. Gemini Live and existing brains are unaffected.",
                color = MaterialTheme.ledger.deskInkFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            message?.let {
                Text(
                    text = it,
                    color = MaterialTheme.ledger.lampGlow,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { vm.clearMessage() }
                )
            }

            if (settings.providers.isEmpty()) {
                Text(
                    text = "No custom providers yet.",
                    color = MaterialTheme.ledger.deskInk,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }

            settings.providers.forEach { provider ->
                val isDefault = settings.defaultProviderId == provider.id
                val providerModels = models.filter { it.providerId == provider.id }
                val h = health[provider.id]
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { vm.setDefaultProvider(provider.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (isDefault) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = "Default provider",
                                tint = if (isDefault) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { expanded = if (expanded == provider.id) null else provider.id }
                        ) {
                            Text(
                                text = provider.name,
                                color = MaterialTheme.ledger.deskPaper,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = provider.baseUrl,
                                color = MaterialTheme.ledger.deskInk,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(checked = provider.enabled, onCheckedChange = { vm.setEnabled(provider.id, it) })
                        IconButton(
                            onClick = { expanded = if (expanded == provider.id) null else provider.id },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (expanded == provider.id) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = "Details",
                                tint = MaterialTheme.ledger.deskInk
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 32.dp)) {
                        val statusText = when {
                            provider.lastCheckedAt == 0L -> "Untested"
                            provider.lastError.isNotBlank() -> "Error"
                            else -> "OK ${provider.latencyMs} ms"
                        }
                        CloudChip(
                            text = statusText,
                            accentColor = if (provider.lastError.isNotBlank()) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.lampGlow
                        )
                        CloudChip(text = "${providerModels.size} models")
                        h?.let { CloudChip(text = if (it.ready) "ready" else if (it.reachable) "reachable" else "down") }
                    }
                    if (provider.lastError.isNotBlank()) {
                        Text(
                            text = provider.lastError,
                            color = MaterialTheme.ledger.emberRed,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 32.dp, top = 2.dp)
                        )
                    }
                    if (expanded == provider.id) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, top = 6.dp)
                        ) {
                            OutlinedButton(onClick = { vm.testConnection(provider.id) }, enabled = busy == null) {
                                if (busy == provider.id) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else Text("Test")
                            }
                            OutlinedButton(onClick = { vm.refreshModels(provider.id) }, enabled = busy == null) { Text("Models") }
                            IconButton(onClick = {
                                draft = ProviderDraft(provider.id, provider.name, provider.baseUrl, provider.apiKeyHeader)
                            }) { Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = MaterialTheme.ledger.deskInk) }
                            IconButton(onClick = { confirmDelete = provider }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.ledger.emberRed)
                            }
                        }
                        provider.quota?.let { q ->
                            val parts = listOfNotNull(
                                q.remainingRequests?.let { "requests left $it" },
                                q.remainingTokens?.let { "tokens left $it" },
                                q.retryAfterSec.takeIf { it > 0 }?.let { "retry after ${it}s" }
                            )
                            if (parts.isNotEmpty()) {
                                Text(
                                    text = "Quota: " + parts.joinToString(" • "),
                                    color = MaterialTheme.ledger.deskInk,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 32.dp, top = 4.dp)
                                )
                            }
                        }
                        Text(
                            text = "Models",
                            color = MaterialTheme.ledger.deskPaper,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 32.dp, top = 8.dp)
                        )
                        if (providerModels.isEmpty()) {
                            Text(
                                text = "No models yet — tap Models to discover, or add a custom model.",
                                color = MaterialTheme.ledger.deskInk,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 32.dp)
                            )
                        }
                        providerModels.take(60).forEach { m ->
                            GatewayModelRow(
                                model = m,
                                onDefault = { vm.setDefaultModel(provider.id, m.id) },
                                onFavorite = { vm.toggleFavorite(m.id) },
                                onDelete = if (m.isCustom) {
                                    {
                                        provider.customModels.firstOrNull { it.modelId == m.id }
                                            ?.let { vm.deleteCustomModel(provider.id, it.id) }
                                    }
                                } else null
                            )
                        }
                        if (providerModels.size > 60) {
                            Text(
                                text = "+${providerModels.size - 60} more",
                                color = MaterialTheme.ledger.deskInkFaint,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 32.dp)
                            )
                        }
                        TextButton(onClick = { addModelFor = provider }, modifier = Modifier.padding(start = 24.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Custom model")
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { draft = ProviderDraft(null, "", "", "Authorization") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add provider")
            }
        }
    }

    draft?.let { d ->
        ProviderDialog(
            draft = d,
            onDismiss = { draft = null },
            onSave = { name, url, key, header ->
                vm.saveProvider(d.id, name, url, key, header)
                draft = null
            }
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text("The provider, its custom models and its encrypted key are deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProvider(p.id)
                    confirmDelete = null
                }) { Text("Remove", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    addModelFor?.let { p ->
        CustomModelDialog(
            providerName = p.name,
            onDismiss = { addModelFor = null },
            onSave = { name, id, url, key ->
                vm.addCustomModel(p.id, name, id, url, key)
                addModelFor = null
            }
        )
    }
}

@Composable
private fun GatewayModelRow(
    model: CloudModelProvider,
    onDefault: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
    ) {
        IconButton(onClick = onDefault, modifier = Modifier.size(30.dp)) {
            Icon(
                if (model.isDefault) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                contentDescription = "Default model",
                tint = if (model.isDefault) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                color = MaterialTheme.ledger.deskPaper,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                if (model.isCustom) "custom" else null,
                model.contextWindow?.let { "ctx ${formatTokens(it)}" },
                model.maxOutputTokens?.let { "out ${formatTokens(it)}" }
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" • "),
                    color = MaterialTheme.ledger.deskInkFaint,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(30.dp)) {
            Icon(
                if (model.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Favourite",
                tint = if (model.isFavorite) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = MaterialTheme.ledger.emberRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ProviderDialog(
    draft: ProviderDraft,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, header: String) -> Unit
) {
    var name by remember { mutableStateOf(draft.name) }
    var url by remember { mutableStateOf(draft.baseUrl) }
    var key by remember { mutableStateOf("") }
    var header by remember { mutableStateOf(draft.header) }
    val valid = name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "Add gateway provider" else "Edit ${draft.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Base URL (e.g. https://openrouter.ai/api)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (draft.id == null) "API key (optional)" else "API key (blank = keep)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = header,
                    onValueChange = { header = it.trim() },
                    label = { Text("Auth header") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, url, key, header) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CustomModelDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onSave: (name: String, modelId: String, baseUrl: String, apiKey: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom model • $providerName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, singleLine = true)
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim() },
                    label = { Text("Model id (e.g. openai/gpt-4o-mini)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text("Alternate base URL (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Model-scoped API key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, id, url, key) }, enabled = name.isNotBlank() && id.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Usage analytics (real AndroLLM CloudUsageMeter) ─────────────────────────

internal fun LazyListScope.gatewayUsageItems(
    snapshot: CloudUsageSnapshot,
    onClear: () -> Unit,
    onExport: () -> Unit
) {
    item(key = "gw-usage-header") {
        SectionHeader(
            title = "Gateway analytics",
            subtitle = "Persistent per-request metering for the optional cloud gateway"
        )
    }
    item(key = "gw-usage-totals") {
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UsageTotalsRow("Today", snapshot.today)
                UsageTotalsRow("This month", snapshot.month)
                UsageTotalsRow("All time", snapshot.total)
                val t = snapshot.total
                Text(
                    text = "Success ${pct(t.successRate)} • cache hit ${pct(t.cacheHitRate)} • " +
                        "avg latency ${t.avgLatencyMs} ms • TTFT ${t.avgFirstTokenMs} ms • " +
                        "${t.rateLimitHits} rate-limit hits • ${t.toolCalls} tool calls",
                    color = MaterialTheme.ledger.deskInk,
                    style = MaterialTheme.typography.labelSmall
                )
                if (snapshot.lastProviderName.isNotBlank()) {
                    Text(
                        text = "Last provider: ${snapshot.lastProviderName}" +
                            (snapshot.lastRequest?.modelId?.let { " • $it" } ?: ""),
                        color = MaterialTheme.ledger.deskInkFaint,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport) { Text("Export CSV") }
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
        }
    }
    if (snapshot.alerts.isNotEmpty()) {
        item(key = "gw-usage-alerts") {
            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    snapshot.alerts.forEach { a ->
                        Text(
                            text = "${a.title} — ${a.detail}",
                            color = if (a.severity == CloudUsageAlert.Severity.INFO) MaterialTheme.ledger.deskInk
                            else MaterialTheme.ledger.emberRed,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
    if (snapshot.daily.any { it.requests > 0 }) {
        item(key = "gw-usage-daily") {
            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Daily tokens (last ${snapshot.daily.size} days)",
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    CloudBarChart(
                        values = snapshot.daily.map { it.totalTokens.toFloat() },
                        accent = MaterialTheme.ledger.lampGlow,
                        height = 110.dp,
                        maxBars = 30
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Requests per hour (today)",
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    CloudBarChart(
                        values = snapshot.hourlyRequests.map { it.toFloat() },
                        accent = MaterialTheme.ledger.lampAmber,
                        height = 90.dp,
                        maxBars = 24
                    )
                }
            }
        }
    }
    if (snapshot.perProvider.isNotEmpty() || snapshot.perModel.isNotEmpty()) {
        item(key = "gw-usage-breakdown") {
            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (snapshot.perProvider.isNotEmpty()) {
                        Text("Per provider", color = MaterialTheme.ledger.deskPaper, style = MaterialTheme.typography.labelMedium)
                        snapshot.perProvider.sortedByDescending { it.requests }.take(8).forEach { p ->
                            BreakdownRow(
                                p.providerName.ifBlank { p.providerId },
                                "${p.requests} req • ${formatTokens(p.totalTokens)} tok • ${usd(p.estimatedCostMicros)} • ${p.failures} fail"
                            )
                        }
                    }
                    if (snapshot.perModel.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Per model", color = MaterialTheme.ledger.deskPaper, style = MaterialTheme.typography.labelMedium)
                        snapshot.perModel.sortedByDescending { it.requests }.take(8).forEach { m ->
                            BreakdownRow(
                                m.modelId,
                                "${m.requests} req • ${formatTokens(m.totalTokens)} tok • ${usd(m.estimatedCostMicros)} • ${m.avgLatencyMs} ms"
                            )
                        }
                    }
                }
            }
        }
    }
    if (snapshot.recentRecords.isNotEmpty()) {
        item(key = "gw-usage-recent") {
            CloudGlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recent requests", color = MaterialTheme.ledger.deskPaper, style = MaterialTheme.typography.labelMedium)
                    snapshot.recentRecords.take(15).forEach { r ->
                        BreakdownRow(
                            "${r.providerName} • ${r.modelId}",
                            if (r.success) "${r.totalTokens} tok • ${r.latencyMs} ms${if (r.cacheHit) " • cache" else ""}"
                            else "failed: ${r.errorKind.name.lowercase(Locale.US)}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageTotalsRow(label: String, t: com.jarvis.assistant.androllm.cloud.usage.CloudUsageTotals) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.ledger.deskInk, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(
            text = "${t.requests} req • ${formatTokens(t.totalTokens)} tok • ${usd(t.estimatedCostMicros)}",
            color = MaterialTheme.ledger.deskPaper,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun BreakdownRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = MaterialTheme.ledger.deskPaper,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = value, color = MaterialTheme.ledger.deskInk, style = MaterialTheme.typography.labelSmall)
    }
}

private fun pct(f: Float): String = "${(f * 100).toInt()}%"

private fun usd(micros: Long): String = "$" + String.format(Locale.US, "%.4f", micros / 1_000_000.0)

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}
