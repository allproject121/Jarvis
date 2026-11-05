package com.jarvis.assistant.androllm.accessibility.settings

/**
 * Ported from AndroLLM (core/accessibility/settings/AccessibilitySettings).
 *
 * User-configurable behaviour of the accessibility automation engine. The
 * master switch is the Android system toggle for JarvisAccessibilityService;
 * everything here tunes how aggressively the engine acts.
 *
 * Jarvis note: [llmPlanning] is additionally gated by
 * AndroLlmFlags.LLM_UI_PLANNER (default OFF) — both must be on for the LLM
 * planner to run; otherwise the deterministic heuristic planner is used.
 */
data class AccessibilitySettings(
    /** Scroll list views until the target element is visible before tapping. */
    val autoScrollIntoView: Boolean = true,
    /** Use a language model (via ModelRouter) to pick the next UI action. */
    val llmPlanning: Boolean = true,
    /** Ask for confirmation before high-risk steps (sending, paying, deleting…). */
    val confirmHighRisk: Boolean = true,
    /** Hard cap on planning steps per task (loop guard). */
    val maxSteps: Int = 12,
    /** Developer mode: record full screen dumps and the execution log. */
    val developerMode: Boolean = false,
    /** Merge on-device OCR text (ML Kit) into screen analysis. */
    val ocrEnabled: Boolean = false
) {
    companion object {
        const val MIN_STEPS = 2
        const val MAX_STEPS = 40
    }
}
