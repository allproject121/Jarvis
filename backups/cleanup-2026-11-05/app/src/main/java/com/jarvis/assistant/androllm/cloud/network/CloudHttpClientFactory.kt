package com.jarvis.assistant.androllm.cloud.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Builds the OkHttp stack used for every LiteLLM request.
 *
 * - HTTP/2 with HTTP/1.1 fallback
 * - Connection pooling (reused connections, minimal battery impact)
 * - Transparent gzip via OkHttp's built-in compression
 * - `retryOnConnectionFailure` for transport-level retries (HTTP 429/5xx
 *   retries live in [LiteLLMClient] with exponential backoff)
 * - No logging interceptors — API keys must never be written to logs
 */
object CloudHttpClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Volatile
    private var sharedClient: OkHttpClient? = null

    /** Shared OkHttp client (created once, reused by all requests). */
    fun okHttpClient(
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS
    ): OkHttpClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: createOkHttpClient(connectTimeoutMs, streamTimeoutMs).also { sharedClient = it }
        }
    }

    /** A fresh OkHttp client with the tuned transport settings. */
    fun createOkHttpClient(
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        streamTimeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(streamTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(streamTimeoutMs, TimeUnit.MILLISECONDS)
        // No callTimeout: it caps the ENTIRE call including body reads, which
        // would truncate long-running SSE streams (thinking models, long
        // answers). The read timeout below already reaps dead connections.
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectionPool(
            ConnectionPool(maxIdleConnections = MAX_IDLE_CONNECTIONS, keepAliveDuration = KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
        )
        .build()

    /**
     * Builds the OkHttp-backed API surface. Endpoints use fully-qualified URLs,
     * so one instance serves every provider.
     */
    fun createApi(client: OkHttpClient = okHttpClient()): LiteLLMApi = LiteLLMApi(client, json)

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    private const val DEFAULT_STREAM_TIMEOUT_MS = 120_000L
    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_MINUTES = 30L
}
