package com.jarvis.assistant.androllm.memory.intelligence

import android.content.Context
import com.jarvis.assistant.androllm.memory.ExtractedMemory
import com.jarvis.assistant.androllm.memory.MemoryExchange
import com.jarvis.assistant.androllm.memory.extraction.MemoryExtractor
import com.jarvis.assistant.androllm.memory.summarize.ConversationSummarizer

/**
 * [MemoryIntelligence] backed by the LOCAL on-device model (ported from AndroLLM
 * LocalMemoryIntelligence). Used when cloud is off, or as the fallback when the
 * cloud call fails — so extraction keeps working on a fully offline device.
 *
 * Jarvis note: the local model is Jarvis' llama.cpp GGUF engine reached through
 * [LocalTextBackend] (only when already loaded and idle — never loads weights).
 */
class LocalMemoryIntelligence(
    private val extractor: MemoryExtractor,
    private val summarizer: ConversationSummarizer
) : MemoryIntelligence {

    constructor(backend: LlmTextBackend) : this(MemoryExtractor(backend), ConversationSummarizer(backend))

    constructor(context: Context) : this(LocalTextBackend(context.applicationContext))

    override suspend fun extract(exchange: MemoryExchange): Result<List<ExtractedMemory>> =
        extractor.extract(exchange)

    override suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> = summarizer.summarize(conversationId, previousSummary, recentMessages)
}
