package com.jarvis.assistant.automation.skills.builtin

import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.skills.JarvisSkill
import com.jarvis.assistant.automation.skills.SkillCategory
import com.jarvis.assistant.automation.skills.SkillExecutionContext
import com.jarvis.assistant.automation.skills.SkillExecutionResult
import com.jarvis.assistant.automation.skills.SkillExecutionStatus
import com.jarvis.assistant.automation.skills.SkillMetadata
import com.jarvis.assistant.automation.skills.SkillRiskLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in skill for monitoring, inspecting, cancelling, and resuming background automation tasks.
 * Reuses AgentTaskEngine and PersistentTaskStore directly.
 */
class TaskManagementSkill : JarvisSkill {

    override val metadata: SkillMetadata = SkillMetadata(
        id = "task_manager_skill",
        name = "Task Management & Self-Recovery",
        description = "Provides real-time task status tracking, active task observation, failure inspection, and safe resumption.",
        version = "1.0.0",
        category = SkillCategory.TASK_MANAGEMENT,
        riskLevel = SkillRiskLevel.SAFE,
        requiredPermissions = emptyList(),
        isNonIdempotent = false,
        timeoutMs = 10_000L,
        supportedTools = listOf(
            "get_active_task",
            "get_task_status",
            "get_failed_tasks",
            "cancel_active_task",
            "get_last_failure_reason",
            "resume_task"
        ),
        toolSchemas = buildSchemas()
    )

    override suspend fun execute(context: SkillExecutionContext): SkillExecutionResult {
        val name = context.toolName
        val args = context.arguments

        return when (name) {
            "get_active_task" -> {
                val summary = AgentTaskEngine.getActiveTaskSummary()
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("active_task_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = true
                )
            }

            "get_task_status" -> {
                val taskId = args.optString("task_id", "").takeIf { it.isNotBlank() }
                val summary = AgentTaskEngine.getTaskStatusSummary(taskId)
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("task_status_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = true
                )
            }

            "get_failed_tasks" -> {
                val limit = args.optInt("limit", 5)
                val summary = AgentTaskEngine.getFailedTasksSummary(limit)
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("failed_tasks_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = true
                )
            }

            "cancel_active_task" -> {
                val reason = args.optString("reason", "User requested cancellation")
                val summary = AgentTaskEngine.cancelActiveTask(reason)
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("cancellation_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = true
                )
            }

            "get_last_failure_reason" -> {
                val summary = AgentTaskEngine.getLastFailureReasonSummary()
                SkillExecutionResult(
                    status = SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("failure_reason_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = true
                )
            }

            "resume_task" -> {
                val taskId = args.optString("task_id", "").takeIf { it.isNotBlank() }
                val summary = AgentTaskEngine.resumeActiveTask(taskId)
                val isBlocked = summary.contains("blocked", true) || summary.contains("cannot", true)
                SkillExecutionResult(
                    status = if (isBlocked) SkillExecutionStatus.BLOCKED else SkillExecutionStatus.SUCCESS,
                    data = JSONObject().apply { put("resumption_summary", summary) },
                    userFacingMessage = summary,
                    isVerified = !isBlocked,
                    errorMessage = if (isBlocked) summary else null
                )
            }

            else -> SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "Unsupported tool '$name' in TaskManagementSkill.",
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

            tool("get_active_task", "Returns what Jarvis is currently doing right now, including current step and screen observation.")
            tool("get_task_status", "Retrieves current status, step progress, or verification result of an active or recent automation task.", JSONObject().apply {
                put("task_id", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional unique task ID to query; if omitted, queries the active or most recent task")
                })
            })
            tool("get_failed_tasks", "Queries recent failed tasks with their error causes and recovery evidence.", JSONObject().apply {
                put("limit", JSONObject().apply {
                    put("type", "integer")
                    put("description", "Maximum number of recent failed tasks to return (default 5)")
                })
            })
            tool("cancel_active_task", "Cancels the active automation task cleanly and releases all resources.", JSONObject().apply {
                put("reason", JSONObject().apply {
                    put("type", "string")
                    put("description", "Reason for cancellation")
                })
            })
            tool("get_last_failure_reason", "Explains why the last task failed, what was observed, and whether user action is required.")
            tool("resume_task", "Resumes an interrupted or paused task if safe (blocks automatic resumption for sensitive/non-idempotent actions).", JSONObject().apply {
                put("task_id", JSONObject().apply {
                    put("type", "string")
                    put("description", "Optional task ID to resume")
                })
            })

            return list
        }
    }
}
