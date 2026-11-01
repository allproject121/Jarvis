package com.jarvis.assistant.voice.normalize

/**
 * Common interface for text normalization stages.
 */
interface TextProcessor {
    fun process(input: String): String
}
