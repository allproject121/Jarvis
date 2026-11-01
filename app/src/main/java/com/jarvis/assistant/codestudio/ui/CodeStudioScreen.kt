package com.jarvis.assistant.codestudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.codestudio.editor.CodeEditor
import com.jarvis.assistant.codestudio.editor.CodeStudioViewModel
import com.jarvis.assistant.codestudio.editor.EditorTab
import com.jarvis.assistant.codestudio.editor.HistoryEntry
import com.jarvis.assistant.codestudio.editor.ScrollRequest
import com.jarvis.assistant.codestudio.editor.StudioPanel
import com.jarvis.assistant.codestudio.generator.GeneratorKind
import com.jarvis.assistant.codestudio.preview.LivePreviewPanel
import com.jarvis.assistant.codestudio.terminal.TerminalPanel
import com.jarvis.assistant.codestudio.workspace.FileNode
import com.jarvis.assistant.codestudio.workspace.ProjectInfo
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ── Dialog model ────────────────────────────────────────────────────────────

private sealed class StudioDialog {
    data class TextInput(val title: String, val label: String, val initial: String, val confirm: String, val onOk: (String) -> Unit) : StudioDialog()
    data class Confirm(val title: String, val text: String, val confirm: String, val onOk: () -> Unit) : StudioDialog()
    data object NewProject : StudioDialog()
    data object Generate : StudioDialog()
    data class AutoFix(val prefill: String) : StudioDialog()
    data class RawReply(val text: String) : StudioDialog()
}

@Composable
fun CodeStudioScreen(vm: CodeStudioViewModel, onExit: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<StudioDialog?>(null) }
    val clipboard = LocalClipboardManager.current
    val snackScope = rememberCoroutineScope()

    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(m)
        }
        vm.message = null
    }

    val copy: (String, String) -> Unit = { text, what ->
        clipboard.setText(AnnotatedString(text)); vm.notify("$what copied")
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (vm.projectDir == null) {
            BackHandler { onExit() }
            ProjectsHome(
                vm = vm,
                onExit = onExit,
                onNew = { dialog = StudioDialog.NewProject },
                onGenerate = { dialog = StudioDialog.Generate },
                onRename = { p -> dialog = StudioDialog.TextInput("Rename project", "New name", p.name, "Rename") { vm.renameProject(p.dir, it) } },
                onDelete = { p -> dialog = StudioDialog.Confirm("Delete project?", "\"${p.name}\" and all its files will be permanently deleted.", "Delete") { vm.deleteProject(p.dir) } },
                onCopyPath = { p -> copy(p.dir.absolutePath, "Absolute path") }
            )
        } else {
            Workspace(
                vm = vm,
                onExit = onExit,
                showDialog = { dialog = it },
                copy = copy
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    when (val d = dialog) {
        null -> Unit
        is StudioDialog.TextInput -> TextInputDialog(d.title, d.label, d.initial, d.confirm, onDismiss = { dialog = null }) { dialog = null; d.onOk(it) }
        is StudioDialog.Confirm -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = { dialog = null; d.onOk() }) { Text(d.confirm, color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        StudioDialog.NewProject -> NewProjectDialog(onDismiss = { dialog = null }) { name, starter -> dialog = null; vm.createProject(name, starter) }
        StudioDialog.Generate -> GenerateDialog(vm, onDismiss = { dialog = null })
        is StudioDialog.AutoFix -> AutoFixDialog(vm, d.prefill, onDismiss = { dialog = null })
        is StudioDialog.RawReply -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("AI reply") },
            text = {
                SelectionContainer {
                    Text(d.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScrollCompat())
                }
            },
            confirmButton = { TextButton(onClick = { copy(d.text, "AI reply"); dialog = null }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())

// ── Projects home ───────────────────────────────────────────────────────────

@Composable
private fun ProjectsHome(
    vm: CodeStudioViewModel,
    onExit: () -> Unit,
    onNew: () -> Unit,
    onGenerate: () -> Unit,
    onRename: (ProjectInfo) -> Unit,
    onDelete: (ProjectInfo) -> Unit,
    onCopyPath: (ProjectInfo) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("CODE STUDIO", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 17.sp)
                Text("AI coding IDE • ${vm.projects.size} project(s)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
            IconButton(onClick = { vm.refreshProjects() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        vm.aiProviderWarning()?.let { WarningBanner(it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNew, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New Project")
            }
            OutlinedButton(onClick = onGenerate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New with AI")
            }
        }
        if (vm.projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Filled.Code, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("No projects yet", fontWeight = FontWeight.SemiBold)
                    Text("Create a project, or describe one and let AI generate it.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.projects, key = { it.dir.absolutePath }) { p ->
                    ProjectCard(p, onOpen = { vm.openProject(p.dir) }, onRename = { onRename(p) }, onDelete = { onDelete(p) }, onCopyPath = { onCopyPath(p) })
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(p: ProjectInfo, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onCopyPath: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.fileCount} file(s) • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastModified))}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Project actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("Open", Icons.Filled.FolderOpen) { menu = false; onOpen() }
                    MenuItem("Rename", Icons.Filled.Edit) { menu = false; onRename() }
                    MenuItem("Copy Absolute Path", Icons.Filled.ContentCopy) { menu = false; onCopyPath() }
                    MenuItem("Delete", Icons.Filled.Delete, danger = true) { menu = false; onDelete() }
                }
            }
        }
    }
}

// ── Workspace ───────────────────────────────────────────────────────────────

@Composable
private fun Workspace(
    vm: CodeStudioViewModel,
    onExit: () -> Unit,
    showDialog: (StudioDialog) -> Unit,
    copy: (String, String) -> Unit
) {
    val dir = vm.projectDir ?: return
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            vm.fullscreen -> vm.fullscreen = false
            vm.panel != StudioPanel.EDITOR -> vm.panel = StudioPanel.EDITOR
            else -> vm.closeProject()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                FileTree(
                    vm = vm,
                    projectDir = dir,
                    onOpenFile = { vm.openFile(it); scope.launch { drawerState.close() } },
                    showDialog = showDialog,
                    copy = copy
                )
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (!vm.fullscreen) {
                TopBar(vm, dir, onMenu = { scope.launch { drawerState.open() } }, onExit = onExit, showDialog = showDialog, copy = copy)
                PanelSwitcher(vm)
                if (vm.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.panel) {
                    StudioPanel.EDITOR -> EditorPanel(vm, copy)
                    StudioPanel.PREVIEW -> LivePreviewPanel(
                        projectDir = dir,
                        modifier = Modifier.fillMaxSize(),
                        onConsoleLine = { line -> vm.log("preview", line) }
                    )
                    StudioPanel.TERMINAL -> TerminalPanel(workDir = dir, modifier = Modifier.fillMaxSize())
                    StudioPanel.CONSOLE -> ConsolePanel(vm, copy, onFix = { showDialog(StudioDialog.AutoFix(vm.recentErrors())) })
                    StudioPanel.HISTORY -> HistoryPanel(vm, onRaw = { showDialog(StudioDialog.RawReply(it)) })
                }
            }
            if (!vm.fullscreen && (vm.panel == StudioPanel.EDITOR || vm.panel == StudioPanel.CONSOLE || vm.panel == StudioPanel.HISTORY)) {
                AiEditBar(vm, onAutoFix = { showDialog(StudioDialog.AutoFix(vm.recentErrors())) })
            }
        }
    }
}

@Composable
private fun TopBar(
    vm: CodeStudioViewModel,
    dir: File,
    onMenu: () -> Unit,
    onExit: () -> Unit,
    showDialog: (StudioDialog) -> Unit,
    copy: (String, String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Files") }
        Column(Modifier.weight(1f)) {
            Text(dir.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            Text(vm.aiStatus ?: (vm.current?.path ?: "No file open"), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (vm.aiStatus != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        IconButton(onClick = { vm.saveAll(); vm.panel = StudioPanel.PREVIEW }) { Icon(Icons.Filled.PlayArrow, "Preview") }
        IconButton(onClick = { vm.saveAll(); vm.panel = StudioPanel.TERMINAL }) { Icon(Icons.Filled.Terminal, "Terminal") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Save all", Icons.Filled.Save) { menu = false; vm.saveAll(); vm.notify("All files saved") }
                MenuItem("Auto-Fix with AI", Icons.Filled.AutoFixHigh) { menu = false; showDialog(StudioDialog.AutoFix(vm.recentErrors())) }
                MenuItem("Validate project", Icons.Filled.Verified) {
                    menu = false
                    val issues = vm.validateNow()
                    if (issues.isEmpty()) vm.notify("Validation passed — no issues found")
                    else { issues.forEach { vm.log("validate", it) }; vm.panel = StudioPanel.CONSOLE; vm.notify("${issues.size} issue(s) found") }
                }
                MenuItem("New File", Icons.Filled.PostAdd) {
                    menu = false
                    showDialog(StudioDialog.TextInput("New file", "File name (e.g. js/app.js)", "", "Create") { vm.createFile(dir, it) })
                }
                MenuItem("New Folder", Icons.Filled.CreateNewFolder) {
                    menu = false
                    showDialog(StudioDialog.TextInput("New folder", "Folder name", "", "Create") { vm.createFolder(dir, it) })
                }
                MenuItem("Copy Absolute Path", Icons.Filled.ContentCopy) { menu = false; copy(dir.absolutePath, "Absolute path") }
                MenuItem("Close project", Icons.Filled.Close) { menu = false; vm.closeProject() }
                MenuItem("Exit Code Studio", Icons.AutoMirrored.Filled.ArrowBack) { menu = false; vm.saveAll(); onExit() }
            }
        }
    }
}

@Composable
private fun PanelSwitcher(vm: CodeStudioViewModel) {
    val errors = vm.console.count { it.isError }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PanelChip(vm, StudioPanel.EDITOR, "Editor", Icons.Filled.Code)
        PanelChip(vm, StudioPanel.PREVIEW, "Preview", Icons.Filled.PlayArrow)
        PanelChip(vm, StudioPanel.TERMINAL, "Terminal", Icons.Filled.Terminal)
        PanelChip(vm, StudioPanel.CONSOLE, if (errors > 0) "Console ($errors)" else "Console", Icons.AutoMirrored.Filled.List)
        PanelChip(vm, StudioPanel.HISTORY, "AI History", Icons.Filled.History)
    }
}

@Composable
private fun PanelChip(vm: CodeStudioViewModel, p: StudioPanel, label: String, icon: ImageVector) {
    FilterChip(
        selected = vm.panel == p,
        onClick = { if (p == StudioPanel.PREVIEW || p == StudioPanel.TERMINAL) vm.saveAll(); vm.panel = p },
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }
    )
}

// ── File tree ───────────────────────────────────────────────────────────────

@Composable
private fun FileTree(
    vm: CodeStudioViewModel,
    projectDir: File,
    onOpenFile: (File) -> Unit,
    showDialog: (StudioDialog) -> Unit,
    copy: (String, String) -> Unit
) {
    Column(Modifier.fillMaxHeight().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PROJECT", fontSize = 11.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
                Text(projectDir.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                showDialog(StudioDialog.TextInput("New file", "File name (e.g. css/main.css)", "", "Create") { vm.createFile(projectDir, it) })
            }) { Icon(Icons.Filled.PostAdd, "New File") }
            IconButton(onClick = {
                showDialog(StudioDialog.TextInput("New folder", "Folder name", "", "Create") { vm.createFolder(projectDir, it) })
            }) { Icon(Icons.Filled.CreateNewFolder, "New Folder") }
            IconButton(onClick = { vm.refreshTree() }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.tree, key = { it.relativePath }) { node ->
                FileRow(
                    node = node,
                    expanded = node.relativePath !in vm.collapsed,
                    active = vm.current?.file?.absolutePath == node.file.absolutePath,
                    onClick = { if (node.isDirectory) vm.toggleFolder(node) else onOpenFile(node.file) },
                    onNewFile = {
                        showDialog(StudioDialog.TextInput("New file in ${node.name}", "File name", "", "Create") { vm.createFile(node.file, it) })
                    },
                    onNewFolder = {
                        showDialog(StudioDialog.TextInput("New folder in ${node.name}", "Folder name", "", "Create") { vm.createFolder(node.file, it) })
                    },
                    onRename = {
                        showDialog(StudioDialog.TextInput("Rename", "New name", node.name, "Rename") { vm.renameNode(node.file, it) })
                    },
                    onDelete = {
                        showDialog(StudioDialog.Confirm("Delete ${node.name}?",
                            if (node.isDirectory) "The folder and everything inside it will be deleted." else "This file will be deleted.", "Delete") {
                            vm.deleteNode(node.file)
                        })
                    },
                    onCopyPath = { copy(node.file.absolutePath, "Absolute path") }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    node: FileNode,
    expanded: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = (8 + node.depth * 14).dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
            Icon(if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(16.dp))
            Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = fileTint(node.name))
        }
        Spacer(Modifier.width(8.dp))
        Text(node.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Actions", Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (node.isDirectory) {
                    MenuItem("New File here", Icons.Filled.PostAdd) { menu = false; onNewFile() }
                    MenuItem("New Folder here", Icons.Filled.CreateNewFolder) { menu = false; onNewFolder() }
                }
                MenuItem("Rename", Icons.Filled.Edit) { menu = false; onRename() }
                MenuItem("Copy Absolute Path", Icons.Filled.ContentCopy) { menu = false; onCopyPath() }
                MenuItem("Delete", Icons.Filled.Delete, danger = true) { menu = false; onDelete() }
            }
        }
    }
}

private fun fileTint(name: String): Color = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> Color(0xFFE44D26)
    "css", "scss" -> Color(0xFF2965F1)
    "js", "mjs", "jsx" -> Color(0xFFF0DB4F)
    "ts", "tsx" -> Color(0xFF3178C6)
    "kt", "kts" -> Color(0xFFA97BFF)
    "java" -> Color(0xFFE76F00)
    "py" -> Color(0xFF4B8BBE)
    "json" -> Color(0xFF8BC34A)
    "md" -> Color(0xFF90A4AE)
    "xml", "svg" -> Color(0xFFFF9800)
    else -> Color(0xFF9E9E9E)
}

// ── Editor panel ────────────────────────────────────────────────────────────

@Composable
private fun EditorPanel(vm: CodeStudioViewModel, copy: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (!vm.fullscreen && vm.tabs.isNotEmpty()) TabStrip(vm)
        val tab = vm.current
        if (tab == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Filled.Description, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                    Text("Open a file from the ☰ file tree", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                }
            }
        } else {
            EditorWithFind(vm, tab, copy)
        }
    }
}

@Composable
private fun TabStrip(vm: CodeStudioViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
        vm.tabs.forEachIndexed { i, t ->
            val selected = i == vm.activeTab
            Row(
                Modifier.padding(end = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .clickable { vm.selectTab(i) }
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (t.dirty) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                if (t.dirty) Spacer(Modifier.width(5.dp))
                Text(t.file.name, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
private fun EditorWithFind(vm: CodeStudioViewModel, tab: EditorTab, copy: (String, String) -> Unit) {
    var findOpen by remember(tab) { mutableStateOf(false) }
    var replaceOpen by remember(tab) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var current by remember(tab) { mutableIntStateOf(0) }
    var scrollReq by remember(tab) { mutableStateOf<ScrollRequest?>(null) }
    var fontSize by remember { mutableStateOf(13f) }

    val text = tab.value.text
    val matches = remember(text, query, matchCase, findOpen) {
        if (!findOpen || query.isEmpty()) emptyList()
        else {
            val out = ArrayList<IntRange>()
            var idx = text.indexOf(query, 0, ignoreCase = !matchCase)
            while (idx >= 0 && out.size < 5000) { out += idx until idx + query.length; idx = text.indexOf(query, idx + query.length.coerceAtLeast(1), ignoreCase = !matchCase) }
            out
        }
    }
    val cur = if (matches.isEmpty()) -1 else current.coerceIn(0, matches.lastIndex)

    fun goTo(i: Int) {
        if (matches.isEmpty()) return
        val n = ((i % matches.size) + matches.size) % matches.size
        current = n
        val r = matches[n]
        tab.value = tab.value.copy(selection = TextRange(r.first, r.last + 1))
        scrollReq = ScrollRequest(r.first)
    }

    Column(Modifier.fillMaxSize()) {
        // Editor toolbar
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.language.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text("${text.count { it == '\n' } + 1} lines", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            Spacer(Modifier.weight(1f))
            SmallIcon(Icons.Filled.Search, "Find in file") { findOpen = !findOpen || replaceOpen; replaceOpen = false }
            SmallIcon(Icons.Filled.FindReplace, "Find & Replace") { findOpen = true; replaceOpen = !replaceOpen }
            SmallIcon(Icons.Filled.ContentCopy, "Copy code") { copy(text, "Code") }
            SmallIcon(Icons.Filled.Save, "Save") { vm.saveCurrent() }
            SmallText("A−") { fontSize = (fontSize - 1f).coerceAtLeast(9f) }
            SmallText("A+") { fontSize = (fontSize + 1f).coerceAtMost(22f) }
            SmallIcon(if (vm.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Expand") { vm.fullscreen = !vm.fullscreen }
            SmallIcon(Icons.Filled.Close, "Close tab") { vm.closeTab(vm.activeTab) }
        }
        if (findOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactField(query, { query = it; current = 0 }, "Find", Modifier.weight(1f))
                    Text(if (matches.isEmpty()) "0/0" else "${cur + 1}/${matches.size}", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    SmallIcon(Icons.Filled.KeyboardArrowUp, "Previous") { goTo(cur - 1) }
                    SmallIcon(Icons.Filled.KeyboardArrowDown, "Next") { goTo(cur + 1) }
                    SmallText(if (matchCase) "Aa✓" else "Aa") { matchCase = !matchCase }
                    SmallIcon(Icons.Filled.Close, "Close find") { findOpen = false; replaceOpen = false }
                }
                if (replaceOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        CompactField(replacement, { replacement = it }, "Replace with", Modifier.weight(1f))
                        TextButton(onClick = {
                            if (matches.isNotEmpty()) {
                                val r = matches[cur]
                                val nt = text.replaceRange(r.first, r.last + 1, replacement)
                                tab.value = tab.value.copy(text = nt, selection = TextRange(r.first + replacement.length))
                            }
                        }) { Text("Replace", fontSize = 12.sp) }
                        TextButton(onClick = {
                            if (query.isNotEmpty()) {
                                val count = matches.size
                                val nt = text.replace(query, replacement, ignoreCase = !matchCase)
                                tab.value = tab.value.copy(text = nt, selection = TextRange(0))
                                vm.notify("Replaced $count occurrence(s)")
                            }
                        }) { Text("All", fontSize = 12.sp) }
                    }
                }
            }
        }
        CodeEditor(
            value = tab.value,
            onValueChange = { vm.updateText(tab, it) },
            language = tab.language,
            matches = matches,
            currentMatch = cur,
            scrollRequest = scrollReq,
            fontSizeSp = fontSize,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── AI edit bar ─────────────────────────────────────────────────────────────

@Composable
private fun AiEditBar(vm: CodeStudioViewModel, onAutoFix: () -> Unit) {
    var instruction by remember { mutableStateOf("") }
    val hasSelection = vm.current?.value?.selection?.collapsed == false
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAutoFix, enabled = !vm.aiBusy) { Icon(Icons.Filled.AutoFixHigh, "Fix with AI", tint = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                placeholder = {
                    Text(if (hasSelection) "Ask AI to edit the selection…" else "Ask AI to edit this code (e.g. fix bug, add styles)",
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                enabled = !vm.aiBusy,
                modifier = Modifier.weight(1f)
            )
            if (vm.aiBusy) {
                IconButton(onClick = { vm.cancelAi() }) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Icon(Icons.Filled.Stop, "Cancel", Modifier.size(14.dp))
                    }
                }
            } else {
                IconButton(onClick = { vm.aiEdit(instruction); instruction = "" }, enabled = instruction.isNotBlank() && vm.projectDir != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ── Console ─────────────────────────────────────────────────────────────────

@Composable
private fun ConsolePanel(vm: CodeStudioViewModel, copy: (String, String) -> Unit, onFix: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(vm.console.size) { if (vm.console.isNotEmpty()) list.scrollToItem(vm.console.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONSOLE LOGS", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onFix, enabled = !vm.aiBusy) {
                Icon(Icons.Filled.BugReport, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Fix with AI", fontSize = 12.sp)
            }
            SmallIcon(Icons.Filled.ContentCopy, "Copy logs") { copy(vm.console.joinToString("\n") { "[${it.source}] ${it.text}" }, "Logs") }
            SmallIcon(Icons.Filled.Delete, "Clear") { vm.console.clear() }
        }
        if (vm.console.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet. Run Preview to capture console output.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(24.dp))
            }
        } else {
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    itemsIndexed(vm.console) { _, line ->
                        Text(
                            "${CodeStudioViewModel.time(line.time)} [${line.source}] ${line.text}",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── History ─────────────────────────────────────────────────────────────────

@Composable
private fun HistoryPanel(vm: CodeStudioViewModel, onRaw: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("AI CONVERSATION & ACTIONS", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
        if (vm.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No AI actions yet. Use the AI bar below to edit code.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.history, key = { "${it.id}-${it.time}" }) { h -> HistoryCard(vm, h, onRaw) }
            }
        }
    }
}

@Composable
private fun HistoryCard(vm: CodeStudioViewModel, h: HistoryEntry, onRaw: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (h.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.kind, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Text(CodeStudioViewModel.time(h.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                if (h.undone) { Spacer(Modifier.width(8.dp)); Text("UNDONE", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                if (h.changes.isNotEmpty() && !h.undone) {
                    TextButton(onClick = { vm.undo(h) }) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Undo", fontSize = 12.sp) }
                }
            }
            Text("You: ${h.prompt}", fontSize = 13.sp, maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis)
            Text("AI: ${h.summary}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = if (expanded) 20 else 3, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                h.details.forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                vm.rawResponse(h.id)?.let { raw -> TextButton(onClick = { onRaw(raw) }) { Text("View AI reply", fontSize = 12.sp) } }
            }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

@Composable
private fun TextInputDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onOk(v.trim()) }, enabled = v.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onOk: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var starter by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { starter = !starter }) {
                    Checkbox(checked = starter, onCheckedChange = { starter = it })
                    Text("Add starter files (index.html, style.css, script.js)", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim(), starter) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GenerateDialog(vm: CodeStudioViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(GeneratorKind.WEB_APP) }
    AlertDialog(
        onDismissRequest = { if (!vm.generating) onDismiss() },
        title = { Text("New project with AI") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScrollCompat()) {
                vm.aiProviderWarning()?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true, enabled = !vm.generating)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GeneratorKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, enabled = !vm.generating,
                            label = { Text(k.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe what to build") },
                    minLines = 3, maxLines = 6, enabled = !vm.generating)
                if (vm.generating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                vm.generatorLog.takeLast(8).forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.generateProject(kind, prompt.trim(), name.trim().ifBlank { "ai-project" }) { onDismiss() } },
                enabled = !vm.generating && prompt.isNotBlank()
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.generating) { Text("Cancel") } }
    )
}

@Composable
private fun AutoFixDialog(vm: CodeStudioViewModel, prefill: String, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf(prefill) }
    var cycles by remember { mutableStateOf(3f) }
    AlertDialog(
        onDismissRequest = { if (!vm.autoFixRunning) onDismiss() },
        title = { Text("AUTO-FIX") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScrollCompat()) {
                Text("Analyzes the project tree + files and the error, applies a surgical AI patch, validates, and retries if needed.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                vm.aiProviderWarning()?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = error, onValueChange = { error = it }, label = { Text("Error / problem (optional)") },
                    minLines = 3, maxLines = 8, enabled = !vm.autoFixRunning, textStyle = MaterialTheme.typography.bodySmall)
                Text("Max cycles: ${cycles.toInt()}", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(value = cycles, onValueChange = { cycles = it }, valueRange = 1f..5f, steps = 3, enabled = !vm.autoFixRunning)
                if (vm.autoFixRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.autoFixVerdict?.let { v ->
                    val ok = v.startsWith("AUTO-FIX VERIFIED") || v.startsWith("No errors")
                    Text(v, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                }
                vm.autoFixLog.takeLast(30).forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            if (vm.autoFixRunning) TextButton(onClick = { vm.cancelAi() }) { Text("Stop") }
            else TextButton(onClick = { vm.runAutoFix(error, cycles.toInt()) }) { Text(if (vm.autoFixVerdict == null) "Run Auto-Fix" else "Run again") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.autoFixRunning) { Text("Close") } }
    )
}

// ── Small building blocks ───────────────────────────────────────────────────

@Composable
private fun WarningBanner(text: String) {
    Text(
        text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)).padding(10.dp)
    )
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(text = { Text(text, color = c) }, leadingIcon = { Icon(icon, null, tint = c) }, onClick = onClick)
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { Icon(icon, desc, Modifier.size(18.dp)) }
}

@Composable
private fun SmallText(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp))
}

@Composable
private fun CompactField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                inner()
            }
        }
    )
}
