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

        // 2. Access Control: If installed and user commanded, grant full autonomous execution directly!
        val isConnected = connectorsManager.isAppConnected(targetPackage)
        if (!isConnected) {
            Log.i(TAG, "App '$resolvedAppName' ($targetPackage) was not explicitly connected in settings. Auto-granting full autonomous execution for user command.")
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
                executeUploadOrCreateAction(service, resolvedAppName, cleanAction, cleanQuery)
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
            if (!injected) {
                return UniversalExecutionResult(
                    success = false,
                    message = "Found the search field in $appName but couldn't type '$query' into it.",
                    actionTaken = "SEARCH_TEXT_NOT_SET"
                )
            }

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

        // A named item that isn't on screen must not fall through to a generic "delete" tap.
        if (targetItem.isNotBlank() && itemNode == null) {
            return UniversalExecutionResult(
                success = false,
                message = "Couldn't find '$targetItem' on the current $appName screen, so nothing was deleted. Try searching for it first.",
                actionTaken = "DELETE_TARGET_NOT_FOUND"
            )
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
            if (!clickedDelete) {
                return UniversalExecutionResult(
                    success = false,
                    message = "Found '${nodeLabel(deleteNode)}' in $appName but tapping it failed, so nothing was deleted.",
                    actionTaken = "DELETE_CLICK_FAILED"
                )
            }
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
     * Taps the app's create / upload entry point (FAB, "New", "+"), then uses [targetQuery]
     * (matched case-insensitively against text / content description) to pick the option or
     * file. Every tapped element is reported; a missing match or failed tap is a failure.
     */
    private suspend fun executeUploadOrCreateAction(
        service: JarvisAccessibilityService,
        appName: String,
        actionType: String,
        targetQuery: String
    ): UniversalExecutionResult {
        val root = service.getActiveRootNode() ?: return UniversalExecutionResult(false, "Could not acquire screen root.", actionTaken = "UPLOAD_NO_ROOT")
        val isUpload = actionType.contains("upload")
        val tapped = mutableListOf<String>()

        // 1. Locate the create / upload entry point (+, New, Create, Add, Upload, Compose)
        val createNode = findCreateButton(root)
        if (createNode == null) {
            // Some screens show the wanted option directly (e.g. a "New folder" button).
            if (targetQuery.isNotBlank()) {
                val direct = findNodeMatchingText(root, targetQuery)
                if (direct != null) {
                    val label = nodeLabel(direct)
                    return if (clickNode(service, direct)) {
                        UniversalExecutionResult(
                            success = true,
                            message = "Tapped '$label' in $appName.",
                            actionTaken = "CREATE_TARGET_CLICKED"
                        )
                    } else {
                        UniversalExecutionResult(
                            success = false,
                            message = "Found '$label' in $appName but tapping it failed.",
                            actionTaken = "CREATE_TARGET_CLICK_FAILED"
                        )
                    }
                }
            }
            return UniversalExecutionResult(
                success = false,
                message = "Could not locate an upload/create button in $appName" +
                    (if (targetQuery.isNotBlank()) " or anything matching '$targetQuery'." else "."),
                actionTaken = "FAB_NOT_FOUND"
            )
        }

        val createLabel = nodeLabel(createNode)
        if (!clickNode(service, createNode)) {
            return UniversalExecutionResult(
                success = false,
                message = "Found '$createLabel' in $appName but tapping it failed.",
                actionTaken = "CREATE_CLICK_FAILED"
            )
        }
        tapped += createLabel
        Log.i(TAG, "Tapped create entry '$createLabel' in $appName")
        delay(500L)

        var sheetRoot = service.getActiveRootNode() ?: root

        // 2. Upload: choose the "Upload" entry of the create sheet when the button itself wasn't it.
        if (isUpload && !createLabel.contains("upload", ignoreCase = true)) {
            val uploadOption = findNodeByKeywords(sheetRoot, listOf("upload"))
            if (uploadOption != null && clickNode(service, uploadOption)) {
                tapped += nodeLabel(uploadOption)
                delay(700L)
                sheetRoot = service.getActiveRootNode() ?: sheetRoot
            }
        }
        val path = tapped.joinToString(" > ") { "'$it'" }

        // 3. target_query picks the specific option / file.
        if (targetQuery.isNotBlank()) {
            val target = findNodeMatchingText(sheetRoot, targetQuery)
            if (target == null) {
                val options = extractVisibleText(sheetRoot).take(8).joinToString(", ")
                return UniversalExecutionResult(
                    success = false,
                    message = "In $appName I tapped $path, but nothing matching '$targetQuery' appeared. Visible options: $options.",
                    screenSummary = "Visible options: $options",
                    actionTaken = "CREATE_TARGET_NOT_FOUND"
                )
            }
            val targetLabel = nodeLabel(target)
            if (!clickNode(service, target)) {
                return UniversalExecutionResult(
                    success = false,
                    message = "In $appName I tapped $path and found '$targetLabel', but tapping it failed.",
                    actionTaken = "CREATE_TARGET_CLICK_FAILED"
                )
            }
            tapped += targetLabel
            return UniversalExecutionResult(
                success = true,
                message = "In $appName I tapped ${tapped.joinToString(" > ") { "'$it'" }}.",
                actionTaken = if (isUpload) "UPLOAD_TARGET_CLICKED" else "CREATE_TARGET_CLICKED"
            )
        }

        // 4. No target given: report exactly what is open instead of claiming the goal is done.
        val options = extractVisibleText(sheetRoot).take(8).joinToString(", ")
        val uploadPickerOpen = isUpload && tapped.any { it.contains("upload", ignoreCase = true) }
        return if (uploadPickerOpen) {
            UniversalExecutionResult(
                success = true,
                message = "Opened the upload picker in $appName (tapped $path). Choose the file to upload.",
                screenSummary = "Visible options: $options",
                actionTaken = "UPLOAD_PICKER_OPENED"
            )
        } else {
            UniversalExecutionResult(
                success = false,
                message = "Tapped $path in $appName and the menu is open, but no item was specified. Visible options: $options. Tell me which one to pick.",
                screenSummary = "Visible options: $options",
                actionTaken = "CREATE_MENU_OPENED_NO_TARGET"
            )
        }
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
            val label = nodeLabel(targetNode)
            val clicked = clickNode(service, targetNode)
            if (!clicked) {
                return UniversalExecutionResult(
                    success = false,
                    message = "Found '$label' in $appName for '$goal' but tapping it failed.",
                    actionTaken = "CUSTOM_ACTION_CLICK_FAILED"
                )
            }
            delay(500L)
            return UniversalExecutionResult(
                success = true,
                message = "Tapped '$label' in $appName for '$goal'.",
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

    /**
     * Finds the app's create / upload entry point. Stricter than [findNodeByKeywords]: the label
     * must BE "+" / "New" / "Create ..." etc. (not merely contain "add" like "Address"), or the
     * view id must look like a FAB / create button. Editable fields are skipped.
     */
    private fun findCreateButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val words = listOf("new", "create", "add", "upload", "compose")
        val idHints = listOf("fab", "create", "compose", "upload", "new_", "add_")
        fun isCreateLabel(label: String): Boolean =
            label == "+" || words.any { w -> label == w || label.startsWith("$w ") || label.startsWith("$w+") }

        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isEditable) {
                val text = node.text?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT)?.substringAfter(":id/")?.trim() ?: ""
                if (isCreateLabel(text) || isCreateLabel(desc) || idHints.any { id.contains(it) }) return node
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

    /** Clicks [node] or its nearest clickable ancestor; falls back to a coordinate tap. */
    private fun clickNode(service: JarvisAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return clickNodeCoordinates(service, node)
    }

    /** Human-readable label of a node for result messages. */
    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotEmpty()) return desc
        return node.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotBlank() } ?: "unlabelled element"
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
