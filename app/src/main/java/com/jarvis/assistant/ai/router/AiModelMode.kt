package com.jarvis.assistant.ai.router

/**
 * Operating modes for the Unified AI Model Router.
 */
enum class AiModelMode(val displayName: String, val description: String) {
    /**
     * Google Gemini Live bidirectional WebSocket streaming.
     * Fastest real-time voice, vision grounding, and native tools.
     */
    GEMINI_LIVE("Gemini Live", "Ultra-low latency conversational streaming & multimodal tools."),

    /**
     * OpenAI ChatGPT (GPT-4o / GPT-4o-mini) via REST API.
     * Deep reasoning, complex coding, and structured task execution.
     */
    CHATGPT("ChatGPT", "High-intellect reasoning, drafting, and problem solving via OpenAI."),

    /**
     * 100% On-Device Local SLM (Gemma 2B / Qwen 1.5B).
     * Zero internet required, flight mode capable, private and instant.
     */
    LOCAL_OFFLINE("Local Offline AI", "Complete privacy and offline intelligence running on device."),

    /**
     * Local Host Server AI (Ollama on Laptop over Wi-Fi / ADB Reverse).
     * High-performance local inference powered by models like Qwen 27B / 7B.
     */
    LOCAL_HOST("Local Host AI (Ollama)", "High-performance inference running on your local Windows PC over LAN or USB."),

    /**
     * Hermes Autonomous Agent on-device.
     * Multi-step autonomous planning and execution of tool workflows.
     */
    HERMES_AGENT("Hermes Agent", "Autonomous multi-step planning and tool execution powered by Nous Hermes."),

    /**
     * Automatic Hybrid Mode (Recommended).
     * Routes queries to Gemini/ChatGPT when connected, and auto-fails over
     * to Local Host AI or Local Offline AI without interruption if internet drops or quota exhausts.
     */
    AUTO_HYBRID("Auto Hybrid (Smart Failover)", "Online intelligence with instant zero-downtime local failover.");

    companion object {
        fun fromString(name: String?): AiModelMode {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: AUTO_HYBRID
        }
    }
}
