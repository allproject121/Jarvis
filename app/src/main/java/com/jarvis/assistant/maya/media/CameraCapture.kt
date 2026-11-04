package com.jarvis.assistant.maya.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.jarvis.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * Headless Camera2 capture (no CameraX): single JPEG stills, camera video via MediaRecorder,
 * and screen recording on top of ScreenCaptureManager's MediaProjection when it can be reached.
 */
object CameraCapture {

    private const val TAG = "MayaCamera"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun cameraId(manager: CameraManager, front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
            ?: manager.cameraIdList.firstOrNull()
    }

    private fun startThread(name: String): Pair<HandlerThread, Handler> {
        val t = HandlerThread(name).apply { start() }
        return t to Handler(t.looper)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, id: String, handler: Handler): CameraDevice? =
        withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    manager.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
                        override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cont.isActive) cont.resume(null) }
                        override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cont.isActive) cont.resume(null) }
                    }, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(camera: CameraDevice, surfaces: List<Surface>, handler: Handler): CameraCaptureSession? =
        withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) }
                        override fun onConfigureFailed(session: CameraCaptureSession) { if (cont.isActive) cont.resume(null) }
                    }, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private fun chooseSize(manager: CameraManager, id: String, format: Int, maxW: Int): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(format)?.toList().orEmpty()
        return sizes.filter { it.width <= maxW }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: Size(1280, 720)
    }

    /** Takes one JPEG still (auto-exposure warmed up for ~0.9s). Returns an upright bitmap. */
    suspend fun takeStill(context: Context, front: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (!hasCameraPermission(context)) return@withContext null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId(manager, front) ?: return@withContext null
        val (thread, handler) = startThread("MayaStill")
        var camera: CameraDevice? = null
        var reader: ImageReader? = null
        var session: CameraCaptureSession? = null
        try {
            val size = chooseSize(manager, id, ImageFormat.JPEG, 1920)
            val rdr = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
            reader = rdr
            val cam = openCamera(manager, id, handler) ?: return@withContext null
            camera = cam
            val sess = createSession(cam, listOf(rdr.surface), handler) ?: return@withContext null
            session = sess
            val warm = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(rdr.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            rdr.setOnImageAvailableListener({ r -> try { r.acquireLatestImage()?.close() } catch (_: Exception) {} }, handler)
            sess.setRepeatingRequest(warm, null, handler)
            delay(900)
            sess.stopRepeating()
            val bytes: ByteArray? = withTimeoutOrNull(4000L) {
                suspendCancellableCoroutine { cont ->
                    rdr.setOnImageAvailableListener({ r ->
                        val img = try { r.acquireLatestImage() } catch (_: Exception) { null }
                        if (img != null) {
                            val buf = img.planes[0].buffer
                            val arr = ByteArray(buf.remaining()).also { buf.get(it) }
                            img.close()
                            if (cont.isActive) cont.resume(arr)
                        }
                    }, handler)
                    val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(rdr.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                    }.build()
                    sess.capture(still, null, handler)
                }
            }
            if (bytes == null) return@withContext null
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val orientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            if (orientation == 0) raw else {
                val m = Matrix().apply {
                    postRotate(orientation.toFloat())
                    if (front) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "takeStill failed: ${e.message}", e)
            null
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    // ---------------- camera video ----------------

    private class Recording(
        val kind: String,
        val file: File,
        val recorder: MediaRecorder,
        val thread: HandlerThread?,
        val camera: CameraDevice?,
        val session: CameraCaptureSession?,
        val virtualDisplay: VirtualDisplay?,
        val startedAt: Long
    )

    @Volatile private var active: Recording? = null
    private var autoStopJob: Job? = null

    fun isRecording(): Boolean = active != null

    @Suppress("DEPRECATION")
    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    suspend fun startVideo(context: Context, seconds: Int, front: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (active != null) return@withContext false to "A recording is already running. Say 'stop recording' first."
        if (!hasCameraPermission(context)) return@withContext false to "Camera permission is not granted to Jarvis."
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameraId(manager, front) ?: return@withContext false to "No camera found."
        val (thread, handler) = startThread("MayaVideo")
        val file = File(MediaFiles.videoDir(context), "JARVIS_VID_${System.currentTimeMillis()}.mp4")
        val size = chooseSize(manager, id, ImageFormat.PRIVATE, 1920).let { if (it.width > 1920) Size(1280, 720) else it }
        val orientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val audio = hasAudioPermission(context)
        val recorder = newRecorder(context)
        try {
            if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOrientationHint(if (front) (360 - orientation) % 360 else orientation)
            recorder.prepare()
        } catch (e: Exception) {
            recorder.release(); thread.quitSafely()
            return@withContext false to "Could not prepare the recorder: ${e.message}"
        }
        val camera = openCamera(manager, id, handler)
        if (camera == null) { recorder.release(); thread.quitSafely(); return@withContext false to "Camera is busy or unavailable." }
        val session = createSession(camera, listOf(recorder.surface), handler)
        if (session == null) { camera.close(); recorder.release(); thread.quitSafely(); return@withContext false to "Could not start the camera session." }
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorder.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            session.setRepeatingRequest(req, null, handler)
            recorder.start()
        } catch (e: Exception) {
            session.close(); camera.close(); recorder.release(); thread.quitSafely()
            return@withContext false to "Recording failed to start: ${e.message}"
        }
        active = Recording("camera", file, recorder, thread, camera, session, null, System.currentTimeMillis())
        scheduleAutoStop(context, seconds)
        true to "Recording video with the ${if (front) "front" else "back"} camera" +
            (if (seconds > 0) " for ${TimerParsing.describe(seconds)}." else ". Say 'stop recording' to finish.") +
            (if (!audio) " (no microphone permission, so no sound)" else "")
    }

    private fun scheduleAutoStop(context: Context, seconds: Int) {
        autoStopJob?.cancel()
        val s = if (seconds <= 0) 600 else seconds.coerceAtMost(1800)
        autoStopJob = scope.launch {
            delay(s * 1000L)
            stop(context)
        }
    }

    /** Stops whichever recording is running, publishes it to the gallery and returns (ok, message, path). */
    suspend fun stop(context: Context): Triple<Boolean, String, String?> = withContext(Dispatchers.IO) {
        val rec = active ?: return@withContext Triple(false, "Nothing is being recorded.", null)
        active = null
        autoStopJob?.cancel()
        var ok = true
        try { rec.recorder.stop() } catch (e: Exception) { ok = false; Log.w(TAG, "recorder.stop failed: ${e.message}") }
        try { rec.recorder.release() } catch (_: Exception) {}
        try { rec.session?.close() } catch (_: Exception) {}
        try { rec.camera?.close() } catch (_: Exception) {}
        try { rec.virtualDisplay?.release() } catch (_: Exception) {}
        rec.thread?.quitSafely()
        if (!ok || !rec.file.exists() || rec.file.length() == 0L) {
            rec.file.delete()
            return@withContext Triple(false, "The recording was too short or failed to save.", null)
        }
        val published = publishVideo(context, rec.file)
        val secs = ((System.currentTimeMillis() - rec.startedAt) / 1000).toInt()
        Triple(true, "Saved a ${TimerParsing.describe(secs)} ${if (rec.kind == "screen") "screen recording" else "video"} to the gallery (Movies/${MediaFiles.ALBUM}).", published ?: rec.file.absolutePath)
    }

    /** Copies an app-private video into Movies/Jarvis via MediaStore; returns the content URI string. */
    private fun publishVideo(context: Context, file: File): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + MediaFiles.ALBUM)
                }
                val uri: Uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                file.delete()
                uri.toString()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), MediaFiles.ALBUM).apply { mkdirs() }
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true); file.delete()
                MediaFiles.registerVideo(context, dest).ref
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishVideo failed: ${e.message}")
            null
        }
    }

    // ---------------- screen recording ----------------

    /**
     * Records the screen using the MediaProjection that the live screen share already holds.
     * ScreenCaptureManager keeps the projection private, so it is read reflectively; Android 14+
     * may refuse a second virtual display on the same projection — then a clear message is returned.
     */
    suspend fun startScreenRecording(context: Context, seconds: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (active != null) return@withContext false to "A recording is already running. Say 'stop recording' first."
        val scm = ScreenCaptureManager.getInstance()
        if (!scm.isScreenShareActive()) return@withContext false to "Start screen share first (Jarvis home → screen share), then ask me to record the screen."
        val projection = (try {
            val f = ScreenCaptureManager::class.java.getDeclaredField("mediaProjection").apply { isAccessible = true }
            f.get(scm) as? MediaProjection
        } catch (e: Exception) { null }) ?: return@withContext false to "Screen share is on but its projection isn't reachable; please use the system screen recorder from Quick Settings."
        val metrics = context.resources.displayMetrics
        val w = (metrics.widthPixels / 2) and 1.inv()
        val h = (metrics.heightPixels / 2) and 1.inv()
        val file = File(MediaFiles.videoDir(context), "JARVIS_SCREEN_${System.currentTimeMillis()}.mp4")
        val recorder = newRecorder(context)
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(w, h)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(5_000_000)
            recorder.prepare()
            val vd = projection.createVirtualDisplay(
                "JarvisScreenRecord", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null
            )
            recorder.start()
            active = Recording("screen", file, recorder, null, null, null, vd, System.currentTimeMillis())
            scheduleAutoStop(context, seconds)
            true to "Recording the screen" + (if (seconds > 0) " for ${TimerParsing.describe(seconds)}." else ". Say 'stop recording' to finish.")
        } catch (e: SecurityException) {
            recorder.release(); file.delete()
            false to "Android doesn't allow a second capture on the current screen share. Stop screen share and use Quick Settings → Screen record, or ask me after restarting share."
        } catch (e: Exception) {
            recorder.release(); file.delete()
            false to "Screen recording failed: ${e.message}"
        }
    }
}
