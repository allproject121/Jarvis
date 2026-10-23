package com.jarvis.assistant.util

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

class Jarvis3DModelBridge(private val context: Context) {

    companion object {
        private const val TAG = "Jarvis3DModelBridge"
        private val _modelDiagnosticsFlow = MutableStateFlow<String>("{}")
        val modelDiagnosticsFlow: StateFlow<String> = _modelDiagnosticsFlow.asStateFlow()
        var latestDiagnosticsJson: String = "{}"
            private set
    }

    @JavascriptInterface
    fun reportModelDiagnostics(jsonStr: String) {
        Log.i(TAG, "Model diagnostics reported: $jsonStr")
        latestDiagnosticsJson = jsonStr
        _modelDiagnosticsFlow.value = jsonStr
    }

    @JavascriptInterface
    fun getModelDiagnostics(): String {
        return latestDiagnosticsJson
    }

    @JavascriptInterface
    fun hasCustomAvatar(): Boolean {
        val f = CustomAvatarHelper.getAvatarFile(context)
        return f.exists() && f.length() > 0
    }

    @JavascriptInterface
    fun getCustomAvatarBase64(): String {
        return CustomAvatarHelper.getBase64(context) ?: ""
    }

    private fun getResolvedModelFile(): File? {
        return CustomAvatarHelper.getActiveModelFile(context)
            ?: ModelVaultManager.getActiveModelFile(context)
            ?: CustomAvatarHelper.get3DModelFile(context)
    }

    @JavascriptInterface
    fun getModelCategory(): String {
        return try {
            ModelVaultManager.getActiveVaultItem(context).category
        } catch (_: Exception) {
            "orb"
        }
    }

    @JavascriptInterface
    fun hasCustom3DModel(): Boolean {
        val currentEntity = JarvisApp.instance.preferences.orbEntityType
        if (currentEntity == "orb") {
            return false
        }
        return getResolvedModelFile() != null ||
                (currentEntity == "hinata_anime" && try { context.assets.open("models/hinata.glb").close(); true } catch (_: Exception) { false }) ||
                (currentEntity == "ai_girl" && try { context.assets.open("models/jett.glb").close(); true } catch (_: Exception) { false })
    }

    @JavascriptInterface
    fun getModelFormat(): String {
        return getResolvedModelFile()?.extension?.lowercase() ?: "glb"
    }

    @JavascriptInterface
    fun getModelFileSize(): Long {
        val file = getResolvedModelFile()
        if (file != null && file.exists()) return file.length()
        return try {
            val currentEntity = JarvisApp.instance.preferences.orbEntityType
            val assetPath = if (currentEntity == "ai_girl") "models/jett.glb" else "models/hinata.glb"
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            0L
        }
    }

    @JavascriptInterface
    fun getModelChunkCount(chunkSize: Int): Int {
        val file = getResolvedModelFile()
        val totalSize = file?.length() ?: getModelFileSize()
        if (totalSize <= 0L) return 0
        val size = if (chunkSize <= 0) 256 * 1024 else chunkSize
        return ((totalSize + size - 1) / size).toInt()
    }

    @JavascriptInterface
    fun getModelChunk(index: Int, chunkSize: Int): String {
        val file = getResolvedModelFile()
        val size = if (chunkSize <= 0) 256 * 1024 else chunkSize
        val offset = index.toLong() * size

        if (file != null && file.exists()) {
            val fileLen = file.length()
            if (offset >= fileLen) return ""
            val toRead = minOf(size.toLong(), fileLen - offset).toInt()
            val buffer = ByteArray(toRead)
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    raf.readFully(buffer)
                }
                Base64.encodeToString(buffer, Base64.NO_WRAP)
            } catch (_: Throwable) {
                ""
            }
        } else {
            return try {
                val currentEntity = JarvisApp.instance.preferences.orbEntityType
                val assetPath = if (currentEntity == "ai_girl") "models/jett.glb" else "models/hinata.glb"
                context.assets.open(assetPath).use { stream ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val s = stream.skip(offset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val buffer = ByteArray(size)
                    val read = stream.read(buffer)
                    if (read > 0) {
                        Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                    } else {
                        ""
                    }
                }
            } catch (_: Throwable) {
                ""
            }
        }
    }

    @JavascriptInterface
    fun onModelTouchReact(): String {
        try {
            HudSoundManager.playTapSound()
        } catch (_: Exception) {}
        return "ok"
    }

    @JavascriptInterface
    fun getModelDescription(): String {
        val active = CustomAvatarHelper.getActiveModel(context)
        if (active != null) {
            return "${active.name} (${active.format.uppercase()}, ${active.formattedSize})"
        }
        val file = getResolvedModelFile() ?: return ""
        val ext = file.extension.uppercase()
        val mb = String.format("%.2f MB", file.length() / (1024f * 1024f))
        return "$ext Model ($mb)"
    }
}
