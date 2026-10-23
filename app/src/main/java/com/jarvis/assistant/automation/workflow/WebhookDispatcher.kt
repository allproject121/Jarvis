package com.jarvis.assistant.automation.workflow

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Generic Webhook Trigger Dispatcher.
 * Dispatches HTTP POST/GET webhooks to external automation systems (n8n, Zapier, Make, custom servers).
 */
object WebhookDispatcher {

    private const val TAG = "WebhookDispatcher"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class WebhookResult(
        val success: Boolean,
        val statusCode: Int,
        val responseBody: String,
        val elapsedMs: Long,
        val message: String
    )

    suspend fun dispatch(
        url: String,
        method: String = "POST",
        payload: String? = null,
        headers: Map<String, String>? = null
    ): WebhookResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val reqBuilder = Request.Builder().url(url)

            headers?.forEach { (k, v) ->
                reqBuilder.addHeader(k, v)
            }

            if (method.equals("POST", ignoreCase = true)) {
                val bodyStr = payload ?: "{}"
                reqBuilder.post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
            } else if (method.equals("PUT", ignoreCase = true)) {
                val bodyStr = payload ?: "{}"
                reqBuilder.put(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
            } else {
                reqBuilder.get()
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val elapsed = System.currentTimeMillis() - startTime
            val code = response.code
            val body = response.body?.string()?.take(500) ?: ""
            val isSuccess = response.isSuccessful

            Log.i(TAG, "Webhook dispatched to $url -> HTTP $code in ${elapsed}ms")
            WebhookResult(
                success = isSuccess,
                statusCode = code,
                responseBody = body,
                elapsedMs = elapsed,
                message = if (isSuccess) "Webhook dispatched successfully (HTTP $code)" else "Webhook failed with HTTP $code: $body"
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Failed dispatching webhook to $url: ${e.message}", e)
            WebhookResult(
                success = false,
                statusCode = 0,
                responseBody = "",
                elapsedMs = elapsed,
                message = "Webhook error: ${e.message}"
            )
        }
    }
}
