package com.jarvis.assistant.ui.compose.feature.setup

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.local.LocalAiConfig
import com.jarvis.assistant.ai.local.LocalModelManager
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.compose.feature.splash.ComposeStartDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Live state of one permission/access gate. */
internal enum class SetupPermissionState {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    NEEDS_SETTINGS,
    NOT_REQUIRED
}

/** How a gate is granted. */
internal enum class SetupGateKind {
    RUNTIME,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    OVERLAY
}

/** One capability Jarvis can use, explained before its permission is requested. */
internal data class SetupGate(
    val id: String,
    val title: String,
    val description: String,
    val explanation: String,
    val kind: SetupGateKind,
    /** Runtime permissions for this SDK level; empty for RUNTIME means "not required here". */
    val permissions: List<String> = emptyList()
) {
    val needsSettingsScreen: Boolean get() = kind != SetupGateKind.RUNTIME
}

/** Process-lifetime scope so a LocalModelManager created here never dies with the screen. */
private val setupBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val ACCESSIBILITY_SERVICE_CLASS =
    "com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService"
private const val TAG = "ComposeSetup"

/**
 * First-run setup: explains and requests the permissions Jarvis' features use,
 * and reports whether an on-device model / cloud key is configured.
 * Plain [AndroidViewModel] — no Hilt.
 */
internal class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    private val prefs: AppPreferences =
        (application as? JarvisApp)?.let { runCatching { it.preferences }.getOrNull() }
            ?: AppPreferences(application)

    private val modelManager: LocalModelManager? =
        runCatching { LocalModelManager.getInstance(application, setupBackgroundScope) }.getOrNull()

    private val uiPrefs = application.getSharedPreferences(
        ComposeStartDestination.PREFS_NAME, Context.MODE_PRIVATE
    )

    val gates: List<SetupGate> = buildGates()

    private fun buildGates(): List<SetupGate> {
        val sdk = Build.VERSION.SDK_INT
        return listOf(
            SetupGate(
                id = "voice_assistant",
                title = "Voice Assistant",
                description = "Talk to Jarvis hands-free",
                explanation = "Microphone access powers wake-word detection, Gemini Live voice and offline speech recognition. Audio is only captured while Jarvis is listening.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(Manifest.permission.RECORD_AUDIO)
            ),
            SetupGate(
                id = "notifications",
                title = "Notifications",
                description = "Status, reminders and downloads",
                explanation = "Lets Jarvis show its foreground listening status, model download progress and reminders.",
                kind = SetupGateKind.RUNTIME,
                permissions = if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            ),
            SetupGate(
                id = "accessibility",
                title = "Phone Control",
                description = "Tap, scroll and type for you",
                explanation = "The Jarvis accessibility service lets the assistant operate apps on your behalf. Enable it in Android Settings → Accessibility; you can turn it off any time.",
                kind = SetupGateKind.ACCESSIBILITY
            ),
            SetupGate(
                id = "notification_access",
                title = "Notification Access",
                description = "Read and reply to messages",
                explanation = "Allows Jarvis to read incoming WhatsApp/Telegram messages aloud and draft replies when you ask.",
                kind = SetupGateKind.NOTIFICATION_LISTENER
            ),
            SetupGate(
                id = "overlay",
                title = "Floating Overlay",
                description = "Task overlay over other apps",
                explanation = "Shows the floating Jarvis orb and task progress on top of other apps.",
                kind = SetupGateKind.OVERLAY
            ),
            SetupGate(
                id = "contacts",
                title = "Contacts",
                description = "Call or text people by name",
                explanation = "Used to resolve \"call Mom\" or \"text Alex\" to the right contact. Contacts never leave your device.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(Manifest.permission.READ_CONTACTS)
            ),
            SetupGate(
                id = "phone",
                title = "Phone Calls",
                description = "Place and answer calls",
                explanation = "Lets Jarvis place calls, announce callers and answer when you say so.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE
                )
            ),
            SetupGate(
                id = "sms",
                title = "SMS",
                description = "Send text messages",
                explanation = "Required to send SMS on your behalf, including emergency SOS messages.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(Manifest.permission.SEND_SMS)
            ),
            SetupGate(
                id = "camera",
                title = "Camera",
                description = "Vision and flashlight",
                explanation = "Lets Jarvis see what you point the camera at during Live sessions and toggle the flashlight.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(Manifest.permission.CAMERA)
            ),
            SetupGate(
                id = "location",
                title = "Location",
                description = "Weather and local answers",
                explanation = "Used for weather, directions and nearby results. Only read when a request needs it.",
                kind = SetupGateKind.RUNTIME,
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ),
            SetupGate(
                id = "bluetooth",
                title = "Bluetooth",
                description = "Headsets and car mode",
                explanation = "Detects Bluetooth headsets and car connections for driving mode and audio routing.",
                kind = SetupGateKind.RUNTIME,
                permissions = if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
            )
        )
    }

    fun status(gate: SetupGate, activity: Activity?): SetupPermissionState = when (gate.kind) {
        SetupGateKind.RUNTIME -> runtimeStatus(gate, activity)
        SetupGateKind.ACCESSIBILITY ->
            if (isAccessibilityEnabled()) SetupPermissionState.GRANTED else SetupPermissionState.NEEDS_SETTINGS
        SetupGateKind.NOTIFICATION_LISTENER ->
            if (isNotificationListenerEnabled()) SetupPermissionState.GRANTED else SetupPermissionState.NEEDS_SETTINGS
        SetupGateKind.OVERLAY ->
            if (Settings.canDrawOverlays(app)) SetupPermissionState.GRANTED else SetupPermissionState.NEEDS_SETTINGS
    }

    private fun runtimeStatus(gate: SetupGate, activity: Activity?): SetupPermissionState {
        if (gate.permissions.isEmpty()) return SetupPermissionState.NOT_REQUIRED
        val granted = gate.permissions.all {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) return SetupPermissionState.GRANTED
        val requested = uiPrefs.getBoolean(requestedKey(gate), false)
        if (requested && activity != null) {
            val missing = gate.permissions.filter {
                ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
            }
            val canAskAgain = missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            if (!canAskAgain) return SetupPermissionState.PERMANENTLY_DENIED
        }
        return SetupPermissionState.DENIED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(app.packageName, ACCESSIBILITY_SERVICE_CLASS)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    fun runtimePermissions(gate: SetupGate): List<String> =
        if (gate.kind == SetupGateKind.RUNTIME) gate.permissions else emptyList()

    fun onRequested(gate: SetupGate) {
        uiPrefs.edit().putBoolean(requestedKey(gate), true).apply()
    }

    /** Opens the matching system screen. Returns false when none could be launched. */
    fun openSettings(gate: SetupGate): Boolean {
        val pkgUri = Uri.parse("package:${app.packageName}")
        val intent = when (gate.kind) {
            SetupGateKind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            SetupGateKind.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            SetupGateKind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            SetupGateKind.RUNTIME -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No settings screen for ${gate.id}", e)
            false
        }
    }

    /** Number of catalog models downloaded and verified on this device. */
    fun readyLocalModelCount(): Int {
        val manager = modelManager ?: return 0
        return LocalAiConfig.AVAILABLE_MODELS.count { runCatching { manager.isModelReady(it.id) }.getOrDefault(false) }
    }

    val localModelCatalogSize: Int get() = LocalAiConfig.AVAILABLE_MODELS.size

    fun hasGeminiKey(): Boolean = runCatching { prefs.apiKey.isNotBlank() }.getOrDefault(false)

    fun hasOpenAiKey(): Boolean = runCatching { prefs.openAiApiKey.isNotBlank() }.getOrDefault(false)

    fun markSetupDone() {
        ComposeStartDestination.setSetupDone(app, true)
    }

    private fun requestedKey(gate: SetupGate) = "setup_requested_${gate.id}"
}
