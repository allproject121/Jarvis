package com.jarvis.assistant.androllm.memory.extraction

import android.util.Log
import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryExchange
import com.jarvis.assistant.androllm.memory.intelligence.LlmTextBackend

/**
 * Runs the "extract long-term memories" step through one [LlmTextBackend]:
 * a short, low-temperature generation whose output is parsed by the lenient
 * [ExtractionJsonParser]. Only durable facts survive the prompt filter.
 *
 * Returns [Result.failure] when the backend is unavailable so the routing
 * layer can fall back to another backing; an empty list means "nothing worth
 * storing".
 */
class MemoryExtractor(private val backend: LlmTextBackend) {

    suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>> {
        if (!backend.isAvailable()) {
            return Result.failure(IllegalStateException("${backend.name} backend unavailable"))
        }
        val output = backend.generate(
            systemPrompt = ExtractionPrompts.SYSTEM_INSTRUCTION_WITH_SCHEMA,
            userContent = ExtractionPrompts.buildUserContent(exchange),
            maxTokens = MAX_TOKENS,
            temperature = TEMPERATURE
        ) ?: return Result.failure(IllegalStateException("${backend.name} backend returned no output"))

        val memories = ExtractionJsonParser.parse(output)
        Log.i(TAG, "Extraction (${backend.name}): ${memories.size} memory(s) from exchange")
        return Result.success(memories)
    }

    private companion object {
        const val TAG = "AndroMemExtractor"
        const val MAX_TOKENS = 220
        const val TEMPERATURE = 0.2f
    }
}
