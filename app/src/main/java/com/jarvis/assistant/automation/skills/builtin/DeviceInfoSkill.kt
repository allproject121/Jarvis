package com.jarvis.assistant.automation.skills.builtin

import com.jarvis.assistant.automation.query.DeviceInfoQueryHelper
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import com.jarvis.assistant.util.DeviceTelemetryHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in skill for querying device status, battery, contacts, and app usage.
 * Integrates directly with DeviceTelemetryHelper and DeviceInfoQueryHelper.
 */
class DeviceInfoSkill : JarvisSkill {

    override val metadata: SkillMetadata = SkillMetadata(
        id = "device_info_skill",
        name = "Device Information & Telemetry",
        description = "Provides battery status, RAM telemetry, contact counts, and application usage statistics.",
        version = "1.0.0",
        category = SkillCategory.DEVICE_INFO,
        riskLevel = SkillRiskLevel.SAFE,
        requiredPermissions = emptyList(),
        isNonIdempotent = false,
        timeoutMs = 10_000L,
        supportedTools = listOf(
            "get_telemetry",
            "get_contact_count",
            "get_whatsapp_contact_count",
            "get_app_usage_stats"
        ),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult {
        val appCtx = context.context
        val name = context.toolName
        val args = context.arguments

        return when (name) {
            "get_telemetry" -> {
                val data = DeviceTelemetryHelper.getTelemetry(appCtx)
                val json = JSONObject().apply {
                    put("battery_percent", data.batteryPercent)
                    put("is_charging", data.isCharging)
                    put("battery_status", data.batteryStatusText)
                    put("ram_used_gb", String.format("%.1f", data.ramUsedGb))
                    put("ram_total_gb", String.format("%.1f", data.ramTotalGb))
                    put("ram_percent", data.ramPercent)
                    put("network_type", data.networkType)
                    put("is_online", data.isOnline)
                    put("uptime", data.uptimeFormatted)
                }
                val msg = "Battery is at ${data.batteryPercent}% (${data.batteryStatusText}). RAM: ${String.format("%.1f", data.ramUsedGb)}GB / ${String.format("%.1f", data.ramTotalGb)}GB (${data.ramPercent}%). Network: ${data.networkType}."
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = json,
                    userFacingMessage = msg,
                    isVerified = true
                )
            }

            "get_contact_count" -> {
                when (val result = DeviceInfoQueryHelper.getPhoneContactsCount(appCtx)) {
                    is DeviceInfoQueryHelper.ContactCountResult.Success -> {
                        val json = JSONObject().apply {
                            put("count", result.count)
                        }
                        SkillExecutionResult(
                            status = SkillExecutionStatus.SUCCESS,
                            data = json,
                            userFacingMessage = result.message,
                            isVerified = true
                        )
                    }
                    is DeviceInfoQueryHelper.ContactCountResult.PermissionDenied -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.BLOCKED,
                            userFacingMessage = "Contacts permission is required to count contacts. Please grant Contacts permission in Settings.",
                            requiredAction = "GRANT_CONTACTS_PERMISSION",
                            errorMessage = "READ_CONTACTS permission denied."
                        )
                    }
                    is DeviceInfoQueryHelper.ContactCountResult.Error -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.FAILED,
                            userFacingMessage = "Failed to query contacts: ${result.reason}",
                            errorMessage = result.reason
                        )
                    }
                }
            }

            "get_whatsapp_contact_count" -> {
                when (val result = DeviceInfoQueryHelper.getWhatsAppContactsCount(appCtx)) {
                    is DeviceInfoQueryHelper.WhatsAppContactCountResult.Success -> {
                        val json = JSONObject().apply {
                            put("count", result.count)
                        }
                        SkillExecutionResult(
                            status = SkillExecutionStatus.SUCCESS,
                            data = json,
                            userFacingMessage = result.explanation,
                            isVerified = true
                        )
                    }
                    is DeviceInfoQueryHelper.WhatsAppContactCountResult.PermissionDenied -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.BLOCKED,
                            userFacingMessage = "Contacts permission is required to query WhatsApp contacts. Please grant Contacts permission in Settings.",
                            requiredAction = "GRANT_CONTACTS_PERMISSION",
                            errorMessage = "READ_CONTACTS permission denied."
                        )
                    }
                    is DeviceInfoQueryHelper.WhatsAppContactCountResult.Error -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.FAILED,
                            userFacingMessage = "Failed to query WhatsApp contacts: ${result.reason}",
                            errorMessage = result.reason
                        )
                    }
                }
            }

            "get_app_usage_stats" -> {
                val appName = args.optString("app_name", "whatsapp")
                    .ifBlank { args.optString("app", "whatsapp") }
                when (val result = DeviceInfoQueryHelper.getAppUsageDuration(appCtx, appName)) {
                    is DeviceInfoQueryHelper.AppUsageResult.Success -> {
                        val json = JSONObject().apply {
                            put("app_name", result.appName)
                            put("package_name", result.packageName)
                            put("duration_ms", result.durationMs)
                            put("formatted_duration", result.formattedDuration)
                        }
                        SkillExecutionResult(
                            status = SkillExecutionStatus.SUCCESS,
                            data = json,
                            userFacingMessage = result.summary,
                            isVerified = true
                        )
                    }
                    is DeviceInfoQueryHelper.AppUsageResult.PermissionDenied -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.BLOCKED,
                            userFacingMessage = result.message,
                            requiredAction = "GRANT_USAGE_ACCESS",
                            errorMessage = "PACKAGE_USAGE_STATS permission denied."
                        )
                    }
                    is DeviceInfoQueryHelper.AppUsageResult.AppNotFound -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.FAILED,
                            userFacingMessage = result.message,
                            errorMessage = result.message
                        )
                    }
                    is DeviceInfoQueryHelper.AppUsageResult.Error -> {
                        SkillExecutionResult(
                            status = SkillExecutionStatus.FAILED,
                            userFacingMessage = "Error querying app usage: ${result.reason}",
                            errorMessage = result.reason
                        )
                    }
                }
            }

            else -> SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Unsupported tool '$name' in DeviceInfoSkill.",
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

            tool("get_telemetry", "Retrieves battery percentage, charging state, RAM usage, and current network status.")
            tool("get_contact_count", "Returns the total count of contacts saved in the phone address book using Android Contacts Provider.")
            tool("get_whatsapp_contact_count", "Queries how many contacts in the phone address book are synced with WhatsApp, explaining data limitations honestly.")
            tool("get_app_usage_stats", "Queries actual app usage duration for today (e.g. 'whatsapp', 'youtube') using Android UsageStatsManager.", JSONObject().apply {
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app to check usage for (default is 'whatsapp')")
                })
            })

            return list
        }
    }
}
