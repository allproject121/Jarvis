package com.jarvis.assistant.maya.hub

import android.content.Context
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry
import com.jarvis.assistant.automation.dispatcher.MayaToolRegistry.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * WP8 HUB tools: Termux recipes & tools, PC <-> Phone hub, Maya Home (ESP32) control.
 * family = "hub". Install via `MayaToolPacks` entry `"hub" to HubToolPack::install`.
 *
 * Deliberately NOT duplicated: control_smart_home / get_smart_home_devices (Home Assistant, used
 * here only as fallback), run_shell_command / generate_project / start_preview (Code Studio),
 * github_clone_repo / github_agent_edit.
 */
object HubToolPack {

    private const val FAMILY = "hub"

    private fun p(type: String, desc: String, enum: List<String>? = null) = MayaToolRegistry.prop(type, desc, enum)

    private fun outcome(success: Boolean, message: String, json: JSONObject = JSONObject()) =
        if (success) ToolOutcome.ok(message, json) else ToolOutcome.fail(message, json)

    private fun JSONObject.str(key: String): String = if (has(key) && !isNull(key)) optString(key, "").trim() else ""
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    private fun JSONObject.int(key: String): Int? = dbl(key)?.toInt()
    private fun JSONObject.bool(key: String, def: Boolean = false): Boolean = if (has(key) && !isNull(key)) {
        when (val v = opt(key)) {
            is Boolean -> v
            is String -> v.equals("true", true) || v == "1" || v.equals("yes", true)
            is Number -> v.toInt() != 0
            else -> def
        }
    } else def

    fun install(context: Context) {
        installTermux()
        installPcHub()
        installMayaHome()
        // Reconnect a previously linked PC in the background.
        try { PcHubClient.get(context).autoConnectIfLinked() } catch (ignored: Exception) { }
    }

    // ── Termux ───────────────────────────────────────────────────────────────

    private fun installTermux() {
        MayaToolRegistry.register(
            name = "termux_status",
            description = "Check whether Jarvis can use Termux at all: installed, RUN_COMMAND permission granted, external-app access on, and which tools (python, node, git, hermes, openclaw, ubuntu, ...) are present. Run this FIRST whenever a Termux command fails, and when the user asks what is installed.",
            family = FAMILY
        ) { ctx, _ ->
            val r = TermuxHub.get(ctx).status()
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_run",
            description = "Run a shell command in Termux on this phone and return its output and exit code. Use for anything that finishes on its own: pkg install, git, python scripts, ls, cat, curl, builds. Servers/watchers (python -m http.server, npm run dev, tail -f, anything ending in &) are recognised and started in the background - you get PID, a port check and the log at once; for others pass background=true. NOT for programs that ask questions (use termux_open_session). Long output comes back tail-first; use termux_read_more with the returned token to go further back.",
            properties = MayaToolRegistry.props(
                "command" to p("string", "Bash command to run inside Termux"),
                "background" to p("boolean", "true = start detached (servers, long jobs); default false"),
                "timeout_sec" to p("integer", "Foreground timeout in seconds (default 60, max 900)"),
                "workdir" to p("string", "Working directory (~ / ~/project / absolute); default Termux home")
            ),
            required = listOf("command"),
            family = FAMILY,
            risky = true
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).runCommand(
                command = a.str("command"),
                background = a.bool("background"),
                timeoutSec = a.int("timeout_sec") ?: 60,
                workdir = a.str("workdir").ifBlank { null }
            )
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_read_more",
            description = "Page further back through a Termux job's output (or refresh a background job's log). Pass the next_token from termux_run / a previous termux_read_more, or just the job id for its latest output.",
            properties = MayaToolRegistry.props("token" to p("string", "Paging token like job_xxx#1234, or a job id; empty = last job")),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).readMore(a.str("token"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_find_in_output",
            description = "Search a Termux job's full output for a word or regex (case-insensitive) and return matching lines with context - faster than paging through long build logs.",
            properties = MayaToolRegistry.props(
                "token" to p("string", "Job id or paging token; empty = last job"),
                "needle" to p("string", "Text or regex to look for, e.g. 'error' or 'listening on'")
            ),
            required = listOf("needle"),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).findInOutput(a.str("token"), a.str("needle"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_open_session",
            description = "Open Termux on screen with a command running in a session the user can see and type into. Use for anything interactive: hermes, openclaw onboard, a setup script that asks questions, a wizard. There is NO output to read back, and the command only starts once Termux is really on screen - the result says whether it began; say that, not a result you did not see.",
            properties = MayaToolRegistry.props("command" to p("string", "Command to start in the visible session (empty = plain shell)")),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).openSession(a.str("command"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_install_package",
            description = "Install one or more Termux packages with pkg (e.g. 'python', 'nodejs git'). Returns the tail of the install log.",
            properties = MayaToolRegistry.props("name" to p("string", "Package name(s), space or comma separated")),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).installPackage(a.str("name"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_list_recipes",
            description = "List the known Termux setup recipes (name, what it installs, estimated time).",
            family = FAMILY
        ) { ctx, _ ->
            val r = TermuxHub.get(ctx).listRecipes()
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_setup_recipe",
            description = "Install a known thing in Termux by name, as a logged background job: 'jarvis-setup' (python, git, nodejs, openssh, termux-api), 'hermes' (Nous Research Hermes Agent), 'openclaw' (self-hosted AI agent), 'ubuntu' (proot-distro Ubuntu). Returns at once with a job id and estimated time - it is NOT done yet; follow with termux_read_more.",
            properties = MayaToolRegistry.props("name" to p("string", "Recipe name", TermuxRecipes.names())),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).setupRecipe(a.str("name"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_read_file",
            description = "Read a text file inside Termux (script, config, log). Long files come back end-first like command output; use termux_read_more for the rest. A bare name is looked for in Jarvis' scratch folder (~/.jarvis); paths starting with ~ work.",
            properties = MayaToolRegistry.props("path" to p("string", "File path inside Termux")),
            required = listOf("path"),
            family = FAMILY
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).readFile(a.str("path"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_write_file",
            description = "Write text to a file inside Termux (script, config, note); creates parent folders. REPLACES the whole file. If the file already exists the call refuses: tell the user the full path, ask, and only if they agree call again with overwrite=true.",
            properties = MayaToolRegistry.props(
                "path" to p("string", "File path inside Termux (~/x.sh, bare name = ~/.jarvis/name)"),
                "content" to p("string", "Full file content"),
                "overwrite" to p("boolean", "true only after the user confirmed replacing an existing file")
            ),
            required = listOf("path", "content"),
            family = FAMILY,
            risky = true
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).writeFile(a.str("path"), a.optString("content", ""), a.bool("overwrite") || a.bool("confirmed"))
            outcome(r.success, r.message, r.json)
        }

        MayaToolRegistry.register(
            name = "termux_run_in_ubuntu",
            description = "Run a shell command inside the Ubuntu that Termux hosts through proot-distro. Use when the user says Ubuntu, or when the command needs apt (Termux itself uses pkg, not apt).",
            properties = MayaToolRegistry.props(
                "command" to p("string", "Command to run inside Ubuntu"),
                "timeout_sec" to p("integer", "Timeout in seconds (default 120)")
            ),
            required = listOf("command"),
            family = FAMILY,
            risky = true
        ) { ctx, a ->
            val r = TermuxHub.get(ctx).runInUbuntu(a.str("command"), a.int("timeout_sec") ?: 120)
            outcome(r.success, r.message, r.json)
        }
    }

    // ── PC <-> Phone hub ─────────────────────────────────────────────────────

    private fun installPcHub() {
        MayaToolRegistry.register(
            name = "pc_hub_status",
            description = "Is the user's PC linked to Jarvis through the PC <-> Phone hub? Shows host, pairing, connection and whether a PC call is open.",
            family = FAMILY
        ) { ctx, _ ->
            val c = PcHubClient.get(ctx)
            c.autoConnectIfLinked()
            val prefs = HubPrefs.get(ctx)
            val json = JSONObject().apply {
                put("state", c.state.value.name.lowercase()); put("linked", c.isLinked())
                put("host", prefs.pcHost); put("port", prefs.pcPort); put("pc_name", prefs.pcName)
                put("call_active", c.callActive.value); put("last_memory_sync", prefs.lastMemorySyncAt)
            }
            val ping = if (c.isPaired()) c.ping().getOrNull() else null
            ToolOutcome.ok(c.describe() + (ping?.let { " - ping ${it}ms" } ?: "") +
                if (!c.isLinked()) ". Link it with link_pc(host, port, code): on the PC run `node companion/server.js`; it prints the LAN address and a 6-digit join code." else "", json)
        }

        MayaToolRegistry.register(
            name = "link_pc",
            description = "Link this phone to the user's PC hub. The PC runs `node companion/server.js`, which prints its LAN address (port 8766, or 8765 over USB with adb reverse) and a 6-digit join code. Ask the user for the code shown on the PC.",
            properties = MayaToolRegistry.props(
                "host" to p("string", "PC IP or host (e.g. 192.168.1.50 or 192.168.1.50:8766); 127.0.0.1 for USB/adb reverse"),
                "port" to p("integer", "Hub port (LAN default 8766, USB 8765)"),
                "code" to p("string", "6-digit join code shown on the PC console")
            ),
            required = listOf("host", "code"),
            family = FAMILY
        ) { ctx, a ->
            val parsed = PcPairing.parseHostPort(a.str("host"), a.int("port") ?: 8766)
                ?: return@register ToolOutcome.fail("Invalid host '${a.str("host")}'.")
            val code = PcPairing.normalize(a.str("code"))
                ?: return@register ToolOutcome.fail("The join code must be the 6 digits shown on the PC console.")
            val prefs = HubPrefs.get(ctx)
            val c = PcHubClient.get(ctx)
            c.disconnect("relinking")
            prefs.pcHost = parsed.first
            prefs.pcPort = a.int("port") ?: parsed.second
            prefs.relayToken = ""
            prefs.pcLinked = true
            c.connect(code)
            if (c.awaitPaired()) ToolOutcome.ok("Linked with ${prefs.pcName.ifBlank { "PC" }} at ${prefs.pcHost}:${prefs.pcPort}. Memory can now sync both ways (sync_memory_with_pc).")
            else {
                val err = c.lastError.value
                prefs.pcLinked = false
                c.disconnect("pair failed")
                ToolOutcome.fail("Could not pair with ${prefs.pcHost}:${prefs.pcPort}: ${err.ifBlank { "no answer" }}. Check the PC is on the same Wi-Fi, the companion is running, the firewall allows the port, and the code is the current one.")
            }
        }

        MayaToolRegistry.register(
            name = "unlink_pc",
            description = "Forget the linked PC (disconnects and deletes the pairing token).",
            family = FAMILY,
            risky = true
        ) { ctx, _ ->
            PcHubClient.get(ctx).disconnect("unlinked")
            HubPrefs.get(ctx).clearPcLink()
            ToolOutcome.ok("PC unlinked.")
        }

        MayaToolRegistry.register(
            name = "ask_my_pc",
            description = "Send a question or task to the PC-side agent and return its text reply ('PC se poochho', 'ask my computer'). The PC answers with its own local LLM.",
            properties = MayaToolRegistry.props("question" to p("string", "What to ask the PC agent")),
            required = listOf("question"),
            family = FAMILY
        ) { ctx, a ->
            val r = PcHubClient.get(ctx).ask(a.str("question"))
            r.fold({ ToolOutcome.ok("[PC] $it", JSONObject().put("reply", it)) }, { ToolOutcome.fail("PC did not answer: ${it.message}") })
        }

        MayaToolRegistry.register(
            name = "control_my_pc",
            description = "Directly control the user's PC from the phone: 'PC lock karo', 'computer so jao', 'PC ka status'. command is one of: status | lock | pc_sleep | shutdown | restart | cancel_shutdown | mute | unmute | open_url <url> (raw 'shell <cmd>' only if the PC allows it).",
            properties = MayaToolRegistry.props("command" to p("string", "PC action, e.g. 'lock' or 'open_url https://...'")),
            required = listOf("command"),
            family = FAMILY,
            risky = true
        ) { ctx, a ->
            val r = PcHubClient.get(ctx).control(a.str("command"))
            r.fold({ p -> outcome(p.optBoolean("ok", true), p.optString("text", "Done."), p) }, { ToolOutcome.fail("PC control failed: ${it.message}") })
        }

        MayaToolRegistry.register(
            name = "call_my_pc",
            description = "Open a relay 'call' with the PC agent: until end_pc_call, forward EVERY user turn with pc_call_say(text). The PC's replies arrive as '[PC] ...' lines - read them out. Text/voice-note relay, not real-time audio.",
            family = FAMILY
        ) { ctx, _ ->
            val r = PcHubClient.get(ctx).startCall()
            r.fold({ ToolOutcome.ok("$it Forward each thing the user says with pc_call_say until they end the call (end_pc_call).", JSONObject().put("call_active", true)) },
                { ToolOutcome.fail("Could not start the PC call: ${it.message}") })
        }

        MayaToolRegistry.register(
            name = "pc_call_say",
            description = "During an open PC call (call_my_pc), forward what the user just said to the PC agent. Returns the PC's reply (also injected as '[PC] ...' when live).",
            properties = MayaToolRegistry.props("text" to p("string", "The user's words to forward")),
            required = listOf("text"),
            family = FAMILY
        ) { ctx, a ->
            val c = PcHubClient.get(ctx)
            val r = c.sendCallChunk(a.str("text"))
            val buffered = c.drainBufferedReplies()
            r.fold({ reply ->
                val all = (buffered + listOf("[PC] $reply")).joinToString("\n")
                ToolOutcome.ok(all, JSONObject().put("reply", reply))
            }, { ToolOutcome.fail("PC call relay failed: ${it.message}") })
        }

        MayaToolRegistry.register(
            name = "end_pc_call",
            description = "End the PC call relay started with call_my_pc.",
            family = FAMILY
        ) { ctx, _ ->
            val c = PcHubClient.get(ctx)
            val r = c.endCall()
            val buffered = c.drainBufferedReplies()
            ToolOutcome.ok((buffered + listOf(r.getOrDefault("PC call ended."))).joinToString("\n"))
        }

        MayaToolRegistry.register(
            name = "sync_memory_with_pc",
            description = "Sync Jarvis memories with the linked PC. push = send phone memories to the PC, pull = import the PC's memories, both = pull then push. Memory syncs both ways - what the PC remembers, this phone remembers too.",
            properties = MayaToolRegistry.props("direction" to p("string", "push | pull | both (default both)", listOf("push", "pull", "both"))),
            family = FAMILY
        ) { ctx, a ->
            val c = PcHubClient.get(ctx)
            val dir = a.str("direction").lowercase().ifBlank { "both" }
            val parts = ArrayList<String>()
            var ok = true
            if (dir == "pull" || dir == "both") c.pullMemory().fold({ parts.add(it) }, { ok = false; parts.add("Pull failed: ${it.message}") })
            if (dir == "push" || dir == "both") c.pushMemory().fold({ parts.add(it) }, { ok = false; parts.add("Push failed: ${it.message}") })
            if (parts.isEmpty()) return@register ToolOutcome.fail("direction must be push, pull or both")
            outcome(ok, parts.joinToString(" "))
        }
    }

    // ── Maya Home (ESP32) ────────────────────────────────────────────────────

    private fun installMayaHome() {
        MayaToolRegistry.register(
            name = "smart_home_list",
            description = "List the user's local Maya Home (ESP32) devices with name, room, kind, IP, last known state and any running patterns/timers.",
            family = FAMILY
        ) { ctx, _ ->
            val h = MayaHomeController.get(ctx)
            ToolOutcome.ok(h.listSummary(), h.listJson())
        }

        MayaToolRegistry.register(
            name = "smart_home_add_device",
            description = "Add a local ESP32 smart-home device. firmware sets the URL layout: tasmota (/cm?cmnd=Power%20On), esphome (/switch/<channel>/turn_on), shelly (/relay/0?turn=on), simple (/on,/off,/toggle,/status) or custom (give on_url/off_url).",
            properties = MayaToolRegistry.props(
                "name" to p("string", "Friendly name, e.g. 'room light'"),
                "room" to p("string", "Room, e.g. 'bedroom'"),
                "kind" to p("string", "light | plug | fan | relay", listOf("light", "plug", "fan", "relay")),
                "ip" to p("string", "Device IP or host on the LAN"),
                "firmware" to p("string", "tasmota | esphome | shelly | simple | custom", listOf("tasmota", "esphome", "shelly", "simple", "custom")),
                "channel" to p("string", "ESPHome entity id / Tasmota or Shelly relay index (optional)"),
                "on_url" to p("string", "Custom full ON URL (optional)"),
                "off_url" to p("string", "Custom full OFF URL (optional)"),
                "toggle_url" to p("string", "Custom full TOGGLE URL (optional)"),
                "status_url" to p("string", "Custom full STATUS URL (optional)")
            ),
            required = listOf("name"),
            family = FAMILY
        ) { ctx, a ->
            val o = MayaHomeController.get(ctx).addDevice(
                a.str("name"), a.str("room"), a.str("kind"), a.str("ip"), a.str("firmware"), a.str("channel"),
                a.str("on_url"), a.str("off_url"), a.str("toggle_url"), a.str("status_url")
            )
            outcome(o.success, o.message, o.json)
        }

        MayaToolRegistry.register(
            name = "smart_home_remove_device",
            description = "Remove a local Maya Home device by name.",
            properties = MayaToolRegistry.props("name" to p("string", "Device name or id")),
            required = listOf("name"),
            family = FAMILY,
            risky = true
        ) { ctx, a ->
            val o = MayaHomeController.get(ctx).removeDevice(a.str("name"))
            outcome(o.success, o.message, o.json)
        }

        MayaToolRegistry.register(
            name = "smart_home_control",
            description = "Control the user's smart-home devices (lights, fan, plug, charger, geyser...). ONE call does the whole request: several devices at once (target='room light, blue light'; 'saari lights' / 'sab' = all - never one call per device), and timing: interval_sec = gap between devices ('do-do second ke interval pe chalu karo' = 2), delay_sec = later ('5 minute baad fan band' = 300), action 'blink' flashes them together, 'chase' = one-after-another party lights, 'cancel' stops a running pattern/timer. The tool schedules timing itself - NEVER use run_mission / wait for timing. If the user names a device (even slightly differently) call this directly; do not ask for the list first. Say what the result says: 'started' is not 'done'. Targets that are not local ESP32 devices go to Home Assistant/webhook.",
            properties = MayaToolRegistry.props(
                "target" to p("string", "Device name(s), comma separated, room or 'all'/'sab'"),
                "action" to p("string", "on | off | toggle | blink | chase | cancel", listOf("on", "off", "toggle", "blink", "chase", "cancel")),
                "interval_sec" to p("number", "Gap between devices / blink edges in seconds"),
                "delay_sec" to p("number", "Start after this many seconds"),
                "count" to p("integer", "Blink flashes or chase rounds (default 5 / 3)")
            ),
            required = listOf("action"),
            family = FAMILY
        ) { ctx, a ->
            val o = MayaHomeController.get(ctx).control(
                target = a.str("target").ifBlank { "all" },
                action = a.str("action"),
                intervalSec = a.dbl("interval_sec"),
                delaySec = a.dbl("delay_sec"),
                count = a.int("count")
            )
            outcome(o.success, o.message, o.json)
        }

        MayaToolRegistry.register(
            name = "smart_home_query",
            description = "Ask a local Maya Home device (or several / all) whether it is on or off and whether a pattern/timer is running.",
            properties = MayaToolRegistry.props("target" to p("string", "Device name(s) or room; empty = all")),
            family = FAMILY
        ) { ctx, a ->
            val o = MayaHomeController.get(ctx).query(a.str("target"))
            outcome(o.success, o.message, o.json)
        }
    }

    /** Names of every tool this pack registers (for docs / find_tool tests). */
    val TOOL_NAMES: List<String> = listOf(
        "termux_status", "termux_run", "termux_read_more", "termux_find_in_output", "termux_open_session",
        "termux_install_package", "termux_list_recipes", "termux_setup_recipe", "termux_read_file",
        "termux_write_file", "termux_run_in_ubuntu",
        "pc_hub_status", "link_pc", "unlink_pc", "ask_my_pc", "control_my_pc", "call_my_pc", "pc_call_say",
        "end_pc_call", "sync_memory_with_pc",
        "smart_home_list", "smart_home_add_device", "smart_home_remove_device", "smart_home_control", "smart_home_query"
    )

    @Suppress("unused")
    private fun names(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.optString(it) }
}
