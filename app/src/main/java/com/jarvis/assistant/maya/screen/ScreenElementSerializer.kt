package com.jarvis.assistant.maya.screen

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.ScreenSignature
import com.jarvis.assistant.automation.agent.ScreenSignatureCalculator
import com.jarvis.assistant.automation.tools.AppIndex

/** One actionable element of a snapshot with its live node and bounds (for exact tap_id). */
class SnapshotElement(
    val line: ElementLine,
    val bounds: Rect,
    val node: AccessibilityNodeInfo?,
    val className: String,
    val resourceId: String?
)

/** Maya-style view of the current screen: element lines + id→node map + change signature. */
class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val elements: List<SnapshotElement>,
    val signature: ScreenSignature,
    val takenAt: Long,
    val truncated: Boolean,
    val isOwnApp: Boolean,
    val allTexts: List<String>
) {
    val byId: Map<Int, SnapshotElement> = elements.associateBy { it.line.id }

    fun header(): String = "App on screen: $appLabel ($packageName)"

    fun elementsBlock(assistantName: String, limit: Int = ScreenElementFormat.DEFAULT_LIMIT): String {
        val header = StringBuilder(header())
        if (isOwnApp) header.append(' ').append(ScreenExecutorPrompt.ownAppWarning(assistantName))
        return ScreenElementFormat.render(header.toString(), elements.map { it.line }, limit)
    }

    fun unlabeledRatio(): Float = ScreenElementFormat.unlabeledRatio(elements.map { it.line })

    fun containsText(needle: String): Boolean {
        val n = needle.trim().lowercase()
        if (n.isEmpty()) return false
        return allTexts.any { it.lowercase().contains(n) }
    }

    fun find(label: String): List<SnapshotElement> {
        val target = label.trim().lowercase()
        if (target.isEmpty()) return emptyList()
        val labeled = elements.filter { it.line.label != ScreenElementFormat.NO_LABEL }
        val exact = labeled.filter { it.line.label.lowercase() == target }
        if (exact.isNotEmpty()) return exact
        // composite row labels ("Rahul | Hi") — match one of the parts exactly
        val part = labeled.filter { e -> e.line.label.lowercase().split(" | ").any { it.trim() == target } }
        if (part.isNotEmpty()) return part
        val contains = labeled.filter { it.line.label.lowercase().contains(target) || (target.contains(it.line.label.lowercase()) && it.line.label.length >= 3) }
        if (contains.isNotEmpty()) return contains.sortedBy { it.line.label.length }
        val targetWords = target.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (targetWords.isEmpty()) return emptyList()
        return labeled.map { e ->
            val words = e.line.label.lowercase().split(Regex("\\s+")).toSet()
            e to (targetWords.intersect(words).size)
        }.filter { it.second > 0 && it.second.toFloat() / targetWords.size >= 0.5f }
            .sortedByDescending { it.second }.map { it.first }
    }
}

/**
 * Walks the accessibility tree and produces the Maya "SCREEN ELEMENTS" list: one line per
 * actionable/visible node, REAL device px centre points, de-duplicated, truncated. Keeps the
 * last snapshot so `tap_element(id)` is an exact lookup.
 */
object ScreenElementSerializer {

    private const val TAG = "MayaScreenSerializer"
    private const val MAX_NODES = 900
    private const val MAX_DEPTH = 40
    const val SERVICE_OFF = "Accessibility service OFF hai — Settings > Accessibility me Jarvis ON karo"

    @Volatile
    var last: ScreenSnapshot? = null
        private set

    fun service(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    fun isServiceOn(): Boolean = JarvisAccessibilityService.instance != null

    fun foregroundPackage(): String = try {
        service()?.getActiveRootNode()?.packageName?.toString() ?: ""
    } catch (_: Exception) { "" }

    fun appLabel(context: Context, pkg: String): String {
        if (pkg.isBlank()) return ""
        try {
            AppIndex.all(context).firstOrNull { it.packageName == pkg }?.let { return it.label }
        } catch (_: Exception) {}
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun signature(): ScreenSignature? = try {
        service()?.getActiveRootNode()?.let { ScreenSignatureCalculator.computeSignature(it) }
    } catch (_: Exception) { null }

    /** Null when the accessibility service is off or no window is available. */
    fun capture(context: Context, limit: Int = ScreenElementFormat.DEFAULT_LIMIT): ScreenSnapshot? {
        val svc = service() ?: return null
        val root = try { svc.getActiveRootNode() } catch (_: Exception) { null } ?: return null
        val pkg = root.packageName?.toString() ?: ""
        val own = pkg == context.packageName
        val collected = ArrayList<SnapshotElement>()
        val texts = ArrayList<String>()
        try {
            walk(root, collected, texts)
            // Dialogs / popups living in other application windows on top of the active one.
            try {
                val others = svc.windows ?: emptyList<AccessibilityWindowInfo>()
                for (w in others) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION && w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                    val r = w.root ?: continue
                    if (r == root) continue
                    val wp = r.packageName?.toString() ?: ""
                    if (wp == pkg || (wp == context.packageName && !own)) continue
                    if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && !w.isFocused) continue
                    walk(r, collected, texts)
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "walk failed: ${e.message}")
        }
        // dedupe + renumber so #N is contiguous and stable for this snapshot
        val deduped = ScreenElementFormat.dedupe(collected.map { it.line })
        val keptKeys = deduped.map { lineKey(it) }.toHashSet()
        val kept = collected.filter { lineKey(it.line) in keptKeys }.distinctBy { lineKey(it.line) }
        val renumbered = kept.mapIndexed { i, e -> SnapshotElement(e.line.copy(id = i + 1), e.bounds, e.node, e.className, e.resourceId) }
        val signature = try { ScreenSignatureCalculator.computeSignature(root) } catch (_: Exception) {
            ScreenSignature(pkg, "", renumbered.size, renumbered.size.toLong())
        }
        val snap = ScreenSnapshot(
            packageName = pkg,
            appLabel = appLabel(context, pkg),
            elements = renumbered,
            signature = signature,
            takenAt = System.currentTimeMillis(),
            truncated = renumbered.size > limit,
            isOwnApp = own,
            allTexts = texts.distinct()
        )
        last = snap
        return snap
    }

    private fun lineKey(l: ElementLine): String = l.label.lowercase() + "|" + l.role + "|" + (l.x / 12) + "|" + (l.y / 12)

    /** All visible text lines (for read_screen_text / watches) without building element lines. */
    fun allVisibleText(): List<String> {
        val root = try { service()?.getActiveRootNode() } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<String>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var count = 0
        val rect = Rect()
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            count++
            try {
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) out.add(t)
                else {
                    val d = node.contentDescription?.toString()?.trim()
                    if (!d.isNullOrEmpty() && node.childCount == 0) out.add(d)
                }
                if (depth < MAX_DEPTH) {
                    for (i in node.childCount - 1 downTo 0) {
                        val c = node.getChild(i) ?: continue
                        stack.addLast(c to depth + 1)
                    }
                }
            } catch (_: Exception) {}
        }
        return out
    }

    // ------------------------------------------------------------------ tree walk

    private fun walk(root: AccessibilityNodeInfo, out: MutableList<SnapshotElement>, texts: MutableList<String>) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var count = 0
        val rect = Rect()
        var nextId = out.size + 1
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            count++
            try {
                if (!node.isVisibleToUser) continue
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val className = node.className?.toString() ?: ""
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) texts.add(text) else if (desc.isNotEmpty() && node.childCount == 0) texts.add(desc)
                val clickable = node.isClickable
                val longClickable = node.isLongClickable
                val checkable = node.isCheckable
                val editable = node.isEditable || className.contains("EditText")
                val scrollable = node.isScrollable
                val actionable = clickable || longClickable || checkable || editable || scrollable
                var rawLabel: String? = when {
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    else -> null
                }
                if (rawLabel == null && editable) {
                    rawLabel = hintOf(node)
                }
                if (rawLabel == null && clickable) {
                    rawLabel = childLabel(node, 0)
                }
                val hasLabel = !rawLabel.isNullOrBlank()
                val isStaticText = !actionable && hasLabel && (className.contains("TextView") || node.childCount == 0)
                if (!actionable && !isStaticText) {
                    pushChildren(node, depth, stack)
                    continue
                }
                // Unlabeled scroll containers add noise (scroll/swipe verbs cover them)
                if (scrollable && !clickable && !checkable && !editable && !hasLabel) {
                    pushChildren(node, depth, stack)
                    continue
                }
                // Skip a non-actionable text child whose clickable ancestor already carries its label
                if (isStaticText && hasAncestorWithSameLabel(node, rawLabel!!)) {
                    pushChildren(node, depth, stack)
                    continue
                }
                val label = ScreenElementFormat.cleanLabel(rawLabel)
                val resId = node.viewIdResourceName
                val idHint = ScreenElementFormat.resourceIdSuffix(resId)
                val role = ScreenElementFormat.roleFor(className, clickable, checkable, editable, scrollable, hasLabel)
                val state: String? = when {
                    checkable -> if (node.isChecked) "currently ON" else "currently OFF"
                    node.isSelected -> "selected"
                    editable && node.isFocused -> "focused"
                    !node.isEnabled -> "disabled"
                    else -> null
                }
                val note = ScreenElementFormat.sendHint(idHint, label)
                val line = ElementLine(
                    id = nextId++,
                    label = label,
                    role = role,
                    state = state,
                    idHint = idHint,
                    x = rect.centerX(),
                    y = rect.centerY(),
                    note = note
                )
                out.add(SnapshotElement(line, Rect(rect), node, className, resId))
            } catch (e: Exception) {
                Log.d(TAG, "node skipped: ${e.message}")
            }
            pushChildren(node, depth, stack)
        }
    }

    private fun pushChildren(node: AccessibilityNodeInfo, depth: Int, stack: ArrayDeque<Pair<AccessibilityNodeInfo, Int>>) {
        if (depth >= MAX_DEPTH) return
        try {
            for (i in node.childCount - 1 downTo 0) {
                val c = node.getChild(i) ?: continue
                stack.addLast(c to depth + 1)
            }
        } catch (_: Exception) {}
    }

    private fun hintOf(node: AccessibilityNodeInfo): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }
    }

    /** Joins the first few descendant texts of a clickable container ("Rahul | Hi, kaise ho"). */
    private fun childLabel(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 3) return null
        val parts = ArrayList<String>()
        try {
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                val t = c.text?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty()) parts.add(t) else childLabel(c, depth + 1)?.let { parts.add(it) }
                if (parts.size >= 3) break
            }
        } catch (_: Exception) {}
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.take(ScreenElementFormat.MAX_LABEL_CHARS)
    }

    private fun hasAncestorWithSameLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        var p: AccessibilityNodeInfo? = try { node.parent } catch (_: Exception) { null }
        var hops = 0
        val target = label.trim().lowercase()
        while (p != null && hops < 3) {
            try {
                if (p.isClickable) {
                    val pl = (p.text?.toString()?.trim().orEmpty().ifEmpty { p.contentDescription?.toString()?.trim().orEmpty() }).lowercase()
                    if (pl == target) return true
                    if (pl.isEmpty() && (childLabel(p, 0) ?: "").lowercase().startsWith(target)) return true
                    return false
                }
                p = p.parent
            } catch (_: Exception) { return false }
            hops++
        }
        return false
    }

    fun assistantName(): String = try { JarvisApp.instance.preferences.assistantName.ifBlank { "Jarvis" } } catch (_: Exception) { "Jarvis" }
}
