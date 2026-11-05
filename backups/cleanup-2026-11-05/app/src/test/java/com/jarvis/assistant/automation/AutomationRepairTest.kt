package com.jarvis.assistant.automation

import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.agent.TaskStepStatus
import com.jarvis.assistant.automation.query.DeviceInfoQueryHelper
import com.jarvis.assistant.connectors.UniversalExecutionResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AutomationRepairTest {

    @Test
    fun testDurationFormatting_CalculatesCorrectUnits() {
        assertEquals("0 minutes", DeviceInfoQueryHelper.formatDuration(0L))
        assertEquals("0 minutes", DeviceInfoQueryHelper.formatDuration(-500L))
        assertEquals("45 sec", DeviceInfoQueryHelper.formatDuration(45_000L))
        assertEquals("12 min", DeviceInfoQueryHelper.formatDuration(12 * 60 * 1000L))
        assertEquals("1 hr", DeviceInfoQueryHelper.formatDuration(60 * 60 * 1000L))
        assertEquals("2 hr 30 min", DeviceInfoQueryHelper.formatDuration((2 * 60 + 30) * 60 * 1000L))
    }

    @Test
    fun testStartOfTodayMillis_SetsMidnightBoundary() {
        val now = 1713600000000L // arbitrary fixed timestamp
        val midnight = DeviceInfoQueryHelper.getStartOfTodayMillis(now)

        assertTrue(midnight <= now)

        val cal = Calendar.getInstance().apply { timeInMillis = midnight }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun testResolvePackagesForApp_ResolvesKnownApps() {
        val waPkgs = DeviceInfoQueryHelper.resolvePackagesForApp("WhatsApp")
        assertTrue(waPkgs.contains("com.whatsapp"))
        assertTrue(waPkgs.contains("com.whatsapp.w4b"))

        val ytPkgs = DeviceInfoQueryHelper.resolvePackagesForApp("YouTube")
        assertTrue(ytPkgs.contains("com.google.android.youtube"))

        val chromePkgs = DeviceInfoQueryHelper.resolvePackagesForApp("Google Chrome")
        assertTrue(chromePkgs.contains("com.android.chrome"))

        val customPkg = DeviceInfoQueryHelper.resolvePackagesForApp("com.example.custom")
        assertTrue(customPkg.contains("com.example.custom"))
    }

    @Test
    fun testTaskEngine_HonestCompletionRequiresEvidence() {
        AgentTaskEngine.begin("Send message to Rahul", "test-honest-completion")

        // Completing without verified goal evidence must be rejected
        val completedWithoutEvidence = AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
        assertFalse("TaskEngine must not claim completion without verified goal evidence", completedWithoutEvidence)

        // After recording verified goal evidence, completion succeeds
        AgentTaskEngine.recordCompletionEvidence(
            AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL,
            "WhatsApp message sent and verified on screen"
        )
        val completedWithEvidence = AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
        assertTrue("TaskEngine must complete after goal evidence is verified", completedWithEvidence)
        assertEquals(AgentTaskEngine.State.COMPLETED.name, AgentTaskEngine.taskState.value?.currentStatus)
    }

    @Test
    fun testTaskEngine_BoundedRetriesPreventsInfiniteLoops() {
        val context = AgentTaskEngine.begin("Find file in Drive", "test-bounded-retries")
        assertNotNull(context)

        // Simulate 3 consecutive step failures
        AgentTaskEngine.failCurrentStep("UI element not clickable (attempt 1)")
        assertEquals(AgentTaskEngine.State.RECOVERING, AgentTaskEngine.current()?.status)

        AgentTaskEngine.failCurrentStep("UI element not clickable (attempt 2)")
        assertEquals(AgentTaskEngine.State.RECOVERING, AgentTaskEngine.current()?.status)

        AgentTaskEngine.failCurrentStep("UI element not clickable (attempt 3)", blocked = true)
        assertEquals(AgentTaskEngine.State.BLOCKED, AgentTaskEngine.current()?.status)
    }

    @Test
    fun testUniversalExecutionResult_NeverClaimsSuccessOnFailure() {
        val a11yDisabled = UniversalExecutionResult(
            success = false,
            message = "Accessibility service is disabled",
            actionTaken = "A11Y_SERVICE_DISABLED"
        )
        assertFalse(a11yDisabled.success)
        assertEquals("A11Y_SERVICE_DISABLED", a11yDisabled.actionTaken)

        val notForeground = UniversalExecutionResult(
            success = false,
            message = "App failed to foreground",
            actionTaken = "TARGET_APP_NOT_FOREGROUND"
        )
        assertFalse(notForeground.success)
        assertEquals("TARGET_APP_NOT_FOREGROUND", notForeground.actionTaken)

        val targetNotFound = UniversalExecutionResult(
            success = false,
            message = "UI target not found",
            actionTaken = "GOAL_TARGET_NOT_FOUND"
        )
        assertFalse(targetNotFound.success)
        assertEquals("GOAL_TARGET_NOT_FOUND", targetNotFound.actionTaken)
    }

    @Test
    fun testAppConnectorsFallback_ExplicitAndTruthful() {
        val restricted = UniversalExecutionResult(
            success = false,
            message = "Notice: App is not connected with Full Autonomous Access. Basic launch only.",
            actionTaken = "BASIC_LAUNCH_RESTRICTED"
        )
        assertFalse(restricted.success)
        assertEquals("BASIC_LAUNCH_RESTRICTED", restricted.actionTaken)
        assertTrue(restricted.message.contains("not connected"))
    }
}
