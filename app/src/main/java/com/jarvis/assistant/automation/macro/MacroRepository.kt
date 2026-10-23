package com.jarvis.assistant.automation.macro

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

class MacroRepository(private val context: Context) {
    companion object {
        private const val TAG = "MacroRepository"
        private const val BUILTIN_PATH = "macros/builtin_macros.json"
        private const val CUSTOM_MACROS_FILE = "custom_macros.json"
    }

    private val gson = Gson()
    private val cachedMacros = mutableListOf<Macro>()

    init {
        loadAllMacros()
    }

    @Synchronized
    fun loadAllMacros(): List<Macro> {
        cachedMacros.clear()
        // 1. Load built-ins from assets
        try {
            context.assets.open(BUILTIN_PATH).bufferedReader().use { reader ->
                val catalog = gson.fromJson(reader, MacroCatalog::class.java)
                catalog?.macros?.let { cachedMacros.addAll(it) }
                Log.i(TAG, "Loaded ${catalog?.macros?.size ?: 0} builtin macros from assets.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load builtin_macros.json: ${e.message}", e)
        }

        // 2. Load custom user macros from internal storage
        try {
            val customFile = File(context.filesDir, CUSTOM_MACROS_FILE)
            if (customFile.exists()) {
                val customJson = customFile.readText()
                val customCatalog = gson.fromJson(customJson, MacroCatalog::class.java)
                customCatalog?.macros?.let { cachedMacros.addAll(it) }
                Log.i(TAG, "Loaded ${customCatalog?.macros?.size ?: 0} custom macros.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom macros: ${e.message}", e)
        }

        return cachedMacros
    }

    fun getAllMacros(): List<Macro> = cachedMacros.toList()

    fun findMacroByName(name: String): Macro? {
        val query = name.trim().lowercase()
        return cachedMacros.firstOrNull { it.name.trim().lowercase() == query }
            ?: cachedMacros.firstOrNull { it.name.lowercase().contains(query) || query.contains(it.name.lowercase()) }
    }

    fun findMacroByIntent(intentUtterance: String): Macro? {
        val lower = intentUtterance.lowercase().trim()
        return cachedMacros.firstOrNull { m ->
            val mIntent = m.intent.lowercase()
            val mName = m.name.lowercase()
            lower.contains(mName) || lower.contains(mIntent) || mIntent.contains(lower)
        }
    }

    @Synchronized
    fun saveCustomMacro(macro: Macro) {
        cachedMacros.removeAll { it.name.equals(macro.name, ignoreCase = true) }
        cachedMacros.add(macro)

        try {
            val customFile = File(context.filesDir, CUSTOM_MACROS_FILE)
            val customOnly = cachedMacros.filter { it !in getBuiltinMacros() }
            val catalog = MacroCatalog(version = 1, macros = customOnly)
            customFile.writeText(gson.toJson(catalog))
            Log.i(TAG, "Saved custom macro '${macro.name}'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save custom macro: ${e.message}", e)
        }
    }

    private fun getBuiltinMacros(): List<Macro> {
        return try {
            context.assets.open(BUILTIN_PATH).bufferedReader().use { reader ->
                gson.fromJson(reader, MacroCatalog::class.java)?.macros ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
