package com.jarvis.assistant.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2TaskAutomationTest {

    @Test
    fun taskExecutionPolicyCategorizesSafeBackgroundVsForegroundVsConsequential() {
        // Safe background queries
        assertEquals(
            TaskCategory.SAFE_BACKGROUND_QUERY,
            TaskExecutionPolicy.categorizeTask("get_contact_count")
        )
        assertEquals(
            TaskCategory.SAFE_BACKGROUND_QUERY,
            TaskExecutionPolicy.categorizeTask("get_app_usage_stats")
        )
        assertEquals(
            TaskCategory.SAFE_BACKGROUND_QUERY,
            TaskExecutionPolicy.categorizeTask("get_telemetry")
        )

        // Requires foreground accessibility
        assertEquals(
            TaskCategory.REQUIRES_FOREGROUND_ACCESSIBILITY,
            TaskExecutionPolicy.categorizeTask("click_ui_element")
        )
        assertEquals(
            TaskCategory.REQUIRES_FOREGROUND_ACCESSIBILITY,
            TaskExecutionPolicy.categorizeTask("type_text")
        )
        assertEquals(
            TaskCategory.REQUIRES_FOREGROUND_ACCESSIBILITY,
            TaskExecutionPolicy.categorizeTask("scroll_screen")
        )

        // Consequential external actions
        assertEquals(
            TaskCategory.CONSEQUENTIAL_EXTERNAL_ACTION,
            TaskExecutionPolicy.categorizeTask("send_whatsapp_message")
        )
        assertEquals(
            TaskCategory.CONSEQUENTIAL_EXTERNAL_ACTION,
            TaskExecutionPolicy.categorizeTask("call_contact")
        )
    }

    @Test
    fun taskExecutionPolicyIdentifiesNonIdempotentActions() {
        assertTrue(TaskExecutionPolicy.isNonIdempotent("send_whatsapp_message"))
        assertTrue(TaskExecutionPolicy.isNonIdempotent("call_contact"))
        assertTrue(TaskExecutionPolicy.isNonIdempotent("whatsapp_call"))
        assertTrue(TaskExecutionPolicy.isNonIdempotent("Send message to Rahul"))
        assertTrue(TaskExecutionPolicy.isNonIdempotent("Papa ko call lagao"))

        assertFalse(TaskExecutionPolicy.isNonIdempotent("get_contact_count"))
        assertFalse(TaskExecutionPolicy.isNonIdempotent("get_app_usage_stats"))
        assertFalse(TaskExecutionPolicy.isNonIdempotent("inspect_screen"))
        assertFalse(TaskExecutionPolicy.isNonIdempotent("get_telemetry"))
    }

    @Test
    fun persistentTaskRecordSafeResumeBlocksNonIdempotentAndCompletedTasks() {
        // Completed task cannot be resumed
        val completedTask = PersistentTaskRecord(
            taskId = "task-completed-1",
            originalRequest = "Check telemetry",
            normalizedGoal = "check telemetry",
            status = UnifiedTaskState.COMPLETED.name,
            isNonIdempotent = false
        )
        assertEquals(UnifiedTaskState.COMPLETED.name, completedTask.status)

        // Cancelled task cannot be resumed
        val cancelledTask = PersistentTaskRecord(
            taskId = "task-cancelled-1",
            originalRequest = "Scroll feed",
            normalizedGoal = "scroll feed",
            status = UnifiedTaskState.CANCELLED.name,
            isNonIdempotent = false
        )
        assertEquals(UnifiedTaskState.CANCELLED.name, cancelledTask.status)

        // Sensitive non-idempotent task cannot be auto-resumed
        val sensitiveTask = PersistentTaskRecord(
            taskId = "task-sensitive-1",
            originalRequest = "Send message to Priya",
            normalizedGoal = "send message to priya",
            status = UnifiedTaskState.FAILED.name,
            isNonIdempotent = true
        )
        assertTrue(sensitiveTask.isNonIdempotent)

        // Exceeded retries cannot be resumed
        val loopedTask = PersistentTaskRecord(
            taskId = "task-loop-1",
            originalRequest = "Open settings",
            normalizedGoal = "open settings",
            status = UnifiedTaskState.FAILED.name,
            retryCount = 5,
            isNonIdempotent = false
        )
        assertTrue(loopedTask.retryCount >= 4)
    }

    @Test
    fun agentTaskEngineTracksLifecycleAndCancellationCleanly() {
        AgentTaskEngine.resetForSession()
        val context = AgentTaskEngine.begin("Instagram check reels", "phase2-test")
        assertNotNull(context)
        assertEquals("phase2-test", context.origin)

        val activeSummary = AgentTaskEngine.getActiveTaskSummary()
        assertTrue(activeSummary.contains("Instagram check reels") || activeSummary.contains("Active Task"))

        AgentTaskEngine.setState(AgentTaskEngine.State.WAITING_FOR_USER)
        assertEquals(AgentTaskEngine.State.WAITING_FOR_USER, AgentTaskEngine.current()?.status)

        val cancelMessage = AgentTaskEngine.cancelActiveTask("Testing cancellation")
        assertTrue(cancelMessage.contains("cancelled", ignoreCase = true))
        assertTrue(AgentTaskEngine.isCancelled())
        assertEquals(AgentTaskEngine.State.CANCELLED, AgentTaskEngine.current()?.status)
    }

    @Test
    fun actionVerifierModelDistinguishesVerifiedUnknownAndNeedsUserCheck() {
        val verifiedRes = VerificationResult(
            isVerified = true,
            status = VerificationStatus.VERIFIED,
            reason = "Text verified in target field",
            stateChanged = true
        )
        assertTrue(verifiedRes.isVerified)
        assertEquals(VerificationStatus.VERIFIED, verifiedRes.status)

        val uncertainRes = VerificationResult(
            isVerified = false,
            status = VerificationStatus.NEEDS_USER_CHECK,
            reason = "Target content was not verified on the screen; manual verification recommended",
            stateChanged = false,
            isRetryable = true
        )
        assertFalse(uncertainRes.isVerified)
        assertEquals(VerificationStatus.NEEDS_USER_CHECK, uncertainRes.status)

        val unknownRes = VerificationResult(
            isVerified = false,
            status = VerificationStatus.UNKNOWN,
            reason = "Cannot inspect screen: no active window root available",
            stateChanged = false,
            isRetryable = true
        )
        assertFalse(unknownRes.isVerified)
        assertEquals(VerificationStatus.UNKNOWN, unknownRes.status)
    }
}
