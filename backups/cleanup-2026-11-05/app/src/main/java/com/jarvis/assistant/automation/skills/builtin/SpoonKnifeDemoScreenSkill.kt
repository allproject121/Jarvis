package com.jarvis.assistant.automation.skills.builtin

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import com.jarvis.assistant.ui.spoonknife.SpoonKnifeDemoActivity
import com.jarvis.assistant.util.ActivityLauncherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in Skill for autonomous Spoon-Knife demo screen control.
 * Safely renders the static HTML/CSS frontend within an isolated WebView container,
 * providing opening, navigation, and dismissal tools.
 */
class SpoonKnifeDemoScreenSkill : JarvisSkill {

    companion object {
        private const val TAG = "SpoonKnifeSkill"

        private fun buildSchemas(): List<JSONObject> {
            val list = mutableListOf<JSONObject>()

            // 1. open_spoon_knife_demo
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "open_spoon_knife_demo")
                    put("description", "Opens the optional Spoon-Knife demo screen rendering the static HTML/CSS frontend in an isolated WebView.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })

            // 2. close_spoon_knife_demo
            list.add(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "close_spoon_knife_demo")
                    put("description", "Closes the currently visible Spoon-Knife demo screen and returns to Jarvis.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })

            return list
        }
    }

    override val metadata: SkillMetadata = SkillMetadata(
        id = "spoon_knife_demo_screen_skill",
        name = "Spoon-Knife Demo Screen",
        description = "Provides an optional standalone demo screen rendering the Spoon-Knife static HTML/CSS frontend.",
        version = "1.0.0",
        category = SkillCategory.CUSTOM,
        riskLevel = SkillRiskLevel.SAFE,
        supportedTools = listOf("open_spoon_knife_demo", "close_spoon_knife_demo"),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult {
        val toolName = context.toolName
        return try {
            when (toolName) {
                "open_spoon_knife_demo" -> handleOpenDemo(context.context)
                "close_spoon_knife_demo" -> handleCloseDemo()
                else -> SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Unsupported tool '$toolName' in SpoonKnifeDemoScreenSkill.",
                    errorMessage = "Unsupported tool: $toolName"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Spoon-Knife tool '$toolName': ${e.message}", e)
            SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Failed to execute '$toolName': ${e.message}",
                errorMessage = e.message
            )
        }
    }

    private fun handleOpenDemo(context: Context): SkillExecutionResult {
        val intent = Intent(context, SpoonKnifeDemoActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
        return if (launched) {
            SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = JSONObject().apply {
                    put("action", "open")
                    put("asset_path", "file:///android_asset/spoonknife/index.html")
                    put("status", "OPENED")
                },
                userFacingMessage = "Spoon-Knife demo screen opened.",
                isVerified = true
            )
        } else {
            SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Failed to launch Spoon-Knife demo screen.",
                errorMessage = "Activity launch blocked or failed."
            )
        }
    }

    private fun handleCloseDemo(): SkillExecutionResult {
        val activity = SpoonKnifeDemoActivity.instance
        return if (activity != null && !activity.isFinishing) {
            try {
                activity.runOnUiThread { activity.finish() }
            } catch (_: Exception) {
                activity.finish()
            }
            SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = JSONObject().apply {
                    put("action", "close")
                    put("status", "CLOSED")
                },
                userFacingMessage = "Spoon-Knife demo screen closed.",
                isVerified = true
            )
        } else {
            SkillExecutionResult(
                status = SkillExecutionStatus.SUCCESS,
                data = JSONObject().apply {
                    put("action", "close")
                    put("status", "NOT_OPEN")
                },
                userFacingMessage = "Spoon-Knife demo screen is not currently open.",
                isVerified = true
            )
        }
    }
}
