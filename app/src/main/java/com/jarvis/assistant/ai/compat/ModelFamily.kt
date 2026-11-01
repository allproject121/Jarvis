package com.jarvis.assistant.ai.compat

/**
 * Supported Small Language Model (SLM) families on Android.
 * Each family has distinct tokenizers, special tokens, and prompt framing conventions.
 */
enum class ModelFamily(
    val displayName: String,
    val bosToken: String,
    val eosToken: String,
    val stopTokens: List<String>,
    val nativeToolMarkers: Boolean = false
) {
    QWEN(
        displayName = "Qwen 2.5",
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>", "</tool_call>"),
        nativeToolMarkers = true
    ),
    GEMMA(
        displayName = "Gemma 2",
        bosToken = "<bos>",
        eosToken = "<eos>",
        stopTokens = listOf("<end_of_turn>", "<eos>"),
        nativeToolMarkers = true
    ),
    LLAMA3(
        displayName = "Llama 3.2",
        bosToken = "<|begin_of_text|>",
        eosToken = "<|eot_id|>",
        stopTokens = listOf("<|eot_id|>", "<|end_of_text|>"),
        nativeToolMarkers = true
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        bosToken = "",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>"),
        nativeToolMarkers = true
    ),
    SMOLLM(
        displayName = "SmolLM2",
        bosToken = "<|im_start|>",
        eosToken = "<|im_end|>",
        stopTokens = listOf("<|im_end|>", "<|endoftext|>"),
        nativeToolMarkers = false
    );

    companion object {
        fun resolveFromModelId(modelId: String): ModelFamily {
            val lower = modelId.lowercase()
            return when {
                lower.contains("gemma") -> GEMMA
                lower.contains("llama") -> LLAMA3
                lower.contains("deepseek") -> DEEPSEEK
                lower.contains("smol") -> SMOLLM
                else -> QWEN
            }
        }
    }
}
