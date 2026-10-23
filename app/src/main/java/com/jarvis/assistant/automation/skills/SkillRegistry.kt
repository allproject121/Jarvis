package com.jarvis.assistant.automation.skills

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe centralized registry for discovering, managing, and executing modular Jarvis skills.
 */
class SkillRegistry internal constructor(private val appContext: Context? = null) {

    companion object {
        private const val TAG = "SkillRegistry"
        private const val PREFS_NAME = "jarvis_skills_prefs"
        private const val PREF_PREFIX_ENABLED = "skill_enabled_"

        @Volatile
        private var instance: SkillRegistry? = null

        fun getInstance(context: Context): SkillRegistry {
            return instance ?: synchronized(this) {
                instance ?: SkillRegistry(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences? = try {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }
    private val memoryPrefs = ConcurrentHashMap<String, Boolean>()
    private val skillsById = ConcurrentHashMap<String, JarvisSkill>()
    private val toolToSkillMap = ConcurrentHashMap<String, JarvisSkill>()

    init {
        registerBuiltInSkills()
    }

    /**
     * Registers a new modular skill.
     * Rejects duplicate skill IDs.
     */
    @Synchronized
    fun registerSkill(skill: JarvisSkill) {
        val id = skill.metadata.id
        if (skillsById.containsKey(id)) {
            val err = "Skill with ID '$id' is already registered. Duplicate rejected."
            Log.e(TAG, err)
            throw IllegalArgumentException(err)
        }

        skillsById[id] = skill
        for (tool in skill.metadata.supportedTools) {
            val existing = toolToSkillMap[tool]
            if (existing != null && existing.metadata.id != id) {
                Log.w(TAG, "Tool '$tool' is registered by both '${existing.metadata.id}' and '$id'. Overriding to '$id'.")
            }
            toolToSkillMap[tool] = skill
        }

        Log.i(TAG, "Registered skill '$id' (${skill.metadata.name}) with ${skill.metadata.supportedTools.size} tools.")
    }

    /**
     * Unregisters a skill by ID.
     */
    @Synchronized
    fun unregisterSkill(skillId: String): Boolean {
        val skill = skillsById.remove(skillId) ?: return false
        for (tool in skill.metadata.supportedTools) {
            if (toolToSkillMap[tool]?.metadata?.id == skillId) {
                toolToSkillMap.remove(tool)
            }
        }
        Log.i(TAG, "Unregistered skill '$skillId'")
        return true
    }

    /**
     * Finds the skill that supports the given tool name.
     */
    fun findSkillForTool(toolName: String): JarvisSkill? {
        return toolToSkillMap[toolName]
    }

    /**
     * Gets a skill by its unique ID.
     */
    fun getSkill(skillId: String): JarvisSkill? {
        return skillsById[skillId]
    }

    /**
     * Returns all registered skills.
     */
    fun getAllSkills(): List<JarvisSkill> {
        return skillsById.values.toList()
    }

    /**
     * Checks if a skill is enabled (defaults to true).
     */
    fun isSkillEnabled(skillId: String): Boolean {
        if (memoryPrefs.containsKey(PREF_PREFIX_ENABLED + skillId)) {
            return memoryPrefs[PREF_PREFIX_ENABLED + skillId] ?: true
        }
        return prefs?.getBoolean(PREF_PREFIX_ENABLED + skillId, true) ?: true
    }

    /**
     * Enables or disables a skill, persisting the setting across restarts.
     */
    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        memoryPrefs[PREF_PREFIX_ENABLED + skillId] = enabled
        prefs?.edit()?.putBoolean(PREF_PREFIX_ENABLED + skillId, enabled)?.apply()
        Log.i(TAG, "Skill '$skillId' enabled state set to: $enabled")
    }

    /**
     * Returns all enabled skills.
     */
    fun getEnabledSkills(): List<JarvisSkill> {
        return skillsById.values.filter { isSkillEnabled(it.metadata.id) }
    }

    /**
     * Aggregates tool schemas declared across all registered and enabled skills.
     */
    fun getRegisteredToolSchemas(): List<JSONObject> {
        val schemas = mutableListOf<JSONObject>()
        for (skill in getEnabledSkills()) {
            schemas.addAll(skill.metadata.toolSchemas)
        }
        return schemas
    }

    /**
     * Executes a tool via its managing skill with full safety policy and timeout bounds.
     */
    suspend fun executeTool(context: SkillExecutionContext): SkillExecutionResult = withContext(Dispatchers.IO) {
        val toolName = context.toolName
        val skill = findSkillForTool(toolName)

        if (skill == null) {
            val errorMsg = "No registered skill found to handle tool '$toolName'."
            Log.w(TAG, errorMsg)
            return@withContext SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = errorMsg,
                errorMessage = errorMsg
            )
        }

        // Check enabled state
        if (!isSkillEnabled(skill.metadata.id)) {
            val blockedMsg = "Skill '${skill.metadata.name}' is currently disabled in Jarvis settings."
            Log.w(TAG, "Refusing execution of tool '$toolName': $blockedMsg")
            return@withContext SkillExecutionResult(
                status = SkillExecutionStatus.BLOCKED,
                userFacingMessage = blockedMsg,
                errorMessage = blockedMsg
            )
        }

        // Check permission and safety policy
        val policyResult = SkillPermissionPolicy.evaluate(context.context, skill, toolName)
        if (policyResult is SkillPermissionPolicy.PolicyCheckResult.Blocked) {
            Log.w(TAG, "Safety policy blocked tool '$toolName': ${policyResult.reason}")
            return@withContext SkillExecutionResult(
                status = policyResult.status,
                userFacingMessage = policyResult.userPrompt,
                errorMessage = policyResult.reason,
                requiredAction = policyResult.requiredAction
            )
        }

        // Bounded execution with timeout
        val timeoutMs = skill.metadata.timeoutMs
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                skill.execute(context)
            }

            if (result == null) {
                val timeoutMsg = "Skill '${skill.metadata.name}' timed out after ${timeoutMs}ms while executing '$toolName'."
                Log.e(TAG, timeoutMsg)
                return@withContext SkillExecutionResult(
                    status = SkillExecutionStatus.FAILED,
                    userFacingMessage = "Operation timed out. Please try again.",
                    errorMessage = timeoutMsg
                )
            }

            result
        } catch (e: Exception) {
            // Redact potential private data and return clean error
            val sanitizedMsg = sanitizeErrorMessage(e.message ?: "Execution error")
            Log.e(TAG, "Error executing skill '${skill.metadata.name}' on tool '$toolName': $sanitizedMsg", e)
            SkillExecutionResult(
                status = SkillExecutionStatus.FAILED,
                userFacingMessage = "An error occurred while executing '$toolName': $sanitizedMsg",
                errorMessage = sanitizedMsg
            )
        }
    }

    /**
     * Registers all standard built-in skills.
     */
    private fun registerBuiltInSkills() {
        try {
            registerSkill(com.jarvis.assistant.automation.skills.builtin.DeviceInfoSkill())
            registerSkill(com.jarvis.assistant.automation.skills.builtin.AppOpenerSkill())
            registerSkill(com.jarvis.assistant.automation.skills.builtin.TaskManagementSkill())
            registerSkill(com.jarvis.assistant.automation.skills.builtin.RepositoryAnalysisSkill())
            registerSkill(com.jarvis.assistant.automation.skills.builtin.SkillBuilderIntegrationSkill())
            registerSkill(com.jarvis.assistant.automation.skills.builtin.SpoonKnifeDemoScreenSkill())
        } catch (e: Exception) {
            Log.e(TAG, "Error registering built-in skills: ${e.message}", e)
        }
    }

    /**
     * Strips credentials, auth tokens, or passwords from error logs.
     */
    private fun sanitizeErrorMessage(message: String): String {
        return message
            .replace(Regex("(?i)(password|secret|token|key|api[_-]?key)\\s*[:=]\\s*['\"][^'\"]+['\"]"), "$1=[REDACTED]")
            .replace(Regex("sk-[a-zA-Z0-9]{20,}"), "[REDACTED_API_KEY]")
            .replace(Regex("ghp_[a-zA-Z0-9]{20,}"), "[REDACTED_GITHUB_TOKEN]")
    }
}
