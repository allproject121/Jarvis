package com.jarvis.assistant.codestudio.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.UUID

enum class TermLineKind { INPUT, STDOUT, STDERR, SYSTEM }

data class TermLine(val id: Long, val text: String, val kind: TermLineKind)

/**
 * A persistent interactive shell process (no PTY): `/system/bin/sh`, or `su -c /system/bin/sh`
 * in root mode. Commands are written to stdin followed by an end-marker echo so the session
 * knows when each command finished and its exit code. State (cd, exported vars) persists.
 */
class TerminalSession(private val workDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _lines = MutableStateFlow<List<TermLine>>(emptyList())
    val lines: StateFlow<List<TermLine>> = _lines.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** True while a command is executing (between submit and its end marker). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _rootActive = MutableStateFlow(false)
    val rootActive: StateFlow<Boolean> = _rootActive.asStateFlow()

    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var shellPid: Int = -1
    private var readers: List<Job> = emptyList()
    private var nextId = 0L
    private val marker = "__JCS_END_" + UUID.randomUUID().toString().replace("-", "").take(12) + "__"
    private val pidMarker = "__JCS_PID__"

    fun start(root: Boolean = false): Boolean {
        stopProcess()
        val dir = workDir.apply { mkdirs() }
        val cmd = if (root) {
            val su = RootShell.suPath() ?: run {
                append("Root unavailable: no 'su' binary on this device.", TermLineKind.SYSTEM)
                return false
            }
            listOf(su, "-c", "/system/bin/sh")
        } else listOf("/system/bin/sh")
        return try {
            val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(false).also {
                it.environment()["HOME"] = dir.absolutePath
                it.environment()["TERM"] = "dumb"
            }.start()
            process = p
            stdin = OutputStreamWriter(p.outputStream)
            readers = listOf(
                scope.launch { pump(p.inputStream, TermLineKind.STDOUT) },
                scope.launch { pump(p.errorStream, TermLineKind.STDERR) },
                scope.launch {
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    if (process === p) {
                        append("[shell exited with code $code — press Restart]", TermLineKind.SYSTEM)
                        _running.value = false
                        process = null
                    }
                }
            )
            _rootActive.value = root
            write("cd ${ShellRunner.shellQuote(dir.absolutePath)} 2>/dev/null; echo $pidMarker\$\$\n")
            append(if (root) "Root shell started (su). Commands run as uid 0." else "Shell started in ${dir.absolutePath}", TermLineKind.SYSTEM)
            true
        } catch (e: Exception) {
            append("Failed to start shell: ${e.message}", TermLineKind.SYSTEM)
            _rootActive.value = false
            false
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /** Sends one command line; returns false if the shell is not running. */
    fun submit(command: String): Boolean {
        append("${if (_rootActive.value) "#" else "$"} $command", TermLineKind.INPUT)
        if (!isAlive()) {
            append("Shell is not running — press Restart.", TermLineKind.SYSTEM)
            return false
        }
        _running.value = true
        // Marker goes on its own line, after the command, with the command's exit code.
        return write("$command\necho \"\"; echo \"$marker\$?\"\n").also { if (!it) _running.value = false }
    }

    /** Raw stdin line for programs that are waiting for input (no marker). */
    fun sendInput(text: String): Boolean {
        append(text, TermLineKind.INPUT)
        return write(text + "\n")
    }

    /**
     * Ctrl-C equivalent: there is no PTY, so SIGINT is delivered to every descendant
     * of the shell found via /proc (same uid only; root children are killed through su).
     */
    fun interrupt() {
        val pid = shellPid
        if (pid <= 0) { append("^C (no foreground process)", TermLineKind.SYSTEM); return }
        scope.launch {
            val kids = descendants(pid)
            if (kids.isEmpty()) {
                append("^C (nothing running)", TermLineKind.SYSTEM)
                _running.value = false
                return@launch
            }
            if (_rootActive.value) {
                ShellRunner.run("kill -INT ${kids.joinToString(" ")}; sleep 0.3; kill -9 ${kids.joinToString(" ")} 2>/dev/null", workDir, root = true, timeoutMs = 5_000)
            } else {
                kids.forEach { runCatching { android.os.Process.sendSignal(it, 2) } }
                Thread.sleep(300)
                descendants(pid).forEach { runCatching { android.os.Process.sendSignal(it, 9) } }
            }
            append("^C", TermLineKind.SYSTEM)
        }
    }

    /** Kills the whole shell (and its children). */
    fun terminate() {
        stopProcess()
        append("Shell terminated.", TermLineKind.SYSTEM)
    }

    fun clear() { synchronized(lock) { _lines.value = emptyList() } }

    fun transcript(): String = _lines.value.joinToString("\n") { it.text }

    fun dispose() {
        stopProcess()
        scope.cancel()
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun stopProcess() {
        val p = process
        process = null
        _running.value = false
        _rootActive.value = false
        if (p != null) {
            val pid = shellPid
            if (pid > 0) descendants(pid).forEach { runCatching { android.os.Process.sendSignal(it, 9) } }
            runCatching { stdin?.close() }
            p.destroy()
            runCatching { p.destroyForcibly() }
        }
        stdin = null
        shellPid = -1
        readers.forEach { it.cancel() }
        readers = emptyList()
    }

    private fun write(s: String): Boolean = try {
        stdin?.apply { write(s); flush() } != null
    } catch (e: Exception) {
        append("stdin closed: ${e.message}", TermLineKind.SYSTEM)
        false
    }

    private fun pump(stream: InputStream, kind: TermLineKind) {
        try {
            stream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    handleLine(line, kind)
                }
            }
        } catch (_: Exception) { }
    }

    private var pendingBlank = false

    private fun handleLine(line: String, kind: TermLineKind) {
        if (kind == TermLineKind.STDOUT) {
            if (line.startsWith(pidMarker)) {
                shellPid = line.removePrefix(pidMarker).trim().toIntOrNull() ?: -1
                return
            }
            if (line.startsWith(marker)) {
                pendingBlank = false
                val code = line.removePrefix(marker).trim().toIntOrNull()
                if (code != null && code != 0) append("[exit $code]", TermLineKind.SYSTEM)
                _running.value = false
                return
            }
            // The empty echo before the marker guarantees the marker starts a line; hide that blank.
            if (line.isEmpty()) {
                if (pendingBlank) append("", kind)
                pendingBlank = true
                return
            }
            if (pendingBlank) { append("", kind); pendingBlank = false }
        }
        append(line, kind)
    }

    private fun append(text: String, kind: TermLineKind) {
        synchronized(lock) {
            val cur = _lines.value
            val next = if (cur.size >= MAX_LINES) cur.drop(cur.size - MAX_LINES + 1) else cur
            _lines.value = next + TermLine(nextId++, text.take(4000), kind)
        }
    }

    private fun descendants(root: Int): List<Int> {
        val parentOf = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(f, "stat").readText() }.getOrNull() ?: return@forEach
            // format: pid (comm) state ppid ...
            val after = stat.substringAfterLast(')').trim().split(' ')
            val ppid = after.getOrNull(1)?.toIntOrNull() ?: return@forEach
            parentOf[pid] = ppid
        }
        val result = mutableListOf<Int>()
        val frontier = ArrayDeque(listOf(root))
        while (frontier.isNotEmpty()) {
            val p = frontier.removeFirst()
            parentOf.filter { it.value == p }.keys.forEach { child ->
                if (child !in result) { result += child; frontier.addLast(child) }
            }
        }
        return result.reversed() // children before parents when signalling
    }

    companion object {
        const val MAX_LINES = 3000
    }
}
