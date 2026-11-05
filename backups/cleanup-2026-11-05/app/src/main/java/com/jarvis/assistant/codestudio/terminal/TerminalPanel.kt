package com.jarvis.assistant.codestudio.terminal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.launch
import java.io.File

/**
 * Interactive terminal for Code Studio. One persistent `/system/bin/sh` (or a root shell
 * via `su -c /system/bin/sh` once the user grants root) rooted at [workDir].
 */
@Composable
fun TerminalPanel(workDir: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val ledger = MaterialTheme.ledger

    val session = remember(workDir.absolutePath) { TerminalSession(workDir) }
    DisposableEffect(session) {
        session.start(root = false)
        onDispose { session.dispose() }
    }

    val lines by session.lines.collectAsState()
    val running by session.running.collectAsState()
    val rootActive by session.rootActive.collectAsState()

    var input by remember { mutableStateOf("") }
    val history = remember(workDir.absolutePath) { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var pendingDangerous by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var rootNote by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    fun execute(cmd: String) {
        if (history.lastOrNull() != cmd) history.add(cmd)
        if (history.size > 200) history.removeAt(0)
        historyIndex = -1
        when (cmd.trim()) {
            "clear", "cls" -> session.clear()
            else -> session.submit(cmd)
        }
    }

    fun submitInput() {
        val cmd = input.trimEnd()
        if (cmd.isBlank()) return
        input = ""
        if (running) {
            // A program is reading stdin: forward the line as input, no marker.
            session.sendInput(cmd)
            return
        }
        val danger = ShellSafety.destructiveReason(cmd)
        if (danger != null) pendingDangerous = cmd to danger else execute(cmd)
    }

    fun historyUp() {
        if (history.isEmpty()) return
        historyIndex = if (historyIndex < 0) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
        input = history[historyIndex]
    }

    fun historyDown() {
        if (historyIndex < 0) return
        historyIndex += 1
        if (historyIndex > history.lastIndex) { historyIndex = -1; input = "" } else input = history[historyIndex]
    }

    fun toggleRoot(enable: Boolean) {
        if (!enable) {
            session.start(root = false)
            rootNote = null
            return
        }
        if (!RootShell.isSuAvailable()) {
            rootNote = "No 'su' binary found — this device is not rooted."
            return
        }
        rootBusy = true
        rootNote = "Requesting root… approve the prompt in your su manager."
        scope.launch {
            val granted = RootShell.requestRoot()
            rootBusy = false
            if (granted && session.start(root = true)) {
                rootNote = null
            } else {
                rootNote = "Root was denied or timed out. Staying in normal shell."
                if (!session.isAlive()) session.start(root = false)
            }
        }
    }

    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

    Surface(modifier = modifier, color = ledger.deskNight, contentColor = ledger.deskPaper) {
        Column(Modifier.fillMaxSize()) {
            // ── toolbar ───────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().background(ledger.deskWalnut).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (rootActive) "root@shell" else "shell",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (rootActive) ledger.emberRed else ledger.deskPaper,
                    fontWeight = FontWeight.SemiBold
                )
                if (running) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ledger.lampAmber)
                }
                Spacer(Modifier.weight(1f))
                Text("Root", style = MaterialTheme.typography.labelMedium, color = ledger.deskInk)
                Spacer(Modifier.width(4.dp))
                if (rootBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ledger.lampAmber)
                } else {
                    Switch(checked = rootActive, onCheckedChange = { toggleRoot(it) })
                }
                IconButton(onClick = { session.interrupt() }) {
                    Icon(Icons.Filled.Stop, contentDescription = "Ctrl-C", tint = ledger.emberRed)
                }
                IconButton(onClick = {
                    if (session.isAlive()) session.terminate() else session.start(root = false)
                }) {
                    Icon(
                        if (session.isAlive()) Icons.Filled.PowerSettingsNew else Icons.Filled.RestartAlt,
                        contentDescription = if (session.isAlive()) "Terminate shell" else "Restart shell",
                        tint = ledger.deskPaperDim
                    )
                }
                IconButton(onClick = { session.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear", tint = ledger.deskPaperDim)
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(session.transcript()))
                    Toast.makeText(context, "Terminal log copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs", tint = ledger.deskPaperDim)
                }
            }
            rootNote?.let {
                Text(
                    it, Modifier.fillMaxWidth().background(ledger.emberRedSoft).padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = ledger.emberRedHard
                )
            }
            HorizontalDivider(color = ledger.deskHairline)

            // ── output ────────────────────────────────────────────────
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(lines, key = { it.id }) { line ->
                        val color: Color = when (line.kind) {
                            TermLineKind.INPUT -> ledger.lampGlow
                            TermLineKind.STDOUT -> ledger.deskPaper
                            TermLineKind.STDERR -> ledger.emberRed
                            TermLineKind.SYSTEM -> ledger.deskInk
                        }
                        Text(line.text, style = mono, color = color)
                    }
                }
            }

            HorizontalDivider(color = ledger.deskHairline)
            // ── input ─────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { historyUp() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous command", tint = ledger.deskInk)
                }
                IconButton(onClick = { historyDown() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next command", tint = ledger.deskInk)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                ev.key == Key.DirectionUp -> { historyUp(); true }
                                ev.key == Key.DirectionDown -> { historyDown(); true }
                                ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { submitInput(); true }
                                ev.isCtrlPressed && ev.key == Key.C && input.isEmpty() -> { session.interrupt(); true }
                                ev.isCtrlPressed && ev.key == Key.L -> { session.clear(); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    textStyle = mono.copy(color = ledger.deskPaper, fontSize = 13.sp),
                    placeholder = {
                        Text(if (running) "input to running program…" else if (rootActive) "# command" else "$ command", style = mono, color = ledger.deskInkFaint)
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ledger.lampAmber,
                        unfocusedBorderColor = ledger.deskHairline,
                        cursorColor = ledger.lampAmber
                    )
                )
                IconButton(onClick = { submitInput() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = ledger.lampAmber)
                }
            }
        }
    }

    pendingDangerous?.let { (cmd, reason) ->
        AlertDialog(
            onDismissRequest = { pendingDangerous = null },
            title = { Text("Dangerous command") },
            text = {
                Column {
                    Text("This command $reason and could break your device or erase data:")
                    Spacer(Modifier.height(8.dp))
                    Text(cmd, style = mono, color = ledger.emberRed)
                    if (rootActive) {
                        Spacer(Modifier.height(8.dp))
                        Text("You are in a ROOT shell — nothing will stop it.", color = ledger.emberRed, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingDangerous = null; execute(cmd) }) {
                    Text("Run anyway", color = ledger.emberRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerous = null; input = cmd }) { Text("Cancel") }
            }
        )
    }
}
