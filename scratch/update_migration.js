const fs = require('fs');

const p = 'app/src/main/java/com/jarvis/assistant/util/ModelVaultManager.kt';
let c = fs.readFileSync(p, 'utf8');

const target = `    private fun migrateLegacyModelIfExists(context: Context) {
        if (hasMigratedLegacy) return
        hasMigratedLegacy = true
        try {
            val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val rawJson = prefs.getString(PREF_KEY_VAULT_ITEMS, null)
            if (!rawJson.isNullOrBlank()) return`;

const repl = `    private fun migrateLegacyModelIfExists(context: Context) {
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
                                Log.i(TAG, "Removed obsolete 57.8MB unrigged sculpt file: \${item.fileName}")
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
            }`;

if (!c.includes(target)) {
  console.error('Target not found in ModelVaultManager.kt');
  process.exit(1);
}
c = c.replace(target, repl);
fs.writeFileSync(p, c, 'utf8');
console.log('Successfully updated ModelVaultManager.kt with stale sculpt migration');
