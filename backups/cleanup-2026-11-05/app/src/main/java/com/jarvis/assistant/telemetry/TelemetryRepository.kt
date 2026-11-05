package com.jarvis.assistant.telemetry

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 1-second rolling telemetry sampler keeping a 300-sample history (~5 minutes)
 * for CPU, RAM, and inference metrics. With Battery saver ON it samples every
 * 5 seconds instead.
 */
class TelemetryRepository(private val context: Context) {

    companion object {
        const val MAX_HISTORY_SAMPLES = 300
        const val MAX_GENERATION_STATS = 60
        const val SAMPLE_INTERVAL_MS = 1_000L
        /** Sampling interval while Settings › Battery saver is ON. */
        const val BATTERY_SAVER_SAMPLE_INTERVAL_MS = 5_000L

        /** Settings › Battery saver (AppPreferences.isBatterySaverEnabled). Read live each tick. */
        fun isBatterySaverOn(): Boolean =
            runCatching { com.jarvis.assistant.JarvisApp.instance.preferences.isBatterySaverEnabled }
                .getOrDefault(false)

        fun currentSampleIntervalMs(): Long =
            if (isBatterySaverOn()) BATTERY_SAVER_SAMPLE_INTERVAL_MS else SAMPLE_INTERVAL_MS

        @Volatile
        private var instance: TelemetryRepository? = null

        fun getInstance(context: Context): TelemetryRepository {
            return instance ?: synchronized(this) {
                instance ?: TelemetryRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _deviceMetrics = MutableStateFlow<DeviceMetrics?>(null)
    val deviceMetrics: StateFlow<DeviceMetrics?> = _deviceMetrics.asStateFlow()

    private val _history = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val history: StateFlow<List<TelemetrySample>> = _history.asStateFlow()

    private val _generationHistory = MutableStateFlow<List<GenerationStat>>(emptyList())
    val generationHistory: StateFlow<List<GenerationStat>> = _generationHistory.asStateFlow()

    private val _isSampling = MutableStateFlow(false)
    val isSampling: StateFlow<Boolean> = _isSampling.asStateFlow()

    private var samplerJob: Job? = null

    @Volatile private var genStartMs = 0L
    @Volatile private var genFirstTokenMs = 0L
    @Volatile private var genTokens = 0L
    @Volatile private var generating = false
    private var clientCount = 0

    init {
        refreshDeviceMetrics()
    }

    @Synchronized
    fun startSampling() {
        clientCount++
        if (samplerJob != null) return
        _isSampling.value = true
        samplerJob = scope.launch {
            while (isActive) {
                sample()
                delay(currentSampleIntervalMs())
            }
        }
    }

    @Synchronized
    fun stopSampling() {
        clientCount = (clientCount - 1).coerceAtLeast(0)
        if (clientCount == 0) {
            samplerJob?.cancel()
            samplerJob = null
            _isSampling.value = false
        }
    }

    /** Call when local inference starts producing a reply. */
    fun beginGeneration() {
        genStartMs = System.currentTimeMillis()
        genFirstTokenMs = 0L
        genTokens = 0L
        generating = true
    }

    /** Call once per generated token. */
    fun onToken() {
        if (!generating) return
        if (genTokens == 0L) genFirstTokenMs = System.currentTimeMillis() - genStartMs
        genTokens++
    }

    /** Call when the reply finishes (normally or with an error). */
    fun endGeneration(modelName: String, promptTokens: Long = 0L) {
        if (!generating) return
        generating = false
        val totalMs = (System.currentTimeMillis() - genStartMs).coerceAtLeast(1L)
        recordGeneration(
            GenerationStat(
                timestampMs = System.currentTimeMillis(),
                promptTokens = promptTokens,
                generatedTokens = genTokens,
                tokensPerSecond = liveTokensPerSecond(),
                totalTimeMs = totalMs,
                firstTokenMs = genFirstTokenMs,
                modelName = modelName
            )
        )
    }

    private fun liveTokensPerSecond(): Float {
        if (genTokens < 2) return 0f
        val decodeMs = (System.currentTimeMillis() - genStartMs - genFirstTokenMs).coerceAtLeast(1L)
        return (genTokens - 1) * 1000f / decodeMs
    }

    fun recordGeneration(stat: GenerationStat) {
        _generationHistory.update { history ->
            (history + stat).takeLast(MAX_GENERATION_STATS)
        }
    }

    fun refreshDeviceMetrics() {
        val info = DeviceInfoCollector.collectDeviceInfo(context)
        _deviceMetrics.value = DeviceMetrics(
            totalRamMb = info.totalRamBytes / (1024L * 1024L),
            availableRamMb = info.availableRamBytes / (1024L * 1024L),
            cpuCores = info.cpuCores,
            deviceModel = "${info.manufacturer} ${info.deviceName}".trim(),
            androidVersion = info.androidVersion,
            isVulkanSupported = info.isVulkanSupported,
            freeStorageBytes = info.freeStorageBytes
        )
    }

    private fun sample() {
        refreshDeviceMetrics()
        val device = _deviceMetrics.value

        val sample = TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            tokensPerSecond = if (generating) liveTokensPerSecond()
                else _generationHistory.value.lastOrNull()?.tokensPerSecond ?: 0f,
            ramUsedMb = device?.usedRamMb?.toFloat() ?: 0f,
            ramTotalMb = device?.totalRamMb?.toFloat() ?: 0f,
            isGenerating = generating
        )

        _history.update { (it + sample).takeLast(MAX_HISTORY_SAMPLES) }
    }
}
