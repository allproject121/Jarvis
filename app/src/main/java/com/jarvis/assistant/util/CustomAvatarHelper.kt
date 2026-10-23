package com.jarvis.assistant.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.JarvisApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages custom 3D avatar images (JPG, PNG, WEBP, etc.):
 * - Ingestion & cropping into a high-res square 512x512 texture.
 * - Anti-aliased circular / rounded vignette composition with alpha channel.
 * - Base64 export for seamless WebGL Three.js ingestion in overlay toys and 3D orb.
 * - Safe deletion and cleanup.
 */
object CustomAvatarHelper {

    private const val FILE_NAME = "custom_3d_avatar.png"
    private const val MODEL_FILE_NAME = "custom_3d_model"
    private const val TARGET_SIZE = 512

    fun getAvatarFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun hasCustomAvatar(context: Context): Boolean {
        val file = getAvatarFile(context)
        return (file.exists() && file.length() > 0) || hasCustom3dModel(context)
    }

    fun getAvatarBitmap(context: Context): Bitmap? {
        val file = getAvatarFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Throwable) {
            null
        }
    }

    fun getBase64(context: Context): String? {
        val file = getAvatarFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bytes = file.readBytes()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /* =========================================================================
     * REAL 3D MODEL SUPPORT (.GLB, .GLTF, .OBJ, .STL, .FBX) & MULTI-MODEL LIBRARY
     * ========================================================================= */

    data class Saved3DModel(
        val id: String,
        val name: String,
        val format: String,
        val sizeBytes: Long,
        val formattedSize: String,
        val addedTime: Long,
        var isActive: Boolean
    )

    private const val MODELS_DIR_NAME = "custom_3d_models"
    private const val LIBRARY_FILE_NAME = "saved_3d_models.json"

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getLibraryFile(context: Context): File {
        return File(context.filesDir, LIBRARY_FILE_NAME)
    }

    @Synchronized
    fun getSaved3DModels(context: Context): List<Saved3DModel> {
        val list = mutableListOf<Saved3DModel>()
        val libFile = getLibraryFile(context)
        val dir = getModelsDirectory(context)

        if (libFile.exists()) {
            try {
                val jsonStr = libFile.readText()
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id")
                    val file = File(dir, id)
                    if (file.exists() && file.length() > 0) {
                        list.add(
                            Saved3DModel(
                                id = id,
                                name = obj.optString("name", file.name),
                                format = obj.optString("format", file.extension.lowercase()),
                                sizeBytes = file.length(),
                                formattedSize = String.format("%.2f MB", file.length() / (1024f * 1024f)),
                                addedTime = obj.optLong("addedTime", System.currentTimeMillis()),
                                isActive = obj.optBoolean("isActive", false)
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w("CustomAvatarHelper", "Error parsing 3D model library JSON", e)
            }
        }

        // Migrate legacy single model if present and not in list
        val legacyFormats = listOf("glb", "gltf", "obj", "stl", "fbx")
        for (ext in legacyFormats) {
            val legacyFile = File(context.filesDir, "$MODEL_FILE_NAME.$ext")
            if (legacyFile.exists() && legacyFile.length() > 0) {
                val newId = "model_legacy_${System.currentTimeMillis()}.$ext"
                val destFile = File(dir, newId)
                legacyFile.copyTo(destFile, overwrite = true)
                legacyFile.delete()
                val isAct = list.isEmpty()
                val model = Saved3DModel(
                    id = newId,
                    name = "Imported Model ($ext)",
                    format = ext,
                    sizeBytes = destFile.length(),
                    formattedSize = String.format("%.2f MB", destFile.length() / (1024f * 1024f)),
                    addedTime = System.currentTimeMillis(),
                    isActive = isAct
                )
                list.add(model)
                saveLibrary(context, list)
            }
        }

        return list
    }

    @Synchronized
    fun saveLibrary(context: Context, list: List<Saved3DModel>) {
        try {
            val array = org.json.JSONArray()
            for (m in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("format", m.format)
                    put("sizeBytes", m.sizeBytes)
                    put("formattedSize", m.formattedSize)
                    put("addedTime", m.addedTime)
                    put("isActive", m.isActive)
                }
                array.put(obj)
            }
            getLibraryFile(context).writeText(array.toString())
        } catch (e: Throwable) {
            Log.e("CustomAvatarHelper", "Failed to write 3D model library", e)
        }
    }

    fun getActiveModel(context: Context): Saved3DModel? {
        val models = getSaved3DModels(context)
        return models.firstOrNull { it.isActive } ?: models.firstOrNull()
    }

    fun getActiveModelFile(context: Context): File? {
        val active = getActiveModel(context)
        if (active != null) {
            val f = File(getModelsDirectory(context), active.id)
            if (f.exists() && f.length() > 0) return f
        }
        val legacy = get3DModelFile(context)
        if (legacy != null && legacy.exists() && legacy.length() > 0) return legacy
        return null
    }

    fun get3DModelFile(context: Context): File? {
        val formats = listOf("glb", "gltf", "obj", "stl", "fbx")
        for (ext in formats) {
            val f = File(context.filesDir, "$MODEL_FILE_NAME.$ext")
            if (f.exists() && f.length() > 0) return f
        }
        val vaultFile = try { ModelVaultManager.getActiveModelFile(context) } catch (_: Throwable) { null }
        if (vaultFile != null && vaultFile.exists() && vaultFile.length() > 0) return vaultFile
        return null
    }

    fun hasCustom3dModel(context: Context): Boolean {
        return getActiveModelFile(context) != null
    }

    fun hasActiveCustom3dModel(context: Context): Boolean {
        return getActiveModelFile(context) != null
    }

    fun getCustomModelFormat(context: Context): String {
        val active = getActiveModel(context)
        if (active != null) return active.format.lowercase()
        val f = getActiveModelFile(context) ?: return ""
        return f.extension.lowercase()
    }

    @Synchronized
    fun setActiveModel(context: Context, modelId: String): Boolean {
        val models = getSaved3DModels(context).toMutableList()
        var found = false
        for (m in models) {
            m.isActive = (m.id == modelId)
            if (m.isActive) found = true
        }
        if (found) {
            saveLibrary(context, models)
            JarvisApp.instance.preferences.orbEntityType = "custom_3d"
        }
        return found
    }

    @Synchronized
    fun switchToDefaultOrb(context: Context) {
        val models = getSaved3DModels(context).toMutableList()
        for (m in models) {
            m.isActive = false
        }
        saveLibrary(context, models)
        JarvisApp.instance.preferences.orbEntityType = "orb"
    }

    @Synchronized
    fun deleteModel(context: Context, modelId: String): Boolean {
        val models = getSaved3DModels(context).toMutableList()
        val toRemove = models.find { it.id == modelId } ?: return false
        val file = File(getModelsDirectory(context), toRemove.id)
        if (file.exists()) file.delete()
        models.remove(toRemove)

        if (toRemove.isActive) {
            if (models.isNotEmpty()) {
                models[0].isActive = true
                JarvisApp.instance.preferences.orbEntityType = "custom_3d"
            } else {
                JarvisApp.instance.preferences.orbEntityType = "orb"
            }
        }
        saveLibrary(context, models)
        return true
    }

    fun deleteCustom3dModel(context: Context): Boolean {
        val models = getSaved3DModels(context)
        for (m in models) {
            File(getModelsDirectory(context), m.id).delete()
        }
        getLibraryFile(context).delete()
        val formats = listOf("glb", "gltf", "obj", "stl", "fbx")
        for (ext in formats) {
            val f = File(context.filesDir, "$MODEL_FILE_NAME.$ext")
            if (f.exists()) f.delete()
        }
        JarvisApp.instance.preferences.orbEntityType = "orb"
        return true
    }

    fun getCustom3dModelBytes(context: Context): ByteArray? {
        val file = getActiveModelFile(context) ?: return null
        return try {
            file.readBytes()
        } catch (_: Throwable) {
            null
        }
    }

    fun getCustom3dModelBase64(context: Context): String? {
        val bytes = getCustom3dModelBytes(context) ?: return null
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: ""
                }
            }
        } catch (_: Throwable) {}
        if (name.isBlank()) {
            name = uri.lastPathSegment ?: ""
        }
        return name
    }

    fun is3DModelFile(context: Context, uri: Uri): Pair<Boolean, String> {
        val name = getFileNameFromUri(context, uri).lowercase()
        if (name.endsWith(".glb")) return Pair(true, "glb")
        if (name.endsWith(".gltf")) return Pair(true, "gltf")
        if (name.endsWith(".obj")) return Pair(true, "obj")
        if (name.endsWith(".stl")) return Pair(true, "stl")
        if (name.endsWith(".fbx")) return Pair(true, "fbx")

        val mime = (context.contentResolver.getType(uri) ?: "").lowercase()
        if (mime.contains("gltf-binary") || mime.contains("glb")) return Pair(true, "glb")
        if (mime.contains("gltf")) return Pair(true, "gltf")
        if (mime.contains("wavefront") || mime.contains("obj")) return Pair(true, "obj")
        if (mime.contains("stl") || mime.contains("sla")) return Pair(true, "stl")
        if (mime.contains("fbx")) return Pair(true, "fbx")

        // Inspect header bytes for "glTF" or FBX magic numbers
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(18)
                val read = stream.read(header)
                if (read >= 4) {
                    val magic4 = String(header.copyOfRange(0, 4), Charsets.US_ASCII)
                    if (magic4 == "glTF") return Pair(true, "glb")
                    if (magic4.startsWith(";")) return Pair(true, "fbx")
                }
                if (read >= 18) {
                    val magic18 = String(header, Charsets.US_ASCII)
                    if (magic18.startsWith("Kaydara FBX Binary")) return Pair(true, "fbx")
                }
            }
        } catch (_: Throwable) {}

        return Pair(false, "")
    }

    fun processAndSave3DModelUri(context: Context, uri: Uri, customName: String? = null): Pair<Boolean, String> {
        try {
            val (is3D, detectedExt) = is3DModelFile(context, uri)
            val ext = if (is3D && detectedExt.isNotBlank()) detectedExt else "glb"
            val dir = getModelsDirectory(context)

            val rawFileName = getFileNameFromUri(context, uri)
            val cleanDisplayName = (customName ?: rawFileName).ifBlank { "Model_${System.currentTimeMillis()}.$ext" }
            val safeFileName = "model_${System.currentTimeMillis()}_" + cleanDisplayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(dir, if (safeFileName.endsWith(".$ext", ignoreCase = true)) safeFileName else "$safeFileName.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                val models = getSaved3DModels(context).toMutableList()
                for (m in models) m.isActive = false

                val newModel = Saved3DModel(
                    id = targetFile.name,
                    name = cleanDisplayName,
                    format = ext.lowercase(),
                    sizeBytes = targetFile.length(),
                    formattedSize = String.format("%.2f MB", targetFile.length() / (1024f * 1024f)),
                    addedTime = System.currentTimeMillis(),
                    isActive = true
                )
                models.add(0, newModel)
                saveLibrary(context, models)

                val prefs = JarvisApp.instance.preferences
                prefs.orbEntityType = "custom_3d"
                return Pair(true, "${newModel.name} (${ext.uppercase()}, ${newModel.formattedSize})")
            }
            return Pair(false, "Failed to save 3D model file.")
        } catch (e: Throwable) {
            Log.e("CustomAvatarHelper", "Error saving 3D model", e)
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Reads ANY media format:
     * - 3D Models: .glb, .gltf, .obj, .stl
     * - All image formats: JPG, PNG, WEBP, GIF, BMP, HEIC/HEIF, etc.
     * - All video formats: MP4, WEBM, MKV, 3GP, AVI, etc. (extracts keyframe)
     */
    fun processAndSaveMediaUri(context: Context, uri: Uri): Boolean {
        // If it's a 3D model file, process as 3D model
        val (is3D, _) = is3DModelFile(context, uri)
        if (is3D) {
            val (ok, _) = processAndSave3DModelUri(context, uri)
            return ok
        }

        var input: InputStream? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            val contentResolver = context.contentResolver
            val mimeType = (contentResolver.getType(uri) ?: "").lowercase()
            val uriString = uri.toString().lowercase()
            val isVideo = mimeType.startsWith("video/") ||
                    listOf(".mp4", ".mkv", ".webm", ".3gp", ".avi", ".mov").any { uriString.contains(it) }

            var originalBitmap: Bitmap? = null

            if (isVideo) {
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 1000L
                    val targetUs = (durationMs * 500L).coerceIn(100_000L, 5_000_000L)
                    originalBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (e: Throwable) {
                    Log.e("CustomAvatarHelper", "Failed to extract video frame", e)
                } finally {
                    try { retriever?.release() } catch (_: Throwable) {}
                }
            }

            if (originalBitmap == null) {
                // Decode bounds first to prevent OOM on high-res camera shots
                input = contentResolver.openInputStream(uri) ?: return false
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                input.close()

                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                if (srcWidth <= 0 || srcHeight <= 0) return false

                var sampleSize = 1
                var maxDim = maxOf(srcWidth, srcHeight)
                while (maxDim / 2 >= TARGET_SIZE) {
                    sampleSize *= 2
                    maxDim /= 2
                }

                // Decode actual downscaled bitmap
                input = contentResolver.openInputStream(uri) ?: return false
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                originalBitmap = BitmapFactory.decodeStream(input, null, decodeOptions) ?: return false
                input.close()
            }

            // Prepare square 512x512 canvas with anti-aliased rounded/circular mask
            val finalBitmap = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Center-crop source into square
            val origW = originalBitmap.width
            val origH = originalBitmap.height
            val minSide = minOf(origW, origH)
            val srcRect = Rect(
                (origW - minSide) / 2,
                (origH - minSide) / 2,
                (origW + minSide) / 2,
                (origH + minSide) / 2
            )
            val dstRect = Rect(0, 0, TARGET_SIZE, TARGET_SIZE)

            // Circular mask
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1 }
            val radius = TARGET_SIZE / 2f
            canvas.drawCircle(radius, radius, radius - 4f, maskPaint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(originalBitmap, srcRect, dstRect, paint)
            originalBitmap.recycle()

            // Save to filesDir as PNG
            val targetFile = getAvatarFile(context)
            if (targetFile.exists()) targetFile.delete()
            val fos = FileOutputStream(targetFile)
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            finalBitmap.recycle()

            val prefs = JarvisApp.instance.preferences
            prefs.hasCustomOverlayAvatar = true
            prefs.customOverlayAvatarPath = targetFile.absolutePath
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
        } finally {
            try { input?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Backward-compatible alias for processAndSaveMediaUri
     */
    fun processAndSaveImageUri(context: Context, uri: Uri): Boolean {
        return processAndSaveMediaUri(context, uri)
    }

    /**
     * Removes the custom 3D avatar file and 3D model, resetting preferences to default.
     */
    fun deleteCustomAvatar(context: Context): Boolean {
        try {
            val file = getAvatarFile(context)
            if (file.exists()) {
                file.delete()
            }
            deleteCustom3dModel(context)

            val prefs = JarvisApp.instance.preferences
            prefs.hasCustomOverlayAvatar = false
            prefs.customOverlayAvatarPath = null
            if (prefs.taskOverlayAvatar == "custom_3d") {
                prefs.taskOverlayAvatar = "ghost"
            }
            if (prefs.orbEntityType == "custom_3d") {
                prefs.orbEntityType = "orb"
            }
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            return false
        }
    }
}
