package com.jarvis.assistant.codestudio.editor

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.codestudio.ai.AutoFixEngine
import com.jarvis.assistant.codestudio.ai.CodeStudioAi
import com.jarvis.assistant.codestudio.generator.GeneratorKind
import com.jarvis.assistant.codestudio.generator.ProjectGenerator
import com.jarvis.assistant.codestudio.workspace.CodeStudioWorkspace
import com.jarvis.assistant.codestudio.workspace.FileNode
import com.jarvis.assistant.codestudio.workspace.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One open editor tab. [value] is the live editor state; [savedText] what is on disk. */
class EditorTab(val file: File, val path: String, initialText: String) {
    var value by mutableStateOf(TextFieldValue(initialText))
    var savedText by mutableStateOf(initialText)
    val language: CodeLanguage = CodeLanguage.fromFileName(file.name)
    val dirty: Boolean get() = value.text != savedText
}

data class ConsoleLine(val time: Long, val source: String, val text: String) {
    val isError: Boolean get() = ERROR_RE.containsMatchIn(text)
    companion object { private val ERROR_RE = Regex("(?i)\\b(error|exception|uncaught|failed|cannot|undefined is not|not defined|syntaxerror|typeerror|referenceerror)\\b") }
}

data class ChangeRecord(val path: String, val before: String?, val after: String)

data class HistoryEntry(
    val id: Long,
    val time: Long,
    val kind: String,                 // "AI EDIT", "AUTO-FIX", "GENERATE", "UNDO"
    val prompt: String,
    val summary: String,
    val success: Boolean,
    val details: List<String>,
    val changes: List<ChangeRecord>,  // kept in memory only (undo)
    val undone: Boolean = false
)

enum class StudioPanel { EDITOR, PREVIEW, TERMINAL, CONSOLE, HISTORY }

class CodeStudioViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    val workspace = CodeStudioWorkspace(app)
    private val autoFix = AutoFixEngine(app, workspace)
    private val main = Handler(Looper.getMainLooper())

    var projects by mutableStateOf<List<ProjectInfo>>(emptyList()); private set
    var projectDir by mutableStateOf<File?>(null); private set
    var tree by mutableStateOf<List<FileNode>>(emptyList()); private set
    var collapsed by mutableStateOf<Set<String>>(emptySet()); private set

    val tabs = mutableStateListOf<EditorTab>()
    var activeTab by mutableStateOf(-1); private set

    val console = mutableStateListOf<ConsoleLine>()
    val history = mutableStateListOf<HistoryEntry>()

    var panel by mutableStateOf(StudioPanel.EDITOR)
    var fullscreen by mutableStateOf(false)

    var aiBusy by mutableStateOf(false); private set
    var aiStatus by mutableStateOf<String?>(null); private set
    val autoFixLog = mutableStateListOf<String>()
    var autoFixRunning by mutableStateOf(false); private set
    var autoFixVerdict by mutableStateOf<String?>(null); private set
    var generating by mutableStateOf(false); private set
    val generatorLog = mutableStateListOf<String>()

    /** One-shot user message (snackbar). */
    var message by mutableStateOf<String?>(null)

    private var aiJob: Job? = null

    val current: EditorTab? get() = tabs.getOrNull(activeTab)

    init { refreshProjects() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun notify(msg: String) { message = msg }

    // ── Projects ────────────────────────────────────────────────────────────

    fun refreshProjects() {
        viewModelScope.launch {
            projects = withContext(Dispatchers.IO) { runCatching { workspace.listProjects() }.getOrDefault(emptyList()) }
        }
    }

    fun openProject(dir: File, thenOpenPath: String? = null) {
        saveAll()
        tabs.clear(); activeTab = -1
        projectDir = dir
        collapsed = emptySet()
        console.clear()
        loadHistory(dir)
        refreshTree()
        val target = thenOpenPath
            ?: listOf("index.html", "main.py", "README.md", "src/index.html").firstOrNull { File(dir, it).isFile }
        target?.let { p -> runCatching { workspace.resolveInProject(dir, p) }.getOrNull()?.takeIf { it.isFile }?.let { openFile(it) } }
        panel = StudioPanel.EDITOR
        log("studio", "Opened project ${dir.name}")
    }

    fun openProjectByName(name: String, filePath: String?) {
        val dir = File(workspace.root, com.jarvis.assistant.codestudio.CodeStudioPaths.sanitize(name))
        if (!dir.isDirectory) { notify("Project '$name' not found"); return }
        openProject(dir, filePath)
    }

    fun closeProject() {
        saveAll(); tabs.clear(); activeTab = -1; projectDir = null; tree = emptyList(); refreshProjects()
    }

    fun createProject(name: String, starter: Boolean) = guard {
        val dir = workspace.createProject(name, starter)
        refreshProjects(); openProject(dir)
    }

    fun renameProject(dir: File, newName: String) = guard {
        val wasOpen = projectDir?.canonicalPath == dir.canonicalPath
        if (wasOpen) closeProject()
        val nd = workspace.renameProject(dir, newName)
        refreshProjects()
        if (wasOpen) openProject(nd)
    }

    fun deleteProject(dir: File) = guard {
        if (projectDir?.canonicalPath == dir.canonicalPath) closeProject()
        workspace.deleteProject(dir)
        historyFile(dir).delete()
        refreshProjects()
        notify("Deleted ${dir.name}")
    }

    // ── Tree ────────────────────────────────────────────────────────────────

    fun refreshTree() {
        val dir = projectDir ?: return
        viewModelScope.launch {
            tree = withContext(Dispatchers.IO) { runCatching { workspace.listTree(dir, collapsed) }.getOrDefault(emptyList()) }
        }
    }

    fun toggleFolder(node: FileNode) {
        collapsed = if (node.relativePath in collapsed) collapsed - node.relativePath else collapsed + node.relativePath
        refreshTree()
    }

    fun createFile(parent: File, name: String) = guard {
        val f = workspace.createFile(parent, name)
        refreshTree(); openFile(f)
    }

    fun createFolder(parent: File, name: String) = guard {
        workspace.createFolder(parent, name); refreshTree()
    }

    fun renameNode(file: File, newName: String) = guard {
        saveAll()
        val nf = workspace.rename(file, newName)
        // Re-point open tabs under the renamed path
        val dir = projectDir ?: return@guard
        val oldPrefix = file.canonicalPath
        val reopened = tabs.filter { it.file.canonicalPath == oldPrefix || it.file.canonicalPath.startsWith(oldPrefix + File.separator) }
        reopened.forEach { t ->
            val idx = tabs.indexOf(t)
            val moved = File(nf, t.file.canonicalPath.removePrefix(oldPrefix).trimStart(File.separatorChar))
            val target = if (t.file.canonicalPath == oldPrefix) nf else moved
            tabs[idx] = EditorTab(target, workspace.relativeTo(dir, target), t.savedText)
        }
        refreshTree()
    }

    fun deleteNode(file: File) = guard {
        val cp = file.canonicalPath
        tabs.filter { it.file.canonicalPath == cp || it.file.canonicalPath.startsWith(cp + File.separator) }
            .forEach { closeTab(tabs.indexOf(it), save = false) }
        workspace.delete(file); refreshTree()
    }

    // ── Tabs ────────────────────────────────────────────────────────────────

    fun openFile(file: File) {
        val dir = projectDir ?: return
        val existing = tabs.indexOfFirst { it.file.canonicalPath == file.canonicalPath }
        if (existing >= 0) { activeTab = existing; panel = StudioPanel.EDITOR; return }
        if (!CodeStudioWorkspace.isTextFile(file)) { notify("${file.name} is a binary file"); return }
        val text = try { workspace.readText(file) } catch (e: Exception) { notify(e.message ?: "Cannot open file"); return }
        tabs += EditorTab(file, workspace.relativeTo(dir, file), text)
        activeTab = tabs.lastIndex
        panel = StudioPanel.EDITOR
    }

    fun selectTab(i: Int) { if (i in tabs.indices) { activeTab = i; panel = StudioPanel.EDITOR } }

    fun closeTab(i: Int, save: Boolean = true) {
        val t = tabs.getOrNull(i) ?: return
        if (save && t.dirty) saveTab(t)
        tabs.removeAt(i)
        activeTab = when {
            tabs.isEmpty() -> -1
            activeTab >= tabs.size -> tabs.lastIndex
            activeTab > i -> activeTab - 1
            else -> activeTab.coerceAtMost(tabs.lastIndex)
        }
    }

    fun updateText(tab: EditorTab, v: TextFieldValue) { tab.value = v }

    fun saveTab(tab: EditorTab): Boolean = try {
        workspace.writeText(tab.file, tab.value.text)
        tab.savedText = tab.value.text
        true
    } catch (e: Exception) { notify("Save failed: ${e.message}"); false }

    fun saveCurrent() { current?.let { if (saveTab(it)) notify("Saved ${it.file.name}") } }

    fun saveAll() { tabs.filter { it.dirty }.forEach { saveTab(it) } }

    /** Reloads open tabs from disk after AI changes (unsaved edits were saved before the AI ran). */
    private fun reloadTabs(paths: Collection<File>) {
        val set = paths.map { it.canonicalPath }.toSet()
        tabs.forEach { t ->
            if (t.file.canonicalPath in set) {
                val txt = runCatching { t.file.readText() }.getOrNull() ?: return@forEach
                val sel = t.value.selection.start.coerceAtMost(txt.length)
                t.value = TextFieldValue(txt, TextRange(sel))
                t.savedText = txt
            }
        }
        // Drop tabs whose files vanished
        for (i in tabs.indices.reversed()) if (!tabs[i].file.exists()) closeTab(i, save = false)
    }

    // ── Console ─────────────────────────────────────────────────────────────

    fun log(source: String, text: String) = onMain {
        console += ConsoleLine(System.currentTimeMillis(), source, text)
        while (console.size > 1000) console.removeAt(0)
    }

    fun recentErrors(): String = console.filter { it.isError }.takeLast(12).joinToString("\n") { "[${it.source}] ${it.text}" }

    // ── AI edit ─────────────────────────────────────────────────────────────

    fun aiProviderWarning(): String? = CodeStudioAi.providerStatus(appCtx).message

    fun aiEdit(instruction: String) {
        val dir = projectDir ?: return
        if (instruction.isBlank() || aiBusy) return
        val tab = current
        saveAll()
        val selection = tab?.value?.let { v -> if (v.selection.collapsed) null else v.text.substring(v.selection.min, v.selection.max) }
        aiBusy = true
        aiStatus = "Thinking…"
        aiJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val ctxFiles = CodeStudioAi.collectContext(workspace, dir, tab?.file, instruction)
                    val ctx = CodeStudioAi.renderContext(workspace.describeTree(dir), ctxFiles)
                    val resp = CodeStudioAi.complete(appCtx, CodeStudioAi.EDIT_SYSTEM,
                        CodeStudioAi.editPrompt(instruction, tab?.path, selection, ctx))
                    onMain { aiStatus = "Applying edit…" }
                    CodeStudioAi.applyResponse(workspace, dir, resp, tab?.path)
                }
                reloadTabs(outcome.changes.map { it.file }); refreshTree()
                val details = outcome.changes.map { "✓ ${it.path} (${it.kind.name.lowercase().replace('_', ' ')})" } +
                    outcome.failures.map { "✗ ${it.path ?: "?"}: ${it.reason} — \"${it.searchPreview}\"" }
                val msg = when {
                    outcome.anyApplied && outcome.failures.isEmpty() -> "Applied AI edit in ${outcome.changedPaths.joinToString()}"
                    outcome.anyApplied -> "Applied AI edit in ${outcome.changedPaths.joinToString()} (${outcome.failures.size} block(s) could not be applied)"
                    else -> "Could not apply edit automatically: ${outcome.failures.firstOrNull()?.reason ?: "no matching code"}"
                }
                addHistory("AI EDIT", instruction, outcome.summary ?: msg, outcome.anyApplied, details,
                    outcome.changes.map { ChangeRecord(it.path, it.before, it.after) }, raw = outcome.rawResponse)
                log("ai", msg)
                notify(msg)
            } catch (e: CodeStudioAi.AiUnavailableException) {
                notify(e.message ?: "AI unavailable"); log("ai", e.message ?: "AI unavailable")
            } catch (e: kotlinx.coroutines.CancellationException) {
                notify("AI edit cancelled")
            } catch (e: Exception) {
                notify("AI edit failed: ${e.message}"); log("ai", "AI edit failed: ${e.message}")
            } finally {
                aiBusy = false; aiStatus = null
            }
        }
    }

    fun cancelAi() { aiJob?.cancel() }

    // ── Auto-Fix ────────────────────────────────────────────────────────────

    fun runAutoFix(errorText: String, maxCycles: Int) {
        val dir = projectDir ?: return
        if (autoFixRunning || aiBusy) return
        saveAll()
        autoFixLog.clear(); autoFixVerdict = null
        autoFixRunning = true; aiBusy = true; aiStatus = "Auto-Fix running…"
        aiJob = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    autoFix.run(dir, current?.file, errorText, maxCycles) { line -> onMain { autoFixLog += line }; log("auto-fix", line) }
                }
                reloadTabs(res.allChanges.map { it.file }); refreshTree()
                autoFixVerdict = res.message
                autoFixLog += res.message
                res.remainingIssues.take(10).forEach { autoFixLog += "  • $it" }
                val details = res.cycles.flatMap { c ->
                    listOf("Cycle ${c.cycle}: ${c.summary ?: "-"}") + c.changed.map { "  ✓ $it" } + c.failures.map { "  ✗ $it" } +
                        c.remainingIssues.take(5).map { "  • $it" }
                }
                // Keep the earliest "before" per file so undo restores the pre-fix state.
                val firstBefore = LinkedHashMap<String, ChangeRecord>()
                res.allChanges.forEach { ch ->
                    val prev = firstBefore[ch.path]
                    firstBefore[ch.path] = prev?.copy(after = ch.after) ?: ChangeRecord(ch.path, ch.before, ch.after)
                }
                addHistory("AUTO-FIX", errorText.ifBlank { "Static validation issues" }, res.message,
                    res.verdict == AutoFixEngine.Verdict.VERIFIED, details, firstBefore.values.toList())
                notify(res.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                autoFixLog += "Auto-Fix cancelled"; notify("Auto-Fix cancelled")
            } catch (e: Exception) {
                autoFixLog += "Auto-Fix error: ${e.message}"; notify("Auto-Fix error: ${e.message}")
            } finally {
                autoFixRunning = false; aiBusy = false; aiStatus = null
            }
        }
    }

    fun validateNow(): List<String> {
        val dir = projectDir ?: return emptyList()
        saveAll()
        return autoFix.validate(dir).map { it.toString() }
    }

    // ── Generator (other module) ────────────────────────────────────────────

    fun generateProject(kind: GeneratorKind, prompt: String, name: String, onDone: () -> Unit) {
        if (generating) return
        generating = true
        generatorLog.clear()
        viewModelScope.launch {
            try {
                val result = ProjectGenerator.generate(appCtx, kind, prompt, name) { p ->
                    onMain { generatorLog += p }
                }
                result.onSuccess { dir ->
                    refreshProjects()
                    if (workspace.isInsideRoot(dir)) openProject(dir) else notify("Generated project at ${dir.path}")
                    addHistory("GENERATE", prompt, "Generated ${kind.name.lowercase().replace('_', ' ')} '${dir.name}'", true,
                        generatorLog.toList(), emptyList())
                    onDone()
                }.onFailure { e ->
                    generatorLog += "Failed: ${e.message}"
                    notify("Generation failed: ${e.message}")
                }
            } catch (e: Exception) {
                generatorLog += "Failed: ${e.message}"
                notify("Generation failed: ${e.message}")
            } finally {
                generating = false
            }
        }
    }

    // ── History / undo ──────────────────────────────────────────────────────

    private val rawResponses = HashMap<Long, String>()
    fun rawResponse(id: Long): String? = rawResponses[id]

    private fun addHistory(kind: String, prompt: String, summary: String, ok: Boolean, details: List<String>, changes: List<ChangeRecord>, raw: String? = null) {
        val e = HistoryEntry(System.nanoTime(), System.currentTimeMillis(), kind, prompt, summary, ok, details, changes)
        raw?.let { rawResponses[e.id] = it }
        history.add(0, e)
        while (history.size > 100) history.removeAt(history.lastIndex)
        projectDir?.let { saveHistory(it) }
    }

    fun undo(entry: HistoryEntry) = guard {
        val dir = projectDir ?: return@guard
        if (entry.changes.isEmpty() || entry.undone) { notify("Nothing to undo"); return@guard }
        saveAll()
        val files = entry.changes.map { ch ->
            val f = workspace.resolveInProject(dir, ch.path)
            if (ch.before == null) workspace.delete(f) else workspace.writeText(f, ch.before)
            f
        }
        val idx = history.indexOfFirst { it.id == entry.id }
        if (idx >= 0) history[idx] = entry.copy(undone = true)
        reloadTabs(files); refreshTree()
        addHistory("UNDO", entry.prompt, "Reverted ${entry.kind.lowercase()} in ${entry.changes.joinToString { it.path }}", true, emptyList(), emptyList())
        notify("Reverted ${entry.changes.size} file(s)")
    }

    private fun historyFile(dir: File) = File(appCtx.filesDir, "codestudio_history/${dir.name}.json")

    private fun saveHistory(dir: File) {
        val arr = JSONArray()
        history.take(60).forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id); put("time", h.time); put("kind", h.kind); put("prompt", h.prompt)
                put("summary", h.summary); put("success", h.success); put("undone", h.undone)
                put("details", JSONArray(h.details.take(40)))
            })
        }
        val f = historyFile(dir)
        viewModelScope.launch(Dispatchers.IO) { runCatching { f.parentFile?.mkdirs(); f.writeText(arr.toString()) } }
    }

    private fun loadHistory(dir: File) {
        history.clear()
        val f = historyFile(dir)
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val det = o.optJSONArray("details")
            history += HistoryEntry(
                o.optLong("id"), o.optLong("time"), o.optString("kind"), o.optString("prompt"), o.optString("summary"),
                o.optBoolean("success"), (0 until (det?.length() ?: 0)).map { det!!.optString(it) }, emptyList(), o.optBoolean("undone")
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private inline fun guard(block: () -> Unit) {
        try { block() } catch (e: Exception) { notify(e.message ?: e.javaClass.simpleName) }
    }

    override fun onCleared() { saveAll(); super.onCleared() }

    companion object {
        private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun time(t: Long): String = synchronized(TIME) { TIME.format(Date(t)) }
    }
}
