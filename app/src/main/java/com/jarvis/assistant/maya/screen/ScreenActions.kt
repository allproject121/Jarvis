package com.jarvis.assistant.maya.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.ScreenSignature
import com.jarvis.assistant.automation.tools.AppIndex
import com.jarvis.assistant.util.ActivityLauncherHelper
import com.jarvis.assistant.util.AppLauncher
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Result of one low-level UI action. */
data class ActionResult(val ok: Boolean, val message: String, val changed: Boolean? = null)

/**
 * Low-level, defensive UI primitives on top of [JarvisAccessibilityService] used by the verb
 * loop, the direct screen tools and macro playback.
 */
object ScreenActions {

    private const val TAG = "MayaScreenActions"

    private val LAUNCHER_HINTS = listOf("launcher", "home", "trebuchet", "lawnchair", "nexuslauncher")

    fun svc(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    // ------------------------------------------------------------------ settle / change

    /** Polls the screen signature until two consecutive reads match (or [maxMs] passes). */
    suspend fun waitForSettle(maxMs: Long = 1_500L, intervalMs: Long = 250L): Pair<ScreenSignature?, Boolean> {
        val deadline = System.currentTimeMillis() + maxMs
        var prev = ScreenElementSerializer.signature()
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val cur = ScreenElementSerializer.signature()
            if (cur != null && prev != null && cur.isEquivalentTo(prev)) return cur to true
            prev = cur
        }
        return prev to false
    }

    fun changed(before: ScreenSignature?, after: ScreenSignature?): Boolean {
        if (before == null || after == null) return before != after
        return !before.isEquivalentTo(after)
    }

    // ------------------------------------------------------------------ taps

    fun tapElement(e: SnapshotElement): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        val node = e.node
        val cx = e.bounds.centerX().toFloat()
        val cy = e.bounds.centerY().toFloat()
        try {
            val fresh = node != null && node.refresh()
            if (fresh && node != null) {
                val pkg = node.packageName?.toString() ?: ""
                if (pkg == "com.android.chrome") {
                    if (s.clickAt(cx, cy)) return ActionResult(true, "tapped")
                }
                if (e.line.role == "input") {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                if (s.clickNodeAndTap(node)) return ActionResult(true, "tapped")
            }
            if (s.performClickAtRect(Rect(e.bounds))) return ActionResult(true, "tapped (by position)")
        } catch (ex: Exception) {
            Log.w(TAG, "tapElement failed: ${ex.message}")
        }
        return if (s.clickAt(cx, cy)) ActionResult(true, "tapped (gesture)") else ActionResult(false, "tap could not be dispatched")
    }

    fun tapCoords(x: Int, y: Int): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        return if (s.clickAt(x.toFloat(), y.toFloat())) ActionResult(true, "tapped ($x,$y)") else ActionResult(false, "gesture at ($x,$y) was rejected")
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 700L): Boolean {
        val s = svc() ?: return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), null, null)
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------------ typing

    private fun findInput(root: AccessibilityNodeInfo, s: JarvisAccessibilityService): AccessibilityNodeInfo? {
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (focused != null && (focused.isEditable || focused.className?.toString()?.contains("EditText") == true)) return focused
        return try { s.findFirstEditableNode(root) } catch (_: Exception) { null }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (_: Exception) { false }
    }

    /**
     * Types [text] into the focused (or first) text field. [realistic] types it character by
     * character with human-like delays (some apps/sites only react to incremental edits).
     */
    suspend fun type(text: String, realistic: Boolean = false, target: SnapshotElement? = null): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        val root = s.getActiveRootNode() ?: return ActionResult(false, "no active window")
        var node: AccessibilityNodeInfo? = target?.node?.takeIf { it.refresh() && (it.isEditable || it.className?.toString()?.contains("EditText") == true) }
        if (node == null) node = findInput(root, s)
        if (node == null) {
            // Last resort: Jarvis' smart typer (clipboard paste / long-press paste fallbacks)
            val ok = try { s.smartTypeText(text, null, false) } catch (_: Exception) { false }
            return if (ok) ActionResult(true, "typed via fallback") else ActionResult(false, "no text field found on screen — tap the input first")
        }
        val input: AccessibilityNodeInfo = node
        if (!input.isFocused) {
            try { input.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Exception) {}
            try { input.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
            delay(200)
        }
        var ok: Boolean
        if (realistic && text.length in 1..400) {
            ok = true
            val sb = StringBuilder()
            for (ch in text) {
                sb.append(ch)
                if (!setText(input, sb.toString())) { ok = false; break }
                val extra = if (ch == ' ') Random.nextLong(30L, 120L) else 0L
                delay(Random.nextLong(55L, 150L) + extra)
            }
        } else {
            ok = setText(input, text)
        }
        if (!ok) {
            val rect = Rect()
            try { input.getBoundsInScreen(rect) } catch (_: Exception) {}
            ok = try { s.smartTypeText(text, rect, false) } catch (_: Exception) { false }
        }
        delay(250)
        val readBack = try { input.refresh(); input.text?.toString() ?: "" } catch (_: Exception) { "" }
        val verified = readBack.contains(text.take(24), ignoreCase = true) || (try { s.verifyTextVisibleAnywhere(text) } catch (_: Exception) { false })
        return when {
            ok && verified -> ActionResult(true, "typed and verified on screen", true)
            ok -> ActionResult(true, "typed (could not read it back — check the field)")
            else -> ActionResult(false, "the field refused the text")
        }
    }

    // ------------------------------------------------------------------ swipe / scroll

    /** Scroll the CONTENT in [direction] (down = reveal what is below). */
    fun scroll(direction: String, distance: String = "medium"): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        val d = direction.lowercase().ifBlank { "down" }
        return if (s.performSmartScroll(d, distance)) ActionResult(true, "scrolled $d") else ActionResult(false, "scroll gesture rejected")
    }

    /** Swipe the FINGER in [direction] (swipe up = next reel / scroll content down). */
    fun swipe(context: Context, direction: String): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        val (w, h) = VisionLookup.deviceSize(context)
        val (sx, sy, ex, ey) = when (direction.lowercase()) {
            "up" -> listOf(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f)
            "down" -> listOf(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.75f)
            "left" -> listOf(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f)
            "right" -> listOf(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f)
        }
        return try {
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val ok = s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 320)).build(), null, null)
            if (ok) ActionResult(true, "swiped $direction") else ActionResult(false, "swipe gesture rejected")
        } catch (e: Exception) {
            ActionResult(false, "swipe failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ global

    fun back(): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        return if (s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) ActionResult(true, "pressed back") else ActionResult(false, "back rejected")
    }

    fun home(): ActionResult {
        val s = svc() ?: return ActionResult(false, ScreenElementSerializer.SERVICE_OFF)
        return if (s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) ActionResult(true, "went to home screen") else ActionResult(false, "home rejected")
    }

    fun isLauncher(pkg: String): Boolean = LAUNCHER_HINTS.any { pkg.lowercase().contains(it) }

    // ------------------------------------------------------------------ apps

    data class OpenedApp(val label: String, val packageName: String)

    /** Opens an installed app by display name (AppIndex fuzzy search, then AppLauncher). */
    suspend fun openApp(context: Context, name: String, waitForegroundMs: Long = 4_000L): Pair<ActionResult, OpenedApp?> {
        val query = name.trim()
        if (query.isEmpty()) return ActionResult(false, "app name missing") to null
        var opened: OpenedApp? = null
        try {
            val res = AppIndex.search(context, query)
            val entry = res.matches.firstOrNull()
            if (entry != null) {
                val intent = (entry.launchIntent?.let { Intent(it) } ?: context.packageManager.getLaunchIntentForPackage(entry.packageName))
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (ActivityLauncherHelper.startActivitySafely(context, intent)) opened = OpenedApp(entry.label, entry.packageName)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AppIndex launch failed: ${e.message}")
        }
        if (opened == null) {
            try {
                val app = AppLauncher.openApp(context, query)
                if (app != null) opened = OpenedApp(app.label, app.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "AppLauncher failed: ${e.message}")
            }
        }
        if (opened == null) return ActionResult(false, "'$query' naam ka app nahi mila (installed nahi hai ya naam alag hai)") to null
        val deadline = System.currentTimeMillis() + waitForegroundMs
        while (System.currentTimeMillis() < deadline) {
            if (ScreenElementSerializer.foregroundPackage() == opened.packageName) break
            delay(250)
        }
        val inFront = ScreenElementSerializer.foregroundPackage() == opened.packageName
        return ActionResult(true, if (inFront) "Opened ${opened.label}" else "Launched ${opened.label} (not in front yet — it may still be loading)", inFront) to opened
    }

    // ------------------------------------------------------------------ waits

    /** Waits until [text] appears (or disappears). Returns elapsed ms or -1 on timeout. */
    suspend fun waitForText(text: String, appear: Boolean, timeoutMs: Long, isCancelled: () -> Boolean = { false }): Long {
        val needle = text.trim().lowercase()
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs.coerceIn(500L, 15 * 60_000L)
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return -1
            val present = ScreenElementSerializer.allVisibleText().any { it.lowercase().contains(needle) }
            if (present == appear) return System.currentTimeMillis() - start
            delay(if (timeoutMs > 60_000L) 1_500L else 700L)
        }
        return -1
    }

    /**
     * Maya wait_for: the condition may be matching text or a visible state. Text is polled; for
     * descriptive conditions the screenshot is also checked with the vision model every ~6 s.
     */
    suspend fun waitForCondition(context: Context, condition: String, timeoutMs: Long, isCancelled: () -> Boolean): ActionResult {
        val cond = condition.trim()
        if (cond.isEmpty()) {
            delay(timeoutMs.coerceAtMost(5_000L))
            return ActionResult(true, "waited ${timeoutMs.coerceAtMost(5_000L)}ms")
        }
        val quoted = Regex("['\"“‘]([^'\"”’]{2,60})['\"”’]").find(cond)?.groupValues?.get(1)
        val textNeedle = quoted ?: cond.takeIf { it.length <= 40 && it.split(' ').size <= 6 }
        val descriptive = quoted == null && cond.split(' ').size > 6
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs.coerceIn(1_000L, 15 * 60_000L)
        var lastVision = 0L
        var visionChecks = 0
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return ActionResult(false, "cancelled while waiting")
            if (textNeedle != null && ScreenElementSerializer.allVisibleText().any { it.contains(textNeedle, ignoreCase = true) }) {
                return ActionResult(true, "'$textNeedle' appeared after ${(System.currentTimeMillis() - start) / 1000}s", true)
            }
            val now = System.currentTimeMillis()
            if ((descriptive || textNeedle == null || now - start > 8_000L) && now - lastVision > 6_000L && visionChecks < 12 && VisionLookup.hasKey()) {
                lastVision = now
                visionChecks++
                if (VisionLookup.verify(context, cond) == true) {
                    return ActionResult(true, "condition visibly met after ${(now - start) / 1000}s (verified by looking)", true)
                }
            }
            delay(1_200L)
        }
        return ActionResult(false, "'$cond' did not happen within ${timeoutMs / 1000}s")
    }

    // ------------------------------------------------------------------ OEM dialogs

    private val REPORT_DIALOG = listOf("report this issue", "report issue")
    private val CRASH_DIALOG = listOf("keeps stopping", "has stopped", "isn't responding", "is not responding", "close app")

    /**
     * Dismisses OEM "Report this issue" overlays (phone reacting to automated taps) and app
     * crash/ANR dialogs. Returns a note when something was dismissed.
     */
    fun dismissSystemDialogs(snapshot: ScreenSnapshot?): String? {
        val snap = snapshot ?: return null
        val texts = snap.allTexts.joinToString(" | ").lowercase()
        if (REPORT_DIALOG.any { texts.contains(it) }) {
            val cancel = snap.elements.firstOrNull { val l = it.line.label.lowercase(); l == "cancel" || l == "close" || l == "dismiss" }
            val r = if (cancel != null) tapElement(cancel) else back()
            return "Dismissed a system 'Report this issue' dialog (${r.message}). Never tap 'Report this issue'."
        }
        if (CRASH_DIALOG.any { texts.contains(it) } && snap.elements.size <= 12) {
            val wait = snap.elements.firstOrNull { it.line.label.equals("Wait", ignoreCase = true) }
            val close = snap.elements.firstOrNull { it.line.label.equals("Close app", ignoreCase = true) || it.line.label.equals("Close", ignoreCase = true) || it.line.label.equals("OK", ignoreCase = true) }
            val target = wait ?: close
            if (target != null) {
                val r = tapElement(target)
                return "Dismissed an app crash/not-responding dialog by tapping '${target.line.label}' (${r.message})."
            }
        }
        return null
    }
}
