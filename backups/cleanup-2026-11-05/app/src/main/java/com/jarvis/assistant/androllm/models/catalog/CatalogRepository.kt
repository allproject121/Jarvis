package com.jarvis.assistant.androllm.models.catalog

import android.content.Context
import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.network.HfCatalogRemoteSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Loads and owns the LiteRT model catalog. On startup prefers a previously fetched catalog
 * persisted in app files, falling back to the catalog bundled as an asset
 * (`assets/models/catalog_v1.json`). [refresh] fetches the newest catalog from
 * [CatalogRemoteSource] and persists it only after it parses and validates cleanly, so a
 * broken remote update never breaks the app.
 *
 * Process singleton (Hilt @Singleton in AndroLLM) — obtain with [getInstance].
 */
class CatalogRepository private constructor(
    private val context: Context,
    private val remoteSource: CatalogRemoteSource
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Loading)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val catalogFile: File
        get() = File(context.filesDir, "catalog/catalog_v1.json")

    init {
        scope.launch {
            loadInitial()
        }
    }

    private fun loadInitial() {
        val bundled = runCatching { context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        if (bundled == null) {
            _state.update { CatalogState.Failed("Catalog asset $BUNDLED_ASSET not found") }
            return
        }
        val saved = runCatching { catalogFile.takeIf { it.exists() }?.readText() }.getOrNull()
        _state.update { CatalogLoader.load(saved, bundled) }
    }

    /**
     * Fetches the latest catalog from the remote source and applies it when valid.
     * Returns the new state on success or a [ModelResult.Error] explaining why nothing changed.
     */
    suspend fun refresh(): ModelResult<CatalogState.Ready> {
        if (_refreshing.value) return ModelResult.error("Refresh already in progress")
        _refreshing.value = true
        return try {
            when (val fetch = remoteSource.fetchCatalogJson()) {
                is ModelResult.Error -> fetch
                is ModelResult.Success -> {
                    when (val state = CatalogLoader.apply(fetch.data, CatalogSource.REMOTE)) {
                        is CatalogState.Ready -> {
                            CatalogLoader.persistText(catalogFile, fetch.data)
                            _state.update { state }
                            ModelResult.success(state)
                        }
                        is CatalogState.Failed -> ModelResult.error(state.message)
                        CatalogState.Loading -> ModelResult.error("Unexpected catalog state")
                    }
                }
            }
        } finally {
            _refreshing.value = false
        }
    }

    /** Current models, or an empty list while loading / after failure. */
    fun currentModels(): List<CatalogModel> =
        (state.value as? CatalogState.Ready)?.catalog?.models.orEmpty()

    /** Catalog entry whose artifact file name is [fileName] (installed-file → metadata lookup). */
    fun findByFileName(fileName: String): CatalogModel? =
        currentModels().firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }

    companion object {
        /** Bundled LiteRT catalog asset (restored from backups/androllm_unused/assets). */
        const val BUNDLED_ASSET = "models/catalog_v1.json"

        @Volatile
        private var instance: CatalogRepository? = null

        fun getInstance(context: Context): CatalogRepository =
            instance ?: synchronized(this) {
                instance ?: CatalogRepository(context.applicationContext, HfCatalogRemoteSource()).also { instance = it }
            }
    }
}
