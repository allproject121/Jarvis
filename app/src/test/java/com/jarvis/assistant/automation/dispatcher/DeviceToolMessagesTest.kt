package com.jarvis.assistant.automation.dispatcher

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying [DeviceToolMessages] so device tools report failures honestly.
 */
class DeviceToolMessagesTest {

    @Test
    fun testFlashlightSuccessOnlyForRealToggle() {
        assertTrue(DeviceToolMessages.isFlashlightSuccess("Flashlight turned on."))
        assertTrue(DeviceToolMessages.isFlashlightSuccess("Flashlight turned off."))
        assertFalse(DeviceToolMessages.isFlashlightSuccess("No flashlight hardware detected on this device."))
        assertFalse(DeviceToolMessages.isFlashlightSuccess("Unable to access the flashlight."))
        assertFalse(DeviceToolMessages.isFlashlightSuccess(null))
    }

    @Test
    fun testWifiStatusHidesUnknownSsid() {
        val msg = DeviceToolMessages.wifiStatusMessage(wifiEnabled = true, ssid = "<unknown ssid>", connected = true)
        assertFalse(msg.contains("<unknown ssid>"))
        assertTrue(msg.contains("network name hidden"))
        assertTrue(msg.contains("location permission"))
    }

    @Test
    fun testWifiStatusConnectedNamedNetwork() {
        assertEquals(
            "Wi-Fi is ON, connected to HomeNet.",
            DeviceToolMessages.wifiStatusMessage(wifiEnabled = true, ssid = "\"HomeNet\"", connected = true)
        )
    }

    @Test
    fun testWifiStatusNotConnectedAndOff() {
        assertEquals(
            "Wi-Fi is ON but not connected to any network.",
            DeviceToolMessages.wifiStatusMessage(wifiEnabled = true, ssid = "<unknown ssid>", connected = false)
        )
        assertEquals(
            "Wi-Fi is currently OFF.",
            DeviceToolMessages.wifiStatusMessage(wifiEnabled = false, ssid = "\"HomeNet\"", connected = false)
        )
    }

    @Test
    fun testCleanSsid() {
        assertEquals("Office 5G", DeviceToolMessages.cleanSsid("\"Office 5G\""))
        assertNull(DeviceToolMessages.cleanSsid("<unknown ssid>"))
        assertNull(DeviceToolMessages.cleanSsid(""))
        assertNull(DeviceToolMessages.cleanSsid(null))
    }

    @Test
    fun testWeatherTextValidation() {
        assertTrue(DeviceToolMessages.isUsableWeatherText("Delhi: ⛅️  +31°C"))
        assertFalse(DeviceToolMessages.isUsableWeatherText(""))
        assertFalse(DeviceToolMessages.isUsableWeatherText(null))
        assertFalse(DeviceToolMessages.isUsableWeatherText("Unknown location; please try ~28.6,77.2"))
        assertFalse(DeviceToolMessages.isUsableWeatherText("<html><body>502 Bad Gateway</body></html>"))
    }
}
