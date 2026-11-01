package com.jarvis.assistant.androllm.voice

import android.util.Log

/**
 * Guards every sherpa-onnx entry point.
 *
 * The sherpa-onnx 1.13.4 Kotlin classes load `libsherpa-onnx-jni.so` in their
 * static initializers. That JNI library links `OrtGetApiBase@VERS_1.27.0` from
 * `libonnxruntime.so`; Jarvis packages its own ONNX Runtime 1.20.0 copy (used by
 * PocketTTS) from app/src/main/jniLibs, which does not define that symbol version.
 * On such a build the class initializer throws `UnsatisfiedLinkError` (or a later
 * `NoClassDefFoundError`). This probe turns that into a readable status instead of
 * a crash, and every sherpa engine checks [isAvailable] before touching sherpa.
 */
object SherpaNative {

    private const val TAG = "SherpaNative"

    @Volatile private var probed = false
    @Volatile private var available = false
    @Volatile var errorMessage: String? = null
        private set

    /** True when the sherpa-onnx JNI library loaded and linked successfully. */
    fun isAvailable(): Boolean {
        if (probed) return available
        synchronized(this) {
            if (probed) return available
            try {
                // Triggers KeywordSpotter's static init -> System.loadLibrary("sherpa-onnx-jni").
                Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
                available = true
                errorMessage = null
            } catch (t: Throwable) {
                available = false
                errorMessage = describe(t)
                Log.e(TAG, "sherpa-onnx native library unavailable: $errorMessage", t)
            }
            probed = true
        }
        return available
    }

    private fun describe(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.last()
        val msg = root.message ?: root.javaClass.simpleName
        return if (msg.contains("VERS_") || msg.contains("OrtGetApiBase")) {
            "ONNX Runtime version mismatch (sherpa-onnx needs ORT 1.27; app ships another libonnxruntime.so): $msg"
        } else msg
    }
}
