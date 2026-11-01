package com.jarvis.assistant.androllm.cloud.network

import com.jarvis.assistant.androllm.cloud.model.CloudChatRequest
import com.jarvis.assistant.androllm.cloud.model.CloudChatResponse
import com.jarvis.assistant.androllm.cloud.model.CloudEmbeddingRequest
import com.jarvis.assistant.androllm.cloud.model.CloudEmbeddingResponse
import com.jarvis.assistant.androllm.cloud.model.CloudModelInfoList
import com.jarvis.assistant.androllm.cloud.model.CloudModelList
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Minimal Retrofit-`Response` look-alike so [LiteLLMClient] keeps the upstream
 * control flow (`isSuccessful`, `code()`, `body()`, `errorBody()`, `headers()`).
 */
class ApiResponse<T>(
    private val code: Int,
    private val headers: Headers,
    private val body: T?,
    private val errorBodyText: String?
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun code(): Int = code
    fun headers(): Headers = headers
    fun body(): T? = body
    fun errorBody(): ErrorBody? = errorBodyText?.let { ErrorBody(it) }

    class ErrorBody(private val text: String) {
        fun string(): String = text
    }
}

/**
 * OkHttp surface over the LiteLLM proxy's OpenAI-compatible REST API.
 *
 * Ported from AndroLLM's Retrofit interface (Jarvis ships no Retrofit). All
 * endpoints take fully-qualified URLs so one instance serves any number of
 * providers. Header values (auth) are resolved per call and never logged.
 */
class LiteLLMApi(
    private val client: OkHttpClient,
    private val json: Json
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * `POST /v1/chat/completions` with SSE streaming. On success the returned
     * body is the live, unread [ResponseBody]; the caller owns and must close it.
     */
    suspend fun streamChat(
        url: String,
        headers: Map<String, String>,
        request: CloudChatRequest
    ): ApiResponse<ResponseBody> {
        val response = execute(post(url, headers, encode(CloudChatRequest.serializer(), request)))
        return if (response.isSuccessful) {
            ApiResponse(response.code, response.headers, response.body, null)
        } else {
            val error = response.body?.use { runCatching { it.string() }.getOrNull() }
            response.close()
            ApiResponse(response.code, response.headers, null, error)
        }
    }

    /** `POST /v1/chat/completions` (non-streaming). */
    suspend fun chat(
        url: String,
        headers: Map<String, String>,
        request: CloudChatRequest
    ): ApiResponse<CloudChatResponse> =
        decoded(post(url, headers, encode(CloudChatRequest.serializer(), request)), CloudChatResponse.serializer())

    /** `POST /v1/embeddings`. */
    suspend fun embeddings(
        url: String,
        headers: Map<String, String>,
        request: CloudEmbeddingRequest
    ): ApiResponse<CloudEmbeddingResponse> =
        decoded(post(url, headers, encode(CloudEmbeddingRequest.serializer(), request)), CloudEmbeddingResponse.serializer())

    /** `GET /v1/models` — model discovery. */
    suspend fun listModels(
        url: String,
        headers: Map<String, String>
    ): ApiResponse<CloudModelList> = decoded(get(url, headers), CloudModelList.serializer())

    /** `GET /v1/model/info` — richer model metadata (context window, ...). */
    suspend fun modelInfo(
        url: String,
        headers: Map<String, String>
    ): ApiResponse<CloudModelInfoList> = decoded(get(url, headers), CloudModelInfoList.serializer())

    /** Generic probe for `/health/liveliness` and `/health/readiness` (body discarded). */
    suspend fun probe(
        url: String,
        headers: Map<String, String>
    ): ApiResponse<Unit> {
        val response = execute(get(url, headers))
        response.close()
        return ApiResponse(response.code, response.headers, Unit, null)
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun post(url: String, headers: Map<String, String>, body: String): Request =
        Request.Builder().url(url).headers(toHeaders(headers)).post(body.toRequestBody(jsonMedia)).build()

    private fun get(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).headers(toHeaders(headers)).get().build()

    private fun toHeaders(map: Map<String, String>): Headers {
        val builder = Headers.Builder()
        map.forEach { (k, v) -> builder.set(k, v) }
        return builder.build()
    }

    private suspend fun <T> decoded(request: Request, serializer: KSerializer<T>): ApiResponse<T> {
        val response = execute(request)
        return response.use { r ->
            val text = withContext(Dispatchers.IO) { r.body?.string() }
            if (r.isSuccessful) {
                val parsed = text?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(serializer, it) }
                ApiResponse(r.code, r.headers, parsed, null)
            } else {
                ApiResponse(r.code, r.headers, null, text)
            }
        }
    }

    /** Cancellable async OkHttp execution (cancelling the coroutine cancels the call). */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
