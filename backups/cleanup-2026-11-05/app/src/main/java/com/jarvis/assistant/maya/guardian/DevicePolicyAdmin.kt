package com.jarvis.assistant.maya.guardian

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/**
 * Device-admin receiver (policies in res/xml/device_admin_policies.xml: force-lock + wipe-data).
 * The user enables it from Faces → Device admin (ACTION_ADD_DEVICE_ADMIN).
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Jarvis device admin enabled (lock + erase)", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Jarvis will no longer be able to lock the phone instantly or erase it if it is stolen."

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Jarvis device admin disabled", Toast.LENGTH_SHORT).show()
    }
}

/** Lock / wipe helpers around [JarvisDeviceAdminReceiver]. */
object DevicePolicyAdmin {

    private const val TAG = "DevicePolicyAdmin"

    fun component(context: Context): ComponentName =
        ComponentName(context.applicationContext, JarvisDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun isActive(context: Context): Boolean = try {
        dpm(context)?.isAdminActive(component(context)) == true
    } catch (_: Exception) { false }

    /** Intent for the system "activate device admin" screen. */
    fun enableIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Lets Jarvis lock the phone instantly when a stranger tries something sensitive, and erase it only when you say the wipe phrase in your own voice."
        )
    }

    data class LockResult(val ok: Boolean, val method: String, val message: String)

    /** DevicePolicyManager.lockNow when admin is active, else accessibility GLOBAL_ACTION_LOCK_SCREEN. */
    fun lockNow(context: Context): LockResult {
        if (isActive(context)) {
            try {
                dpm(context)?.lockNow()
                return LockResult(true, "device_admin", "Phone locked.")
            } catch (e: Exception) {
                Log.w(TAG, "lockNow failed: ${e.message}")
            }
        }
        val svc = JarvisAccessibilityService.instance
        if (svc != null) {
            val ok = try { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Exception) { false }
            if (ok) return LockResult(true, "accessibility", "Phone locked (via accessibility).")
        }
        return LockResult(false, "none",
            "Couldn't lock the phone: enable Jarvis as device admin (Faces → Device admin) or turn on the Jarvis accessibility service.")
    }

    fun lockState(context: Context): Map<String, Any> {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return linkedMapOf(
            "keyguard_locked" to (km?.isKeyguardLocked == true),
            "device_locked" to (km?.isDeviceLocked == true),
            "device_secure" to (km?.isDeviceSecure == true),
            "screen_on" to (pm?.isInteractive == true),
            "device_admin_active" to isActive(context),
            "accessibility_running" to (JarvisAccessibilityService.instance != null)
        )
    }

    /** Factory reset. Caller MUST have passed [WipeGuard.evaluate] first. */
    fun wipe(context: Context): String {
        val manager = dpm(context) ?: return "Device policy service unavailable."
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    manager.wipeDevice(0)
                } catch (e: SecurityException) {
                    // Plain device admins (not device owner) still go through wipeData on many builds.
                    @Suppress("DEPRECATION")
                    manager.wipeData(0)
                }
            } else {
                @Suppress("DEPRECATION")
                manager.wipeData(0)
            }
            "Erasing the phone now."
        } catch (e: SecurityException) {
            "Android refused the wipe: ${e.message}. On this Android version only a device-owner app may be allowed to erase the phone."
        } catch (e: Exception) {
            "Wipe failed: ${e.message}"
        }
    }
}
