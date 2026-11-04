package com.jarvis.assistant.automation.dispatcher

/**
 * Pure (Android-free) helpers used by JarvisToolDispatcher so device tools report what really
 * happened instead of a canned "done". Kept separate so they can be unit-tested on the JVM.
 */
object DeviceToolMessages {

    /** Placeholder Android returns for the SSID when the app lacks location permission. */
    const val UNKNOWN_SSID = "<unknown ssid>"

    const val WIFI_PANEL_MESSAGE =
        "Android doesn't let apps switch Wi-Fi directly. I opened the Wi-Fi panel - please tap it on/off."

    /** PhoneControlManager.setFlashlight only returns "Flashlight turned on/off." on success. */
    fun isFlashlightSuccess(message: String?): Boolean =
        message != null && message.trim().startsWith("Flashlight turned", ignoreCase = true)

    /** Strips the quotes Android puts around SSIDs; null when the name is hidden or empty. */
    fun cleanSsid(ssid: String?): String? {
        val trimmed = ssid?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.equals(UNKNOWN_SSID, ignoreCase = true) || trimmed == "0x") return null
        return trimmed
    }

    fun wifiStatusMessage(wifiEnabled: Boolean, ssid: String?, connected: Boolean): String {
        if (!wifiEnabled) return "Wi-Fi is currently OFF."
        if (!connected) return "Wi-Fi is ON but not connected to any network."
        val name = cleanSsid(ssid)
        return if (name != null) {
            "Wi-Fi is ON, connected to $name."
        } else {
            "Wi-Fi is ON and connected to Wi-Fi (network name hidden — location permission needed)."
        }
    }

    /** True when a wttr.in body is a real weather line rather than an error / HTML page. */
    fun isUsableWeatherText(body: String?): Boolean {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return !lower.contains("<html") &&
            !lower.startsWith("unknown location") &&
            !lower.startsWith("sorry") &&
            !lower.contains("unable to reach")
    }
}
