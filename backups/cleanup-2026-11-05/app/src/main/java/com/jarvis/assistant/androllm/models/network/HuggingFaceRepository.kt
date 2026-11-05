package com.jarvis.assistant.androllm.models.network

import com.jarvis.assistant.androllm.models.ModelArtifactFormat
import com.jarvis.assistant.androllm.models.ModelResult
import com.jarvis.assistant.androllm.models.RemoteModelDetails
import com.jarvis.assistant.androllm.models.RemoteModelFile
import com.jarvis.assistant.androllm.models.RemoteModelSummary
import com.jarvis.assistant.androllm.models.RemoteSearchFormat
import com.jarvis.assistant.androllm.models.RepositoryFilter
import com.jarvis.assistant.androllm.models.modelRunCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Hugging Face repository provider over [HuggingFaceApi].
 *
 * Jarvis extension of the AndroLLM provider: searches either LiteRT-LM repositories
 * (AndroLLM behaviour, for the LiteRT engine) or GGUF repositories (for Jarvis' llama.cpp
 * engine), and reports each file's real size / SHA-256 from the Hub's blob metadata
 * instead of estimating it.
 */
class HuggingFaceRepository(
    private val api: HuggingFaceApi
) : ModelRepositoryProvider {

    override val providerId: String = "huggingface"
    override val providerName: String = "Hugging Face Hub"

    override fun searchModels(filter: RepositoryFilter): Flow<ModelResult<List<RemoteModelSummary>>> = flow {
        val result = modelRunCatching {
            val dtos = api.searchModels(
                query = filter.searchQuery,
                sort = filter.sortBy.name.lowercase(),
                limit = 30,
                filter = filter.format.hfFilter
            )
            val summaries = dtos.map { dto -> dto.toSummary(filter.format) }
            when (filter.format) {
                // Defense in depth: only surface LiteRT artifacts. A repo can be tagged
                // litert-lm yet ship only GGUF — a download would fail header validation.
                RemoteSearchFormat.LITERT -> summaries.filter { it.isLiteRtArtifact }
                RemoteSearchFormat.GGUF -> summaries.filter { s -> s.tags.any { it.equals("gguf", ignoreCase = true) } }
            }
        }
        emit(result)
    }

    override fun getModelDetails(modelId: String): Flow<ModelResult<RemoteModelDetails>> = flow {
        val result = modelRunCatching {
            val dto = api.getModelDetails(modelId)
            val parts = dto.id.split("/")
            val author = dto.author ?: parts.firstOrNull() ?: "Unknown"
            val name = parts.lastOrNull() ?: dto.id

            RemoteModelDetails(
                id = dto.id,
                name = name,
                author = author,
                description = "Hugging Face repository $author/$name",
                downloads = dto.downloads,
                likes = dto.likes,
                tags = dto.tags,
                license = extractLicense(dto.tags),
                lastModified = dto.lastModified,
                repositoryUrl = "https://huggingface.co/${dto.id}",
                isGated = dto.isGated,
                files = parseFiles(dto)
            )
        }
        emit(result)
    }

    override fun getReadme(modelId: String): Flow<ModelResult<String>> = flow {
        emit(modelRunCatching { api.getReadmeText(modelId) })
    }

    private fun HfModelDto.toSummary(format: RemoteSearchFormat): RemoteModelSummary {
        val parts = id.split("/")
        val authorName = author ?: parts.firstOrNull() ?: "Hugging Face"
        val modelName = parts.lastOrNull() ?: id
        return RemoteModelSummary(
            id = id,
            name = modelName,
            author = authorName,
            description = when (format) {
                RemoteSearchFormat.GGUF -> "GGUF model by $authorName"
                RemoteSearchFormat.LITERT -> "LiteRT model by $authorName"
            },
            downloads = downloads,
            likes = likes,
            tags = tags,
            lastModified = lastModified,
            pipelineTag = pipelineTag,
            family = extractFamily(id, tags, pipelineTag),
            isGgufAvailable = tags.any { it.equals("gguf", ignoreCase = true) },
            isGated = isGated
        )
    }

    private fun parseFiles(dto: HfModelDto): List<RemoteModelFile> {
        val siblings = dto.siblings ?: return emptyList()
        return siblings.mapNotNull { sibling ->
            val filename = sibling.rfilename
            val format = ModelArtifactFormat.fromFileName(filename) ?: return@mapNotNull null
            if (format == ModelArtifactFormat.GGUF) {
                val base = filename.substringAfterLast('/').lowercase()
                // Split shards and multimodal projectors cannot be loaded as a single model file.
                if (SPLIT_SHARD.containsMatchIn(base) || base.startsWith("mmproj")) return@mapNotNull null
            }
            val downloadUrl = "https://huggingface.co".toHttpUrl().newBuilder()
                .addPathSegments(dto.id)
                .addPathSegment("resolve")
                .addPathSegment("main")
                .addPathSegments(filename)
                .build().toString()
            RemoteModelFile(
                filename = filename,
                downloadUrl = downloadUrl,
                format = format,
                sizeBytes = sibling.lfs?.size?.takeIf { it > 0 } ?: sibling.size ?: 0L,
                quantization = if (format == ModelArtifactFormat.GGUF) {
                    extractQuantization(filename)
                } else {
                    extractLiteRtQuantization(filename)
                },
                sha256 = sibling.lfs?.sha256?.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }?.lowercase()
            )
        }.sortedBy { it.sizeBytes }
    }

    /**
     * Best-effort family extraction for catalog display only — the runtime never uses it
     * to decide how to load or talk to a model.
     */
    private fun extractFamily(id: String, tags: List<String>, pipelineTag: String?): String {
        val lowerId = id.lowercase()
        val familyMatch = when {
            lowerId.contains("gemma") || tags.any { it.equals("gemma", ignoreCase = true) } -> "Gemma"
            lowerId.contains("llama") || tags.any { it.equals("llama", ignoreCase = true) } -> "Llama"
            lowerId.contains("mistral") || tags.any { it.equals("mistral", ignoreCase = true) } -> "Mistral"
            lowerId.contains("qwen") || tags.any { it.equals("qwen", ignoreCase = true) } -> "Qwen"
            lowerId.contains("phi") || tags.any { it.equals("phi", ignoreCase = true) } -> "Phi"
            lowerId.contains("deepseek") -> "DeepSeek"
            lowerId.contains("smollm") -> "SmolLM"
            else -> null
        }
        if (familyMatch != null) return familyMatch

        val namespace = id.split("/").firstOrNull()?.takeIf { it.isNotBlank() }
        val pipeline = pipelineTag?.takeIf { it.isNotBlank() }
        val archTag = tags.firstOrNull { it.startsWith("model_type:") }
            ?.removePrefix("model_type:")
            ?.takeIf { it.isNotBlank() }
        return when {
            archTag != null -> archTag.replaceFirstChar { it.uppercase() }
            pipeline != null -> pipeline.replace("-", " ").replaceFirstChar { it.uppercase() }
            namespace != null -> namespace.replaceFirstChar { it.uppercase() }
            else -> "Other"
        }
    }

    private fun extractLicense(tags: List<String>): String {
        return tags.find { it.startsWith("license:") }?.removePrefix("license:") ?: "Unknown"
    }

    /** GGUF quantization from the file name, longest token first; "" when not stated. */
    private fun extractQuantization(filename: String): String {
        val upper = filename.uppercase()
        return GGUF_QUANTS.firstOrNull { upper.contains(it) } ?: ""
    }

    private fun extractLiteRtQuantization(filename: String): String {
        val upper = filename.uppercase()
        return when {
            "MIXED_INT4" in upper || "MIXED" in upper -> "MIXED"
            "INT4" in upper -> "INT4"
            "INT8" in upper -> "INT8"
            "_Q8" in upper || "-Q8" in upper -> "Q8"
            "_Q4" in upper || "-Q4" in upper -> "Q4"
            "F32" in upper || "FP32" in upper -> "FP32"
            "F16" in upper || "FP16" in upper -> "F16"
            else -> ""
        }
    }

    private companion object {
        val SPLIT_SHARD = Regex("-\\d{5}-of-\\d{5}\\.gguf$")

        /** Ordered so that more specific labels win (Q4_K_M before Q4_K before Q4_0 …). */
        val GGUF_QUANTS = listOf(
            "IQ2_XXS", "IQ3_XXS", "IQ2_XS", "IQ3_XS", "IQ4_XS", "IQ4_NL", "IQ1_S", "IQ1_M",
            "IQ2_S", "IQ2_M", "IQ3_S", "IQ3_M",
            "Q2_K_S", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_K_S", "Q4_K_M", "Q5_K_S", "Q5_K_M",
            "Q2_K", "Q3_K", "Q4_K", "Q5_K", "Q6_K",
            "Q4_0", "Q4_1", "Q5_0", "Q5_1", "Q8_0",
            "TQ1_0", "TQ2_0", "MXFP4", "BF16", "F16", "F32"
        )
    }
}
