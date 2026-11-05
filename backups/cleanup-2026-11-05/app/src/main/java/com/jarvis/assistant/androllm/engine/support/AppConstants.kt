package com.jarvis.assistant.androllm.engine.support

/** Subset of AndroLLM core/common AppConstants used by the ported engine. */
object AppConstants {
    object Network {
        const val CONNECT_TIMEOUT = 30_000L
        const val READ_TIMEOUT = 60_000L
        const val WRITE_TIMEOUT = 60_000L
        const val MAX_RETRIES = 3
    }

    object Model {
        const val MIN_RAM_GB = 4
        const val RECOMMENDED_RAM_GB = 8
        const val DEFAULT_CONTEXT_LENGTH = 4096
        const val MAX_CONTEXT_LENGTH = 32768
    }
}
