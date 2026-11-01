package com.jarvis.assistant.codestudio.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Result of a one-shot shell command. [exitCode] is -1 when the process was killed (timeout) or failed to start. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Reusable non-UI shell executor (used by the agent tool `run_shell_command` and by the IDE).
 *
 * - Normal mode: `/system/bin/sh -c <command>` in [workDir], as the app's own uid.
 * - Root mode: `su -c <command>` — only when an `su` binary exists ([RootShell.isSuAvailable]);
 *   whether root is really granted is decided by the su manager, never assumed here.
 */
object ShellRunner {
    private const val SH = "/system/bin/sh"
    /** Per-stream output cap so a runaway command cannot exhaust memory. */
    private const val MAX_STREAM_CHARS = 512 * 1024

    suspend fun run(
        command: String,
        workDir: File,
        root: Boolean = false,
        timeoutMs: Long = 60_000
    ): ShellResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ShellResult(0, "", "", false)
        if (root && !RootShell.isSuAvailable()) {
            return@withContext ShellResult(-1, "", "Root unavailable: no 'su' binary found on this device.", false)
        }
        val dir = workDir.takeIf { it.isDirectory } ?: workDir.apply { mkdirs() }
        val cmd = if (root) {
            // su does not inherit the ProcessBuilder directory reliably, so cd explicitly.
            listOf(RootShell.suPath() ?: "su", "-c", "cd ${shellQuote(dir.absolutePath)} && $command")
        } else {
            listOf(SH, "-c", command)
        }
        val process = try {
            ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(false)
                .also { pb -> pb.environment()["HOME"] = dir.absolutePath; pb.environment()["TERM"] = "dumb" }
                .start()
        } catch (e: Exception) {
            return@withContext ShellResult(-1, "", "Failed to start shell: ${e.message}", false)
        }
        try { process.outputStream.close() } catch (_: Exception) {}

        coroutineScope {
            val out = async(Dispatchers.IO) { readCapped(process.inputStream) }
            val err = async(Dispatchers.IO) { readCapped(process.errorStream) }
            val finished = try {
                runInterruptible { process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            }
            if (!finished) {
                process.destroy()
                if (!runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    process.destroyForcibly()
                }
            }
            val stdout = out.await()
            val stderr = err.await()
            ShellResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished
            )
        }
    }

    private fun readCapped(stream: InputStream): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var truncated = false
        try {
            stream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length < MAX_STREAM_CHARS) {
                        sb.append(buf, 0, minOf(n, MAX_STREAM_CHARS - sb.length))
                    } else {
                        truncated = true // keep draining so the process never blocks on a full pipe
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed because the process was killed
        }
        if (truncated) sb.append("\n[output truncated]")
        return sb.toString()
    }

    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * Root detection. Never pretends: [isSuAvailable] only says an `su` binary exists;
 * [requestRoot] actually runs `su -c id` (which makes Magisk/KernelSU show its grant prompt)
 * and reports root only when the output contains `uid=0`.
 */
object RootShell {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        "/vendor/bin/su", "/system_ext/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/bin/failsafe/su"
    )

    @Volatile private var cachedSuPath: String? = null
    @Volatile private var lookedUp = false
    @Volatile var rootGranted: Boolean = false
        private set

    fun suPath(): String? {
        if (lookedUp) return cachedSuPath
        val fromFixed = SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
        val fromPath = fromFixed ?: System.getenv("PATH").orEmpty().split(':')
            .filter { it.isNotBlank() }
            .map { File(it, "su") }
            .firstOrNull { runCatching { it.exists() }.getOrDefault(false) }?.absolutePath
        cachedSuPath = fromPath
        lookedUp = true
        return fromPath
    }

    fun isSuAvailable(): Boolean = suPath() != null

    /**
     * Asks the su manager for root. Returns true only if `id` really reported uid=0.
     * The su prompt may wait for the user, hence the generous timeout.
     */
    suspend fun requestRoot(timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        val su = suPath() ?: return@withContext false.also { rootGranted = false }
        val ok = try {
            val p = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            try { p.outputStream.close() } catch (_: Exception) {}
            val done = runInterruptible { p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            if (!done) { p.destroyForcibly(); false } else {
                val text = p.inputStream.bufferedReader().readText()
                p.exitValue() == 0 && text.contains("uid=0")
            }
        } catch (_: Exception) {
            false
        }
        rootGranted = ok
        ok
    }
}

/** Heuristics for obviously destructive / risky shell commands. */
object ShellSafety {
    private data class Rule(val regex: Regex, val reason: String)

    private val DESTRUCTIVE = listOf(
        Rule(Regex("""\brm\s+(-[a-zA-Z-]+\s+)*(/|/\*|~|~/\*?|${'$'}HOME/?|/system\S*|/vendor\S*|/boot\S*|/proc\S*|/dev\S*|/data/?\*?|/data/(data|user|app|media)/?\*?|/sdcard/?\*?|/storage(/emulated(/0)?)?/?\*?)(\s|;|&|\||$)"""),
            "deletes a system / root / storage path"),
        Rule(Regex("""\brm\s+.*--no-preserve-root"""), "rm with --no-preserve-root"),
        Rule(Regex("""\bmkfs(\.\w+)?\b|\bmke2fs\b|\bnewfs\b|\bmkswap\b"""), "formats a filesystem"),
        Rule(Regex("""\bdd\b[^|;&]*\bof=/dev/"""), "dd writing directly to a device"),
        Rule(Regex(""">\s*/dev/(block|sd[a-z]|mmcblk|nvme|loop)"""), "redirects output onto a block device"),
        Rule(Regex("""\b(reboot|shutdown|poweroff|halt)\b"""), "reboots / powers off the device"),
        Rule(Regex("""\bsvc\s+power\s+(reboot|shutdown)"""), "reboots / powers off the device"),
        Rule(Regex("""\b(flash_image|fastboot|wipe)\b"""), "flashes or wipes partitions"),
        Rule(Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*}\s*;\s*:"""), "fork bomb"),
        Rule(Regex("""\bchmod\s+(-[a-zA-Z]*R[a-zA-Z]*\s+)\S+\s+/(\s|$)"""), "recursive chmod on /"),
        Rule(Regex("""\bchown\s+(-[a-zA-Z]*R[a-zA-Z]*\s+)\S+\s+/(\s|$)"""), "recursive chown on /"),
        Rule(Regex("""\bpm\s+(uninstall|clear)\b"""), "uninstalls an app or wipes its data"),
        Rule(Regex("""\bsetenforce\b|\bmount\s+-o\s+\S*rw\S*\s+/system"""), "changes system security / mounts"),
    )

    private val RISKY = Regex(
        """\b(rm|rmdir|mv|chmod|chown|kill|killall|pkill|su|dd|truncate|shred|am\s+force-stop|settings\s+put|setprop|iptables|curl|wget|nc)\b|>\s*\S"""
    )

    /** A human-readable reason when the command is obviously destructive; null otherwise. */
    fun destructiveReason(command: String): String? {
        val c = command.trim()
        return DESTRUCTIVE.firstOrNull { it.regex.containsMatchIn(c) }?.reason
    }

    /** True for anything that modifies/deletes data, signals processes, or reaches the network. */
    fun isRisky(command: String): Boolean =
        destructiveReason(command) != null || RISKY.containsMatchIn(command)
}
