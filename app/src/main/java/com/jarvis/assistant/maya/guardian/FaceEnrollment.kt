package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Shared enrolment step for the Faces screen and the enroll_face tool. */
object FaceEnrollment {

    private const val TAG = "FaceEnrollment"
    const val SAMPLES = 5

    sealed class Step {
        data class Added(val person: FacePerson, val sampleCount: Int) : Step()
        data class Rejected(val reason: String) : Step()
    }

    /** Detects the largest face in [frame] and stores its print + 96x96 crop for [name]. */
    suspend fun addFrame(context: Context, name: String, role: String?, frame: Bitmap): Step {
        val clean = name.trim()
        if (clean.isEmpty()) return Step.Rejected("Name is empty")
        val engine = FaceEngine.get(context)
        val faces = engine.detect(frame)
        if (faces.isEmpty()) return Step.Rejected("No face found — look at the camera in good light")
        if (faces.size > 1) return Step.Rejected("More than one face in view — only the person being saved, please")
        val face = faces.first()
        val store = FaceStore(context.filesDir)
        val person = store.upsert(clean, role)
        val dir = store.personDir(person)
        val file = File(dir, "sample_${System.currentTimeMillis()}.jpg")
        val path = try {
            FileOutputStream(file).use { face.crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "sample save failed: ${e.message}")
            null
        }
        val updated = store.addSample(clean, role, face.vector, path)
        return Step.Added(updated, updated.vectors.size)
    }

    /** Camera-only enrolment used by the voice tool: up to [SAMPLES] stills from the front camera. */
    suspend fun enrollFromCamera(context: Context, name: String, role: String?): String {
        if (!FaceCamera.hasCameraPermission(context)) return "Camera permission is missing — open Faces and tap Add person."
        var added = 0
        var lastReason = ""
        repeat(SAMPLES) {
            val frame = FaceCamera.captureStillSuspend(context, front = true)
            if (frame == null) {
                lastReason = "camera unavailable (Android blocks the camera while Jarvis is in the background)"
                return@repeat
            }
            when (val r = addFrame(context, name, role, frame)) {
                is Step.Added -> added++
                is Step.Rejected -> lastReason = r.reason
            }
        }
        return if (added > 0) "Saved $added face sample(s) for ${name.trim()} as ${FaceStore.normaliseRole(role)}. " +
            "A face print is a list of numbers kept only on this phone (basic on-device matcher, not a neural face model)."
        else "Couldn't save the face: $lastReason."
    }
}
