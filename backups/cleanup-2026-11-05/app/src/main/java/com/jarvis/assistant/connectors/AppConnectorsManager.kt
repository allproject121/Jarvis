package com.jarvis.assistant.connectors

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.ai.learning.HabitPatternProfiler
import com.jarvis.assistant.ai.memory.VectorMemoryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class ConnectorStatus(val label: String, val colorHex: String) {
    OPERATIONAL("OPERATIONAL", "#4ADE80"),
    AUTHENTICATED("AUTHENTICATED", "#22D3EE"),
    CONNECTED("CONNECTED", "#38BDF8"),
    INSTALLED("INSTALLED", "#94A3B8"),
    AVAILABLE("AVAILABLE", "#A78BFA"),
    DISCONNECTED("DISCONNECTED", "#64748B")
}

data class AppConnector(
    val id: String,
    val name: String,
    val packageName: String,
    val description: String,
    val category: String = "Application",
    val isInstalled: Boolean,
    val isExternal: Boolean = false,
    var isConnected: Boolean = false,
    var lastSyncTimestamp: Long = 0L,
    var itemsIndexedCount: Int = 0,
    var status: ConnectorStatus = ConnectorStatus.DISCONNECTED
)

/**
 * Universal App Connectors & Integrations Manager.
 * Orchestrates deep access, full phone app discovery, data ingestion into Vector Memory,
 * and autonomous tool execution for all installed applications on the device.
 */
class AppConnectorsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AppConnectorsManager"
        private const val PREFS_NAME = "jarvis_app_connectors_prefs"
        private const val KEY_EXTERNAL_CONNECTORS = "external_connectors_json"
        private const val KEY_CONNECTED_PACKAGES = "connected_packages_set"

        /** packageName/id prefix of the token-based service connector rows (see serviceConnectors()). */
        const val MAYA_SERVICE_PREFIX = "maya:"

        /** Honest description of what a generic app connection actually allows. */
        const val ACCESSIBILITY_CAPABILITY =
            "Jarvis can open this app and tap/type on its screen via Accessibility; app-specific actions depend on the app's layout."

        @Volatile
        private var instance: AppConnectorsManager? = null

        fun getInstance(context: Context): AppConnectorsManager {
            return instance ?: synchronized(this) {
                instance ?: AppConnectorsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun computeConnectorStatus(conn: AppConnector): ConnectorStatus {
        if (conn.packageName.startsWith(MAYA_SERVICE_PREFIX)) {
            val s = com.jarvis.assistant.maya.connectors.ConnectorService.fromId(conn.packageName.removePrefix(MAYA_SERVICE_PREFIX))
            val linked = s != null && com.jarvis.assistant.maya.connectors.ConnectorSecrets.isLinked(context, s)
            val err = s != null && com.jarvis.assistant.maya.connectors.ConnectorSecrets.lastStatus(context, s)?.startsWith("error:") == true
            return when {
                linked && !err -> ConnectorStatus.AUTHENTICATED
                linked -> ConnectorStatus.CONNECTED
                else -> ConnectorStatus.AVAILABLE
            }
        }
        if (!conn.isInstalled && conn.isExternal) return ConnectorStatus.AVAILABLE
        if (!conn.isConnected) return if (conn.isInstalled) ConnectorStatus.INSTALLED else ConnectorStatus.DISCONNECTED
        val hasPermission = when (conn.packageName) {
            "phone" -> androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            "contacts" -> androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            "com.whatsapp", "com.google.android.youtube", "com.android.chrome" -> com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.isRunning()
            "com.android.camera2" -> androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else -> true
        }
        return if (hasPermission) ConnectorStatus.OPERATIONAL else ConnectorStatus.AUTHENTICATED
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val pm: PackageManager = context.packageManager

    private val _connectorsFlow = MutableStateFlow<List<AppConnector>>(emptyList())
    val connectorsFlow: StateFlow<List<AppConnector>> = _connectorsFlow.asStateFlow()

    init {
        refreshConnectorsList()
    }

    fun getConnectors(): List<AppConnector> = _connectorsFlow.value

    /**
     * Dynamically discovers ALL installed applications on the user's phone,
     * categorizes them, extracts their capability profile, and checks their connection status.
     */
    fun refreshConnectorsList() {
        val connectedPackages = prefs.getStringSet(KEY_CONNECTED_PACKAGES, null) ?: setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.whatsapp",
            "com.google.android.youtube",
            "com.android.chrome",
            "com.google.android.apps.docs",
            "com.google.android.apps.nbu.files"
        )

        val list = mutableListOf<AppConnector>()
        val seenPackages = mutableSetOf<String>()

        // 1. Core System Anchors (Telephony, Contacts, System Settings)
        val coreSystemServices = listOf(
            Triple("phone", "Phone Dialer & Call Logs", "Auto-analyzes call logs, frequency, recency & dials numbers without prompts"),
            Triple("contacts", "Contacts & Address Book", "Autonomous contact matching, profile retrieval & frequency disambiguation"),
            Triple("com.android.settings", "System Settings", "Volume, Bluetooth, Wi-Fi, Display, Battery & device toggles"),
            Triple("com.android.camera2", "Camera & Vision", "Front-camera security capture, spatial scene recognition & barcode scanning")
        )

        for ((pkg, name, desc) in coreSystemServices) {
            val isConn = connectedPackages.contains(pkg)
            val (category, _) = getAppCategoryAndCapability(pkg, name)
            val c1 = AppConnector(
                id = pkg,
                name = name,
                packageName = pkg,
                description = desc,
                category = category,
                isInstalled = true,
                isExternal = false,
                isConnected = isConn
            )
            c1.status = computeConnectorStatus(c1)
            list.add(c1)
            seenPackages.add(pkg)
        }

        // 2. Discover ALL Installed Applications on Device via Launcher Intent
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveList = pm.queryIntentActivities(launcherIntent, 0)
            val currentAppPkg = context.packageName

            for (ri in resolveList) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == currentAppPkg || seenPackages.contains(pkg)) continue

                val appName = try {
                    ri.loadLabel(pm).toString().trim().ifBlank { pkg }
                } catch (_: Exception) {
                    pkg
                }

                val (category, capabilityDesc) = getAppCategoryAndCapability(pkg, appName)
                val isConn = connectedPackages.contains(pkg)

                val c2 = AppConnector(
                    id = pkg,
                    name = appName,
                    packageName = pkg,
                    description = capabilityDesc,
                    category = category,
                    isInstalled = true,
                    isExternal = false,
                    isConnected = isConn
                )
                c2.status = computeConnectorStatus(c2)
                list.add(c2)
                seenPackages.add(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating installed apps: ${e.message}", e)
        }

        // 3. Load user-added external connectors / custom plugins
        val extJson = prefs.getString(KEY_EXTERNAL_CONNECTORS, null)
        if (!extJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<AppConnector>>() {}.type
                val externals: List<AppConnector> = gson.fromJson(extJson, type) ?: emptyList()
                for (ext in externals) {
                    val isInst = isPackageInstalled(ext.packageName)
                    val isConn = connectedPackages.contains(ext.packageName) || ext.isConnected
                    list.add(ext.copy(isInstalled = isInst, isConnected = isConn))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading external connectors: ${e.message}")
            }
        }

        // 4. Maya-style cloud service connectors (token-linked; managed in ConnectorsActivity)
        list.addAll(serviceConnectors())

        // Sort: Connected apps first, then alphabetically by name
        list.sortWith(compareByDescending<AppConnector> { it.isConnected }.thenBy { it.name.lowercase(Locale.ROOT) })

        _connectorsFlow.value = list
    }

    /**
     * Rows for the token-based service connectors (Notion, Linear, Todoist, GitLab, Drive, Vercel,
     * Telegram, GitHub). Their packageName is "maya:<id>"; tapping one opens
     * [com.jarvis.assistant.maya.connectors.ConnectorsActivity] instead of toggling.
     */
    private fun serviceConnectors(): List<AppConnector> {
        return try {
            com.jarvis.assistant.maya.connectors.ConnectorService.entries.map { s ->
                val linked = com.jarvis.assistant.maya.connectors.ConnectorSecrets.isLinked(context, s)
                val lastError = com.jarvis.assistant.maya.connectors.ConnectorSecrets.lastStatus(context, s)?.startsWith("error:") == true
                AppConnector(
                    id = MAYA_SERVICE_PREFIX + s.id,
                    name = s.label,
                    packageName = MAYA_SERVICE_PREFIX + s.id,
                    description = s.description + if (linked) "" else " Tap to link a token.",
                    category = "Cloud Service",
                    isInstalled = true,
                    isExternal = false,
                    isConnected = linked,
                    status = when {
                        linked && !lastError -> ConnectorStatus.AUTHENTICATED
                        linked -> ConnectorStatus.CONNECTED
                        else -> ConnectorStatus.AVAILABLE
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service connectors unavailable: ${e.message}")
            emptyList()
        }
    }

    fun isServiceConnector(conn: AppConnector): Boolean = conn.packageName.startsWith(MAYA_SERVICE_PREFIX)

    private fun isPackageInstalled(pkg: String): Boolean {
        if (pkg == "phone" || pkg == "contacts") return true
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Infers the app category from package and app name; the capability text is the same honest
     * Accessibility-based description for every generic app.
     */
    private fun getAppCategoryAndCapability(packageName: String, appName: String): Pair<String, String> {
        val lowerPkg = packageName.lowercase(Locale.ROOT)
        val lowerName = appName.lowercase(Locale.ROOT)
        return when {
            lowerPkg.contains("docs") || lowerPkg.contains("drive") || lowerName.contains("drive") || lowerName.contains("cloud") || lowerPkg.contains("dropbox") || lowerPkg.contains("onedrive") -> {
                Pair("Cloud & Documents", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("nbu.files") || lowerPkg.contains("filemanager") || lowerPkg.contains("files") || lowerName.contains("files") || lowerName.contains("file manager") -> {
                Pair("File System", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("whatsapp") || lowerPkg.contains("telegram") || lowerPkg.contains("signal") || lowerPkg.contains("messages") || lowerName.contains("whatsapp") || lowerName.contains("telegram") || lowerName.contains("chat") -> {
                Pair("Communication", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("gm") || lowerPkg.contains("mail") || lowerPkg.contains("outlook") || lowerName.contains("gmail") || lowerName.contains("mail") || lowerName.contains("email") -> {
                Pair("Email & Mail", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("photos") || lowerPkg.contains("gallery") || lowerName.contains("photos") || lowerName.contains("gallery") -> {
                Pair("Media & Gallery", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("youtube") || lowerPkg.contains("spotify") || lowerPkg.contains("music") || lowerPkg.contains("netflix") || lowerPkg.contains("primevideo") || lowerName.contains("youtube") || lowerName.contains("spotify") || lowerName.contains("music") -> {
                Pair("Streaming & Media", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("chrome") || lowerPkg.contains("browser") || lowerPkg.contains("firefox") || lowerPkg.contains("brave") || lowerName.contains("chrome") || lowerName.contains("browser") -> {
                Pair("Web Browser", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("dialer") || lowerPkg.contains("phone") || lowerName.contains("phone") || lowerName.contains("call") -> {
                Pair("Telephony", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("contacts") || lowerName.contains("contacts") || lowerName.contains("people") -> {
                Pair("Address Book", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("settings") || lowerName.contains("settings") -> {
                Pair("System Settings", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("camera") || lowerName.contains("camera") -> {
                Pair("Camera & Vision", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("instagram") || lowerPkg.contains("facebook") || lowerPkg.contains("twitter") || lowerPkg.contains("tiktok") || lowerName.contains("instagram") || lowerName.contains("facebook") || lowerName.contains("twitter") || lowerName.contains("reddit") -> {
                Pair("Social Network", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("maps") || lowerName.contains("maps") || lowerName.contains("navigation") -> {
                Pair("Navigation & Maps", ACCESSIBILITY_CAPABILITY)
            }
            lowerPkg.contains("pay") || lowerPkg.contains("wallet") || lowerPkg.contains("bank") || lowerName.contains("pay") || lowerName.contains("upi") -> {
                Pair("Finance & Payments", ACCESSIBILITY_CAPABILITY)
            }
            else -> {
                Pair("Application", ACCESSIBILITY_CAPABILITY)
            }
        }
    }

    /**
     * Checks if an app is connected with Full Autonomous Access.
     */
    fun isAppConnected(appNameOrPackage: String): Boolean {
        val clean = appNameOrPackage.trim().lowercase(Locale.ROOT)
        val connected = prefs.getStringSet(KEY_CONNECTED_PACKAGES, null) ?: emptySet()
        if (connected.contains(clean)) return true

        // Match against known connectors list
        val connectors = _connectorsFlow.value
        val matched = connectors.firstOrNull {
            it.packageName.equals(clean, ignoreCase = true) ||
            it.name.equals(clean, ignoreCase = true) ||
            it.id.equals(clean, ignoreCase = true) ||
            clean.contains(it.name.lowercase(Locale.ROOT)) ||
            it.name.lowercase(Locale.ROOT).contains(clean) ||
            it.packageName.lowercase(Locale.ROOT).contains(clean)
        }
        return matched?.isConnected == true
    }

    /**
     * Finds the connector for a given app name or package.
     */
    fun findConnector(appNameOrPackage: String): AppConnector? {
        val clean = appNameOrPackage.trim().lowercase(Locale.ROOT)
        val connectors = _connectorsFlow.value
        return connectors.firstOrNull {
            it.packageName.equals(clean, ignoreCase = true) ||
            it.name.equals(clean, ignoreCase = true) ||
            it.id.equals(clean, ignoreCase = true) ||
            clean.contains(it.name.lowercase(Locale.ROOT)) ||
            it.name.lowercase(Locale.ROOT).contains(clean) ||
            it.packageName.lowercase(Locale.ROOT).contains(clean)
        }
    }

    /**
     * Connects ALL installed applications at once with Full Access.
     */
    fun connectAllApps(scope: CoroutineScope) {
        val all = _connectorsFlow.value
        val newSet = all.map { it.packageName }.toMutableSet()
        newSet.add("phone")
        newSet.add("contacts")
        prefs.edit().putStringSet(KEY_CONNECTED_PACKAGES, newSet).apply()
        refreshConnectorsList()

        scope.launch(Dispatchers.IO) {
            for (c in all) {
                syncAppDataToMemory(c.id)
            }
        }
    }

    /**
     * Disconnects all applications.
     */
    fun disconnectAllApps() {
        prefs.edit().putStringSet(KEY_CONNECTED_PACKAGES, emptySet()).apply()
        refreshConnectorsList()
    }

    /**
     * Toggles the connection state of an app.
     * When connected, automatically indexes metadata into Vector Semantic Memory to train Jarvis.
     */
    fun setConnectorEnabled(connectorId: String, enabled: Boolean, scope: CoroutineScope) {
        val currentSet = prefs.getStringSet(KEY_CONNECTED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            currentSet.add(connectorId)
        } else {
            currentSet.remove(connectorId)
        }
        prefs.edit().putStringSet(KEY_CONNECTED_PACKAGES, currentSet).apply()

        refreshConnectorsList()

        if (enabled) {
            scope.launch(Dispatchers.IO) {
                syncAppDataToMemory(connectorId)
            }
        }
    }

    /**
     * Registers a new custom/external app or plugin connector.
     */
    fun addExternalConnector(
        name: String,
        packageNameOrEndpoint: String,
        description: String
    ) {
        val newExt = AppConnector(
            id = "ext_${System.currentTimeMillis()}",
            name = name.trim(),
            packageName = packageNameOrEndpoint.trim(),
            description = description.trim(),
            category = "External Plugin",
            isInstalled = isPackageInstalled(packageNameOrEndpoint.trim()),
            isExternal = true,
            isConnected = true
        )

        val currentList = getExternalConnectors().toMutableList()
        currentList.add(newExt)
        saveExternalConnectors(currentList)

        // Mark connected
        val currentSet = prefs.getStringSet(KEY_CONNECTED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(newExt.packageName)
        prefs.edit().putStringSet(KEY_CONNECTED_PACKAGES, currentSet).apply()

        refreshConnectorsList()
    }

    fun removeExternalConnector(id: String) {
        val currentList = getExternalConnectors().toMutableList()
        currentList.removeAll { it.id == id }
        saveExternalConnectors(currentList)
        refreshConnectorsList()
    }

    private fun getExternalConnectors(): List<AppConnector> {
        val extJson = prefs.getString(KEY_EXTERNAL_CONNECTORS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppConnector>>() {}.type
            gson.fromJson(extJson, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveExternalConnectors(list: List<AppConnector>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_EXTERNAL_CONNECTORS, json).apply()
    }

    /**
     * Ingests app capabilities, top contacts, and call patterns into Vector Memory and Habit Profiler.
     */
    suspend fun syncAppDataToMemory(connectorId: String) = withContext(Dispatchers.IO) {
        val memoryEngine = VectorMemoryEngine.getInstance(context, CoroutineScope(Dispatchers.IO))
        val habitProfiler = HabitPatternProfiler.getInstance(context)

        val connector = findConnector(connectorId)

        when (connectorId) {
            "phone", "contacts" -> {
                Log.i(TAG, "Ingesting phone contacts and call log patterns into Vector Memory...")
                var count = 0
                try {
                    val resolver = context.contentResolver
                    val cursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 100"
                    )

                    cursor?.use {
                        val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (it.moveToNext()) {
                            val name = it.getString(nameIdx) ?: continue
                            val num = it.getString(numIdx) ?: continue
                            if (name.isNotBlank() && num.isNotBlank()) {
                                memoryEngine.indexMemory(
                                    id = System.currentTimeMillis() + count,
                                    text = "Contact record: $name has phone number $num",
                                    category = "contacts_index"
                                )
                                count++
                            }
                        }
                    }
                    Log.i(TAG, "Successfully indexed $count contacts into Vector Memory Core.")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not index contacts: ${e.message}")
                }
            }

            else -> {
                val appLabel = connector?.name ?: connectorId
                val appDesc = connector?.description ?: ACCESSIBILITY_CAPABILITY
                habitProfiler.recordAction("app_connected_$connectorId")
                memoryEngine.indexMemory(
                    id = System.currentTimeMillis(),
                    text = "[CONNECTED APP CAPABILITY]: App '$appLabel' (package: $connectorId) is connected. $appDesc",
                    category = "app_connector_capability"
                )
            }
        }
    }
}
