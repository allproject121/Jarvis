package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.androllm.accessibility.analyzer.ScreenAnalyzer
import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.automation.tools.AppIndex

data class UiActionResult(val success: Boolean, val message: String) {
    companion object {
        fun ok(msg: String) = UiActionResult(true, msg)
        fun fail(msg: String) = UiActionResult(false, msg)
    }
}

/**
 * Main coordinator for Android screen inspection and interaction.
 * Directly integrates with JarvisAccessibilityService.
 *
 * AndroLLM upgrade (core/accessibility/controller/AccessibilityController):
 * every node read/action runs on the main thread via [MainThread]; click
 * scrolls the target into view (setting `autoScrollIntoView`) and refuses
 * invisible nodes; typing targets TEXT_FIELD nodes only; scroll falls back
 * to a swipe gesture when no scrollable container exists; unknown navigate /
 * swipe / scroll values fail instead of silently defaulting; [launchApp]
 * opens installed apps by label; [captureScreenshot] backs OCR / QR.
 */
class AccessibilityController(private val context: Context) {

    private val appContext = context.applicationContext
    private val settingsStore = AccessibilitySettingsStore.getInstance(appContext)
    private val gestures = GestureExecutor { JarvisAccessibilityService.instance }
    private val textInput = TextInputController(appContext, gestures)

    val isConnected: Boolean get() = JarvisAccessibilityService.isRunning()

    /** Runs [block] on the main thread; null when node access failed. */
    suspend fun <T> onMain(block: () -> T): T? = MainThread.node(block)

    private suspend fun liveRoot(): AccessibilityNodeInfo? =
        onMain { JarvisAccessibilityService.instance?.rootInActiveWindow }

    // ── Screen reading ──────────────────────────────────────────────────────

    suspend fun readScreen(): UiScreenSnapshot = ScreenAnalyzer.read(settingsStore.current())

    // ── Element actions ─────────────────────────────────────────────────────

    /** Taps the element matching [selector], scrolling it into view first if needed. */
    suspend fun click(selector: UiSelector, maxScrolls: Int = 6): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val settings = settingsStore.current()
        val root = liveRoot() ?: return UiActionResult.fail("No active window found.")
        var node = onMain { LiveTree.find(root, selector) }
        var scrolls = 0
        while (node == null && settings.autoScrollIntoView && scrolls < maxScrolls) {
            val scrolled = onMain {
                LiveTree.findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            } ?: false
            if (!scrolled) break
            scrolls++
            kotlinx.coroutines.delay(250)
            val fresh = liveRoot() ?: root
            node = onMain { LiveTree.find(fresh, selector) }
        }
        val target = node
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found on this screen.")
        val visible = onMain { target.isVisibleToUser } ?: false
        if (!visible) return UiActionResult.fail("Element '${selector.summary}' is not visible.")
        val ok = gestures.clickNode(target)
        AccessibilityDebugStore.record("controller", "click ${selector.summary} = $ok (scrolls=$scrolls)")
        return if (ok) UiActionResult.ok("Tapped ${selector.summary}.") else UiActionResult.fail("Tapping ${selector.summary} failed.")
    }

    suspend fun tapCoordinates(x: Float, y: Float): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val rect = Rect(x.toInt() - 5, y.toInt() - 5, x.toInt() + 5, y.toInt() + 5)
        val ok = gestures.tap(rect)
        return if (ok) UiActionResult.ok("Tapped ($x, $y)") else UiActionResult.fail("Failed tap at ($x, $y)")
    }

    /** Double-taps the element matching [selector]. */
    suspend fun doubleTap(selector: UiSelector): UiActionResult {
        val bounds = boundsOf(selector) ?: return UiActionResult.fail(missing(selector))
        val ok = gestures.doubleTap(bounds)
        return if (ok) UiActionResult.ok("Double-tapped ${selector.summary}.") else UiActionResult.fail("Double-tap failed.")
    }

    /** Long-presses the element matching [selector]. */
    suspend fun longClick(selector: UiSelector): UiActionResult {
        val bounds = boundsOf(selector) ?: return UiActionResult.fail(missing(selector))
        val ok = gestures.longPress(bounds)
        return if (ok) UiActionResult.ok("Long-pressed ${selector.summary}.") else UiActionResult.fail("Long-press failed.")
    }

    /** Drags the element matching [selector] by ([dx], [dy]) pixels. */
    suspend fun drag(selector: UiSelector, dx: Float, dy: Float): UiActionResult {
        val bounds = boundsOf(selector) ?: return UiActionResult.fail(missing(selector))
        val ok = gestures.drag(
            bounds.exactCenterX(), bounds.exactCenterY(),
            bounds.exactCenterX() + dx, bounds.exactCenterY() + dy
        )
        return if (ok) UiActionResult.ok("Dragged ${selector.summary}.") else UiActionResult.fail("Drag failed.")
    }

    /** Two-finger pinch around the screen center. */
    suspend fun pinch(zoomIn: Boolean): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val dm = appContext.resources.displayMetrics
        val ok = gestures.pinch(dm.widthPixels / 2f, dm.heightPixels / 2f, zoomIn)
        return if (ok) UiActionResult.ok(if (zoomIn) "Pinched in." else "Pinched out.") else UiActionResult.fail("Pinch failed.")
    }

    /**
     * Types [text] into the field matching [into] (label, resource id or hint;
     * TEXT_FIELD nodes only) or, when [into] is null, the focused / first
     * editable field.
     */
    suspend fun type(text: String, into: String?): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val root = liveRoot() ?: return UiActionResult.fail("No active window found.")
        val hint = into?.trim()?.takeIf { it.isNotEmpty() }
        val field = onMain {
            if (hint != null) {
                LiveTree.find(root, UiSelector(textContains = hint, type = UiElementType.TEXT_FIELD))
                    ?: LiveTree.find(root, UiSelector(resourceId = hint, type = UiElementType.TEXT_FIELD))
                    ?: LiveTree.collect(root).firstOrNull { n ->
                        n.isEditable && n.isVisibleToUser &&
                            n.hintText?.toString()?.contains(hint, ignoreCase = true) == true
                    }
            } else {
                LiveTree.findFocused(root)?.takeIf { it.isEditable } ?: LiveTree.findFirstEditable(root)
            }
        }
        if (field == null) {
            val what = hint?.let { " matching '$it'" } ?: ""
            return UiActionResult.fail("No text field$what is available on this screen.")
        }
        val ok = textInput.setText(field, text)
        AccessibilityDebugStore.record("controller", "type into ${hint ?: "focused"} = $ok")
        return if (ok) {
            UiActionResult.ok("Typed into the field${hint?.let { " ($it)" } ?: ""}.")
        } else {
            UiActionResult.fail("Typing failed - the field may not accept text.")
        }
    }

    /** Backwards-compatible entry point (selector form). */
    suspend fun typeText(text: String, intoSelector: UiSelector? = null): UiActionResult {
        if (intoSelector == null) return type(text, null)
        val hint = intoSelector.textContains ?: intoSelector.text ?: intoSelector.resourceId
        return type(text, hint)
    }

    // ── Scrolling & gestures ────────────────────────────────────────────────

    /**
     * Scrolls the first scrollable container; falls back to a swipe gesture
     * when the screen has none (custom / Compose / web content). Accepts
     * up/down/left/right and the legacy forward/backward.
     */
    suspend fun scroll(direction: String): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val dir = direction.trim().lowercase()
        val root = liveRoot()
        val scrollable = root?.let { r -> onMain { LiveTree.findScrollable(r) } }
        val ok = when (dir) {
            "down", "forward" -> (scrollable?.let { gestures.scrollForward(it) } ?: false) || swipeGesture("up")
            "up", "backward" -> (scrollable?.let { gestures.scrollBackward(it) } ?: false) || swipeGesture("down")
            "left", "right" -> swipeGesture(dir)
            else -> return UiActionResult.fail("Unknown scroll direction '$direction' (use up, down, left or right).")
        }
        return if (ok) UiActionResult.ok("Scrolled $dir.") else UiActionResult.fail("Could not scroll $dir.")
    }

    suspend fun swipe(direction: String): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val dir = direction.trim().lowercase()
        if (dir !in setOf("up", "down", "left", "right")) {
            return UiActionResult.fail("Unknown swipe direction '$direction' (use up, down, left or right).")
        }
        val ok = swipeGesture(dir)
        return if (ok) UiActionResult.ok("Swiped $dir.") else UiActionResult.fail("Swipe $dir failed.")
    }

    private suspend fun swipeGesture(direction: String): Boolean {
        val dm = appContext.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (fx, fy, dx, dy) = when (direction) {
            "up" -> Quad(w / 2, h * 0.75f, 0f, -h * 0.45f)
            "down" -> Quad(w / 2, h * 0.25f, 0f, h * 0.45f)
            "left" -> Quad(w * 0.8f, h / 2, -w * 0.45f, 0f)
            "right" -> Quad(w * 0.2f, h / 2, w * 0.45f, 0f)
            else -> return false
        }
        return gestures.swipe(fx, fy, dx, dy)
    }

    // ── System navigation ───────────────────────────────────────────────────

    suspend fun navigate(action: String): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        val name = action.trim().lowercase()
        val code = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "split_screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                else return UiActionResult.fail("Split screen is not supported on this Android version.")
            }
            else -> return UiActionResult.fail("Unknown navigation action '$action'.")
        }
        val ok = gestures.globalAction(code)
        return if (ok) UiActionResult.ok("Performed $name.") else UiActionResult.fail("Could not perform $name.")
    }

    /**
     * Advanced gestures (double_tap / long_press on a labelled element, drag,
     * pinch). All node lookups run on the main thread.
     */
    suspend fun gesture(action: String, label: String? = null, dx: Float = 0f, dy: Float = 0f): UiActionResult {
        if (!isConnected) return UiActionResult.fail(notConnected())
        return when (action.trim().lowercase()) {
            "double_tap" -> label?.let { doubleTap(UiSelector(textContains = it)) }
                ?: UiActionResult.fail("Label required for double_tap")
            "long_press" -> label?.let { longClick(UiSelector(textContains = it)) }
                ?: UiActionResult.fail("Label required for long_press")
            "drag" -> {
                if (label != null) {
                    drag(UiSelector(textContains = label), dx, dy)
                } else {
                    val dm = appContext.resources.displayMetrics
                    val cx = dm.widthPixels / 2f
                    val cy = dm.heightPixels / 2f
                    val ok = gestures.drag(cx, cy, cx + dx, cy + dy)
                    if (ok) UiActionResult.ok("Dragged ($dx, $dy)") else UiActionResult.fail("Drag failed")
                }
            }
            "pinch_in" -> pinch(zoomIn = true)
            "pinch_out" -> pinch(zoomIn = false)
            else -> UiActionResult.fail("Unknown gesture: $action")
        }
    }

    // ── App launching ───────────────────────────────────────────────────────

    /** Launches an installed app by its visible label ("open WhatsApp"). */
    suspend fun launchApp(label: String): UiActionResult {
        val query = label.trim()
        if (query.isEmpty()) return UiActionResult.fail("No app name given.")
        val result = runCatching { AppIndex.search(appContext, query) }.getOrNull()
        val entry = result?.matches?.firstOrNull { it.launchIntent != null }
            ?: return UiActionResult.fail("No installed app matches '$label'.")
        val launchIntent = entry.launchIntent
            ?: return UiActionResult.fail("App '${entry.label}' has no launcher activity.")
        val intent = Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
        AccessibilityDebugStore.record("controller", "launchApp $label -> ${entry.packageName} = $launched")
        return if (launched) UiActionResult.ok("Opened ${entry.label}.") else UiActionResult.fail("Could not open ${entry.label}.")
    }

    // ── Vision support (screenshot) ─────────────────────────────────────────

    /** Captures the screen as a software bitmap (API 30+; needs the service connected). */
    suspend fun captureScreenshot(): Bitmap? = JarvisAccessibilityService.captureScreenBitmap()

    // ── Helpers ─────────────────────────────────────────────────────────────

    fun openSystemSettings(): Boolean = runCatching {
        appContext.startActivity(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    private suspend fun boundsOf(selector: UiSelector): Rect? {
        val root = liveRoot() ?: return null
        return onMain {
            val node = LiveTree.find(root, selector) ?: return@onMain null
            Rect().also { node.getBoundsInScreen(it) }
        }?.takeIf { !it.isEmpty }
    }

    private fun missing(selector: UiSelector): String =
        if (!isConnected) notConnected() else "Element '${selector.summary}' was not found."

    private fun notConnected(): String =
        "Jarvis Accessibility Service is not enabled. Please enable it in Settings > Accessibility."

    private data class Quad(val x: Float, val y: Float, val dx: Float, val dy: Float)
}
