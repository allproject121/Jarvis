package com.jarvis.assistant.models.catalog

object RecommendationEngine {

    private const val RAM_HEADROOM_GB = 1.0

    fun recommend(
        models: List<CatalogModel>,
        deviceRamGb: Float,
        topN: Int = 10
    ): List<ModelRecommendation> {
        if (models.isEmpty()) return emptyList()
        val availableGb = (deviceRamGb - 0.75f).coerceAtLeast(0.5f)

        return models
            .filter { !it.hidden && it.status == "STABLE" }
            .map { model ->
                val score = score(model, availableGb)
                val tier = tier(model, availableGb)
                val reasons = reasons(model, availableGb)
                ModelRecommendation(model, score, reasons, tier)
            }
            .sortedByDescending { it.score }
            .take(topN)
    }

    private fun score(model: CatalogModel, availableGb: Float): Double {
        val ramMb = model.estimatedRuntimeRamMb
        val ramGb = ramMb / 1000.0
        val headroom = availableGb - ramGb

        val ramFit = when {
            headroom >= RAM_HEADROOM_GB -> 1.0
            headroom >= 0 -> 0.6 + 0.4 * (headroom / RAM_HEADROOM_GB)
            else -> 0.15
        }

        val sizeGb = model.sizeBytes / 1_000_000_000.0
        val storageFit = if (sizeGb > 0) {
            when {
                sizeGb <= availableGb * 3 -> 1.0
                sizeGb <= availableGb * 5 -> 0.6
                else -> 0.3
            }
        } else 0.6

        val curated = if (model.recommended) 0.5 else 0.0
        val comfort = (1.0 - ramGb / availableGb.coerceAtLeast(0.1f)).coerceIn(0.0, 1.0)

        return ramFit * 0.45 + storageFit * 0.2 + curated * 0.25 + comfort * 0.1
    }

    private fun tier(model: CatalogModel, availableGb: Float): RecommendationTier {
        val ramGb = model.estimatedRuntimeRamMb / 1000.0
        return when {
            ramGb <= availableGb - RAM_HEADROOM_GB -> RecommendationTier.RECOMMENDED
            ramGb <= availableGb -> RecommendationTier.POSSIBLE
            else -> RecommendationTier.NOT_RECOMMENDED
        }
    }

    private fun reasons(model: CatalogModel, availableGb: Float): List<String> {
        val reasons = mutableListOf<String>()
        val ramMb = model.estimatedRuntimeRamMb
        val ramGb = ramMb / 1000.0
        reasons += if (ramGb <= availableGb) {
            "Estimated runtime RAM: ${ramMb}MB (fits comfortably)"
        } else {
            "Estimated runtime RAM: ${ramMb}MB (high load for device)"
        }
        if (model.sizeBytes > 0) {
            val gb = model.sizeBytes / 1_000_000_000.0
            reasons += "Storage: %.1f GB".format(gb)
        }
        if (model.parameters.isNotBlank()) reasons += "${model.parameters} parameters"
        if (model.quantization.isNotBlank()) reasons += "${model.quantization} quantization"
        return reasons
    }
}
