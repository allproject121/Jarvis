package com.jarvis.assistant.ui.compose.feature.models

import android.app.Application
import android.net.Uri
import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalDownloadStatus
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.LocalModelDescriptor
import com.jarvis.assistant.ai.local.LocalModelDownload
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.models.InstalledLiteRtModel
import com.jarvis.assistant.androllm.models.LiteRtModelStore
import com.jarvis.assistant.androllm.models.ModelArtifactFormat
import com.jarvis.assistant.androllm.models.ModelLibraryPrefs
import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.RemoteModelDetails
import com.jarvis.assistant.androllm.models.RemoteModelFile
import com.jarvis.assistant.androllm.models.RemoteModelSummary
import com.jarvis.assistant.androllm.models.RemoteSearchFormat
import com.jarvis.assistant.androllm.models.RepositoryFilter
import com.jarvis.assistant.androllm.models.SortBy
import com.jarvis.assistant.androllm.models.catalog.CatalogModel
import com.jarvis.assistant.androllm.models.catalog.CatalogRepository
import com.jarvis.assistant.androllm.models.catalog.CatalogState
import com.jarvis.assistant.androllm.models.catalog.ModelInspector
import com.jarvis.assistant.androllm.models.catalog.ModelSearchEngine
import com.jarvis.assistant.androllm.models.catalog.RecommendationEngine
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.engine.LocalLiteRtModel
import com.jarvis.assistant.androllm.engine.support.ModelFormat
import com.jarvis.assistant.androllm.models.benchmark.BenchmarkReport
import com.jarvis.assistant.androllm.models.benchmark.ModelBenchmarker
import com.jarvis.assistant.androllm.models.compatibility.CompatibilityAnalysis
import com.jarvis.assistant.androllm.models.compatibility.CompatibilityAnalyzer
import com.jarvis.assistant.androllm.models.download.BackgroundDownloadPrefs
import com.jarvis.assistant.androllm.models.download.DownloadPriority
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadManager
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadProgress
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadRequest
import com.jarvis.assistant.androllm.models.download.LiteRtDownloadStatus
import com.jarvis.assistant.androllm.models.download.ModelCompatibility
import com.jarvis.assistant.androllm.models.download.ModelImporter
import com.jarvis.assistant.androllm.models.network.RepositoryRegistry
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import com.jarvis.assistant.telemetry.DeviceInfoCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide scope for engine operations started from the Models screen
 * (model load / unload / benchmark) so they are not cancelled half-way when the
 * user leaves the screen. Also used as the fallback scope for the backend
 * singletons' getInstance (JarvisApp normally creates them first with its own scope).
 */
internal object ModelsBackgroundScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

enum class ModelsTab { INSTALLED, DOWNLOADS, CATALOG, HUGGINGFACE, DIAGNOSTICS }

enum class ModelSortOption { NAME, SIZE, RAM }

enum class JarvisCatalogSort(val label: String) {
    RECOMMENDED("Recommended"),
    SIZE_ASC("Smallest"),
    SIZE_DESC("Largest"),
    NAME("Name")
}

enum class JarvisCatalogFilter(val label: String) {
    ALL("All"),
    FAVORITES("★ Favorites"),
    INSTALLED("Installed"),
    NOT_INSTALLED("Not installed"),
    FITS_DEVICE("Fits this device")
}

/** Which engine family the Catalog tab lists. */
enum class CatalogFormatFilter(val label: String) {
    ALL("All formats"),
    GGUF("GGUF · llama.cpp"),
    LITERT("LiteRT")
}

/** One GGUF model (curated Jarvis model or custom HF / imported model) plus its on-device state. */
data class JarvisModelRow(
    val descriptor: LocalModelDescriptor,
    val isDownloaded: Boolean,
    val hasPartialDownload: Boolean,
    val isCustom: Boolean = false
) {
    val id: String get() = descriptor.id
}

/** One LiteRT catalog entry plus its on-device state and device fit. */
data class LiteRtCatalogRow(
    val model: CatalogModel,
    val installed: InstalledLiteRtModel?,
    val hasPartial: Boolean,
    val recommendation: RecommendationEngine.ModelRecommendation?,
    val gate: ModelCompatibility.GateResult,
    /** Device-fit analysis (AndroLLM CompatibilityAnalyzer); null until hardware info is known. */
    val analysis: CompatibilityAnalysis? = null
) {
    val id: String get() = model.id
    val format: ModelArtifactFormat get() = ModelArtifactFormat.fromFileName(model.fileName) ?: ModelArtifactFormat.LITERTLM
}

enum class DownloadRowStatus(val label: String) {
    QUEUED("Queued"), DOWNLOADING("Downloading"), VERIFYING("Verifying"), PAUSED("Paused"), ERROR("Failed")
}

/** Unified Downloads-tab row for GGUF (LocalModelManager) and LiteRT (LiteRtDownloadManager) downloads. */
data class DownloadRow(
    val id: String,
    val name: String,
    val format: ModelArtifactFormat,
    val status: DownloadRowStatus,
    val percent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Float,
    val etaSeconds: Long,
    val errorMessage: String?
) {
    val isGguf: Boolean get() = format == ModelArtifactFormat.GGUF
    val isActive: Boolean get() = status == DownloadRowStatus.DOWNLOADING || status == DownloadRowStatus.QUEUED || status == DownloadRowStatus.VERIFYING
}

/** Real measurements taken with LocalAiEngine; tokens = streamed chunks. */
data class JarvisBenchmarkReport(
    val modelId: String,
    val modelName: String,
    val quantization: String,
    val timestamp: String,
    val loadTimeMs: Long,
    val firstTokenMs: Long,
    val generatedTokens: Int,
    val generationTimeMs: Long,
    val tokensPerSecond: Float,
    val processPssMb: Float
)

/** Hugging Face Hub tab state. */
data class HfUiState(
    val query: String = "",
    val format: RemoteSearchFormat = RemoteSearchFormat.GGUF,
    val sort: SortBy = SortBy.DOWNLOADS,
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<RemoteModelSummary> = emptyList(),
    val error: String? = null,
    val selectedId: String? = null,
    val details: RemoteModelDetails? = null,
    val detailsLoading: Boolean = false,
    val detailsError: String? = null
)

/** Storage-import progress (SAF). */
data class ImportUiState(
    val running: Boolean,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = -1L
)

/** First-launch suggestion: the best Jarvis GGUF model and the best LiteRT catalog model for this device. */
data class FirstLaunchRecommendation(
    val gguf: LocalModelDescriptor?,
    val liteRt: RecommendationEngine.ModelRecommendation?
)

data class ModelsUiState(
    val rows: List<JarvisModelRow> = emptyList(),
    val isScanning: Boolean = true,
    val activeModelId: String = LocalAiConfig.DEFAULT_MODEL_ID,
    val engineState: LocalEngineState = LocalEngineState.Uninitialized,
    val selectedTab: ModelsTab = ModelsTab.INSTALLED,
    val searchQuery: String = "",
    val sortOption: ModelSortOption = ModelSortOption.NAME,
    val catalogSort: JarvisCatalogSort = JarvisCatalogSort.RECOMMENDED,
    val catalogFilter: JarvisCatalogFilter = JarvisCatalogFilter.ALL,
    val formatFilter: CatalogFormatFilter = CatalogFormatFilter.ALL,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val hardwareInfo: DeviceHardwareInfo? = null,
    val benchmarkRunningId: String? = null,
    val benchmarkReport: JarvisBenchmarkReport? = null,
    val ggufDownloads: Map<String, LocalModelDownload> = emptyMap(),
    val liteRtDownloads: Map<String, LiteRtDownloadProgress> = emptyMap(),
    val catalogModels: List<CatalogModel> = emptyList(),
    val catalogStatus: String? = null,
    val catalogError: String? = null,
    val catalogRefreshing: Boolean = false,
    val installedLiteRt: List<InstalledLiteRtModel> = emptyList(),
    val liteRtPartials: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val preferredLiteRtPath: String? = null,
    val liteRtEngineEnabled: Boolean = false,
    val inspections: Map<String, ModelInspector.Inspection> = emptyMap(),
    val inspectionErrors: Map<String, String> = emptyMap(),
    val hf: HfUiState = HfUiState(),
    val importState: ImportUiState? = null,
    val recommendation: FirstLaunchRecommendation? = null,
    /** Opt-in WorkManager downloads (default OFF). */
    val backgroundDownloads: Boolean = false,
    val backgroundUnmeteredOnly: Boolean = false,
    /** Path of the LiteRT model being benchmarked on the AndroLLM engine. */
    val liteRtBenchmarkRunningPath: String? = null,
    val liteRtBenchmarkReport: BenchmarkReport? = null
) {
    val totalRamGb: Float get() = hardwareInfo?.totalRamGb ?: 0f

    val isEngineLoaded: Boolean
        get() = engineState is LocalEngineState.Ready || engineState is LocalEngineState.Generating

    /** Model currently resident in the llama.cpp engine (null when nothing is loaded). */
    val loadedModelId: String? get() = if (isEngineLoaded) activeModelId else null

    private fun matchesQuery(row: JarvisModelRow): Boolean {
        val q = searchQuery.trim()
        if (q.isEmpty()) return true
        val d = row.descriptor
        return d.name.contains(q, true) || d.provider.contains(q, true) ||
            d.parameters.contains(q, true) || d.quantization.contains(q, true) || d.id.contains(q, true)
    }

    val installedRows: List<JarvisModelRow>
        get() {
            val base = rows.filter { it.isDownloaded && matchesQuery(it) }
            return when (sortOption) {
                ModelSortOption.NAME -> base.sortedBy { it.descriptor.name.lowercase(Locale.ROOT) }
                ModelSortOption.SIZE -> base.sortedBy { it.descriptor.downloadSizeBytes }
                ModelSortOption.RAM -> base.sortedBy { it.descriptor.recommendedMinRamGb }
            }
        }

    val installedLiteRtVisible: List<InstalledLiteRtModel>
        get() {
            val q = searchQuery.trim()
            val base = if (q.isEmpty()) installedLiteRt else installedLiteRt.filter {
                it.displayName.contains(q, true) || it.file.name.contains(q, true) ||
                    (it.catalogModel?.family?.contains(q, true) == true)
            }
            return when (sortOption) {
                ModelSortOption.NAME -> base.sortedBy { it.displayName.lowercase(Locale.ROOT) }
                ModelSortOption.SIZE -> base.sortedBy { it.sizeBytes }
                ModelSortOption.RAM -> base.sortedBy { it.catalogModel?.minRamGb ?: Float.MAX_VALUE }
            }
        }

    val installedCount: Int get() = rows.count { it.isDownloaded } + installedLiteRt.size

    fun fitsDevice(d: LocalModelDescriptor): Boolean =
        hardwareInfo == null || d.recommendedMinRamGb <= totalRamGb + 0.5f

    /** Best-fit recommendation: the largest curated model that fits this device's RAM. */
    val recommendedId: String?
        get() = rows.filter { !it.isCustom }.map { it.descriptor }
            .filter { fitsDevice(it) }
            .maxByOrNull { it.downloadSizeBytes }?.id

    val catalogRows: List<JarvisModelRow>
        get() {
            if (formatFilter == CatalogFormatFilter.LITERT) return emptyList()
            val filtered = rows.filter { matchesQuery(it) }.filter {
                when (catalogFilter) {
                    JarvisCatalogFilter.ALL -> true
                    JarvisCatalogFilter.FAVORITES -> it.id in favorites
                    JarvisCatalogFilter.INSTALLED -> it.isDownloaded
                    JarvisCatalogFilter.NOT_INSTALLED -> !it.isDownloaded
                    JarvisCatalogFilter.FITS_DEVICE -> fitsDevice(it.descriptor)
                }
            }
            return when (catalogSort) {
                JarvisCatalogSort.RECOMMENDED -> filtered.sortedWith(
                    compareByDescending<JarvisModelRow> { it.id == recommendedId }
                        .thenByDescending { it.id in favorites }
                        .thenByDescending { fitsDevice(it.descriptor) }
                        .thenBy { it.descriptor.downloadSizeBytes }
                )
                JarvisCatalogSort.SIZE_ASC -> filtered.sortedBy { it.descriptor.downloadSizeBytes }
                JarvisCatalogSort.SIZE_DESC -> filtered.sortedByDescending { it.descriptor.downloadSizeBytes }
                JarvisCatalogSort.NAME -> filtered.sortedBy { it.descriptor.name.lowercase(Locale.ROOT) }
            }
        }

    private val liteRtRecommendations: Map<String, RecommendationEngine.ModelRecommendation>
        get() = if (totalRamGb > 0f) {
            RecommendationEngine.recommend(catalogModels, totalRamGb, topN = catalogModels.size).associateBy { it.model.id }
        } else emptyMap()

    val liteRtCatalogRows: List<LiteRtCatalogRow>
        get() {
            if (formatFilter == CatalogFormatFilter.GGUF) return emptyList()
            val recs = liteRtRecommendations
            val installedByName = installedLiteRt.associateBy { it.file.name.lowercase() }
            val visible = ModelSearchEngine.search(catalogModels.filter { !it.hidden }, searchQuery)
            val rows = visible.map { m ->
                LiteRtCatalogRow(
                    model = m,
                    installed = installedByName[LiteRtDownloadManager.safeFileName(m.fileName).lowercase()],
                    hasPartial = LiteRtDownloadManager.safeFileName(m.fileName) in liteRtPartials,
                    recommendation = recs[m.id],
                    gate = ModelCompatibility.check(m),
                    analysis = hardwareInfo?.let { hw -> CompatibilityAnalyzer.analyze(m, hw) }
                )
            }.filter {
                when (catalogFilter) {
                    JarvisCatalogFilter.ALL -> true
                    JarvisCatalogFilter.FAVORITES -> it.id in favorites
                    JarvisCatalogFilter.INSTALLED -> it.installed != null
                    JarvisCatalogFilter.NOT_INSTALLED -> it.installed == null
                    JarvisCatalogFilter.FITS_DEVICE -> it.recommendation?.tier != RecommendationEngine.Tier.NOT_RECOMMENDED
                }
            }
            return when (catalogSort) {
                JarvisCatalogSort.RECOMMENDED -> rows.sortedWith(
                    compareByDescending<LiteRtCatalogRow> { it.id in favorites }
                        .thenByDescending { it.recommendation?.score ?: -1.0 }
                )
                JarvisCatalogSort.SIZE_ASC -> rows.sortedBy { it.model.sizeBytes }
                JarvisCatalogSort.SIZE_DESC -> rows.sortedByDescending { it.model.sizeBytes }
                JarvisCatalogSort.NAME -> rows.sortedBy { it.model.name.lowercase(Locale.ROOT) }
            }
        }

    val downloadRows: List<DownloadRow>
        get() {
            val gguf = ggufDownloads.values.map { d ->
                DownloadRow(
                    id = d.modelId,
                    name = rows.firstOrNull { it.id == d.modelId }?.descriptor?.name ?: d.modelId,
                    format = ModelArtifactFormat.GGUF,
                    status = when (d.status) {
                        LocalDownloadStatus.DOWNLOADING -> DownloadRowStatus.DOWNLOADING
                        LocalDownloadStatus.VERIFYING -> DownloadRowStatus.VERIFYING
                        LocalDownloadStatus.PAUSED -> DownloadRowStatus.PAUSED
                        LocalDownloadStatus.ERROR -> DownloadRowStatus.ERROR
                    },
                    percent = d.progressPercent,
                    bytesDownloaded = d.bytesDownloaded,
                    totalBytes = d.totalBytes,
                    speedBytesPerSec = d.speedBytesPerSec,
                    etaSeconds = d.etaSeconds,
                    errorMessage = d.errorMessage
                )
            }
            val lite = liteRtDownloads.values.filter { it.status != LiteRtDownloadStatus.DOWNLOADED }.map { d ->
                DownloadRow(
                    id = d.modelId,
                    name = d.name,
                    format = ModelArtifactFormat.fromFileName(d.fileName) ?: ModelArtifactFormat.LITERTLM,
                    status = when (d.status) {
                        LiteRtDownloadStatus.QUEUED -> DownloadRowStatus.QUEUED
                        LiteRtDownloadStatus.DOWNLOADING, LiteRtDownloadStatus.DOWNLOADED -> DownloadRowStatus.DOWNLOADING
                        LiteRtDownloadStatus.VERIFYING -> DownloadRowStatus.VERIFYING
                        LiteRtDownloadStatus.PAUSED -> DownloadRowStatus.PAUSED
                        LiteRtDownloadStatus.ERROR -> DownloadRowStatus.ERROR
                    },
                    percent = d.progressPercent,
                    bytesDownloaded = d.bytesDownloaded,
                    totalBytes = d.totalBytes,
                    speedBytesPerSec = d.speedBytesPerSec,
                    etaSeconds = d.etaSeconds,
                    errorMessage = d.errorMessage
                )
            }
            return (gguf + lite).sortedWith(compareByDescending<DownloadRow> { it.isActive }.thenBy { it.name.lowercase(Locale.ROOT) })
        }

    /** Ids (GGUF model ids and LiteRT catalog ids) currently downloading / queued. */
    val activeDownloadIds: Set<String>
        get() = downloadRows.filter { it.isActive }.map { it.id }.toSet()
}

/**
 * Models screen state holder wired to Jarvis' real on-device model backends:
 * [LocalModelManager] (curated + custom GGUF models for llama.cpp: download with pause /
 * resume / cancel, delete, active model) and [LocalAiEngine] (load / unload / benchmark),
 * plus the ported AndroLLM layer: [CatalogRepository] (LiteRT catalog), [LiteRtDownloadManager]
 * (LiteRT artifacts into AndroLlmPaths.liteRtModelsDir), [RepositoryRegistry] (Hugging Face
 * search), [ModelImporter] (SAF import), [ModelInspector] (real GGUF metadata) and
 * [RecommendationEngine].
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val bgScope = ModelsBackgroundScope.scope
    private val manager: LocalModelManager = LocalModelManager.getInstance(application, bgScope)
    private val engine: LocalAiEngine = LocalAiEngine.getInstance(application, bgScope)
    private val catalogRepository = CatalogRepository.getInstance(application)
    private val liteRtDownloads = LiteRtDownloadManager.getInstance(application)
    private val libraryPrefs = ModelLibraryPrefs.getInstance(application)
    private val registry = RepositoryRegistry.instance
    private val inspector = ModelInspector()

    private val _uiState = MutableStateFlow(
        ModelsUiState(
            liteRtEngineEnabled = AndroLlmFlags.isEnabled(application, AndroLlmFlags.LITERT_ENGINE),
            backgroundDownloads = BackgroundDownloadPrefs.isEnabled(application),
            backgroundUnmeteredOnly = BackgroundDownloadPrefs.isUnmeteredOnly(application)
        )
    )
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private var hfSearchJob: Job? = null
    private var hfDetailsJob: Job? = null
    private var importJob: Job? = null
    @Volatile private var ggufScanned = false
    @Volatile private var liteRtScanned = false

    init {
        refreshInstalled()
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching { DeviceInfoCollector.collectDeviceInfo(app) }.getOrNull()
            _uiState.update { it.copy(hardwareInfo = info) }
            maybeOfferFirstLaunchRecommendation()
        }
        viewModelScope.launch {
            manager.activeModelId.collect { id -> _uiState.update { it.copy(activeModelId = id) } }
        }
        viewModelScope.launch {
            engine.engineState.collect { state -> _uiState.update { it.copy(engineState = state) } }
        }
        viewModelScope.launch {
            manager.downloads.collect { map -> _uiState.update { it.copy(ggufDownloads = map) } }
        }
        viewModelScope.launch {
            // A download finishing / being cancelled changes installed + partial state.
            manager.downloads.map { it.keys to it.values.map { d -> d.status } }.distinctUntilChanged().collect { refreshInstalled() }
        }
        viewModelScope.launch {
            manager.customModels.collect { refreshInstalled() }
        }
        viewModelScope.launch {
            liteRtDownloads.downloads.collect { map ->
                _uiState.update { it.copy(liteRtDownloads = map) }
            }
        }
        viewModelScope.launch {
            liteRtDownloads.downloads.map { it.keys to it.values.map { d -> d.status } }.distinctUntilChanged().collect { rescanLiteRt() }
        }
        viewModelScope.launch {
            liteRtDownloads.installedVersion.collect { rescanLiteRt() }
        }
        viewModelScope.launch {
            catalogRepository.state.collect { state ->
                when (state) {
                    CatalogState.Loading -> Unit
                    is CatalogState.Ready -> {
                        _uiState.update {
                            it.copy(
                                catalogModels = state.catalog.models,
                                catalogError = null,
                                catalogStatus = "${state.catalog.models.size} LiteRT models · ${state.source.name.lowercase()} catalog" +
                                    if (state.warnings.isNotEmpty()) " · ${state.warnings.size} warnings" else ""
                            )
                        }
                        rescanLiteRt()
                        maybeOfferFirstLaunchRecommendation()
                    }
                    is CatalogState.Failed -> _uiState.update { it.copy(catalogError = state.message) }
                }
            }
        }
        viewModelScope.launch {
            catalogRepository.refreshing.collect { r -> _uiState.update { it.copy(catalogRefreshing = r) } }
        }
        viewModelScope.launch {
            libraryPrefs.favorites.collect { f -> _uiState.update { it.copy(favorites = f) } }
        }
        viewModelScope.launch {
            libraryPrefs.preferredLiteRtModelPath.collect { p -> _uiState.update { it.copy(preferredLiteRtPath = p) } }
        }
    }

    // ───────────────────────────── Scanning ─────────────────────────────

    fun refreshInstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = manager.allModels().map { d ->
                JarvisModelRow(
                    descriptor = d,
                    isDownloaded = runCatching { manager.isModelReady(d.id) }.getOrDefault(false),
                    hasPartialDownload = manager.getPartialFile(d.id).let { it.exists() && it.length() > 0 },
                    isCustom = manager.isCustomModel(d.id)
                )
            }
            _uiState.update { it.copy(rows = rows, isScanning = false) }
            ggufScanned = true
            maybeOfferFirstLaunchRecommendation()
            inspectInstalledGguf(rows.filter { it.isDownloaded })
        }
    }

    private fun rescanLiteRt() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = LiteRtModelStore.scan(app, catalogRepository.currentModels())
            val partials = LiteRtModelStore.partialFileNames(app)
            _uiState.update {
                it.copy(
                    installedLiteRt = installed,
                    liteRtPartials = partials,
                    liteRtEngineEnabled = AndroLlmFlags.isEnabled(app, AndroLlmFlags.LITERT_ENGINE)
                )
            }
            liteRtScanned = true
            maybeOfferFirstLaunchRecommendation()
            inspectInstalledLiteRt(installed)
        }
    }

    /** Real metadata straight from the GGUF bytes (GgufReader), cached per file revision. */
    private fun inspectInstalledGguf(rows: List<JarvisModelRow>) {
        rows.forEach { row ->
            val file = manager.getModelFile(row.id)
            val key = inspectionKey(file)
            if (_uiState.value.inspections.containsKey(key) || _uiState.value.inspectionErrors.containsKey(key)) return@forEach
            inspector.inspect(file)
                .onSuccess { insp -> _uiState.update { it.copy(inspections = it.inspections + (key to insp)) } }
                .onFailure { e -> _uiState.update { it.copy(inspectionErrors = it.inspectionErrors + (key to (e.message ?: "Unreadable GGUF"))) } }
        }
    }

    private fun inspectInstalledLiteRt(installed: List<InstalledLiteRtModel>) {
        installed.forEach { model ->
            // MediaPipe .task bundles are ZIP archives without an inspectable model header.
            if (model.format == ModelArtifactFormat.TASK) return@forEach
            val key = liteRtInspectionKey(model)
            if (_uiState.value.inspections.containsKey(key) || _uiState.value.inspectionErrors.containsKey(key)) return@forEach
            inspector.inspectAny(model.file, model.catalogModel)
                .onSuccess { insp -> _uiState.update { it.copy(inspections = it.inspections + (key to insp)) } }
                .onFailure { e -> _uiState.update { it.copy(inspectionErrors = it.inspectionErrors + (key to (e.message ?: "Unreadable artifact"))) } }
        }
    }

    fun ggufFile(modelId: String): File = manager.getModelFile(modelId)

    // ───────────────────────────── Simple UI state ─────────────────────────────

    fun selectTab(tab: ModelsTab) = _uiState.update { it.copy(selectedTab = tab) }
    fun updateSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }
    fun updateSortOption(o: ModelSortOption) = _uiState.update { it.copy(sortOption = o) }
    fun updateCatalogSort(o: JarvisCatalogSort) = _uiState.update { it.copy(catalogSort = o) }
    fun updateCatalogFilter(f: JarvisCatalogFilter) = _uiState.update { it.copy(catalogFilter = f) }
    fun updateFormatFilter(f: CatalogFormatFilter) = _uiState.update { it.copy(formatFilter = f) }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }
    fun dismissInfo() = _uiState.update { it.copy(infoMessage = null) }
    fun dismissBenchmarkReport() = _uiState.update { it.copy(benchmarkReport = null) }
    fun toggleFavorite(id: String) = libraryPrefs.toggleFavorite(id)

    // ───────────────────────────── GGUF (llama.cpp) ─────────────────────────────

    fun downloadModel(modelId: String) {
        manager.downloadModel(modelId)
        _uiState.update { it.copy(selectedTab = ModelsTab.DOWNLOADS, errorMessage = null) }
    }

    /** Retry resumes from the partial file via the manager's HTTP Range support. */
    fun retryDownload(modelId: String) = manager.resumeDownload(modelId)
    fun pauseGgufDownload(modelId: String) = manager.pauseDownload(modelId)
    fun cancelGgufDownload(modelId: String) {
        manager.cancelDownload(modelId)
        refreshInstalled()
    }

    /** Makes the model active and loads it into the llama.cpp engine. */
    fun loadModel(modelId: String) {
        manager.setActiveModel(modelId)
        bgScope.launch {
            val ok = runCatching { engine.initialize(LocalAiConfig(modelId = modelId)) }.getOrDefault(false)
            if (!ok) {
                val msg = (engine.engineState.value as? LocalEngineState.Error)?.message
                    ?: "Failed to load model into the on-device engine."
                _uiState.update { it.copy(errorMessage = msg) }
            }
        }
    }

    fun unloadModel() {
        bgScope.launch { runCatching { engine.unload() } }
    }

    /** Sets the active model without loading it (used by voice / chat on next initialize). */
    fun setActiveModel(modelId: String) {
        manager.setActiveModel(modelId)
    }

    fun deleteModel(modelId: String) {
        val state = _uiState.value
        bgScope.launch {
            if (state.loadedModelId == modelId) runCatching { engine.unload() }
            val file = manager.getModelFile(modelId)
            val key = inspectionKey(file)
            val ok = withContext(Dispatchers.IO) { manager.deleteModel(modelId) }
            if (!ok) _uiState.update { it.copy(errorMessage = "Could not delete model file for $modelId.") }
            _uiState.update { it.copy(inspections = it.inspections - key, inspectionErrors = it.inspectionErrors - key) }
            refreshInstalled()
        }
    }

    fun runBenchmark(modelId: String) {
        val descriptor = manager.getDescriptor(modelId) ?: return
        if (_uiState.value.benchmarkRunningId != null) return
        _uiState.update { it.copy(benchmarkRunningId = modelId) }
        bgScope.launch {
            val result = runCatching {
                val loadStart = System.currentTimeMillis()
                manager.setActiveModel(modelId)
                val loaded = engine.initialize(LocalAiConfig(modelId = modelId))
                val loadMs = System.currentTimeMillis() - loadStart
                if (!loaded) {
                    throw IllegalStateException(
                        (engine.engineState.value as? LocalEngineState.Error)?.message ?: "Model failed to load"
                    )
                }
                val genStart = System.currentTimeMillis()
                var firstTokenMs = -1L
                var chunks = 0
                engine.generateStreaming(BENCHMARK_PROMPT).collect {
                    if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - genStart
                    chunks++
                }
                val genMs = System.currentTimeMillis() - genStart
                if (chunks < 2) {
                    throw IllegalStateException("The native engine did not stream tokens; benchmark aborted.")
                }
                val decodeMs = (genMs - firstTokenMs.coerceAtLeast(0L)).coerceAtLeast(1L)
                JarvisBenchmarkReport(
                    modelId = modelId,
                    modelName = descriptor.name,
                    quantization = descriptor.quantization,
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    loadTimeMs = loadMs,
                    firstTokenMs = firstTokenMs.coerceAtLeast(0L),
                    generatedTokens = chunks,
                    generationTimeMs = genMs,
                    tokensPerSecond = (chunks - 1).coerceAtLeast(1) * 1000f / decodeMs,
                    processPssMb = Debug.getPss() / 1024f
                )
            }
            _uiState.update {
                it.copy(
                    benchmarkRunningId = null,
                    benchmarkReport = result.getOrNull(),
                    errorMessage = result.exceptionOrNull()?.let { e -> "Benchmark failed: ${e.message}" }
                )
            }
        }
    }

    // ───────────────────────────── LiteRT ─────────────────────────────

    // ───────────────────────── Background downloads / priority ─────────────────────────

    /** Opt-in: hand new downloads to WorkManager (network/storage constraints, survives process death). */
    fun setBackgroundDownloads(enabled: Boolean) {
        BackgroundDownloadPrefs.setEnabled(app, enabled)
        _uiState.update { it.copy(backgroundDownloads = enabled) }
    }

    fun setBackgroundUnmeteredOnly(enabled: Boolean) {
        BackgroundDownloadPrefs.setUnmeteredOnly(app, enabled)
        _uiState.update { it.copy(backgroundUnmeteredOnly = enabled) }
    }

    /** Downloads a LiteRT catalog model with an explicit queue priority. */
    fun downloadLiteRt(model: CatalogModel, priority: DownloadPriority) {
        val gate = liteRtDownloads.startCatalogDownload(model, priority)
        if (!gate.compatible) {
            _uiState.update { it.copy(errorMessage = "${model.name}: ${gate.reason}") }
            return
        }
        _uiState.update { it.copy(selectedTab = ModelsTab.DOWNLOADS, errorMessage = null) }
    }

    // ───────────────────────── LiteRT benchmark (AndroLLM ModelBenchmarker) ─────────────────────────

    fun runLiteRtBenchmark(model: InstalledLiteRtModel) {
        if (_uiState.value.liteRtBenchmarkRunningPath != null || _uiState.value.benchmarkRunningId != null) return
        if (!AndroLlmFlags.isEnabled(app, AndroLlmFlags.LITERT_ENGINE)) {
            _uiState.update { it.copy(errorMessage = "Enable the LiteRT-LM engine in Settings → Engines to benchmark LiteRT models.") }
            return
        }
        if (model.format == ModelArtifactFormat.TFLITE) {
            _uiState.update { it.copy(errorMessage = "${model.displayName} is an embedding/TFLite model — only LiteRT-LM chat models can be benchmarked.") }
            return
        }
        _uiState.update { it.copy(liteRtBenchmarkRunningPath = model.path) }
        bgScope.launch {
            val engines = AndroLlmEngines.getInstance(app)
            val local = LocalLiteRtModel(
                id = model.file.nameWithoutExtension,
                name = model.displayName,
                file = model.file,
                sizeBytes = model.sizeBytes,
                format = ModelFormat.LITERTLM
            )
            val result = ModelBenchmarker.runLiteRtBenchmark(engines, local)
            _uiState.update {
                it.copy(
                    liteRtBenchmarkRunningPath = null,
                    liteRtBenchmarkReport = (result as? com.jarvis.assistant.androllm.engine.support.Result.Success)?.data,
                    errorMessage = (result as? com.jarvis.assistant.androllm.engine.support.Result.Error)
                        ?.let { e -> "LiteRT benchmark failed: ${e.exception.message}" }
                )
            }
        }
    }

    fun dismissLiteRtBenchmarkReport() = _uiState.update { it.copy(liteRtBenchmarkReport = null) }

    fun downloadLiteRt(model: CatalogModel) {
        val gate = liteRtDownloads.startCatalogDownload(model)
        if (!gate.compatible) {
            _uiState.update { it.copy(errorMessage = "${model.name}: ${gate.reason}") }
            return
        }
        _uiState.update { it.copy(selectedTab = ModelsTab.DOWNLOADS, errorMessage = null) }
    }

    fun pauseDownload(row: DownloadRow) {
        if (row.isGguf) manager.pauseDownload(row.id) else liteRtDownloads.pauseDownload(row.id)
    }

    fun resumeDownload(row: DownloadRow) {
        if (row.isGguf) manager.resumeDownload(row.id) else liteRtDownloads.resumeDownload(row.id)
    }

    fun cancelDownload(row: DownloadRow) {
        if (row.isGguf) cancelGgufDownload(row.id) else liteRtDownloads.cancelDownload(row.id)
    }

    fun pauseAll() {
        _uiState.value.downloadRows.filter { it.status == DownloadRowStatus.DOWNLOADING }.forEach { pauseDownload(it) }
    }

    fun resumeAll() {
        _uiState.value.downloadRows
            .filter { it.status == DownloadRowStatus.PAUSED || it.status == DownloadRowStatus.ERROR }
            .forEach { resumeDownload(it) }
    }

    fun deleteLiteRt(model: InstalledLiteRtModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = liteRtDownloads.deleteInstalled(model.file)
            if (!ok) _uiState.update { it.copy(errorMessage = "Could not delete ${model.file.name}.") }
            if (libraryPrefs.preferredLiteRtModelPath.value == model.path) libraryPrefs.setPreferredLiteRtModel(null)
            val key = liteRtInspectionKey(model)
            _uiState.update { it.copy(inspections = it.inspections - key, inspectionErrors = it.inspectionErrors - key) }
        }
    }

    /** Marks a LiteRT artifact as the preferred model for the LiteRT engine (toggle). */
    fun togglePreferredLiteRt(model: InstalledLiteRtModel) {
        val current = libraryPrefs.preferredLiteRtModelPath.value
        libraryPrefs.setPreferredLiteRtModel(if (current == model.path) null else model.path)
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            when (val r = catalogRepository.refresh()) {
                is ModelResult.Success -> _uiState.update {
                    it.copy(infoMessage = "Catalog updated: ${r.data.catalog.models.size} LiteRT models.")
                }
                is ModelResult.Error -> _uiState.update {
                    it.copy(infoMessage = "Catalog not updated: ${r.exception.message}. Keeping the current catalog.")
                }
            }
        }
    }

    // ───────────────────────────── Hugging Face ─────────────────────────────

    fun updateHfQuery(q: String) = _uiState.update { it.copy(hf = it.hf.copy(query = q)) }

    fun setHfFormat(format: RemoteSearchFormat) {
        _uiState.update { it.copy(hf = it.hf.copy(format = format, results = emptyList(), searched = false, error = null)) }
        searchHf()
    }

    fun setHfSort(sort: SortBy) {
        _uiState.update { it.copy(hf = it.hf.copy(sort = sort)) }
        if (_uiState.value.hf.searched) searchHf()
    }

    fun searchHf() {
        val hf = _uiState.value.hf
        hfSearchJob?.cancel()
        _uiState.update { it.copy(hf = it.hf.copy(loading = true, error = null)) }
        hfSearchJob = viewModelScope.launch {
            val filter = RepositoryFilter(searchQuery = hf.query.trim(), format = hf.format, sortBy = hf.sort)
            when (val result = registry.getActiveProvider().searchModels(filter).first()) {
                is ModelResult.Success -> _uiState.update {
                    it.copy(hf = it.hf.copy(loading = false, searched = true, results = result.data))
                }
                is ModelResult.Error -> _uiState.update {
                    it.copy(hf = it.hf.copy(loading = false, searched = true, results = emptyList(),
                        error = "Hugging Face search failed: ${result.exception.message}"))
                }
            }
        }
    }

    fun openHfDetails(modelId: String) {
        hfDetailsJob?.cancel()
        _uiState.update { it.copy(hf = it.hf.copy(selectedId = modelId, details = null, detailsLoading = true, detailsError = null)) }
        hfDetailsJob = viewModelScope.launch {
            when (val result = registry.getActiveProvider().getModelDetails(modelId).first()) {
                is ModelResult.Success -> _uiState.update {
                    it.copy(hf = it.hf.copy(details = result.data, detailsLoading = false))
                }
                is ModelResult.Error -> _uiState.update {
                    it.copy(hf = it.hf.copy(detailsLoading = false, detailsError = result.exception.message ?: "Could not load model details"))
                }
            }
        }
    }

    fun closeHfDetails() {
        hfDetailsJob?.cancel()
        _uiState.update { it.copy(hf = it.hf.copy(selectedId = null, details = null, detailsLoading = false, detailsError = null)) }
    }

    /** Jarvis GGUF id for a Hugging Face file (stable across sessions). */
    fun hfGgufId(repoId: String, file: RemoteModelFile): String =
        LocalModelManager.sanitizeModelId("hf-$repoId-${file.filename.substringAfterLast('/').substringBeforeLast('.')}")

    /** Downloads one file of a Hub repository: GGUF → Jarvis llama.cpp, LiteRT → LiteRT models dir. */
    fun downloadHfFile(details: RemoteModelDetails, file: RemoteModelFile) {
        if (file.format == ModelArtifactFormat.GGUF) {
            val id = hfGgufId(details.id, file)
            val sizeGb = file.sizeBytes / (1024.0 * 1024.0 * 1024.0)
            val stem = file.filename.substringAfterLast('/').substringBeforeLast('.')
            val params = PARAMS_IN_NAME.find(details.name)?.groupValues?.get(1)?.let { "${it}B" } ?: ""
            val descriptor = LocalModelDescriptor(
                id = id,
                name = stem,
                provider = "${details.author} · Hugging Face",
                parameters = params,
                quantization = "GGUF ${file.quantization}".trim(),
                downloadSizeBytes = file.sizeBytes,
                downloadUrl = file.downloadUrl,
                // llama.cpp maps the whole file; plus ~1 GB for KV cache and runtime.
                recommendedMinRamGb = if (file.sizeBytes > 0) ceil(sizeGb + 1.0).toInt().coerceAtLeast(2) else 4,
                description = "Downloaded from huggingface.co/${details.id} (${file.filename}). License: ${details.license}.",
                sha256 = file.sha256
            )
            if (!manager.registerCustomModel(descriptor)) {
                _uiState.update { it.copy(errorMessage = "Could not register ${file.filename}.") }
                return
            }
            manager.downloadModel(id)
        } else {
            liteRtDownloads.startDownload(
                LiteRtDownloadRequest(
                    modelId = "hf-${details.id}-${file.filename}",
                    name = file.filename.substringAfterLast('/'),
                    downloadUrl = file.downloadUrl,
                    fileName = LiteRtDownloadManager.safeFileName(file.filename),
                    expectedSize = file.sizeBytes,
                    sha256 = file.sha256
                )
            )
        }
        _uiState.update { it.copy(selectedTab = ModelsTab.DOWNLOADS, infoMessage = "Downloading ${file.filename}") }
    }

    // ───────────────────────────── Import (SAF) ─────────────────────────────

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        _uiState.update { it.copy(importState = ImportUiState(running = true)) }
        importJob = ModelImporter.scope.launch {
            val result = ModelImporter.import(app, uri) { copied, total ->
                _uiState.update { it.copy(importState = ImportUiState(running = true, copiedBytes = copied, totalBytes = total)) }
            }
            when (result) {
                is ModelResult.Success -> {
                    val imported = result.data
                    _uiState.update {
                        it.copy(
                            importState = null,
                            selectedTab = ModelsTab.INSTALLED,
                            infoMessage = "Imported ${imported.displayName} (${imported.format.label})."
                        )
                    }
                    if (imported.format == ModelArtifactFormat.GGUF) refreshInstalled() else rescanLiteRt()
                }
                is ModelResult.Error -> _uiState.update {
                    it.copy(importState = null, errorMessage = result.exception.message ?: "Import failed")
                }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _uiState.update { it.copy(importState = null) }
    }

    // ───────────────────────────── First-launch recommendation ─────────────────────────────

    private fun maybeOfferFirstLaunchRecommendation() {
        if (libraryPrefs.isFirstLaunchRecommendationShown()) return
        val state = _uiState.value
        val hw = state.hardwareInfo ?: return
        if (!ggufScanned || !liteRtScanned || state.catalogModels.isEmpty()) return
        if (state.recommendation != null) return
        if (state.rows.any { it.isDownloaded } || state.installedLiteRt.isNotEmpty()) {
            libraryPrefs.markFirstLaunchRecommendationShown()
            return
        }
        val gguf = LocalAiConfig.AVAILABLE_MODELS
            .filter { it.recommendedMinRamGb <= hw.totalRamGb + 0.5f }
            .maxByOrNull { it.downloadSizeBytes }
        val liteRt = RecommendationEngine.recommend(
            state.catalogModels.filter { m -> "CHAT" in m.categories && ModelCompatibility.check(m).compatible },
            hw.totalRamGb,
            topN = 1
        ).firstOrNull()
        if (gguf == null && liteRt == null) return
        _uiState.update { it.copy(recommendation = FirstLaunchRecommendation(gguf, liteRt)) }
    }

    fun dismissRecommendation() {
        libraryPrefs.markFirstLaunchRecommendationShown()
        _uiState.update { it.copy(recommendation = null) }
    }

    fun acceptGgufRecommendation(modelId: String) {
        dismissRecommendation()
        manager.setActiveModel(modelId)
        downloadModel(modelId)
    }

    fun acceptLiteRtRecommendation(model: CatalogModel) {
        dismissRecommendation()
        downloadLiteRt(model)
    }

    override fun onCleared() {
        // Imports run on ModelImporter.scope and keep going; downloads live in their managers.
        super.onCleared()
    }

    companion object {
        private const val BENCHMARK_PROMPT =
            "Write a short paragraph describing a quiet morning in a small mountain village."

        /** "Qwen2.5-1.5B-Instruct" → 1.5 (parameter count stated in the repo name). */
        private val PARAMS_IN_NAME = Regex("""(?<![A-Za-z0-9.])(\d+(?:\.\d+)?)[Bb](?![A-Za-z])""")

        fun inspectionKey(file: File): String = "${file.absolutePath}@${file.lastModified()}"

        /** LiteRT inspections depend on the matching catalog entry too. */
        fun liteRtInspectionKey(model: InstalledLiteRtModel): String =
            inspectionKey(model.file) + "#" + (model.catalogModel?.id ?: "")
    }
}
