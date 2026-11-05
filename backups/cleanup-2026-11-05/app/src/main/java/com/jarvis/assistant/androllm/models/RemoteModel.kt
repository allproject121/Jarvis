package com.jarvis.assistant.androllm.models

/**
 * Artifact formats Jarvis can install: GGUF runs on Jarvis' llama.cpp engine
 * ([com.jarvis.assistant.ai.local.LocalModelManager]); LiteRT artifacts go to
 * [com.jarvis.assistant.androllm.AndroLlmPaths.liteRtModelsDir] for the LiteRT-LM engine.
 */
enum class ModelArtifactFormat(val label: String, val extension: String) {
    GGUF("GGUF · llama.cpp", ".gguf"),
    LITERTLM("LiteRT-LM", ".litertlm"),
    TFLITE("TFLite", ".tflite"),
    TASK("MediaPipe Task", ".task");

    val isLiteRt: Boolean get() = this != GGUF

    companion object {
        fun fromFileName(name: String): ModelArtifactFormat? {
            val lower = name.lowercase()
            return entries.firstOrNull { lower.endsWith(it.extension) }
        }
    }
}

/** Which artifact family a Hugging Face search targets. */
enum class RemoteSearchFormat(val label: String, val hfFilter: String) {
    GGUF("GGUF (llama.cpp)", "gguf"),
    LITERT("LiteRT-LM", "litertlm")
}

/**
 * Summary of a model hosted on a remote repository (e.g. Hugging Face).
 */
data class RemoteModelSummary(
    val id: String,
    val name: String,
    val author: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val lastModified: String = "",
    val pipelineTag: String = "text-generation",
    val family: String = "Unknown",
    val isGgufAvailable: Boolean = true,
    val isGated: Boolean = false
) {
    /**
     * True when the repo carries a LiteRT artifact (the litert-lm/litertlm tags).
     */
    val isLiteRtArtifact: Boolean
        get() = tags.any {
            it.equals("litert-lm", ignoreCase = true) ||
                it.equals("litertlm", ignoreCase = true) ||
                it.equals("litert", ignoreCase = true)
        }
}

/**
 * Detailed metadata of a remote model including file variants and license.
 */
data class RemoteModelDetails(
    val id: String,
    val name: String,
    val author: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val license: String = "Unknown",
    val lastModified: String = "",
    val repositoryUrl: String = "",
    val isGated: Boolean = false,
    val files: List<RemoteModelFile> = emptyList()
) {
    val ggufFiles: List<RemoteModelFile> get() = files.filter { it.format == ModelArtifactFormat.GGUF }
    val liteRtFiles: List<RemoteModelFile> get() = files.filter { it.format.isLiteRt }
}

/**
 * A specific downloadable model file from a remote repository. [sizeBytes] and [sha256]
 * come from the Hub's blob metadata (LFS pointer) — 0 / null when the Hub does not report them.
 */
data class RemoteModelFile(
    val filename: String,
    val downloadUrl: String,
    val format: ModelArtifactFormat,
    val sizeBytes: Long = 0,
    val quantization: String = "",
    val sha256: String? = null
)

/**
 * Filter parameters for searching remote model repositories.
 */
data class RepositoryFilter(
    val searchQuery: String = "",
    val format: RemoteSearchFormat = RemoteSearchFormat.GGUF,
    val family: String? = null,
    val quantization: String? = null,
    val sortBy: SortBy = SortBy.DOWNLOADS,
    val page: Int = 1
)

enum class SortBy(val label: String) {
    DOWNLOADS("Downloads"),
    LIKES("Likes"),
    LAST_MODIFIED("Recently updated")
}
