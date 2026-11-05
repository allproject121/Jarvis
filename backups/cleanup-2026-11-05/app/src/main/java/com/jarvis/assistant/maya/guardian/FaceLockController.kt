package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Face Lock: when the Jarvis app lock (Protocol 7) or a protected setting needs unlocking, try
 * the owner's face for up to [FACE_WINDOW_MS] and fall back to the phone's BiometricPrompt.
 *
 * Honest caveat (also shown in the Faces screen): the face check is the basic on-device matcher
 * in [FaceMatcher], not a neural/3D face model — a good photo of the owner may fool it. It is a
 * convenience layer; BiometricPrompt (fingerprint / face / PIN) stays the real fallback.
 */
object FaceLockController {

    private const val TAG = "FaceLock"
    const val FACE_WINDOW_MS = 5_000L

    enum class Method { FACE, BIOMETRIC, NONE }

    data class UnlockResult(val success: Boolean, val method: Method, val message: String)

    /** Face lock on, an owner (boss) face enrolled and camera permission granted. */
    fun isFaceUsable(context: Context): Boolean {
        val prefs = GuardianPrefs(context)
        if (!prefs.faceLockEnabled) return false
        if (!FaceCamera.hasCameraPermission(context)) return false
        return FaceStore(context.filesDir).owners().any { it.vectors.isNotEmpty() }
    }

    /**
     * Tries the face for ≤ 5 s and returns the outcome only (no biometric fallback).
     * [onResult] runs on the main thread.
     */
    fun tryFace(activity: FragmentActivity, onResult: (UnlockResult) -> Unit): Job? {
        val ctx = activity.applicationContext
        if (!isFaceUsable(ctx)) {
            onResult(UnlockResult(false, Method.NONE, "Face lock not set up"))
            return null
        }
        return activity.lifecycleScope.launch {
            val prefs = GuardianPrefs(ctx)
            val store = FaceStore(ctx.filesDir)
            val engine = FaceEngine.get(ctx)
            val deadline = SystemClock.elapsedRealtime() + FACE_WINDOW_MS
            var lastUnknown: android.graphics.Bitmap? = null
            var sawFace = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining < 600) break
                val frame = FaceCamera.captureStillSuspend(ctx, front = true, timeoutMs = remaining) ?: break
                val faces = try { engine.identify(frame, store, prefs.faceThreshold) } catch (e: Exception) {
                    Log.w(TAG, "identify failed: ${e.message}")
                    emptyList()
                }
                if (faces.isNotEmpty()) sawFace = true
                val owner = faces.firstOrNull { it.person?.isOwner == true }
                if (owner != null) {
                    prefs.faceLockFailedAttempts = 0
                    prefs.faceLockLastAttemptAt = System.currentTimeMillis()
                    // A face match is NOT treated as owner-voice verification (photos can fool it).
                    onResult(UnlockResult(true, Method.FACE, "Recognised ${owner.person?.name} (score ${"%.2f".format(owner.match?.score ?: 0f)})"))
                    return@launch
                }
                if (faces.any { it.person == null }) lastUnknown = frame
            }
            prefs.faceLockFailedAttempts = prefs.faceLockFailedAttempts + 1
            prefs.faceLockLastAttemptAt = System.currentTimeMillis()
            // Maya: failed face checks show up in "Who tried to get in", with a photo when one was taken.
            val saved = lastUnknown?.let { WatchmanFaceMonitor.saveIntruderBitmap(ctx, it, "facelock") }
            if (saved != null || sawFace) {
                TouchGuardIncidents.of(ctx).record(
                    TouchGuardIncident(
                        id = UUID.randomUUID().toString(),
                        at = System.currentTimeMillis(),
                        source = "face_lock",
                        motionDelta = 0f,
                        photoPath = saved?.absolutePath,
                        verdict = WatchmanFaceMonitor.Verdict.UNKNOWN.name,
                        names = emptyList(),
                        note = "failed face check"
                    )
                )
            }
            onResult(UnlockResult(false, Method.FACE, if (sawFace) "Face not recognised" else "No face seen"))
        }
    }

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    /** The phone's own BiometricPrompt (fingerprint / face / PIN). */
    fun biometric(activity: FragmentActivity, title: String, onResult: (UnlockResult) -> Unit) {
        val can = BiometricManager.from(activity).canAuthenticate(authenticators())
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(UnlockResult(false, Method.NONE, "No screen lock / biometric set up on this phone"))
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(UnlockResult(true, Method.BIOMETRIC, "Verified"))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(UnlockResult(false, Method.BIOMETRIC, errString.toString()))
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Verify it's you")
            .setAllowedAuthenticators(authenticators())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) info.setNegativeButtonText("Cancel")
        try {
            prompt.authenticate(info.build())
        } catch (e: Exception) {
            onResult(UnlockResult(false, Method.BIOMETRIC, "Could not start verification: ${e.message}"))
        }
    }

    /**
     * Face first (≤ 5 s, only when face lock is set up), then BiometricPrompt.
     * [onResult] runs on the main thread exactly once.
     */
    fun requestUnlock(activity: FragmentActivity, title: String = "Jarvis Face Lock", onResult: (UnlockResult) -> Unit) {
        if (!isFaceUsable(activity)) {
            biometric(activity, title, onResult)
            return
        }
        tryFace(activity) { r ->
            if (r.success) onResult(r)
            else if (!activity.isFinishing) biometric(activity, title, onResult)
            else onResult(r)
        }
    }
}
