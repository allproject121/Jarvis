package com.jarvis.assistant.automation.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * A selector describing which live node an action should target.
 */
data class UiSelector(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val type: UiElementType? = null,
    val index: Int = 0
) {
    val summary: String
        get() = buildString {
            text?.let { append("text=\"$it\" ") }
            textContains?.let { append("contains=\"$it\" ") }
            resourceId?.let { append("id=*$it ") }
            type?.let { append("type=${it.displayName} ") }
            if (index > 0) append("[$index]")
        }.trim()
}

/**
 * Traversal and matching helpers for semantic and live accessibility nodes.
 */
object AccessibilityNodeFinder {

    fun find(root: UiNode, selector: UiSelector): List<UiNode> =
        root.flatten().filter { matches(it, selector) }.toList()

    fun findOne(root: UiNode, selector: UiSelector): UiNode? {
        var seen = 0
        for (node in root.flatten()) {
            if (matches(node, selector)) {
                if (seen == selector.index) return node
                seen++
            }
        }
        return null
    }

    fun findEditable(root: UiNode): UiNode? =
        root.flatten().firstOrNull { it.type == UiElementType.TEXT_FIELD && it.enabled }

    fun findFocused(root: UiNode): UiNode? =
        root.flatten().firstOrNull { it.focused }

    private fun matches(node: UiNode, selector: UiSelector): Boolean {
        selector.text?.let { exact ->
            if (!node.label.equals(exact, ignoreCase = true)) return false
        }
        selector.textContains?.let { contains ->
            if (!node.label.contains(contains, ignoreCase = true)) return false
        }
        selector.resourceId?.let { id ->
            if (!node.resourceId.contains(id, ignoreCase = true)) return false
        }
        selector.type?.let { type ->
            if (node.type != type) return false
        }
        return true
    }
}

/**
 * Traversal on live [AccessibilityNodeInfo] objects.
 */
object LiveTree {

    fun findFocused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    fun collect(root: AccessibilityNodeInfo, max: Int = 500): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(64)
        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= max) return
            out.add(node)
            val count = node.childCount
            for (i in 0 until count) {
                if (out.size >= max) return
                walk(node.getChild(i) ?: continue)
            }
        }
        walk(root)
        return out
    }

    /**
     * First node matching [selector] (honouring [UiSelector.index]). When
     * [visibleOnly] is true (default), off-screen / hidden nodes are skipped so
     * the index counts only elements the user can actually see — matching the
     * semantic tree built by [UiTreeBuilder], which also drops invisible nodes.
     */
    fun find(
        root: AccessibilityNodeInfo,
        selector: UiSelector,
        visibleOnly: Boolean = true
    ): AccessibilityNodeInfo? {
        var seen = 0
        for (node in collect(root)) {
            if (visibleOnly && !node.isVisibleToUser) continue
            if (matches(node, selector)) {
                if (seen == selector.index) return node
                seen++
            }
        }
        return null
    }

    fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collect(root).firstOrNull { it.isEditable && it.isVisibleToUser }

    fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collect(root).firstOrNull { it.isScrollable && it.isVisibleToUser }

    fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()
            ?: ""

    private fun matches(node: AccessibilityNodeInfo, selector: UiSelector): Boolean {
        selector.text?.let { exact ->
            if (!labelOf(node).equals(exact, ignoreCase = true)) return false
        }
        selector.textContains?.let { contains ->
            if (!labelOf(node).contains(contains, ignoreCase = true)) return false
        }
        selector.resourceId?.let { id ->
            if (!node.viewIdResourceName.orEmpty().contains(id, ignoreCase = true)) return false
        }
        selector.type?.let { type ->
            val (t, _) = UiElementClassifier.classify(
                className = node.className?.toString(),
                resourceId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                editable = node.isEditable,
                clickable = node.isClickable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                scrollable = node.isScrollable,
                childCount = node.childCount
            )
            if (t != type) return false
        }
        return true
    }
}
