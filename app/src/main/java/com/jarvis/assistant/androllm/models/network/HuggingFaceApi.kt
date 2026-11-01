package com.jarvis.assistant.androllm.models.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared OkHttp client + JSON config for the model catalog / Hub layer (replaces AndroLLM's Ktor client). */
object ModelsHttp {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    const val USER_AGENT = "Jarvis-AndroLLM/1.0 (Android)"

    /** GET [url] and return the body text; throws [IOException] on non-2xx. */
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Empty response body for $url")
        }
    }
}

@Serializable
data class HfModelDto(
    val id: String = "",
    val author: String? = null,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    @SerialName("lastModified")
    val lastModified: String = "",
    @SerialName("pipeline_tag")
    val pipelineTag: String = "text-generation",
    /** `false`, `"auto"` or `"manual"` on the Hub — any non-false value means gated. */
    val gated: JsonElement? = null,
    val siblings: List<HfSiblingDto>? = null
) {
    val isGated: Boolean
        get() {
            val primitive = gated as? JsonPrimitive ?: return false
            primitive.booleanOrNull?.let { return it }
            return primitive.content.isNotBlank() && !primitive.content.equals("false", ignoreCase = true)
        }
}

@Serializable
data class HfLfsDto(
    val sha256: String? = null,
    val size: Long = 0
)

@Serializable
data class HfSiblingDto(
    val rfilename: String = "",
    /** Present when the request asks for `blobs=true`. */
    val size: Long? = null,
    val lfs: HfLfsDto? = null
)

/**
 * Direct REST API client for the official Hugging Face Hub endpoints (OkHttp port of
 * AndroLLM's Ktor client). Process singleton.
 */
class HuggingFaceApi(private val http: ModelsHttp = ModelsHttp) {

    companion object {
        private const val BASE_URL = "https://huggingface.co"
    }

    /**
     * Searches Hugging Face models. [filter] is the Hub library/tag filter: `litertlm` keeps the
     * list to LiteRT-LM repositories (AndroLLM default), `gguf` lists llama.cpp GGUF repositories
     * for Jarvis' native engine. An empty query with the litertlm filter returns the official
     * litert-community models.
     */
    suspend fun searchModels(
        query: String = "",
        sort: String = "downloads",
        limit: Int = 30,
        filter: String = "litertlm"
    ): List<HfModelDto> {
        val sortParam = when (sort.lowercase()) {
            "likes" -> "likes"
            "last_modified" -> "lastModified"
            else -> "downloads"
        }
        val search = query.trim().ifEmpty { if (filter == "litertlm") "litertlm" else "" }
        val url = "$BASE_URL/api/models".toHttpUrl().newBuilder().apply {
            if (search.isNotEmpty()) addQueryParameter("search", search)
            addQueryParameter("filter", filter)
            addQueryParameter("sort", sortParam)
            addQueryParameter("direction", "-1")
            addQueryParameter("limit", limit.toString())
        }.build().toString()
        val body = http.getText(url)
        return http.json.decodeFromString(ListSerializer(HfModelDto.serializer()), body)
    }

    /**
     * Fetches metadata and the file list for [modelId]. `blobs=true` makes the Hub report each
     * file's real size and LFS SHA-256, so downloads can be size- and hash-verified.
     */
    suspend fun getModelDetails(modelId: String): HfModelDto {
        val url = "$BASE_URL/api/models".toHttpUrl().newBuilder()
            .addPathSegments(modelId)
            .addQueryParameter("blobs", "true")
            .build().toString()
        val body = http.getText(url)
        return http.json.decodeFromString(HfModelDto.serializer(), body)
    }

    /**
     * Fetches raw README.md markdown text for [modelId].
     */
    suspend fun getReadmeText(modelId: String): String {
        val url = "$BASE_URL/$modelId/raw/main/README.md"
        return http.getText(url)
    }
}
