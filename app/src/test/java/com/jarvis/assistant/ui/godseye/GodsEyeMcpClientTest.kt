package com.jarvis.assistant.ui.godseye

import org.json.JSONArray
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
        assertTrue(url.startsWith(GodsEyeActivity.GODS_EYE_LOCAL_URL))
        assertTrue(url.contains("#v=2"))
        assertTrue(url.contains("lat=35.6769"))
        assertTrue(url.contains("lon=139.7639"))
        assertTrue(url.contains("alt=4000000"))
        assertTrue(url.contains("heading=45.0"))
        assertTrue(url.contains("pitch=-60.0"))
        assertTrue(url.contains("style=flir"))
    }

    @Test
    fun repeatedCameraNavigationProducesDeterministicHashes() {
        val targets = listOf(
            Triple(51.5074, -0.1278, "nvg"),
            Triple(35.6762, 139.6503, "crt"),
            Triple(21.3069, -157.8583, "normal")
        )

        targets.forEach { (lat, lon, style) ->
            val viewObj = JSONObject().apply {
                put("camera", JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("altitude_m", 1200000L)
                    put("heading_deg", 0.0)
                    put("pitch_deg", -90.0)
                })
                put("style", style)
            }
            val url = GodsEyeMcpClient.buildViewUrlFromViewObject(viewObj)
            assertTrue(url.startsWith(GodsEyeActivity.GODS_EYE_LOCAL_URL))
            assertTrue(url.contains("#v=2"))
            assertTrue(url.contains("lat=$lat"))
            assertTrue(url.contains("lon=$lon"))
            if (style != "normal") {
                assertTrue(url.contains("style=$style"))
            }
        }
    }

    @Test
    fun standaloneViewHandlesPlaceResolution() {
        val args = JSONObject().apply {
            put("location", "London")
            put("style", "nvg")
        }
        val result = GodsEyeMcpClient.executeStandaloneTool("gods_eye_show_view", args)
        assertTrue(result.optBoolean("success"))
        val viewUrl = result.optString("view_url")
        assertTrue(viewUrl.contains("lat=51.5074"))
        assertTrue(viewUrl.contains("lon=-0.1278"))
        assertTrue(viewUrl.contains("style=nvg"))
        assertEquals("London", result.optString("target"))
    }

    @Test
    fun standaloneOpenViewDefaultsCorrectly() {
        val args = JSONObject().apply {
            put("style", "thermal")
        }
        val result = GodsEyeMcpClient.executeStandaloneTool("open_gods_eye_view", args)
        assertTrue(result.optBoolean("success"))
        val viewUrl = result.optString("view_url")
        assertTrue(viewUrl.contains("style=flir"))
    }

    @Test
    fun standaloneCctvFindsCamerasAndGeneratesValidViewUrlWithLayer() {
        val args = JSONObject().apply {
            put("area", "London")
        }
        val result = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args)
        assertTrue(result.optBoolean("success"))
        val viewUrl = result.optString("view_url")
        assertTrue(viewUrl.contains("layers=cctv"))
        assertTrue(viewUrl.contains("lat=51."))
        assertTrue(viewUrl.contains("lon=-0."))
        assertEquals("London", result.optString("city"))
        assertTrue(result.optString("sample_camera_id").startsWith("tfl-"))
    }

    @Test
    fun standaloneFiresGeneratesValidViewUrlWithFirmsLayer() {
        val args = JSONObject().apply {
            put("location", "California")
        }
        val result = GodsEyeMcpClient.executeStandaloneTool("gods_eye_get_active_fires", args)
        assertTrue(result.optBoolean("success"))
        val viewUrl = result.optString("view_url")
        assertTrue(viewUrl.contains("layers=firms"))
        assertTrue(viewUrl.contains("style=flir"))
        assertEquals("FLIR", result.optString("thermal_mode"))
    }

    @Test
    fun standaloneSituationBriefProducesUnifiedBriefing() {
        val args = JSONObject().apply {
            put("area", "Persian Gulf")
        }
        val result = GodsEyeMcpClient.executeStandaloneTool("gods_eye_situation_brief", args)
        assertTrue(result.optBoolean("success"))
        val viewUrl = result.optString("view_url")
        assertTrue(viewUrl.contains("layers=earthquakes,military"))
        assertTrue(viewUrl.contains("style=flir"))
        assertTrue(result.has("summary"))
        assertTrue(result.has("earthquakes"))
        assertTrue(result.has("military"))
        assertTrue(result.has("launches"))
    }

    @Test
    fun executeStandaloneToolDispatchesAllRequiredTools() {
        val toolsToTest = listOf(
            "open_gods_eye_view",
            "gods_eye_show_view",
            "gods_eye_get_earthquakes",
            "gods_eye_military_awareness",
            "gods_eye_aircraft_in_area",
            "gods_eye_get_recent_launches",
            "gods_eye_satellites_overhead",
            "gods_eye_find_cctv_cameras",
            "gods_eye_situation_brief",
            "gods_eye_get_active_fires",
            "gods_eye_inspect_globe",
            "gods_eye_control_layers",
            "gods_eye_select_camera"
        )

        for (tool in toolsToTest) {
            val args = JSONObject().apply {
                put("location", "Tokyo")
                put("area", "Tokyo")
                put("camera_id", "tfl-00001.06502")
            }
            val res = GodsEyeMcpClient.executeStandaloneTool(tool, args)
            assertNotNull("Result for $tool should not be null", res)
            // Every tool returns success=true with view_url or structured error if upstream network unavailable
            assertTrue("Result for $tool must have success field", res.has("success"))
            if (res.optBoolean("success")) {
                assertTrue("Successful tool $tool must include view_url", res.has("view_url"))
            } else {
                assertTrue("Failed tool $tool must include message", res.has("message"))
            }
        }
    }

    @Test
    fun toolResponseSerializationMatchesProto3JsonFormat() {
        val callId = "call_abc123"
        val toolName = "gods_eye_show_view"
        val toolResult = JSONObject().apply {
            put("success", true)
            put("view_url", "https://appassets.androidplatform.net/index.html#v=2&lat=51.5&lon=-0.1")
        }

        val responseEntry = JSONObject().apply {
            put("id", callId)
            put("name", toolName)
            put("response", toolResult)
        }
        val responsesArray = JSONArray().put(responseEntry)
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", responsesArray)
            })
        }

        assertTrue(msg.has("toolResponse"))
        val toolResp = msg.getJSONObject("toolResponse")
        assertTrue(toolResp.has("functionResponses"))
        val arr = toolResp.getJSONArray("functionResponses")
        assertEquals(1, arr.length())
        val item = arr.getJSONObject(0)
        assertEquals(callId, item.getString("id"))
        assertEquals(toolName, item.getString("name"))
        assertTrue(item.getJSONObject("response").getBoolean("success"))
    }

    @Test
    fun executeToolGracefullyHandlesOfflineOrUnreachableService() {
        val dummyArgs = JSONObject().apply {
            put("area", "London")
        }
        val result = GodsEyeMcpClient.executeTool("gods_eye_show_view", dummyArgs)
        assertNotNull(result)
        // With loopback offline, immediately falls back to standalone handler and succeeds
        assertTrue(result.optBoolean("success"))
        assertTrue(result.has("view_url"))
        assertTrue(result.has("action_json"))
    }

    @Test
    fun buildActionJsonFromViewObjectProducesValidJson() {
        val viewObj = JSONObject().apply {
            put("camera", JSONObject().apply {
                put("lat", 35.6769)
                put("lon", 139.7639)
                put("altitude_m", 4000000L)
                put("heading_deg", 45.0)
                put("pitch_deg", -60.0)
            })
            put("style", "thermal")
            put("layers", "cctv")
        }

        val actionJsonStr = GodsEyeMcpClient.buildActionJsonFromViewObject(viewObj)
        val action = JSONObject(actionJsonStr)
        assertEquals(35.6769, action.getDouble("lat"), 0.0001)
        assertEquals(139.7639, action.getDouble("lon"), 0.0001)
        assertEquals(4000000L, action.getLong("alt"))
        assertEquals("flir", action.getString("style"))
        assertEquals("cctv", action.getString("layers"))
    }

    @Test
    fun standaloneCctvIncludesCameraIdInActionJson() {
        val args = JSONObject().apply {
            put("location", "London")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args)
        assertTrue(res.optBoolean("success"))
        assertTrue(res.has("action_json"))
        val action = JSONObject(res.getString("action_json"))
        assertEquals("cctv", action.getString("layers"))
        assertTrue(action.has("cameraId"))
        assertEquals(res.getString("sample_camera_id"), action.getString("cameraId"))
    }

    @Test
    fun standaloneEarthquakesIncludesEarthquakesLayerInActionJson() {
        val args = JSONObject().apply {
            put("area", "California")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_get_earthquakes", args)
        if (res.optBoolean("success")) {
            assertTrue(res.has("action_json"))
            val action = JSONObject(res.getString("action_json"))
            assertEquals("earthquakes", action.getString("layers"))
        }
    }

    @Test
    fun globalCctvCatalogSearchFindsSydneyCameras() {
        val args = JSONObject().apply {
            put("query", "Sydney")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args)
        assertTrue(res.optBoolean("success"))
        assertEquals("Sydney", res.optString("city"))
        assertTrue(res.optString("sample_camera_id").isNotBlank())
        assertTrue(res.optString("feed_type").isNotBlank())
    }

    @Test
    fun globalCctvCatalogSearchFindsAustinCameras() {
        val args = JSONObject().apply {
            put("area", "Austin")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args)
        assertTrue(res.optBoolean("success"))
        assertEquals("Austin", res.optString("city"))
        assertEquals("image", res.optString("feed_type"))
    }

    @Test
    fun genericCctvQueryDefaultsToFeaturedCamerasWithSuccess() {
        // Test query "cctv" as passed by Gemini voice command "god eye me cctv show karo"
        val args1 = JSONObject().apply {
            put("area", "cctv")
        }
        val res1 = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args1)
        assertTrue(res1.optBoolean("success"))
        assertTrue(res1.optBoolean("found"))
        assertTrue(res1.has("sample_camera_id"))
        assertTrue(res1.has("view_url"))

        // Test empty/blank query
        val args2 = JSONObject()
        val res2 = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args2)
        assertTrue(res2.optBoolean("success"))
        assertTrue(res2.optBoolean("found"))
    }

    @Test
    fun cctvSearchUnmatchedLocationReturnsHonestFailureWithoutLondonFallback() {
        val args = JSONObject().apply {
            put("location", "Tokyo")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_find_cctv_cameras", args)
        assertFalse(res.optBoolean("success"))
        assertFalse(res.optBoolean("found"))
        assertTrue(res.optString("message").contains("Tokyo", ignoreCase = true))
        assertTrue(res.has("supported_cities"))
    }

    @Test
    fun inspectGlobeReturnsValidGlobeState() {
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_inspect_globe", JSONObject())
        assertTrue(res.optBoolean("success"))
        assertTrue(res.has("globe_state"))
        val state = res.getJSONObject("globe_state")
        assertTrue(state.has("lat"))
        assertTrue(state.has("lon"))
        assertTrue(state.has("alt"))
        assertTrue(state.has("visual_style"))
    }

    @Test
    fun controlLayersEnablesAndDisablesSpecifiedLayers() {
        val args = JSONObject().apply {
            put("enable", JSONArray().put("earthquakes").put("cctv"))
            put("disable", JSONArray().put("flights"))
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_control_layers", args)
        assertTrue(res.optBoolean("success"))
        assertTrue(res.has("action_json"))
        val action = JSONObject(res.getString("action_json"))
        assertTrue(action.has("enable_layers"))
        assertTrue(action.has("disable_layers"))
        assertTrue(action.has("trace_id"))
    }

    @Test
    fun selectCameraFindsAndSelectsCameraWithAction() {
        val args = JSONObject().apply {
            put("camera_id", "tfl-00001.06502")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_select_camera", args)
        assertTrue(res.optBoolean("success"))
        assertTrue(res.has("camera"))
        assertEquals("tfl-00001.06502", res.getJSONObject("camera").getString("id"))
        val action = JSONObject(res.getString("action_json"))
        assertEquals("tfl-00001.06502", action.getString("cameraId"))
        assertEquals("cctv", action.getString("layers"))
    }

    @Test
    fun selectCameraInvalidIdReturnsHonestError() {
        val args = JSONObject().apply {
            put("camera_id", "nonexistent-camera-99999")
        }
        val res = GodsEyeMcpClient.executeStandaloneTool("gods_eye_select_camera", args)
        assertFalse(res.optBoolean("success"))
        assertTrue(res.optString("message").contains("not found"))
    }

    @Test
    fun findCameraResolvesRealCameraUrlsAndSnapshots() {
        // Sydney camera
        val sydneyCam = GodsEyeMcpClient.findCamera("nsw-201")
        assertNotNull("Sydney camera should be found", sydneyCam)
        assertTrue("Sydney camera must have non-empty url or snapshotUrl", sydneyCam!!.url.isNotBlank() || sydneyCam.snapshotUrl.isNotBlank())

        // London camera
        val londonCam = GodsEyeMcpClient.findCamera("tfl-00001.06502")
        assertNotNull("London camera should be found", londonCam)
        assertTrue("London camera url must point to mp4 stream", londonCam!!.url.contains(".mp4"))

        // Austin camera
        val austinCam = GodsEyeMcpClient.findCamera("354")
        assertNotNull("Austin camera should be found", austinCam)
        assertTrue("Austin camera snapshotUrl must contain .jpg", austinCam!!.snapshotUrl.contains(".jpg"))
    }

    @Test
    fun awaitActionReceiptRetrievesRecordedReceiptWithinTimeout() {
        val testTraceId = "tr-test-" + System.currentTimeMillis()
        val testReceiptJson = JSONObject().apply {
            put("trace_id", testTraceId)
            put("status", "succeeded")
            put("state", JSONObject().apply {
                put("lat", 30.2672)
                put("lon", -97.7431)
                put("alt", 50000)
                put("visual_style", "normal")
            })
        }.toString()

        // Background thread records receipt after 50ms
        Thread {
            Thread.sleep(50)
            GodsEyeActivity.recordActionReceipt(testTraceId, testReceiptJson)
        }.start()

        val receipt = GodsEyeActivity.awaitActionReceipt(testTraceId, 300)
        assertNotNull("Receipt should be retrieved within timeout", receipt)
        assertEquals("succeeded", receipt!!.optString("status"))
        assertEquals(30.2672, receipt.getJSONObject("state").getDouble("lat"), 0.001)
    }
}
