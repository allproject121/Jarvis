package com.jarvis.assistant.ui.compose.feature.home

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.telemetry.DeviceMetrics
import com.jarvis.assistant.telemetry.TelemetryRepository
import com.jarvis.assistant.telemetry.TelemetrySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Fallback scope for backend singletons (JarvisApp normally creates them first with its own scope). */
private val homeFallbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Live snapshot for the Home dashboard. Every value comes from Jarvis' real
 * backend: TelemetryRepository (RAM, tokens/sec), LocalModelManager (active
 * model + weights on disk), LocalAiEngine (engine lifecycle) and ChatRepository.
 */
data class HomeTelemetry(
    val deviceMetrics: DeviceMetrics? = null,
    val history: List<TelemetrySample> = emptyList(),
    val activeModelId: String = LocalAiConfig.DEFAULT_MODEL_ID,
    val activeModelName: String = "",
    val activeModelSizeBytes: Long = 0L,
    val activeModelDownloaded: Boolean = false,
    val engineState: LocalEngineState = LocalEngineState.Uninitialized,
    val storageTotalBytes: Long = 0L,
    val recentTurns: List<ChatTurn> = emptyList(),
    val totalTurns: Int = 0
) {
    val latest: TelemetrySample? get() = history.lastOrNull()

    val isModelLoaded: Boolean
        get() = engineState is LocalEngineState.Ready || engineState is LocalEngineState.Generating
    val isGenerating: Boolean
        get() = engineState is LocalEngineState.Generating || latest?.isGenerating == true

    val ramUsedMb: Float
        get() = latest?.ramUsedMb ?: deviceMetrics?.usedRamMb?.toFloat() ?: 0f
    val ramTotalMb: Float
        get() = latest?.ramTotalMb ?: deviceMetrics?.totalRamMb?.toFloat() ?: 0f
    val tokensPerSecond: Float get() = latest?.tokensPerSecond ?: 0f
    val speedHistory: List<Float> get() = history.map { it.tokensPerSecond }
    val vulkanSupported: Boolean get() = deviceMetrics?.isVulkanSupported == true

    val storageFreeFraction: Float
        get() {
            val free = deviceMetrics?.freeStorageBytes ?: return 0f
            return if (storageTotalBytes > 0) (free.toFloat() / storageTotalBytes).coerceIn(0f, 1f) else 0f
        }
}

private data class ModelPart(
    val activeId: String,
    val downloaded: Boolean,
    val engineState: LocalEngineState
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val telemetryRepository = TelemetryRepository.getInstance(application)
    private val modelManager = LocalModelManager.getInstance(application, homeFallbackScope)
    private val engine = LocalAiEngine.getInstance(application, homeFallbackScope)

    private val turnsFlow: Flow<List<ChatTurn>> =
        runCatching { (application as? JarvisApp)?.chatRepository?.turns }.getOrNull() ?: flowOf(emptyList())

    private val storageTotalBytes: Long = runCatching {
        StatFs(Environment.getDataDirectory().path).totalBytes
    }.getOrDefault(0L)

    private val modelPart: Flow<ModelPart> = combine(
        modelManager.activeModelId,
        modelManager.downloadState,
        engine.engineState
    ) { id, _, state ->
        ModelPart(
            activeId = id,
            downloaded = runCatching { modelManager.isModelReady(id) }.getOrDefault(false),
            engineState = state
        )
    }.flowOn(Dispatchers.IO)

    /** Null until the first emission — the screen shows skeleton cards meanwhile. */
    val telemetry: StateFlow<HomeTelemetry?> = combine(
        telemetryRepository.deviceMetrics,
        telemetryRepository.history,
        modelPart,
        turnsFlow
    ) { device, history, model, turns ->
        val descriptor = LocalAiConfig.getModelById(model.activeId)
        HomeTelemetry(
            deviceMetrics = device,
            history = history,
            activeModelId = model.activeId,
            activeModelName = descriptor?.name ?: model.activeId,
            activeModelSizeBytes = descriptor?.downloadSizeBytes ?: 0L,
            activeModelDownloaded = model.downloaded,
            engineState = model.engineState,
            storageTotalBytes = storageTotalBytes,
            recentTurns = turns.takeLast(5).asReversed(),
            totalTurns = turns.size
        )
    }.map<HomeTelemetry, HomeTelemetry?> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    init {
        telemetryRepository.startSampling()
    }

    override fun onCleared() {
        telemetryRepository.stopSampling()
        super.onCleared()
    }

    fun refresh() {
        telemetryRepository.refreshDeviceMetrics()
    }
}
