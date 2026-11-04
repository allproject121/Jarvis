package com.jarvis.assistant.maya.brain

import android.util.Log
import com.jarvis.assistant.androllm.voice.asr.StreamingSpeechRecognizer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Deepgram nova-3 streaming transcriber over an OkHttp WebSocket (Maya's "Deepgram" /
 * "Backup transcriber"). Implements the AndroLLM [StreamingSpeechRecognizer] contract:
 * [feed] takes 16 kHz float chunks and returns the interim transcript, [isEndpoint] turns
 * true on `speech_final` / `UtteranceEnd`, [finalText] returns the accumulated final
 * transcript (sending `Finalize` and waiting briefly when the server has not closed the
 * utterance yet).
 *
 * KeepAlive frames are sent every 5 s while idle; the socket reconnects once on failure.
 * [lastError] carries a clear text when the key is missing / rejected / out of credit.
 */
class DeepgramStreamingRecognizer(
    private val apiKeyProvider: () -> String,
    private val endpointingMs: Int = 300,
    private val language: String = "multi",
    private val model: String = "nova-3"
) : StreamingSpeechRecognizer {

    companion object {
        private const val TAG = "DeepgramSTT"
        private const val KEEPALIVE_MS = 5_000L
        private const val FINALIZE_WAIT_MS = 1_500L
        private const val CONNECT_WAIT_MS = 6_000L

        const val ERR_NO_KEY = "Needs a Deepgram key — save one below first. (Settings → Voice engine → Backup transcriber me Deepgram API key daalo.)"
        const val ERR_BAD_KEY = "Deepgram ki key galat hai ya band kar di gayi hai — Settings → Voice engine → Backup transcriber me sahi key daalo."
        const val ERR_NO_CREDIT = "Deepgram ka credit khatam — Deepgram account me credit daalo, ya Settings me nayi key."
        const val ERR_NETWORK = "Deepgram se connect nahi ho paaya — internet check karo, phir dobara bolo."

        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    @Volatile private var reconnectedOnce = false
    @Volatile private var sessionActive = false
    @Volatile private var lastSendAt = 0L
    @Volatile private var keepAliveThread: Thread? = null

    private val finals = StringBuilder()
    @Volatile private var interim: String = ""
    @Volatile private var endpoint = false
    @Volatile private var lastConfidence = 0f
    @Volatile private var tokenCount = 0

    /** Clear human-readable reason for the last failure (null when healthy). */
    @Volatile var lastError: String? = null
        private set

    override val isInitialized: Boolean get() = connected

    override fun ensureInitialized(): Boolean {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            lastError = ERR_NO_KEY
            return false
        }
        if (connected) return true
        return connect(key)
    }

    override fun updateSilenceTimeout(seconds: Float) {
        // Endpointing is a URL parameter fixed at connect time (Maya uses 300 ms).
    }

    override fun startSession() {
        lock.withLock {
            finals.setLength(0)
            interim = ""
            endpoint = false
            tokenCount = 0
            sessionActive = true
        }
        if (!connected) ensureInitialized()
    }

    override fun feed(samples: FloatArray): String {
        if (samples.isEmpty()) return interim
        val ws = socket
        if (ws == null || !connected) {
            if (!reconnectedOnce && apiKeyProvider().isNotBlank()) {
                reconnectedOnce = true
                Log.w(TAG, "Socket down while feeding; reconnecting once")
                connect(apiKeyProvider())
            }
            return interim
        }
        val bytes = DeepgramJson.floatToPcm16(samples)
        if (!ws.send(bytes.toByteString(0, bytes.size))) {
            Log.w(TAG, "send() returned false; socket is closing")
            connected = false
        }
        lastSendAt = System.currentTimeMillis()
        return currentText()
    }

    override fun isEndpoint(): Boolean = endpoint

    override fun finalText(): String {
        val ws = socket
        if (ws != null && connected && !endpoint) {
            // Ask Deepgram to flush whatever it is still holding, then wait a little.
            runCatching { ws.send("""{"type":"Finalize"}""") }
            lock.withLock {
                val deadline = System.currentTimeMillis() + FINALIZE_WAIT_MS
                while (!endpoint && System.currentTimeMillis() < deadline) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    runCatching { stateChanged.await(remaining, TimeUnit.MILLISECONDS) }
                }
            }
        }
        val text = lock.withLock {
            val f = finals.toString().trim()
            if (f.isNotBlank()) f else interim.trim()
        }
        reset()
        return text
    }

    override fun lastTokenCount(): Int = tokenCount

    override fun estimatedConfidence(): Float = lastConfidence

    override fun reset() {
        lock.withLock {
            finals.setLength(0)
            interim = ""
            endpoint = false
            tokenCount = 0
        }
    }

    override fun release() {
        sessionActive = false
        stopKeepAlive()
        runCatching {
            socket?.send("""{"type":"CloseStream"}""")
            socket?.close(1000, "released")
        }
        socket = null
        connected = false
        reset()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun currentText(): String = lock.withLock {
        val f = finals.toString().trim()
        when {
            f.isBlank() -> interim
            interim.isBlank() -> f
            else -> "$f $interim"
        }
    }

    private fun connect(key: String): Boolean {
        lock.withLock {
            if (connected) return true
            if (connecting) {
                val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
                while (connecting && System.currentTimeMillis() < deadline) {
                    runCatching { stateChanged.await(200, TimeUnit.MILLISECONDS) }
                }
                return connected
            }
            connecting = true
            lastError = null
        }
        val url = DeepgramJson.listenUrl(endpointingMs, model, language)
        Log.i(TAG, "STT connect deepgram model=$model language=$language")
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $key")
            .build()
        runCatching { socket?.cancel() }
        socket = http.newWebSocket(request, listener)
        lock.withLock {
            val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
            while (connecting && System.currentTimeMillis() < deadline) {
                runCatching { stateChanged.await(200, TimeUnit.MILLISECONDS) }
            }
            if (connecting) {
                connecting = false
                if (lastError == null) lastError = ERR_NETWORK
            }
        }
        if (connected) startKeepAlive()
        return connected
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lock.withLock {
                connected = true
                connecting = false
                reconnectedOnce = false
                lastError = null
                stateChanged.signalAll()
            }
            Log.i(TAG, "STT ready (deepgram)")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = DeepgramJson.parse(text) ?: return
            lock.withLock {
                when {
                    msg.isResult -> {
                        if (msg.transcript.isNotBlank()) {
                            tokenCount += msg.transcript.split(' ').size
                            lastConfidence = msg.confidence.toFloat()
                        }
                        if (msg.isFinal) {
                            if (msg.transcript.isNotBlank()) {
                                if (finals.isNotEmpty()) finals.append(' ')
                                finals.append(msg.transcript)
                            }
                            interim = ""
                            if (msg.speechFinal && finals.isNotBlank()) endpoint = true
                        } else if (msg.transcript.isNotBlank()) {
                            interim = msg.transcript
                        }
                    }
                    msg.isUtteranceEnd -> if (finals.isNotBlank() || interim.isNotBlank()) {
                        if (finals.isBlank() && interim.isNotBlank()) {
                            finals.append(interim); interim = ""
                        }
                        endpoint = true
                    }
                    msg.isError -> {
                        lastError = msg.detail.ifBlank { "Deepgram error" }
                        Log.w(TAG, "Deepgram error frame: ${msg.detail}")
                    }
                }
                stateChanged.signalAll()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Deepgram only sends JSON text frames.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "STT closing (deepgram) code=$code $reason")
            connected = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "STT lost (deepgram) code=$code $reason")
            lock.withLock { connected = false; connecting = false; stateChanged.signalAll() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code ?: 0
            lastError = when (code) {
                401, 403 -> ERR_BAD_KEY
                402 -> ERR_NO_CREDIT
                0 -> ERR_NETWORK
                else -> "Deepgram HTTP $code: ${t.message ?: response?.message.orEmpty()}"
            }
            Log.w(TAG, "STT lost (deepgram) code=$code: ${t.message}")
            lock.withLock { connected = false; connecting = false; stateChanged.signalAll() }
        }
    }

    private fun startKeepAlive() {
        if (keepAliveThread?.isAlive == true) return
        keepAliveThread = Thread({
            try {
                while (connected && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(1_000L)
                    if (System.currentTimeMillis() - lastSendAt >= KEEPALIVE_MS) {
                        socket?.send("""{"type":"KeepAlive"}""")
                        lastSendAt = System.currentTimeMillis()
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "deepgram-keepalive").apply { isDaemon = true; start() }
    }

    private fun stopKeepAlive() {
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }
}
