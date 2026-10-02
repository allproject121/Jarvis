package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class JarvisContextEngine {

    private var currentApp: String = "Unknown"
    private var currentActivity: String = ""
    private var lastScreenContent: String = ""
    private var screenElements: List<String> = emptyList()
    private var detectedButtons: List<String> = emptyList()
    private var detectedInputs: List<String> = emptyList()
    private var navigationStack: MutableList<String> = mutableListOf()

    companion object {
        const val APP_UNKNOWN = "Unknown"
        const val APP_WHATSAPP = "com.whatsapp"
        const val APP_YOUTUBE = "com.google.android.youtube"
        const val APP_CHROME = "com.android.chrome"
        const val APP_INSTAGRAM = "com.instagram.android"
        const val APP_PHONE = "com.android.dialer"
        const val APP_CAMERA = "com.android.camera"
        const val APP_MAPS = "com.google.android.apps.maps"
        const val APP_GMAIL = "com.google.android.gm"
        const val APP_SETTINGS = "com.android.settings"
        const val APP_GALLERY = "com.android.gallery"
        const val APP_PLAYSTORE = "com.android.vending"
        const val APP_MESSAGES = "com.android.mms"
        const val APP_FILES = "com.android.documentsui"
        const val APP_CLOCK = "com.android.deskclock"
        const val APP_CALENDAR = "com.android.calendar"
    }

    fun updateContext(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != currentApp) {
                    navigationStack.add(currentApp)
                    if (navigationStack.size > 10) navigationStack.removeAt(0)
                    currentApp = pkg
                }
                currentActivity = event.className?.toString() ?: ""
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastScreenContent = event.text?.joinToString(" ") ?: ""
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank()) {
                    // Track what buttons are being clicked
                }
            }
        }
    }

    fun updateScreenElements(service: JarvisAccessibilityService) {
        val elements = service.mapScreenInteractions()
        screenElements = elements.map { it.text }.filter { it.isNotBlank() }
        detectedButtons = elements.filter { it.isClickable }.map { it.text }.filter { it.isNotBlank() }
        detectedInputs = elements.filter { it.isEditable }.map { it.text }.filter { it.isNotBlank() }
    }

    fun getCurrentApp(): String = currentApp

    fun getCurrentAppName(): String {
        return when (currentApp) {
            APP_WHATSAPP -> "WhatsApp"
            APP_YOUTUBE -> "YouTube"
            APP_CHROME -> "Chrome"
            APP_INSTAGRAM -> "Instagram"
            APP_PHONE -> "Phone"
            APP_CAMERA -> "Camera"
            APP_MAPS -> "Google Maps"
            APP_GMAIL -> "Gmail"
            APP_SETTINGS -> "Settings"
            APP_GALLERY -> "Gallery"
            APP_PLAYSTORE -> "Play Store"
            APP_MESSAGES -> "Messages"
            APP_FILES -> "Files"
            APP_CLOCK -> "Clock"
            APP_CALENDAR -> "Calendar"
            else -> "Unknown App"
        }
    }

    fun isBackground(): Boolean {
        return currentApp.contains("launcher") || currentApp.contains("home")
    }

    fun getDetectedButtons(): List<String> = detectedButtons
    fun getDetectedInputs(): List<String> = detectedInputs
    fun getScreenText(): String = lastScreenContent
    fun getAllElements(): List<String> = screenElements
    fun getButtonCount(): Int = detectedButtons.size

    fun findElement(text: String): String? {
        return screenElements.find { it.lowercase().contains(text.lowercase()) }
    }

    fun getPreviousApp(): String = navigationStack.lastOrNull() ?: APP_UNKNOWN

    fun getContextSummary(): String {
        return "Current App: ${getCurrentAppName()} ($currentApp)\n" +
               "Activity: $currentActivity\n" +
               "Buttons detected: ${detectedButtons.size}\n" +
               "Input fields: ${detectedInputs.size}\n" +
               "Screen elements: ${screenElements.size}"
    }

    fun isInWhatsApp(): Boolean = currentApp == APP_WHATSAPP
    fun isInYouTube(): Boolean = currentApp == APP_YOUTUBE
    fun isInChrome(): Boolean = currentApp == APP_CHROME
    fun isInInstagram(): Boolean = currentApp == APP_INSTAGRAM
    fun isInMaps(): Boolean = currentApp == APP_MAPS
    fun isInSettings(): Boolean = currentApp == APP_SETTINGS
    fun isOnHomeScreen(): Boolean = isBackground()

    fun getActionableContext(command: String): String {
        return when {
            command.contains("scroll") || command.contains("upar") || command.contains("neeche") -> {
                when {
                    isInWhatsApp() -> "WHATSAPP_SCROLL"
                    isInYouTube() -> "YOUTUBE_SCROLL"
                    isInInstagram() -> "INSTAGRAM_SCROLL"
                    isInChrome() -> "CHROME_SCROLL"
                    else -> "GENERIC_SCROLL"
                }
            }
            command.contains("click") || command.contains("dabao") || command.contains("press") -> {
                "CLICK_IN_CURRENT_APP"
            }
            command.contains("type") || command.contains("likho") || command.contains("write") -> {
                "TYPE_IN_CURRENT_APP"
            }
            else -> "UNKNOWN"
        }
    }

    fun getSuggestedButtons(): String {
        if (detectedButtons.isEmpty()) return "Screen par koi button nahi mila."
        return "Screen par ye buttons hain: ${detectedButtons.joinToString(", ")}"
    }

    fun getSuggestedInputs(): String {
        if (detectedInputs.isEmpty()) return "Screen par koi input field nahi mila."
        return "Input fields: ${detectedInputs.joinToString(", ")}"
    }
}
