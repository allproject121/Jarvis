package com.jarvis.assistant.androllm.memory.intelligence

import android.content.Context
import android.util.Log
import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryExchange
import com.jarvis.assistant.androllm.memory.extraction.MemoryExtractor
import com.jarvis.assistant.androllm.memory.summarize.ConversationSummarizer

/**
 * Provider-independent "thinking" used by the memory pipeline: extraction and
 * summarization. A failure surfaces as [Result.failure] so callers can fall
 * back to another backing.
 */
interface MemoryIntelligence {
    suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>>
    suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String>
}

/** [MemoryIntelligence] over a single [LlmTextBackend]. */
class BackendMemoryIntelligence(backend: LlmTextBackend) : MemoryIntelligence {
    private val extractor = MemoryExtractor(backend)
    private val summarizer = ConversationSummarizer(backend)

    override suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>> =
        extractor.extract(exchange)

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> = summarizer.summarize(conversationId, previousSummary, recentMessages)
}

/** Which backing the memory pipeline may use. */
enum class MemoryIntelligenceRoute(val label: String) {
    /** Cloud text routing when configured + online, falling back to the on-device model. */
    AUTO("Auto (cloud, then on-device)"),
    /** On-device GGUF model only — nothing leaves the phone. */
    LOCAL_ONLY("On-device only"),
    /** Cloud text routing only. */
    CLOUD_ONLY("Cloud only");

    companion object {
        fun fromName(raw: String?): MemoryIntelligenceRoute =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}

/**
 * Routes memory intelligence (ported from AndroLLM RoutingMemoryIntelligence):
 *  - AUTO: cloud when configured, falling back to local on failure; local otherwise;
 *  - LOCAL_ONLY / CLOUD_ONLY: the single chosen backing.
 */
class RoutingMemoryIntelligence(
    private val cloud: MemoryIntelligence,
    private val local: MemoryIntelligence,
    private val cloudAvailable: () -> Boolean,
    private val routeProvider: () -> MemoryIntelligenceRoute
) : MemoryIntelligence {

    override suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>> =
        route(
            cloudCall = { cloud.extract(exchange) },
            localCall = { local.extract(exchange) },
            what = "extraction"
        )

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> = route(
        cloudCall = { cloud.summarize(conversationId, previousSummary, recentMessages) },
        localCall = { local.summarize(conversationId, previousSummary, recentMessages) },
        what = "summarization"
    )

    private suspend fun <T> route(
        cloudCall: suspend () -> Result<T>,
        localCall: suspend () -> Result<T>,
        what: String
    ): Result<T> {
        return when (routeProvider()) {
            MemoryIntelligenceRoute.LOCAL_ONLY -> localCall()
            MemoryIntelligenceRoute.CLOUD_ONLY -> cloudCall()
            MemoryIntelligenceRoute.AUTO -> {
                if (!cloudAvailable()) return localCall()
                val result = cloudCall()
                if (result.isSuccess) {
                    result
                } else {
                    Log.w(TAG, "Cloud $what failed (${result.exceptionOrNull()?.message}); falling back to local")
                    localCall()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AndroMemRouting"

        fun create(context: Context, routeProvider: () -> MemoryIntelligenceRoute): RoutingMemoryIntelligence {
            val appContext = context.applicationContext
            val cloudBackend = CloudTextBackend(appContext)
            val localBackend = LocalTextBackend(appContext)
            val gatewayEnabled = {
                com.jarvis.assistant.androllm.AndroLlmFlags.isEnabled(
                    appContext,
                    com.jarvis.assistant.androllm.AndroLlmFlags.CLOUD_GATEWAY
                )
            }
            // Cloud side: the AndroLLM LiteLLM gateway when the user enabled it
            // (default OFF), always falling back to Jarvis' own quiet text routing.
            val cloud = CloudMemoryIntelligence(
                cloudGateway = com.jarvis.assistant.androllm.cloud.AndroLlmCloud.getInstance(appContext).gateway,
                fallback = BackendMemoryIntelligence(cloudBackend),
                gatewayEnabled = gatewayEnabled
            )
            return RoutingMemoryIntelligence(
                cloud = cloud,
                local = LocalMemoryIntelligence(localBackend),
                cloudAvailable = { cloudBackend.isAvailable() || gatewayEnabled() },
                routeProvider = routeProvider
            )
        }
    }
}
