package com.jarvis.assistant.androllm.memory.intelligence

import com.jarvis.assistant.androllm.cloud.CloudGateway
import com.jarvis.assistant.androllm.cloud.model.CloudChatMessage
import com.jarvis.assistant.androllm.cloud.model.CloudException
import com.jarvis.assistant.androllm.cloud.model.CloudGenerationConfig
import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryExchange
import com.jarvis.assistant.androllm.memory.extraction.ExtractionJsonParser
import com.jarvis.assistant.androllm.memory.extraction.ExtractionPrompts
import com.jarvis.assistant.androllm.memory.extraction.ExtractionSchema
import com.jarvis.assistant.androllm.memory.util.MemoryLogger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * [MemoryIntelligence] backed by the AndroLLM cloud gateway (any LiteLLM /
 * OpenAI-compatible provider the user configured in the Cloud screen). Ported
 * from AndroLLM CloudMemoryIntelligence: same prompts + JSON-schema contract as
 * the local backing, quiet non-streaming [CloudGateway.chatOnce] call, so the
 * chat UI never sees it and Gemini Live is never touched.
 *
 * Jarvis-first: when [gatewayEnabled] is false (the CLOUD_GATEWAY flag is OFF by
 * default) or the gateway call fails, it delegates to [fallback] — Jarvis' own
 * quiet text routing ([BackendMemoryIntelligence] over [CloudTextBackend]).
 */
class CloudMemoryIntelligence(
    private val cloudGateway: CloudGateway,
    private val fallback: MemoryIntelligence,
    private val gatewayEnabled: () -> Boolean,
    private val logger: MemoryLogger = MemoryLogger
) : MemoryIntelligence {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>> {
        if (!gatewayUsable()) return fallback.extract(exchange)
        val result = runCatchingCancellable {
            val messages = listOf(
                CloudChatMessage(role = "system", content = ExtractionPrompts.SYSTEM_INSTRUCTION),
                CloudChatMessage(role = "user", content = ExtractionPrompts.buildUserContent(exchange))
            )
            val output = cloudGateway.chatOnce(
                messages = messages,
                config = CloudGenerationConfig(
                    temperature = 0.2,
                    topP = 1.0,
                    maxTokens = 220,
                    jsonSchema = json.parseToJsonElement(ExtractionSchema.JSON_SCHEMA)
                )
            )
            val memories = ExtractionJsonParser.parse(output)
            logger.info("Gateway extraction: ${memories.size} memory(s) from exchange")
            memories
        }
        if (result.isSuccess) return result
        logger.warn("Gateway extraction failed (${result.exceptionOrNull()?.message}); using Jarvis text routing")
        return fallback.extract(exchange)
    }

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> {
        if (!gatewayUsable()) return fallback.summarize(conversationId, previousSummary, recentMessages)
        val result = runCatchingCancellable {
            val messages = listOf(
                CloudChatMessage(role = "system", content = com.jarvis.assistant.androllm.memory.summarize.SummaryPrompts.SYSTEM_INSTRUCTION),
                CloudChatMessage(
                    role = "user",
                    content = com.jarvis.assistant.androllm.memory.summarize.SummaryPrompts.buildUserContent(previousSummary, recentMessages)
                )
            )
            val output = cloudGateway.chatOnce(
                messages = messages,
                config = CloudGenerationConfig(temperature = 0.2, topP = 1.0, maxTokens = 220)
            )
            val summary = output.trim()
            if (summary.isEmpty()) throw CloudException("Empty summary")
            logger.info("Gateway summarized conversation $conversationId (${recentMessages.size} messages)")
            summary
        }
        if (result.isSuccess) return result
        logger.warn("Gateway summarization failed (${result.exceptionOrNull()?.message}); using Jarvis text routing")
        return fallback.summarize(conversationId, previousSummary, recentMessages)
    }

    private suspend fun gatewayUsable(): Boolean =
        gatewayEnabled() && try {
            cloudGateway.isConfigured()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
