package com.jarvis.assistant.telemetry

data class TelemetrySample(
    val timestampMs: Long,
    val tokensPerSecond: Float,
    val ramUsedMb: Float,
    val ramTotalMb: Float,
    val isGenerating: Boolean
)

data class GenerationStat(
    val timestampMs: Long,
    val promptTokens: Long,
    val generatedTokens: Long,
    val tokensPerSecond: Float,
    val totalTimeMs: Long,
    val firstTokenMs: Long,
    val modelName: String
)

data class DeviceMetrics(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val deviceModel: String,
    val androidVersion: String,
    val isVulkanSupported: Boolean,
    val freeStorageBytes: Long
) {
    val usedRamMb: Long get() = (totalRamMb - availableRamMb).coerceAtLeast(0L)
    val ramUsageFraction: Float get() = if (totalRamMb > 0) usedRamMb.toFloat() / totalRamMb else 0f
}
