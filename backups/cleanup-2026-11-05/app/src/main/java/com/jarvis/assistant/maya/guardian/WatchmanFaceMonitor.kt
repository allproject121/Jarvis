package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.security.guardian.GuardianForegroundService
import com.jarvis.assistant.security.guardian.TouchGuardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Watchman: runs when Touch Guard (armed through the existing TouchGuardManager) fires.
 * TouchGuardManager already takes the front-camera photo into filesDir/watchman (the intruder
 * gallery); this class logs the incident, runs face detection + the basic matcher on it and:
 *  - boss / family recognised → stand down (siren off, Touch Guard disarmed) unless God Mode;
 *  - unknown face → keep the photo, notify, inject "[SYSTEM EVENT] Watchman: anjaan chehra dekha".
 */
object WatchmanFaceMonitor {

    private const val TAG = "WatchmanFace"

    enum class Verdict { PENDING, NO_PHOTO, NO_FACE, OWNER_STOOD_DOWN, KNOWN, UNKNOWN }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called by TouchGuardManager after each trigger (photo null = camera failed / timed out). */
    fun onTouchGuardTriggered(context: Context, photo: File?, motionDelta: Float) {
        val app = context.applicationContext
        val incidents = TouchGuardIncidents.of(app)
        val incident = incidents.record(
            TouchGuardIncident(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                source = "touch_guard",
                motionDelta = motionDelta,
                photoPath = photo?.absolutePath,
                verdict = if (photo == null) Verdict.NO_PHOTO.name else Verdict.PENDING.name,
                names = emptyList(),
                note = if (photo == null) "phone moved, no photo" else "phone moved"
            )
        )
        if (photo == null) {
            ListenModeController.injectEvent("Touch Guard: kisi ne phone hilaya (photo nahi le paayi).")
            return
        }
        scope.launch { analyse(app, incident.id, photo) }
    }

    private suspend fun analyse(context: Context, incidentId: String, photo: File) {
        val incidents = TouchGuardIncidents.of(context)
        val bmp: Bitmap? = try { BitmapFactory.decodeFile(photo.absolutePath) } catch (_: Exception) { null }
        if (bmp == null) {
            incidents.update(incidentId) { it.copy(verdict = Verdict.NO_FACE.name, note = "photo unreadable") }
            return
        }
        val store = FaceStore(context.filesDir)
        val prefs = GuardianPrefs(context)
        val found = try { FaceEngine.get(context).identify(bmp, store, prefs.faceThreshold) } catch (e: Exception) {
            Log.w(TAG, "identify failed: ${e.message}")
            emptyList()
        }
        if (found.isEmpty()) {
            incidents.update(incidentId) { it.copy(verdict = Verdict.NO_FACE.name, note = "no face in photo") }
            ListenModeController.injectEvent("Touch Guard: kisi ne phone hilaya. Photo li, par chehra saaf nahi dikha.")
            return
        }
        val names = found.mapNotNull { it.person?.name }.distinct()
        val trusted = found.mapNotNull { it.person }.filter { it.isTrusted }
        val unknownCount = found.count { it.person == null }

        if (trusted.isNotEmpty() && unknownCount == 0 && !prefs.godMode) {
            incidents.update(incidentId) { it.copy(verdict = Verdict.OWNER_STOOD_DOWN.name, names = names, note = "trusted face — stood down") }
            Handler(Looper.getMainLooper()).post {
                try {
                    TouchGuardManager.stopAlarm()
                    if (TouchGuardManager.isArmed()) TouchGuardManager.disarm(context)
                } catch (e: Exception) { Log.w(TAG, "stand down failed: ${e.message}") }
            }
            ListenModeController.injectEvent("Watchman: ${trusted.first().name} ko pehchan liya — touch guard band kar diya.")
            return
        }

        if (unknownCount > 0) {
            incidents.update(incidentId) { it.copy(verdict = Verdict.UNKNOWN.name, names = names, note = "$unknownCount unknown face(s)") }
            GuardianForegroundService.postMovedAlert(
                context,
                "Watchman: unknown face seen at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}. Photo saved in Faces → Watchman."
            )
            ListenModeController.injectEvent("Watchman: anjaan chehra dekha. Photo le li gayi hai (Faces → Watchman). Boss ko batao.")
        } else {
            incidents.update(incidentId) { it.copy(verdict = Verdict.KNOWN.name, names = names, note = "known face(s)") }
            ListenModeController.injectEvent("Watchman: phone ${names.joinToString()} ne uthaya.")
        }
    }

    /** Intruder gallery directory (shared with WatchmanSecurityManager). */
    fun watchmanDir(context: Context): File = File(context.filesDir, "watchman").apply { if (!exists()) mkdirs() }

    /** Saves [bitmap] into the intruder gallery (used for failed Face Lock checks). */
    fun saveIntruderBitmap(context: Context, bitmap: Bitmap, prefix: String = "intruder"): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(watchmanDir(context), "${prefix}_$ts.jpg")
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f
    } catch (e: Exception) {
        Log.w(TAG, "save failed: ${e.message}")
        null
    }

    fun clearIntruderGallery(context: Context): Int {
        val files = watchmanDir(context).listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
        return files.count { it.delete() }
    }
}
