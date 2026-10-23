package com.jarvis.assistant.automation.command

import android.util.Log
import com.jarvis.assistant.automation.macro.MacroEngine
import com.jarvis.assistant.automation.macro.MacroExecutionResult
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.termux.TermuxBridge
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.connectors.GitHubConnector
import com.jarvis.assistant.connectors.N8nWorkflowGenerator
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.finance.YahooFinanceClient
import com.jarvis.assistant.marketing.SeoAuditEngine
import com.jarvis.assistant.marketing.YouTubeCreatorToolkit
import com.jarvis.assistant.security.guardian.ActionCategory
import com.jarvis.assistant.security.guardian.GuardianRole
import com.jarvis.assistant.security.guardian.VoiceGuardian
import com.jarvis.assistant.security.guardian.WatchmanSecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager,
    private val macroEngine: MacroEngine? = null,
    private val termuxBridge: TermuxBridge? = null,
    private val voiceGuardian: VoiceGuardian? = null,
    private val watchmanSecurity: WatchmanSecurityManager? = null,
    private val financeClient: YahooFinanceClient? = null,
    private val gitHubConnector: GitHubConnector? = null,
    private val seoAuditEngine: SeoAuditEngine? = null,
    private val directAiImageGenerator: com.jarvis.assistant.ai.DirectAiImageGenerator? = null
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: JarvisCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is JarvisCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is JarvisCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is JarvisCommand.PauseMedia -> youtubeManager.pausePlayback()
            is JarvisCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is JarvisCommand.NextMedia -> youtubeManager.nextMedia()
            is JarvisCommand.PreviousMedia -> youtubeManager.previousMedia()
            is JarvisCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is JarvisCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is JarvisCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is JarvisCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is JarvisCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is JarvisCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is JarvisCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is JarvisCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is JarvisCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is JarvisCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is JarvisCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is JarvisCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is JarvisCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is JarvisCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is JarvisCommand.Remember -> memoryManager.rememberFact(command.text)
            is JarvisCommand.Forget -> memoryManager.forgetFact(command.query)
            is JarvisCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is JarvisCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is JarvisCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is JarvisCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is JarvisCommand.GoToBackground -> "Going to background."

            // --- JARVIS INTEGRATED PROTOCOL FEATURES ---

            // 1. Generative AI Macros (ChatGPT / Gemini)
            is JarvisCommand.RunMacro -> {
                val res = macroEngine?.executeMacroByName(command.macroName, command.params)
                when (res) {
                    is MacroExecutionResult.Success -> res.output ?: res.message
                    is MacroExecutionResult.Failure -> "Macro failed: ${res.error}"
                    null -> "Macro engine not available."
                }
            }
            is JarvisCommand.CreateImageInChatGPT -> {
                val prompt = command.prompt
                if (directAiImageGenerator != null) {
                    val bmp = directAiImageGenerator.generateImage(prompt)
                    if (bmp != null) "AI image generated for '$prompt' and saved to Gallery." else "Direct AI image generation failed."
                } else {
                    val res = macroEngine?.executeMacroByName("chatgpt image", mapOf("image prompt" to prompt))
                    when (res) {
                        is MacroExecutionResult.Success -> "Image generation initiated in ChatGPT."
                        is MacroExecutionResult.Failure -> "ChatGPT image failed: ${res.error}"
                        null -> "Macro engine not available."
                    }
                }
            }
            is JarvisCommand.CreateImageInGemini -> {
                val prompt = command.prompt
                if (directAiImageGenerator != null) {
                    val bmp = directAiImageGenerator.generateImage(prompt)
                    if (bmp != null) "AI image generated for '$prompt' and saved to Gallery." else "Direct AI image generation failed."
                } else {
                    val res = macroEngine?.executeMacroByName("gemini image", mapOf("image prompt" to prompt))
                    when (res) {
                        is MacroExecutionResult.Success -> "Image generation initiated in Gemini."
                        is MacroExecutionResult.Failure -> "Gemini image failed: ${res.error}"
                        null -> "Macro engine not available."
                    }
                }
            }
            is JarvisCommand.CreateVideoInGemini -> {
                val res = macroEngine?.executeMacroByName("gemini video", mapOf("video prompt" to command.prompt))
                when (res) {
                    is MacroExecutionResult.Success -> "Video generation initiated in Gemini."
                    is MacroExecutionResult.Failure -> "Gemini video failed: ${res.error}"
                    null -> "Macro engine not available."
                }
            }
            is JarvisCommand.AskChatGPT -> {
                val res = macroEngine?.executeMacroByName("chatgpt se poochho", mapOf("sawaal" to command.query))
                when (res) {
                    is MacroExecutionResult.Success -> res.output ?: "Query sent to ChatGPT."
                    is MacroExecutionResult.Failure -> "ChatGPT query failed: ${res.error}"
                    null -> "Macro engine not available."
                }
            }
            is JarvisCommand.AskGemini -> {
                val res = macroEngine?.executeMacroByName("gemini se poochho", mapOf("sawaal" to command.query))
                when (res) {
                    is MacroExecutionResult.Success -> res.output ?: "Query sent to Gemini."
                    is MacroExecutionResult.Failure -> "Gemini query failed: ${res.error}"
                    null -> "Macro engine not available."
                }
            }

            // 2. Termux & Linux Integration
            is JarvisCommand.RunTermux -> {
                if (termuxBridge == null) return@withContext "Terminal bridge not available."
                if (termuxBridge.isTermuxInstalled()) {
                    val ok = if (command.inUbuntu) {
                        termuxBridge.runInUbuntu(command.command)
                    } else {
                        termuxBridge.runCommand(command.command)
                    }
                    if (ok) "Command dispatched to Termux: ${command.command}" else "Failed to send command to Termux."
                } else {
                    // Fallback to native Android Linux Shell execution
                    val out = termuxBridge.executeLocalCommand(command.command)
                    "Shell: $out"
                }
            }
            is JarvisCommand.TermuxInstallPackage -> {
                val ok = termuxBridge?.installPackage(command.packageName) ?: false
                if (ok) "Installing ${command.packageName} in Termux..." else "Termux not available."
            }
            is JarvisCommand.TermuxWriteFile -> {
                val ok = termuxBridge?.writeFile(command.path, command.content) ?: false
                if (ok) "File written to Termux: ${command.path}" else "Failed to write file."
            }

            // 3. Voice Guardian & Biometric Security
            is JarvisCommand.EnrollVoice -> {
                val role = try { GuardianRole.valueOf(command.role.uppercase()) } catch (_: Exception) { GuardianRole.FAMILY }
                val ok = voiceGuardian?.enrollVoice(command.name, role) ?: false
                if (ok) "Voice enrolled for ${command.name} as $role" else "Voice Guardian not ready."
            }
            is JarvisCommand.RemoveVoice -> {
                val ok = voiceGuardian?.removeVoice(command.name) ?: false
                if (ok) "Voice removed for ${command.name}" else "Profile not found."
            }
            is JarvisCommand.SetVoiceRole -> {
                val role = try { GuardianRole.valueOf(command.role.uppercase()) } catch (_: Exception) { GuardianRole.FAMILY }
                val ok = voiceGuardian?.setVoiceRole(command.name, role) ?: false
                if (ok) "Updated ${command.name}'s role to $role" else "Profile not found."
            }
            is JarvisCommand.ListVoices -> {
                val list = voiceGuardian?.listVoices() ?: emptyList()
                if (list.isEmpty()) "No enrolled voices." else list.joinToString("\n") { "${it.name} (${it.role})" }
            }
            is JarvisCommand.ArmVoiceGuard -> {
                voiceGuardian?.isArmed = command.armed
                if (command.armed) "Voice Guardian armed. Security active." else "Voice Guardian disarmed."
            }
            is JarvisCommand.CaptureIntruderPhoto -> {
                watchmanSecurity?.captureIntruderPhoto { file ->
                    Log.i(TAG, "Intruder snapshot saved at: ${file?.absolutePath}")
                }
                "Capturing intruder snapshot."
            }

            // 4. Stock Market & Finance
            is JarvisCommand.StockQuoteCommand -> {
                val quote = financeClient?.getQuote(command.symbol)
                if (quote != null) {
                    val sign = if (quote.change >= 0) "+" else ""
                    "${quote.name} (${quote.symbol}): ${quote.currency} ${quote.price} ($sign${String.format("%.2f", quote.changePercent)}%)"
                } else {
                    "Could not fetch quote for ${command.symbol}."
                }
            }
            is JarvisCommand.TrendingStocksCommand -> {
                val trending = financeClient?.getTrending() ?: emptyList()
                if (trending.isNotEmpty()) "Trending tickers: " + trending.take(5).joinToString(", ") else "No trending stocks data available."
            }
            is JarvisCommand.AddToWatchlist -> {
                financeClient?.addToWatchlist(command.symbol)
                "Added ${command.symbol} to watchlist."
            }
            is JarvisCommand.RemoveFromWatchlist -> {
                financeClient?.removeFromWatchlist(command.symbol)
                "Removed ${command.symbol} from watchlist."
            }
            is JarvisCommand.ViewWatchlist -> {
                val list = financeClient?.getWatchlist() ?: emptySet()
                if (list.isEmpty()) "Watchlist is empty." else "Watchlist: " + list.joinToString(", ")
            }

            // 5. Connectors & Developer Tools
            is JarvisCommand.ListGitHubRepos -> {
                val repos = gitHubConnector?.listMyRepositories() ?: emptyList()
                if (repos.isEmpty()) "No GitHub repositories found or token not configured." else "Repositories:\n" + repos.joinToString("\n")
            }
            is JarvisCommand.ListGitHubIssues -> {
                val issues = gitHubConnector?.getRepoIssues(command.owner, command.repo) ?: emptyList()
                if (issues.isEmpty()) "No open issues found for ${command.owner}/${command.repo}." else "Issues:\n" + issues.joinToString("\n")
            }
            is JarvisCommand.CreateGitHubIssue -> {
                gitHubConnector?.createIssue(command.owner, command.repo, command.title, command.body) ?: "GitHub not configured."
            }
            is JarvisCommand.GenerateN8nWorkflow -> {
                N8nWorkflowGenerator.generateWorkflow(command.name, command.triggerType, command.description)
            }

            // 6. Marketing, SEO & Creator Toolkit
            is JarvisCommand.SeoAuditPageSpeed -> {
                seoAuditEngine?.runPageSpeedAudit(command.url) ?: "PageSpeed service not available."
            }
            is JarvisCommand.FetchKeywords -> {
                val kw = seoAuditEngine?.fetchKeywords(command.seed) ?: emptyList()
                if (kw.isEmpty()) "No keyword suggestions found." else "Keyword suggestions:\n" + kw.joinToString("\n")
            }
            is JarvisCommand.GenerateCreatorScript -> {
                YouTubeCreatorToolkit.generateDynamicScript(com.jarvis.assistant.JarvisApp.instance, command.topic, command.format, inHinglish = true)
            }
        }
    }
}
