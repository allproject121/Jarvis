package com.jarvis.assistant.androllm.models.compatibility

import com.jarvis.assistant.androllm.engine.support.Model
import com.jarvis.assistant.androllm.models.catalog.CatalogModel
import com.jarvis.assistant.androllm.models.download.ModelCompatibility
import com.jarvis.assistant.telemetry.DeviceHardwareInfo
import java.util.Locale

enum class CompatibilityRating {
    EXCELLENT,
    MODERATE,
    INSUFFICIENT_RAM,
    UNSUPPORTED
}

data class CompatibilityAnalysis(
    val rating: CompatibilityRating,
    val title: String,
    val summary: String,
    val estimatedMemoryMb: Long,
    val estimatedTokPerSec: Float,
    val isRamSufficient: Boolean
)

/**
 * Device-fit analysis for a model (ported from AndroLLM feature/models
 * CompatibilityAnalyzer): RAM vs the model's minimum/recommended RAM, an
 * estimated memory footprint, and a coarse throughput expectation.
 * Uses Jarvis' [DeviceHardwareInfo] (telemetry/DeviceInfoCollector).
 */
object CompatibilityAnalyzer {

    fun analyze(model: Model, hardware: DeviceHardwareInfo): CompatibilityAnalysis =
        analyze(
            fileSize = model.fileSize,
            contextLength = model.contextLength,
            minRamGb = model.minRamGb,
            recommendedRamGb = model.recommendedRamGb,
            hardware = hardware,
            unsupportedReason = null
        )

    /**
     * Catalog variant: also folds in the LiteRT container gate
     * ([ModelCompatibility.check]) — an artifact the runtime can never load is
     * [CompatibilityRating.UNSUPPORTED] regardless of RAM.
     */
    fun analyze(model: CatalogModel, hardware: DeviceHardwareInfo): CompatibilityAnalysis {
        val gate = ModelCompatibility.check(model)
        return analyze(
            fileSize = model.sizeBytes,
            contextLength = model.contextLength,
            minRamGb = model.minRamGb,
            recommendedRamGb = model.recommendedRamGb,
            hardware = hardware,
            unsupportedReason = gate.reason.takeIf { !gate.compatible }
        )
    }

    private fun analyze(
        fileSize: Long,
        contextLength: Int,
        minRamGb: Float,
        recommendedRamGb: Float,
        hardware: DeviceHardwareInfo,
        unsupportedReason: String?
    ): CompatibilityAnalysis {
        val totalRamGb = hardware.totalRamGb

        // Estimate memory usage from file size and context length (KV cache).
        val estMemoryBytes = fileSize + (contextLength * 1024L * 2)
        val estMemoryMb = estMemoryBytes / (1024 * 1024)

        val isRamSufficient = totalRamGb >= minRamGb

        val rating = when {
            unsupportedReason != null -> CompatibilityRating.UNSUPPORTED
            totalRamGb < minRamGb -> CompatibilityRating.INSUFFICIENT_RAM
            totalRamGb >= recommendedRamGb -> CompatibilityRating.EXCELLENT
            else -> CompatibilityRating.MODERATE
        }

        val estimatedTokPerSec = when (rating) {
            CompatibilityRating.EXCELLENT -> if (hardware.cpuCores >= 8) 16.5f else 10.0f
            CompatibilityRating.MODERATE -> 6.5f
            CompatibilityRating.INSUFFICIENT_RAM -> 0.0f
            CompatibilityRating.UNSUPPORTED -> 0.0f
        }

        val (title, summary) = when (rating) {
            CompatibilityRating.EXCELLENT -> Pair(
                "✓ Excellent Compatibility",
                "Your device has ${fmt(totalRamGb)} GB RAM, exceeding the recommended ${fmt(recommendedRamGb)} GB."
            )
            CompatibilityRating.MODERATE -> Pair(
                "⚠ Moderate Performance Expected",
                "Your device meets the minimum ${fmt(minRamGb)} GB RAM requirement. Performance may slow down during long contexts."
            )
            CompatibilityRating.INSUFFICIENT_RAM -> Pair(
                "✕ Insufficient RAM",
                "Model requires at least ${fmt(minRamGb)} GB RAM. Your device only has ${fmt(totalRamGb)} GB RAM."
            )
            CompatibilityRating.UNSUPPORTED -> Pair(
                "✕ Unsupported Model",
                unsupportedReason ?: "This model format or architecture is incompatible with your system."
            )
        }

        return CompatibilityAnalysis(
            rating = rating,
            title = title,
            summary = summary,
            estimatedMemoryMb = estMemoryMb,
            estimatedTokPerSec = estimatedTokPerSec,
            isRamSufficient = isRamSufficient
        )
    }

    private fun fmt(gb: Float): String = String.format(Locale.US, "%.1f", gb)
}
