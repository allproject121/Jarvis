package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * "She looks at you": a tiny front-camera face tracker for the Home 3D character.
 *
 * Opens the front camera at 320x240, runs ML Kit face detection about 5 times a second and
 * reports where the nearest face is, as x/y in [-1, 1] (x > 0 = the person is on the right side
 * of the screen as they look at it, y > 0 = lower), or `present = false` when nobody is there.
 * Frames are never stored or sent anywhere; only the position leaves this class.
 * Opt-in (Settings → Media & Extras → "Character looks at you") and needs CAMERA permission.
 */
class ViewerFaceTracker(
    context: Context,
    private val onViewer: (x: Float, y: Float, present: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 270
    @Volatile private var busy = false
    @Volatile private var running = false
    private var lastProcessAt = 0L
    private var missCount = 0

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.12f)
                .build()
        )
    }

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val cameraId = try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) { null } ?: return false
        sensorOrientation = try {
            manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        } catch (_: Exception) { 270 }

        running = true
        val t = HandlerThread("ViewerFaceTracker").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val r = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val image = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            val now = SystemClock.uptimeMillis()
            if (busy || now - lastProcessAt < 200 || !running) { image.close(); return@setOnImageAvailableListener }
            busy = true
            lastProcessAt = now
            val input = try { InputImage.fromMediaImage(image, sensorOrientation) } catch (e: Exception) { null }
            if (input == null) { image.close(); busy = false; return@setOnImageAvailableListener }
            // After rotation the upright frame is 240x320 (portrait) for 90/270 sensors
            val upW = if (sensorOrientation % 180 == 0) 320f else 240f
            val upH = if (sensorOrientation % 180 == 0) 240f else 320f
            detector.process(input)
                .addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (face != null) {
                        missCount = 0
                        val cx = face.boundingBox.exactCenterX() / upW * 2f - 1f
                        val cy = face.boundingBox.exactCenterY() / upH * 2f - 1f
                        // Front camera frames are not mirrored: someone on the screen's right
                        // shows up on the image's left, so flip x.
                        onViewer((-cx).coerceIn(-1f, 1f), cy.coerceIn(-1f, 1f), true)
                    } else if (++missCount >= 3) {
                        onViewer(0f, 0f, false)
                    }
                }
                .addOnCompleteListener {
                    try { image.close() } catch (_: Exception) {}
                    busy = false
                }
        }, h)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running) { camera.close(); return }
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!running) { s.close(); return }
                                session = s
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Exception) { Log.w(TAG, "repeating request failed: ${e.message}"); stop() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "session configure failed"); stop() }
                        }, h)
                    } catch (e: Exception) { Log.w(TAG, "session failed: ${e.message}"); stop() }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); stop() }
                override fun onError(camera: CameraDevice, error: Int) {
                    // Usually the camera is busy (video call, Gemini camera look, another app) — just stop quietly.
                    Log.w(TAG, "camera error $error"); camera.close(); stop()
                }
            }, h)
        } catch (e: Exception) {
            Log.w(TAG, "openCamera failed: ${e.message}")
            stop()
            return false
        }
        return true
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        thread?.quitSafely()
        thread = null; handler = null
        busy = false
        try { onViewer(0f, 0f, false) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ViewerFaceTracker"
        private const val PREFS = "jarvis_avatar_prefs"
        private const val KEY_FOLLOW = "follow_viewer_face"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FOLLOW, false)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FOLLOW, on).apply()
    }
}
