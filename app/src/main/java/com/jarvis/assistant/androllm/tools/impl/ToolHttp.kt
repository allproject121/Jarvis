package com.jarvis.assistant.androllm.tools.impl

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared OkHttp client for the network tools (weather, web search, GitHub) —
 * replaces the Ktor HttpClient AndroLLM injected through Hilt.
 */
internal object ToolHttp {

    data class Response(val code: Int, val body: String)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Blocking GET on [Dispatchers.IO]; throws on network errors (callers wrap). */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                Response(resp.code, resp.body?.string().orEmpty())
            }
        }
}
