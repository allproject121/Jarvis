package com.jarvis.assistant.ui.compose.navigation

/**
 * Route names for the AndroLLM-style Compose UI hosted by AndroLlmFragment (a tab inside MainActivity).
 * Every feature screen exposes `@Composable fun <Name>Route(navigate: (String) -> Unit, back: () -> Unit)`.
 */
object ComposeRoutes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val SETUP = "setup"
    const val HOME = "home"
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val VOICE = "voice"
    const val PROMPTS = "prompts"
    const val DEVELOPER = "developer"
    const val CLOUD = "cloud"
    const val PROFILE = "profile"
    const val TOOL_DEBUG = "tool_debug"

    /** Not a Compose destination: opens Jarvis' classic HUD (orb, companion, God's Eye, updates). */
    const val CLASSIC_HUD = "classic_hud"
}
