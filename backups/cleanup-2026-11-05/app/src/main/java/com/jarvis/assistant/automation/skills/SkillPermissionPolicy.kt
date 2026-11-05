package com.jarvis.assistant.automation.skills

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.agent.PolicyEvaluation
import com.jarvis.assistant.automation.agent.TaskExecutionPolicy

/**
 * Enforces safety and permission validation before any skill tool executes.
 */
object SkillPermissionPolicy {

    private const val TAG = "SkillPermissionPolicy"

    sealed class PolicyCheckResult {
        object Allowed : PolicyCheckResult()
        data class Blocked(
            val reason: String,
            val requiredAction: String? = null,
            val status: SkillExecutionStatus = SkillExecutionStatus.BLOCKED,
            val userPrompt: String = reason
        ) : PolicyCheckResult()
    }

    /**
     * Evaluates preconditions, runtime permissions, and safety barriers before skill execution.
     */
    fun evaluate(context: Context, skill: JarvisSkill, toolName: String): PolicyCheckResult {
        val meta = skill.metadata

        // 1. Validate Android runtime permissions declared by the skill
        for (permission in meta.requiredPermissions) {
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val permName = permission.substringAfterLast(".")
                Log.w(TAG, "Skill '${meta.name}' blocked: missing permission $permName")
                return PolicyCheckResult.Blocked(
                    reason = "Required permission '$permName' is not granted.",
                    requiredAction = "GRANT_PERMISSION_$permName",
                    status = SkillExecutionStatus.BLOCKED,
                    userPrompt = "Permission '$permName' is required to use '${meta.name}'. Please grant this permission in Settings."
                )
            }
        }

        // 2. Validate background execution and device state policy
        val policyEvaluation = TaskExecutionPolicy.evaluatePreconditions(context, toolName)
        if (!policyEvaluation.canExecute) {
            val status = when ((policyEvaluation as? PolicyEvaluation.Blocked)?.targetState?.name) {
                "WAITING_FOR_USER" -> SkillExecutionStatus.WAITING_FOR_USER
                "WAITING_FOR_PERMISSION" -> SkillExecutionStatus.BLOCKED
                else -> SkillExecutionStatus.FAILED
            }
            return PolicyCheckResult.Blocked(
                reason = policyEvaluation.reason ?: "Precondition check failed",
                requiredAction = policyEvaluation.requiredAction,
                status = status,
                userPrompt = (policyEvaluation as? PolicyEvaluation.Blocked)?.userPrompt
                    ?: policyEvaluation.reason ?: "Action blocked by safety policy."
            )
        }

        // 3. Risky / Consequential action guard
        if (meta.riskLevel == SkillRiskLevel.HIGH_RISK && meta.isNonIdempotent) {
            Log.i(TAG, "Skill '${meta.name}' requires high-risk confirmation guard for tool '$toolName'")
        }

        return PolicyCheckResult.Allowed
    }
}
