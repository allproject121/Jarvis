package com.jarvis.assistant.ai.local

/**
 * Configuration parameters for the on-device Local AI inference engine.
 */
data class LocalAiConfig(
    val modelId: String = DEFAULT_MODEL_ID,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
    val contextWindowLimit: Int = 2048,
    val threads: Int = 4,
    val enableGpuAcceleration: Boolean = false
) {
    companion object {
        const val MODEL_QWEN_0_5B = "qwen-2.5-0.5b-instruct"
        const val MODEL_QWEN_1_5B = "qwen-2.5-1.5b-instruct"
        const val MODEL_HERMES_1_5B = "hermes-2-pro-1.5b"
        const val MODEL_SMOLLM_1_7B = "smollm2-1.7b-instruct"

        const val DEFAULT_MODEL_ID = MODEL_QWEN_0_5B

        val AVAILABLE_MODELS = listOf(
            LocalModelDescriptor(
                id = MODEL_QWEN_0_5B,
                name = "Qwen 2.5 (0.5B Micro-Edge)",
                provider = "Alibaba / Open-Source",
                parameters = "0.5 Billion",
                quantization = "GGUF Q4_K_M",
                downloadSizeBytes = 397_908_480L, // ~380 MB
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                recommendedMinRamGb = 2,
                description = "Ultra-lightweight edge model. Installs in under 30 seconds, runs with blazing speed on any Android phone (2GB+ RAM) with zero cloud dependency."
            ),
            LocalModelDescriptor(
                id = MODEL_QWEN_1_5B,
                name = "Qwen 2.5 (1.5B Instruct)",
                provider = "Alibaba / Open-Source",
                parameters = "1.5 Billion",
                quantization = "GGUF Q4_K_M",
                downloadSizeBytes = 986_099_840L, // ~940 MB
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                recommendedMinRamGb = 4,
                description = "Ultra-fast multilingual model with superior Hindi/Hinglish language fluency and fast token generation."
            ),
            LocalModelDescriptor(
                id = MODEL_HERMES_1_5B,
                name = "Hermes 2 Pro (1.5B Qwen)",
                provider = "Nous Research",
                parameters = "1.5 Billion",
                quantization = "GGUF Q4_K_M",
                downloadSizeBytes = 986_099_840L, // ~940 MB
                downloadUrl = "https://huggingface.co/NousResearch/Hermes-2-Pro-Qwen-1.5B-GGUF/resolve/main/Hermes-2-Pro-Qwen-1.5B-Q4_K_M.gguf",
                recommendedMinRamGb = 4,
                description = "Official Nous Research agentic model with native structured tool calling and autonomous reasoning support."
            ),
            LocalModelDescriptor(
                id = MODEL_SMOLLM_1_7B,
                name = "SmolLM2 (1.7B Instruct)",
                provider = "HuggingFace",
                parameters = "1.7 Billion",
                quantization = "GGUF Q4_K_M",
                downloadSizeBytes = 1_061_048_320L, // ~1.01 GB
                downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
                recommendedMinRamGb = 4,
                description = "Ultra-compact mobile instruction model designed for extreme battery efficiency and low memory footprint."
            )
        )

        val DEFAULT_MODEL: LocalModelDescriptor
            get() = AVAILABLE_MODELS.firstOrNull { it.id == DEFAULT_MODEL_ID } ?: AVAILABLE_MODELS.first()

        fun getModelById(id: String): LocalModelDescriptor? {
            return AVAILABLE_MODELS.firstOrNull { it.id == id }
        }
    }
}

/**
 * Metadata descriptor for an installable local AI model.
 */
data class LocalModelDescriptor(
    val id: String,
    val name: String,
    val provider: String,
    val parameters: String,
    val quantization: String,
    val downloadSizeBytes: Long,
    val downloadUrl: String,
    val recommendedMinRamGb: Int,
    val description: String
) {
    val formattedSize: String
        get() {
            val mb = downloadSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1024) {
                String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.0f MB", mb)
            }
        }
}
