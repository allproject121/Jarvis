package com.jarvis.assistant.automation.command

sealed class JarvisCommand {
    // Existing Core Commands
    data class PlayYouTube(val query: String) : JarvisCommand()
    data class SearchYouTube(val query: String) : JarvisCommand()
    data class OpenApp(val packageName: String) : JarvisCommand()
    data class WhatsAppMessage(val contact: String, val message: String) : JarvisCommand()
    data class WhatsAppCall(val contact: String, val video: Boolean) : JarvisCommand()
    data class WhatsAppDeleteMessage(val contact: String?, val forEveryone: Boolean) : JarvisCommand()
    object SubscribeChannel : JarvisCommand()
    object ToggleFullscreen : JarvisCommand()
    data class Brightness(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Volume(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Flash(val enabled: Boolean) : JarvisCommand()
    data class Remember(val text: String) : JarvisCommand()
    data class Forget(val query: String) : JarvisCommand()
    object WhatDoYouRemember : JarvisCommand()
    data class SearchWeb(val query: String) : JarvisCommand()
    data class OpenWebUrls(val urls: List<String>) : JarvisCommand()
    data class PhoneCall(val contactOrNumber: String) : JarvisCommand()
    object PauseMedia : JarvisCommand()
    object ResumeMedia : JarvisCommand()
    object NextMedia : JarvisCommand()
    object PreviousMedia : JarvisCommand()
    data class ConfirmAction(val confirmed: Boolean) : JarvisCommand()
    data class DisambiguationChoice(val choice: String) : JarvisCommand()
    object RecallBirthday : JarvisCommand()
    object RecallFavoriteSong : JarvisCommand()
    object RecallRoutine : JarvisCommand()
    object GoToBackground : JarvisCommand()

    // --- JARVIS INTEGRATED PROTOCOL COMMANDS ---

    // 1. Macros & Generative Workflows
    data class RunMacro(val macroName: String, val params: Map<String, String> = emptyMap()) : JarvisCommand()
    data class CreateImageInChatGPT(val prompt: String) : JarvisCommand()
    data class CreateImageInGemini(val prompt: String) : JarvisCommand()
    data class CreateVideoInGemini(val prompt: String) : JarvisCommand()
    data class AskChatGPT(val query: String) : JarvisCommand()
    data class AskGemini(val query: String) : JarvisCommand()

    // 2. Termux & Linux Commands
    data class RunTermux(val command: String, val inUbuntu: Boolean = false) : JarvisCommand()
    data class TermuxInstallPackage(val packageName: String) : JarvisCommand()
    data class TermuxWriteFile(val path: String, val content: String) : JarvisCommand()

    // 3. Voice Guardian & Biometric Security
    data class EnrollVoice(val name: String, val role: String = "FAMILY") : JarvisCommand()
    data class RemoveVoice(val name: String) : JarvisCommand()
    data class SetVoiceRole(val name: String, val role: String) : JarvisCommand()
    object ListVoices : JarvisCommand()
    data class ArmVoiceGuard(val armed: Boolean) : JarvisCommand()
    object CaptureIntruderPhoto : JarvisCommand()

    // 4. Stock Market & Finance
    data class StockQuoteCommand(val symbol: String) : JarvisCommand()
    object TrendingStocksCommand : JarvisCommand()
    data class AddToWatchlist(val symbol: String) : JarvisCommand()
    data class RemoveFromWatchlist(val symbol: String) : JarvisCommand()
    object ViewWatchlist : JarvisCommand()

    // 5. Connectors & Developer Tools
    object ListGitHubRepos : JarvisCommand()
    data class ListGitHubIssues(val owner: String, val repo: String) : JarvisCommand()
    data class CreateGitHubIssue(val owner: String, val repo: String, val title: String, val body: String) : JarvisCommand()
    data class GenerateN8nWorkflow(val name: String, val triggerType: String, val description: String) : JarvisCommand()

    // 6. Marketing, SEO & Creator Toolkit
    data class SeoAuditPageSpeed(val url: String) : JarvisCommand()
    data class FetchKeywords(val seed: String) : JarvisCommand()
    data class GenerateCreatorScript(val topic: String, val format: String = "Reel") : JarvisCommand()
}
