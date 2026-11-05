package com.jarvis.assistant.maya.guardian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Camera2 front-camera helpers (CameraX is not a dependency of Jarvis). Same open → session →
 * still-capture pattern as [com.jarvis.assistant.security.guardian.WatchmanSecurityManager],
 * but returning an upright [Bitmap] instead of writing a file, plus a preview session for the
 * enrolment screen (TextureView surface + JPEG snapshots).
 *
 * Background camera access is restricted on Android 11+; callers get null when the camera is
 * unavailable rather than an exception.
 */
object FaceCamera {

    private const val TAG = "FaceCamera"
    private const val STILL_W = 640
    private const val STILL_H = 480
    private const val OPEN_TIMEOUT_MS = 6_000L

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun cameraManager(context: Context): CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** Front camera id, falling back to any camera. */
    fun cameraId(context: Context, front: Boolean = true): String? {
        val cm = cameraManager(context) ?: return null
        return try {
            val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList failed: ${e.message}")
            null
        }
    }

    private fun sensorOrientation(context: Context, id: String): Int = try {
        cameraManager(context)?.getCameraCharacteristics(id)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    } catch (_: Exception) { 0 }

    /** JPEG bytes → upright bitmap (rotated by the sensor orientation, portrait phone assumed). */
    fun decodeUpright(jpeg: ByteArray, sensorOrientation: Int, mirror: Boolean): Bitmap? {
        val raw = try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) } catch (e: Exception) { null } ?: return null
        if (sensorOrientation % 360 == 0 && !mirror) return raw
        val m = Matrix()
        if (sensorOrientation % 360 != 0) m.postRotate(sensorOrientation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return try {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        } catch (e: Exception) { raw }
    }

    /**
     * Opens the front camera, grabs one JPEG still and closes it. [callback] runs once on the
     * main thread with null on any failure (no permission, no camera, background restriction).
     */
    fun captureStill(context: Context, front: Boolean = true, callback: (Bitmap?) -> Unit) {
        val reported = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun done(b: Bitmap?) {
            if (reported.compareAndSet(false, true)) main.post { callback(b) }
        }
        if (!hasCameraPermission(context)) { done(null); return }
        val cm = cameraManager(context) ?: run { done(null); return }
        val id = cameraId(context, front) ?: run { done(null); return }
        val orientation = sensorOrientation(context, id)
        val reader = ImageReader.newInstance(STILL_W, STILL_H, ImageFormat.JPEG, 2)
        val thread = HandlerThread("FaceCamera").apply { start() }
        val handler = Handler(thread.looper)
        fun cleanup(camera: CameraDevice?) {
            try { camera?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { thread.quitSafely() } catch (_: Exception) {}
        }
        main.postDelayed({ if (!reported.get()) { Log.w(TAG, "captureStill timed out"); done(null) } }, OPEN_TIMEOUT_MS)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        reader.setOnImageAvailableListener({ r ->
                            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            image.close()
                            val bmp = decodeUpright(bytes, orientation, mirror = false)
                            cleanup(camera)
                            done(bmp)
                        }, handler)
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                // Short delay so auto-exposure has a few frames before the still.
                                handler.postDelayed({
                                    try {
                                        session.capture(request.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "still capture failed: ${e.message}")
                                        cleanup(camera); done(null)
                                    }
                                }, 300)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                cleanup(camera); done(null)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        Log.w(TAG, "capture setup failed: ${e.message}")
                        cleanup(camera); done(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) { cleanup(camera); done(null) }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "camera error $error")
                    cleanup(camera); done(null)
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "camera permission missing: ${e.message}")
            cleanup(null); done(null)
        } catch (e: Exception) {
            Log.w(TAG, "openCamera failed: ${e.message}")
            cleanup(null); done(null)
        }
    }

    /** Coroutine form of [captureStill]; null on failure or after [timeoutMs]. */
    suspend fun captureStillSuspend(context: Context, front: Boolean = true, timeoutMs: Long = OPEN_TIMEOUT_MS + 1_000L): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                captureStill(context, front) { bmp -> if (cont.isActive) cont.resume(bmp) }
            }
        }

    /**
     * Live preview on a TextureView's [SurfaceTexture] plus on-demand JPEG snapshots, used by
     * the enrolment flow in FacesActivity. Call [open] once the SurfaceTexture is available and
     * [close] from onPause / when done.
     */
    class PreviewSession(private val context: Context, private val texture: SurfaceTexture, private val front: Boolean = true) {
        private var camera: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private val thread = HandlerThread("FacePreview").apply { start() }
        private val handler = Handler(thread.looper)
        private val main = Handler(Looper.getMainLooper())
        private var orientation = 0
        @Volatile private var pendingSnap: ((Bitmap?) -> Unit)? = null
        @Volatile var isOpen = false
            private set

        fun open(onReady: () -> Unit, onFail: (String) -> Unit) {
            if (!hasCameraPermission(context)) { onFail("Camera permission not granted"); return }
            val cm = cameraManager(context) ?: run { onFail("No camera service"); return }
            val id = cameraId(context, front) ?: run { onFail("No camera found"); return }
            orientation = sensorOrientation(context, id)
            texture.setDefaultBufferSize(STILL_W, STILL_H)
            val previewSurface = Surface(texture)
            val r = ImageReader.newInstance(STILL_W, STILL_H, ImageFormat.JPEG, 2).also { reader = it }
            r.setOnImageAvailableListener({ rd ->
                val image = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                image.close()
                val cb = pendingSnap
                pendingSnap = null
                val bmp = decodeUpright(bytes, orientation, mirror = false)
                if (cb != null) main.post { cb(bmp) }
            }, handler)
            try {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        camera = cam
                        try {
                            @Suppress("DEPRECATION")
                            cam.createCaptureSession(listOf(previewSurface, r.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(previewSurface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                        s.setRepeatingRequest(req.build(), null, handler)
                                        isOpen = true
                                        main.post(onReady)
                                    } catch (e: Exception) {
                                        main.post { onFail("Preview failed: ${e.message}") }
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    main.post { onFail("Camera session failed") }
                                }
                            }, handler)
                        } catch (e: Exception) {
                            main.post { onFail("Camera setup failed: ${e.message}") }
                        }
                    }

                    override fun onDisconnected(cam: CameraDevice) { close(); main.post { onFail("Camera disconnected") } }
                    override fun onError(cam: CameraDevice, error: Int) { close(); main.post { onFail("Camera error $error") } }
                }, handler)
            } catch (e: SecurityException) {
                onFail("Camera permission missing")
            } catch (e: Exception) {
                onFail("Could not open camera: ${e.message}")
            }
        }

        /** Takes one JPEG still from the running preview; [callback] on the main thread. */
        fun snap(callback: (Bitmap?) -> Unit) {
            val cam = camera
            val s = session
            val r = reader
            if (cam == null || s == null || r == null || !isOpen) { callback(null); return }
            if (pendingSnap != null) { callback(null); return }
            pendingSnap = callback
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(r.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                s.capture(req.build(), null, handler)
            } catch (e: Exception) {
                pendingSnap = null
                Log.w(TAG, "snap failed: ${e.message}")
                callback(null)
            }
        }

        fun close() {
            isOpen = false
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            session = null
            camera = null
            reader = null
            try { thread.quitSafely() } catch (_: Exception) {}
        }
    }
}
