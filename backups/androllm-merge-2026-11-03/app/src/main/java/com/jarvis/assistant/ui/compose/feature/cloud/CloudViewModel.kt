package com.jarvis.assistant.ui.compose.feature.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.cloud.CloudUsageMeter
import com.jarvis.assistant.ai.cloud.CloudUsageStats
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.ai.router.ChatGptClient
import com.jarvis.assistant.ai.router.ModelRouter
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The three cloud/remote brains Jarvis' backend actually supports. */
internal enum class CloudProviderId(val title: String, val endpointLabel: String) {
    GEMINI("Google Gemini", "generativelanguage.googleapis.com"),
    OPENAI("OpenAI (ChatGPT)", "api.openai.com"),
    LOCAL_HOST("Local Host (Ollama)", "")
}

/** Result of the most recent connection probe for a provider. */
internal data class ProviderHealth(
    val reachable: Boolean,
    val authorized: Boolean,
    val latencyMs: Long,
    val modelIds: List<String> = emptyList(),
    val error: String = ""
)

internal data class CloudUiState(
    val mode: AiModelMode = AiModelMode.AUTO_HYBRID,
    val geminiKeyMasked: String = "",
    val openAiKeyMasked: String = "",
    val geminiModel: String = "",
    val geminiModels: List<String> = emptyList(),
    val openAiModel: String = ChatGptClient.MODEL_GPT_4O_MINI,
    val localEndpoint: String = "",
    val localModel: String = "",
    val canStoreSecurely: Boolean = true,
    val preferredBrain: String = "",
    val testingId: CloudProviderId? = null,
    val health: Map<CloudProviderId, ProviderHealth> = emptyMap(),
    val lastActiveProvider: String = "",
    val offlineFallbackActive: Boolean = false
) {
    fun hasKey(id: CloudProviderId): Boolean = when (id) {
        CloudProviderId.GEMINI -> geminiKeyMasked.isNotEmpty()
        CloudProviderId.OPENAI -> openAiKeyMasked.isNotEmpty()
        CloudProviderId.LOCAL_HOST -> localEndpoint.isNotBlank()
    }
}

/**
 * Cloud brains view model, wired to Jarvis' real backend:
 *  - API keys / Gemini model / Ollama endpoint → [AppPreferences]
 *  - routing mode → [ModelRouter.setMode] + [AppPreferences.aiModelMode]
 *  - session counters → [CloudUsageMeter]
 * Connection tests are real HTTP probes of each provider's model-list endpoint.
 */
internal class CloudViewModel(application: Application) : AndroidViewModel(application) {

    private val jarvisApp: JarvisApp? = application as? JarvisApp

    private val prefs: AppPreferences =
        jarvisApp?.let { runCatching { it.preferences }.getOrNull() } ?: AppPreferences(application)

    private val router: ModelRouter? = jarvisApp?.let { runCatching { it.modelRouter }.getOrNull() }

    private val usageMeter: CloudUsageMeter = CloudUsageMeter.getInstance(application)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()

    val usageStats: StateFlow<CloudUsageStats> = usageMeter.stats

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        reload()
        router?.let { r ->
            viewModelScope.launch {
                r.currentMode.collect { mode -> _uiState.update { it.copy(mode = mode) } }
            }
            viewModelScope.launch {
                r.lastActiveProviderName.collect { name -> _uiState.update { it.copy(lastActiveProvider = name) } }
            }
            viewModelScope.launch {
                r.isOfflineFallbackActive.collect { active -> _uiState.update { it.copy(offlineFallbackActive = active) } }
            }
        }
    }

    fun reload() {
        val gemini = runCatching { prefs.apiKey }.getOrDefault("")
        val openAi = runCatching { prefs.openAiApiKey }.getOrDefault("")
        val persistedMode = AiModelMode.fromString(prefs.aiModelMode)
        _uiState.update {
            it.copy(
                // ModelRouter is in-memory and starts at AUTO_HYBRID; prefs is the persisted choice.
                mode = router?.currentMode?.value ?: persistedMode,
                geminiKeyMasked = mask(gemini),
                openAiKeyMasked = mask(openAi),
                geminiModel = prefs.aiModel,
                geminiModels = GeminiConstants.SUPPORTED_MODELS,
                localEndpoint = prefs.customEndpoint,
                localModel = prefs.localHostModel,
                canStoreSecurely = prefs.canStoreApiKeySecurely,
                preferredBrain = prefs.preferredBrain
            )
        }
    }

    // ── Routing mode ─────────────────────────────────────────────────────────

    fun setMode(mode: AiModelMode) {
        router?.setMode(mode)
        prefs.aiModelMode = mode.name
        prefs.voiceEngineType = mode.name
        _uiState.update { it.copy(mode = mode) }
        _message.value = "Engine configured: ${mode.displayName}"
    }

    // ── Keys & endpoints ─────────────────────────────────────────────────────

    fun saveGeminiKey(key: String) {
        runCatching { prefs.apiKey = key.trim() }
            .onSuccess { _message.value = if (key.isBlank()) "Gemini key removed" else "Gemini key saved" }
            .onFailure { e -> _message.value = "Could not save Gemini key: ${e.message}" }
        _uiState.update { it.copy(health = it.health - CloudProviderId.GEMINI) }
        reload()
    }

    fun saveOpenAiKey(key: String) {
        runCatching { prefs.openAiApiKey = key.trim() }
            .onSuccess { _message.value = if (key.isBlank()) "OpenAI key removed" else "OpenAI key saved" }
            .onFailure { e -> _message.value = "Could not save OpenAI key: ${e.message}" }
        _uiState.update { it.copy(health = it.health - CloudProviderId.OPENAI) }
        reload()
    }

    fun saveLocalHost(endpoint: String, model: String) {
        prefs.customEndpoint = endpoint
        if (model.isNotBlank()) prefs.localHostModel = model
        _uiState.update { it.copy(health = it.health - CloudProviderId.LOCAL_HOST) }
        _message.value = "Local host saved"
        reload()
    }

    fun setGeminiModel(model: String) {
        prefs.aiModel = model
        _message.value = "Gemini model set to ${prettyModel(model)}"
        reload()
    }

    fun setLocalHostModel(model: String) {
        prefs.localHostModel = model
        _message.value = "Local host model set to $model"
        reload()
    }

    // ── Connection tests ─────────────────────────────────────────────────────

    fun testConnection(id: CloudProviderId) {
        if (_uiState.value.testingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(testingId = id) }
            val result = withContext(Dispatchers.IO) { probe(id) }
            _uiState.update { it.copy(testingId = null, health = it.health + (id to result)) }
            _message.value = when {
                result.reachable && result.authorized ->
                    "OK — ${result.modelIds.size} models discovered (${result.latencyMs} ms)"
                result.reachable -> "Reachable, but rejected: ${result.error}"
                else -> "Connection failed: ${result.error.ifBlank { "unreachable" }}"
            }
        }
    }

    private fun probe(id: CloudProviderId): ProviderHealth {
        val request = when (id) {
            CloudProviderId.GEMINI -> {
                val key = runCatching { prefs.apiKey }.getOrDefault("")
                if (key.isBlank()) return ProviderHealth(false, false, 0, error = "No Gemini API key set")
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                    .header("x-goog-api-key", key)
                    .get()
                    .build()
            }
            CloudProviderId.OPENAI -> {
                val key = runCatching { prefs.openAiApiKey }.getOrDefault("").trim()
                if (key.isBlank()) return ProviderHealth(false, false, 0, error = "No OpenAI API key set")
                Request.Builder()
                    .url("https://api.openai.com/v1/models")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
            }
            CloudProviderId.LOCAL_HOST -> {
                val base = prefs.customEndpoint.trim().trimEnd('/')
                if (!base.startsWith("http://") && !base.startsWith("https://")) {
                    return ProviderHealth(false, false, 0, error = "Endpoint must start with http:// or https://")
                }
                Request.Builder().url("$base/api/tags").get().build()
            }
        }
        val start = System.nanoTime()
        return try {
            http.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    ProviderHealth(
                        reachable = true,
                        authorized = false,
                        latencyMs = latency,
                        error = "HTTP ${response.code}" + extractError(body)?.let { " — $it" }.orEmpty()
                    )
                } else {
                    ProviderHealth(
                        reachable = true,
                        authorized = true,
                        latencyMs = latency,
                        modelIds = parseModels(id, body)
                    )
                }
            }
        } catch (e: Exception) {
            ProviderHealth(false, false, 0, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseModels(id: CloudProviderId, body: String): List<String> = runCatching {
        val root = JSONObject(body)
        val out = mutableListOf<String>()
        when (id) {
            CloudProviderId.GEMINI -> {
                val arr = root.optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            CloudProviderId.OPENAI -> {
                val arr = root.optJSONArray("data")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            CloudProviderId.LOCAL_HOST -> {
                val arr = root.optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
        out.sorted()
    }.getOrDefault(emptyList())

    private fun extractError(body: String): String? = runCatching {
        val err = JSONObject(body).optJSONObject("error") ?: return@runCatching null
        err.optString("message").takeIf { it.isNotBlank() }?.take(140)
    }.getOrNull()

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun mask(key: String): String {
            val k = key.trim()
            if (k.isEmpty()) return ""
            if (k.length <= 8) return "••••"
            return k.take(4) + "••••" + k.takeLast(4)
        }

        fun prettyModel(model: String): String = model.removePrefix("models/")
    }
}
