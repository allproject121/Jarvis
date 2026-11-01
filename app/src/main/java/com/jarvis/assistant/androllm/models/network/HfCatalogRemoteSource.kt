package com.jarvis.assistant.androllm.models.network

import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.catalog.CatalogRemoteSource

/**
 * Fetches the latest model catalog JSON from a remote endpoint, trying the
 * primary catalog host first and falling back to the AndroLLM project's GitHub copy.
 *
 * The catalog is plain metadata, so new official LiteRT-LM models can be added
 * server-side without an app update. When every endpoint is unreachable (offline,
 * or the file has not been published yet), [com.jarvis.assistant.androllm.models.catalog.CatalogRepository]
 * simply keeps the bundled catalog asset.
 */
class HfCatalogRemoteSource(private val http: ModelsHttp = ModelsHttp) : CatalogRemoteSource {

    override suspend fun fetchCatalogJson(): ModelResult<String> {
        var lastError: Throwable? = null
        for (url in CATALOG_URLS) {
            try {
                val body = http.getText(url)
                if (body.isNotBlank()) return ModelResult.success(body)
            } catch (t: Exception) {
                lastError = t
            }
        }
        return ModelResult.error<String>(lastError ?: IllegalStateException("No catalog endpoint available"))
    }

    companion object {
        /**
         * Endpoints tried in order. The primary host can publish new LiteRT
         * models at any time; the GitHub copy mirrors AndroLLM's bundled catalog.
         */
        val CATALOG_URLS: List<String> = listOf(
            "https://models.androllm.com/catalog.json",
            "https://raw.githubusercontent.com/ShadowSafin/AndroLLM/main/core/models/src/main/assets/catalog_v1.json"
        )
    }
}
