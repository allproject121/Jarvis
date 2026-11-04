package com.jarvis.assistant.ui.compose.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory as MemoryChipIcon
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.androllm.memory.Memory
import com.jarvis.assistant.androllm.memory.MemoryCategory
import com.jarvis.assistant.androllm.memory.MemoryInspectorStats
import com.jarvis.assistant.androllm.memory.MemoryLogLevel
import com.jarvis.assistant.androllm.memory.MemorySearchFilters
import com.jarvis.assistant.androllm.memory.MemorySettings
import com.jarvis.assistant.androllm.memory.MemoryStats
import com.jarvis.assistant.androllm.memory.MemoryWriteAction
import com.jarvis.assistant.androllm.memory.Project
import com.jarvis.assistant.androllm.memory.Relationship
import com.jarvis.assistant.androllm.memory.Tag
import com.jarvis.assistant.androllm.memory.background.MemoryBackgroundScheduler
import com.jarvis.assistant.androllm.memory.background.MemoryMaintenance
import com.jarvis.assistant.androllm.memory.embedding.EmbeddingSource
import com.jarvis.assistant.androllm.memory.intelligence.MemoryIntelligenceRoute
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * "Memory" settings — AndroLLM memory intelligence on top of Jarvis' existing
 * memory core (now inside Jarvis' [MemoryManager]): LLM extraction + rolling summaries (ON by default), background
 * indexing (OFF by default), routing, tuning, embedding source (hashing by
 * default), projects / tags / relationships, import / export, the Memory
 * Inspector (stats + log levels) and memory management.
 */
@Composable
fun MemorySettingsSection() {
    val context = LocalContext.current
    val pipeline = remember { MemoryManager.getInstance(context) }
    val settingsStore = pipeline.settingsStore
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(settingsStore.current()) }
    var stats by remember { mutableStateOf(MemoryStats()) }
    var memories by remember { mutableStateOf<List<Memory>>(emptyList()) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var relationships by remember { mutableStateOf<List<Relationship>>(emptyList()) }
    var inspector by remember { mutableStateOf<MemoryInspectorStats?>(null) }
    var filters by remember { mutableStateOf(MemorySearchFilters()) }
    var filterLabel by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var visibleCount by remember { mutableIntStateOf(PAGE_SIZE) }
    var showRouteDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEmbeddingSourceDialog by remember { mutableStateOf(false) }
    var showEmbeddingPathDialog by remember { mutableStateOf(false) }
    var showCloudEmbeddingDialog by remember { mutableStateOf(false) }
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var showAddRelationshipDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showInspector by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }
    var pendingTagEdit by remember { mutableStateOf<Memory?>(null) }
    var pendingProjectEdit by remember { mutableStateOf<Memory?>(null) }
    var pendingProjectDelete by remember { mutableStateOf<Project?>(null) }

    suspend fun reload() {
        settings = settingsStore.current()
        stats = pipeline.stats()
        projects = pipeline.getProjects()
        tags = pipeline.getTags()
        relationships = pipeline.getRelationships()
        memories = pipeline.getMemories(filters)
        if (showInspector) inspector = pipeline.inspectorStats()
    }

    fun refresh() {
        scope.launch { runCatching { reload() }.onFailure { message = "Could not load memories: ${it.message}" } }
    }

    fun embeddingSettingsChanged() {
        settings = settingsStore.current()
        scope.launch {
            runCatching {
                pipeline.embeddingIndex.invalidate()
                pipeline.embeddingIndex.applyToJarvis()
            }.onFailure { message = "Embedder update failed: ${it.message}" }
            message = "Embedding source updated. Run indexing now to re-embed existing memories."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                } ?: error("Could not read the selected file")
                pipeline.importMemories(text)
            }
            message = result.fold(
                { "Imported: +${it.inserted} new, ~${it.updated} merged, -${it.skipped} skipped, ${it.relationships} link(s)" },
                { "Import failed: ${it.message}" }
            )
            runCatching { reload() }
            busy = false
        }
    }

    LaunchedEffect(Unit) { runCatching { reload() }.onFailure { message = "Could not load memories: ${it.message}" } }

    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
        Column {
            // ── Status ──
            SettingRow(
                icon = Icons.Filled.MemoryChipIcon,
                title = "Memory core",
                value = "${stats.memoryCount} memories • ${stats.indexedCount} indexed"
            ) { refresh() }
            Text(
                text = buildString {
                    append("${stats.pinnedCount} pinned • ${stats.archivedCount} archived • ")
                    append("${stats.extractedCount} LLM-extracted • ${stats.summaryCount} summaries")
                    append("\n${projects.size} projects • ${tags.size} tags • ${relationships.size} links")
                    if (stats.lastIndexingAt > 0) {
                        append("\nLast indexing: ").append(formatTime(stats.lastIndexingAt))
                        if (stats.lastIndexingResult.isNotBlank()) append(" (").append(stats.lastIndexingResult).append(")")
                    }
                    if (stats.lastExtractionAt > 0) {
                        append("\nLast extraction: ").append(formatTime(stats.lastExtractionAt))
                        if (stats.lastExtractionResult.isNotBlank()) append(" (").append(stats.lastExtractionResult).append(")")
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.lampGlow),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Switches (both OFF by default) ──
            ToggleRow(
                icon = Icons.Filled.Psychology,
                title = "LLM memory extraction",
                subtitle = "After each turn, a quiet text-model call extracts durable facts and keeps a rolling summary. Never uses Gemini Live.",
                checked = settings.extractionEnabled,
                onCheckedChange = { on ->
                    settingsStore.setExtractionEnabled(on)
                    settings = settingsStore.current()
                }
            )
            ToggleRow(
                icon = Icons.Filled.CloudSync,
                title = "Background indexing",
                subtitle = "Every 6h (battery not low): index pending memories, prune stale vectors, archive expired ones.",
                checked = settings.backgroundIndexingEnabled,
                onCheckedChange = { on ->
                    settingsStore.setBackgroundIndexingEnabled(on)
                    runCatching {
                        if (on) MemoryBackgroundScheduler.schedule(context) else MemoryBackgroundScheduler.cancel(context)
                    }.onFailure { message = "Scheduler error: ${it.message}" }
                    settings = settingsStore.current()
                }
            )
            SettingRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Memory intelligence",
                value = settings.route.label,
                enabled = settings.extractionEnabled
            ) { showRouteDialog = true }

            SliderSetting(
                label = { "Merge similarity threshold: ${"%.2f".format(it)}" },
                value = settings.similarityThreshold,
                valueRange = MemorySettings.THRESHOLD_MIN..MemorySettings.THRESHOLD_MAX,
                onCommit = { v ->
                    settingsStore.setSimilarityThreshold(v)
                    settings = settingsStore.current()
                }
            )
            SliderSetting(
                label = { "Summarize every ${it.toInt()} messages" },
                value = settings.summarizationInterval.toFloat(),
                valueRange = MemorySettings.SUMMARIZATION_MIN.toFloat()..MemorySettings.SUMMARIZATION_MAX.toFloat(),
                onCommit = { v ->
                    settingsStore.setSummarizationInterval(v.toInt())
                    settings = settingsStore.current()
                }
            )
            SliderSetting(
                label = { "Max memories injected into context: ${it.toInt()}" },
                value = settings.maxContextMemories.toFloat(),
                valueRange = 0f..5f,
                steps = 4,
                onCommit = { v ->
                    settingsStore.setMaxContextMemories(v.toInt())
                    settings = settingsStore.current()
                }
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Embeddings (hashing stays the default) ──
            SettingRow(
                icon = Icons.Filled.Hub,
                title = "Embedding source",
                value = settings.embeddingSource.label
            ) { showEmbeddingSourceDialog = true }
            if (settings.embeddingSource == EmbeddingSource.LITERT || settings.embeddingSource == EmbeddingSource.AUTO) {
                SettingRow(
                    icon = Icons.Filled.Folder,
                    title = "LiteRT embedding model (.tflite)",
                    value = settings.embeddingModelPath.substringAfterLast('/').ifBlank {
                        if (settings.embeddingSource == EmbeddingSource.AUTO) "Built-in Hashing (Active)" else "Not set"
                    }
                ) { showEmbeddingPathDialog = true }
            }
            if (settings.embeddingSource == EmbeddingSource.CLOUD || settings.embeddingSource == EmbeddingSource.AUTO) {
                SettingRow(
                    icon = Icons.Filled.CloudSync,
                    title = "Cloud embedding model",
                    value = settings.cloudEmbeddingModel.ifBlank {
                        if (settings.embeddingSource == EmbeddingSource.AUTO) "Built-in Hashing (Active)" else "Not set"
                    }
                ) { showCloudEmbeddingDialog = true }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Actions ──
            SettingRow(
                icon = Icons.Filled.Sync,
                title = if (busy) "Working…" else "Run indexing now",
                enabled = !busy
            ) {
                busy = true
                scope.launch {
                    val result = runCatching { MemoryMaintenance.run(context) }
                    message = result.fold({ "Indexing done: ${it.describe()}" }, { "Indexing failed: ${it.message}" })
                    runCatching { reload() }
                    busy = false
                }
            }
            SettingRow(icon = Icons.Filled.FileDownload, title = "Export memories (JSON)", enabled = !busy) {
                scope.launch {
                    message = pipeline.exportMemories().fold(
                        { "Exported to ${it.absolutePath}" },
                        { "Export failed: ${it.message}" }
                    )
                }
            }
            SettingRow(icon = Icons.Filled.FileUpload, title = "Import memories (JSON)", enabled = !busy) {
                runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    .onFailure { message = "No file picker available: ${it.message}" }
            }
            SettingRow(icon = Icons.Filled.Add, title = "Add a memory", enabled = !busy) { showAddDialog = true }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Projects ──
            SubHeader("Projects")
            projects.forEach { project ->
                GraphRow(
                    icon = Icons.Filled.Folder,
                    text = project.name,
                    detail = buildString {
                        append("${project.memoryCount} memories")
                        if (project.description.isNotBlank()) append(" • ").append(project.description)
                    },
                    selected = filters.projectId == project.id,
                    onClick = {
                        filters = if (filters.projectId == project.id) MemorySearchFilters() else MemorySearchFilters(projectId = project.id)
                        filterLabel = if (filters.isEmpty) null else "Project: ${project.name}"
                        visibleCount = PAGE_SIZE
                        refresh()
                    },
                    onDelete = { pendingProjectDelete = project }
                )
            }
            TextButton(onClick = { showAddProjectDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+ New project", color = MaterialTheme.ledger.lampGlow)
            }

            // ── Tags ──
            SubHeader("Tags")
            if (tags.isEmpty()) {
                EmptyLine("No tags yet — tags come from extraction or the tag button on a memory.")
            }
            tags.forEach { tag ->
                GraphRow(
                    icon = Icons.Filled.Tag,
                    text = tag.name,
                    detail = "${tag.memoryCount} memories",
                    selected = tag.name in filters.tags,
                    onClick = {
                        filters = if (tag.name in filters.tags) MemorySearchFilters() else MemorySearchFilters(tags = setOf(tag.name))
                        filterLabel = if (filters.isEmpty) null else "Tag: ${tag.name}"
                        visibleCount = PAGE_SIZE
                        refresh()
                    },
                    onDelete = {
                        scope.launch {
                            pipeline.deleteTag(tag.id).onFailure { message = "Delete tag failed: ${it.message}" }
                            runCatching { reload() }
                        }
                    }
                )
            }

            // ── Relationships ──
            SubHeader("Relationships")
            if (relationships.isEmpty()) {
                EmptyLine("No links yet — memories learned in the same exchange are linked automatically.")
            }
            val byId = memories.associateBy { it.id }
            relationships.take(MAX_LINKS_SHOWN).forEach { rel ->
                GraphRow(
                    icon = Icons.Filled.Link,
                    text = "#${rel.fromMemoryId} → #${rel.toMemoryId}  (${rel.type})",
                    detail = listOfNotNull(byId[rel.fromMemoryId]?.content?.take(40), byId[rel.toMemoryId]?.content?.take(40))
                        .joinToString("  ↔  "),
                    selected = false,
                    onClick = {},
                    onDelete = {
                        scope.launch {
                            pipeline.deleteRelationship(rel.id).onFailure { message = "Delete link failed: ${it.message}" }
                            runCatching { reload() }
                        }
                    }
                )
            }
            TextButton(onClick = { showAddRelationshipDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+ Link two memories", color = MaterialTheme.ledger.lampGlow)
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Memory Inspector ──
            SettingRow(
                icon = Icons.Filled.BugReport,
                title = if (showInspector) "Hide memory inspector" else "Memory inspector",
                value = "Logs: ${settings.logLevel.name}"
            ) {
                showInspector = !showInspector
                if (showInspector) scope.launch { inspector = runCatching { pipeline.inspectorStats() }.getOrNull() }
            }
            if (showInspector) {
                inspector?.let { InspectorBlock(it) }
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { showLogLevelDialog = true }) {
                        Text("Log level", color = MaterialTheme.ledger.lampGlow)
                    }
                    TextButton(onClick = {
                        MemoryLogger.clear()
                        scope.launch { inspector = runCatching { pipeline.inspectorStats() }.getOrNull() }
                    }) {
                        Text("Clear logs", color = MaterialTheme.ledger.lampGlow)
                    }
                    TextButton(onClick = { scope.launch { inspector = runCatching { pipeline.inspectorStats() }.getOrNull() } }) {
                        Text("Refresh", color = MaterialTheme.ledger.lampGlow)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            // ── Memory list ──
            filterLabel?.let { label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(16.dp))
                    Text(
                        text = "  $label",
                        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.ledger.lampGlow),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        filters = MemorySearchFilters()
                        filterLabel = null
                        refresh()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear filter", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (memories.isEmpty()) {
                EmptyLine(if (filterLabel == null) "No memories stored yet." else "No memories match this filter.")
            } else {
                memories.take(visibleCount).forEachIndexed { index, memory ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.ledger.deskHairlineSoft)
                    MemoryRow(
                        memory = memory,
                        onTogglePin = {
                            scope.launch {
                                pipeline.setPinned(memory.id, !memory.isPinned)
                                    .onFailure { message = "Pin failed: ${it.message}" }
                                runCatching { reload() }
                            }
                        },
                        onToggleArchive = {
                            scope.launch {
                                pipeline.setArchived(memory.id, !memory.isArchived)
                                    .onFailure { message = "Archive failed: ${it.message}" }
                                runCatching { reload() }
                            }
                        },
                        onEditTags = { pendingTagEdit = memory },
                        onEditProject = { pendingProjectEdit = memory },
                        onDelete = { pendingDelete = memory }
                    )
                }
                if (memories.size > visibleCount) {
                    TextButton(
                        onClick = { visibleCount += PAGE_SIZE },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("Show more (${memories.size - visibleCount} remaining)", color = MaterialTheme.ledger.lampGlow)
                    }
                }
            }
        }
    }

    // ── Dialogs ──

    if (showRouteDialog) {
        val routes = MemoryIntelligenceRoute.entries
        ChoiceDialog(
            title = "Memory intelligence",
            options = routes.map { it.name },
            selected = settings.route.name,
            onDismiss = { showRouteDialog = false },
            onSelect = { index ->
                settingsStore.setRoute(routes[index])
                settings = settingsStore.current()
                showRouteDialog = false
            },
            labelFor = { name -> MemoryIntelligenceRoute.fromName(name).label }
        )
    }

    if (showEmbeddingSourceDialog) {
        val sources = EmbeddingSource.entries
        ChoiceDialog(
            title = "Embedding source",
            options = sources.map { it.name },
            selected = settings.embeddingSource.name,
            onDismiss = { showEmbeddingSourceDialog = false },
            onSelect = { index ->
                settingsStore.setEmbeddingSource(sources[index])
                showEmbeddingSourceDialog = false
                embeddingSettingsChanged()
            },
            labelFor = { name -> EmbeddingSource.fromName(name).label }
        )
    }

    if (showEmbeddingPathDialog) {
        TextInputDialog(
            title = "LiteRT embedding model",
            description = "Absolute path of an EmbeddingGemma .tflite file; tokenizer.model must sit next to it.",
            label = "Model path",
            initial = settings.embeddingModelPath,
            allowEmpty = true,
            onDismiss = { showEmbeddingPathDialog = false },
            onSave = { path ->
                showEmbeddingPathDialog = false
                settingsStore.setEmbeddingModelPath(path)
                embeddingSettingsChanged()
            }
        )
    }

    if (showCloudEmbeddingDialog) {
        TextInputDialog(
            title = "Cloud embedding model",
            description = "Model id served by your AndroLLM cloud gateway provider, e.g. openai/text-embedding-3-small.",
            label = "Model id",
            initial = settings.cloudEmbeddingModel,
            allowEmpty = true,
            onDismiss = { showCloudEmbeddingDialog = false },
            onSave = { id ->
                showCloudEmbeddingDialog = false
                settingsStore.setCloudEmbeddingModel(id)
                embeddingSettingsChanged()
            }
        )
    }

    if (showLogLevelDialog) {
        val levels = MemoryLogLevel.entries
        ChoiceDialog(
            title = "Inspector log level",
            options = levels.map { it.name },
            selected = settings.logLevel.name,
            onDismiss = { showLogLevelDialog = false },
            onSelect = { index ->
                pipeline.setLogLevel(levels[index])
                settings = settingsStore.current()
                showLogLevelDialog = false
            }
        )
    }

    if (showAddDialog) {
        TextInputDialog(
            title = "Add a memory",
            description = "Saved through the memory policy (secrets and instructions are refused; duplicates are merged).",
            label = "Fact to remember",
            initial = "",
            onDismiss = { showAddDialog = false },
            onSave = { text ->
                showAddDialog = false
                scope.launch {
                    message = pipeline.saveMemory(MemoryCategory.CUSTOM, text).fold(
                        { r ->
                            when (r.action) {
                                MemoryWriteAction.INSERTED -> "Memory saved."
                                MemoryWriteAction.UPDATED -> "Existing memory updated."
                                MemoryWriteAction.SKIPPED -> "Not saved: ${r.reason ?: "rejected by policy"}"
                            }
                        },
                        { "Save failed: ${it.message}" }
                    )
                    runCatching { reload() }
                }
            }
        )
    }

    if (showAddProjectDialog) {
        TextInputDialog(
            title = "New project",
            description = "Projects group related memories. Assign memories with the folder button.",
            label = "Project name",
            initial = "",
            onDismiss = { showAddProjectDialog = false },
            onSave = { name ->
                showAddProjectDialog = false
                scope.launch {
                    pipeline.createProject(name).onFailure { message = "Create project failed: ${it.message}" }
                    runCatching { reload() }
                }
            }
        )
    }

    if (showAddRelationshipDialog) {
        TextInputDialog(
            title = "Link two memories",
            description = "Format: <from id> <to id> [type], e.g. \"12 15 depends_on\". Ids are shown on each memory.",
            label = "Link",
            initial = "",
            onDismiss = { showAddRelationshipDialog = false },
            onSave = { raw ->
                showAddRelationshipDialog = false
                val parts = raw.trim().split(Regex("\\s+"))
                val from = parts.getOrNull(0)?.removePrefix("#")?.toLongOrNull()
                val to = parts.getOrNull(1)?.removePrefix("#")?.toLongOrNull()
                if (from == null || to == null) {
                    message = "Enter two memory ids, e.g. \"12 15\"."
                } else {
                    scope.launch {
                        message = pipeline.addRelationship(from, to, parts.getOrNull(2) ?: "related_to").fold(
                            { if (it == null) "Link already exists." else "Linked #$from → #$to." },
                            { "Link failed: ${it.message}" }
                        )
                        runCatching { reload() }
                    }
                }
            }
        )
    }

    pendingTagEdit?.let { memory ->
        TextInputDialog(
            title = "Tags",
            description = "Comma separated (max 10).",
            label = "Tags",
            initial = memory.tags.joinToString(", "),
            allowEmpty = true,
            onDismiss = { pendingTagEdit = null },
            onSave = { raw ->
                pendingTagEdit = null
                scope.launch {
                    pipeline.setTags(memory.id, raw.split(',')).onFailure { message = "Tag update failed: ${it.message}" }
                    runCatching { reload() }
                }
            }
        )
    }

    pendingProjectEdit?.let { memory ->
        val options = listOf(NO_PROJECT) + projects.map { it.name }
        ChoiceDialog(
            title = "Project",
            options = options,
            selected = memory.projectName ?: NO_PROJECT,
            onDismiss = { pendingProjectEdit = null },
            onSelect = { index ->
                pendingProjectEdit = null
                val name = options[index].takeIf { it != NO_PROJECT }
                scope.launch {
                    pipeline.setProject(memory.id, name).onFailure { message = "Project update failed: ${it.message}" }
                    runCatching { reload() }
                }
            }
        )
    }

    pendingProjectDelete?.let { project ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${project.name}\" will be removed. Its ${project.memoryCount} memories are kept (just unassigned).",
            confirmLabel = "Delete",
            onDismiss = { pendingProjectDelete = null },
            onConfirm = {
                pendingProjectDelete = null
                scope.launch {
                    pipeline.deleteProject(project.id).onFailure { message = "Delete project failed: ${it.message}" }
                    if (filters.projectId == project.id) {
                        filters = MemorySearchFilters()
                        filterLabel = null
                    }
                    runCatching { reload() }
                }
            }
        )
    }

    pendingDelete?.let { memory ->
        ConfirmDialog(
            title = "Delete memory?",
            message = "\"${memory.content.take(120)}\" will be permanently removed.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    pipeline.deleteMemory(memory.id).onFailure { message = "Delete failed: ${it.message}" }
                    runCatching { reload() }
                }
            }
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun GraphRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (selected) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskPaper,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.ledger.emberRed, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun InspectorBlock(stats: MemoryInspectorStats) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = buildString {
                append("Memories ${stats.memoryCount} • vectors ${stats.vectorCount} • neural rows ${stats.embeddingCount}\n")
                append("Projects ${stats.projectCount} • tags ${stats.tagCount} • links ${stats.relationshipCount} • summaries ${stats.summaryCount}\n")
                append("Retrieval last ${stats.lastRetrievalMs}ms / avg ${stats.avgRetrievalMs}ms • embedding ${stats.lastEmbeddingMs}ms\n")
                append("Extraction last ${stats.lastExtractionMs}ms • runs ${stats.totalExtractions} • +${stats.totalInserted} ~${stats.totalUpdated}\n")
                append("Embedder: ${stats.embedderSignature.ifBlank { "?" }} (${stats.embeddingSource})")
                if (stats.embeddingDimension > 0) append(" • dim ${stats.embeddingDimension}")
                if (stats.embeddingModelLoaded) append(" • LiteRT loaded")
            },
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk, fontFamily = FontFamily.Monospace)
        )
        if (stats.logs.isEmpty()) {
            Text(
                text = "No log entries yet.",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        stats.logs.take(MAX_LOGS_SHOWN).forEach { entry ->
            Text(
                text = "${formatClock(entry.timestamp)} ${entry.level.name.first()} ${entry.message}",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = when (entry.level) {
                        MemoryLogLevel.ERROR -> MaterialTheme.ledger.emberRed
                        MemoryLogLevel.WARN -> MaterialTheme.ledger.lampGlow
                        else -> MaterialTheme.ledger.deskInk
                    },
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun MemoryRow(
    memory: Memory,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onEditTags: () -> Unit,
    onEditProject: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (memory.isPinned) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (memory.isArchived) MaterialTheme.ledger.deskInk else MaterialTheme.ledger.deskPaper
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("#").append(memory.id).append(" • ")
                    append(memory.jarvisCategory)
                    if (memory.category != MemoryCategory.CUSTOM) append(" / ").append(memory.category.label)
                    append(" • ").append(memory.source)
                    if (memory.effectivePriority > 1) append(" • p").append(memory.effectivePriority)
                    if (memory.accessCount > 0) append(" • used ").append(memory.accessCount).append("x")
                    memory.projectName?.let { append(" • ").append(it) }
                    if (memory.tags.isNotEmpty()) append(" • ").append(memory.tags.joinToString(" ") { "#$it" })
                    if (memory.isArchived) append(" • archived")
                },
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (memory.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (memory.isPinned) "Unpin" else "Pin",
                tint = if (memory.isPinned) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onEditTags, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Tag, contentDescription = "Tags", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onEditProject, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = "Project", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onToggleArchive, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (memory.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                contentDescription = if (memory.isArchived) "Unarchive" else "Archive",
                tint = MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.ledger.emberRed,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private const val PAGE_SIZE = 20
private const val MAX_LINKS_SHOWN = 30
private const val MAX_LOGS_SHOWN = 40
private const val NO_PROJECT = "(none)"

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun formatClock(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
