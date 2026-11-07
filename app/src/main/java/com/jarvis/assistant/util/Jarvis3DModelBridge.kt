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
        if (currentEntity == "orb" || currentEntity == "arc_reactor") {
            return false
        }
        return getResolvedModelFile() != null ||
                (currentEntity == "hinata_anime" && try { context.assets.open("models/hinata.glb").close(); true } catch (_: Exception) { false }) ||
                (currentEntity == "ai_girl" && try { context.assets.open("models/jett.glb").close(); true } catch (_: Exception) { false }) ||
                (currentEntity == "mora_cyber" && try { context.assets.open("models/mora.glb").close(); true } catch (_: Exception) { false })
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
            val assetPath = when (currentEntity) {
                "ai_girl" -> "models/jett.glb"
                "mora_cyber" -> "models/mora.glb"
                else -> "models/hinata.glb"
            }
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            when (JarvisApp.instance.preferences.orbEntityType) {
                "hinata_anime" -> 11168028L
                "mora_cyber" -> 4196116L
                "ai_girl" -> 2991116L
                else -> 0L
            }
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

    /**
     * RedMagic-style touch personality: the 3D page reports where she was touched, her mood
     * (happy / shy / ticklish / surprised / curious / annoyed / embarrassed / angry / sulk) and
     * the line shown in her bubble. With Gemini Live on, the live brain answers in its own voice;
     * otherwise the bubble line is spoken with the on-device voice. Rate-limited so taps can't
     * flood the conversation.
     */
    @JavascriptInterface
    fun onModelTouched(zone: String?, mood: String?, line: String?): String {
        try { HudSoundManager.playTapSound() } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        if (now - lastTouchVoiceAt < 2_500L) return "throttled"
        lastTouchVoiceAt = now
        val z = zone.orEmpty(); val m = mood.orEmpty()
        val spoken = cleanForSpeech(line)
        var result = "ok"
        try {
            val live = isLive()
            if (live) {
                val feeling = when (m) {
                    "angry", "sulk" -> "Tumhe bilkul achha nahi laga, tum naraz ho — pyaar se par saaf mana karo"
                    "embarrassed" -> "Tum sharma gayi aur thodi naraz ho — mana karo"
                    "annoyed" -> "Tum thodi chidh gayi ho"
                    "shy" -> "Tumhe achha laga, tum sharma rahi ho"
                    "ticklish" -> "Gudgudi hui, tum hans rahi ho"
                    else -> "Tum khush ho"
                }
                com.jarvis.assistant.background.JarvisConversationService.sendTextMessage(
                    "[SYSTEM EVENT — Boss ne nahi bola] Boss ne screen pe tumhe '$z' pe chhua. $feeling. " +
                        "Sirf ek chhoti natural Hinglish line bolo (jaise: \"$spoken\"), koi tool mat chalao."
                )
                result = "live"
            } else if (spoken.isNotBlank()) {
                com.jarvis.assistant.voice.PocketTtsEngine.speak(spoken, context)
                result = "local"
            }
        } catch (e: Exception) {
            Log.w("Jarvis3DModelBridge", "touch voice failed: ${e.message}")
        }
        return result
    }

    /**
     * Speaks a line the character says on her own (greeting when you come back, "maaf kiya"
     * after a sorry, a grudge reminder). Returns "live" when Gemini says it, "local" when the
     * on-device voice says it (the page then moves her lips), "throttled" or "skip" otherwise.
     */
    @JavascriptInterface
    fun speakLine(line: String?, mood: String?): String {
        val spoken = cleanForSpeech(line)
        if (spoken.isBlank()) return "skip"
        val now = System.currentTimeMillis()
        if (now - lastTouchVoiceAt < 2_500L) return "throttled"
        lastTouchVoiceAt = now
        return try {
            if (isLive()) {
                com.jarvis.assistant.background.JarvisConversationService.sendTextMessage(
                    "[SYSTEM EVENT — Boss ne nahi bola] Tum (${mood.orEmpty().ifBlank { "normal" }} mood me) " +
                        "apni awaaz me sirf ye chhoti line bolo, kuch aur nahi, koi tool nahi: \"$spoken\""
                )
                "live"
            } else {
                com.jarvis.assistant.voice.PocketTtsEngine.speak(spoken, context)
                "local"
            }
        } catch (e: Exception) {
            Log.w("Jarvis3DModelBridge", "speakLine failed: ${e.message}")
            "skip"
        }
    }

    /**
     * Battery level and charger state for the character ("Charging shuru!", low-battery nudge).
     * Uses the sticky ACTION_BATTERY_CHANGED broadcast, so no receiver is registered.
     * Returns JSON: {"level": 0-100, "plugged": true/false}.
     */
    @JavascriptInterface
    fun getBatteryInfo(): String {
        return try {
            val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            "{\"level\":$pct,\"plugged\":$plugged}"
        } catch (_: Exception) {
            "{\"level\":-1,\"plugged\":false}"
        }
    }

    /** True while Gemini Live is connected (the page then lets Gemini do the talking). */
    @JavascriptInterface
    fun isVoiceLive(): Boolean = try { isLive() } catch (_: Exception) { false }

    private fun isLive(): Boolean =
        com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value &&
            com.jarvis.assistant.background.JarvisConversationService.connectionStatus.value == "LIVE"

    /** Drops emoji / symbols so the voice doesn't read them out. */
    private fun cleanForSpeech(line: String?): String =
        line.orEmpty().replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s.,!?'~-]"), "").replace(Regex("\\s+"), " ").trim()

    @Volatile
    private var lastTouchVoiceAt = 0L

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
