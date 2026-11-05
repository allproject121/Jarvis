package com.jarvis.assistant.maya.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.jarvis.assistant.util.ActivityLauncherHelper
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * MediaStore / file helpers shared by the media pack: saving generated pictures to
 * Pictures/Jarvis, resolving "last photo" / "screenshot" / absolute paths to content URIs,
 * simple gallery search, and opening a viewer.
 */
object MediaFiles {

    private const val TAG = "MayaMediaFiles"
    const val ALBUM = "Jarvis"
    const val FILE_PROVIDER_SUFFIX = ".maya.media.fileprovider"

    data class SavedMedia(val uri: Uri, val path: String?, val displayName: String) {
        /** Absolute path when known, otherwise the content URI string. */
        val ref: String get() = path ?: uri.toString()
    }

    fun authority(context: Context): String = context.packageName + FILE_PROVIDER_SUFFIX

    // ---------------- saving ----------------

    fun saveImage(context: Context, bitmap: Bitmap, baseName: String = "JARVIS_AI", quality: Int = 95): SavedMedia? {
        val name = "${baseName}_${System.currentTimeMillis()}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                SavedMedia(uri, pathForUri(context, uri), name)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }
                val uri = scanFile(context, file) ?: Uri.fromFile(file)
                SavedMedia(uri, file.absolutePath, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveImage failed: ${e.message}", e)
            null
        }
    }

    fun saveImageBytes(context: Context, bytes: ByteArray, baseName: String = "JARVIS_AI"): SavedMedia? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return saveImage(context, bmp, baseName)
    }

    /** Registers a finished video file (already written by MediaRecorder) in MediaStore. */
    fun registerVideo(context: Context, file: File): SavedMedia {
        val uri = scanFile(context, file) ?: Uri.fromFile(file)
        return SavedMedia(uri, file.absolutePath, file.name)
    }

    /** Directory for app-written videos (Movies/Jarvis via app-specific external storage). */
    fun videoDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, ALBUM)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun scanFile(context: Context, file: File): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
        val collection = if (mimeFor(file.name).startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.insert(collection, values)
    } catch (e: Exception) {
        null
    }

    // ---------------- resolving ----------------

    fun pathForUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Resolves a user/model supplied reference to a shareable content URI.
     * Accepts: content:// or file:// URIs, absolute paths, "last"/"latest"/"recent" (newest gallery
     * image), "screenshot" (newest screenshot), "video"/"last video" (newest video), "generated"
     * (newest generated image), or a file name to search for.
     */
    fun resolveToUri(context: Context, ref: String?): Pair<Uri, String>? {
        val r = ref?.trim().orEmpty()
        if (r.isBlank()) return newest(context, image = true)
        if (r.startsWith("content://")) return Uri.parse(r) to mimeForUri(context, Uri.parse(r))
        if (r.startsWith("file://")) return fileToUri(context, File(Uri.parse(r).path ?: return null))
        if (r.startsWith("/")) return fileToUri(context, File(r))
        val low = r.lowercase(Locale.ROOT)
        when {
            low.contains("screenshot") -> newest(context, image = true, nameLike = "%screenshot%")?.let { return it }
            low == "generated" || low.contains("ai image") || low.contains("generated image") -> {
                MediaPrefs.get(context).recentImages().firstOrNull()?.let { return resolveToUri(context, it) }
            }
            low.contains("video") || low.contains("clip") || low.contains("reel") -> newest(context, image = false)?.let { return it }
            low == "last" || low == "latest" || low == "recent" || low.contains("last photo") || low.contains("latest photo") ||
                low.contains("newest") || low == "photo" || low == "image" -> newest(context, image = true)?.let { return it }
        }
        // search by name in images, videos, then downloads/files
        findFiles(context, r, limit = 1).firstOrNull()?.let { f -> return fileToUri(context, File(f.path)) }
        return null
    }

    private fun fileToUri(context: Context, file: File): Pair<Uri, String>? {
        if (!file.exists()) return null
        val mime = mimeFor(file.name)
        // Prefer the MediaStore URI (works for gallery files without FileProvider path rules)
        mediaStoreUriForPath(context, file.absolutePath)?.let { return it to mime }
        return try {
            FileProvider.getUriForFile(context, authority(context), file) to mime
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider could not wrap ${file.absolutePath}: ${e.message}")
            Uri.fromFile(file) to mime
        }
    }

    private fun mediaStoreUriForPath(context: Context, path: String): Uri? {
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        for (col in collections) {
            try {
                context.contentResolver.query(
                    col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null
                )?.use { c ->
                    if (c.moveToFirst()) return ContentUris.withAppendedId(col, c.getLong(0))
                }
            } catch (e: Exception) {
                // ignore and try the next collection
            }
        }
        return null
    }

    fun mimeForUri(context: Context, uri: Uri): String =
        try { context.contentResolver.getType(uri) } catch (e: Exception) { null } ?: "*/*"

    fun newest(context: Context, image: Boolean, nameLike: String? = null): Pair<Uri, String>? {
        val col = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val selection = if (nameLike != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?" else null
        val args = if (nameLike != null) arrayOf(nameLike, nameLike) else null
        return try {
            context.contentResolver.query(
                col,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val mime = c.getString(1) ?: if (image) "image/jpeg" else "video/mp4"
                    ContentUris.withAppendedId(col, id) to mime
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "newest() query failed: ${e.message}")
            null
        }
    }

    data class FoundFile(val name: String, val path: String, val sizeBytes: Long, val modifiedMs: Long, val mime: String)

    /** Searches MediaStore (all files) by name fragment; falls back to app dirs and Downloads. */
    fun findFiles(context: Context, query: String, limit: Int = 15): List<FoundFile> {
        val out = linkedMapOf<String, FoundFile>()
        val q = query.trim()
        val like = "%${q.replace(' ', '%')}%"
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE
                ),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf(like, like),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val path = c.getString(1) ?: continue
                    val name = c.getString(0) ?: File(path).name
                    out[path] = FoundFile(name, path, c.getLong(2), c.getLong(3) * 1000L, c.getString(4) ?: mimeFor(name))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findFiles MediaStore query failed: ${e.message}")
        }
        if (out.size < limit) {
            val dirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                context.getExternalFilesDir(null),
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                videoDir(context),
                context.filesDir
            )
            for (dir in dirs) {
                try {
                    dir.walkTopDown().maxDepth(3).filter { it.isFile && it.name.contains(q, ignoreCase = true) }
                        .sortedByDescending { it.lastModified() }
                        .forEach { f ->
                            if (out.size < limit && !out.containsKey(f.absolutePath)) {
                                out[f.absolutePath] = FoundFile(f.name, f.absolutePath, f.length(), f.lastModified(), mimeFor(f.name))
                            }
                        }
                } catch (e: Exception) {
                    // directory may be inaccessible; keep going
                }
            }
        }
        return out.values.toList()
    }

    // ---------------- opening ----------------

    fun openViewer(context: Context, uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            ActivityLauncherHelper.startActivitySafely(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "openViewer failed: ${e.message}")
            false
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
