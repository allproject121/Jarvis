package com.jarvis.assistant.ui.compose.feature.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ai.router.AiModelMode
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.model.ChatFontSize
import com.jarvis.assistant.ui.compose.model.ThemeMode
import com.jarvis.assistant.ui.compose.model.UiDensity
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.settings.PrivacyShieldActivity
import com.jarvis.assistant.ui.settings.SettingsActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

/** Which modal is open on the settings screen (null = none). */
private enum class SettingsDialog {
    USER_NAME, ASSISTANT_NAME, PERSONALITY, AI_MODE, GEMINI_MODEL, GEMINI_KEY,
    OPENAI_KEY, ENDPOINT, OLLAMA_MODEL, VOICE, LANGUAGE, DELETE_MEMORIES
}

/** Runtime permissions Jarvis' tools need (all declared in the manifest). */
private val toolPermissionGroups: List<Pair<String, List<String>>> = listOf(
    "Microphone" to listOf(Manifest.permission.RECORD_AUDIO),
    "SMS" to listOf(Manifest.permission.SEND_SMS),
    "Contacts" to listOf(Manifest.permission.READ_CONTACTS),
    "Phone calls" to listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE),
    "Call log" to listOf(Manifest.permission.READ_CALL_LOG),
    "Location" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    "Camera" to listOf(Manifest.permission.CAMERA)
)

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.launchSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

/** Opens the classic (XML) Jarvis settings screen hosted by SettingsActivity. */
private fun Context.openClassicSettings() = launchSafely(Intent(this, SettingsActivity::class.java))

private fun jarvisThemeDisplayName(): String =
    when (val key = com.jarvis.assistant.util.ThemeManager.currentThemeKey()) {
        com.jarvis.assistant.util.ThemeManager.THEME_ANDROLLM_NIGHT -> "AndroLLM Night"
        com.jarvis.assistant.util.ThemeManager.THEME_ANDROLLM_PARCHMENT -> "AndroLLM Parchment"
        "pearl_white" -> "Pearl White"
        "dark" -> "Stark Navy"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

@Suppress("unused")
private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}

private fun UiDensity.displayName(): String = when (this) {
    UiDensity.COMPACT -> "Compact"
    UiDensity.DEFAULT -> "Default"
    UiDensity.COMFORTABLE -> "Comfortable"
}

private fun ChatFontSize.displayName(): String = when (this) {
    ChatFontSize.SMALL -> "Small"
    ChatFontSize.MEDIUM -> "Medium"
    ChatFontSize.LARGE -> "Large"
}

private fun AiModelMode.shortName(): String = displayName.substringBefore(" (")

private fun geminiModelLabel(id: String): String = id.removePrefix("models/")

/**
 * Compose Settings (ported from AndroLLM feature/settings) wired to Jarvis'
 * real preferences ([com.jarvis.assistant.data.preferences.AppPreferences]),
 * MCP manager, model router and local model manager.
 */
@Composable
fun SettingsRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(app) }
    SettingsScreen(viewModel = viewModel, navigate = navigate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    navigate: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val mcpToolCount by viewModel.mcpToolCount.collectAsStateWithLifecycle()
    val sessionOn by viewModel.voiceSessionOn.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.voiceConnectionStatus.collectAsStateWithLifecycle()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var permissionTick by remember { mutableIntStateOf(0) }
    var micDenied by remember { mutableStateOf(false) }

    fun toggleGroup(group: SettingsGroup) {
        expandedGroup = if (expandedGroup == group.name) null else group.name
    }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) return@LaunchedEffect
        val match = SettingsGroup.entries.firstOrNull { it.matches(q) }
        if (match != null) expandedGroup = match.name
    }

    // Re-read prefs / permission / service state every time Settings resumes
    // (e.g. returning from the classic settings or system permission pages).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                permissionTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toolPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionTick++ }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionTick++
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            micDenied = false
            viewModel.startVoiceSession()
        } else {
            micDenied = true
        }
    }

    fun requestVoiceStart() {
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            viewModel.startVoiceSession()
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        voicePermissionLauncher.launch(needed.toTypedArray())
    }

    CloudAtmosphericBackground(reduceMotion = state.reduceMotion) {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.SETTINGS,
            onTabSelected = { tab -> if (tab.route != ComposeRoutes.SETTINGS) navigate(tab.route) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.deskPaper
                            )
                        )
                    },
                    actions = {
                        TextButton(onClick = { context.openClassicSettings() }) {
                            Text("Classic", color = MaterialTheme.ledger.lampGlow)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "settings-search") {
                    SettingsSearchField(query = searchQuery, onQueryChange = { searchQuery = it })
                }

                message?.let { text ->
                    item(key = "settings-message") {
                        CloudChip(text = text, accentColor = MaterialTheme.ledger.lampGlow)
                    }
                }

                item(key = "settings-quick-actions") {
                    SettingsQuickActions(
                        actions = listOf(
                            QuickAction(Icons.Filled.Cached, "Clear Cache") { viewModel.clearCache() },
                            QuickAction(Icons.Filled.IosShare, "Export Memory") { viewModel.exportMemories() },
                            QuickAction(Icons.Filled.Insights, "Developer Mode") { navigate(ComposeRoutes.DEVELOPER) },
                            QuickAction(Icons.Filled.Settings, "Classic Jarvis Settings") { context.openClassicSettings() }
                        )
                    )
                }

                // ── Account & Profile ───────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Account,
                    icon = Icons.Filled.Person,
                    expanded = expandedGroup == SettingsGroup.Account.name,
                    onToggle = { toggleGroup(SettingsGroup.Account) },
                    visible = SettingsGroup.Account.matches(searchQuery),
                    subtitle = state.userName.ifBlank { "Local profile" },
                    reduceMotion = state.reduceMotion
                ) {
                    UserProfileCard(state = state, onClick = { dialog = SettingsDialog.USER_NAME })
                    UserStatsRow(state = state)
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Person,
                                title = "Your name",
                                value = state.userName,
                                onClick = { dialog = SettingsDialog.USER_NAME }
                            )
                            SettingRow(
                                icon = Icons.Filled.RecordVoiceOver,
                                title = "Assistant name",
                                value = state.assistantName,
                                onClick = { dialog = SettingsDialog.ASSISTANT_NAME }
                            )
                            SettingRow(
                                icon = Icons.Filled.Psychology,
                                title = "Personality",
                                value = state.personality,
                                onClick = { dialog = SettingsDialog.PERSONALITY }
                            )
                        }
                    }
                }

                // ── AI Engine & API keys ────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.AiEngine,
                    icon = Icons.Filled.CloudDone,
                    expanded = expandedGroup == SettingsGroup.AiEngine.name,
                    onToggle = { toggleGroup(SettingsGroup.AiEngine) },
                    visible = SettingsGroup.AiEngine.matches(searchQuery),
                    subtitle = state.aiMode.shortName(),
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Bolt,
                                title = "AI engine",
                                value = state.aiMode.shortName(),
                                onClick = { dialog = SettingsDialog.AI_MODE }
                            )
                            Text(
                                text = state.aiMode.description,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            SettingRow(
                                icon = Icons.Filled.Insights,
                                title = "Last active provider",
                                value = state.activeProvider.ifBlank { "—" },
                                onClick = { viewModel.refresh() }
                            )
                        }
                    }
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            CardTitle("Gemini Live")
                            SettingRow(
                                icon = Icons.Filled.Tune,
                                title = "Model",
                                value = geminiModelLabel(state.geminiModel),
                                onClick = { dialog = SettingsDialog.GEMINI_MODEL }
                            )
                            SettingRow(
                                icon = Icons.Filled.VpnKey,
                                title = "Gemini API key",
                                value = if (state.geminiKeySet) "Saved" else "Not set",
                                enabled = state.canStoreKeySecurely,
                                onClick = { dialog = SettingsDialog.GEMINI_KEY }
                            )
                            if (!state.canStoreKeySecurely) {
                                Text(
                                    text = "Secure key storage is unavailable on this device.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.emberRed),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            CardTitle("ChatGPT (OpenAI)")
                            SettingRow(
                                icon = Icons.Filled.VpnKey,
                                title = "OpenAI API key",
                                value = if (state.openAiKeySet) "Saved" else "Not set",
                                onClick = { dialog = SettingsDialog.OPENAI_KEY }
                            )
                        }
                    }
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            CardTitle("Local host (Ollama)")
                            SettingRow(
                                icon = Icons.Filled.Dns,
                                title = "Endpoint",
                                value = state.customEndpoint,
                                onClick = { dialog = SettingsDialog.ENDPOINT }
                            )
                            SettingRow(
                                icon = Icons.Filled.Tune,
                                title = "Model tag",
                                value = state.localHostModel,
                                onClick = { dialog = SettingsDialog.OLLAMA_MODEL }
                            )
                        }
                    }
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            CardTitle("On-device models (llama.cpp)")
                            if (state.localModels.isEmpty()) {
                                Text(
                                    text = "…",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            state.localModels.forEach { model ->
                                LocalModelRowView(
                                    model = model,
                                    active = model.id == state.activeLocalModelId,
                                    onSelect = { viewModel.selectLocalModel(model.id) },
                                    onDownload = { viewModel.downloadLocalModel(model.id) }
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            SettingRow(
                                icon = Icons.Filled.Speed,
                                title = "Recommended threads / context",
                                value = if (state.recommendedThreads > 0) {
                                    "${state.recommendedThreads} threads • ${state.recommendedContext} tokens"
                                } else "…",
                                onClick = { viewModel.refreshStorage() }
                            )
                            UnavailableRow(
                                title = "Sampling parameters",
                                detail = "Temperature, top-k/top-p and max tokens are fixed by Jarvis' local engine"
                            )
                        }
                    }
                }

                // ── Appearance ──────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Appearance,
                    icon = Icons.Filled.Palette,
                    expanded = expandedGroup == SettingsGroup.Appearance.name,
                    onToggle = { toggleGroup(SettingsGroup.Appearance) },
                    visible = SettingsGroup.Appearance.matches(searchQuery),
                    subtitle = "Display",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Palette,
                                title = "Theme",
                                // One theme for the whole app: the Jarvis theme picker
                                // (incl. AndroLLM Night / Parchment) drives Compose too.
                                value = jarvisThemeDisplayName(),
                                onClick = {
                                    context.launchSafely(
                                        Intent(context, com.jarvis.assistant.ui.settings.CustomizationStudioActivity::class.java)
                                    )
                                }
                            )
                            SettingRow(
                                icon = Icons.Filled.TextFields,
                                title = "Text Size",
                                value = state.fontSize.displayName(),
                                onClick = { viewModel.cycleFontSize() }
                            )
                            SettingRow(
                                icon = Icons.Filled.SpaceBar,
                                title = "Interface Density",
                                value = state.uiDensity.displayName(),
                                onClick = { viewModel.cycleDensity() }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f))
                            ToggleRow(
                                icon = Icons.Filled.MotionPhotosOn,
                                title = "Reduce Background Motion",
                                subtitle = "Hold the atmospheric background still",
                                checked = state.reduceMotion,
                                onCheckedChange = { viewModel.setReduceMotion(it) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.BlurOn,
                                title = "Glass blur",
                                subtitle = "Liquid-glass blur in the classic Jarvis screens",
                                checked = state.blurEnabled,
                                onCheckedChange = { viewModel.setBlur(it) }
                            )
                            UnavailableRow(title = "Chat Wallpaper")
                            SettingRow(
                                icon = Icons.Filled.OpenInNew,
                                title = "Classic themes & studio",
                                value = "Open classic settings",
                                onClick = { context.openClassicSettings() }
                            )
                        }
                    }
                }

                // ── Storage ─────────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Storage,
                    icon = Icons.Filled.Storage,
                    expanded = expandedGroup == SettingsGroup.Storage.name,
                    onToggle = { toggleGroup(SettingsGroup.Storage) },
                    visible = SettingsGroup.Storage.matches(searchQuery),
                    subtitle = state.freeBytes?.let { "${formatBytes(it)} free" },
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = "Free Space",
                                value = state.freeBytes?.let { "${formatBytes(it)} free" } ?: "…",
                                onClick = { viewModel.refreshStorage() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = "Models on Device",
                                value = state.modelBytes?.let { formatBytes(it) } ?: "…",
                                onClick = { viewModel.refreshStorage() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Cached,
                                title = "App cache",
                                value = state.cacheBytes?.let { formatBytes(it) } ?: "…",
                                onClick = { viewModel.refreshStorage() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Delete,
                                title = "Clear cache",
                                onClick = { viewModel.clearCache() }
                            )
                        }
                    }
                }

                // ── Memory ──────────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Memory,
                    icon = Icons.Filled.Psychology,
                    expanded = expandedGroup == SettingsGroup.Memory.name,
                    onToggle = { toggleGroup(SettingsGroup.Memory) },
                    visible = SettingsGroup.Memory.matches(searchQuery),
                    subtitle = if (state.memoryEnabled) "On-device" else "Off",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ToggleRow(
                                icon = Icons.Filled.Memory,
                                title = "Long-term Memory",
                                subtitle = "Jarvis remembers facts from your conversations (stored on this device). Off: the memory tools neither save nor recall.",
                                checked = state.memoryEnabled,
                                onCheckedChange = { viewModel.setMemoryEnabled(it) }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.memoryCount?.let { "$it memories • ${state.memoryCategories} categories" } ?: "…",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.refreshMemoryStats() }) {
                                    Text("Refresh", color = MaterialTheme.ledger.lampGlow)
                                }
                            }
                            SettingRow(
                                icon = Icons.Filled.IosShare,
                                title = "Export memories",
                                onClick = { viewModel.exportMemories() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Psychology,
                                title = "Memory Inspector",
                                value = "Open developer dashboard",
                                onClick = { navigate(ComposeRoutes.DEVELOPER) }
                            )
                            SettingRow(
                                icon = Icons.Filled.OpenInNew,
                                title = "Browse & edit memories",
                                value = "Jarvis Memory",
                                onClick = { context.launchSafely(Intent(context, MemoryActivity::class.java)) }
                            )
                            SettingRow(
                                icon = Icons.Filled.Delete,
                                title = "Delete all memories",
                                onClick = { dialog = SettingsDialog.DELETE_MEMORIES }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            Text(
                                text = "Similarity threshold, retrieval count, embedding source and memory import are in AndroLLM features → Memory, below.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // ── Voice Assistant ─────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.VoiceAssistant,
                    icon = Icons.Filled.RecordVoiceOver,
                    expanded = expandedGroup == SettingsGroup.VoiceAssistant.name,
                    onToggle = { toggleGroup(SettingsGroup.VoiceAssistant) },
                    visible = SettingsGroup.VoiceAssistant.matches(searchQuery),
                    subtitle = if (sessionOn) "Listening" else "Off",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = null,
                                    tint = if (sessionOn) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Voice Assistant",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.ledger.deskPaper
                                        )
                                    )
                                    Text(
                                        text = if (sessionOn) "Session active • $connectionStatus" else "Idle • $connectionStatus",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (sessionOn) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (micDenied) {
                                Text(
                                    text = "The microphone permission is required for the assistant.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.emberRed),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            CloudCapsuleButton(
                                text = if (sessionOn) "Stop assistant" else "Start assistant",
                                onClick = { if (sessionOn) viewModel.stopVoiceSession() else requestVoiceStart() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            SettingRow(
                                icon = Icons.Filled.RecordVoiceOver,
                                title = "TTS voice",
                                value = state.voice,
                                onClick = { dialog = SettingsDialog.VOICE }
                            )
                            SettingRow(
                                icon = Icons.Filled.Translate,
                                title = "Voice language",
                                value = state.language,
                                onClick = { dialog = SettingsDialog.LANGUAGE }
                            )
                            ToggleRow(
                                icon = Icons.Filled.GraphicEq,
                                title = "Silent execution",
                                subtitle = "Run commands without spoken confirmations",
                                checked = state.silentExecution,
                                onCheckedChange = { viewModel.setSilentExecution(it) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.Speed,
                                title = "HUD sounds",
                                subtitle = "Chimes for listening, taps and replies",
                                checked = state.hudSoundEnabled,
                                onCheckedChange = { viewModel.setHudSound(it) }
                            )
                            if (state.hudSoundEnabled) {
                                SliderSetting(
                                    label = { "HUD sound volume  ${(it * 100).toInt()}%" },
                                    value = state.hudSoundVolume,
                                    valueRange = 0f..1f,
                                    onCommit = { viewModel.setHudVolume(it) }
                                )
                            }
                            ToggleRow(
                                icon = Icons.Filled.Timer,
                                title = "Battery saver",
                                subtitle = "Telemetry every 5 s instead of 1 s, slower Dynamic Island polling",
                                checked = state.batterySaver,
                                onCheckedChange = { viewModel.setBatterySaver(it) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.Security,
                                title = "Owner voice verification",
                                subtitle = if (state.ownerVoiceEnrolled) "Only respond to your enrolled voice" else "Enroll your voice first",
                                checked = state.ownerVoiceEnabled,
                                enabled = state.ownerVoiceEnrolled,
                                onCheckedChange = { viewModel.setOwnerVoice(it) }
                            )
                            SettingRow(
                                icon = Icons.Filled.Mic,
                                title = "Enroll owner voice",
                                value = if (state.ownerVoiceEnrolled) "Enrolled" else "Setup",
                                onClick = { context.launchSafely(Intent(context, VoiceEnrollmentActivity::class.java)) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.TouchApp,
                                title = "Task overlay",
                                subtitle = "Floating avatar while Jarvis works",
                                checked = state.taskOverlayEnabled,
                                onCheckedChange = { viewModel.setTaskOverlay(it) }
                            )
                            SettingRow(
                                icon = Icons.Filled.OpenInNew,
                                title = "Floating overlay",
                                value = if (state.overlayGranted) "Allowed" else "Not allowed",
                                onClick = { context.launchSafely(viewModel.overlaySettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            Text(
                                text = "Speaking speed, pitch, continuous conversation and the wake/STT/TTS engines live in AndroLLM features → Voice, below.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // ── Speech recognition ──────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.SpeechRecognition,
                    icon = Icons.Filled.Mic,
                    expanded = expandedGroup == SettingsGroup.SpeechRecognition.name,
                    onToggle = { toggleGroup(SettingsGroup.SpeechRecognition) },
                    visible = SettingsGroup.SpeechRecognition.matches(searchQuery),
                    subtitle = "Vosk wake word",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Mic,
                                title = "Wake word engine",
                                value = "Vosk (offline)",
                                onClick = null
                            )
                            Text(
                                text = "Wake word / STT / TTS engine choice and whisper.cpp model downloads are in AndroLLM features → Voice, below.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // ── Text normalization ──────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.TextNormalization,
                    icon = Icons.Filled.TextFields,
                    expanded = expandedGroup == SettingsGroup.TextNormalization.name,
                    onToggle = { toggleGroup(SettingsGroup.TextNormalization) },
                    visible = SettingsGroup.TextNormalization.matches(searchQuery),
                    subtitle = if (state.normalizeNumbers || state.normalizeCurrency) "On" else "Off",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ToggleRow(
                                icon = Icons.Filled.Translate,
                                title = "Numbers",
                                subtitle = "123 → one hundred twenty-three",
                                checked = state.normalizeNumbers,
                                onCheckedChange = { viewModel.setNormalizeNumbers(it) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.Translate,
                                title = "Currencies",
                                subtitle = "\$20 → twenty dollars",
                                checked = state.normalizeCurrency,
                                onCheckedChange = { viewModel.setNormalizeCurrency(it) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.Translate,
                                title = "Sentence assembler",
                                subtitle = "Speak streamed replies in whole sentences",
                                checked = state.sentenceAssembler,
                                onCheckedChange = { viewModel.setSentenceAssembler(it) }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            Text(
                                text = "Dates, units, math, emoji, URLs, phones, abbreviations and custom regex rules are in AndroLLM features → Voice → Normalization, below.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                // ── Automation ──────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Automation,
                    icon = Icons.Filled.Bolt,
                    expanded = expandedGroup == SettingsGroup.Automation.name,
                    onToggle = { toggleGroup(SettingsGroup.Automation) },
                    visible = SettingsGroup.Automation.matches(searchQuery),
                    subtitle = if (state.directAutonomous) "Direct mode" else "Safe mode",
                    reduceMotion = state.reduceMotion
                ) {
                    AutomationCard(
                        directAutonomous = state.directAutonomous,
                        autoReplyConfirm = state.autoReplyConfirm,
                        permissionTick = permissionTick,
                        onDirectChange = { viewModel.setDirectAutonomous(it) },
                        onAutoReplyConfirmChange = { viewModel.setAutoReplyConfirm(it) },
                        onRequestPermissions = { perms -> toolPermissionLauncher.launch(perms.toTypedArray()) },
                        onOpenClassic = { context.openClassicSettings() }
                    )
                }

                // ── UI Automation ───────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.UiAutomation,
                    icon = Icons.Filled.AccessibilityNew,
                    expanded = expandedGroup == SettingsGroup.UiAutomation.name,
                    onToggle = { toggleGroup(SettingsGroup.UiAutomation) },
                    visible = SettingsGroup.UiAutomation.matches(searchQuery),
                    subtitle = if (state.accessibilityRunning) "Accessibility active" else "Accessibility off",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.TouchApp,
                                title = "Accessibility service",
                                value = if (state.accessibilityRunning) "Active" else "Off — tap to enable",
                                onClick = {
                                    context.launchSafely(
                                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
                            ToggleRow(
                                icon = Icons.Filled.TouchApp,
                                title = "Scroll into view",
                                subtitle = "Auto-scroll lists until the target element is visible",
                                checked = state.autoScroll,
                                onCheckedChange = { viewModel.setAutoScroll(it) }
                            )
                        }
                    }
                }

                // ── Device permissions ──────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.DevicePermissions,
                    icon = Icons.Filled.Lock,
                    expanded = expandedGroup == SettingsGroup.DevicePermissions.name,
                    onToggle = { toggleGroup(SettingsGroup.DevicePermissions) },
                    visible = SettingsGroup.DevicePermissions.matches(searchQuery),
                    subtitle = "SMS, Phone, Contacts, Location…",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Lock,
                                title = "Permissions & Access",
                                value = "Microphone, notifications, accessibility…",
                                onClick = { context.launchSafely(Intent(context, PermissionActivity::class.java)) }
                            )
                        }
                    }
                }

                // ── MCP ─────────────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Mcp,
                    icon = Icons.Filled.Dns,
                    expanded = expandedGroup == SettingsGroup.Mcp.name,
                    onToggle = { toggleGroup(SettingsGroup.Mcp) },
                    visible = SettingsGroup.Mcp.matches(searchQuery),
                    subtitle = if (state.mcpServers.isEmpty()) "None" else "${state.mcpServers.size} server(s)",
                    reduceMotion = state.reduceMotion
                ) {
                    McpSection(
                        servers = state.mcpServers,
                        toolCount = mcpToolCount,
                        onAdd = { name, url, token -> viewModel.addMcpServer(name, url, token) },
                        onRemove = { viewModel.removeMcpServer(it) },
                        onToggle = { server, enabled -> viewModel.setMcpServerEnabled(server, enabled) }
                    )
                }

                // ── Safety & privacy ────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Safety,
                    icon = Icons.Filled.Security,
                    expanded = expandedGroup == SettingsGroup.Safety.name,
                    onToggle = { toggleGroup(SettingsGroup.Safety) },
                    visible = SettingsGroup.Safety.matches(searchQuery),
                    subtitle = if (state.privacyMode) "Privacy mode on" else "Privacy mode off",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ToggleRow(
                                icon = Icons.Filled.Security,
                                title = "Privacy mode",
                                subtitle = "Shield sensitive chat logs & readouts",
                                checked = state.privacyMode,
                                onCheckedChange = { viewModel.setPrivacyMode(it) }
                            )
                            SettingRow(
                                icon = Icons.Filled.Lock,
                                title = "Privacy Shield",
                                value = "Policy & data controls",
                                onClick = { context.launchSafely(Intent(context, PrivacyShieldActivity::class.java)) }
                            )
                            UnavailableRow(title = "Warn before opening AI links")
                        }
                    }
                }

                // ── Developer ───────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.Developer,
                    icon = Icons.Filled.Code,
                    expanded = expandedGroup == SettingsGroup.Developer.name,
                    onToggle = { toggleGroup(SettingsGroup.Developer) },
                    visible = SettingsGroup.Developer.matches(searchQuery),
                    subtitle = "Telemetry & diagnostics",
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Insights,
                                title = "Developer dashboard",
                                value = "Live telemetry",
                                onClick = { navigate(ComposeRoutes.DEVELOPER) }
                            )
                            ToggleRow(
                                icon = Icons.Filled.Code,
                                title = "Developer links",
                                subtitle = "Show developer contact links in classic settings",
                                checked = state.developerLinks,
                                onCheckedChange = { viewModel.setDeveloperLinks(it) }
                            )
                            UnavailableRow(title = "Export & preview logs")
                        }
                    }
                }

                // ── About ───────────────────────────────────────────────────
                settingsAccordionItem(
                    group = SettingsGroup.About,
                    icon = Icons.Filled.Info,
                    expanded = expandedGroup == SettingsGroup.About.name,
                    onToggle = { toggleGroup(SettingsGroup.About) },
                    visible = SettingsGroup.About.matches(searchQuery),
                    subtitle = state.versionName,
                    reduceMotion = state.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Info,
                                title = "Version",
                                value = state.versionName,
                                onClick = {}
                            )
                            SettingRow(
                                icon = Icons.Filled.Settings,
                                title = "Classic Jarvis Settings",
                                value = "All legacy options",
                                onClick = { context.openClassicSettings() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = "Reload settings",
                                onClick = { viewModel.refresh() }
                            )
                        }
                    }
                }

                item(key = "settings-androllm-features") {
                    AndroLlmFeatureSections(onOpenToolDebug = { navigate(com.jarvis.assistant.ui.compose.navigation.ComposeRoutes.TOOL_DEBUG) })
                }

                item(key = "settings-end-spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.USER_NAME -> TextInputDialog(
            title = "Your name",
            description = "How Jarvis addresses you.",
            label = "Name",
            initial = state.userName,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setUserName(it) }
        )
        SettingsDialog.ASSISTANT_NAME -> TextInputDialog(
            title = "Assistant name",
            description = "The name your assistant answers to.",
            label = "Assistant name",
            initial = state.assistantName,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setAssistantName(it) }
        )
        SettingsDialog.PERSONALITY -> ChoiceDialog(
            title = "Personality",
            options = SettingsViewModel.personalities,
            selected = state.personality,
            onDismiss = { dialog = null },
            onSelect = { index ->
                dialog = null
                viewModel.setPersonality(SettingsViewModel.personalities[index])
            }
        )
        SettingsDialog.AI_MODE -> {
            val modes = AiModelMode.entries
            ChoiceDialog(
                title = "AI engine",
                options = modes.map { it.name },
                selected = state.aiMode.name,
                labelFor = { name -> AiModelMode.fromString(name).displayName },
                onDismiss = { dialog = null },
                onSelect = { index ->
                    dialog = null
                    viewModel.setAiMode(modes[index])
                }
            )
        }
        SettingsDialog.GEMINI_MODEL -> ChoiceDialog(
            title = "Gemini model",
            options = SettingsViewModel.geminiModels,
            selected = state.geminiModel,
            labelFor = { geminiModelLabel(it) },
            onDismiss = { dialog = null },
            onSelect = { index ->
                dialog = null
                viewModel.setGeminiModel(SettingsViewModel.geminiModels[index])
            }
        )
        SettingsDialog.GEMINI_KEY -> TextInputDialog(
            title = "Gemini API key",
            description = "Stored in encrypted storage on this device. Leave empty to clear.",
            label = "API key",
            initial = "",
            secret = true,
            allowEmpty = true,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setGeminiApiKey(it) }
        )
        SettingsDialog.OPENAI_KEY -> TextInputDialog(
            title = "OpenAI API key",
            description = "Used by the ChatGPT engine. Leave empty to clear.",
            label = "API key",
            initial = "",
            secret = true,
            allowEmpty = true,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setOpenAiApiKey(it) }
        )
        SettingsDialog.ENDPOINT -> TextInputDialog(
            title = "Ollama endpoint",
            description = "Local host server URL. Loopback works with `adb reverse tcp:11434 tcp:11434`.",
            label = "Endpoint URL",
            initial = state.customEndpoint,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setCustomEndpoint(it) }
        )
        SettingsDialog.OLLAMA_MODEL -> TextInputDialog(
            title = "Ollama model",
            description = "Model tag installed in your Ollama server (e.g. qwen3:8b).",
            label = "Model tag",
            initial = state.localHostModel,
            onDismiss = { dialog = null },
            onSave = { dialog = null; viewModel.setLocalHostModel(it) }
        )
        SettingsDialog.VOICE -> ChoiceDialog(
            title = "TTS voice",
            options = SettingsViewModel.voices,
            selected = state.voice,
            onDismiss = { dialog = null },
            onSelect = { index ->
                dialog = null
                viewModel.setVoice(SettingsViewModel.voices[index])
            }
        )
        SettingsDialog.LANGUAGE -> ChoiceDialog(
            title = "Conversation language",
            options = SettingsViewModel.languages,
            selected = state.language,
            onDismiss = { dialog = null },
            onSelect = { index ->
                dialog = null
                viewModel.setLanguage(SettingsViewModel.languages[index])
            }
        )
        SettingsDialog.DELETE_MEMORIES -> ConfirmDialog(
            title = "Delete all memories?",
            message = "Every fact Jarvis has stored about you will be permanently erased.",
            confirmLabel = "Delete",
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                viewModel.deleteAllMemories()
            }
        )
        null -> Unit
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.ledger.deskPaper
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun LocalModelRowView(
    model: LocalModelRow,
    active: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.ledger.deskPaper
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.sizeLabel + if (model.ready) " • downloaded" else "",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
        }
        when {
            active && model.ready -> CloudChip(text = "Active", accentColor = MaterialTheme.ledger.revolutNeonEmerald)
            model.ready -> TextButton(onClick = onSelect) { Text("Use", color = MaterialTheme.ledger.lampGlow) }
            else -> TextButton(onClick = onDownload) { Text("Download", color = MaterialTheme.ledger.lampGlow) }
        }
    }
}

/**
 * Profile header — three distinct lines (name, assistant, device status).
 * Jarvis has no cloud account, so the status line is always local.
 */
@Composable
private fun UserProfileCard(state: SettingsUiState, onClick: () -> Unit) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CloudBugdroidLogo(size = 72.dp)
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.userName.ifBlank { "Jarvis User" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Assistant: ${state.assistantName.ifBlank { "JARVIS" }}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Local profile · stored on this device",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Downloaded models / model storage / execution engine — all live values. */
@Composable
private fun UserStatsRow(state: SettingsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatTile(
            label = "Downloaded",
            value = if (state.localModels.isEmpty()) "…" else {
                val count = state.downloadedModelCount
                "$count Model${if (count == 1) "" else "s"}"
            },
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "Storage",
            value = state.modelBytes?.let { formatBytes(it) } ?: "…",
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = "Execution",
            value = state.aiMode.shortName(),
            highlight = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    CloudGlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskPaper
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Automation / tool calling. Jarvis' tool pipeline is always on; the one
 * real switch is Direct (no confirmations) vs Safe (ask first).
 */
@Composable
private fun AutomationCard(
    directAutonomous: Boolean,
    autoReplyConfirm: Boolean,
    permissionTick: Int,
    onDirectChange: (Boolean) -> Unit,
    onAutoReplyConfirmChange: (Boolean) -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenClassic: () -> Unit
) {
    val context = LocalContext.current
    // permissionTick forces a re-check after a grant / resume.
    val missing = remember(permissionTick) {
        toolPermissionGroups.filter { (_, perms) -> perms.any { !context.hasPermission(it) } }
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Rule, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Confirmations", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                    Text(
                        "Which actions require your approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk
                    )
                }
            }
            ConfirmationOption(
                selected = !directAutonomous,
                title = "Safe mode",
                description = "Commands ask for confirmation before acting",
                onClick = { onDirectChange(false) }
            )
            ConfirmationOption(
                selected = directAutonomous,
                title = "Direct autonomous mode",
                description = "Zero confirmations — actions run instantly",
                onClick = { onDirectChange(true) }
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            ToggleRow(
                icon = Icons.Filled.Security,
                title = "Confirm message auto-replies",
                subtitle = "Ask before Jarvis replies to messages for you",
                checked = autoReplyConfirm,
                onCheckedChange = onAutoReplyConfirmChange
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            if (missing.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Device permissions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                        Text(
                            "Some tools need system permissions before they can run",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                }
                missing.forEach { (label, perms) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Not granted",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampAmber)
                        )
                        TextButton(onClick = { onRequestPermissions(perms) }) {
                            Text("Grant", color = MaterialTheme.ledger.lampGlow)
                        }
                    }
                }
            } else {
                Text(
                    text = "All tool permissions granted.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            Text(
                text = "Per-tool toggles, voice confirmations and the confirmation mode are in AndroLLM features → Automation, below.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            SettingRow(
                icon = Icons.Filled.OpenInNew,
                title = "Skills & sub-agents",
                value = "Classic settings",
                onClick = onOpenClassic
            )
        }
    }
}

@Composable
private fun ConfirmationOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (selected) "●" else "○",
            color = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
            style = MaterialTheme.typography.bodyMedium
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (selected) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskInk,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
            )
        }
    }
}
