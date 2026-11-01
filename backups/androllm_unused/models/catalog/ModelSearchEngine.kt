package com.jarvis.assistant.models.catalog

object ModelSearchEngine {

    fun search(models: List<CatalogModel>, rawQuery: String): List<CatalogModel> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return models

        return models.filter { model ->
            model.id.lowercase().contains(q) ||
            model.name.lowercase().contains(q) ||
            model.family.lowercase().contains(q) ||
            model.architecture.lowercase().contains(q) ||
            model.parameters.lowercase().contains(q) ||
            model.quantization.lowercase().contains(q) ||
            model.tags.any { it.lowercase().contains(q) } ||
            model.categories.any { it.lowercase().contains(q) }
        }
    }
}
