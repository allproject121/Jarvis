package com.jarvis.assistant.androllm.cloud

import android.content.Context
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.cloud.cache.PromptCache
import com.jarvis.assistant.androllm.cloud.network.LiteLLMClient
import com.jarvis.assistant.androllm.cloud.pipeline.CloudRequestPlanner
import com.jarvis.assistant.androllm.cloud.pipeline.CloudResultObserver
import com.jarvis.assistant.androllm.cloud.usage.CloudUsageMeter
import com.jarvis.assistant.androllm.cloud.usage.FileCloudUsageStore
import com.jarvis.assistant.security.cipher.AndroidKeyCipher
import java.io.File

/**
 * Manual object graph for the AndroLLM LiteLLM / OpenAI-compatible cloud
 * gateway (replaces AndroLLM's Hilt `CloudModule`).
 *
 * Everything here is OPTIONAL and additive: nothing is constructed until a
 * caller asks for it, and chat routing only reaches the gateway when the user
 * selects the `CLOUD_GATEWAY` router mode AND [AndroLlmFlags.CLOUD_GATEWAY] is on.
 * Gemini Live is never touched.
 *
 * Reuse of existing Jarvis infrastructure:
 * - API keys are encrypted with Jarvis's [AndroidKeyCipher] (security/cipher).
 * - Retries use Jarvis's `ai.cloud.RetryPolicy`.
 * - Every gateway request is mirrored into Jarvis's `ai.cloud.CloudUsageMeter`
 *   so the existing token/cost counters include gateway traffic; the richer
 *   AndroLLM meter (persistent, per-provider analytics) powers the Cloud screen.
 */
class AndroLlmCloud private constructor(context: Context) {

    private val appContext = context.applicationContext

    val client: LiteLLMClient = LiteLLMClient()
    val settingsStore: CloudSettingsStore = CloudSettingsStore(appContext)
    val providerManager: ProviderManager =
        ProviderManager(settingsStore, client, AndroidKeyCipher.getInstance(appContext))
    val usageMeter: CloudUsageMeter =
        CloudUsageMeter(FileCloudUsageStore(File(appContext.filesDir, "androllm/cloud/cloud-usage.json")))
    val promptCache: PromptCache =
        PromptCache(diskFile = File(appContext.filesDir, "androllm/cloud/prompt-cache.json"))
    val requestPlanner: CloudRequestPlanner = CloudRequestPlanner(promptCache)
    val resultObserver: CloudResultObserver = CloudResultObserver()
    val gateway: CloudGateway =
        CloudGateway(client, providerManager, usageMeter, requestPlanner, resultObserver)
    val healthMonitor: ProviderHealthMonitor = ProviderHealthMonitor(providerManager, client)

    init {
        // Mirror each gateway request into Jarvis's existing usage meter.
        usageMeter.onRecorded = {
            val last = usageMeter.records(limit = 1).firstOrNull()
            if (last != null) {
                com.jarvis.assistant.ai.cloud.CloudUsageMeter.getInstance(appContext).record(
                    model = last.modelId,
                    promptTokens = last.inputTokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    completionTokens = last.outputTokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                )
            }
        }
    }

    /** Whether the user opted into gateway routing (default OFF). */
    fun isRoutingEnabled(): Boolean = AndroLlmFlags.isEnabled(appContext, AndroLlmFlags.CLOUD_GATEWAY)

    companion object {
        @Volatile
        private var instance: AndroLlmCloud? = null

        fun getInstance(context: Context): AndroLlmCloud =
            instance ?: synchronized(this) {
                instance ?: AndroLlmCloud(context.applicationContext).also { instance = it }
            }
    }
}
