package com.jarvis.assistant.androllm.memory.intelligence

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.AiTextSynthesizer
import com.jarvis.assistant.ai.local.LocalAiEngine
import com.jarvis.assistant.ai.local.LocalEngineState
import com.jarvis.assistant.ai.provider.LocalPhoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A quiet, single-shot, TOOL-FREE text generation call used by the memory
 * pipeline (extraction + summarization). The chat UI never sees it.
 *
 * Returns null when the backend is unavailable or produced nothing.
 */
interface LlmTextBackend {
    val name: String
    fun isAvailable(): Boolean
    suspend fun generate(systemPrompt: String, userContent: String, maxTokens: Int, temperature: Float): String?
}

/**
 * "Cloud" backing: Jarvis' existing quiet text-routing cascade
 * ([AiTextSynthesizer]: Gemini REST -> OpenAI -> LocalHost/Ollama), the same
 * REST path [com.jarvis.assistant.ai.router.ModelRouter] uses for Gemini text.
 *
 * Never touches the Gemini Live websocket and never executes tools.
 */
class CloudTextBackend(private val context: Context) : LlmTextBackend {
    override val name: String = "cloud"

    override fun isAvailable(): Boolean {
        val prefs = (context.applicationContext as? JarvisApp)?.preferences ?: return false
        val hasProvider = prefs.apiKey.isNotBlank() || prefs.openAiApiKey.isNotBlank() || prefs.customEndpoint.isNotBlank()
        if (!hasProvider) return false
        // A configured LocalHost endpoint can be on the LAN/adb reverse, so only
        // require validated internet when no LocalHost endpoint is configured.
        return prefs.customEndpoint.isNotBlank() || hasInternet()
    }

    override suspend fun generate(systemPrompt: String, userContent: String, maxTokens: Int, temperature: Float): String? {
        if (!isAvailable()) return null
        return try {
            AiTextSynthesizer.synthesizeText(
                context = context,
                systemInstruction = systemPrompt,
                prompt = userContent,
                temperature = temperature.toDouble(),
                maxTokens = maxTokens
            ) { "" }.trim().ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud memory call failed: ${e.message}")
            null
        }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "AndroMemCloud"
    }
}

/**
 * "Local" backing: the on-device llama.cpp GGUF model held by Jarvis'
 * [LocalPhoneProvider], via its tool-free [LocalPhoneProvider.generateQuiet].
 *
 * Only runs when the model is ALREADY loaded (never loads weights in the
 * background) and when the local engine is not busy answering the user.
 */
class LocalTextBackend(private val context: Context) : LlmTextBackend {
    override val name: String = "local"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provider by lazy { LocalPhoneProvider.getInstance(context, scope) }
    private val engine by lazy { LocalAiEngine.getInstance(context, scope) }

    override fun isAvailable(): Boolean =
        provider.isReady() && engine.engineState.value !is LocalEngineState.Generating

    override suspend fun generate(systemPrompt: String, userContent: String, maxTokens: Int, temperature: Float): String? {
        if (!isAvailable()) return null
        return try {
            provider.generateQuiet(
                prompt = userContent,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature
            )?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "Local memory call failed: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AndroMemLocal"
    }
}
