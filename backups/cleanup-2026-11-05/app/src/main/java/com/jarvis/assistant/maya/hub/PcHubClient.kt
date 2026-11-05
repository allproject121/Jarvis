package com.jarvis.assistant.maya.hub

import android.content.Context
import android.os.Build
import android.util.Log
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.memory.MemoryManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Phone side of the PC <-> Phone hub: an OkHttp WebSocket to the Node companion
 * (`companion/server.js`, path `/jarvis`) with join-code pairing, request/reply matching,
 * auto-reconnect with exponential backoff, a text "call" relay session and two-way memory sync.
 */
class PcHubClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PcHubClient"
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private const val ASK_TIMEOUT_MS = 120_000L
        private const val MAX_BACKOFF_MS = 30_000L

        @Volatile
        private var instance: PcHubClient? = null

        fun get(context: Context): PcHubClient =
            instance ?: synchronized(this) {
                instance ?: PcHubClient(context.applicationContext).also { instance = it }
            }
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, PAIRED, ERROR }

    data class LogLine(val ts: Long, val direction: String, val text: String)

    private val prefs = HubPrefs.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    private val _callActive = MutableStateFlow(false)
    /** True while a relay "call" is open: user turns are forwarded to the PC until end_pc_call. */
    val callActive: StateFlow<Boolean> = _callActive

    private val _log = MutableSharedFlow<LogLine>(replay = 60, extraBufferCapacity = 60)
    /** Transcript of hub traffic for the UI. */
    val log: SharedFlow<LogLine> = _log

    private val pcName: String get() = prefs.pcName

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var backoffMs = 1_000L
    @Volatile private var manualClose = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PcHubProtocol.Message>>()
    private val pendingPairCode = MutableStateFlow("")
    /** PC replies that arrived during a call while no live session could take them. */
    private val callReplyBuffer = ArrayDeque<String>()

    // ── Connection lifecycle ────────────────────────────────────────────────

    fun isLinked(): Boolean = prefs.pcLinked && prefs.pcHost.isNotBlank()
    fun isConnected(): Boolean = _state.value == State.CONNECTED || _state.value == State.PAIRED
    fun isPaired(): Boolean = _state.value == State.PAIRED

    fun describe(): String = when (_state.value) {
        State.PAIRED -> "Paired with ${pcName.ifBlank { "PC" }} at ${prefs.pcHost}:${prefs.pcPort}" + if (_callActive.value) " (call active)" else ""
        State.CONNECTED -> "Connected to ${prefs.pcHost}:${prefs.pcPort}, not paired yet"
        State.CONNECTING -> "Connecting to ${prefs.pcHost}:${prefs.pcPort}..."
        State.ERROR -> "Error: ${_lastError.value.ifBlank { "unknown" }} (${prefs.pcHost}:${prefs.pcPort})"
        State.DISCONNECTED -> if (isLinked()) "Linked to ${prefs.pcHost}:${prefs.pcPort} but offline" else "No PC linked"
    }

    /** Opens (or re-opens) the socket using the stored host/port. Safe to call repeatedly. */
    @Synchronized
    fun connect(pairCode: String? = null) {
        val host = prefs.pcHost
        if (host.isBlank()) {
            _state.value = State.DISCONNECTED
            return
        }
        if (pairCode != null) pendingPairCode.value = pairCode
        if (_state.value == State.CONNECTING || isConnected()) {
            if (pairCode != null && _state.value == State.CONNECTED) sendPair(pairCode)
            return
        }
        manualClose = false
        _state.value = State.CONNECTING
        val url = PcHubProtocol.wsUrl(host, prefs.pcPort, prefs.relayToken)
        val req = Request.Builder().url(url).build()
        socket = http.newWebSocket(req, listener)
        emit("sys", "connecting $host:${prefs.pcPort}")
    }

    @Synchronized
    fun disconnect(reason: String = "closed by phone") {
        manualClose = true
        reconnectJob?.cancel()
        socket?.close(1000, reason)
        socket = null
        _state.value = State.DISCONNECTED
        _callActive.value = false
        failAllPending("disconnected")
    }

    /** Called on app start by the tool pack so a linked PC reconnects in the background. */
    fun autoConnectIfLinked() {
        if (isLinked() && prefs.pcAutoConnect && !isConnected()) connect()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = 1_000L
            _state.value = State.CONNECTED
            _lastError.value = ""
            emit("sys", "connected")
            val hello = PcHubProtocol.build(
                PcHubProtocol.T_HELLO,
                PcHubProtocol.helloPayload(
                    deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
                    appVersion = appVersion(),
                    phoneJoinCode = ensurePhoneCode(),
                    token = prefs.relayToken
                )
            )
            webSocket.send(hello.encode())
            val code = pendingPairCode.value.ifBlank { prefs.pairSecret }
            if (code.isNotBlank()) sendPair(code)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = PcHubProtocol.decode(text) ?: return
            handleIncoming(msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            emit("sys", "closed: $reason")
            _state.value = State.DISCONNECTED
            _callActive.value = false
            failAllPending("socket closed")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _lastError.value = t.message ?: t.javaClass.simpleName
            _state.value = State.ERROR
            _callActive.value = false
            emit("sys", "failure: ${_lastError.value}")
            failAllPending(_lastError.value)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (manualClose || !isLinked() || !prefs.pcAutoConnect) return
        reconnectJob?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        reconnectJob = scope.launch {
            delay(wait)
            if (!manualClose && !isConnected()) connect()
        }
    }

    private fun sendPair(code: String) {
        val normalized = PcPairing.normalize(code) ?: return
        socket?.send(PcHubProtocol.build(PcHubProtocol.T_PAIR, JSONObject().put("code", normalized).put("phone_code", ensurePhoneCode())).encode())
        emit("out", "pair ***")
    }

    private fun handleIncoming(msg: PcHubProtocol.Message) {
        emit("in", "${msg.type} ${msg.text().take(200)}")
        when (msg.type) {
            PcHubProtocol.T_WELCOME -> {
                msg.payload.optString("pc_name").takeIf { it.isNotBlank() }?.let { prefs.pcName = it }
                if (msg.payload.optBoolean("paired", false)) {
                    _state.value = State.PAIRED
                    prefs.pcLinked = true
                }
            }
            PcHubProtocol.T_PAIRED -> {
                msg.payload.optString("token").takeIf { it.isNotBlank() }?.let { prefs.relayToken = it }
                msg.payload.optString("pc_name").takeIf { it.isNotBlank() }?.let { prefs.pcName = it }
                val code = pendingPairCode.value
                if (code.isNotBlank()) prefs.pairSecret = PcPairing.normalize(code) ?: code
                pendingPairCode.value = ""
                prefs.pcLinked = true
                _state.value = State.PAIRED
                notifyLive("PC hub paired with ${prefs.pcName.ifBlank { "PC" }}.")
            }
            PcHubProtocol.T_PAIR_FAILED -> {
                _lastError.value = msg.payload.optString("reason", "pair failed")
                pendingPairCode.value = ""
                if (_state.value == State.PAIRED) _state.value = State.CONNECTED
            }
            PcHubProtocol.T_CALL_STARTED -> _callActive.value = true
            PcHubProtocol.T_CALL_ENDED -> {
                _callActive.value = false
                notifyLive("PC call ended by the PC.")
            }
            PcHubProtocol.T_CALL_REPLY, PcHubProtocol.T_EVENT -> {
                val t = msg.text()
                if (t.isNotBlank()) deliverPcText(t)
            }
            PcHubProtocol.T_PONG -> Unit
        }
        val replyTo = msg.replyTo
        if (replyTo != null) pending.remove(replyTo)?.complete(msg)
    }

    /** Injects "[PC] ..." into the live Gemini session, else buffers it for the next tool reply. */
    private fun deliverPcText(text: String) {
        val line = "[PC] $text"
        if (JarvisConversationService.isSessionOn.value) {
            try {
                JarvisConversationService.sendTextMessage(line)
                return
            } catch (e: Exception) {
                Log.w(TAG, "live inject failed: ${e.message}")
            }
        }
        synchronized(callReplyBuffer) { callReplyBuffer.addLast(line); while (callReplyBuffer.size > 20) callReplyBuffer.removeFirst() }
    }

    /** Drains replies that could not be injected live. */
    fun drainBufferedReplies(): List<String> = synchronized(callReplyBuffer) {
        val out = callReplyBuffer.toList(); callReplyBuffer.clear(); out
    }

    // ── Requests ────────────────────────────────────────────────────────────

    private suspend fun request(msg: PcHubProtocol.Message, timeoutMs: Long = REQUEST_TIMEOUT_MS): Result<PcHubProtocol.Message> {
        val ws = socket
        if (ws == null || !isConnected()) {
            if (isLinked()) connect()
            return Result.failure(IllegalStateException("PC is not connected (${describe()})"))
        }
        val deferred = CompletableDeferred<PcHubProtocol.Message>()
        pending[msg.id] = deferred
        if (!ws.send(msg.encode())) {
            pending.remove(msg.id)
            return Result.failure(IllegalStateException("Could not send to PC (socket closed)"))
        }
        emit("out", "${msg.type} ${msg.text().take(200)}")
        val reply = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(msg.id)
            return Result.failure(e)
        }
        pending.remove(msg.id)
        if (reply == null) return Result.failure(IllegalStateException("PC did not answer within ${timeoutMs / 1000}s"))
        if (reply.type == PcHubProtocol.T_ERROR) return Result.failure(IllegalStateException(reply.payload.optString("message", "PC error")))
        return Result.success(reply)
    }

    /** Waits briefly for pairing to complete after [connect]. */
    suspend fun awaitPaired(timeoutMs: Long = 12_000L): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isPaired()) return true
            if (_state.value == State.ERROR) return false
            if (_lastError.value.isNotBlank() && _state.value == State.CONNECTED && pendingPairCode.value.isBlank()) return false
            delay(250)
        }
        return isPaired()
    }

    suspend fun ask(question: String): Result<String> =
        request(PcHubProtocol.text(PcHubProtocol.T_ASK, question), ASK_TIMEOUT_MS).map { it.text().ifBlank { "(PC sent an empty reply)" } }

    suspend fun control(command: String): Result<JSONObject> =
        request(PcHubProtocol.build(PcHubProtocol.T_CONTROL, JSONObject().put("command", command))).map { it.payload }

    suspend fun startCall(): Result<String> {
        val r = request(PcHubProtocol.build(PcHubProtocol.T_CALL_START))
        if (r.isSuccess) _callActive.value = true
        return r.map { it.text().ifBlank { "Call relay open with ${prefs.pcName.ifBlank { "PC" }}." } }
    }

    /** Forwards one user turn during a call; the PC's answer is injected as "[PC] ..." or returned. */
    suspend fun sendCallChunk(text: String): Result<String> {
        if (!_callActive.value) return Result.failure(IllegalStateException("No PC call is active - use call_my_pc first."))
        val r = request(PcHubProtocol.text(PcHubProtocol.T_CALL_CHUNK, text), ASK_TIMEOUT_MS)
        return r.map { it.text() }
    }

    suspend fun endCall(): Result<String> {
        if (!_callActive.value && !isConnected()) return Result.success("No PC call was active.")
        val r = request(PcHubProtocol.build(PcHubProtocol.T_CALL_END), 10_000L)
        _callActive.value = false
        return if (r.isSuccess) Result.success("PC call ended.") else Result.success("PC call ended locally (${r.exceptionOrNull()?.message}).")
    }

    suspend fun ping(): Result<Long> {
        val t0 = System.currentTimeMillis()
        return request(PcHubProtocol.build(PcHubProtocol.T_PING), 8_000L).map { System.currentTimeMillis() - t0 }
    }

    // ── Memory sync ─────────────────────────────────────────────────────────

    /** Pushes the phone's exported memory JSON to the PC. */
    suspend fun pushMemory(): Result<String> = withContext(Dispatchers.IO) {
        val file = MemoryManager.getInstance(context).exportMemories().getOrElse { return@withContext Result.failure(it) }
        val raw = try { file.readText() } catch (e: Exception) { return@withContext Result.failure(e) }
        val payload = JSONObject().put("format", "jarvis-memory-export").put("data", raw).put("bytes", raw.length)
        request(PcHubProtocol.build(PcHubProtocol.T_MEMORY_PUSH, payload), 60_000L).map { reply ->
            prefs.lastMemorySyncAt = System.currentTimeMillis()
            "Pushed ${raw.length} bytes of memories to PC" + reply.payload.optString("summary", "").let { if (it.isNotBlank()) " ($it)" else "" }
        }
    }

    /** Pulls the PC's memory JSON and imports it (additive merge handled by MemoryManager). */
    suspend fun pullMemory(): Result<String> = withContext(Dispatchers.IO) {
        request(PcHubProtocol.build(PcHubProtocol.T_MEMORY_PULL), 60_000L).mapCatching { reply ->
            val raw = reply.payload.optString("data", "")
            if (raw.isBlank()) return@mapCatching "PC has no memories to share yet."
            val res = MemoryManager.getInstance(context).importMemories(raw)
            prefs.lastMemorySyncAt = System.currentTimeMillis()
            "Pulled memories from PC: ${res.inserted} new, ${res.updated} updated, ${res.skipped} skipped."
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    fun ensurePhoneCode(): String {
        val existing = prefs.phoneJoinCode
        if (existing.isNotBlank()) return existing
        return PcPairing.generateCode().also { prefs.phoneJoinCode = it }
    }

    fun regeneratePhoneCode(): String = PcPairing.generateCode().also { prefs.phoneJoinCode = it }

    private fun failAllPending(reason: String) {
        val err = IllegalStateException(reason)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun emit(direction: String, text: String) {
        _log.tryEmit(LogLine(System.currentTimeMillis(), direction, text))
    }

    private fun notifyLive(text: String) {
        try {
            if (JarvisConversationService.isSessionOn.value) JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
        } catch (e: Exception) {
            Log.w(TAG, "notifyLive failed: ${e.message}")
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
