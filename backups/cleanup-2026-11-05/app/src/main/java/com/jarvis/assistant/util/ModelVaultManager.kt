package com.jarvis.assistant.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class VaultItem(
    val id: String,
    val title: String,
    val category: String, // "orb", "live_companion", "arc_reactor", "custom_3d"
    val format: String,   // "builtin", "glb", "gltf", "obj", "stl"
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val isBuiltin: Boolean = false,
    val colorHex: String = "#22D3EE"
) {
    fun getFormattedSize(): String {
        if (sizeBytes <= 0) return "Built-in"
        val mb = sizeBytes / (1024f * 1024f)
        return if (mb >= 1.0f) String.format("%.1f MB", mb) else String.format("%d KB", sizeBytes / 1024)
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("category", category)
            put("format", format)
            put("fileName", fileName)
            put("sizeBytes", sizeBytes)
            put("createdAt", createdAt)
            put("isBuiltin", isBuiltin)
            put("colorHex", colorHex)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): VaultItem {
            return VaultItem(
                id = json.optString("id", ""),
                title = json.optString("title", "Untitled Model"),
                category = json.optString("category", "orb"),
                format = json.optString("format", "builtin"),
                fileName = json.optString("fileName", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                isBuiltin = json.optBoolean("isBuiltin", false),
                colorHex = json.optString("colorHex", "#22D3EE")
            )
        }
    }
}

object ModelVaultManager {

    private const val TAG = "ModelVaultManager"
    private const val PREF_KEY_VAULT_ITEMS = "key_model_vault_items_json"
    private const val PREF_KEY_ACTIVE_VAULT_ID = "key_active_vault_item_id"

    // Default built-in vault slots
    val BUILTIN_ORB = VaultItem(
        id = "builtin_classic_orb",
        title = "STARK Core Orb",
        category = "orb",
        format = "builtin",
        fileName = "",
        sizeBytes = 0L,
        createdAt = 0L,
        isBuiltin = true,
        colorHex = "#22D3EE"
    )

    val BUILTIN_HINATA_ANIME = VaultItem(
        id = "builtin_hinata_anime",
        title = "Hinata Hyuga (Anime Companion)",
        category = "hinata_anime",
        format = "builtin",
        fileName = "models/hinata.glb",
        sizeBytes = 11168028L,
        createdAt = 0L,
        isBuiltin = true,
        colorHex = "#A78BFA"
    )

    val BUILTIN_LIVE_COMPANION = VaultItem(
        id = "builtin_live_companion",
        title = "Jett (3D Fallback)",
        category = "ai_girl",
        format = "builtin",
        fileName = "models/jett.glb",
        sizeBytes = 2991116L,
        createdAt = 0L,
        isBuiltin = true,
        colorHex = "#22D3EE"
    )

    val BUILTIN_ARC_REACTOR = VaultItem(
        id = "builtin_arc_reactor",
        title = "Mark 50 Arc Reactor",
        category = "arc_reactor",
        format = "builtin",
        fileName = "",
        sizeBytes = 0L,
        createdAt = 0L,
        isBuiltin = true,
        colorHex = "#38BDF8"
    )

    val BUILTIN_MORA = VaultItem(
        id = "builtin_mora_cyber",
        title = "REDMAGIC Mora (Cyber Companion)",
        category = "mora_cyber",
        format = "builtin",
        fileName = "models/mora.glb",
        sizeBytes = 4196116L,
        createdAt = 0L,
        isBuiltin = true,
        colorHex = "#FF0055"
    )

    fun getVaultDir(context: Context): File {
        val dir = File(context.filesDir, "vault")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @Synchronized
    fun getVaultItems(context: Context): List<VaultItem> {
        migrateLegacyModelIfExists(context)
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val rawJson = prefs.getString(PREF_KEY_VAULT_ITEMS, null)

        val list = mutableListOf<VaultItem>()
        // Always include builtins
        list.add(BUILTIN_ORB)
        list.add(BUILTIN_HINATA_ANIME)
        list.add(BUILTIN_MORA)
        list.add(BUILTIN_LIVE_COMPANION)
        list.add(BUILTIN_ARC_REACTOR)

        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val item = VaultItem.fromJsonObject(obj)
                    // Verify file still exists on disk
                    val file = File(getVaultDir(context), item.fileName)
                    if (file.exists() && file.length() > 0) {
                        val fixedItem = if (item.title == "custom_3d_avatar") item.copy(title = "Jett (3D Model)") else item
                        list.add(fixedItem)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing vault items JSON", e)
            }
        }
        return list
    }

    @Synchronized
    private fun saveCustomItems(context: Context, items: List<VaultItem>) {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        items.filter { !it.isBuiltin }.forEach { array.put(it.toJsonObject()) }
        prefs.edit().putString(PREF_KEY_VAULT_ITEMS, array.toString()).apply()
    }

    fun getActiveVaultItemId(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val currentEntity = JarvisApp.instance.preferences.orbEntityType
        val saved = prefs.getString(PREF_KEY_ACTIVE_VAULT_ID, null)
        val allItems = getVaultItems(context)

        // Prioritize active entity type if set to a built-in companion
        if (currentEntity == "mora_cyber") return BUILTIN_MORA.id
        if (currentEntity == "hinata_anime") return BUILTIN_HINATA_ANIME.id
        if (currentEntity == "ai_girl") return BUILTIN_LIVE_COMPANION.id
        if (currentEntity == "arc_reactor") return BUILTIN_ARC_REACTOR.id
        if (currentEntity == "orb") return BUILTIN_ORB.id

        if (!saved.isNullOrBlank() && allItems.any { it.id == saved }) return saved

        return BUILTIN_HINATA_ANIME.id
    }

    fun setActiveVaultItemId(context: Context, id: String) {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_ACTIVE_VAULT_ID, id).apply()

        // Sync with AppPreferences orbEntityType
        val item = getVaultItems(context).find { it.id == id } ?: BUILTIN_ORB
        val appPrefs = JarvisApp.instance.preferences
        appPrefs.orbEntityType = item.category
    }

    fun getActiveVaultItem(context: Context): VaultItem {
        val activeId = getActiveVaultItemId(context)
        val allItems = getVaultItems(context)
        return allItems.find { it.id == activeId }
            ?: if (JarvisApp.instance.preferences.orbEntityType == "custom_3d") {
                allItems.filter { !it.isBuiltin }.lastOrNull() ?: BUILTIN_ORB
            } else {
                BUILTIN_ORB
            }
    }

    fun getActiveModelFile(context: Context): File? {
        val active = getActiveVaultItem(context)
        if (active.isBuiltin) {
            // Built-in models (e.g. Jett humanoid avatar) stream directly from assets
            return null
        }
        if (!active.isBuiltin && active.fileName.isNotBlank()) {
            val file = File(getVaultDir(context), active.fileName)
            if (file.exists() && file.length() > 0) return file
        }
        // Fallback: any custom model file in vault only for custom_3d mode
        if (JarvisApp.instance.preferences.orbEntityType == "custom_3d") {
            val allItems = getVaultItems(context)
            for (item in allItems.filter { !it.isBuiltin }.reversed()) {
                val file = File(getVaultDir(context), item.fileName)
                if (file.exists() && file.length() > 0) return file
            }
            // Fallback: legacy custom_3d_model files
            val formats = listOf("glb", "gltf", "obj", "stl")
            for (ext in formats) {
                val f = File(context.filesDir, "custom_3d_model.$ext")
                if (f.exists() && f.length() > 0) return f
            }
        }
        return null
    }

    fun importModelFromUri(context: Context, uri: Uri, customTitle: String? = null): VaultItem? {
        try {
            val (is3D, detectedExt) = CustomAvatarHelper.is3DModelFile(context, uri)
            val ext = if (is3D && detectedExt.isNotBlank()) detectedExt else "glb"

            val timestamp = System.currentTimeMillis()
            val id = "vault_model_$timestamp"
            val fileName = "model_$timestamp.$ext"
            val targetFile = File(getVaultDir(context), fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                return null
            }

            val derivedTitle = if (!customTitle.isNullOrBlank()) {
                customTitle
            } else {
                val realName = try {
                    var n: String? = null
                    if (uri.scheme == "content") {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                n = cursor.getString(0)
                            }
                        }
                    }
                    n ?: uri.lastPathSegment?.substringAfterLast("/")
                } catch (_: Throwable) { null }

                if (!realName.isNullOrBlank()) {
                    val clean = if (realName.contains(".")) realName.substringBeforeLast(".") else realName
                    clean.replace('_', ' ').replace('-', ' ').trim()
                } else {
                    "${ext.uppercase()} 3D Model #${getVaultItems(context).size}"
                }
            }

            val newItem = VaultItem(
                id = id,
                title = derivedTitle,
                category = "custom_3d",
                format = ext,
                fileName = fileName,
                sizeBytes = targetFile.length(),
                createdAt = timestamp,
                isBuiltin = false,
                colorHex = "#22D3EE"
            )

            val currentItems = getVaultItems(context).filter { !it.isBuiltin }.toMutableList()
            currentItems.add(newItem)
            saveCustomItems(context, currentItems)

            // Automatically set as active
            setActiveVaultItemId(context, newItem.id)
            return newItem
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model into vault", e)
            return null
        }
    }

    fun deleteVaultItem(context: Context, id: String): Boolean {
        val items = getVaultItems(context)
        val target = items.find { it.id == id } ?: return false
        if (target.isBuiltin) return false // Built-in items cannot be deleted

        // Delete underlying file
        try {
            val file = File(getVaultDir(context), target.fileName)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting vault file ${target.fileName}", e)
        }

        val remaining = items.filter { !it.isBuiltin && it.id != id }
        saveCustomItems(context, remaining)

        // If the deleted item was active, fallback to classic orb
        if (getActiveVaultItemId(context) == id) {
            setActiveVaultItemId(context, BUILTIN_ORB.id)
        }
        return true
    }

    private var hasMigratedLegacy = false

    private fun migrateLegacyModelIfExists(context: Context) {
        if (hasMigratedLegacy) return
        hasMigratedLegacy = true
        try {
            val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

            // Clean up obsolete 57.8MB unrigged sculpt if present in vault or prefs
            val rawJson = prefs.getString(PREF_KEY_VAULT_ITEMS, null)
            if (!rawJson.isNullOrBlank()) {
                try {
                    val array = JSONArray(rawJson)
                    val cleanList = mutableListOf<VaultItem>()
                    var modified = false
                    for (i in 0 until array.length()) {
                        val item = VaultItem.fromJsonObject(array.getJSONObject(i))
                        if (item.sizeBytes == 57862972L || item.fileName.startsWith("model_1792468691847") || item.title == "custom_3d_avatar") {
                            val staleFile = File(getVaultDir(context), item.fileName)
                            if (staleFile.exists()) {
                                staleFile.delete()
                                Log.i(TAG, "Removed obsolete 57.8MB unrigged sculpt file: ${item.fileName}")
                            }
                            modified = true
                        } else {
                            cleanList.add(item)
                        }
                    }
                    if (modified) {
                        saveCustomItems(context, cleanList)
                        val activeId = prefs.getString(PREF_KEY_ACTIVE_VAULT_ID, null)
                        if (activeId == "vault_model_1792468691847" || cleanList.none { it.id == activeId }) {
                            prefs.edit().putString(PREF_KEY_ACTIVE_VAULT_ID, BUILTIN_LIVE_COMPANION.id).apply()
                            JarvisApp.instance.preferences.orbEntityType = "ai_girl"
                            Log.i(TAG, "Migrated active avatar to BUILTIN_LIVE_COMPANION")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error cleaning stale vault items", e)
                }
                return
            }

            val formats = listOf("glb", "gltf", "obj", "stl")
            var legacyFile: File? = null
            for (ext in formats) {
                val f = File(context.filesDir, "custom_3d_model.$ext")
                if (f.exists() && f.length() > 0) {
                    legacyFile = f
                    break
                }
            }

            if (legacyFile != null && legacyFile.exists() && legacyFile.length() > 0) {
                val ext = legacyFile.extension
                val targetName = "model_legacy.$ext"
                val targetFile = File(getVaultDir(context), targetName)
                legacyFile.copyTo(targetFile, overwrite = true)

                val legacyItem = VaultItem(
                    id = "vault_model_legacy",
                    title = "Saved 3D Model",
                    category = "custom_3d",
                    format = ext,
                    fileName = targetName,
                    sizeBytes = targetFile.length(),
                    createdAt = legacyFile.lastModified(),
                    isBuiltin = false,
                    colorHex = "#38BDF8"
                )
                saveCustomItems(context, listOf(legacyItem))
                Log.i(TAG, "Migrated legacy 3D model into vault: $targetName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration error", e)
        }
    }
}
