package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Dispatches touch gestures and navigation actions via [AccessibilityService].
 */
class GestureExecutor(
    private val serviceRef: () -> AccessibilityService?
) {
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JarvisGestures"
    }

    suspend fun tap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        return dispatch(path, 60L, "tap@(${bounds.centerX()},${bounds.centerY()})")
    }

    suspend fun longPress(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        return dispatch(path, 700L, "longPress")
    }

    suspend fun doubleTap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val c1 = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val c2 = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke1 = GestureDescription.StrokeDescription(c1, 0, 70)
        val stroke2 = GestureDescription.StrokeDescription(c2, 130, 70)
        return dispatchMulti(listOf(stroke1, stroke2), "doubleTap")
    }

    suspend fun swipe(fromX: Float, fromY: Float, dx: Float, dy: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(fromX + dx, fromY + dy)
        }
        return dispatch(path, durationMs, "swipe(${dx.toInt()},${dy.toInt()})")
    }

    suspend fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 600): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(path, durationMs, "drag")
    }

    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean): Boolean {
        val span = 0.18f * minOf(cx, cy).coerceAtLeast(100f)
        val inner = 0.15f * span
        val stroke1 = if (zoomIn) {
            GestureDescription.StrokeDescription(linePath(cx - span, cy, cx - inner, cy), 0, 350)
        } else {
            GestureDescription.StrokeDescription(linePath(cx - inner, cy, cx - span, cy), 0, 350)
        }
        val stroke2 = if (zoomIn) {
            GestureDescription.StrokeDescription(linePath(cx + span, cy, cx + inner, cy), 0, 350)
        } else {
            GestureDescription.StrokeDescription(linePath(cx + inner, cy, cx + span, cy), 0, 350)
        }
        return dispatchMulti(listOf(stroke1, stroke2), if (zoomIn) "pinchIn" else "pinchOut")
    }

    private fun linePath(x0: Float, y0: Float, x1: Float, y1: Float): Path =
        Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }

    /**
     * Clicks [node]: ACTION_CLICK first (on the main thread, as the framework
     * requires), walking up to the nearest clickable ancestor when the label
     * node itself isn't clickable, then a coordinate tap on its bounds.
     */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val clicked = MainThread.action {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && hops < 4) {
                target = target.parent
                hops++
            }
            (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (clicked) {
            AccessibilityDebugStore.record("gesture", "click(action)")
            return true
        }
        val bounds = Rect()
        MainThread.node { node.getBoundsInScreen(bounds) }
        return try {
            tap(bounds)
        } catch (e: Exception) {
            Log.w(TAG, "clickNode error: ${e.message}")
            false
        }
    }

    suspend fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }

    suspend fun scrollBackward(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }

    /** System-level actions (back / home / recents …), dispatched on the main thread. */
    suspend fun globalAction(action: Int): Boolean {
        val s = serviceRef() ?: return false
        val ok = MainThread.action { s.performGlobalAction(action) }
        AccessibilityDebugStore.record("gesture", "globalAction($action) = $ok")
        return ok
    }

    private suspend fun dispatch(path: Path, durationMs: Long, label: String): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchMulti(listOf(stroke), label)
    }

    private suspend fun dispatchMulti(
        strokes: List<GestureDescription.StrokeDescription>,
        label: String
    ): Boolean {
        val service = serviceRef() ?: return false
        val gesture = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
        AccessibilityDebugStore.record("gesture", label)
        return suspendCancellableCoroutine { cont ->
            try {
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    handler
                )
                if (!dispatched && cont.isActive) cont.resume(false)
            } catch (e: Exception) {
                Log.w(TAG, "dispatch gesture failed: ${e.message}")
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
