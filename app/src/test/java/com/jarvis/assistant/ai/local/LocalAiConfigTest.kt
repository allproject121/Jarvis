package com.jarvis.assistant.ai.local

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying [LocalAiConfig] model metadata and configurations.
 */
class LocalAiConfigTest {

    @Test
    fun testAllModelsHaveValidUrlsAndNames() {
        val models = LocalAiConfig.AVAILABLE_MODELS
        assertTrue("At least 3 models must be configured", models.size >= 3)

        for (m in models) {
            assertTrue("Model ID must not be blank", m.id.isNotBlank())
            assertTrue("Model name must not be blank", m.name.isNotBlank())
            assertTrue("Model downloadUrl must start with https://", m.downloadUrl.startsWith("https://"))
            assertTrue("Model downloadUrl must end with .gguf", m.downloadUrl.endsWith(".gguf"))
            assertTrue("Model size must be positive", m.downloadSizeBytes > 100_000_000L)
            assertTrue("Quantization must be specified", m.quantization.isNotBlank())
            assertTrue("Description must be non-empty", m.description.isNotBlank())
        }
    }

    @Test
    fun testDefaultModelExistsInList() {
        val defaultModel = LocalAiConfig.DEFAULT_MODEL
        val found = LocalAiConfig.AVAILABLE_MODELS.any { it.id == defaultModel.id }
        assertTrue("Default model must exist in AVAILABLE_MODELS", found)
    }

    @Test
    fun testGetModelById() {
        val qwen = LocalAiConfig.getModelById(LocalAiConfig.MODEL_QWEN_1_5B)
        assertNotNull(qwen)
        assertTrue(qwen?.downloadUrl?.contains("qwen") == true)

        val nonExistent = LocalAiConfig.getModelById("invalid-id-xyz")
        assertNull(nonExistent)
    }
}
