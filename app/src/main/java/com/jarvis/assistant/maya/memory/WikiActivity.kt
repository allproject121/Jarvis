package com.jarvis.assistant.maya.memory

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wiki, rules & episodes: the Maya "Memory & data" screen, reached from MemoryActivity.
 * Tabs: Wiki · Rules · Episodes · Notices · Backup.
 */
class WikiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startTab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0
        setContent {
            AndroLLMTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WikiScreen(startTab = startTab, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_WIKI = 0
        const val TAB_RULES = 1
        const val TAB_EPISODES = 2
        const val TAB_NOTICES = 3
        const val TAB_BACKUP = 4

        fun intent(context: Context, tab: Int = TAB_WIKI): android.content.Intent =
            android.content.Intent(context, WikiActivity::class.java).putExtra(EXTRA_TAB, tab)
    }
}

private val TABS = listOf("Wiki", "Rules", "Episodes", "Notices", "Backup")

private fun fmt(ts: Long): String = if (ts <= 0) "never" else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
private fun WikiScreen(startTab: Int, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(startTab.coerceIn(0, TABS.lastIndex)) }
    val unread by NoticesStore.unreadFlow.collectAsState()
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Wiki, rules & episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text(if (i == WikiActivity.TAB_NOTICES && unread > 0) "$title ($unread)" else title)
                })
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            when (tab) {
                WikiActivity.TAB_WIKI -> WikiTab()
                WikiActivity.TAB_RULES -> RulesTab()
                WikiActivity.TAB_EPISODES -> EpisodesTab()
                WikiActivity.TAB_NOTICES -> NoticesTab()
                else -> BackupTab()
            }
        }
    }
}

// ── Wiki ─────────────────────────────────────────────────────────────────────

@Composable
private fun WikiTab() {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<WikiPageMeta>>(emptyList()) }
    var open by remember { mutableStateOf<Pair<WikiPageMeta, String>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey) { pages = withContext(Dispatchers.IO) { WikiCompiler.readIndex(context) } }

    val current = open
    if (current != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = { open = null }) { Text("← All pages") }
            Text("Updated ${fmt(current.first.updatedAt)} · ${current.first.sources} sources", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { MarkdownText(current.second) }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your personal wiki", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (!WikiCompiler.hasLlm(context)) {
                    Toast.makeText(context, "Configure an AI model/key first", Toast.LENGTH_SHORT).show()
                } else {
                    WikiScheduler.runNow(context, force = true)
                    Toast.makeText(context, "Compiling wiki in the background…", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Compile now") }
            TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
        }
        Text("Rebuilt every 12 h from your memories and conversations.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (pages.isEmpty()) {
            Text("No pages yet. Tap Compile now (needs an AI key).", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages, key = { it.slug }) { p ->
                Card(modifier = Modifier.fillMaxWidth().clickable {
                    val md = WikiCompiler.readPage(context, p.slug).orEmpty()
                    open = p to md.ifBlank { "_(empty page)_" }
                }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.title, fontWeight = FontWeight.Bold)
                        if (p.summary.isNotBlank()) Text(p.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Updated ${fmt(p.updatedAt)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Tiny markdown renderer: #/##/### headings, - and * bullets, **bold**. */
@Composable
private fun MarkdownText(md: String) {
    md.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            line.startsWith("### ") -> Text(inlineBold(line.removePrefix("### ")), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            line.startsWith("## ") -> Text(inlineBold(line.removePrefix("## ")), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
            line.startsWith("# ") -> Text(inlineBold(line.removePrefix("# ")), fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = (line.length - line.trimStart().length) / 2
                Row(modifier = Modifier.padding(start = (8 + indent * 12).dp, top = 2.dp)) {
                    Text("•  ")
                    Text(inlineBold(line.trimStart().drop(2)))
                }
            }
            else -> Text(inlineBold(line), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun inlineBold(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1 && parts.size > 2) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
        } else {
            append(part)
        }
    }
}

// ── Rules ────────────────────────────────────────────────────────────────────

@Composable
private fun RulesTab() {
    val context = LocalContext.current
    val store = remember { RulesStore.get(context) }
    var rules by remember { mutableStateOf(store.list()) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var editText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Standing instructions Jarvis follows in every conversation.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("e.g. Jawab chhote rakho") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (draft.isNotBlank()) {
                    store.add(draft)
                    draft = ""
                    rules = store.list()
                }
            }) { Text("Add") }
        }
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) Text("No rules yet. Say \"ab se hamesha …\" or add one here.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rules, key = { it.id }) { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text)
                            Text("v${r.version} · ${fmt(r.updatedAt)}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { editing = r; editText = r.text }) { Text("Edit") }
                        TextButton(onClick = { store.delete(r.id); rules = store.list() }) { Text("Delete") }
                    }
                }
            }
        }
    }

    val e = editing
    if (e != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit rule") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    store.update(e.id, editText)
                    rules = store.list()
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

// ── Episodes ─────────────────────────────────────────────────────────────────

@Composable
private fun EpisodesTab() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, date) {
        val r = withContext(Dispatchers.IO) {
            val log = EpisodeLog.get(context)
            total = log.count()
            log.recall(query.ifBlank { null }, date.ifBlank { null }?.takeIf { EpisodeScoring.dateRange(it) != null }, 100)
        }
        results = r
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("$total conversation episodes logged", style = MaterialTheme.typography.bodySmall)
        Row {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), placeholder = { Text("Search") }, singleLine = true)
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(value = date, onValueChange = { date = it }, modifier = Modifier.weight(0.8f), placeholder = { Text("yyyy-MM-dd / today") }, singleLine = true)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results, key = { "${it.ts}_${it.user.hashCode()}" }) { ep ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(fmt(ep.ts) + (ep.app?.let { " · $it" } ?: "") + (if (ep.tools.isNotEmpty()) " · ${ep.tools.joinToString()}" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (ep.user.isNotBlank()) Text("You: ${ep.user}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Jarvis: ${ep.assistant}", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// ── Notices ──────────────────────────────────────────────────────────────────

@Composable
private fun NoticesTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notices by NoticesStore.noticesFlow.collectAsState()
    var quote by remember { mutableStateOf(DailyQuote.cached(context)) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { NoticesStore.refresh(context) }
        if (quote == null) quote = DailyQuote.today(context)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("Aaj ka quote", style = MaterialTheme.typography.labelMedium)
                Text(quote ?: "…", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch(Dispatchers.IO) { NoticesStore.markAllRead(context) } }) { Text("Mark all read") }
        }
        if (notices.isEmpty()) Text("No notices yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(notices, key = { it.id }) { n ->
                Card(modifier = Modifier.fillMaxWidth().clickable { scope.launch(Dispatchers.IO) { NoticesStore.markRead(context, n.id) } }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (n.kind == NoticesStore.KIND_ALERT) "⚠ " else "") + n.title,
                                fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold,
                                color = if (n.kind == NoticesStore.KIND_ALERT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodySmall)
                            Text(fmt(n.ts) + if (!n.read) " · unread" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { NoticesStore.delete(context, n.id) } }) { Text("✕") }
                    }
                }
            }
        }
    }
}

// ── Backup ───────────────────────────────────────────────────────────────────

@Composable
private fun BackupTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var incognito by remember { mutableStateOf(MemoryPrefs.isIncognito(context)) }
    var autoSave by remember { mutableStateOf(MemoryPrefs.isAutoSave(context)) }

    // Same engine + file format as Settings -> System -> Backup & restore (data/backup/BackupManager).
    var restartPrompt by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "Creating backup..."
            scope.launch {
                val r = com.jarvis.assistant.data.backup.BackupManager.exportToUri(context, uri)
                status = r.fold({ it.describe() }, { "Export failed: ${it.message}" })
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "Restoring..."
            scope.launch {
                val r = com.jarvis.assistant.data.backup.BackupManager.restoreFromUri(context, uri)
                status = r.fold({ "Restored.\n${it.describe()}" }, { "Restore failed: ${it.message}" })
                restartPrompt = r.isSuccess
                busy = false
            }
        }
    }
    if (restartPrompt) {
        AlertDialog(
            onDismissRequest = { restartPrompt = false },
            title = { Text("Backup restored") },
            text = { Text("Restart Jarvis now so every screen loads the restored settings and data.") },
            confirmButton = {
                TextButton(onClick = {
                    restartPrompt = false
                    (context as? android.app.Activity)?.let { com.jarvis.assistant.data.backup.BackupManager.restartApp(it) }
                }) { Text("Restart now") }
            },
            dismissButton = { TextButton(onClick = { restartPrompt = false }) { Text("Later") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Memory & data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Same backup as Settings → System → Backup & restore: one file with settings, memories, chats, rules, episodes, wiki, notices and saved macros. You choose where to save it. API keys, tokens and the voice print are never included.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                runCatching { exporter.launch(com.jarvis.assistant.data.backup.BackupManager.suggestedFileName()) }
                    .onFailure { status = "No file picker available: ${it.message}" }
            }) { Text("Export") }
            OutlinedButton(enabled = !busy, onClick = {
                runCatching { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
                    .onFailure { status = "No file picker available: ${it.message}" }
            }) { Text("Restore") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        ToggleRow("Incognito", "Don't store this session (no facts, episodes or wiki).", incognito) {
            incognito = it
            MemoryPrefs.setIncognito(context, it)
        }
        ToggleRow("Auto-save memories", "Learn facts from conversations automatically.", autoSave) {
            autoSave = it
            MemoryPrefs.setAutoSave(context, it)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
