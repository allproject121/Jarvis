package com.jarvis.assistant.ui.compose.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.voice.AndroLlmVoiceConfig
import com.jarvis.assistant.androllm.voice.AndroLlmVoicePrefs
import com.jarvis.assistant.androllm.voice.SherpaVoiceController
import com.jarvis.assistant.androllm.voice.VoiceDownloadState
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.VoiceModelDownloader
import com.jarvis.assistant.androllm.voice.VoiceModelKind
import com.jarvis.assistant.androllm.voice.VoiceModelPack
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.voice.normalize.CustomRuleSpec
import com.jarvis.assistant.voice.normalize.NormalizationSettings
import com.jarvis.assistant.voice.normalize.NormalizationSettingsStore
import com.jarvis.assistant.voice.normalize.TextNormalizationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.preferences.JarvisExtraPrefs
import com.jarvis.assistant.voice.PocketTtsEngine
import com.jarvis.assistant.voice.edge.EdgeVoiceCatalog

/**
 * Settings → Voice. Every control here changes a voice engine Jarvis really uses:
 * - Gemini Live voice (AppPreferences.voice, read when a Live session connects),
 * - "Hey Jarvis" wake-word sensitivity (VoiceSettingsStore.sensitivity, read by the Vosk wake
 *   word in JarvisConversationService on every detection),
 * - reply voice for non-Live speech (Jarvis voice / Edge neural, JarvisExtraPrefs) + a test,
 * - the optional sherpa-onnx "Hey Jarvis" spotter (hands off to Jarvis' voice session),
 * - speech text normalization (OfflineVoiceConversationEngine runs every offline reply through it).
 * The separate AndroLLM voice-assistant loop, whisper models and the sherpa dictation / TTS
 * test toggles were removed from this page: nothing in Jarvis' own voice path used them.
 */
@Composable
fun VoiceSettingsSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SherpaVoiceController.init(context)
        NormalizationSettingsStore.load(context)
        VoiceModelDownloader.refresh(context)
        withContext(Dispatchers.Default) { SherpaVoiceController.refreshNativeStatus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JarvisVoiceCard()
        SherpaEnginesCard()
        VoiceModelsCard()
        NormalizationCard()
    }
}

// ── Jarvis voice (the engines Jarvis really uses) ───────────────────────────

private const val VOICE_TEST_LINE = "Hello, main Jarvis hoon. Replies isi awaaz mein aayenge."

@Composable
private fun JarvisVoiceCard() {
    val context = LocalContext.current
    val prefs = remember { JarvisApp.instance.preferences }
    val scope = rememberCoroutineScope()
    val sessionOn by JarvisConversationService.isSessionOn.collectAsStateWithLifecycle()
    val standby by JarvisConversationService.isStandby.collectAsStateWithLifecycle()
    val connection by JarvisConversationService.connectionStatus.collectAsStateWithLifecycle()
    // Wake-word sensitivity: JarvisConversationService reads VoiceSettingsStore.sensitivity on
    // every Vosk "jarvis" hit (WakeWordThresholds); AppPreferences is kept in sync as its fallback.
    val voiceStore = remember { VoiceSettingsStore.get(context) }
    val voiceSettings by voiceStore.state.collectAsStateWithLifecycle()

    var liveVoice by remember { mutableStateOf(prefs.voice) }
    var edgeOn by remember { mutableStateOf(JarvisExtraPrefs.isEdgeTtsSelected(context)) }
    var edgeVoice by remember { mutableStateOf(JarvisExtraPrefs.edgeVoice(context)) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }

    SectionCard(title = "Jarvis voice", subtitle = "The voice you hear and the wake word Jarvis listens for") {
        InfoRow(
            Icons.Filled.Mic,
            "Microphone",
            when {
                sessionOn -> "In use by Jarvis voice session ($connection)"
                standby -> "In use by the \"Hey Jarvis\" wake word (standby)"
                else -> "Free"
            }
        )
        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
        SettingRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Gemini Live voice",
            value = liveVoice
        ) { dialog = "live" }
        Text(
            "Used from the next Gemini Live session (turn Jarvis off and on to hear it now).",
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
            modifier = Modifier.padding(start = 46.dp, end = 12.dp, bottom = 4.dp)
        )
        SliderSetting(
            label = { "\"Hey Jarvis\" sensitivity: ${"%.2f".format(it)}  (higher = wakes more easily)" },
            value = voiceSettings.sensitivity,
            valueRange = VoiceSettings.MIN_SENSITIVITY..VoiceSettings.MAX_SENSITIVITY,
            onCommit = { v ->
                prefs.voskWakeWordSensitivity = v
                scope.launch { runCatching { voiceStore.update { it.copy(sensitivity = v) } } }
            }
        )
        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
        SettingRow(
            icon = Icons.Filled.GraphicEq,
            title = "Reply voice (offline & chat read-aloud)",
            value = if (edgeOn) "Edge neural (online)" else "Jarvis voice"
        ) { dialog = "engine" }
        if (edgeOn) {
            SettingRow(
                icon = Icons.Filled.Tune,
                title = "Edge voice",
                value = EdgeVoiceCatalog.labelFor(edgeVoice)
            ) { dialog = "edge" }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CloudCapsuleButton(
                text = "Test reply voice",
                icon = Icons.Filled.PlayArrow,
                onClick = {
                    testNote = "Speaking..."
                    // The same engine Jarvis uses for non-Live speech (Edge when selected and online,
                    // otherwise Android TTS / PocketTTS).
                    PocketTtsEngine.speak(VOICE_TEST_LINE, context) { testNote = "Done - that is the reply voice." }
                }
            )
            testNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk))
            }
        }
    }

    when (dialog) {
        "live" -> ChoiceDialog(
            title = "Gemini Live voice",
            options = GeminiConstants.SUPPORTED_VOICES,
            selected = liveVoice,
            onDismiss = { dialog = null },
            onSelect = { index ->
                val v = GeminiConstants.SUPPORTED_VOICES[index]
                prefs.voice = v
                liveVoice = v
                dialog = null
            }
        )
        "engine" -> {
            val engines = listOf(JarvisExtraPrefs.TTS_ENGINE_JARVIS, JarvisExtraPrefs.TTS_ENGINE_EDGE)
            ChoiceDialog(
                title = "Reply voice",
                options = engines,
                selected = if (edgeOn) JarvisExtraPrefs.TTS_ENGINE_EDGE else JarvisExtraPrefs.TTS_ENGINE_JARVIS,
                onDismiss = { dialog = null },
                onSelect = { index ->
                    JarvisExtraPrefs.setTtsEngine(context, engines[index])
                    edgeOn = JarvisExtraPrefs.isEdgeTtsSelected(context)
                    dialog = null
                },
                labelFor = { id ->
                    if (id == JarvisExtraPrefs.TTS_ENGINE_EDGE) "Edge neural - natural voice, needs internet (falls back to Jarvis voice)"
                    else "Jarvis voice - Android TTS, works offline"
                }
            )
        }
        "edge" -> {
            val voices = EdgeVoiceCatalog.VOICES
            ChoiceDialog(
                title = "Edge voice",
                options = voices.map { it.id },
                selected = edgeVoice,
                onDismiss = { dialog = null },
                onSelect = { index ->
                    JarvisExtraPrefs.setEdgeVoice(context, voices[index].id)
                    edgeVoice = voices[index].id
                    dialog = null
                },
                labelFor = { id -> EdgeVoiceCatalog.labelFor(id) }
            )
        }
    }
}

// ── sherpa-onnx engines ─────────────────────────────────────────────────────

@Composable
private fun SherpaEnginesCard() {
    val context = LocalContext.current
    val cfg by AndroLlmVoicePrefs.config.collectAsStateWithLifecycle()
    val status by SherpaVoiceController.status.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SherpaVoiceController.recheck()
    }
    LaunchedEffect(Unit) {
        SherpaVoiceController.events.collect { message = it }
    }

    SectionCard(
        title = "Offline wake word (sherpa-onnx)",
        subtitle = "Optional second \"Hey Jarvis\" spotter while the app is open; on a hit it starts Jarvis' voice session. Needs the wake-word model below."
    ) {
        if (status.nativeAvailable == false) {
            WarningRow(
                "sherpa-onnx native library failed to load - these engines cannot run on this build. " +
                    (status.nativeError ?: "")
            )
        }

        ToggleRow(
            icon = Icons.Filled.Hearing,
            title = "sherpa wake word",
            subtitle = "\"Hey Jarvis\" keyword spotter while the app is open and Jarvis is idle - " +
                status.wakeState.label + (status.wakeDetail?.let { " ($it)" } ?: ""),
            checked = cfg.wakeWordEnabled,
            onCheckedChange = { on ->
                AndroLlmVoicePrefs.setFlag(context, AndroLlmFlags.SHERPA_WAKE_WORD, on)
                if (on && !PermissionHelper.hasRecordAudioPermission(context)) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        )
        if (cfg.wakeWordEnabled) {
            SliderSetting(
                label = { "Wake sensitivity: ${"%.2f".format(it)}" },
                value = cfg.wakeSensitivity,
                valueRange = AndroLlmVoiceConfig.MIN_SENSITIVITY..AndroLlmVoiceConfig.MAX_SENSITIVITY,
                onCommit = { AndroLlmVoicePrefs.setWakeSensitivity(context, it) }
            )
        }
        message?.let {
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { message = null }) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.ledger.deskInk)
                }
            }
        }
    }
}

// ── Model downloads ─────────────────────────────────────────────────────────

@Composable
private fun VoiceModelsCard() {
    val context = LocalContext.current
    val cfg by AndroLlmVoicePrefs.config.collectAsStateWithLifecycle()
    val states by VoiceModelDownloader.states.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<VoiceModelPack?>(null) }

    SectionCard(
        title = "Voice models",
        subtitle = "Wake word = sherpa spotter above; speech recognition = Advanced Agentic voice (sherpa transcriber). Downloaded only when you tap Download."
    ) {
        VoiceModelKind.values().filter { it != VoiceModelKind.TTS }.forEachIndexed { kindIndex, kind ->
            if (kindIndex > 0) HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            Text(
                kind.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.ledger.lampGlow,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)
            )
            val selectedId = when (kind) {
                VoiceModelKind.WAKE_WORD -> cfg.wakeWordPackId
                VoiceModelKind.ASR -> cfg.asrPackId
                VoiceModelKind.TTS -> cfg.ttsPackId
            }
            VoiceModelCatalog.ofKind(kind).forEach { pack ->
                ModelPackRow(
                    pack = pack,
                    state = states[pack.id] ?: VoiceDownloadState.NotInstalled,
                    selected = pack.id == selectedId,
                    onSelect = { AndroLlmVoicePrefs.selectPack(context, pack) },
                    onDownload = { VoiceModelDownloader.download(context, pack) },
                    onCancel = { VoiceModelDownloader.cancel(pack.id) },
                    onDelete = { pendingDelete = pack }
                )
            }
        }
    }

    pendingDelete?.let { pack ->
        ConfirmDialog(
            title = "Delete ${pack.title}?",
            message = "Frees ${formatBytes(pack.totalBytes)}. Any sherpa feature using it stops until you download it again.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                SherpaVoiceController.releaseEnginesFor(pack)
                VoiceModelDownloader.delete(context, pack)
            }
        )
    }
}

@Composable
private fun ModelPackRow(
    pack: VoiceModelPack,
    state: VoiceDownloadState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (selected) "●" else "○",
                color = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pack.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Text(
                    "${pack.description} ${formatBytes(pack.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (state) {
                is VoiceDownloadState.Downloading -> IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download", tint = MaterialTheme.ledger.deskInk)
                }
                VoiceDownloadState.Installed -> IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = MaterialTheme.ledger.deskInk)
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download model", tint = MaterialTheme.ledger.lampGlow)
                }
            }
        }
        when (state) {
            is VoiceDownloadState.Downloading -> {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairline
                )
                Text(
                    "${formatBytes(state.bytesDone)} / ${formatBytes(state.bytesTotal)}" +
                        if (state.currentFile.isNotBlank()) " - ${state.currentFile.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
            is VoiceDownloadState.Failed -> Text(
                "Download failed: ${state.message}. Tap download to retry (resumes).",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.emberRed)
            )
            VoiceDownloadState.Installed -> Text(
                if (selected) "Installed - selected" else "Installed - tap to select",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
            )
            VoiceDownloadState.NotInstalled -> Unit
        }
    }
}

// ── Text normalization ──────────────────────────────────────────────────────

private data class StageToggle(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val get: (NormalizationSettings) -> Boolean,
    val set: (NormalizationSettings, Boolean) -> NormalizationSettings
)

private val STAGE_TOGGLES = listOf(
    StageToggle(Icons.Filled.Language, "URLs & emails", "\"user@example.com\" -> \"user at example dot com\"",
        { it.urlsEmails }, { s, v -> s.copy(urlsEmails = v) }),
    StageToggle(Icons.Filled.Phone, "Phone numbers", "Spoken digit by digit",
        { it.phones }, { s, v -> s.copy(phones = v) }),
    StageToggle(Icons.Filled.Paid, "Currency", "\"€19.99\" -> \"nineteen euros and ninety nine cents\"",
        { it.currency }, { s, v -> s.copy(currency = v) }),
    StageToggle(Icons.Filled.Event, "Dates & times", "\"14:30\" -> \"two thirty pm\"",
        { it.dates }, { s, v -> s.copy(dates = v) }),
    StageToggle(Icons.Filled.Straighten, "Units", "\"10km\" -> \"10 kilometers\"",
        { it.units }, { s, v -> s.copy(units = v) }),
    StageToggle(Icons.Filled.Functions, "Math", "\"2+2\", \"16:9\", \"≈\"",
        { it.math }, { s, v -> s.copy(math = v) }),
    StageToggle(Icons.Filled.Numbers, "Numbers", "Integers, decimals, %, ordinals, versions",
        { it.numbers }, { s, v -> s.copy(numbers = v) }),
    StageToggle(Icons.Filled.Spellcheck, "Abbreviations", "\"GPU\" -> \"g p u\"",
        { it.abbreviations }, { s, v -> s.copy(abbreviations = v) }),
    StageToggle(Icons.Filled.EmojiEmotions, "Emoji & symbols", "Emoji described in words, markdown removed",
        { it.emoji }, { s, v -> s.copy(emoji = v) }),
    StageToggle(Icons.Filled.Rule, "Built-in rules", "Arrows, dashes, ©, ®, ™, §",
        { it.builtInRules }, { s, v -> s.copy(builtInRules = v) }),
    StageToggle(Icons.Filled.AutoFixHigh, "Custom rules", "Your regex rules below",
        { it.customRules }, { s, v -> s.copy(customRules = v) }),
    StageToggle(Icons.Filled.BugReport, "Debug trace", "Log every stage to logcat (tag TtsNormalization)",
        { it.debug }, { s, v -> s.copy(debug = v) })
)

@Composable
private fun NormalizationCard() {
    val context = LocalContext.current
    val settings by NormalizationSettingsStore.settings.collectAsStateWithLifecycle()
    val rules by NormalizationSettingsStore.customRules.collectAsStateWithLifecycle()
    var showAddRule by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf("Meet me at 3:30 pm, it costs \$19.99 🚀 -> see jarvis.ai") }
    var previewOut by remember { mutableStateOf("") }

    LaunchedEffect(preview, settings, rules) {
        previewOut = withContext(Dispatchers.Default) {
            runCatching { TextNormalizationPipeline.getInstance().process(preview) }.getOrDefault("")
        }
    }

    SectionCard(
        title = "Text normalization",
        subtitle = "How offline voice replies are rewritten before they are spoken (3:30 pm -> three thirty pm)"
    ) {
        ToggleRow(
            icon = Icons.Filled.TextFields,
            title = "Normalize text for speech",
            subtitle = "Master switch for every stage below",
            checked = settings.enabled,
            onCheckedChange = { on -> NormalizationSettingsStore.update(context) { it.copy(enabled = on) } }
        )
        STAGE_TOGGLES.forEach { t ->
            ToggleRow(
                icon = t.icon,
                title = t.title,
                subtitle = t.subtitle,
                checked = t.get(settings),
                enabled = settings.enabled,
                onCheckedChange = { on -> NormalizationSettingsStore.update(context) { t.set(it, on) } }
            )
        }

        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
        Text(
            "Custom rules",
            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.ledger.lampGlow, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 12.dp, top = 10.dp)
        )
        if (rules.isEmpty()) {
            Text(
                "No custom rules. Add a regex (e.g. \"\\bk8s\\b\" -> \"kubernetes\").",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        rules.forEachIndexed { index, rule ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rule.name.ifBlank { "Rule ${index + 1}" },
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        "/${rule.regex}/ -> \"${rule.replacement}\"",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { NormalizationSettingsStore.setRuleEnabled(context, index, it) },
                    colors = settingsSwitchColors()
                )
                IconButton(onClick = { NormalizationSettingsStore.removeRule(context, index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete rule", tint = MaterialTheme.ledger.deskInk)
                }
            }
        }
        CloudCapsuleButton(
            text = "Add rule",
            icon = Icons.Filled.Add,
            onClick = { showAddRule = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )

        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = preview,
                onValueChange = { preview = it },
                label = { Text("Preview input") },
                leadingIcon = { Icon(Icons.Filled.ShortText, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Spoken: $previewOut",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper)
            )
        }
    }

    if (showAddRule) {
        AddRuleDialog(
            onDismiss = { showAddRule = false },
            onAdd = { spec ->
                if (NormalizationSettingsStore.addRule(context, spec)) showAddRule = false
            }
        )
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (CustomRuleSpec) -> Unit) {
    var name by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    val valid = remember(regex) {
        regex.isNotEmpty() && runCatching { java.util.regex.Pattern.compile(regex) }.isSuccess
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add normalization rule", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    label = { Text("Regex (Java syntax)") },
                    singleLine = true,
                    isError = regex.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Replacement (\$1 = group 1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(CustomRuleSpec(name.trim(), regex, replacement)) },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.lampAmber)
            ) { Text("Add", color = MaterialTheme.ledger.inkOnLamp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.ledger.deskInk) }
        }
    )
}

// ── Small building blocks ───────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        Column {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.ledger.deskPaper)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
            }
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            content()
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.ledger.lampDeep, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = MaterialTheme.ledger.deskPaper))
            Text(value, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk))
        }
    }
}

@Composable
private fun WarningRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.ledger.emberRed, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper))
    }
}
