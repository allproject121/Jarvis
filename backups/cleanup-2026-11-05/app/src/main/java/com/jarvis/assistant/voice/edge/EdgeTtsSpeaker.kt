package com.jarvis.assistant.voice.edge

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.jarvis.assistant.data.preferences.JarvisExtraPrefs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Plays Edge neural speech for Jarvis' NON-Gemini voice paths (PocketTtsEngine users,
 * the offline voice engine and the AndroLLM voice assistant through PocketTtsEngine).
 *
 * - Sequential queue; synthesis of queued items starts immediately (prefetch) so chunked
 *   replies play back-to-back.
 * - Playback via the platform [MediaPlayer] from the cached MP3 file (no ExoPlayer).
 * - When synthesis fails, the item's `onFailed` callback runs so the caller can speak the
 *   same text with Jarvis' existing TTS.
 */
object EdgeTtsSpeaker {

    private const val TAG = "EdgeTtsSpeaker"

    private class Item(
        val text: String,
        val generation: Int,
        val audio: Deferred<File?>,
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onFailed: () -> Unit
    )

    private class Playback(val player: MediaPlayer, val cont: CancellableContinuation<Boolean>) {
        fun abort() {
            runCatching { player.stop() }
            runCatching { player.release() }
            if (cont.isActive) cont.resume(false)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private val inFlight = ConcurrentLinkedQueue<Item>()
    private val lock = Any()

    @Volatile private var generation = 0
    @Volatile private var playback: Playback? = null
    private var worker: Job? = null

    private val _speaking = MutableStateFlow(false)

    /** True while Edge audio is playing (read by the Dynamic Island). */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Edge is selected in Settings and a network is available. */
    fun isUsable(context: Context): Boolean =
        JarvisExtraPrefs.isEdgeTtsSelected(context) && EdgeTtsClient.isNetworkAvailable(context)

    /**
     * Speaks [text] with the voice selected in Settings (or [voiceOverride]).
     * [flush] = true stops current/queued speech first (QUEUE_FLUSH semantics).
     */
    fun speak(
        context: Context,
        text: String,
        flush: Boolean = true,
        voiceOverride: String? = null,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val app = context.applicationContext
        if (flush) stop()
        val voice = voiceOverride?.takeIf { it.isNotBlank() } ?: JarvisExtraPrefs.edgeVoice(app)
        val rate = JarvisExtraPrefs.edgeRatePercent(app)
        val pitch = JarvisExtraPrefs.edgePitchHz(app)
        val audio = scope.async { EdgeTtsClient.synthesizeToFile(app, text, voice, rate, pitch) }
        val item = Item(text, generation, audio, onStart, onDone, onFailed)
        inFlight.add(item)
        ensureWorker()
        queue.trySend(item)
    }

    /** Stops playback and drops everything queued (no callbacks fire for dropped items). */
    fun stop() {
        synchronized(lock) {
            generation++
        }
        while (true) {
            val item = inFlight.poll() ?: break
            item.audio.cancel()
        }
        playback?.abort()
        playback = null
        _speaking.value = false
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                for (item in queue) {
                    try {
                        process(item)
                    } catch (e: Exception) {
                        Log.w(TAG, "Edge speech item failed: ${e.message}")
                    } finally {
                        inFlight.remove(item)
                    }
                }
            }
        }
    }

    private suspend fun process(item: Item) {
        if (item.generation != generation) return
        val file = try {
            item.audio.await()
        } catch (_: Exception) {
            null
        }
        if (item.generation != generation) return
        if (file == null) {
            Log.i(TAG, "Edge voice unavailable - falling back to Jarvis TTS")
            runCatching { item.onFailed() }
            return
        }
        _speaking.value = true
        runCatching { item.onStart() }
        val ok = play(file)
        if (inFlight.size <= 1) _speaking.value = false
        if (item.generation != generation) return
        if (!ok) {
            // Corrupt/undecodable cache entry: drop it and let Jarvis TTS speak instead.
            runCatching { file.delete() }
            runCatching { item.onFailed() }
            return
        }
        runCatching { item.onDone() }
    }

    private suspend fun play(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        val pb = Playback(player, cont)
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                runCatching { it.release() }
                if (playback === pb) playback = null
                if (cont.isActive) cont.resume(true)
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                runCatching { mp.release() }
                if (playback === pb) playback = null
                if (cont.isActive) cont.resume(false)
                true
            }
            player.prepare()
            playback = pb
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "Edge playback failed: ${e.message}")
            runCatching { player.release() }
            if (playback === pb) playback = null
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { pb.abort() }
    }
}
