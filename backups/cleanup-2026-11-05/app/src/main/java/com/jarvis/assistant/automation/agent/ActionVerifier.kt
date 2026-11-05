package com.jarvis.assistant.automation.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Closed-loop action verification engine.
 * Never assumes an action succeeded just because a gesture or API call returned true.
 * Performs rigorous before/after state comparison.
 */
object ActionVerifier {
    private const val TAG = "ActionVerifier"

    /**
     * Verifies that typing actually inserted the expected text into the target input field.
     */
    suspend fun verifyTyping(
        service: JarvisAccessibilityService,
        targetRect: Rect?,
        expectedText: String,
        retries: Int = 2
    ): VerificationResult {
        val cleanExpected = expectedText.trim()
        if (cleanExpected.isEmpty()) {
            return VerificationResult(isVerified = true, reason = "Empty text to type")
        }

        for (attempt in 0..retries) {
            delay(80)
            val root = service.getActiveRootNode() ?: service.getAppRootNode()
            if (root == null) {
                if (attempt < retries) continue
                return VerificationResult(
                    isVerified = false,
                    reason = "No active root node found to verify typed text"
                )
            }

            // 1. Check node at target rect
            var targetNode: AccessibilityNodeInfo? = null
            if (targetRect != null && targetRect.width() > 0) {
                targetNode = service.findNodeCoveringRect(root, targetRect)
            }

            // A target rectangle must resolve back to that same visible editable field.
            // Falling back to an unrelated focused/first editable field could verify
            // text in the wrong input and falsely report success.
            if (targetNode == null) {
                if (targetRect == null) {
                    targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: service.findNodeWithInputFocus(root)
                }
            }

            if (targetNode != null && targetNode.isVisibleToUser && targetNode.isEditable) {
                val actualText = targetNode.text?.toString() ?: ""
                // Do not log entered text: it may contain searches, messages, or credentials.
                Log.d(TAG, "Verify typing: target length=${actualText.length}, expected length=${cleanExpected.length}")
                val normalize = { value: String ->
                    value.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
                }

                if (normalize(actualText) == normalize(cleanExpected)) {
                    return VerificationResult(
                        isVerified = true,
                        reason = "Text verified in target field",
                        stateChanged = true,
                        actualValue = null
                    )
                }
            }

        }

        return VerificationResult(
            isVerified = false,
            reason = "Text mismatch: the target input value did not match the requested text",
            stateChanged = false,
            isRetryable = true
        )
    }

    /**
     * Verifies that a click caused a measurable UI state change.
     */
    suspend fun verifyClick(
        preSignature: ScreenSignature,
        service: JarvisAccessibilityService,
        waitMs: Long = 300L
    ): VerificationResult {
        delay(waitMs)
        val postRoot = service.getActiveRootNode() ?: service.getAppRootNode()
        val postSignature = ScreenSignatureCalculator.computeSignature(postRoot)

        // 1. Window or package transition
        if (postSignature.packageName != preSignature.packageName ||
            postSignature.className != preSignature.className) {
            return VerificationResult(
                isVerified = false,
                reason = "Window transitioned, but the requested destination was not checked",
                stateChanged = true
            )
        }

        // 2. Element tree change
        if (!postSignature.isEquivalentTo(preSignature)) {
            return VerificationResult(
                isVerified = false,
                reason = "UI content changed, but the requested result was not checked",
                stateChanged = true
            )
        }

        // Slight extra delay in case of slow animation
        delay(200L)
        val secondCheckRoot = service.getActiveRootNode() ?: service.getAppRootNode()
        val secondSignature = ScreenSignatureCalculator.computeSignature(secondCheckRoot)
        if (!secondSignature.isEquivalentTo(preSignature)) {
            return VerificationResult(
                isVerified = false,
                reason = "UI transitioned after layout animation, but the requested result was not checked",
                stateChanged = true
            )
        }

        return VerificationResult(
            isVerified = false,
            reason = "No detectable UI change after click (UI state remained identical)",
            stateChanged = false,
            isRetryable = true
        )
    }

    /**
     * Verifies that a scroll produced new visible items or reached the end of list.
     */
    fun verifyScroll(
        preSignature: ScreenSignature,
        postSignature: ScreenSignature
    ): VerificationResult {
        if (postSignature.isEquivalentTo(preSignature)) {
            return VerificationResult(
                isVerified = false,
                reason = "No visible scroll progress detected; the list end is unproven",
                stateChanged = false
            )
        }
        return VerificationResult(
            isVerified = true,
            reason = "Scroll revealed new content",
            stateChanged = true
        )
    }

    /**
     * Verifies whether target content is visibly present on screen, returning explicit
     * UNKNOWN or NEEDS_USER_CHECK outcomes when state is inconclusive.
     */
    fun verifyTargetVisibleOrUncertain(
        service: JarvisAccessibilityService,
        expectedText: String,
        targetPackage: String? = null
    ): VerificationResult {
        val root = service.getActiveRootNode() ?: service.getAppRootNode()
        if (root == null) {
            return VerificationResult(
                isVerified = false,
                reason = "Cannot inspect screen: no active window root available",
                status = VerificationStatus.UNKNOWN,
                isRetryable = true
            )
        }

        if (targetPackage != null && root.packageName?.toString()?.contains(targetPackage, ignoreCase = true) != true) {
            return VerificationResult(
                isVerified = false,
                reason = "Target application is not in the foreground (current app: ${root.packageName})",
                status = VerificationStatus.NEEDS_USER_CHECK,
                isRetryable = false
            )
        }

        val cleanExpected = expectedText.trim().lowercase()
        if (cleanExpected.isBlank()) {
            return VerificationResult(
                isVerified = true,
                reason = "Target text query is empty",
                status = VerificationStatus.VERIFIED
            )
        }

        fun findMatching(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(cleanExpected) || desc.contains(cleanExpected)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findMatching(child)) return true
            }
            return false
        }

        val found = findMatching(root)
        return if (found) {
            VerificationResult(
                isVerified = true,
                reason = "Expected target content verified on active screen",
                status = VerificationStatus.VERIFIED,
                stateChanged = true
            )
        } else {
            VerificationResult(
                isVerified = false,
                reason = "Target content was not verified on the screen; manual verification recommended",
                status = VerificationStatus.NEEDS_USER_CHECK,
                isRetryable = true
            )
        }
    }
}
