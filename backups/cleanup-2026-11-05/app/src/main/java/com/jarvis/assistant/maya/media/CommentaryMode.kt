package com.jarvis.assistant.maya.media

import android.content.Context
import android.util.Log
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Maya's esports commentator mode: injects the ported caster instruction into the live Gemini
 * session and pushes screen frames at ~2 fps while the screen share is active.
 */
object CommentaryMode {

    private const val TAG = "CommentaryMode"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null
    @Volatile var isOn: Boolean = false
        private set
    @Volatile var game: String = ""
        private set

    /** Ported from Maya (line 6618), with the game name folded in. */
    fun commentatorLine(game: String?): String {
        val g = game?.trim().orEmpty()
        val gamePart = if (g.isNotBlank()) " Game: $g." else ""
        return "[SYSTEM EVENT] Commentary mode ON.$gamePart Ab se tum ek high-energy esports commentator ho. Screen pe jo ho raha hai uspe " +
            "non-stop, exciting Hinglish commentary do — kills, knock, loot, enemy spotting, zone, vehicle, clutch moments pe react karo. " +
            "Permission ya 'kya karu' mat pucho, bas commentary karti raho jab tak Boss 'stop'/'ruk ja'/'bas' na bole. " +
            "Abhi screen dekh ke turant shuru karo, ek crisp punchy line se."
    }

    fun start(context: Context, gameName: String?): Pair<Boolean, String> {
        val prefs = MediaPrefs.get(context)
        val g = gameName?.trim().orEmpty().ifBlank { prefs.commentaryGame }
        if (!JarvisConversationService.isSessionOn.value) {
            return false to "Commentary needs the live voice session. Start Jarvis live (tap the orb), turn on screen share, then say 'commentary shuru karo'."
        }
        val scm = ScreenCaptureManager.getInstance()
        if (!scm.isScreenShareActive()) {
            return false to "Pehle screen share on karo (Jarvis home → screen share button) taaki main game dekh sakun, phir 'commentary shuru karo' bolo."
        }
        game = g
        isOn = true
        if (g.isNotBlank()) prefs.commentaryGame = g
        JarvisConversationService.sendTextMessage(commentatorLine(g))
        val intervalMs = (1000L / prefs.commentaryFps.coerceIn(1, 4))
        pushJob?.cancel()
        pushJob = scope.launch {
            while (isActive && isOn) {
                try {
                    if (!JarvisConversationService.isSessionOn.value || !scm.isScreenShareActive()) {
                        Log.i(TAG, "Live session or screen share ended; stopping commentary")
                        isOn = false
                        break
                    }
                    val jpeg = scm.captureSnapshotJpeg(60)
                    if (jpeg != null && jpeg.isNotEmpty()) JarvisConversationService.sendImmediateVideoFrame(jpeg)
                } catch (e: Exception) {
                    Log.d(TAG, "frame push failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
        return true to "Commentary mode on${if (g.isNotBlank()) " for $g" else ""} — streaming the screen at ${prefs.commentaryFps} fps."
    }

    fun stop(): String {
        val was = isOn
        isOn = false
        pushJob?.cancel()
        pushJob = null
        if (was && JarvisConversationService.isSessionOn.value) {
            JarvisConversationService.sendTextMessage("[SYSTEM EVENT] Commentary mode OFF. Stop commentating and return to normal assistant behaviour. Say one short sign-off line.")
        }
        return if (was) "Commentary band." else "Commentary was not running."
    }
}
