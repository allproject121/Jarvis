package com.jarvis.assistant.ui.compose.feature.models

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.LocalModelDescriptor
import com.jarvis.assistant.androllm.models.InstalledLiteRtModel
import com.jarvis.assistant.androllm.models.benchmark.BenchmarkReport
import com.jarvis.assistant.androllm.models.benchmark.ModelBenchmarker
import com.jarvis.assistant.androllm.models.compatibility.CompatibilityRating
import com.jarvis.assistant.androllm.models.download.DownloadPriority
import com.jarvis.assistant.androllm.models.ModelArtifactFormat
import com.jarvis.assistant.androllm.models.RemoteModelDetails
import com.jarvis.assistant.androllm.models.RemoteModelFile
import com.jarvis.assistant.androllm.models.RemoteModelSummary
import com.jarvis.assistant.androllm.models.RemoteSearchFormat
import com.jarvis.assistant.androllm.models.SortBy
import com.jarvis.assistant.androllm.models.catalog.CatalogModel
import com.jarvis.assistant.androllm.models.catalog.ModelInspector
import com.jarvis.assistant.androllm.models.catalog.RecommendationEngine
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.ModelWalletCard
import com.jarvis.assistant.ui.compose.model.Model
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import org.json.JSONObject

/**
 * Model Manager — Installed models (GGUF + LiteRT), Download queue (pause / resume / cancel),
 * Catalog (Jarvis GGUF models + the AndroLLM LiteRT catalog), Hugging Face Hub search,
 * storage import (SAF), Hardware diagnostics and Benchmarking.
 * Ported from AndroLLM feature/models and wired to Jarvis' LocalModelManager / LocalAiEngine
 * and the com.jarvis.assistant.androllm.models layer.
 */
@Composable
fun ModelsRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ModelsViewModel = viewModel { ModelsViewModel(application) }
    ModelsScreen(viewModel = viewModel, navigate = navigate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(
    viewModel: ModelsViewModel,
    navigate: (String) -> Unit
) {
    val data by viewModel.uiState.collectAsStateWithLifecycle()

    var sortMenuExpanded by remember { mutableStateOf(false) }

    val installedListState = rememberLazyListState()
    val downloadsListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    val hfListState = rememberLazyListState()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    LaunchedEffect(Unit) { viewModel.refreshInstalled() }

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.MODELS,
            onTabSelected = { tab ->
                if (tab.route != ComposeRoutes.MODELS) navigate(tab.route)
            },
            topBar = {
                TopAppBar(
                    title = { Text("Model Manager", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort models", tint = MaterialTheme.ledger.deskInk)
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Name") },
                                    onClick = { viewModel.updateSortOption(ModelSortOption.NAME); sortMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by Size") },
                                    onClick = { viewModel.updateSortOption(ModelSortOption.SIZE); sortMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by RAM") },
                                    onClick = { viewModel.updateSortOption(ModelSortOption.RAM); sortMenuExpanded = false }
                                )
                            }
                        }
                        IconButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = data.importState == null
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = "Import model from storage (GGUF / LiteRT)",
                                tint = MaterialTheme.ledger.deskInk
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (data.selectedTab != ModelsTab.HUGGINGFACE) {
                    OutlinedTextField(
                        value = data.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text("Search models...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (data.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = modelsTextFieldColors()
                    )
                }

                val downloadCount = data.downloadRows.size
                ScrollableTabRow(
                    selectedTabIndex = data.selectedTab.ordinal,
                    edgePadding = 20.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.ledger.deskInk,
                    divider = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ModelsTabItem(data.selectedTab == ModelsTab.INSTALLED, "Installed (${data.installedCount})") {
                        viewModel.selectTab(ModelsTab.INSTALLED)
                    }
                    ModelsTabItem(data.selectedTab == ModelsTab.DOWNLOADS, "Downloads ($downloadCount)") {
                        viewModel.selectTab(ModelsTab.DOWNLOADS)
                    }
                    ModelsTabItem(data.selectedTab == ModelsTab.CATALOG, "Catalog (${data.rows.size + data.catalogModels.size})") {
                        viewModel.selectTab(ModelsTab.CATALOG)
                    }
                    ModelsTabItem(data.selectedTab == ModelsTab.HUGGINGFACE, "HuggingFace 🤗") {
                        viewModel.selectTab(ModelsTab.HUGGINGFACE)
                    }
                    ModelsTabItem(data.selectedTab == ModelsTab.DIAGNOSTICS, "Hardware") {
                        viewModel.selectTab(ModelsTab.DIAGNOSTICS)
                    }
                }

                data.importState?.let { imp ->
                    ImportProgressBanner(imp, onCancel = { viewModel.cancelImport() })
                }

                data.errorMessage?.let { error ->
                    MessageBanner(
                        text = error,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        onDismiss = { viewModel.dismissError() }
                    )
                }
                data.infoMessage?.let { info ->
                    MessageBanner(
                        text = info,
                        container = MaterialTheme.ledger.deskWalnutRaised,
                        content = MaterialTheme.ledger.deskPaper,
                        onDismiss = { viewModel.dismissInfo() }
                    )
                }

                when (data.selectedTab) {
                    ModelsTab.INSTALLED -> InstalledModelsTab(
                        data = data,
                        viewModel = viewModel,
                        onBrowseCatalog = { viewModel.selectTab(ModelsTab.CATALOG) },
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        listState = installedListState
                    )
                    ModelsTab.DOWNLOADS -> DownloadsTab(
                        data = data,
                        viewModel = viewModel,
                        listState = downloadsListState
                    )
                    ModelsTab.CATALOG -> CatalogTab(
                        data = data,
                        viewModel = viewModel,
                        listState = catalogListState
                    )
                    ModelsTab.HUGGINGFACE -> HuggingFaceTab(
                        data = data,
                        viewModel = viewModel,
                        listState = hfListState
                    )
                    ModelsTab.DIAGNOSTICS -> HardwareDiagnosticsTab(hardwareInfo = data.hardwareInfo)
                }
            }
        }

        data.benchmarkReport?.let { report ->
            BenchmarkReportDialog(
                report = report,
                onDismiss = { viewModel.dismissBenchmarkReport() }
            )
        }

        data.liteRtBenchmarkReport?.let { report ->
            LiteRtBenchmarkReportDialog(
                report = report,
                onDismiss = { viewModel.dismissLiteRtBenchmarkReport() }
            )
        }

        data.recommendation?.let { rec ->
            FirstLaunchRecommendationDialog(
                recommendation = rec,
                hardwareInfo = data.hardwareInfo,
                onDownloadGguf = { viewModel.acceptGgufRecommendation(it) },
                onDownloadLiteRt = { viewModel.acceptLiteRtRecommendation(it) },
                onDismiss = { viewModel.dismissRecommendation() }
            )
        }

        data.hf.selectedId?.let {
            HfDetailsDialog(
                hf = data.hf,
                data = data,
                viewModel = viewModel,
                onDismiss = { viewModel.closeHfDetails() }
            )
        }
    }
}

@Composable
private fun modelsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.ledger.lampDeep,
    unfocusedBorderColor = MaterialTheme.ledger.deskHairline,
    focusedContainerColor = MaterialTheme.ledger.deskWalnutRaised,
    unfocusedContainerColor = MaterialTheme.ledger.deskWalnutRaised,
    cursorColor = MaterialTheme.ledger.lampDeep,
    focusedLeadingIconColor = MaterialTheme.ledger.deskInk,
    unfocusedLeadingIconColor = MaterialTheme.ledger.deskInk,
    focusedTrailingIconColor = MaterialTheme.ledger.deskInk,
    unfocusedTrailingIconColor = MaterialTheme.ledger.deskInk,
    focusedTextColor = MaterialTheme.ledger.deskPaper,
    unfocusedTextColor = MaterialTheme.ledger.deskPaper
)

@Composable
private fun MessageBanner(text: String, container: Color, content: Color, onDismiss: () -> Unit) {
    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Cancel, contentDescription = "Dismiss", tint = content)
            }
        }
    }
}

@Composable
private fun ImportProgressBanner(state: ImportUiState, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.ledger.deskWalnutRaised, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.totalBytes > 0) {
                        "Importing model… ${state.copiedBytes.formatSize()} / ${state.totalBytes.formatSize()}"
                    } else {
                        "Importing model… ${state.copiedBytes.formatSize()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ledger.deskPaper,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (state.copiedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
            }
        }
    }
}

@Composable
private fun ModelsTabItem(selected: Boolean, label: String, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(label) },
        selectedContentColor = MaterialTheme.ledger.lampDeep,
        unselectedContentColor = MaterialTheme.ledger.deskInk
    )
}

private fun LocalModelDescriptor.toUiModel(contextLength: Int, inspection: ModelInspector.Inspection?): Model = Model(
    name = name,
    fileSize = downloadSizeBytes,
    parameters = inspection?.parameters ?: parameters,
    quantization = inspection?.quantization ?: quantization.removePrefix("GGUF").trim(),
    contextLength = contextLength,
    family = inspection?.family ?: provider.substringBefore("/").substringBefore("·").trim(),
    recommendedRamGb = recommendedMinRamGb.toFloat()
)

// ─────────────────────────────── Installed ───────────────────────────────

@Composable
private fun InstalledModelsTab(
    data: ModelsUiState,
    viewModel: ModelsViewModel,
    onBrowseCatalog: () -> Unit,
    onImport: () -> Unit,
    listState: LazyListState
) {
    val installedOnly = data.installedRows
    val liteRtInstalled = data.installedLiteRtVisible
    val runtimeContext = remember { LocalAiConfig().contextWindowLimit }

    if (data.isScanning) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.ledger.lampAmber)
        }
        return
    }

    if (installedOnly.isEmpty() && liteRtInstalled.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.deskInk,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = if (data.searchQuery.isBlank()) "No Installed Models" else "No installed models match your search.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
                Text(
                    text = "Download a GGUF model for Jarvis' llama.cpp engine or a LiteRT model from the Catalog, search Hugging Face, or import a model file from storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ledger.deskInk,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBrowseCatalog) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Catalog")
                    }
                    OutlinedButton(onClick = onImport) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import")
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "backend_info") {
            BackendInfoCard(liteRtEnabled = data.liteRtEngineEnabled)
        }

        if (data.engineState !is LocalEngineState.Uninitialized) {
            item(key = "status_dashboard") {
                val activeName = data.rows.firstOrNull { it.id == data.activeModelId }?.descriptor?.name ?: data.activeModelId
                ModelStatusDashboard(
                    engineState = data.engineState,
                    activeModelName = activeName,
                    onUnload = { viewModel.unloadModel() },
                    onRetry = { viewModel.loadModel(data.activeModelId) }
                )
            }
        }

        if (installedOnly.isNotEmpty()) {
            item(key = "gguf_header") { SectionHeader("GGUF · llama.cpp (${installedOnly.size})") }
        }
        items(installedOnly, key = { "gguf_${it.id}" }) { row ->
            val isLoaded = row.id == data.loadedModelId
            val isSelected = row.id == data.activeModelId
            val benchmarking = data.benchmarkRunningId == row.id
            val file = viewModel.ggufFile(row.id)
            val key = ModelsViewModel.inspectionKey(file)
            val inspection = data.inspections[key]
            val inspectionError = data.inspectionErrors[key]
            val isFavorite = row.id in data.favorites
            Column {
                ModelWalletCard(
                    model = row.descriptor.toUiModel(runtimeContext, inspection),
                    isActive = isLoaded,
                    isDownloaded = row.isDownloaded,
                    activeContextLength = if (isLoaded) runtimeContext else null,
                    onLoadClick = {
                        if (isLoaded) viewModel.unloadModel() else viewModel.loadModel(row.id)
                    },
                    onDownloadClick = { viewModel.downloadModel(row.id) },
                    menuItems = {
                        DropdownMenuItem(
                            text = { Text(if (isSelected) "Active Model" else "Set as Active Model") },
                            onClick = { viewModel.setActiveModel(row.id) },
                            enabled = !isSelected,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                            onClick = { viewModel.toggleFavorite(row.id) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (benchmarking) "Benchmark running…" else "Run Benchmark") },
                            onClick = { viewModel.runBenchmark(row.id) },
                            enabled = data.benchmarkRunningId == null,
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Model", color = MaterialTheme.colorScheme.error) },
                            onClick = { viewModel.deleteModel(row.id) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                InspectionStrip(
                    format = ModelArtifactFormat.GGUF,
                    inspection = inspection,
                    error = inspectionError,
                    extraBadges = buildList {
                        if (isFavorite) add("★ Favorite")
                        if (row.isCustom) add(if (row.descriptor.downloadUrl.isBlank()) "Imported" else "Hugging Face")
                    }
                )
                if (benchmarking) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.ledger.lampAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Benchmarking ${row.descriptor.name}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                }
            }
        }

        if (liteRtInstalled.isNotEmpty()) {
            item(key = "litert_header") {
                Column {
                    SectionHeader("LiteRT (${liteRtInstalled.size})")
                    if (!data.liteRtEngineEnabled) {
                        Text(
                            text = "The LiteRT engine is turned off. Enable it in Settings to run these models; Gemini Live stays the primary brain.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.ledger.deskInkFaint
                        )
                    }
                }
            }
        }
        items(liteRtInstalled, key = { "litert_${it.path}" }) { model ->
            InstalledLiteRtCard(
                model = model,
                inspection = data.inspections[ModelsViewModel.liteRtInspectionKey(model)],
                inspectionError = data.inspectionErrors[ModelsViewModel.liteRtInspectionKey(model)],
                isPreferred = data.preferredLiteRtPath == model.path,
                isFavorite = (model.catalogModel?.id ?: model.file.name) in data.favorites,
                onTogglePreferred = { viewModel.togglePreferredLiteRt(model) },
                onToggleFavorite = { viewModel.toggleFavorite(model.catalogModel?.id ?: model.file.name) },
                onDelete = { viewModel.deleteLiteRt(model) },
                isBenchmarking = data.liteRtBenchmarkRunningPath == model.path,
                benchmarkEnabled = data.liteRtBenchmarkRunningPath == null && data.benchmarkRunningId == null,
                onBenchmark = { viewModel.runLiteRtBenchmark(model) }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.ledger.lampDeep
    )
}

/** Real model facts parsed from the file bytes (GgufReader / LiteRT container header). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectionStrip(
    format: ModelArtifactFormat,
    inspection: ModelInspector.Inspection?,
    error: String?,
    extraBadges: List<String> = emptyList()
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CatalogBadgePill(format.label)
        extraBadges.forEach { CatalogBadgePill(it) }
        when {
            inspection != null -> {
                if (inspection.architecture.isNotBlank() && inspection.architecture != "unknown") {
                    ModelMetaPill("arch ${inspection.architecture}", leadingIcon = Icons.Default.Memory)
                }
                if (inspection.quantization.isNotBlank() && inspection.quantization != "UNKNOWN") {
                    ModelMetaPill(inspection.quantization, leadingIcon = Icons.Default.Storage)
                }
                if (inspection.contextLength > 0) {
                    ModelMetaPill("${inspection.contextLength} ctx", leadingIcon = Icons.Default.History)
                }
                if (inspection.blockCount > 0) ModelMetaPill("${inspection.blockCount} layers")
                if (inspection.tensorCount > 0) ModelMetaPill("${inspection.tensorCount} tensors")
                if (inspection.isMoe) ModelMetaPill("MoE · ${inspection.expertCount} experts")
                inspection.tokenizerModel?.takeIf { it.isNotBlank() }?.let { ModelMetaPill("tokenizer $it") }
                if (!inspection.chatTemplate.isNullOrBlank()) ModelMetaPill("chat template ✓")
                if (inspection.containerVersion > 0) ModelMetaPill("container v${inspection.containerVersion}")
            }
            error != null -> CatalogBadgePill("⚠ $error")
        }
    }
}

@Composable
private fun InstalledLiteRtCard(
    model: InstalledLiteRtModel,
    inspection: ModelInspector.Inspection?,
    inspectionError: String?,
    isPreferred: Boolean,
    isFavorite: Boolean,
    onTogglePreferred: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    isBenchmarking: Boolean = false,
    benchmarkEnabled: Boolean = true,
    onBenchmark: (() -> Unit)? = null
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${model.file.name} • ${model.sizeBytes.formatSize()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                        tint = MaterialTheme.ledger.lampDeep
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            InspectionStrip(
                format = model.format,
                inspection = inspection,
                error = inspectionError,
                extraBadges = buildList {
                    if (isPreferred) add("⭐ Preferred LiteRT model")
                    model.catalogModel?.let { add(it.parameters.ifBlank { it.family }) }
                }
            )
            inspection?.compatibilityNote?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.ledger.deskInkFaint)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTogglePreferred, modifier = Modifier.weight(1f)) {
                    Icon(if (isPreferred) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isPreferred) "Preferred" else "Set preferred")
                }
                if (onBenchmark != null && model.format != ModelArtifactFormat.TFLITE) {
                    TextButton(onClick = onBenchmark, enabled = benchmarkEnabled && !isBenchmarking) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isBenchmarking) "Benchmarking…" else "Benchmark")
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("${model.file.name} (${model.sizeBytes.formatSize()}) will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/** Jarvis runs GGUF weights on llama.cpp; LiteRT artifacts run on the optional LiteRT-LM engine. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackendInfoCard(liteRtEnabled: Boolean) {
    val config = remember { LocalAiConfig() }
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column {
            Text(
                text = "Execution Backends",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.ledger.deskPaper
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "GGUF weights run on Jarvis' llama.cpp CPU engine. LiteRT models (.litertlm / .tflite) run on the LiteRT engine " +
                    if (liteRtEnabled) "(enabled)." else "(currently disabled in Settings).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.ledger.deskInk
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelMetaPill("CPU · ${config.threads} threads", leadingIcon = Icons.Default.Memory)
                ModelMetaPill("${config.contextWindowLimit} ctx tokens", leadingIcon = Icons.Default.History)
                ModelMetaPill("max ${config.maxTokens} new tokens", leadingIcon = Icons.Default.Speed)
                ModelMetaPill(if (liteRtEnabled) "LiteRT on" else "LiteRT off", leadingIcon = Icons.Default.Info)
            }
        }
    }
}

@Composable
private fun ModelStatusDashboard(
    engineState: LocalEngineState,
    activeModelName: String,
    onUnload: () -> Unit,
    onRetry: () -> Unit
) {
    val success = MaterialTheme.ledger.revolutNeonEmerald
    val (statusLabel, statusColor, showProgress) = when (engineState) {
        LocalEngineState.Loading -> Triple("Loading: $activeModelName", MaterialTheme.ledger.lampAmber, true)
        LocalEngineState.Ready -> Triple("🟢 Engine Ready", success, false)
        LocalEngineState.Generating -> Triple("● Generating", MaterialTheme.ledger.lampAmber, false)
        is LocalEngineState.Error -> Triple("● Error: ${engineState.message}", MaterialTheme.ledger.emberRed, false)
        LocalEngineState.Uninitialized -> Triple("No Model", MaterialTheme.ledger.deskInk, false)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Engine Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                }
                if (engineState is LocalEngineState.Ready || engineState is LocalEngineState.Generating) {
                    OutlinedButton(
                        onClick = onUnload,
                        enabled = engineState !is LocalEngineState.Generating
                    ) {
                        Text("Unload", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )
            if (engineState is LocalEngineState.Ready || engineState is LocalEngineState.Generating) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = activeModelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ledger.deskInk
                )
            }
            if (engineState is LocalEngineState.Error) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onRetry) {
                    Text("Retry Load", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (showProgress) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
            }
        }
    }
}

// ─────────────────────────────── Downloads ───────────────────────────────

@Composable
private fun DownloadsTab(
    data: ModelsUiState,
    viewModel: ModelsViewModel,
    listState: LazyListState
) {
    val rows = data.downloadRows
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.deskInk,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "No Active Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
                Text(
                    text = "Browse the Catalog or Hugging Face to download GGUF and LiteRT models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ledger.deskInk,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                BackgroundDownloadsCard(data = data, viewModel = viewModel)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Download Queue (${rows.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.deskPaper,
                modifier = Modifier.weight(1f)
            )
            if (rows.any { it.status == DownloadRowStatus.DOWNLOADING }) {
                TextButton(onClick = { viewModel.pauseAll() }) { Text("Pause all") }
            }
            if (rows.any { it.status == DownloadRowStatus.PAUSED || it.status == DownloadRowStatus.ERROR }) {
                TextButton(onClick = { viewModel.resumeAll() }) { Text("Resume all") }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Paused and interrupted downloads keep their partial file and resume with an HTTP Range request. Progress is also shown in the notification shade.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskInk
        )
        Spacer(modifier = Modifier.height(8.dp))
        BackgroundDownloadsCard(data = data, viewModel = viewModel)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows, key = { "${it.format.name}_${it.id}" }) { row ->
                DownloadCard(
                    row = row,
                    onPause = { viewModel.pauseDownload(row) },
                    onResume = { viewModel.resumeDownload(row) },
                    onCancel = { viewModel.cancelDownload(row) }
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    row: DownloadRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val total = row.totalBytes
    val running = row.status == DownloadRowStatus.DOWNLOADING || row.status == DownloadRowStatus.VERIFYING
    var confirmCancel by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = row.format.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.lampDeep
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                StatusBadge(status = row.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if ((running || row.status == DownloadRowStatus.QUEUED) && total <= 0L || row.status == DownloadRowStatus.VERIFYING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
            } else {
                LinearProgressIndicator(
                    progress = { (row.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = if (row.status == DownloadRowStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (total > 0) {
                        "${row.bytesDownloaded.formatSize()} / ${total.formatSize()} (${row.percent}%)"
                    } else {
                        row.bytesDownloaded.formatSize()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ledger.deskInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.status == DownloadRowStatus.DOWNLOADING) {
                    Text(
                        text = if (row.speedBytesPerSec > 0f) {
                            "${row.speedBytesPerSec.formatSpeed()} • ${if (row.etaSeconds > 0) "${row.etaSeconds.formatEta()} left" else "Calculating..."}"
                        } else {
                            "Calculating..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.lampDeep,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            row.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == DownloadRowStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.ledger.deskInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (row.status) {
                    DownloadRowStatus.DOWNLOADING, DownloadRowStatus.QUEUED -> {
                        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }
                    DownloadRowStatus.VERIFYING -> {
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                            Text("Verifying…")
                        }
                    }
                    DownloadRowStatus.PAUSED, DownloadRowStatus.ERROR -> {
                        Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (row.status == DownloadRowStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (row.status == DownloadRowStatus.PAUSED) "Resume" else "Retry")
                        }
                    }
                }
                TextButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel")
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel download?") },
            text = { Text("${row.name}: the partially downloaded data (${row.bytesDownloaded.formatSize()}) will be deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("Cancel download") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun StatusBadge(status: DownloadRowStatus) {
    val color = when (status) {
        DownloadRowStatus.DOWNLOADING, DownloadRowStatus.VERIFYING -> MaterialTheme.ledger.lampAmber
        DownloadRowStatus.QUEUED, DownloadRowStatus.PAUSED -> MaterialTheme.ledger.deskInk
        DownloadRowStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(status.label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.15f))
    )
}

// ─────────────────────────────── Catalog ───────────────────────────────

@Composable
private fun CatalogTab(
    data: ModelsUiState,
    viewModel: ModelsViewModel,
    listState: LazyListState
) {
    if (data.isScanning) {
        CatalogLoadingPlaceholder()
        return
    }

    val showRecommended = data.searchQuery.isBlank() &&
        data.catalogFilter == JarvisCatalogFilter.ALL &&
        data.formatFilter != CatalogFormatFilter.LITERT &&
        data.recommendedId != null
    val recommendedRow = if (showRecommended) data.rows.find { it.id == data.recommendedId } else null
    val visible = data.catalogRows.filter { it.id != recommendedRow?.id }
    val liteRtRows = data.liteRtCatalogRows
    val activeIds = data.activeDownloadIds

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "catalog_header") {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Model Catalog (${data.rows.size + data.catalogModels.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.lampDeep
                    )
                    TextButton(
                        onClick = { viewModel.refreshCatalog(); viewModel.refreshInstalled() },
                        enabled = !data.catalogRefreshing
                    ) {
                        if (data.catalogRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Update")
                    }
                }
                (data.catalogError ?: data.catalogStatus)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (data.catalogError != null) MaterialTheme.colorScheme.error else MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
        }

        item(key = "format_chips") {
            ChipRow {
                CatalogFormatFilter.values().forEach { option ->
                    FilterChip(
                        selected = data.formatFilter == option,
                        onClick = { viewModel.updateFormatFilter(option) },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item(key = "sort_chips") {
            ChipRow {
                JarvisCatalogSort.values().forEach { option ->
                    FilterChip(
                        selected = data.catalogSort == option,
                        onClick = { viewModel.updateCatalogSort(option) },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        item(key = "filter_chips") {
            ChipRow {
                JarvisCatalogFilter.values().forEach { filter ->
                    FilterChip(
                        selected = data.catalogFilter == filter,
                        onClick = { viewModel.updateCatalogFilter(filter) },
                        label = { Text(filter.label) }
                    )
                }
            }
        }

        if (recommendedRow != null) {
            item(key = "rec_header") {
                Text(
                    text = "⭐ Recommended for your device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.lampDeep
                )
            }
            item(key = "rec_${recommendedRow.id}") {
                CatalogModelCard(
                    row = recommendedRow,
                    recommended = true,
                    fitsDevice = data.fitsDevice(recommendedRow.descriptor),
                    isFavorite = recommendedRow.id in data.favorites,
                    isDownloading = recommendedRow.id in activeIds,
                    onDownload = { viewModel.downloadModel(recommendedRow.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(recommendedRow.id) }
                )
            }
            item(key = "divider") {
                HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            }
        }

        if (data.formatFilter != CatalogFormatFilter.LITERT) {
            item(key = "gguf_section") {
                SectionHeader("GGUF · Jarvis llama.cpp (${visible.size + if (recommendedRow != null) 1 else 0})")
            }
            items(visible, key = { "gguf_${it.id}" }) { row ->
                CatalogModelCard(
                    row = row,
                    recommended = false,
                    fitsDevice = data.fitsDevice(row.descriptor),
                    isFavorite = row.id in data.favorites,
                    isDownloading = row.id in activeIds,
                    onDownload = { viewModel.downloadModel(row.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(row.id) }
                )
            }
        }

        if (data.formatFilter != CatalogFormatFilter.GGUF) {
            item(key = "litert_section") {
                Column {
                    SectionHeader("LiteRT · AndroLLM catalog (${liteRtRows.size})")
                    Text(
                        text = if (data.liteRtEngineEnabled) {
                            "Runs on the LiteRT-LM engine. Files are saved to the LiteRT models folder."
                        } else {
                            "Runs on the optional LiteRT-LM engine (currently disabled in Settings). You can download now and enable it later."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
            items(liteRtRows, key = { "litert_${it.id}" }) { row ->
                LiteRtCatalogCard(
                    row = row,
                    isFavorite = row.id in data.favorites,
                    isDownloading = row.id in activeIds,
                    onDownload = { viewModel.downloadLiteRt(row.model) },
                    onToggleFavorite = { viewModel.toggleFavorite(row.id) },
                    onDownloadWithPriority = { priority -> viewModel.downloadLiteRt(row.model, priority) }
                )
            }
        }

        if (visible.isEmpty() && recommendedRow == null && liteRtRows.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No models match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun CatalogModelCard(
    row: JarvisModelRow,
    recommended: Boolean,
    fitsDevice: Boolean,
    isFavorite: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val wide = wideSize()
    val d = row.descriptor
    val subtitle = "${d.quantization.removePrefix("GGUF").trim()} • ${d.formattedSize}".trim(' ', '•')
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    CatalogCardBody(row, recommended, fitsDevice, isFavorite, onToggleFavorite, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(20.dp))
                    CatalogModelAction(subtitle, row.isDownloaded, isDownloading, row.hasPartialDownload, onDownload)
                }
            } else {
                CatalogCardBody(row, recommended, fitsDevice, isFavorite, onToggleFavorite)
                Spacer(modifier = Modifier.height(14.dp))
                CatalogModelAction(
                    subtitle, row.isDownloaded, isDownloading, row.hasPartialDownload, onDownload,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CardTitleRow(title: String, subtitle: String, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.deskPaper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                tint = MaterialTheme.ledger.lampDeep
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCardBody(
    row: JarvisModelRow,
    recommended: Boolean,
    fitsDevice: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val model = row.descriptor
    Column(modifier = modifier) {
        CardTitleRow(
            title = model.name,
            subtitle = listOf(model.parameters, model.provider).filter { it.isNotBlank() }.joinToString(" • "),
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = model.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ledger.deskInk,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        val badges = buildList {
            add(ModelArtifactFormat.GGUF.label)
            if (recommended) add("⭐ Recommended")
            if (row.isDownloaded) add("✓ Installed")
            if (!fitsDevice) add("⚠ Needs more RAM")
            if (row.hasPartialDownload && !row.isDownloaded) add("Partial download")
            if (row.isCustom) add(if (model.downloadUrl.isBlank()) "Imported" else "Hugging Face")
            add("Offline")
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            badges.forEach { CatalogBadgePill(it) }
        }

        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelMetaPill("RAM ${model.recommendedMinRamGb}+ GB", leadingIcon = Icons.Default.Speed)
            if (model.downloadSizeBytes > 0) ModelMetaPill(model.downloadSizeBytes.formatSize(), leadingIcon = Icons.Default.Storage)
            if (model.quantization.isNotBlank()) {
                ModelMetaPill(model.quantization, leadingIcon = Icons.Default.Memory)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiteRtCatalogCard(
    row: LiteRtCatalogRow,
    isFavorite: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadWithPriority: ((DownloadPriority) -> Unit)? = null
) {
    val m = row.model
    val wide = wideSize()
    val subtitle = "${m.quantization} • ${m.sizeBytes.formatSize()}"
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            CardTitleRow(
                title = m.name,
                subtitle = listOf(m.parameters, m.family, m.author).filter { it.isNotBlank() }.joinToString(" • "),
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = m.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val badges = buildList {
                add(row.format.label)
                when (row.recommendation?.tier) {
                    RecommendationEngine.Tier.RECOMMENDED -> add("🟢 Recommended")
                    RecommendationEngine.Tier.POSSIBLE -> add("🟡 Possible")
                    RecommendationEngine.Tier.NOT_RECOMMENDED -> add("🔴 Heavy for this device")
                    null -> Unit
                }
                if (row.installed != null) add("✓ Installed")
                if (row.hasPartial && row.installed == null) add("Partial download")
                if (!row.gate.compatible) add("⚠ Incompatible")
                if (m.isGated) add("Gated")
                if (m.supportsImageInput) add("Vision")
                if (m.supportsAudioInput) add("Audio")
                if (m.supportsToolCalling) add("Tool calling")
                m.sections.take(3).forEach { add(it) }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                badges.forEach { CatalogBadgePill(it) }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelMetaPill("RAM ~${formatMbAsGb(m.estimatedRuntimeRamMbValue)}", leadingIcon = Icons.Default.Speed)
                ModelMetaPill(m.sizeBytes.formatSize(), leadingIcon = Icons.Default.Storage)
                ModelMetaPill("${m.contextLength} ctx", leadingIcon = Icons.Default.History)
                m.expectedTokSec?.let { ModelMetaPill(it, leadingIcon = Icons.Default.Speed) }
            }
            if (!row.gate.compatible) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(row.gate.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            row.analysis?.takeIf { row.gate.compatible }?.let { analysis ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = analysis.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (analysis.rating) {
                        CompatibilityRating.EXCELLENT -> MaterialTheme.ledger.lampDeep
                        CompatibilityRating.MODERATE -> MaterialTheme.ledger.deskPaper
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = analysis.summary + " Est. ~${analysis.estimatedMemoryMb} MB" +
                        if (analysis.estimatedTokPerSec > 0f) " · ~${analysis.estimatedTokPerSec} tok/s" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.deskInk
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            CatalogModelAction(
                subtitle = subtitle,
                isDownloaded = row.installed != null,
                isDownloading = isDownloading,
                hasPartial = row.hasPartial,
                onDownload = onDownload,
                modifier = if (wide) Modifier else Modifier.fillMaxWidth(),
                enabled = row.gate.compatible
            )
            if (onDownloadWithPriority != null && row.gate.compatible && row.installed == null && !isDownloading) {
                TextButton(onClick = { onDownloadWithPriority(DownloadPriority.HIGH) }) {
                    Text("Download first (high priority)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatMbAsGb(mb: Long): String =
    if (mb >= 1000) String.format(java.util.Locale.US, "%.1f GB", mb / 1000.0) else "$mb MB"

private enum class CatalogActionState { INSTALLED, DOWNLOADING, DOWNLOAD }

@Composable
private fun CatalogModelAction(
    subtitle: String,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    hasPartial: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val target = when {
        isDownloaded -> CatalogActionState.INSTALLED
        isDownloading -> CatalogActionState.DOWNLOADING
        else -> CatalogActionState.DOWNLOAD
    }
    AnimatedContent(
        targetState = target,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "catalogAction"
    ) { state ->
        when (state) {
            CatalogActionState.INSTALLED -> OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = modifier
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Installed")
            }
            CatalogActionState.DOWNLOADING -> Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.ledger.deskWalnutRaised,
                modifier = modifier
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.ledger.lampAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Downloading…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.ledger.deskPaper)
                }
            }
            CatalogActionState.DOWNLOAD -> Button(
                onClick = onDownload,
                enabled = enabled,
                modifier = modifier
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(if (hasPartial) "Resume Download" else "Download", style = MaterialTheme.typography.labelLarge)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────── Hugging Face ───────────────────────────────

@Composable
private fun HuggingFaceTab(
    data: ModelsUiState,
    viewModel: ModelsViewModel,
    listState: LazyListState
) {
    val hf = data.hf
    LaunchedEffect(Unit) {
        if (!hf.searched && !hf.loading) viewModel.searchHf()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = hf.query,
            onValueChange = { viewModel.updateHfQuery(it) },
            placeholder = { Text(if (hf.format == RemoteSearchFormat.GGUF) "Search GGUF models on Hugging Face…" else "Search LiteRT models on Hugging Face…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { viewModel.searchHf() }) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "Search")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchHf() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = modelsTextFieldColors()
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChipRow {
                RemoteSearchFormat.values().forEach { f ->
                    FilterChip(
                        selected = hf.format == f,
                        onClick = { viewModel.setHfFormat(f) },
                        label = { Text(f.label) }
                    )
                }
            }
            ChipRow {
                SortBy.values().forEach { s ->
                    FilterChip(
                        selected = hf.sort == s,
                        onClick = { viewModel.setHfSort(s) },
                        label = { Text(s.label) }
                    )
                }
            }
        }

        when {
            hf.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.ledger.lampAmber)
            }
            hf.error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hf.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = { viewModel.searchHf() }) { Text("Retry") }
                }
            }
            hf.searched && hf.results.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No repositories found.", color = MaterialTheme.ledger.deskInk)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "hf_note") {
                    Text(
                        text = if (hf.format == RemoteSearchFormat.GGUF) {
                            "GGUF files run on Jarvis' llama.cpp engine. Single-file GGUFs only (split shards are hidden)."
                        } else {
                            "LiteRT files (.litertlm / .tflite / .task) are saved for the LiteRT engine."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
                items(hf.results, key = { it.id }) { summary ->
                    HfResultCard(summary = summary, onClick = { viewModel.openHfDetails(summary.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HfResultCard(summary: RemoteModelSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.deskPaper,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CatalogBadgePill(summary.family)
                ModelMetaPill("⬇ ${formatCount(summary.downloads)}")
                ModelMetaPill("♥ ${formatCount(summary.likes)}")
                if (summary.isGated) CatalogBadgePill("Gated")
                if (summary.lastModified.isNotBlank()) ModelMetaPill(summary.lastModified.take(10))
            }
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun HfDetailsDialog(
    hf: HfUiState,
    data: ModelsUiState,
    viewModel: ModelsViewModel,
    onDismiss: () -> Unit
) {
    val details = hf.details
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details?.name ?: hf.selectedId.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    hf.detailsLoading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.ledger.lampAmber)
                    }
                    hf.detailsError != null -> Text(hf.detailsError, color = MaterialTheme.colorScheme.error)
                    details != null -> HfDetailsBody(details, data, viewModel)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun HfDetailsBody(details: RemoteModelDetails, data: ModelsUiState, viewModel: ModelsViewModel) {
    Text(
        text = "${details.author} • License: ${details.license} • ⬇ ${formatCount(details.downloads)} • ♥ ${formatCount(details.likes)}",
        style = MaterialTheme.typography.bodySmall
    )
    if (details.isGated) {
        Text(
            text = "This repository is gated: downloads require accepting its license on huggingface.co and may fail without a token.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    val files = if (data.hf.format == RemoteSearchFormat.GGUF) details.ggufFiles else details.liteRtFiles
    if (files.isEmpty()) {
        Text(
            text = "No downloadable ${if (data.hf.format == RemoteSearchFormat.GGUF) "single-file GGUF" else "LiteRT"} files in this repository.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val activeIds = data.activeDownloadIds
    files.forEach { file ->
        val installed = when (file.format) {
            ModelArtifactFormat.GGUF -> data.rows.any { it.id == viewModel.hfGgufId(details.id, file) && it.isDownloaded }
            else -> data.installedLiteRt.any { it.file.name.equals(file.filename.substringAfterLast('/'), ignoreCase = true) }
        }
        val downloading = when (file.format) {
            ModelArtifactFormat.GGUF -> viewModel.hfGgufId(details.id, file) in activeIds
            else -> "hf-${details.id}-${file.filename}" in activeIds
        }
        HfFileRow(
            file = file,
            ramGb = data.totalRamGb,
            installed = installed,
            downloading = downloading,
            onDownload = { viewModel.downloadHfFile(details, file) }
        )
    }
}

@Composable
private fun HfFileRow(
    file: RemoteModelFile,
    ramGb: Float,
    installed: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    val sizeGb = file.sizeBytes / (1024f * 1024f * 1024f)
    val heavy = ramGb > 0f && file.format == ModelArtifactFormat.GGUF && sizeGb + 1f > ramGb
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.filename.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOf(
                        file.format.label,
                        file.quantization,
                        if (file.sizeBytes > 0) file.sizeBytes.formatSize() else "size unknown",
                        if (file.sha256 != null) "SHA-256 ✓" else ""
                    ).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall
                )
                if (heavy) {
                    Text("⚠ Larger than this device's RAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            when {
                installed -> Icon(Icons.Default.CheckCircle, contentDescription = "Installed", tint = MaterialTheme.ledger.revolutNeonEmerald)
                downloading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download ${file.filename}")
                }
            }
        }
    }
}

// ─────────────────────────────── First launch ───────────────────────────────

@Composable
private fun FirstLaunchRecommendationDialog(
    recommendation: FirstLaunchRecommendation,
    hardwareInfo: DeviceHardwareInfo?,
    onDownloadGguf: (String) -> Unit,
    onDownloadLiteRt: (CatalogModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recommended on-device models") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Gemini Live remains Jarvis' primary brain. An on-device model adds an offline fallback" +
                        (hardwareInfo?.let { " — picked for your ${String.format(java.util.Locale.US, "%.1f", it.totalRamGb)} GB RAM device" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall
                )
                recommendation.gguf?.let { d ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.ledger.deskWalnutRaised) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${d.name}", fontWeight = FontWeight.Bold)
                            Text("${ModelArtifactFormat.GGUF.label} • ${d.formattedSize} • RAM ${d.recommendedMinRamGb}+ GB", style = MaterialTheme.typography.labelSmall)
                            Text(d.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Button(onClick = { onDownloadGguf(d.id) }) { Text("Download for Jarvis") }
                        }
                    }
                }
                recommendation.liteRt?.let { rec ->
                    val m = rec.model
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.ledger.deskWalnutRaised) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            Text("${ModelArtifactFormat.LITERTLM.label} • ${m.sizeBytes.formatSize()}", style = MaterialTheme.typography.labelSmall)
                            rec.reasons.take(3).forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
                            OutlinedButton(onClick = { onDownloadLiteRt(m) }) { Text("Download LiteRT model") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}

@Composable
private fun ModelMetaPill(text: String, leadingIcon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.ledger.deskWalnutDeep
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.ledger.lampDeep
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.ledger.deskInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class BadgeTint(val container: Color, val content: Color, val border: Color)

@Composable
private fun badgeTint(badge: String): BadgeTint = when {
    badge.contains("Recommended") || badge.contains("Installed") -> BadgeTint(
        container = MaterialTheme.ledger.revolutNeonEmerald.copy(alpha = 0.14f),
        content = MaterialTheme.ledger.revolutNeonEmerald,
        border = MaterialTheme.ledger.revolutNeonEmerald.copy(alpha = 0.40f)
    )
    badge.contains("RAM") -> BadgeTint(
        container = MaterialTheme.ledger.emberRed.copy(alpha = 0.12f),
        content = MaterialTheme.ledger.emberRed,
        border = MaterialTheme.ledger.emberRed.copy(alpha = 0.40f)
    )
    else -> BadgeTint(
        container = MaterialTheme.ledger.cloudWhite.copy(alpha = 0.08f),
        content = MaterialTheme.ledger.lampAmber,
        border = MaterialTheme.ledger.cloudWhite.copy(alpha = 0.20f)
    )
}

@Composable
private fun CatalogBadgePill(badge: String) {
    val tint = badgeTint(badge)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.container,
        contentColor = tint.content,
        border = BorderStroke(1.dp, tint.border)
    ) {
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun wideSize(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
private fun CatalogLoadingPlaceholder() {
    val pulse = rememberInfiniteTransition(label = "catalogSkeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.ledger.deskWalnut),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SkeletonBar(fraction = 0.6f, barHeight = 22.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonBar(fraction = 0.35f, barHeight = 13.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(16.dp))
                    SkeletonBar(fraction = 1f, barHeight = 12.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(fraction = 0.8f, barHeight = 12.dp, alpha = pulse.value)
                    Spacer(modifier = Modifier.height(16.dp))
                    SkeletonBar(fraction = 0.42f, barHeight = 44.dp, alpha = pulse.value)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, barHeight: Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(barHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.ledger.deskWalnutDeep.copy(alpha = alpha))
    )
}

// ─────────────────────────────── Hardware ───────────────────────────────

@Composable
private fun HardwareDiagnosticsTab(hardwareInfo: DeviceHardwareInfo?) {
    if (hardwareInfo == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.ledger.lampAmber)
        }
        return
    }
    val config = remember { LocalAiConfig() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Device Hardware & Capability",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagnosticRow("Device", "${hardwareInfo.manufacturer} ${hardwareInfo.deviceName}")
                    DiagnosticRow("Android Version", "Android ${hardwareInfo.androidVersion} (API ${hardwareInfo.apiLevel})")
                    DiagnosticRow("CPU Architecture", "${hardwareInfo.abi} (${hardwareInfo.cpuCores} Cores)")
                    DiagnosticRow("Total RAM", String.format(java.util.Locale.US, "%.2f GB", hardwareInfo.totalRamGb))
                    DiagnosticRow("Available RAM", hardwareInfo.availableRamBytes.formatSize())
                    DiagnosticRow("Free Storage", hardwareInfo.freeStorageBytes.formatSize())
                    DiagnosticRow("Vulkan Support", if (hardwareInfo.isVulkanSupported) "🟢 Supported" else "🔴 Not Available")
                    DiagnosticRow("Inference Engines", "llama.cpp (GGUF) · LiteRT-LM (optional)")
                    DiagnosticRow("Inference Threads", "${config.threads}")
                    DiagnosticRow("Context Window", "${config.contextWindowLimit} tokens")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.ledger.deskInk,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.ledger.deskPaper,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Opt-in WorkManager downloads (AndroLLM ModelDownloadWorker). OFF by default. */
@Composable
private fun BackgroundDownloadsCard(data: ModelsUiState, viewModel: ModelsViewModel) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background downloads",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                    Text(
                        text = "New downloads run in WorkManager: they wait for a network and free storage, and continue after the app is closed or the phone restarts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
                Switch(checked = data.backgroundDownloads, onCheckedChange = { viewModel.setBackgroundDownloads(it) })
            }
            if (data.backgroundDownloads) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Wi-Fi / unmetered networks only",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.ledger.deskPaper,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = data.backgroundUnmeteredOnly, onCheckedChange = { viewModel.setBackgroundUnmeteredOnly(it) })
                }
            }
        }
    }
}

// ─────────────────────────────── Benchmark ───────────────────────────────

/** Report of the AndroLLM LiteRT-LM engine benchmark ([ModelBenchmarker]). */
@Composable
private fun LiteRtBenchmarkReportDialog(
    report: BenchmarkReport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LiteRT Benchmark: ${report.modelName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Throughput: ${String.format(java.util.Locale.US, "%.1f", report.tokensPerSecond)} tok/s",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.ledger.lampDeep
                )
                Text("Load Time: ${report.loadTimeMs} ms")
                if (report.firstTokenMs > 0) Text("Time to First Token: ${report.firstTokenMs} ms")
                if (report.promptTokens > 0) Text("Prompt: ${report.promptTokens} tokens in ${report.promptTimeMs} ms")
                Text("Generation: ${report.generatedTokens} tokens in ${report.generationTimeMs} ms")
                Text("Peak RAM: ${String.format(java.util.Locale.US, "%.1f", report.peakRamMb)} MB")
                if (report.backend.isNotBlank()) Text("Backend: ${report.backend}")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Benchmark Report", ModelBenchmarker.exportToJson(report)))
                    Toast.makeText(context, "Exported JSON report copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text("Copy JSON") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BenchmarkReportDialog(
    report: JarvisBenchmarkReport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Benchmark Results: ${report.modelName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Throughput: ${String.format(java.util.Locale.US, "%.1f", report.tokensPerSecond)} tok/s",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.ledger.lampDeep
                )
                Text("Load Time: ${report.loadTimeMs} ms")
                Text("Time to First Token: ${report.firstTokenMs} ms")
                Text("Generation: ${report.generatedTokens} streamed tokens in ${report.generationTimeMs} ms")
                Text("Process RAM (PSS): ${String.format(java.util.Locale.US, "%.1f", report.processPssMb)} MB")
                Text(
                    "Tokens are counted as streamed chunks from the llama.cpp engine.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.ledger.deskInk
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val json = JSONObject().apply {
                        put("modelId", report.modelId)
                        put("modelName", report.modelName)
                        put("quantization", report.quantization)
                        put("timestamp", report.timestamp)
                        put("loadTimeMs", report.loadTimeMs)
                        put("firstTokenMs", report.firstTokenMs)
                        put("generatedTokens", report.generatedTokens)
                        put("generationTimeMs", report.generationTimeMs)
                        put("tokensPerSecond", report.tokensPerSecond.toDouble())
                        put("processPssMb", report.processPssMb.toDouble())
                    }.toString(2)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Benchmark Report", json))
                    Toast.makeText(context, "Exported JSON report copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text("Copy JSON") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ─────────────────────────────── Formatting ───────────────────────────────

private fun Long.formatSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", this / 1024.0)
    this < 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", this / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.getDefault(), "%.1f GB", this / (1024.0 * 1024.0 * 1024.0))
}

private fun Float.formatSpeed(): String = when {
    this < 1024f -> String.format(java.util.Locale.getDefault(), "%.0f B/s", this)
    this < 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.0f KB/s", this / 1024f)
    this < 1024f * 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.2f MB/s", this / (1024f * 1024f))
    else -> String.format(java.util.Locale.getDefault(), "%.2f GB/s", this / (1024f * 1024f * 1024f))
}

private fun Long.formatEta(): String = when {
    this < 60 -> "${this}s"
    this < 3600 -> "${this / 60}m ${this % 60}s"
    else -> "${this / 3600}h ${(this % 3600) / 60}m"
}
