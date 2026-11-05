package com.jarvis.assistant.androllm.models.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Registry holding active repository providers. Enables switching or querying
 * across multiple online model repositories. Process singleton ([instance]).
 */
class RepositoryRegistry(
    val huggingFaceRepository: HuggingFaceRepository
) {

    private val providersMap: Map<String, ModelRepositoryProvider> = mapOf(
        huggingFaceRepository.providerId to huggingFaceRepository
    )

    private val _selectedProviderId = MutableStateFlow(huggingFaceRepository.providerId)
    val selectedProviderId: StateFlow<String> = _selectedProviderId

    fun getActiveProvider(): ModelRepositoryProvider {
        return providersMap[_selectedProviderId.value] ?: huggingFaceRepository
    }

    fun selectProvider(providerId: String) {
        if (providersMap.containsKey(providerId)) {
            _selectedProviderId.value = providerId
        }
    }

    fun getAvailableProviders(): List<Pair<String, String>> {
        return providersMap.values.map { it.providerId to it.providerName }
    }

    companion object {
        val instance: RepositoryRegistry by lazy {
            RepositoryRegistry(HuggingFaceRepository(HuggingFaceApi()))
        }
    }
}
