package com.jarvis.assistant.androllm.accessibility.analyzer

import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore
import com.jarvis.assistant.androllm.accessibility.ocr.MlKitOcrEngine
import com.jarvis.assistant.androllm.accessibility.ocr.OcrEngine
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettings
import com.jarvis.assistant.androllm.accessibility.state.UIStateTracker
import com.jarvis.assistant.automation.accessibility.AccessibilityNodeFinder
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.accessibility.LiveTree
import com.jarvis.assistant.automation.accessibility.MainThread
import com.jarvis.assistant.automation.accessibility.UiElementType
import com.jarvis.assistant.automation.accessibility.UiNode
import com.jarvis.assistant.automation.accessibility.UiScreenSnapshot
import com.jarvis.assistant.automation.accessibility.UiTreeBuilder

/**
 * Ported from AndroLLM (core/accessibility/analyzer/ScreenAnalyzer).
 *
 * Produces [UiScreenSnapshot]s: reads the live window on the main thread,
 * builds the semantic tree, detects loading state, focus and selection, and
 * merges ML Kit OCR text when enabled. This is the single source of truth the
 * planners and local models see.
 */
object ScreenAnalyzer {

    private class Raw(
        val pkg: String,
        val tree: UiNode?,
        val focusedLive: String
    )

    private val ocrEngine: OcrEngine = MlKitOcrEngine

    suspend fun read(settings: AccessibilitySettings): UiScreenSnapshot {
        val service = JarvisAccessibilityService.instance ?: return UiScreenSnapshot()

        val raw = MainThread.node {
            val root = service.rootInActiveWindow ?: return@node null
            Raw(
                pkg = root.packageName?.toString().orEmpty(),
                tree = UiTreeBuilder.build(root),
                focusedLive = LiveTree.findFocused(root)?.let { LiveTree.labelOf(it) }.orEmpty()
            )
        }

        val context = UIStateTracker.snapshot()
        val uiRoot = raw?.tree
        val loading = uiRoot?.flatten()?.any { it.type == UiElementType.PROGRESS } ?: false
        UIStateTracker.setLoading(loading)

        val focused = uiRoot?.let { AccessibilityNodeFinder.findFocused(it) }?.label
            ?.takeIf { it.isNotBlank() }
            ?: raw?.focusedLive?.takeIf { it.isNotBlank() }
            ?: context.focusedText
        val selected = uiRoot?.flatten()?.firstOrNull { it.selected && it.hasLabel }?.label
            ?: context.selectedText

        var ocrLines = emptyList<String>()
        if (settings.ocrEnabled && ocrEngine.isAvailable) {
            JarvisAccessibilityService.captureScreenBitmap()?.let { bitmap ->
                ocrLines = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault(emptyList())
                runCatching { bitmap.recycle() }
            }
        }

        val snapshot = UiScreenSnapshot(
            // The live root's package is authoritative; the event tracker is a fallback.
            packageName = raw?.pkg?.takeIf { it.isNotBlank() } ?: context.currentPackage,
            windowTitle = context.windowTitle,
            root = uiRoot,
            focusedText = focused,
            selectedText = selected,
            loading = loading,
            ocrLines = ocrLines,
            capturedAt = System.currentTimeMillis()
        )
        if (settings.developerMode) {
            AccessibilityDebugStore.recordScreenDump(snapshot.describe(120))
        }
        return snapshot
    }
}
