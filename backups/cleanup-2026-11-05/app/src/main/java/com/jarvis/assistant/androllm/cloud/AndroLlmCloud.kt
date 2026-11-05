package com.jarvis.assistant.androllm.cloud

import android.content.Context
import com.jarvis.assistant.ai.cloud.CloudUsageMeter
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.cloud.cache.PromptCache
import com.jarvis.assistant.androllm.cloud.network.LiteLLMClient
import com.jarvis.assistant.androllm.cloud.pipeline.CloudRequestPlanner
import com.jarvis.assistant.androllm.cloud.pipeline.CloudResultObserver
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
 * - Usage accounting is the single shared [CloudUsageMeter]
 *   (`ai.cloud.CloudUsageMeter.getInstance`): every gateway request is
 *   recorded exactly once and feeds both the persistent per-provider
 *   analytics on the Cloud screen and Jarvis' in-session token/cost counters.
 */
class AndroLlmCloud private constructor(context: Context) {

    private val appContext = context.applicationContext

    val client: LiteLLMClient = LiteLLMClient()
    val settingsStore: CloudSettingsStore = CloudSettingsStore(appContext)
    val providerManager: ProviderManager =
        ProviderManager(settingsStore, client, AndroidKeyCipher.getInstance(appContext))
    val usageMeter: CloudUsageMeter = CloudUsageMeter.getInstance(appContext)
    val promptCache: PromptCache =
        PromptCache(diskFile = File(appContext.filesDir, "androllm/cloud/prompt-cache.json"))
    val requestPlanner: CloudRequestPlanner = CloudRequestPlanner(promptCache)
    val resultObserver: CloudResultObserver = CloudResultObserver()
    val gateway: CloudGateway =
        CloudGateway(client, providerManager, usageMeter, requestPlanner, resultObserver)
    val healthMonitor: ProviderHealthMonitor = ProviderHealthMonitor(providerManager, client)

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
