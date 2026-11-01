package com.jarvis.assistant.ui.compose.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ai.local.LocalDownloadStatus
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.AndroLlmPaths
import com.jarvis.assistant.androllm.models.LiteRtModelStore
import com.jarvis.assistant.androllm.models.ModelLibraryPrefs
import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.catalog.CatalogRepository
import com.jarvis.assistant.androllm.models.catalog.CatalogState
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadManager
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadStatus
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-lifetime scope for the section's manager singletons (never a screen scope). */
private object ModelsSettingsScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * "On-device Models" settings section: summary of Jarvis' GGUF models (llama.cpp) and the
 * LiteRT models managed by the AndroLLM model layer — active model, installed counts, storage
 * used, LiteRT catalog status / update, preferred LiteRT model, and bulk pause / resume of
 * downloads. The full manager lives on the Models screen.
 */
@Composable
fun ModelsSettingsSection() {
    val context = LocalContext.current.applicationContext
    val manager = remember { LocalModelManager.getInstance(context, ModelsSettingsScope.scope) }
    val catalog = remember { CatalogRepository.getInstance(context) }
    val liteRtDownloads = remember { LiteRtDownloadManager.getInstance(context) }
    val libraryPrefs = remember { ModelLibraryPrefs.getInstance(context) }
    val uiScope = rememberCoroutineScope()

    val activeId by manager.activeModelId.collectAsState()
    val ggufDownloads by manager.downloads.collectAsState()
    val customModels by manager.customModels.collectAsState()
    val liteDownloads by liteRtDownloads.downloads.collectAsState()
    val installedVersion by liteRtDownloads.installedVersion.collectAsState()
    val catalogState by catalog.state.collectAsState()
    val refreshing by catalog.refreshing.collectAsState()
    val preferredPath by libraryPrefs.preferredLiteRtModelPath.collectAsState()

    var ggufInstalled by remember { mutableStateOf(0) }
    var ggufBytes by remember { mutableStateOf(0L) }
    var liteInstalled by remember { mutableStateOf(0) }
    var liteBytes by remember { mutableStateOf(0L) }
    var activeReady by remember { mutableStateOf(false) }
    var catalogMessage by remember { mutableStateOf<String?>(null) }
    val liteRtEnabled = remember { AndroLlmFlags.isEnabled(context, AndroLlmFlags.LITERT_ENGINE) }

    LaunchedEffect(activeId, ggufDownloads.keys, customModels, installedVersion, catalogState) {
        withContext(Dispatchers.IO) {
            val ready = manager.allModels().filter { runCatching { manager.isModelReady(it.id) }.getOrDefault(false) }
            ggufInstalled = ready.size
            ggufBytes = ready.sumOf { manager.getModelFile(it.id).length() }
            activeReady = runCatching { manager.isModelReady(activeId) }.getOrDefault(false)
            val lite = LiteRtModelStore.scan(context, catalog.currentModels())
            liteInstalled = lite.size
            liteBytes = lite.sumOf { it.sizeBytes } +
                (File(AndroLlmPaths.liteRtModelsDir(context), "tokenizer.model").takeIf { it.exists() }?.length() ?: 0L)
        }
    }

    val activeName = manager.getDescriptor(activeId)?.name ?: activeId
    val runningCount = ggufDownloads.values.count { it.status == LocalDownloadStatus.DOWNLOADING } +
        liteDownloads.values.count { it.status == LiteRtDownloadStatus.DOWNLOADING || it.status == LiteRtDownloadStatus.QUEUED }
    val pausedCount = ggufDownloads.values.count { it.status == LocalDownloadStatus.PAUSED || it.status == LocalDownloadStatus.ERROR } +
        liteDownloads.values.count { it.status == LiteRtDownloadStatus.PAUSED || it.status == LiteRtDownloadStatus.ERROR }
    val catalogSummary = when (val s = catalogState) {
        CatalogState.Loading -> "Loading LiteRT catalog…"
        is CatalogState.Ready -> "${s.catalog.models.size} LiteRT models (${s.source.name.lowercase()} catalog)"
        is CatalogState.Failed -> "Catalog unavailable: ${s.message}"
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("On-device Models", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                    Text(
                        "Offline fallback models. Gemini Live stays Jarvis' primary brain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            SettingRow(
                icon = Icons.Filled.Star,
                title = "Active GGUF model",
                value = "$activeName${if (activeReady) "" else " (not downloaded)"}",
                onClick = {}
            )
            SettingRow(
                icon = Icons.Filled.Storage,
                title = "GGUF models (llama.cpp)",
                value = "$ggufInstalled installed • ${formatBytes(ggufBytes)}",
                onClick = {}
            )
            SettingRow(
                icon = Icons.Filled.Storage,
                title = "LiteRT models",
                value = "$liteInstalled installed • ${formatBytes(liteBytes)} • engine ${if (liteRtEnabled) "on" else "off"}",
                onClick = {}
            )
            SettingRow(
                icon = Icons.Filled.Star,
                title = "Preferred LiteRT model",
                value = preferredPath?.let { File(it).name } ?: "None",
                enabled = preferredPath != null,
                onClick = { libraryPrefs.setPreferredLiteRtModel(null) }
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            SettingRow(
                icon = Icons.Filled.CloudSync,
                title = if (refreshing) "Updating catalog…" else "Update LiteRT catalog",
                value = catalogMessage ?: catalogSummary,
                enabled = !refreshing,
                onClick = {
                    uiScope.launch {
                        catalogMessage = when (val r = catalog.refresh()) {
                            is ModelResult.Success -> "Updated: ${r.data.catalog.models.size} models"
                            is ModelResult.Error -> "Not updated: ${r.exception.message}"
                        }
                    }
                }
            )

            if (runningCount > 0) {
                SettingRow(
                    icon = Icons.Filled.Pause,
                    title = "Pause all downloads",
                    value = "$runningCount running",
                    onClick = {
                        ggufDownloads.values.filter { it.status == LocalDownloadStatus.DOWNLOADING }
                            .forEach { manager.pauseDownload(it.modelId) }
                        liteRtDownloads.pauseAll()
                    }
                )
            }
            if (pausedCount > 0) {
                SettingRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Resume downloads",
                    value = "$pausedCount paused / failed",
                    onClick = {
                        ggufDownloads.values
                            .filter { it.status == LocalDownloadStatus.PAUSED || it.status == LocalDownloadStatus.ERROR }
                            .forEach { manager.resumeDownload(it.modelId) }
                        liteRtDownloads.resumeAll()
                    }
                )
            }
            if (runningCount == 0 && pausedCount == 0) {
                SettingRow(
                    icon = Icons.Filled.Download,
                    title = "Downloads",
                    value = "None in progress",
                    enabled = false,
                    onClick = {}
                )
            }
        }
    }
}
