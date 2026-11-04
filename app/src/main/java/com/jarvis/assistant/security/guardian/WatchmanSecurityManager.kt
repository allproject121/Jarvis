package com.jarvis.assistant.security.guardian

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchmanSecurityManager(private val context: Context) {
    companion object {
        private const val TAG = "WatchmanSecurity"
    }

    private val watchmanDir = File(context.filesDir, "watchman").apply {
        if (!exists()) mkdirs()
    }

    fun listIntruderPhotos(): List<File> {
        return watchmanDir.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Stealth photo capture of intruder from front camera.
     */
    fun captureIntruderPhoto(callback: ((File?) -> Unit)? = null) {
        // Report exactly once, whichever path ends the capture (success, error, disconnect).
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        val onComplete: ((File?) -> Unit) = { file ->
            if (reported.compareAndSet(false, true)) callback?.invoke(file)
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            onComplete(null)
            return
        }

        try {
            // Find front-facing camera
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (frontCameraId == null) {
                Log.w(TAG, "No suitable camera found for intruder capture.")
                onComplete(null)
                return
            }

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            val handler = Handler(Looper.getMainLooper())

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        imageReader.setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()

                                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val file = File(watchmanDir, "intruder_$timeStamp.jpg")
                                FileOutputStream(file).use { it.write(bytes) }
                                Log.i(TAG, "Captured intruder photo: ${file.absolutePath}")

                                camera.close()
                                reader.close()
                                onComplete(file)
                            }
                        }, handler)

                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to capture still: ${e.message}")
                                        camera.close()
                                        onComplete(null)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    onComplete(null)
                                }
                            },
                            handler
                        )
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Camera permission missing: ${e.message}")
                        camera.close()
                        onComplete(null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in capture setup: ${e.message}")
                        camera.close()
                        onComplete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // e.g. camera taken away / blocked while the app is in the background
                    camera.close()
                    imageReader.close()
                    onComplete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error $error (background camera access is restricted on Android 11+)")
                    camera.close()
                    imageReader.close()
                    onComplete(null)
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing: ${e.message}")
            onComplete(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
            onComplete(null)
        }
    }
}
