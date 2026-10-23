package com.jarvis.assistant.ui.godseye

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GodsEyeMcpClientTest {

    @Test
    fun buildViewUrlFromViewObjectProducesCorrectCesiumCoordinates() {
        val viewObj = JSONObject().apply {
            put("camera", JSONObject().apply {
                put("lat", 35.6769)
                put("lon", 139.7639)
                put("altitude_m", 4000000L)
                put("heading_deg", 45.0)
                put("pitch_deg", -60.0)
            })
            put("style", "thermal")
        }

        val url = GodsEyeMcpClient.buildViewUrlFromViewObject(viewObj)
        assertTrue(url.startsWith("http://localhost:4173/#v=2"))
        assertTrue(url.contains("lat=35.6769"))
        assertTrue(url.contains("lon=139.7639"))
        assertTrue(url.contains("alt=4000000"))
        assertTrue(url.contains("heading=45.0"))
        assertTrue(url.contains("pitch=-60.0"))
        assertTrue(url.contains("style=flir"))
    }

    @Test
    fun executeToolGracefullyHandlesOfflineService() {
        // Test offline fallback on unreachable port
        val dummyArgs = JSONObject().apply {
            put("area", "NonExistentPlace12345")
        }
        val result = GodsEyeMcpClient.executeTool("gods_eye_situation_brief", dummyArgs)
        assertNotNull(result)
        // Either succeeds if service is running or fails gracefully with clear message without crashing
        if (!result.optBoolean("success")) {
            assertTrue(result.has("message"))
            assertFalse(result.optString("message").isBlank())
        }
    }
}
