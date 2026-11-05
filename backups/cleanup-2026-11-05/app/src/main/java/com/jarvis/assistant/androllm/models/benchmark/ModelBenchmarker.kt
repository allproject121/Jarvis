package com.jarvis.assistant.androllm.models.benchmark

import android.os.Debug
import com.jarvis.assistant.androllm.engine.AndroLlmEngines
import com.jarvis.assistant.androllm.engine.LocalLiteRtModel
import com.jarvis.assistant.androllm.engine.api.EngineRepository
import com.jarvis.assistant.androllm.engine.models.EngineStats
import com.jarvis.assistant.androllm.engine.support.Model
import com.jarvis.assistant.androllm.engine.support.Result
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Result of one benchmark run (ported from AndroLLM feature/models BenchmarkReport). */
@Serializable
data class BenchmarkReport(
    val modelId: String,
    val modelName: String,
    val quantization: String,
    val timestamp: String,
    val loadTimeMs: Long,
    val promptTokens: Long,
    val promptTimeMs: Long,
    val generatedTokens: Long,
    val generationTimeMs: Long,
    val tokensPerSecond: Float,
    val peakRamMb: Float,
    /** Engine-reported time to first token (0 = unknown). */
    val firstTokenMs: Long = 0,
    /** Backend the engine actually ran on ("" = unknown). */
    val backend: String = ""
)

/**
 * Loads a model into an [EngineRepository] and measures load time and
 * generation throughput (ported from AndroLLM ModelBenchmarker).
 *
 * Jarvis note: the GGUF / llama.cpp benchmark lives in the Models screen
 * ([com.jarvis.assistant.ui.compose.feature.models.ModelsViewModel.runBenchmark]);
 * this one targets the optional AndroLLM LiteRT-LM engine. Unlike upstream it
 * never substitutes placeholder numbers: when the engine reports no stats, the
 * wall-clock measurements are used and token counts come from the generation
 * state.
 */
object ModelBenchmarker {

    const val DEFAULT_PROMPT = "Explain the theory of relativity in three bullet points."

    private val json = Json { prettyPrint = true }

    suspend fun runBenchmark(
        model: Model,
        engineRepository: EngineRepository,
        promptText: String = DEFAULT_PROMPT
    ): Result<BenchmarkReport> = com.jarvis.assistant.androllm.engine.support.runCatching {
        val startTime = System.currentTimeMillis()

        // 1. Measure load time
        val load = engineRepository.loadModel(model)
        if (load is Result.Error) throw load.exception
        val loadTimeMs = System.currentTimeMillis() - startTime

        // 2. Measure generation performance
        val genStart = System.currentTimeMillis()
        val gen = engineRepository.generate(promptText)
        if (gen is Result.Error) throw gen.exception
        val wallGenMs = System.currentTimeMillis() - genStart

        // 3. Extract stats (engine-reported, else measured)
        val stats: EngineStats? = engineRepository.performanceStats.value
        val generated = stats?.generatedTokens?.takeIf { it > 0 }
            ?: (engineRepository.generationState.value as? com.jarvis.assistant.androllm.engine.api.GenerationState.Completed)
                ?.stats?.generatedTokens
            ?: 0L
        val genMs = stats?.generationTimeMs?.takeIf { it > 0 } ?: wallGenMs
        val tps = stats?.tokensPerSecond?.takeIf { it > 0f }
            ?: if (generated > 0 && genMs > 0) generated * 1000f / genMs else 0f
        val peakMb = stats?.memoryPeakBytes?.takeIf { it > 0 }?.let { it / (1024f * 1024f) }
            ?: (Debug.getPss() / 1024f)

        BenchmarkReport(
            modelId = model.id,
            modelName = model.name,
            quantization = model.quantization,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            loadTimeMs = loadTimeMs,
            promptTokens = stats?.promptTokens ?: 0L,
            promptTimeMs = stats?.promptTimeMs ?: 0L,
            generatedTokens = generated,
            generationTimeMs = genMs,
            tokensPerSecond = tps,
            peakRamMb = peakMb,
            firstTokenMs = stats?.firstTokenMs ?: 0L,
            backend = stats?.backend.orEmpty()
        )
    }

    /** Benchmarks an installed LiteRT-LM file on the AndroLLM engine (user's backend preference). */
    suspend fun runLiteRtBenchmark(
        engines: AndroLlmEngines,
        local: LocalLiteRtModel,
        promptText: String = DEFAULT_PROMPT
    ): Result<BenchmarkReport> {
        val init = engines.ensureInitialized()
        if (init is Result.Error) return init
        // Start from a cold engine so the load time is real.
        engines.unload()
        return runBenchmark(engines.toEngineModel(local), engines.repository, promptText)
    }

    fun exportToJson(report: BenchmarkReport): String =
        json.encodeToString(BenchmarkReport.serializer(), report)
}
