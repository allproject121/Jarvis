package com.jarvis.assistant.maya.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import kotlinx.coroutines.launch

/**
 * Jarvis HUB screen: Termux · PC ⇄ Phone · Maya Home.
 * Launch with `Intent(context, HubActivity::class.java)`.
 */
class HubActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroLLMTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HubScreen(onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun HubScreen(onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Termux", "PC ⇄ Phone", "Maya Home")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Jarvis HUB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                0 -> TermuxTab()
                1 -> PcTab()
                else -> HomeTab()
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun OutputBox(text: String) {
    if (text.isBlank()) return
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        )
    }
}

// ── Termux ──────────────────────────────────────────────────────────────────

@Composable
private fun TermuxTab() {
    val ctx = LocalContext.current
    val hub = remember { TermuxHub.get(ctx) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Checking...") }
    var busy by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var nextToken by remember { mutableStateOf<String?>(null) }
    val version by hub.jobsVersion.collectAsState()
    val jobs = remember(version) { hub.listJobs(8) }

    LaunchedEffect(Unit) { status = hub.status().message }

    SectionCard("Status") {
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { status = "Checking..."; status = hub.status().message } }) { Text("Refresh") }
            if (!hub.hasPermission()) OutlinedButton(onClick = { hub.requestPermission() }) { Text("Grant permission") }
        }
    }

    SectionCard("Recipes") {
        TermuxRecipes.all.values.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, fontWeight = FontWeight.SemiBold)
                    Text("${r.description} (~${r.estimatedTime})", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val res = hub.setupRecipe(r.name)
                        output = res.message
                        nextToken = res.json.optString("job_id").ifBlank { null }
                        busy = false
                    }
                }) { Text("Install") }
            }
        }
    }

    SectionCard("Run a command") {
        OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("bash command") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && command.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    output = "Running..."
                    val res = hub.runCommand(command, background = background)
                    output = res.message
                    nextToken = if (res.json.isNull("next_token")) null else res.json.optString("next_token").ifBlank { null }
                    busy = false
                }
            }) { Text(if (background) "Start in background" else "Run") }
            TextButton(onClick = { background = !background }) { Text(if (background) "Mode: background" else "Mode: foreground") }
            TextButton(onClick = { scope.launch { hub.openSession(command).let { output = it.message } } }) { Text("Open in Termux") }
        }
    }

    SectionCard("Output") {
        OutputBox(output.ifBlank { "(no output yet)" })
        val tok = nextToken
        if (tok != null) {
            OutlinedButton(onClick = {
                scope.launch {
                    val res = hub.readMore(tok)
                    output = res.message
                    nextToken = if (res.json.isNull("next_token")) null else res.json.optString("next_token").ifBlank { null }
                }
            }) { Text("Read more / refresh") }
        }
    }

    SectionCard("Recent jobs") {
        if (jobs.isEmpty()) Text("No jobs yet.", style = MaterialTheme.typography.bodySmall)
        jobs.forEach { j ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${j.state.padEnd(8)} ${j.command.take(48)}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        val res = hub.readMore(j.id)
                        output = res.message
                        nextToken = if (res.json.isNull("next_token")) null else res.json.optString("next_token").ifBlank { null }
                    }
                }) { Text("View") }
            }
        }
    }
}

// ── PC ⇄ Phone ──────────────────────────────────────────────────────────────

@Composable
private fun PcTab() {
    val ctx = LocalContext.current
    val client = remember { PcHubClient.get(ctx) }
    val prefs = remember { HubPrefs.get(ctx) }
    val scope = rememberCoroutineScope()
    val state by client.state.collectAsState()
    val callActive by client.callActive.collectAsState()
    val lastError by client.lastError.collectAsState()
    var host by remember { mutableStateOf(prefs.pcHost) }
    var port by remember { mutableStateOf(prefs.pcPort.toString()) }
    var code by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val transcript = remember { mutableStateListOf<String>() }
    val phoneCode = remember { client.ensurePhoneCode() }

    LaunchedEffect(Unit) {
        client.autoConnectIfLinked()
        client.log.collect { line ->
            transcript.add("${line.direction}: ${line.text}")
            while (transcript.size > 40) transcript.removeAt(0)
        }
    }

    SectionCard("Connection") {
        Text("State: ${state.name.lowercase()}${if (callActive) " · call open" else ""}", fontWeight = FontWeight.SemiBold)
        Text(client.describe(), style = MaterialTheme.typography.bodySmall)
        if (lastError.isNotBlank()) Text("Last error: $lastError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("This phone's code: ${PcPairing.pretty(phoneCode)} (shown on the PC console on connect)", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("PC IP / host (127.0.0.1 over USB)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Port") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text("Join code") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = host.isNotBlank() && code.length == 6, onClick = {
                scope.launch {
                    client.disconnect("relinking")
                    val hp = PcPairing.parseHostPort(host, port.toIntOrNull() ?: HubPrefs.DEFAULT_PC_PORT)
                    if (hp == null) { result = "Invalid host"; return@launch }
                    prefs.pcHost = hp.first
                    prefs.pcPort = port.toIntOrNull() ?: hp.second
                    prefs.relayToken = ""
                    prefs.pcLinked = true
                    client.connect(code)
                    result = if (client.awaitPaired()) "Linked with ${prefs.pcName.ifBlank { "PC" }}." else {
                        prefs.pcLinked = false
                        client.disconnect("pair failed")
                        "Pairing failed: ${client.lastError.value.ifBlank { "no answer" }}"
                    }
                }
            }) { Text("Link") }
            OutlinedButton(onClick = { client.disconnect("unlinked"); prefs.clearPcLink(); result = "Unlinked." }) { Text("Unlink") }
            OutlinedButton(onClick = { client.connect() }) { Text("Reconnect") }
        }
    }

    SectionCard("Ask my PC") {
        OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text("Question or task") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = question.isNotBlank(), onClick = {
                scope.launch {
                    result = "Asking..."
                    result = client.ask(question).fold({ "[PC] $it" }, { "Failed: ${it.message}" })
                }
            }) { Text("Ask") }
            OutlinedButton(onClick = { scope.launch { result = client.control("status").fold({ it.optString("text") }, { "Failed: ${it.message}" }) } }) { Text("PC status") }
            OutlinedButton(onClick = { scope.launch { result = client.control("lock").fold({ it.optString("text") }, { "Failed: ${it.message}" }) } }) { Text("Lock PC") }
        }
    }

    SectionCard("Memory sync") {
        val last = prefs.lastMemorySyncAt
        Text(if (last > 0) "Last sync: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}" else "Never synced.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { result = client.pushMemory().fold({ it }, { "Push failed: ${it.message}" }) } }) { Text("Push") }
            OutlinedButton(onClick = { scope.launch { result = client.pullMemory().fold({ it }, { "Pull failed: ${it.message}" }) } }) { Text("Pull") }
        }
    }

    if (result.isNotBlank()) SectionCard("Result") { OutputBox(result) }

    SectionCard("Hub traffic") {
        OutputBox(transcript.joinToString("\n").ifBlank { "(nothing yet)" })
    }
}

// ── Maya Home ───────────────────────────────────────────────────────────────

@Composable
private fun HomeTab() {
    val ctx = LocalContext.current
    val home = remember { MayaHomeController.get(ctx) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val schedVersion by home.scheduler.version.collectAsState()
    val devices = remember(refresh) { home.devices() }
    val running = remember(schedVersion, refresh) { home.scheduler.active() }
    var message by remember { mutableStateOf("") }

    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("light") }
    var ip by remember { mutableStateOf("") }
    var firmware by remember { mutableStateOf("tasmota") }
    var channel by remember { mutableStateOf("") }

    SectionCard("Devices (${devices.size})") {
        if (devices.isEmpty()) Text(MayaHomeController.MSG_NO_DEVICES, style = MaterialTheme.typography.bodySmall)
        devices.forEach { d ->
            Column(Modifier.fillMaxWidth()) {
                Text("${d.name}${if (d.room.isNotBlank()) " · ${d.room}" else ""}", fontWeight = FontWeight.SemiBold)
                Text("${d.kind.label} · ${d.firmware.label} · ${d.ip}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("on", "off", "toggle", "blink").forEach { act ->
                        OutlinedButton(onClick = { scope.launch { message = home.control(d.name, act).message } }) { Text(act) }
                    }
                    TextButton(onClick = { scope.launch { message = home.query(d.name).message } }) { Text("status") }
                    TextButton(onClick = { message = home.removeDevice(d.id).message; refresh++ }) { Text("remove") }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (devices.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { message = home.control("sab", "on").message } }) { Text("All on") }
                Button(onClick = { scope.launch { message = home.control("sab", "off").message } }) { Text("All off") }
                OutlinedButton(onClick = { scope.launch { message = home.control("saari lights", "chase", intervalSec = 0.6).message } }) { Text("Party") }
            }
        }
    }

    if (running.isNotEmpty()) {
        SectionCard("Running patterns") {
            running.forEach { r -> Text("${r.label} · ${r.deviceIds.size} device(s)", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { home.scheduler.cancelAll(); message = "Cancelled." }) { Text("Cancel all") }
        }
    }

    if (message.isNotBlank()) SectionCard("Result") { Text(message, style = MaterialTheme.typography.bodySmall) }

    SectionCard("Add device") {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. room light)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Room") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP / host (192.168.1.42)") }, modifier = Modifier.fillMaxWidth())
        Text("Kind", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("light", "plug", "fan", "relay").forEach { k ->
                if (k == kind) Button(onClick = { kind = k }) { Text(k) } else OutlinedButton(onClick = { kind = k }) { Text(k) }
            }
        }
        Text("Firmware", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("tasmota", "esphome", "shelly", "simple").forEach { f ->
                if (f == firmware) Button(onClick = { firmware = f }) { Text(f) } else OutlinedButton(onClick = { firmware = f }) { Text(f) }
            }
        }
        OutlinedTextField(value = channel, onValueChange = { channel = it }, label = { Text("Channel / entity id (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank() && ip.isNotBlank(), onClick = {
            val o = home.addDevice(name, room, kind, ip, firmware, channel)
            message = o.message
            if (o.success) { name = ""; ip = ""; channel = ""; refresh++ }
        }) { Text("Add") }
    }
}
