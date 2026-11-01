package com.jarvis.assistant.models.catalog

data class CatalogModel(
    val id: String,
    val name: String,
    val description: String = "",
    val family: String = "",
    val architecture: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val license: String = "Apache-2.0",
    val author: String = "",
    val fileName: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0,
    val parameters: String = "",
    val quantization: String = "",
    val contextLength: Int = 4096,
    val minRamGb: Float = 4.0f,
    val recommendedRamGb: Float = 8.0f,
    val downloads: Long = 0,
    val likes: Long = 0,
    val trendingScore: Long = 0,
    val sha256: String? = null,
    val modality: String = "TEXT",
    val status: String = "STABLE",
    val recommended: Boolean = false,
    val hidden: Boolean = false,
    val streamable: Boolean = true,
    val estimatedRuntimeRamMb: Long = 2000L
)

enum class RecommendationTier {
    RECOMMENDED,
    POSSIBLE,
    NOT_RECOMMENDED
}

data class ModelRecommendation(
    val model: CatalogModel,
    val score: Double,
    val reasons: List<String>,
    val tier: RecommendationTier
)
