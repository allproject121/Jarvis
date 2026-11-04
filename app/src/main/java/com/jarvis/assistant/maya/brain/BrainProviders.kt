package com.jarvis.assistant.maya.brain

/**
 * Wire protocol a brain provider speaks.
 */
enum class BrainApiStyle {
    /** `POST {baseUrl}/chat/completions` with OpenAI-style `tools` / `tool_calls`. */
    OPENAI,

    /** `POST {baseUrl}/messages` with Anthropic `tools` / `tool_use` / `tool_result` blocks. */
    ANTHROPIC
}

/**
 * One Advanced-Agentic brain preset (Maya "Voice engine → Brain" provider list).
 *
 * [baseUrl] never ends with a slash and already includes the version segment, so
 * the chat endpoint is `"$baseUrl/chat/completions"` (or `"$baseUrl/messages"` for
 * Anthropic) and the model list is [modelsEndpoint].
 */
data class BrainProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyHint: String,
    val defaultModel: String,
    val modelsEndpoint: String,
    val apiStyle: BrainApiStyle = BrainApiStyle.OPENAI,
    /** Vision-capable default for the eyes role (empty = same as [defaultModel]). */
    val defaultVisionModel: String = "",
    /** Extra headers the provider likes (OpenRouter attribution). */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** True when the user must type the base URL themselves. */
    val customBaseUrl: Boolean = false
) {
    fun chatUrl(resolvedBaseUrl: String = baseUrl): String {
        val base = resolvedBaseUrl.trimEnd('/')
        return when (apiStyle) {
            BrainApiStyle.OPENAI -> "$base/chat/completions"
            BrainApiStyle.ANTHROPIC -> "$base/messages"
        }
    }

    fun modelsUrl(resolvedBaseUrl: String = baseUrl): String {
        if (modelsEndpoint.isNotBlank() && !customBaseUrl) return modelsEndpoint
        return "${resolvedBaseUrl.trimEnd('/')}/models"
    }
}

/**
 * Provider presets for the Advanced Agentic brain (ported from Maya's Voice engine screen).
 */
object BrainProviders {

    const val GEMINI_OPENAI = "gemini-openai"
    const val OPENROUTER = "openrouter"
    const val GROQ = "groq"
    const val OPENAI = "openai"
    const val DEEPSEEK = "deepseek"
    const val MISTRAL = "mistral"
    const val TOGETHER = "together"
    const val DEEPINFRA = "deepinfra"
    const val NVIDIA = "nvidia"
    const val ANTHROPIC = "anthropic"
    const val CUSTOM = "custom"

    val OPENROUTER_HEADERS = mapOf(
        "HTTP-Referer" to "https://jarvis.mobile",
        "X-Title" to "JARVIS Mobile OS"
    )

    val ALL: List<BrainProvider> = listOf(
        BrainProvider(
            id = GEMINI_OPENAI,
            label = "Google Gemini (OpenAI-compatible)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyHint = "AIza... from aistudio.google.com",
            defaultModel = "gemini-2.5-flash",
            modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/models",
            defaultVisionModel = "gemini-2.5-flash"
        ),
        BrainProvider(
            id = OPENROUTER,
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-v1-... from openrouter.ai/keys",
            defaultModel = "openrouter/auto",
            modelsEndpoint = "https://openrouter.ai/api/v1/models",
            defaultVisionModel = "google/gemini-2.0-flash-exp:free",
            extraHeaders = OPENROUTER_HEADERS
        ),
        BrainProvider(
            id = GROQ,
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            keyHint = "gsk_... from console.groq.com",
            defaultModel = "llama-3.3-70b-versatile",
            modelsEndpoint = "https://api.groq.com/openai/v1/models",
            defaultVisionModel = "meta-llama/llama-4-scout-17b-16e-instruct"
        ),
        BrainProvider(
            id = OPENAI,
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            keyHint = "sk-... from platform.openai.com",
            defaultModel = "gpt-4o-mini",
            modelsEndpoint = "https://api.openai.com/v1/models",
            defaultVisionModel = "gpt-4o-mini"
        ),
        BrainProvider(
            id = DEEPSEEK,
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            keyHint = "sk-... from platform.deepseek.com",
            defaultModel = "deepseek-chat",
            modelsEndpoint = "https://api.deepseek.com/v1/models"
        ),
        BrainProvider(
            id = MISTRAL,
            label = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            keyHint = "from console.mistral.ai",
            defaultModel = "mistral-small-latest",
            modelsEndpoint = "https://api.mistral.ai/v1/models",
            defaultVisionModel = "pixtral-12b-latest"
        ),
        BrainProvider(
            id = TOGETHER,
            label = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            keyHint = "from api.together.ai",
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            modelsEndpoint = "https://api.together.xyz/v1/models",
            defaultVisionModel = "meta-llama/Llama-4-Scout-17B-16E-Instruct"
        ),
        BrainProvider(
            id = DEEPINFRA,
            label = "DeepInfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            keyHint = "from deepinfra.com/dash/api_keys",
            defaultModel = "meta-llama/Meta-Llama-3.1-70B-Instruct",
            modelsEndpoint = "https://api.deepinfra.com/v1/openai/models"
        ),
        BrainProvider(
            id = NVIDIA,
            label = "NVIDIA NIM",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyHint = "nvapi-... from build.nvidia.com",
            defaultModel = "meta/llama-3.1-70b-instruct",
            modelsEndpoint = "https://integrate.api.nvidia.com/v1/models"
        ),
        BrainProvider(
            id = ANTHROPIC,
            label = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1",
            keyHint = "sk-ant-... from console.anthropic.com",
            defaultModel = "claude-3-5-haiku-latest",
            modelsEndpoint = "https://api.anthropic.com/v1/models",
            apiStyle = BrainApiStyle.ANTHROPIC,
            defaultVisionModel = "claude-3-5-sonnet-latest"
        ),
        BrainProvider(
            id = CUSTOM,
            label = "Custom (OpenAI-compatible URL)",
            baseUrl = "http://127.0.0.1:11434/v1",
            keyHint = "optional, sent as Bearer token",
            defaultModel = "",
            modelsEndpoint = "",
            customBaseUrl = true
        )
    )

    fun byId(id: String?): BrainProvider? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }

    fun byIdOrDefault(id: String?): BrainProvider = byId(id) ?: ALL.first()

    fun ids(): List<String> = ALL.map { it.id }

    /** Accepts common aliases spoken by the user ("gemini", "claude", "local", ...). */
    fun resolveAlias(raw: String?): BrainProvider? {
        val v = raw?.trim()?.lowercase() ?: return null
        byId(v)?.let { return it }
        return when {
            v.startsWith("gemini") || v.startsWith("google") -> byId(GEMINI_OPENAI)
            v.startsWith("claude") || v.startsWith("anthropic") -> byId(ANTHROPIC)
            v.startsWith("open router") || v.startsWith("open-router") -> byId(OPENROUTER)
            v.startsWith("chatgpt") || v.startsWith("gpt") -> byId(OPENAI)
            v.startsWith("deep seek") -> byId(DEEPSEEK)
            v.startsWith("deep infra") -> byId(DEEPINFRA)
            v.startsWith("nim") || v.startsWith("nvidia") -> byId(NVIDIA)
            v.startsWith("ollama") || v.startsWith("local") || v.startsWith("lm studio") || v.startsWith("custom") || v.startsWith("http") -> byId(CUSTOM)
            else -> null
        }
    }
}
