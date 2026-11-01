package com.jarvis.assistant.androllm.memory.summarize

import android.util.Log
import com.jarvis.assistant.androllm.memory.intelligence.LlmTextBackend

/**
 * Incremental conversation summarizer. Every `summarizationInterval` messages
 * the latest summary + new messages are condensed into an updated rolling
 * summary, stored separately so the context builder can prefer summaries over
 * raw histories.
 */
class ConversationSummarizer(private val backend: LlmTextBackend) {

    suspend fun summarize(
        conversationId: String,
        previousSummary: String?,
        recentMessages: List<Pair<String, String>>
    ): Result<String> {
        if (!backend.isAvailable()) {
            return Result.failure(IllegalStateException("${backend.name} backend unavailable"))
        }
        val output = backend.generate(
            systemPrompt = SummaryPrompts.SYSTEM_INSTRUCTION,
            userContent = SummaryPrompts.buildUserContent(previousSummary, recentMessages),
            maxTokens = 220,
            temperature = 0.2f
        )
        val summary = output?.trim().orEmpty()
        if (summary.isEmpty()) {
            Log.d(TAG, "Summarization returned empty output (conversation $conversationId)")
            return Result.failure(IllegalStateException("Empty summary"))
        }
        Log.i(TAG, "Summarized conversation $conversationId (${recentMessages.size} messages, ${backend.name})")
        return Result.success(summary)
    }

    private companion object {
        const val TAG = "AndroMemSummarizer"
    }
}
