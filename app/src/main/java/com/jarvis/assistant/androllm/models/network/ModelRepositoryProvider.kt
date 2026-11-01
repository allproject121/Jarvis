package com.jarvis.assistant.androllm.models.network

import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.RemoteModelDetails
import com.jarvis.assistant.androllm.models.RemoteModelSummary
import com.jarvis.assistant.androllm.models.RepositoryFilter
import kotlinx.coroutines.flow.Flow

/**
 * Modular repository provider abstraction. Decouples the UI from specific online
 * model sources (Hugging Face today; others can register in [RepositoryRegistry]).
 */
interface ModelRepositoryProvider {

    /** Unique identifier of this repository provider (e.g. "huggingface"). */
    val providerId: String

    /** Display name of this repository provider (e.g. "Hugging Face Hub"). */
    val providerName: String

    /** Searches models matching [filter]. */
    fun searchModels(filter: RepositoryFilter): Flow<ModelResult<List<RemoteModelSummary>>>

    /** Fetches detailed metadata (incl. downloadable files) for [modelId]. */
    fun getModelDetails(modelId: String): Flow<ModelResult<RemoteModelDetails>>

    /** Fetches the raw README markdown content for [modelId]. */
    fun getReadme(modelId: String): Flow<ModelResult<String>>
}
