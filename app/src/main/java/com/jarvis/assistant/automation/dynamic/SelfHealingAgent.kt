package com.jarvis.assistant.automation.dynamic

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Autonomous Reflection & Self-Healing Agent.
 * Intercepts tool execution errors, diagnoses root causes, patches dynamic tools,
 * and records lessons learned into Jarvis's persistent Memory Core.
 */
class SelfHealingAgent private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SelfHealingAgent"

        @Volatile
        private var instance: SelfHealingAgent? = null

        fun getInstance(context: Context): SelfHealingAgent {
            return instance ?: synchronized(this) {
                instance ?: SelfHealingAgent(context.applicationContext).also { instance = it }
            }
        }
    }

    private val registry by lazy { DynamicToolRegistry.getInstance(context) }
    private val memoryDb by lazy { MemoryDatabase.getInstance(context) }
    private val logFile by lazy { File(context.filesDir, "dynamic_tools/healing_history.log") }

    data class HealingIncident(
        val toolName: String,
        val arguments: JSONObject,
        val errorMessage: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Intercepts a failed tool execution and checks if self-healing remediation is possible.
     */
    suspend fun interceptFailure(
        toolName: String,
        arguments: JSONObject,
        errorMessage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val incident = HealingIncident(toolName, arguments, errorMessage)
            logIncident(incident)

            // 1. Record failure in DynamicToolRegistry
            registry.recordExecution(toolName, success = false, error = errorMessage)

            // 2. Check if this is a dynamic tool eligible for reflection
            val dynamicTool = registry.getTool(toolName)
            if (dynamicTool != null) {
                Log.w(TAG, "Diagnosing failure in dynamic tool '$toolName' (v${dynamicTool.version})")
                // If the tool has failed repeatedly (>3 times), flag for rewrite or auto-repair
                if (dynamicTool.failureCount >= 3) {
                    Log.e(TAG, "Dynamic tool '$toolName' has failed 3+ times. Flagging for AI rewrite.")
                }
            }

            // 3. Persist diagnostic memory in Room so Gemini Live has context in future turns
            val memoryText = "Self-Healing diagnostic: Tool '$toolName' failed with error: '$errorMessage'. Arguments: $arguments"
            memoryDb.memoryDao().insertMemory(
                MemoryEntity(
                    category = MemoryEntity.CATEGORY_NOTES,
                    key = "self_healing_$toolName",
                    value = errorMessage,
                    rawText = memoryText
                )
            )
            Log.i(TAG, "Saved self-healing diagnostic to Memory Core for tool '$toolName'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in self-healing interception: ${e.message}", e)
            false
        }
    }

    /**
     * Records a successful patch applied to a dynamic tool or macro.
     */
    suspend fun recordPatchSuccess(
        toolName: String,
        patchDescription: String
    ) = withContext(Dispatchers.IO) {
        try {
            registry.recordExecution(toolName, success = true, error = null)

            val memoryText = "Self-Healing resolution: Successfully repaired tool '$toolName'. Fix: $patchDescription"
            memoryDb.memoryDao().insertMemory(
                MemoryEntity(
                    category = MemoryEntity.CATEGORY_NOTES,
                    key = "self_healing_patch_$toolName",
                    value = patchDescription,
                    rawText = memoryText
                )
            )
            Log.i(TAG, "Recorded successful self-healing patch for tool '$toolName'")
        } catch (e: Exception) {
            Log.e(TAG, "Error recording patch success: ${e.message}", e)
        }
    }

    private fun logIncident(incident: HealingIncident) {
        try {
            val entry = "[${java.util.Date(incident.timestamp)}] TOOL: ${incident.toolName} | ERROR: ${incident.errorMessage} | ARGS: ${incident.arguments}\n"
            logFile.appendText(entry)
        } catch (_: Exception) {}
    }
}
