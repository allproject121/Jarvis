package com.jarvis.assistant.androllm.models.catalog

import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.modelRunCatching
import java.io.File

/**
 * Where the active catalog was loaded from. BUNDLED ships with the APK, SAVED is a
 * previously fetched catalog persisted to app files, REMOTE was just fetched and applied.
 */
enum class CatalogSource { BUNDLED, SAVED, REMOTE }

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(
        val catalog: CatalogFile,
        val source: CatalogSource,
        val warnings: List<String> = emptyList()
    ) : CatalogState

    data class Failed(val message: String) : CatalogState
}

/**
 * Fetches the latest catalog document from a remote host. Implemented in the network
 * package ([com.jarvis.assistant.androllm.models.network.HfCatalogRemoteSource]).
 */
interface CatalogRemoteSource {
    suspend fun fetchCatalogJson(): ModelResult<String>
}

/**
 * Pure catalog loading/merging logic, kept free of Android dependencies so it can be
 * unit-tested on the JVM.
 */
object CatalogLoader {

    fun load(savedJson: String?, bundledJson: String): CatalogState {
        if (!savedJson.isNullOrBlank()) {
            val state = apply(savedJson, CatalogSource.SAVED)
            if (state is CatalogState.Ready) return state
        }
        return try {
            val result = CatalogParser.parse(bundledJson)
            val report = CatalogValidator.validate(result.catalog.models)
            if (report.errors.isNotEmpty()) {
                CatalogState.Failed("Bundled catalog failed validation: ${report.errors.first()}")
            } else {
                CatalogState.Ready(result.catalog, CatalogSource.BUNDLED, result.warnings + report.warnings)
            }
        } catch (e: IllegalArgumentException) {
            CatalogState.Failed("Bundled catalog is invalid: ${e.message}")
        }
    }

    /**
     * Parses and validates a catalog document. Returns Ready only when the document
     * parses cleanly and passes validation; otherwise Failed with the reason, so the
     * caller can keep the previous catalog.
     */
    fun apply(jsonText: String, source: CatalogSource): CatalogState {
        return try {
            val result = CatalogParser.parse(jsonText)
            val report = CatalogValidator.validate(result.catalog.models)
            if (report.errors.isNotEmpty()) {
                CatalogState.Failed("Catalog failed validation: ${report.errors.first()}")
            } else {
                CatalogState.Ready(result.catalog, source, result.warnings + report.warnings)
            }
        } catch (e: IllegalArgumentException) {
            CatalogState.Failed(e.message ?: "Invalid catalog document")
        }
    }

    fun persistText(file: File, jsonText: String): ModelResult<Unit> = modelRunCatching {
        file.parentFile?.mkdirs()
        file.writeText(jsonText)
    }
}
