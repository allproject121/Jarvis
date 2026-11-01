package com.jarvis.assistant.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ProviderHealth(
    val reachable: Boolean,
    val latencyMs: Long,
    val statusCode: Int = 0,
    val error: String? = null
)

/**
 * Proactively probes latency and health for configured AI cloud endpoints:
 * - Localhost Ollama / vLLM bridge
 * - Gemini API
 * - OpenAI API
 */
class ProviderHealthMonitor private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ProviderHealthMonitor"

        @Volatile
        private var instance: ProviderHealthMonitor? = null

        fun getInstance(context: Context, scope: CoroutineScope): ProviderHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: ProviderHealthMonitor(context.applicationContext, scope).also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _healthMap = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())
    val healthMap: StateFlow<Map<String, ProviderHealth>> = _healthMap.asStateFlow()

    private var probeJob: Job? = null

    fun start(intervalMinutes: Int = 2) {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                probeAll()
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
    }

    suspend fun probeAll(): Map<String, ProviderHealth> {
        val results = mutableMapOf<String, ProviderHealth>()

        // 1. Localhost Ollama Probe
        results["ollama"] = probeEndpoint("http://127.0.0.1:11434/api/tags")

        // 2. OpenAI Probe
        results["openai"] = probeEndpoint("https://api.openai.com/v1/models")

        // 3. Google Gemini Probe
        results["gemini"] = probeEndpoint("https://generativelanguage.googleapis.com")

        _healthMap.value = results
        return results
    }

    private fun probeEndpoint(url: String): ProviderHealth {
        val start = System.currentTimeMillis()
        return try {
            val request = Request.Builder().url(url).head().build()
            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                ProviderHealth(
                    reachable = true,
                    latencyMs = latency,
                    statusCode = response.code
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            ProviderHealth(
                reachable = false,
                latencyMs = latency,
                error = e.message
            )
        }
    }
}
