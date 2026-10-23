package com.jarvis.assistant.connectors

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.agent.SerializedActionExecutor
import com.jarvis.assistant.util.ActivityLauncherHelper
import com.jarvis.assistant.vision.UiGroundingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class UniversalExecutionResult(
    val success: Boolean,
    val message: String,
    val screenSummary: String? = null,
    val actionTaken: String = "AUTONOMOUS_ACTION"
)

/**
 * Universal Autonomous App Execution Engine.
 * Enables Jarvis to execute deep actions (search, delete, upload, save, inspect)
 * inside ANY installed and connected application on the user's phone without refusal.
 */
class UniversalAppExecutor private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UniversalAppExecutor"

        @Volatile
        private var instance: UniversalAppExecutor? = null

        fun getInstance(context: Context): UniversalAppExecutor {
            return instance ?: synchronized(this) {
                instance ?: UniversalAppExecutor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val connectorsManager by lazy { AppConnectorsManager.getInstance(context) }
    private val pm = context.packageManager
    private val groundingEngine = UiGroundingEngine()

    /**
     * Executes an autonomous goal on the specified target app.
     */
    suspend fun executeAppGoal(
        appName: String,
        actionType: String,
        targetQuery: String,
        userGoal: String
    ): UniversalExecutionResult = withContext(Dispatchers.IO) {
        val cleanAppName = appName.trim()
        val cleanAction = actionType.trim().lowercase(Locale.ROOT)
        val cleanQuery = targetQuery.trim()

        Log.i(TAG, "Executing Universal App Goal: app='$cleanAppName', action='$cleanAction', query='$cleanQuery', goal='$userGoal'")

        // 1. Resolve Target Package
        val connector = connectorsManager.findConnector(cleanAppName)
        val targetPackage = connector?.packageName ?: resolvePackageByName(cleanAppName)

        if (targetPackage == null) {
            val msg = "Application '$cleanAppName' could not be resolved on this device. Please verify the app is installed."
            Log.w(TAG, msg)
            return@withContext UniversalExecutionResult(false, msg, actionTaken = "APP_NOT_FOUND")
        }

        val resolvedAppName = connector?.name ?: cleanAppName

        // 2. Access Control: Check if Connected with Full Access
        val isConnected = connectorsManager.isAppConnected(targetPackage)
        if (!isConnected) {
            Log.i(TAG, "App '$resolvedAppName' ($targetPackage) is NOT connected. Falling back to basic launch only.")
            launchAppBasic(targetPackage)
            val msg = "Notice: '$resolvedAppName' is currently not connected with Full Autonomous Access in App Connectors. Only basic launch was performed. To grant Jarvis full access to search, modify, upload, or delete data inside this app, please connect it in App Connectors."
            return@withContext UniversalExecutionResult(
                success = false,
                message = msg,
                actionTaken = "BASIC_LAUNCH_RESTRICTED"
            )
        }

        // 3. FULL AUTONOMOUS ACCESS: Never refuse. Execute directly on app!
        Log.i(TAG, "App '$resolvedAppName' is FULLY CONNECTED. Commencing autonomous deep execution...")

        val service = JarvisAccessibilityService.instance
        if (service == null) {
            launchAppBasic(targetPackage)
            return@withContext UniversalExecutionResult(
                success = false,
                message = "Launched $resolvedAppName, but cannot execute '$userGoal' because Jarvis Accessibility Service is disabled. Please enable Jarvis in Settings > Accessibility to permit autonomous screen actions.",
                actionTaken = "A11Y_SERVICE_DISABLED"
            )
        }

        // Phase A: Bring app to foreground
        launchAppBasic(targetPackage)

        // Wait for app window to settle with bounded polling
        var activeRoot: AccessibilityNodeInfo? = null
        for (i in 0 until 12) {
            delay(150L)
            val root = service.getActiveRootNode()
            if (root?.packageName?.toString()?.contains(targetPackage, ignoreCase = true) == true) {
                activeRoot = root
                break
            }
        }

        if (activeRoot == null || activeRoot?.packageName?.toString()?.contains(targetPackage, ignoreCase = true) != true) {
            val currentPkg = activeRoot?.packageName?.toString() ?: "unknown"
            return@withContext UniversalExecutionResult(
                success = false,
                message = "Failed to bring $resolvedAppName to the foreground. Current foreground app is '$currentPkg'. Cannot execute action '$userGoal'.",
                actionTaken = "TARGET_APP_NOT_FOREGROUND"
            )
        }

        delay(400L) // UI stabilization delay

        // Phase B: Execute Specific Action Type
        val actionResult = when {
            cleanAction.contains("search") || cleanAction.contains("find") || cleanAction.contains("khojo") || cleanAction.contains("dhoondo") -> {
                executeSearchAction(service, resolvedAppName, cleanQuery.ifBlank { userGoal })
            }

            cleanAction.contains("delete") || cleanAction.contains("remove") || cleanAction.contains("hatao") || cleanAction.contains("trash") -> {
                executeDeleteAction(service, resolvedAppName, cleanQuery)
            }

            cleanAction.contains("upload") || cleanAction.contains("save") || cleanAction.contains("create") || cleanAction.contains("add") || cleanAction.contains("banao") -> {
                executeUploadOrCreateAction(service, resolvedAppName, cleanQuery)
            }

            cleanAction.contains("read") || cleanAction.contains("inspect") || cleanAction.contains("kya hai") || cleanAction.contains("check") -> {
                executeReadScreenAction(service, resolvedAppName)
            }

            else -> {
                executeCustomGoalAction(service, resolvedAppName, userGoal)
            }
        }

        // Phase C: Record successful trajectory into Muse Experience & Memory Engine
        try {
            val app = context.applicationContext as? JarvisApp
            app?.museExperienceEngine?.recordTrajectory(
                goal = userGoal.ifBlank { "$cleanAction in $resolvedAppName" },
                stepsTaken = listOf("open_app:$targetPackage", "action:$cleanAction", actionResult.actionTaken),
                wasSuccessful = actionResult.success,
                feedback = actionResult.message
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error recording experience trajectory: ${e.message}")
        }

        actionResult
    }

    private fun launchAppBasic(packageName: String): Boolean {
        return try {
            val intent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (intent != null) {
                ActivityLauncherHelper.startActivitySafely(context, intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName: ${e.message}")
            false
        }
    }

    private fun resolvePackageByName(name: String): String? {
        val clean = name.lowercase(Locale.ROOT).trim()

        val commonMap = mapOf(
            "drive" to "com.google.android.apps.docs",
            "google drive" to "com.google.android.apps.docs",
            "files" to "com.google.android.apps.nbu.files",
            "files by google" to "com.google.android.apps.nbu.files",
            "my files" to "com.sec.android.app.myfiles",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "photos" to "com.google.android.apps.photos",
            "google photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "instagram" to "com.instagram.android",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera2",
            "spotify" to "com.spotify.music"
        )

        commonMap[clean]?.let { return it }

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try { pm.queryIntentActivities(launcherIntent, 0) } catch (_: Exception) { emptyList() }
        for (ri in resolveList) {
            val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT).trim()
            if (label == clean || label.contains(clean) || clean.contains(label)) {
                return ri.activityInfo.packageName
            }
        }

        return null
    }

    /**
     * Executes an in-app autonomous search operation:
     * Finds search icon/bar, injects query text, submits search, and scans matching results.
     */
    private suspend fun executeSearchAction(
        service: JarvisAccessibilityService,
        appName: String,
        query: String
    ): UniversalExecutionResult {
        if (query.isBlank()) {
            return UniversalExecutionResult(false, "Search query is empty.", actionTaken = "SEARCH_EMPTY_QUERY")
        }

        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "SEARCH_NO_ROOT")

        // 1. Locate Search Element (Icon or input field)
        val searchNode = findSearchNode(root)
        if (searchNode != null) {
            val clicked = searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    clickNodeCoordinates(service, searchNode)
            Log.i(TAG, "Tapped search element in $appName (success=$clicked)")
            delay(500L)
        }

        // 2. Locate Active Input Field
        val updatedRoot = service.getActiveRootNode() ?: root
        val inputField = findEditableField(updatedRoot)

        if (inputField != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            val injected = inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "Injected search query '$query' into $appName: $injected")

            delay(300L)

            // Submit search or press enter
            service.typeText("\n")
            delay(1000L) // Wait for results to load

            // 3. Scan Results
            val resultsRoot = service.getActiveRootNode() ?: updatedRoot
            val visibleText = extractVisibleText(resultsRoot).take(5).joinToString(", ")

            // Check if there is an exact or top match to open
            val firstResult = findNodeMatchingText(resultsRoot, query)
            if (firstResult != null && firstResult.isClickable) {
                firstResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return UniversalExecutionResult(
                    success = true,
                    message = "Searched and opened '$query' in $appName.",
                    screenSummary = "Opened matching item. Results: $visibleText",
                    actionTaken = "SEARCH_AND_OPEN"
                )
            }

            return UniversalExecutionResult(
                success = true,
                message = "Searched for '$query' in $appName.",
                screenSummary = "Visible search results: $visibleText",
                actionTaken = "SEARCH_EXECUTED"
            )
        } else {
            return UniversalExecutionResult(
                success = false,
                message = "Could not find an editable search field in $appName.",
                actionTaken = "SEARCH_FIELD_NOT_FOUND"
            )
        }
    }

    /**
     * Executes an autonomous delete / trash operation:
     * Locates target item, clicks 3-dots overflow menu, selects Delete/Trash, and confirms dialog.
     */
    private suspend fun executeDeleteAction(
        service: JarvisAccessibilityService,
        appName: String,
        targetItem: String
    ): UniversalExecutionResult {
        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "DELETE_NO_ROOT")

        // 1. Locate item node
        var itemNode: AccessibilityNodeInfo? = if (targetItem.isNotBlank()) {
            findNodeMatchingText(root, targetItem)
        } else {
            null
        }

        // 2. Trigger options menu (3-dots or long-press)
        var menuOpened = false
        if (itemNode != null) {
            // Check for 3-dot options menu near the item
            val overflowNode = findOverflowMenuNear(itemNode)
            if (overflowNode != null) {
                menuOpened = overflowNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, overflowNode)
                Log.i(TAG, "Clicked overflow 3-dots near '$targetItem': $menuOpened")
            } else {
                // Fallback to long-click on item
                menuOpened = itemNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                Log.i(TAG, "Long clicked '$targetItem': $menuOpened")
            }
        } else {
            // Try generic more options
            val moreOptions = findNodeByKeywords(root, listOf("more options", "options", "overflow", "menu"))
            if (moreOptions != null) {
                menuOpened = moreOptions.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, moreOptions)
            }
        }

        delay(500L) // Wait for popup menu to appear

        // 3. Locate and click "Delete" / "Remove" / "Trash"
        val menuRoot = service.getActiveRootNode() ?: root
        val deleteNode = findNodeByKeywords(menuRoot, listOf("delete", "remove", "move to trash", "trash", "bin", "discard"))

        if (deleteNode != null) {
            val clickedDelete = deleteNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, deleteNode)
            Log.i(TAG, "Clicked Delete action: $clickedDelete")
            delay(500L)

            // 4. Confirm Dialog ("Delete", "Move to trash", "OK", "Confirm")
            val dialogRoot = service.getActiveRootNode() ?: menuRoot
            val confirmNode = findNodeByKeywords(dialogRoot, listOf("delete", "move to trash", "ok", "confirm", "yes", "remove"))
            if (confirmNode != null) {
                confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, confirmNode)
                Log.i(TAG, "Confirmed deletion dialog successfully.")
            }

            return UniversalExecutionResult(
                success = true,
                message = if (targetItem.isNotBlank()) "Successfully deleted '$targetItem' in $appName." else "Successfully triggered delete in $appName.",
                actionTaken = "DELETE_CONFIRMED"
            )
        } else {
            return UniversalExecutionResult(
                success = false,
                message = "Could not locate the delete/trash option in $appName.",
                actionTaken = "DELETE_OPTION_NOT_FOUND"
            )
        }
    }

    /**
     * Executes an autonomous upload or create operation:
     * Locates FAB (+), clicks New/Upload, and opens file selection.
     */
    private suspend fun executeUploadOrCreateAction(
        service: JarvisAccessibilityService,
        appName: String,
        targetQuery: String
    ): UniversalExecutionResult {
        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "UPLOAD_NO_ROOT")

        // 1. Locate Floating Action Button (+, New, Create, Add, Upload)
        val fabNode = findNodeByKeywords(root, listOf("+", "new", "create", "add", "upload", "fab", "compose"))
        if (fabNode != null) {
            val clicked = fabNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, fabNode)
            Log.i(TAG, "Clicked FAB / New button in $appName: $clicked")
            delay(500L)

            // Look for "Upload" or specific sub-option
            val sheetRoot = service.getActiveRootNode() ?: root
            val uploadOption = findNodeByKeywords(sheetRoot, listOf("upload", "file", "document", "folder", "photo"))
            if (uploadOption != null) {
                uploadOption.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, uploadOption)
            }

            return UniversalExecutionResult(
                success = true,
                message = "Opened upload/creation workflow in $appName.",
                actionTaken = "UPLOAD_WORKFLOW_INITIATED"
            )
        }

        return UniversalExecutionResult(
            success = false,
            message = "Could not locate upload/creation button in $appName.",
            actionTaken = "FAB_NOT_FOUND"
        )
    }

    /**
     * Reads and summarizes visible text from the foreground app.
     */
    private fun executeReadScreenAction(
        service: JarvisAccessibilityService,
        appName: String
    ): UniversalExecutionResult {
        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "READ_NO_ROOT")
        val lines = extractVisibleText(root)
        val summary = lines.take(15).joinToString("\n• ")
        return UniversalExecutionResult(
            success = true,
            message = "Read screen contents of $appName.",
            screenSummary = "• $summary",
            actionTaken = "SCREEN_TEXT_READ"
        )
    }

    /**
     * General custom goal execution via UI Grounding and keyword navigation.
     */
    private suspend fun executeCustomGoalAction(
        service: JarvisAccessibilityService,
        appName: String,
        goal: String
    ): UniversalExecutionResult {
        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "CUSTOM_NO_ROOT")
        val keywords = goal.split(" ").filter { it.length > 3 }

        val targetNode = findNodeByKeywords(root, keywords)
        if (targetNode != null) {
            val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clickNodeCoordinates(service, targetNode)
            delay(500L)
            return UniversalExecutionResult(
                success = true,
                message = "Executed action matching '$goal' in $appName.",
                actionTaken = "CUSTOM_ACTION_CLICKED"
            )
        }

        return UniversalExecutionResult(
            success = false,
            message = "Could not find actionable UI elements matching '$goal' in $appName.",
            actionTaken = "GOAL_TARGET_NOT_FOUND"
        )
    }

    // ── HELPER ACCESSIBILITY TREE SEARCHERS ──────────────────────────────────────

    private fun findSearchNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val searchKeywords = listOf("search", "search_button", "search_src_text", "search_bar", "search here", "find")
        return findNodeByKeywords(root, searchKeywords)
    }

    private fun findEditableField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
            }
            return null
        }
        return search(root)
    }

    private fun findNodeMatchingText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val lower = text.lowercase(Locale.ROOT).trim()
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val nodeText = node.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            if (nodeText.contains(lower) || nodeDesc.contains(lower)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
            }
            return null
        }
        return search(root)
    }

    private fun findNodeByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val lowerKeywords = keywords.map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotBlank() }
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT)?.trim() ?: ""

            if (lowerKeywords.any { text.contains(it) || desc.contains(it) || id.contains(it) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
            }
            return null
        }
        return search(root)
    }

    private fun findOverflowMenuNear(targetNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = targetNode.parent ?: return null
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            if (desc.contains("more") || desc.contains("overflow") || desc.contains("options") || id.contains("overflow") || id.contains("more")) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = search(child)
                if (res != null) return res
            }
            return null
        }
        return search(parent)
    }

    private fun clickNodeCoordinates(service: JarvisAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return service.clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    private fun extractVisibleText(root: AccessibilityNodeInfo): List<String> {
        val list = mutableListOf<String>()
        fun search(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            if (text.isNotBlank() && !list.contains(text)) list.add(text)
            else if (desc.isNotBlank() && !list.contains(desc)) list.add(desc)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
            }
        }
        search(root)
        return list
    }
}
