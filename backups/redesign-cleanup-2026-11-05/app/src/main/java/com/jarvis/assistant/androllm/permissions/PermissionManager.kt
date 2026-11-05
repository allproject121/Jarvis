package com.jarvis.assistant.androllm.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.jarvis.assistant.androllm.permissions.handler.AccessibilityAccessHandler
import com.jarvis.assistant.androllm.permissions.handler.AlarmAccessHandler
import com.jarvis.assistant.androllm.permissions.handler.BluetoothPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.CalendarPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.CameraPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.ContactsPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.LocationPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.MicrophonePermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.NotificationAccessHandler
import com.jarvis.assistant.androllm.permissions.handler.NotificationPermissionHandler
import com.jarvis.assistant.androllm.permissions.handler.SmsPermissionHandler
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A lazy, per-feature permission request raised by a tool (or any caller)
 * that found a permission missing. A UI host ([com.jarvis.assistant.ui.compose.feature.settings.PermissionRequestHost])
 * observes [PermissionManager.pendingRequest] and launches the system dialog
 * (or the matching settings screen) the next time the app UI is visible.
 */
data class PermissionRequest(
    val id: Long,
    val feature: Feature,
    val handlerId: String,
    /** Runtime permissions to request; empty = special access (settings screen). */
    val permissions: List<String>,
    /** Why it is being asked right now (shown next to the request). */
    val reason: String
)

/**
 * Central permission/access manager — the single source of truth for every
 * runtime permission and special access the AndroLLM layer can ask for
 * (ported from AndroLLM core/permissions; Hilt removed).
 *
 * Responsibilities:
 *  - [status] — live state of a gate, including permanently-denied detection
 *  - [runtimePermissions] — what to feed the system permission dialog
 *  - [onPermissionRequested] — request history so permanent denial is detected
 *  - [openSettings] — special access that lives in system settings
 *  - [permissionsNeeded] / [permissionsNeededForEnabledFeatures] — derive the
 *    concrete permission list from enabled features (declarative feature map)
 *  - Jarvis addition: [isGranted] / [requestFeature] / [pendingRequest] — lazy
 *    per-feature requests raised at the moment a tool needs a permission.
 *
 * This complements (never replaces) Jarvis' own util.PermissionHelper,
 * ui.permission.PermissionActivity and the first-run setup screen; handlers
 * read the same Android state, so every surface agrees.
 */
class PermissionManager(
    context: Context,
    handlers: Set<PermissionHandler> = defaultHandlers()
) {

    private val context: Context = context.applicationContext

    /** All registered handlers, in the recommended request order. */
    val handlers: List<PermissionHandler> =
        handlers.sortedBy { handler ->
            REQUEST_ORDER.indexOf(handler.id).let { if (it < 0) Int.MAX_VALUE else it }
        }

    /** Permissions the user has already been asked about (persisted across restarts). */
    private val requestedPermissions = Collections.synchronizedSet(
        this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_REQUESTED, emptySet())!!.toMutableSet()
    )

    private val requestIds = AtomicLong(0)
    private val _pendingRequest = MutableStateFlow<PermissionRequest?>(null)

    /** The newest lazy permission request waiting for a UI host, or null. */
    val pendingRequest: StateFlow<PermissionRequest?> = _pendingRequest.asStateFlow()

    /** Lookup by [PermissionHandler.id]. */
    fun handler(id: String): PermissionHandler? = handlers.firstOrNull { it.id == id }

    /**
     * Live state of [handler]. Passing the hosting [Activity] enables
     * permanent-denial detection: once a runtime permission was requested and
     * Android reports the rationale can no longer be shown
     * (shouldShowRequestPermissionRationale == false), the state becomes
     * [PermissionState.PERMANENTLY_DENIED] and the UI offers "Open Settings"
     * instead of "Try Again".
     */
    fun status(
        handler: PermissionHandler,
        activity: Activity? = null
    ): PermissionState {
        if (!handler.isFeatureEnabled(context)) return PermissionState.NOT_REQUIRED
        val raw = handler.rawState(context)
        if (raw != PermissionState.DENIED) return raw

        // Runtime-permission denial — distinguish "try again" from "blocked".
        val denied = handler.runtimePermissions(context)
            .firstOrNull { !isGranted(it) }
            ?: return PermissionState.GRANTED
        val requestedBefore = denied in requestedPermissions
        val canShowRationale = activity?.let { a ->
            runCatching { ActivityCompat.shouldShowRequestPermissionRationale(a, denied) }
                .getOrDefault(true)
        } ?: true
        return if (requestedBefore && !canShowRationale) {
            PermissionState.PERMANENTLY_DENIED
        } else {
            PermissionState.DENIED
        }
    }

    /** The dialog permissions to request for [handler] (empty = none needed). */
    fun runtimePermissions(handler: PermissionHandler): List<String> =
        handler.runtimePermissions(context)

    /**
     * Records a dialog request so permanent-denial detection knows the
     * history. Call immediately before launching the system dialog.
     */
    fun onPermissionRequested(handler: PermissionHandler) {
        requestedPermissions.addAll(handler.runtimePermissions(context))
        persistRequested()
    }

    /** Opens the system screen where [handler] can be granted. */
    fun openSettings(handler: PermissionHandler): Boolean = handler.openSettings(context)

    /** Permissions the app would need if [feature] were used on this device. */
    fun permissionsNeeded(feature: Feature): List<String> =
        feature.handlerIds.mapNotNull { handler(it) }
            .flatMap { it.runtimePermissions(context) }
            .distinct()

    /**
     * Permissions for every feature that is actually enabled on this device —
     * the "which permissions are needed from enabled features" answer.
     */
    fun permissionsNeededForEnabledFeatures(): List<String> =
        Feature.entries
            .filter { feature ->
                feature.handlerIds.any { id -> handler(id)?.isFeatureEnabled(context) == true }
            }
            .flatMap { permissionsNeeded(it) }
            .distinct()

    // ── Jarvis: lazy per-feature requests ─────────────────────────────────

    /** True when every gate behind [feature] is granted (or not required here). */
    fun isGranted(feature: Feature): Boolean =
        feature.handlerIds.mapNotNull { handler(it) }.all { h ->
            when (status(h)) {
                PermissionState.GRANTED, PermissionState.NOT_REQUIRED, PermissionState.UNAVAILABLE -> true
                else -> false
            }
        }

    /**
     * Returns true when [feature] is already usable. Otherwise raises a lazy
     * [PermissionRequest] for the UI host (system dialog for runtime
     * permissions, settings screen for special access) and returns false —
     * the caller should tell the user what was asked and why.
     */
    fun requestFeature(feature: Feature, reason: String): Boolean {
        if (isGranted(feature)) return true
        val handler = feature.handlerIds.mapNotNull { handler(it) }
            .firstOrNull { status(it) != PermissionState.GRANTED }
            ?: return true
        _pendingRequest.value = PermissionRequest(
            id = requestIds.incrementAndGet(),
            feature = feature,
            handlerId = handler.id,
            permissions = handler.runtimePermissions(context).filterNot { isGranted(it) },
            reason = reason
        )
        return false
    }

    /** Called by the UI host once it has acted on (or dismissed) [request]. */
    fun consumeRequest(request: PermissionRequest) {
        _pendingRequest.compareAndSet(request, null)
    }

    /**
     * Standard user-facing message for a tool that needs [feature]: raises
     * the lazy request and explains what happens next.
     */
    fun missingMessage(feature: Feature, reason: String): String {
        requestFeature(feature, reason)
        val handler = feature.handlerIds.firstNotNullOfOrNull { handler(it) }
        val how = if (handler?.needsSettingsScreen == true) {
            "enable it in the Android settings screen Jarvis opens (or Settings → Permissions & Access)"
        } else {
            "grant it when Jarvis asks (or in Settings → Permissions & Access)"
        }
        return "${feature.displayName} permission is not granted — $how, then try again."
    }

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun persistRequested() {
        val snapshot = synchronized(requestedPermissions) { requestedPermissions.toSet() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_REQUESTED, snapshot).apply()
    }

    companion object {
        private const val PREFS = "androllm_permissions"
        private const val KEY_REQUESTED = "requested_permissions"

        /**
         * Recommended request order (the product spec's smart sequence):
         * voice → notifications → accessibility → the rest.
         */
        private val REQUEST_ORDER = listOf(
            "voice_assistant",
            "notifications",
            "accessibility",
            "notification_access",
            "contacts",
            "sms",
            "calendar",
            "camera",
            "location",
            "bluetooth",
            "alarms"
        )

        /** Every built-in gate (the former Hilt PermissionModule multibinding). */
        fun defaultHandlers(): Set<PermissionHandler> = setOf(
            MicrophonePermissionHandler(),
            NotificationPermissionHandler(),
            AccessibilityAccessHandler(),
            NotificationAccessHandler(),
            ContactsPermissionHandler(),
            SmsPermissionHandler(),
            CalendarPermissionHandler(),
            CameraPermissionHandler(),
            LocationPermissionHandler(),
            BluetoothPermissionHandler(),
            AlarmAccessHandler()
        )

        @Volatile
        private var instance: PermissionManager? = null

        fun getInstance(context: Context): PermissionManager =
            instance ?: synchronized(this) {
                instance ?: PermissionManager(context.applicationContext).also { instance = it }
            }
    }
}
