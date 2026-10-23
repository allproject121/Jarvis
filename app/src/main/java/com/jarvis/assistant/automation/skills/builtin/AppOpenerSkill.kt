package com.jarvis.assistant.automation.skills.builtin

import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import com.jarvis.assistant.util.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Built-in skill for launching applications, opening URLs, and searching Google Chrome.
 * Reuses existing AppLauncher and PhoneControlManager implementations.
 */
class AppOpenerSkill : JarvisSkill {

    override val metadata: SkillMetadata = SkillMetadata(
        id = "app_opener_skill",
        name = "App & Web Opener",
        description = "Launches installed Android applications, opens websites, and conducts searches in Google Chrome.",
        version = "1.0.0",
        category = SkillCategory.APP_AUTOMATION,
        riskLevel = SkillRiskLevel.MODERATE,
        requiredPermissions = emptyList(),
        isNonIdempotent = false,
        timeoutMs = 15_000L,
        supportedTools = listOf(
            "open_app",
            "open_website",
            "search_in_chrome"
        ),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult {
        val appCtx = context.context
        val name = context.toolName
        val args = context.arguments
        val app = appCtx.applicationContext as? JarvisApp

        return when (name) {
            "open_app" -> {
                val appName = args.optString("app_name", "").ifBlank { args.optString("app", "") }
                val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null

                if (appName.isBlank()) {
                    return SkillExecutionResult(
                        status = SkillExecutionStatus.FAILED,
                        userFacingMessage = "Missing app_name parameter.",
                        errorMessage = "Missing app_name argument"
                    )
                }

                val openResult = withContext(Dispatchers.Main) {
                    AppLauncher.openAppResult(appCtx, appName, appNumber)
                }

                when (openResult) {
                    is AppLauncher.OpenAppResult.Success -> {
                        val openedApp = openResult.app
                        val data = JSONObject().apply {
                            put("package_name", openedApp.packageName)
                            put("app_label", openedApp.label)
                            put("is_dual_profile", openedApp.isDualOrWorkProfile)
                        }
                        SkillExecutionResult(
                            status = SkillExecutionStatus.SUCCESS,
                            data = data,
                            userFacingMessage = "Opened ${openedApp.label}. (Note: App is now in foreground; internal tasks require specific actions).",
                            isVerified = true
                        )
                    }

                    is AppLauncher.OpenAppResult.MultipleFound -> {
                        val matchesArr = JSONArray()
                        openResult.matches.forEach { matchesArr.put("${it.label} (${it.packageName})") }
                        val data = JSONObject().apply {
                            put("matches", matchesArr)
                        }
                        SkillExecutionResult(
                            status = SkillExecutionStatus.WAITING_FOR_USER,
                            data = data,
                            userFacingMessage = "Multiple applications found for '$appName': ${openResult.matches.joinToString { it.label }}. Which one would you like to open?",
                            isVerified = false
                        )
                    }

                    is AppLauncher.OpenAppResult.NotFound -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.FAILED,
                            userFacingMessage = "Could not find application '$appName' installed on this device.",
                            errorMessage = "Application not found: $appName"
                        )
                    }

                    is AppLauncher.OpenAppResult.Failure -> {
                        // Fallback to phone control manager
                        val opened = withContext(Dispatchers.Main) {
                            app?.phoneControlManager?.openApp(appName)
                        }
                        if (opened != null) {
                            SkillExecutionResult(
                                status = SkillExecutionStatus.SUCCESS,
                                userFacingMessage = "Opened $appName.",
                                isVerified = true
                            )
                        } else {
                            SkillExecutionResult(
                                status = SkillExecutionStatus.FAILED,
                                userFacingMessage = "Failed to launch $appName.",
                                errorMessage = "Launch failed for $appName"
                            )
                        }
                    }
                }
            }

            "open_website" -> {
                val url = args.optString("url", "").ifBlank { args.optString("target_url", "") }
                if (url.isBlank()) {
                    return SkillExecutionResult(
                        status = SkillExecutionStatus.FAILED,
                        userFacingMessage = "Missing URL parameter.",
                        errorMessage = "Missing URL argument"
                    )
                }

                val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else url

                val launched = withContext(Dispatchers.Main) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appCtx.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        app?.phoneControlManager?.openUrlsInChromeTabs(listOf(formattedUrl)) ?: false
                    }
                }

                if (launched) {
                    SkillExecutionResult(
                        status = SkillExecutionStatus.SUCCESS,
                        data = JSONObject().apply { put("url", formattedUrl) },
                        userFacingMessage = "Opened $formattedUrl in browser.",
                        isVerified = true
                    )
                } else {
                    SkillExecutionResult(
                        status = SkillExecutionStatus.FAILED,
                        userFacingMessage = "Could not open $formattedUrl.",
                        errorMessage = "Failed to open URL $formattedUrl"
                    )
                }
            }

            "search_in_chrome" -> {
                val query = args.optString("query", "")
                if (query.isBlank()) {
                    return SkillExecutionResult(
                        status = SkillExecutionStatus.FAILED,
                        userFacingMessage = "Missing search query.",
                        errorMessage = "Missing query"
                    )
                }

                val targetUrl = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                val launched = withContext(Dispatchers.Main) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appCtx.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        app?.phoneControlManager?.openUrlsInChromeTabs(listOf(targetUrl)) ?: false
                    }
                }

                if (launched) {
                    SkillExecutionResult(
                        status = SkillExecutionStatus.SUCCESS,
                        data = JSONObject().apply { put("query", query); put("url", targetUrl) },
                        userFacingMessage = "Searching Google Chrome for '$query'.",
                        isVerified = true
                    )
                } else {
                    SkillExecutionResult(
                        status = SkillExecutionStatus.FAILED,
                        userFacingMessage = "Could not launch Chrome search for '$query'.",
                        errorMessage = "Failed searching in Chrome"
                    )
                }
            }

            else -> SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Unsupported tool '$name' in AppOpenerSkill.",
                errorMessage = "Unsupported tool: $name"
            )
        }
    }

    companion object {
        private fun buildSchemas(): List<JSONObject> {
            val list = mutableListOf<JSONObject>()
            fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) {
                list.add(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", name)
                        put("description", desc)
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", props)
                            put("required", JSONArray(req))
                        })
                    })
                })
            }

            tool(
                name = "open_app",
                desc = "Opens or brings an installed application to the foreground. Note: Opening an app only brings it to the foreground; internal app actions require specific tools.",
                props = JSONObject().apply {
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name of the app (e.g. 'whatsapp', 'youtube', 'instagram', 'settings')")
                    })
                    put("app_number", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional 1-based index (1 or 2) if dual apps are installed")
                    })
                },
                req = listOf("app_name")
            )

            tool(
                name = "open_website",
                desc = "Opens a web URL in the device default web browser or Chrome.",
                props = JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "The website URL to open")
                    })
                },
                req = listOf("url")
            )

            tool(
                name = "search_in_chrome",
                desc = "Performs a Google Search query in the Chrome browser.",
                props = JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "Search query keywords")
                    })
                },
                req = listOf("query")
            )

            return list
        }
    }
}
