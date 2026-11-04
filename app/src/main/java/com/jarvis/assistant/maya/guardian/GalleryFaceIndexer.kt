package com.jarvis.assistant.maya.guardian

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File

/**
 * "Find people in your photos": one-shot WorkManager job that scans the newest [LIMIT] gallery
 * images, detects faces (ML Kit), matches them against enrolled people with the basic
 * [FaceMatcher] and writes filesDir/faces/gallery_index.json. Needs READ_MEDIA_IMAGES
 * (Android 13+) or READ_EXTERNAL_STORAGE — FacesActivity requests it before enqueueing.
 * Nothing leaves the phone; unknown faces are only counted, never stored.
 */
class GalleryFaceIndexer(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "GalleryFaceIndexer"
        const val UNIQUE_NAME = "maya_gallery_face_index"
        const val LIMIT = 500
        private const val DECODE_MAX_SIDE = 1024

        fun mediaPermission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<GalleryFaceIndexer>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        /** Decodes a content:// or file path with downsampling. */
        fun decodeSampled(context: Context, uriOrPath: String, maxSide: Int = DECODE_MAX_SIDE): Bitmap? {
            return try {
                val isUri = uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (isUri) {
                    context.contentResolver.openInputStream(Uri.parse(uriOrPath))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                } else {
                    BitmapFactory.decodeFile(uriOrPath, bounds)
                }
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                if (longest <= 0) return null
                while (longest / (sample * 2) >= maxSide) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                if (isUri) {
                    context.contentResolver.openInputStream(Uri.parse(uriOrPath))?.use { BitmapFactory.decodeStream(it, null, opts) }
                } else {
                    BitmapFactory.decodeFile(uriOrPath, opts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode failed for $uriOrPath: ${e.message}")
                null
            }
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!hasPermission(ctx)) {
            Log.w(TAG, "No media permission — skipping gallery scan")
            return Result.failure()
        }
        val store = FaceStore(ctx.filesDir)
        val prefs = GuardianPrefs(ctx)
        val people = store.vectorsByName()
        val engine = FaceEngine.get(ctx)
        val entries = ArrayList<GalleryEntry>()
        var scanned = 0

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        try {
            ctx.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext() && scanned < LIMIT) {
                    if (isStopped) break
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol)
                    else if (addedCol >= 0) c.getLong(addedCol) * 1000L else 0L
                    scanned++
                    val bmp = decodeSampled(ctx, uri.toString()) ?: continue
                    val faces = try { engine.detect(bmp) } catch (e: Exception) { emptyList() }
                    if (faces.isEmpty()) continue
                    val names = ArrayList<String>()
                    var unknown = 0
                    for (f in faces) {
                        val m = if (people.isEmpty()) null else FaceMatcher.bestMatch(f.vector, people, prefs.faceThreshold)
                        if (m != null) { if (m.name !in names) names.add(m.name) } else unknown++
                    }
                    entries.add(GalleryEntry(uri.toString(), null, taken, names, unknown))
                    if (scanned % 50 == 0) setProgress(androidx.work.workDataOf("scanned" to scanned))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "gallery scan failed: ${e.message}", e)
            return Result.failure()
        }
        store.writeGalleryIndex(GalleryIndex(System.currentTimeMillis(), scanned, entries))
        prefs.galleryIndexedAt = System.currentTimeMillis()
        Log.i(TAG, "Indexed $scanned images, ${entries.size} with faces")
        return Result.success()
    }
}

/** Shared "who is in this photo" helper for tools and UI. */
object PhotoFaces {
    suspend fun whoIsIn(context: Context, pathOrUri: String): String {
        val src = pathOrUri.trim()
        if (src.isEmpty()) return "Give me a photo path or content:// uri."
        if (!src.startsWith("content://") && !src.startsWith("file://") && !File(src).exists()) return "Photo not found: $src"
        val bmp = GalleryFaceIndexer.decodeSampled(context, src)
            ?: return "Couldn't open that photo (it may need the photos permission — open Faces and tap 'Find people in your photos')."
        val store = FaceStore(context.filesDir)
        val found = FaceEngine.get(context).identify(bmp, store, GuardianPrefs(context).faceThreshold)
        if (found.isEmpty()) return "No face found in that photo."
        val known = found.mapNotNull { f -> f.person?.let { "${it.name} (pakka, ${"%.2f".format(f.match?.score ?: 0f)})" } }
        val unknown = found.count { it.person == null }
        return buildString {
            append("${found.size} face(s). ")
            if (known.isNotEmpty()) append("Recognised: ${known.joinToString()}. ")
            if (unknown > 0) append("$unknown unknown — I won't guess their names. ")
            append("(basic on-device matcher)")
        }
    }
}
