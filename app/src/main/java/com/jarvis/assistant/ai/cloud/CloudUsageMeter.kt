package com.jarvis.assistant.ai.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

data class TokenUsageRecord(
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val estimatedCostUsd: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class CloudUsageStats(
    val totalRequests: Int = 0,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalTokens: Long = 0,
    val totalEstimatedCostUsd: Double = 0.0
)

/**
 * Tracks token usage and API expenditures for cloud and local models.
 * Calculates costs for GPT-4o, GPT-4o-mini, Gemini Flash, Claude 3.5 Sonnet, etc.
 */
class CloudUsageMeter private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CloudUsageMeter"
        private const val MAX_HISTORY = 500

        @Volatile
        private var instance: CloudUsageMeter? = null

        fun getInstance(context: Context): CloudUsageMeter {
            return instance ?: synchronized(this) {
                instance ?: CloudUsageMeter(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Estimated cost per 1M tokens ($ prompt / $ completion).
         */
        fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): Double {
            val lower = model.lowercase()
            val (promptPer1M, completionPer1M) = when {
                lower.contains("gpt-4o-mini") -> 0.15 to 0.60
                lower.contains("gpt-4o") -> 2.50 to 10.00
                lower.contains("gemini-2.0-flash") || lower.contains("gemini-1.5-flash") -> 0.075 to 0.30
                lower.contains("gemini-1.5-pro") -> 1.25 to 5.00
                lower.contains("claude-3-5-sonnet") -> 3.00 to 15.00
                lower.contains("deepseek") -> 0.14 to 0.28
                else -> 0.0 to 0.0 // Local models (Qwen, Gemma, Llama) are free ($0.00)
            }
            val promptCost = (promptTokens.toDouble() / 1_000_000.0) * promptPer1M
            val compCost = (completionTokens.toDouble() / 1_000_000.0) * completionPer1M
            return promptCost + compCost
        }
    }

    private val records = ConcurrentLinkedDeque<TokenUsageRecord>()

    private val _stats = MutableStateFlow(CloudUsageStats())
    val stats: StateFlow<CloudUsageStats> = _stats.asStateFlow()

    fun record(model: String, promptTokens: Int, completionTokens: Int) {
        val total = promptTokens + completionTokens
        val cost = estimateCost(model, promptTokens, completionTokens)
        val rec = TokenUsageRecord(
            model = model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = total,
            estimatedCostUsd = cost
        )

        records.add(rec)
        while (records.size > MAX_HISTORY) {
            records.pollFirst()
        }

        val current = _stats.value
        _stats.value = CloudUsageStats(
            totalRequests = current.totalRequests + 1,
            totalPromptTokens = current.totalPromptTokens + promptTokens,
            totalCompletionTokens = current.totalCompletionTokens + completionTokens,
            totalTokens = current.totalTokens + total,
            totalEstimatedCostUsd = current.totalEstimatedCostUsd + cost
        )

        Log.d(TAG, "Recorded usage: $model -> $total tokens (cost: $$cost)")
    }
}
