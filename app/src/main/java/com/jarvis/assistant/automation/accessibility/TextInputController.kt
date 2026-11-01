package com.jarvis.assistant.automation.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore

/**
 * Types and clears text in Android UI elements.
 * Uses ACTION_SET_TEXT primarily, falling back to clipboard + ACTION_PASTE for stubborn webviews and fields.
 * All node actions run on the main thread (ported AndroLLM behaviour).
 */
class TextInputController(
    private val context: Context,
    private val gestures: GestureExecutor
) {
    companion object {
        private const val TAG = "JarvisTextInput"
    }

    suspend fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) }
        if (ok) return true
        AccessibilityDebugStore.record("input", "SET_TEXT failed - clipboard fallback")
        return pasteViaClipboard(node, text)
    }

    suspend fun clear(node: AccessibilityNodeInfo): Boolean = setText(node, "")

    private suspend fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        return MainThread.action {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("jarvis_input", text))
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                Log.w(TAG, "pasteViaClipboard failed: ${e.message}")
                false
            }
        }
    }
}
