package com.jarvis.assistant.automation.skills

import android.content.Context
import org.json.JSONObject

/**
 * Category classification for Jarvis skills.
 */
enum class SkillCategory {
    DEVICE_INFO,
    APP_AUTOMATION,
    TASK_MANAGEMENT,
    DEVELOPER_TOOLS,
    PRODUCTIVITY,
    SYSTEM,
    CUSTOM
}

/**
 * Risk classification for skill execution safety.
 */
enum class SkillRiskLevel {
    SAFE,       // Read-only queries, telemetry, status inspection
    MODERATE,   // App opening, navigation, UI reading
    HIGH_RISK   // Messaging, calling, deletions, file writes, network requests with side effects
}

/**
 * High-level execution status returned by any skill execution.
 */
enum class SkillExecutionStatus {
    SUCCESS,
    FAILED,
    WAITING_FOR_USER,
    BLOCKED
}

/**
 * Declarative metadata defining a modular Jarvis Skill.
 */
data class SkillMetadata(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val category: SkillCategory = SkillCategory.CUSTOM,
    val riskLevel: SkillRiskLevel = SkillRiskLevel.SAFE,
    val requiredPermissions: List<String> = emptyList(),
    val isNonIdempotent: Boolean = false,
    val timeoutMs: Long = 20_000L,
    val supportedTools: List<String>,
    val toolSchemas: List<JSONObject> = emptyList()
)

/**
 * Context passed to a skill during execution.
 */
data class SkillExecutionContext(
    val context: Context,
    val toolCallId: String,
    val toolName: String,
    val arguments: JSONObject,
    val callingUserId: String? = null,
    val isForeground: Boolean = true,
    val userUtterance: String? = null
)

/**
 * Structured outcome returned by a skill execution.
 */
data class SkillExecutionResult(
    val status: SkillExecutionStatus,
    val data: JSONObject = JSONObject(),
    val userFacingMessage: String,
    val isVerified: Boolean = false,
    val errorMessage: String? = null,
    val requiredAction: String? = null
) {
    val isSuccess: Boolean
        get() = status == SkillExecutionStatus.SUCCESS

    fun toResultJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("success", isSuccess)
        json.put("status", status.name)
        json.put("message", userFacingMessage)
        json.put("verified", isVerified)
        if (errorMessage != null) json.put("error", errorMessage)
        if (requiredAction != null) json.put("required_action", requiredAction)

        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!json.has(key)) {
                json.put(key, data.get(key))
            }
        }
        return json
    }
}

/**
 * The core contract that every modular Jarvis Skill must implement.
 */
interface JarvisSkill {
    val metadata: SkillMetadata

    /**
     * Executes the requested tool operation within this skill.
     * Guaranteed to be called after permission checks and safety policies pass.
     */
    suspend fun execute(context: SkillExecutionContext): SkillExecutionResult
}
