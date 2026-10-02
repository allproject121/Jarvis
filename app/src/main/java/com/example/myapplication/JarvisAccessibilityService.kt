package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    private val knowledgeBase: JarvisKnowledgeBase by lazy { JarvisKnowledgeBase(this) }
    private var lastEventTime: Long = 0
    private var currentPackageName: String = ""
    private var lastPackageName: String = ""
    private var tts: TextToSpeech? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != currentPackageName) {
                    lastPackageName = currentPackageName
                    currentPackageName = pkg
                    knowledgeBase.trackAppLaunch(pkg)
                    knowledgeBase.trackActivity("Switched to: $pkg")
                }
                
                // Detect WhatsApp "Not registered" dialog
                if (pkg == "com.whatsapp") {
                    checkForWhatsAppDialogs()
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank()) {
                    knowledgeBase.remember("last_notification", text)
                    knowledgeBase.trackActivity("Notification: $text")
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank()) {
                    knowledgeBase.incrementUsageCounter("clicks_on_$text")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0) {
                    val text = event.text?.joinToString(" ") ?: ""
                    if (text.isNotBlank() && text.length > 10) {
                        knowledgeBase.remember("last_screen_text_change", text.take(200))
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    // === GESTURES ===

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(builder.build(), null, null)
    }

    fun performDoubleTap(x: Float, y: Float) {
        val builder = GestureDescription.Builder()
        val path1 = Path().apply { moveTo(x, y) }
        val path2 = Path().apply { moveTo(x, y) }
        builder.addStroke(GestureDescription.StrokeDescription(path1, 0, 100))
        builder.addStroke(GestureDescription.StrokeDescription(path2, 200, 100))
        dispatchGesture(builder.build(), null, null)
    }

    fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
        dispatchGesture(builder.build(), null, null)
    }

    fun performSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, duration: Long = 300) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), null, null)
    }

    fun performSwipeUp() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels / 2f, display.heightPixels * 0.8f, display.widthPixels / 2f, display.heightPixels * 0.2f)
    }

    fun performSwipeDown() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels / 2f, display.heightPixels * 0.2f, display.widthPixels / 2f, display.heightPixels * 0.8f)
    }

    fun performSwipeLeft() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels * 0.8f, display.heightPixels / 2f, display.widthPixels * 0.2f, display.heightPixels / 2f)
    }

    fun performSwipeRight() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels * 0.2f, display.heightPixels / 2f, display.widthPixels * 0.8f, display.heightPixels / 2f)
    }

    fun performScrollDown() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels / 2f, display.heightPixels * 0.6f, display.widthPixels / 2f, display.heightPixels * 0.4f, 200)
    }

    fun performScrollUp() {
        val display = resources.displayMetrics
        performSwipe(display.widthPixels / 2f, display.heightPixels * 0.4f, display.widthPixels / 2f, display.heightPixels * 0.6f, 200)
    }

    // === GLOBAL ACTIONS ===

    fun performRecents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
    fun performNotifications() { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
    fun performQuickSettings() { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
    fun performSplitScreen() { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) }
    fun performLockScreen() { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
    fun performTakeScreenshot() { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }
    fun performPowerDialog() { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }
    fun performBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun performHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    private fun checkForWhatsAppDialogs() {
        val rootNode = rootInActiveWindow ?: return
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, nodes)
        
        val isNotRegisteredDialog = nodes.any { 
            it.text?.toString()?.contains("not registered with WhatsApp", true) == true 
        }
        
        if (isNotRegisteredDialog) {
            Log.d("Accessibility", "WhatsApp 'Not Registered' dialog detected")
            speakText("Sir, ye number WhatsApp par register nahi hai. Kya aap isse SMS bhejna chahte hain?")
        }
    }

    // Click at specific coordinates
    fun clickElement(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e("Accessibility", "clickElement error: ${e.message}")
            false
        }
    }

    // === UI ELEMENT FINDING & CLICKING ===

    fun findAndClickText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        // Try exact match first
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (nodes.isNotEmpty()) {
            val clickable = findClickableAncestor(nodes[0])
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        // Fallback to fuzzy match
        return findAndClickTextFuzzy(text)
    }

    fun findAndClickTextFuzzy(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, allNodes)
        val match = allNodes.firstOrNull {
            val nodeText = it.text?.toString()?.lowercase() ?: it.contentDescription?.toString()?.lowercase() ?: ""
            nodeText.contains(text.lowercase())
        }
        if (match != null) {
            val clickable = findClickableAncestor(match)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    fun findAndClickId(id: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
        if (nodes.isNotEmpty()) {
            val clickable = findClickableAncestor(nodes[0])
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    fun findAndClickDescription(desc: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, nodes)
        val match = nodes.find {
            it.contentDescription?.toString()?.lowercase()?.contains(desc.lowercase()) == true
        }
        if (match != null) {
            val clickable = findClickableAncestor(match)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent ?: break
        }
        return null
    }

    // === TEXT INPUT ===

    fun findAndTypeTextFuzzy(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, nodes)

        // Find editable fields - prefer the one that's currently focused or has hint text
        val editable = nodes.filter {
            it.isEditable || it.className?.contains("EditText", true) == true
        }.sortedByDescending { it.isFocused }

        if (editable.isNotEmpty()) {
            val node = editable.first()
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // Use ACTION_SET_TEXT to clear and set, then append via clipboard
            return typeTextInNode(node, text)
        }
        return false
    }

    fun findAndFocusTypable(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, nodes)
        val editable = nodes.filter {
            it.isEditable || it.className?.contains("EditText", true) == true
        }.sortedByDescending { it.isFocused }
        if (editable.isNotEmpty()) {
            editable.first().performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return true
        }
        return false
    }

    fun typeTextInFocused(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, nodes)
        val focused = nodes.firstOrNull { it.isFocused }
        if (focused != null && (focused.isEditable || focused.className?.contains("EditText", true) == true)) {
            return typeTextInNode(focused, text)
        }
        return false
    }

    private fun typeTextInNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Try ACTION_SET_TEXT first (replaces all text)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        // Fallback: try per-character via ACTION_CLICK on the field then paste
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Try clipboard approach
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("JarvisType", text)
            clipboard.setPrimaryClip(clip)
            // Focus and paste
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(android.R.id.paste)
            return true
        } catch (e: Exception) {
            Log.e("Accessibility", "Paste failed: ${e.message}")
        }
        return false
    }

    fun findAndTypeText(viewId: String, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNotEmpty()) {
            return typeTextInNode(nodes[0], text)
        }
        return false
    }

    // === SCREEN MAPPING ===

    fun mapScreenInteractions(): List<ScreenElement> {
        val elements = mutableListOf<ScreenElement>()
        val root = rootInActiveWindow ?: return elements
        traverseNode(root, elements)
        return elements
    }

    fun getScreenTextContent(): String {
        val elements = mapScreenInteractions()
        return elements.filter { it.text.isNotBlank() }.joinToString(" | ") { it.text }
    }

    fun findElementsByText(text: String): List<ScreenElement> {
        return mapScreenInteractions().filter {
            it.text.lowercase().contains(text.lowercase())
        }
    }

    fun findClickableElements(): List<ScreenElement> {
        return mapScreenInteractions().filter { it.isClickable }
    }

    fun findEditableElements(): List<ScreenElement> {
        return mapScreenInteractions().filter { it.isEditable }
    }

    fun getScreenCenter(): Pair<Float, Float> {
        val display = resources.displayMetrics
        return display.widthPixels / 2f to display.heightPixels / 2f
    }

    fun isElementVisible(text: String): Boolean {
        return findElementsByText(text).isNotEmpty()
    }

    fun getCenterOfElement(text: String): Pair<Float, Float>? {
        val elements = findElementsByText(text)
        if (elements.isEmpty()) return null
        val el = elements.first()
        val bounds = el.bounds
        return (bounds.left + bounds.right) / 2f to (bounds.top + bounds.bottom) / 2f
    }

    fun findAndClickCenterOfText(text: String): Boolean {
        val center = getCenterOfElement(text) ?: return false
        performTap(center.first, center.second)
        return true
    }

    // === SCROLL TO TEXT ===

    fun scrollToText(text: String, maxScrolls: Int = 10): Boolean {
        var scrolls = 0
        while (scrolls < maxScrolls) {
            if (isElementVisible(text)) return true
            performScrollDown()
            try { Thread.sleep(500) } catch (e: Exception) {}
            scrolls++
        }
        return isElementVisible(text)
    }

    // === NODE TRAVERSAL ===

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<ScreenElement>) {
        if (node.isClickable || node.isEditable || !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            list.add(ScreenElement(
                text = node.text?.toString() ?: node.contentDescription?.toString() ?: "",
                className = node.className?.toString() ?: "",
                id = node.viewIdResourceName ?: "",
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                bounds = bounds
            ))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list)
        }
    }

    private fun findAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodes(child, list)
        }
    }

    // === GETTERS ===

    fun getCurrentPackageName(): String? = currentPackageName

    fun getLastPackageName(): String? = lastPackageName

    fun getPackageHistory(): List<String> {
        val history = knowledgeBase.recall("activity_history") ?: return emptyList()
        return try {
            val json = org.json.JSONArray(history)
            (0 until json.length()).map { json.getJSONObject(it).getString("activity") }
                .filter { it.startsWith("Switched to:") }
                .map { it.removePrefix("Switched to: ") }
                .distinct()
                .takeLast(10)
        } catch (e: Exception) { emptyList() }
    }

    // === SPEAK ===

    fun speakText(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    data class ScreenElement(
        val text: String,
        val className: String,
        val id: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val bounds: Rect
    )

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        tts?.stop()
        tts?.shutdown()
    }
}
