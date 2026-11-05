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

/**
 * Settings → Voice. Jarvis's own voice stack (Gemini Live, Vosk wake word, Android
 * TTS / PocketTTS) stays the default; this section adds the optional AndroLLM
 * sherpa-onnx engines (all OFF by default), user-initiated model downloads, and the
 * live text-normalization stage toggles used by every Jarvis offline TTS path.
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
        JarvisVoiceDefaultsCard()
        SherpaEnginesCard()
        VoiceModelsCard()
        AndroVoiceAssistantCard()
        WhisperModelsCard()
        NormalizationCard()
    }
}

// ── Jarvis defaults (read-only status) ──────────────────────────────────────

@Composable
private fun JarvisVoiceDefaultsCard() {
    val sessionOn by JarvisConversationService.isSessionOn.collectAsStateWithLifecycle()
    val standby by JarvisConversationService.isStandby.collectAsStateWithLifecycle()
    val connection by JarvisConversationService.connectionStatus.collectAsStateWithLifecycle()

    SectionCard(title = "Jarvis voice (default)", subtitle = "Primary pipeline - unchanged by the options below") {
        InfoRow(Icons.Filled.RecordVoiceOver, "Conversation", "Gemini Live voice (falls back to offline Vosk + Android TTS / PocketTTS)")
        InfoRow(Icons.Filled.Hearing, "Wake word", "Built-in Vosk \"Hey Jarvis\" in standby")
        InfoRow(
            Icons.Filled.Mic,
            "Microphone",
            when {
                sessionOn -> "In use by Jarvis voice session ($connection)"
                standby -> "In use by Jarvis wake word (standby)"
                else -> "Free"
            }
        )
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
        title = "Offline engines (sherpa-onnx)",
        subtitle = "Optional AndroLLM alternatives. Off by default; never run while Jarvis uses the mic."
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
        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

        ToggleRow(
            icon = Icons.Filled.Mic,
            title = "sherpa speech recognition",
            subtitle = "Offline streaming dictation (does not replace Gemini Live / Vosk)",
            checked = cfg.asrEnabled,
            onCheckedChange = { AndroLlmVoicePrefs.setFlag(context, AndroLlmFlags.SHERPA_ASR, it) }
        )
        if (cfg.asrEnabled) {
            SliderSetting(
                label = { "End of speech after ${"%.1f".format(it)} s silence" },
                value = cfg.asrSilenceSeconds,
                valueRange = AndroLlmVoiceConfig.MIN_SILENCE..AndroLlmVoiceConfig.MAX_SILENCE,
                onCommit = { AndroLlmVoicePrefs.setAsrSilenceSeconds(context, it) }
            )
            val dictation = status.dictation
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CloudCapsuleButton(
                    text = if (dictation.active) "Stop" else "Try dictation",
                    icon = Icons.Filled.Mic,
                    onClick = {
                        if (dictation.active) {
                            SherpaVoiceController.stopDictation()
                        } else if (!PermissionHelper.hasRecordAudioPermission(context)) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            message = SherpaVoiceController.startDictation(context)
                        }
                    }
                )
                Text(
                    text = when {
                        dictation.active -> dictation.partial.ifBlank { "Listening..." }
                        dictation.error != null -> dictation.error
                        dictation.finalText.isNotBlank() -> "\"${dictation.finalText}\""
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

        ToggleRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "sherpa offline TTS",
            subtitle = "VITS / Piper voice for read-aloud. Jarvis's conversation voice is unchanged.",
            checked = cfg.ttsEnabled,
            onCheckedChange = { AndroLlmVoicePrefs.setFlag(context, AndroLlmFlags.SHERPA_TTS, it) }
        )
        if (cfg.ttsEnabled) {
            SliderSetting(
                label = { "Speaking speed: ${"%.2f".format(it)}x" },
                value = cfg.ttsSpeed,
                valueRange = AndroLlmVoiceConfig.MIN_SPEED..AndroLlmVoiceConfig.MAX_SPEED,
                onCommit = { AndroLlmVoicePrefs.setTtsSpeed(context, it) }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CloudCapsuleButton(
                    text = if (status.speaking) "Stop" else "Test voice",
                    icon = Icons.Filled.RecordVoiceOver,
                    onClick = {
                        if (status.speaking) {
                            SherpaVoiceController.stopSpeaking()
                        } else {
                            message = SherpaVoiceController.speak(
                                context,
                                "Hello, I'm Jarvis. It's 10:30 am and the battery is at 85%."
                            )
                        }
                    }
                )
                status.ttsError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk))
                }
            }
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
        subtitle = "Downloaded only when you tap Download - stored in app files/models/voice"
    ) {
        VoiceModelKind.values().forEachIndexed { kindIndex, kind ->
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
        subtitle = "How replies are rewritten before offline TTS speaks them (Android TTS, sherpa)"
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
