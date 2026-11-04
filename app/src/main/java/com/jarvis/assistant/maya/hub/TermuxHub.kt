package com.jarvis.assistant.maya.hub

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.termux.TermuxBridge
import com.jarvis.assistant.termux.TermuxPermissionActivity
import com.jarvis.assistant.termux.TermuxResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Maya-style Termux tooling on top of [com.jarvis.assistant.termux.TermuxBridge] (the richer
 * bridge with the RUN_COMMAND result receiver). Every job's output is persisted under
 * `filesDir/termux_jobs/<id>.txt` (+ `<id>.json` metadata) so `termux_read_more` /
 * `termux_find_in_output` can page it later, and background jobs log to
 * `~/.jarvis/jobs/<id>.log` inside Termux.
 */
class TermuxHub private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TermuxHub"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val JOBS_DIR_NAME = "termux_jobs"
        private const val MAX_STORED_CHARS = 400_000
        private const val MAX_JOB_FILES = 60

        @Volatile
        private var instance: TermuxHub? = null

        fun get(context: Context): TermuxHub =
            instance ?: synchronized(this) {
                instance ?: TermuxHub(context.applicationContext).also { instance = it }
            }

        // ── Explanatory failure strings ported from Maya ─────────────────────
        const val MSG_NOT_INSTALLED =
            "Termux is not installed on this phone (or I cannot see it). Do not use the Play Store build - it is outdated and will not work; install Termux from F-Droid or its GitHub releases."
        const val MSG_NO_PERMISSION =
            "Jarvis does not have Termux's RUN_COMMAND permission yet. I opened the permission prompt - grant it, then try again."
        const val MSG_EXTERNAL_APPS_OFF =
            "I handed the command to Termux but Termux never answered my check. This almost always means external-app access is switched off. In Termux run: mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings - then retry."
        const val MSG_SESSION_NOT_STARTED =
            "I asked Termux to run this in a new session, but several seconds later it still had NOT started. The reason is almost always the same: Termux could not bring its own window to the front (it lacks the 'Display over other apps' permission), and a session command only starts once Termux is on screen. Do not say it started. Two options: (1) ask the user to tap the Termux notification - the command starts then; (2) if it needs no answers (like a server), run it with termux_run background=true, which needs no window."
        const val MSG_PRIVATE_PATH =
            "That file is inside Termux's private folder - only Termux can open it, Jarvis cannot read it directly. Copy it to shared storage first with termux_run (e.g. cp <file> /sdcard/Jarvis/), then use the new path."

        fun msgTimeout(seconds: Int, jobId: String, processSeen: Boolean?): String = when (processSeen) {
            false -> "No result came back within ${seconds}s, and I checked: no such process is running in Termux any more. So it either never started or was killed midway (Android sometimes kills Termux processes). Run it once more; if it happens again ask the user to open Termux and tap 'Acquire wakelock'. (job $jobId - termux_read_more(\"$jobId\") shows any output that arrived late.)"
            true -> "No result within ${seconds}s but the process is still running in Termux. It is a long job, not a failure - do not say 'done' yet. Check back with termux_read_more(\"$jobId\") or run it with background=true next time."
            else -> "No result within ${seconds}s, and Termux did not answer my follow-up check either - so I genuinely do not know whether it is running, stuck or never ran. Do not guess and do not say 'done'. Run termux_status, and ask the user to open Termux and look. (job $jobId - termux_read_more(\"$jobId\") shows late output.)"
        }
    }

    data class JobMeta(
        val id: String,
        val command: String,
        val background: Boolean,
        val startedAt: Long,
        var finishedAt: Long = 0L,
        var exitCode: Int? = null,
        var pid: Int? = null,
        var remoteLog: String? = null,
        /** running | done | timeout | failed | started */
        var state: String = "running",
        var kind: String = "run"
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("command", command); put("background", background)
            put("started_at", startedAt); put("finished_at", finishedAt)
            put("exit_code", exitCode ?: JSONObject.NULL); put("pid", pid ?: JSONObject.NULL)
            put("remote_log", remoteLog ?: JSONObject.NULL); put("state", state); put("kind", kind)
        }

        companion object {
            fun fromJson(o: JSONObject): JobMeta = JobMeta(
                id = o.optString("id"),
                command = o.optString("command"),
                background = o.optBoolean("background"),
                startedAt = o.optLong("started_at"),
                finishedAt = o.optLong("finished_at"),
                exitCode = if (o.isNull("exit_code")) null else o.optInt("exit_code"),
                pid = if (o.isNull("pid")) null else o.optInt("pid"),
                remoteLog = if (o.isNull("remote_log")) null else o.optString("remote_log"),
                state = o.optString("state", "done"),
                kind = o.optString("kind", "run")
            )
        }
    }

    /** Result of a tool-level operation; [json] is merged into the tool result. */
    data class HubResult(val success: Boolean, val message: String, val json: JSONObject = JSONObject())

    val bridge = TermuxBridge(context)
    private val prefs = HubPrefs.get(context)
    private val jobsDir: File = File(context.filesDir, JOBS_DIR_NAME).also { it.mkdirs() }

    private val _jobsVersion = MutableStateFlow(0L)
    /** Bumps whenever a job starts/finishes; UI observes to refresh. */
    val jobsVersion: StateFlow<Long> = _jobsVersion

    // ── Status ───────────────────────────────────────────────────────────────

    fun isInstalled(): Boolean = bridge.isTermuxInstalled()
    fun hasPermission(): Boolean = bridge.isRunCommandPermissionGranted()

    fun requestPermission() {
        try {
            context.startActivity(Intent(context, TermuxPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Termux permission prompt: ${e.message}")
        }
    }

    suspend fun status(): HubResult = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("installed", isInstalled())
        if (!isInstalled()) return@withContext HubResult(false, MSG_NOT_INSTALLED, json)
        json.put("permission_granted", hasPermission())
        if (!hasPermission()) {
            requestPermission()
            return@withContext HubResult(false, MSG_NO_PERMISSION, json)
        }
        val res = bridge.executeBashCommand(TermuxRecipes.STATUS_PROBE, TERMUX_HOME, 10_000L)
        if (res == null) {
            json.put("external_apps", false)
            return@withContext HubResult(false, MSG_EXTERNAL_APPS_OFF, json)
        }
        json.put("external_apps", true)
        val have = ArrayList<String>()
        val missing = ArrayList<String>()
        res.stdout.lines().forEach { line ->
            when {
                line.startsWith("have ") -> have.add(line.removePrefix("have ").trim())
                line.startsWith("no ") -> missing.add(line.removePrefix("no ").trim())
                line.contains('%') -> json.put("disk", line.trim())
            }
        }
        json.put("have", org.json.JSONArray(have))
        json.put("missing", org.json.JSONArray(missing))
        json.put("recipes", org.json.JSONArray(TermuxRecipes.names()))
        val msg = buildString {
            append("Termux OK: installed, permission granted, external apps allowed. ")
            append("Present: ${if (have.isEmpty()) "nothing yet" else have.joinToString(", ")}. ")
            if (missing.isNotEmpty()) append("Missing: ${missing.joinToString(", ")}. ")
            json.optString("disk").takeIf { it.isNotBlank() }?.let { append("Disk: $it. ") }
            append("Recipes: ${TermuxRecipes.names().joinToString(", ")}.")
        }
        HubResult(true, msg, json)
    }

    private fun preflight(): HubResult? {
        if (!isInstalled()) return HubResult(false, MSG_NOT_INSTALLED)
        if (!hasPermission()) {
            requestPermission()
            return HubResult(false, MSG_NO_PERMISSION)
        }
        return null
    }

    // ── Run ──────────────────────────────────────────────────────────────────

    /**
     * Runs [command]. Foreground: captured through the result receiver with [timeoutSec];
     * background: detached with nohup, returns PID + port check + first log lines.
     * Servers are auto-detected and backgrounded like Maya did.
     */
    suspend fun runCommand(
        command: String,
        background: Boolean = false,
        timeoutSec: Int = prefs.termuxDefaultTimeoutSec,
        workdir: String? = null,
        kind: String = "run"
    ): HubResult = withContext(Dispatchers.IO) {
        preflight()?.let { return@withContext it }
        val cmd = command.trim()
        if (cmd.isEmpty()) return@withContext HubResult(false, "Empty command.")
        val wd = workdir?.takeIf { it.isNotBlank() }?.let { TermuxOutputPager.resolveTermuxPath(it) } ?: TERMUX_HOME
        val goBackground = background || TermuxOutputPager.looksLikeServer(cmd)
        if (goBackground) runBackground(TermuxOutputPager.stripTrailingAmpersand(cmd), wd, kind)
        else runForeground(cmd, wd, timeoutSec.coerceIn(3, 900), kind)
    }

    private suspend fun runForeground(cmd: String, wd: String, timeoutSec: Int, kind: String): HubResult {
        val id = TermuxOutputPager.newJobId()
        val meta = JobMeta(id, cmd, background = false, startedAt = System.currentTimeMillis(), kind = kind)
        saveMeta(meta)
        prefs.lastTermuxJobId = id
        bump()
        val wrapped = "cd ${TermuxOutputPager.shellQuote(wd)} 2>/dev/null; $cmd"
        val res = bridge.executeBashCommandWithLateResult(wrapped, wd, timeoutSec * 1000L) { late ->
            val m = loadMeta(id) ?: meta
            m.exitCode = late.exitCode; m.finishedAt = System.currentTimeMillis(); m.state = "done"
            saveMeta(m)
            saveOutput(id, combine(late))
            bump()
            notifyLive("Termux job $id finished late (exit ${late.exitCode}): ${combine(late).takeLast(400)}")
        }
        if (res == null) {
            meta.state = "timeout"
            saveMeta(meta)
            bump()
            // Second check: is anything with this command still alive?
            val probe = bridge.executeBashCommand(
                "pgrep -f ${TermuxOutputPager.shellQuote(cmd.take(60))} >/dev/null 2>&1 && echo ALIVE || echo DEAD",
                TERMUX_HOME, 6_000L
            )
            val seen: Boolean? = when {
                probe == null -> null
                probe.stdout.contains("ALIVE") -> true
                else -> false
            }
            return HubResult(false, msgTimeout(timeoutSec, id, seen), JSONObject().apply {
                put("job_id", id); put("state", "timeout"); put("process_seen", seen ?: JSONObject.NULL)
            })
        }
        meta.exitCode = res.exitCode; meta.finishedAt = System.currentTimeMillis(); meta.state = "done"
        saveMeta(meta)
        val full = combine(res)
        saveOutput(id, full)
        bump()
        val page = TermuxOutputPager.lastPage(id, full)
        val ok = res.exitCode == 0
        val msg = buildString {
            append(if (ok) "Exit 0." else "Exit ${res.exitCode}.")
            if (res.exitCode == -1 && res.stderr.isNotBlank() && res.stdout.isBlank()) append(" Termux reported: ${res.stderr.take(300)}")
            append('\n')
            append(page.text.ifBlank { "(no output)" })
            if (page.hasMore) append("\n[... ${page.from} earlier chars - termux_read_more(\"${page.nextToken}\")]")
        }
        return HubResult(ok, msg, JSONObject().apply {
            put("job_id", id); put("exit_code", res.exitCode); put("total_chars", page.totalChars)
            put("truncated", page.truncated); put("next_token", page.nextToken ?: JSONObject.NULL)
        })
    }

    private suspend fun runBackground(cmd: String, wd: String, kind: String): HubResult {
        val id = TermuxOutputPager.newJobId()
        val remoteLog = "${TermuxRecipes.JARVIS_JOBS_DIR}/$id.log"
        val meta = JobMeta(id, cmd, background = true, startedAt = System.currentTimeMillis(), remoteLog = remoteLog, kind = kind)
        saveMeta(meta)
        prefs.lastTermuxJobId = id
        bump()
        val ports = TermuxOutputPager.guessPorts(cmd)
        val portChecks = ports.joinToString("\n") { p ->
            "(exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null && echo \"PORT_OPEN=$p\" || echo \"PORT_CLOSED=$p\""
        }
        val q = TermuxOutputPager.shellQuote(cmd)
        val script = """
            mkdir -p ${TermuxRecipes.JARVIS_JOBS_DIR}
            cd ${TermuxOutputPager.shellQuote(wd)} 2>/dev/null
            nohup bash -c $q > $remoteLog 2>&1 &
            JPID=${'$'}!
            echo "PID=${'$'}JPID"
            echo ${'$'}JPID > ${TermuxRecipes.JARVIS_JOBS_DIR}/$id.pid
            sleep 2
            if kill -0 ${'$'}JPID 2>/dev/null; then echo "ALIVE=1"; else echo "ALIVE=0"; wait ${'$'}JPID 2>/dev/null; echo "EXIT=${'$'}?"; fi
            $portChecks
            echo "--- log ---"
            tail -n 40 $remoteLog 2>/dev/null
        """.trimIndent()
        val res = bridge.executeBashCommand(script, wd, 15_000L)
        if (res == null) {
            meta.state = "timeout"; saveMeta(meta); bump()
            return HubResult(false, MSG_EXTERNAL_APPS_OFF, JSONObject().put("job_id", id))
        }
        val out = combine(res)
        val pid = Regex("""PID=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val alive = out.contains("ALIVE=1")
        val exit = Regex("""EXIT=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        meta.pid = pid
        meta.state = if (alive) "running" else "done"
        meta.exitCode = if (alive) null else exit
        saveMeta(meta)
        saveOutput(id, out)
        bump()
        val openPorts = Regex("""PORT_OPEN=(\d+)""").findAll(out).map { it.groupValues[1] }.toList()
        val closedPorts = Regex("""PORT_CLOSED=(\d+)""").findAll(out).map { it.groupValues[1] }.toList()
        val log = out.substringAfter("--- log ---", "").trim()
        val msg = buildString {
            if (alive) append("Started in background (job $id, PID ${pid ?: "?"}). ")
            else append("Started but the process already exited (job $id, exit ${exit ?: "?"}) - probably a crash; see the log. ")
            if (openPorts.isNotEmpty()) append("Port ${openPorts.joinToString(", ")} is open. ")
            if (closedPorts.isNotEmpty()) append("Port ${closedPorts.joinToString(", ")} not open yet. ")
            append("Log: $remoteLog - termux_read_more(\"$id\") refreshes it.")
            if (log.isNotBlank()) append("\n--- log ---\n").append(log.takeLast(2000))
        }
        return HubResult(alive || exit == 0, msg, JSONObject().apply {
            put("job_id", id); put("pid", pid ?: JSONObject.NULL); put("alive", alive)
            put("open_ports", org.json.JSONArray(openPorts)); put("log", remoteLog)
        })
    }

    // ── Paging / search ──────────────────────────────────────────────────────

    /** Pages further back in a job's output; background jobs refresh from Termux first. */
    suspend fun readMore(token: String): HubResult = withContext(Dispatchers.IO) {
        val raw = token.trim().ifEmpty { prefs.lastTermuxJobId }
        val parsed = TermuxOutputPager.parseToken(raw) ?: return@withContext HubResult(false, "Bad token '$token'.")
        val (id, off) = parsed
        val meta = loadMeta(id) ?: return@withContext HubResult(false, "No job '$id' - it may have been cleaned up.")
        if (meta.background && off == null) refreshBackgroundJob(meta)
        val full = loadOutput(id) ?: ""
        val page = TermuxOutputPager.pageForToken(raw, full) ?: return@withContext HubResult(false, "Bad token '$token'.")
        val msg = buildString {
            append("Job $id (${meta.state}${meta.exitCode?.let { ", exit $it" } ?: ""}) chars ${page.from}-${page.to} of ${page.totalChars}:\n")
            append(page.text.ifBlank { "(no output yet)" })
            if (page.hasMore) append("\n[... earlier - termux_read_more(\"${page.nextToken}\")]")
        }
        HubResult(true, msg, JSONObject().apply {
            put("job_id", id); put("state", meta.state); put("from", page.from); put("to", page.to)
            put("total_chars", page.totalChars); put("next_token", page.nextToken ?: JSONObject.NULL)
        })
    }

    suspend fun findInOutput(token: String, needle: String): HubResult = withContext(Dispatchers.IO) {
        val raw = token.trim().ifEmpty { prefs.lastTermuxJobId }
        val (id, _) = TermuxOutputPager.parseToken(raw) ?: return@withContext HubResult(false, "Bad token '$token'.")
        val meta = loadMeta(id) ?: return@withContext HubResult(false, "No job '$id'.")
        if (meta.background) refreshBackgroundJob(meta)
        val full = loadOutput(id) ?: ""
        val matches = TermuxOutputPager.find(full, needle)
        HubResult(matches.isNotEmpty(), TermuxOutputPager.formatMatches(matches, needle), JSONObject().apply {
            put("job_id", id); put("matches", matches.size)
        })
    }

    private suspend fun refreshBackgroundJob(meta: JobMeta) {
        val log = meta.remoteLog ?: return
        val pid = meta.pid
        val script = buildString {
            if (pid != null) append("kill -0 $pid 2>/dev/null && echo '__ALIVE__' || echo '__DEAD__'\n")
            append("tail -c 200000 $log 2>/dev/null")
        }
        val res = bridge.executeBashCommand(script, TERMUX_HOME, 10_000L) ?: return
        val out = res.stdout
        val alive = out.startsWith("__ALIVE__")
        val body = out.removePrefix("__ALIVE__").removePrefix("__DEAD__").trimStart('\n')
        if (pid != null) meta.state = if (alive) "running" else "done"
        if (!alive && pid != null && meta.finishedAt == 0L) meta.finishedAt = System.currentTimeMillis()
        saveMeta(meta)
        saveOutput(meta.id, body)
        bump()
    }

    // ── Sessions / packages / recipes ────────────────────────────────────────

    /** Opens Termux on screen with [command] in a visible session; verifies it began. */
    suspend fun openSession(command: String): HubResult = withContext(Dispatchers.IO) {
        preflight()?.let { return@withContext it }
        val cmd = command.trim().ifEmpty { "bash" }
        val id = TermuxOutputPager.newJobId()
        val marker = "${TermuxRecipes.JARVIS_JOBS_DIR}/$id.started"
        val wrapped = "mkdir -p ${TermuxRecipes.JARVIS_JOBS_DIR}; touch $marker; $cmd"
        val meta = JobMeta(id, cmd, background = false, startedAt = System.currentTimeMillis(), kind = "session", state = "started")
        saveMeta(meta); saveOutput(id, "(visible session - output is on the Termux screen, not readable here)"); bump()
        if (!bridge.openSession(wrapped, TERMUX_HOME)) {
            meta.state = "failed"; saveMeta(meta)
            return@withContext HubResult(false, "Could not hand the session to Termux (intent failed). $MSG_NOT_INSTALLED")
        }
        delay(4_000L)
        val check = bridge.executeBashCommand("[ -e $marker ] && echo STARTED || echo NOT_STARTED", TERMUX_HOME, 6_000L)
        val checkOut = check?.stdout.orEmpty()
        val started = checkOut.contains("STARTED") && !checkOut.contains("NOT_STARTED")
        if (started) {
            HubResult(true, "Session started in Termux and is on screen: `$cmd`. There is no output to read back here - the user interacts with it in Termux.", JSONObject().put("job_id", id).put("started", true))
        } else {
            meta.state = "not_started"; saveMeta(meta); bump()
            HubResult(false, "`$cmd` - $MSG_SESSION_NOT_STARTED", JSONObject().put("job_id", id).put("started", false))
        }
    }

    suspend fun installPackage(name: String): HubResult {
        val pkg = name.trim().split(Regex("""[\s,]+""")).filter { it.matches(Regex("""[A-Za-z0-9._+-]+""")) }
        if (pkg.isEmpty()) return HubResult(false, "Package name missing or invalid.")
        return runCommand("pkg install -y ${pkg.joinToString(" ")} 2>&1 | tail -n 60", background = false, timeoutSec = 600, kind = "install")
    }

    fun listRecipes(): HubResult {
        val arr = org.json.JSONArray()
        val sb = StringBuilder("Termux recipes:\n")
        TermuxRecipes.all.values.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name); put("title", r.title); put("description", r.description)
                put("estimated_time", r.estimatedTime); put("interactive", r.interactive)
            })
            sb.append("- ${r.name}: ${r.title} - ${r.description} (~${r.estimatedTime})\n")
        }
        return HubResult(true, sb.toString().trimEnd(), JSONObject().put("recipes", arr))
    }

    /** Writes the recipe script into Termux and starts it as a logged background job. */
    suspend fun setupRecipe(name: String): HubResult = withContext(Dispatchers.IO) {
        val recipe = TermuxRecipes.get(name)
            ?: return@withContext HubResult(false, "Unknown recipe '$name'. Known: ${TermuxRecipes.names().joinToString(", ")}.")
        preflight()?.let { return@withContext it }
        val path = TermuxRecipes.scriptPath(recipe)
        val write = writeFile(path, recipe.script, overwrite = true)
        if (!write.success) return@withContext write
        if (recipe.interactive) {
            val r = openSession("bash $path")
            return@withContext r.copy(message = r.message + "\nEstimated time: ${recipe.estimatedTime}. ${recipe.afterNote}")
        }
        val r = runCommand("bash $path", background = true, kind = "recipe:${recipe.name}")
        r.copy(
            message = "Recipe '${recipe.name}' (${recipe.title}) started - estimated ${recipe.estimatedTime}. Do not say it is done yet; follow progress with termux_read_more(\"${r.json.optString("job_id")}\"). When finished: ${recipe.afterNote}\n" + r.message,
            json = r.json.put("recipe", recipe.name).put("estimated_time", recipe.estimatedTime).put("verify_command", recipe.verifyCommand)
        )
    }

    // ── Files ────────────────────────────────────────────────────────────────

    suspend fun readFile(path: String): HubResult = withContext(Dispatchers.IO) {
        preflight()?.let { return@withContext it }
        val p = TermuxOutputPager.resolveTermuxPath(path)
        val q = TermuxOutputPager.shellQuote(p)
        val r = runCommand("if [ -f $q ]; then cat -- $q; else echo \"__NOFILE__ $p\"; exit 2; fi", background = false, timeoutSec = 30, kind = "read")
        if (r.message.contains("__NOFILE__")) HubResult(false, "No such file in Termux: $p", r.json) else r
    }

    /** Replaces the whole file. Refuses to overwrite an existing file unless [overwrite]. */
    suspend fun writeFile(path: String, content: String, overwrite: Boolean): HubResult = withContext(Dispatchers.IO) {
        preflight()?.let { return@withContext it }
        val p = TermuxOutputPager.resolveTermuxPath(path)
        val q = TermuxOutputPager.shellQuote(p)
        if (!overwrite) {
            val exists = bridge.executeBashCommand("[ -e $q ] && echo EXISTS || echo FREE", TERMUX_HOME, 8_000L)
            if (exists == null) return@withContext HubResult(false, MSG_EXTERNAL_APPS_OFF)
            if (exists.stdout.contains("EXISTS")) {
                return@withContext HubResult(false, "'$p' already exists in Termux. Writing REPLACES the whole file and the old content cannot be recovered. Tell the user the full path and ask; only if they say yes call termux_write_file again with the same path and overwrite=true.", JSONObject().put("exists", true))
            }
        }
        val delim = "JARVIS_EOF_" + System.currentTimeMillis().toString(36)
        val script = "mkdir -p \"$(dirname $q)\" && cat > $q <<'$delim'\n$content\n$delim\necho \"WROTE $(wc -c < $q) bytes to $p\""
        val res = bridge.executeBashCommand(script, TERMUX_HOME, 20_000L)
            ?: return@withContext HubResult(false, MSG_EXTERNAL_APPS_OFF)
        if (res.exitCode == 0) HubResult(true, res.stdout.trim().ifBlank { "Wrote $p" }, JSONObject().put("path", p))
        else HubResult(false, "Write failed (exit ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }.take(400)}")
    }

    suspend fun runInUbuntu(command: String, timeoutSec: Int = 120): HubResult = withContext(Dispatchers.IO) {
        preflight()?.let { return@withContext it }
        val inner = TermuxOutputPager.shellQuote(command.trim())
        val cmd = "if [ ! -d \$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu ]; then echo '__NO_UBUNTU__'; exit 3; fi; proot-distro login ubuntu -- bash -lc $inner"
        val r = runCommand(cmd, background = false, timeoutSec = timeoutSec, kind = "ubuntu")
        if (r.message.contains("__NO_UBUNTU__")) HubResult(false, "Ubuntu is not installed in Termux yet - run termux_setup_recipe(\"ubuntu\") first (15-30 min).", r.json) else r
    }

    // ── Job store ────────────────────────────────────────────────────────────

    fun listJobs(limit: Int = 20): List<JobMeta> =
        (jobsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { JobMeta.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
            .take(limit)

    fun loadMeta(id: String): JobMeta? = runCatching {
        val f = File(jobsDir, "$id.json")
        if (f.exists()) JobMeta.fromJson(JSONObject(f.readText())) else null
    }.getOrNull()

    fun loadOutput(id: String): String? = runCatching {
        val f = File(jobsDir, "$id.txt")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun saveMeta(meta: JobMeta) {
        runCatching { File(jobsDir, "${meta.id}.json").writeText(meta.toJson().toString()) }
        pruneOld()
    }

    private fun saveOutput(id: String, text: String) {
        val t = if (text.length > MAX_STORED_CHARS) text.takeLast(MAX_STORED_CHARS) else text
        runCatching { File(jobsDir, "$id.txt").writeText(t) }
    }

    private fun pruneOld() {
        val metas = jobsDir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (metas.size <= MAX_JOB_FILES) return
        metas.sortedBy { it.lastModified() }.take(metas.size - MAX_JOB_FILES).forEach { f ->
            f.delete(); File(jobsDir, f.name.removeSuffix(".json") + ".txt").delete()
        }
    }

    private fun combine(r: TermuxResultReceiver.TermuxResult): String =
        if (r.stderr.isBlank()) r.stdout else if (r.stdout.isBlank()) r.stderr else r.stdout + "\n[stderr]\n" + r.stderr

    private fun bump() {
        _jobsVersion.value = System.currentTimeMillis()
    }

    private fun notifyLive(text: String) {
        try {
            if (JarvisConversationService.isSessionOn.value) JarvisConversationService.sendTextMessage("[SYSTEM EVENT] $text")
        } catch (e: Exception) {
            Log.w(TAG, "notifyLive failed: ${e.message}")
        }
    }
}
