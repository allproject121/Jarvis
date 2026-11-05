package com.jarvis.assistant.maya.guardian

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ML Kit face *detection* (bounding box + eye landmarks + tracking id) glued to the
 * hand-made [FaceMatcher] embedding. ML Kit does not do recognition; the recognition quality
 * here is that of a basic LBP/block-mean matcher, see [FaceMatcher].
 *
 * Pipeline per face: eye-landmark alignment → 96x96 crop → grayscale → [FaceMatcher.extract].
 */
class FaceEngine(context: Context) {

    companion object {
        private const val TAG = "FaceEngine"
        private const val MAX_INPUT_SIDE = 1024
        /** Where the eyes land in the aligned 96x96 crop. */
        private const val EYE_Y = 38f
        private const val EYE_DISTANCE = 40f

        @Volatile private var shared: FaceEngine? = null
        fun get(context: Context): FaceEngine =
            shared ?: synchronized(this) { shared ?: FaceEngine(context.applicationContext).also { shared = it } }
    }

    data class DetectedFace(
        val box: Rect,
        val leftEye: PointF?,
        val rightEye: PointF?,
        val trackingId: Int?,
        /** Aligned 96x96 crop (kept for sample images / intruder thumbnails). */
        val crop: Bitmap,
        val vector: FloatArray
    )

    data class Identified(val face: DetectedFace, val match: FaceMatcher.Match?, val person: FacePerson?)

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
    )

    private suspend fun runDetector(bitmap: Bitmap, rotation: Int): List<Face> =
        suspendCancellableCoroutine { cont ->
            try {
                detector.process(InputImage.fromBitmap(bitmap, rotation))
                    .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "ML Kit face detection failed: ${e.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit face detection threw: ${e.message}")
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    /** Downscale very large inputs so detection stays fast; returns the input when small enough. */
    private fun limitSize(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_INPUT_SIDE) return src
        val scale = MAX_INPUT_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Detects every face in [bitmap]. The camera path already rotates frames upright, but phones
     * disagree about sensor orientation, so when nothing is found the other three rotations are
     * tried as well (the crop is taken from the rotated copy, so alignment stays consistent).
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace> = withContext(Dispatchers.Default) {
        val input = limitSize(bitmap)
        for (deg in intArrayOf(0, 90, 270, 180)) {
            val upright = rotate(input, deg)
            val faces = runDetector(upright, 0)
            if (faces.isNotEmpty()) {
                return@withContext faces.mapNotNull { f -> toDetected(upright, f) }
            }
        }
        emptyList()
    }

    /** Largest detected face or null. */
    suspend fun detectLargest(bitmap: Bitmap): DetectedFace? =
        detect(bitmap).maxByOrNull { it.box.width().toLong() * it.box.height().toLong() }

    private fun toDetected(src: Bitmap, face: Face): DetectedFace? {
        val box = Rect(face.boundingBox)
        if (box.width() < 24 || box.height() < 24) return null
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val crop = align(src, box, left, right) ?: return null
        val vector = try { FaceMatcher.extract(toGray(crop)) } catch (e: Exception) {
            Log.w(TAG, "feature extraction failed: ${e.message}")
            return null
        }
        return DetectedFace(box, left, right, face.trackingId, crop, vector)
    }

    /**
     * Similarity transform so the eyes land at fixed positions in a 96x96 crop. Without eye
     * landmarks the bounding box is squared, padded by 10% and scaled instead.
     */
    fun align(src: Bitmap, box: Rect, leftEye: PointF?, rightEye: PointF?): Bitmap? {
        val size = FaceMatcher.SIZE
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = Matrix()
        if (leftEye != null && rightEye != null && hypot((rightEye.x - leftEye.x).toDouble(), (rightEye.y - leftEye.y).toDouble()) >= 8.0) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val cx = (leftEye.x + rightEye.x) / 2f
            val cy = (leftEye.y + rightEye.y) / 2f
            val scale = EYE_DISTANCE / dist
            m.postTranslate(-cx, -cy)
            m.postRotate(-angle)
            m.postScale(scale, scale)
            m.postTranslate(size / 2f, EYE_Y)
        } else {
            val side = maxOf(box.width(), box.height()) * 1.1f
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()
            val scale = size / side
            m.postTranslate(-cx, -cy)
            m.postScale(scale, scale)
            m.postTranslate(size / 2f, size / 2f)
        }
        return try {
            canvas.drawBitmap(src, m, paint)
            out
        } catch (e: Exception) {
            Log.w(TAG, "align failed: ${e.message}")
            null
        }
    }

    /** Row-major 0..255 luminance of a 96x96 bitmap. */
    fun toGray(bitmap96: Bitmap): IntArray {
        val size = FaceMatcher.SIZE
        val b = if (bitmap96.width == size && bitmap96.height == size) bitmap96
        else Bitmap.createScaledBitmap(bitmap96, size, size, true)
        val argb = IntArray(size * size)
        b.getPixels(argb, 0, size, 0, 0, size, size)
        return IntArray(size * size) { i ->
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF
            ((299 * r + 587 * g + 114 * bl) / 1000).coerceIn(0, 255)
        }
    }

    /** Best enrolled person for [vector] or null. */
    fun match(vector: FloatArray, store: FaceStore, threshold: Float): Pair<FaceMatcher.Match, FacePerson>? {
        val m = FaceMatcher.bestMatch(vector, store.vectorsByName(), threshold) ?: return null
        val p = store.findByName(m.name) ?: return null
        return m to p
    }

    /** Detects all faces and labels each one with the best enrolled person (or null = unknown). */
    suspend fun identify(bitmap: Bitmap, store: FaceStore, threshold: Float): List<Identified> {
        val people = store.vectorsByName()
        return detect(bitmap).map { f ->
            val m = if (people.isEmpty()) null else FaceMatcher.bestMatch(f.vector, people, threshold)
            Identified(f, m, m?.let { store.findByName(it.name) })
        }
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
    }
}
