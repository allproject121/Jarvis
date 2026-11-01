package com.jarvis.assistant.automation.dynamic

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe persistent registry managing dynamically created AI tools.
 * Stored in app internal storage (filesDir/dynamic_tools).
 */
class DynamicToolRegistry internal constructor(
    private val context: Context? = null,
    baseDirOverride: File? = null
) {

    companion object {
        private const val TAG = "DynamicToolRegistry"
        private const val DIRECTORY_NAME = "dynamic_tools"
        private const val REGISTRY_FILE = "registry.json"

        @Volatile
        private var instance: DynamicToolRegistry? = null

        fun getInstance(context: Context): DynamicToolRegistry {
            return instance ?: synchronized(this) {
                instance ?: DynamicToolRegistry(context = context.applicationContext).also { instance = it }
            }
        }

        @VisibleForTesting
        fun createForTesting(storageDir: File): DynamicToolRegistry {
            return DynamicToolRegistry(context = null, baseDirOverride = storageDir)
        }

        // Protected system tool names that dynamic tools cannot overwrite
        val PROTECTED_TOOLS = setOf(
            "call_contact", "send_whatsapp_message", "whatsapp_call", "send_sms",
            "open_app", "toggle_flashlight", "toggle_wifi", "toggle_bluetooth",
            "set_volume", "get_battery_status", "create_dynamic_tool", "edit_dynamic_tool",
            "list_dynamic_tools", "delete_dynamic_tool"
        )
    }

    private val mutex = Mutex()
    private val baseDir: File = baseDirOverride ?: File(context?.filesDir, DIRECTORY_NAME).apply { if (!exists()) mkdirs() }
    private val registryFile: File by lazy { File(baseDir, REGISTRY_FILE) }
    private val inMemoryCache = mutableMapOf<String, DynamicTool>()

    init {
        loadRegistry()
    }

    private fun loadRegistry() {
        try {
            if (!registryFile.exists()) return
            val content = registryFile.readText()
            if (content.isBlank()) return
            val jsonArray = JSONArray(content)
            inMemoryCache.clear()
            for (i in 0 until jsonArray.length()) {
                val toolObj = jsonArray.getJSONObject(i)
                val tool = DynamicTool.fromJson(toolObj)
                inMemoryCache[tool.name] = tool
            }
            Log.i(TAG, "Loaded ${inMemoryCache.size} dynamic tools into memory cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading dynamic tool registry: ${e.message}", e)
        }
    }

    private fun persistRegistryLocked() {
        try {
            val jsonArray = JSONArray()
            inMemoryCache.values.forEach { jsonArray.put(it.toJson()) }
            registryFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting dynamic tool registry: ${e.message}", e)
        }
    }

    suspend fun getAllTools(): List<DynamicTool> = mutex.withLock {
        inMemoryCache.values.toList()
    }

    suspend fun getTool(name: String): DynamicTool? = mutex.withLock {
        inMemoryCache[name]
    }

    suspend fun isDynamicTool(name: String): Boolean = mutex.withLock {
        inMemoryCache.containsKey(name)
    }

    /**
     * Validates and creates a new dynamic tool.
     */
    suspend fun createTool(
        name: String,
        description: String,
        parametersSchemaJson: String,
        scriptType: DynamicTool.ScriptType,
        scriptCode: String,
        author: String = "JARVIS_AI"
    ): Result<DynamicTool> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanName = name.trim().lowercase()

            if (!cleanName.matches(Regex("^[a-z0-9_]{3,64}$"))) {
                return@withContext Result.failure(
                    IllegalArgumentException("Tool name must be 3-64 characters alphanumeric or underscore: '$cleanName'")
                )
            }

            if (cleanName in PROTECTED_TOOLS) {
                return@withContext Result.failure(
                    IllegalArgumentException("Tool name '$cleanName' is protected and cannot be overwritten.")
                )
            }

            if (inMemoryCache.containsKey(cleanName)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Dynamic tool '$cleanName' already exists. Use edit_dynamic_tool to update it.")
                )
            }

            // Validate parameter schema
            try {
                JSONObject(parametersSchemaJson)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid JSON schema for parameters: ${e.message}")
                )
            }

            if (!baseDir.exists()) baseDir.mkdirs()

            // Save script file to disk
            val fileExt = when (scriptType) {
                DynamicTool.ScriptType.JAVASCRIPT -> "js"
                DynamicTool.ScriptType.SHELL -> "sh"
                DynamicTool.ScriptType.MACRO_JSON -> "json"
            }
            val scriptFile = File(baseDir, "$cleanName.$fileExt")
            scriptFile.writeText(scriptCode)

            val newTool = DynamicTool(
                name = cleanName,
                description = description.trim(),
                parametersSchemaJson = parametersSchemaJson.trim(),
                scriptType = scriptType,
                scriptCode = scriptCode,
                version = 1,
                author = author
            )

            inMemoryCache[cleanName] = newTool
            persistRegistryLocked()
            Log.i(TAG, "Created dynamic tool '$cleanName' (version 1)")
            Result.success(newTool)
        }
    }

    /**
     * Edits an existing dynamic tool (updates code and/or schema, increments version).
     */
    suspend fun editTool(
        name: String,
        updatedScriptCode: String? = null,
        updatedDescription: String? = null,
        updatedParametersSchemaJson: String? = null
    ): Result<DynamicTool> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanName = name.trim().lowercase()
            val existing = inMemoryCache[cleanName]
                ?: return@withContext Result.failure(IllegalArgumentException("Dynamic tool '$cleanName' not found."))

            val newCode = updatedScriptCode ?: existing.scriptCode
            val newDesc = updatedDescription ?: existing.description
            val newSchema = updatedParametersSchemaJson ?: existing.parametersSchemaJson

            // Validate schema if updated
            if (updatedParametersSchemaJson != null) {
                try {
                    JSONObject(newSchema)
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid updated JSON schema: ${e.message}")
                    )
                }
            }

            if (!baseDir.exists()) baseDir.mkdirs()

            // Save script file to disk
            val fileExt = when (existing.scriptType) {
                DynamicTool.ScriptType.JAVASCRIPT -> "js"
                DynamicTool.ScriptType.SHELL -> "sh"
                DynamicTool.ScriptType.MACRO_JSON -> "json"
            }
            val scriptFile = File(baseDir, "$cleanName.$fileExt")
            scriptFile.writeText(newCode)

            val updatedTool = existing.copy(
                description = newDesc,
                parametersSchemaJson = newSchema,
                scriptCode = newCode,
                version = existing.version + 1,
                updatedAt = System.currentTimeMillis()
            )

            inMemoryCache[cleanName] = updatedTool
            persistRegistryLocked()
            Log.i(TAG, "Updated dynamic tool '$cleanName' to version ${updatedTool.version}")
            Result.success(updatedTool)
        }
    }

    /**
     * Deletes a dynamic tool and its associated file.
     */
    suspend fun deleteTool(name: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cleanName = name.trim().lowercase()
            val existing = inMemoryCache.remove(cleanName) ?: return@withContext false

            val fileExt = when (existing.scriptType) {
                DynamicTool.ScriptType.JAVASCRIPT -> "js"
                DynamicTool.ScriptType.SHELL -> "sh"
                DynamicTool.ScriptType.MACRO_JSON -> "json"
            }
            val scriptFile = File(baseDir, "$cleanName.$fileExt")
            if (scriptFile.exists()) scriptFile.delete()

            persistRegistryLocked()
            Log.i(TAG, "Deleted dynamic tool '$cleanName'")
            true
        }
    }

    /**
     * Records execution telemetry for self-healing diagnosis.
     */
    suspend fun recordExecution(name: String, success: Boolean, error: String? = null) = mutex.withLock {
        val cleanName = name.trim().lowercase()
        val existing = inMemoryCache[cleanName] ?: return@withLock
        val updated = existing.copy(
            lastExecutionSuccess = success,
            failureCount = if (success) 0 else existing.failureCount + 1,
            lastError = if (success) null else error,
            updatedAt = System.currentTimeMillis()
        )
        inMemoryCache[cleanName] = updated
        persistRegistryLocked()
    }

    /**
     * Exports all active dynamic tools as Gemini function declaration schemas.
     */
    suspend fun toGeminiSchemas(): List<JSONObject> = mutex.withLock {
        inMemoryCache.values.map { it.toGeminiSchema() }
    }
}
