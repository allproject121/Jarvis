package com.jarvis.assistant.ui.compose.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.androllm.voice.VoiceSettingsStore
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistant
import com.jarvis.assistant.androllm.voice.assistant.VoiceAssistantController
import com.jarvis.assistant.androllm.voice.assistant.service.VoiceAssistantService
import com.jarvis.assistant.androllm.voice.model.VoiceModels
import com.jarvis.assistant.androllm.voice.model.VoiceSettings
import com.jarvis.assistant.androllm.voice.voicecloud.GeminiApiKeyProvider
import com.jarvis.assistant.androllm.voice.whisper.WhisperDownloadState
import com.jarvis.assistant.androllm.voice.whisper.WhisperModelManager
import com.jarvis.assistant.androllm.voice.whisper.WhisperModels
import com.jarvis.assistant.androllm.voice.whisper.WhisperNative
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.voice.overlay.OverlayPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GEMINI_VOICES = listOf("Kore", "Puck", "Fenrir", "Aoede", "Charon")

/**
 * Settings → Voice → optional AndroLLM voice assistant mode: own toggle (OFF by
 * default), engine choices (Jarvis engines are the defaults), behaviour toggles and
 * the start / stop control. Jarvis's Gemini Live voice is unaffected.
 */
@Composable
internal fun AndroVoiceAssistantCard() {
    val context = LocalContext.current
    val store = remember { VoiceSettingsStore.get(context) }
    val s by store.state.collectAsStateWithLifecycle()
    val running by VoiceAssistantService.isRunning.collectAsStateWithLifecycle()
    val ui by VoiceAssistantController.state.collectAsStateWithLifecycle()
    val jarvisSession by JarvisConversationService.isSessionOn.collectAsStateWithLifecycle()
    val jarvisStandby by JarvisConversationService.isStandby.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<String?>(null) }

    fun update(transform: (VoiceSettings) -> VoiceSettings) {
        scope.launch { store.update(transform) }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) VoiceAssistant.start(context) else "Microphone permission is required."
    }

    AndroCard(
        title = "AndroLLM voice assistant (optional)",
        subtitle = "Alternative wake → listen → answer → speak loop with its own engines. Off by default; " +
            "runs only while Jarvis's voice service is powered off and never records while Jarvis uses the mic."
    ) {
        ToggleRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Enable AndroLLM voice mode",
            subtitle = if (s.enabled) "Enabled - start it below" else "Off (Jarvis voice is used)",
            checked = s.enabled,
            onCheckedChange = { on ->
                update { it.copy(enabled = on) }
                if (!on) VoiceAssistant.stop(context)
            }
        )
        if (s.enabled) {
            val blocked = if (!running) VoiceAssistant.blockReason(context) else null
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CloudCapsuleButton(
                    text = if (running) "Stop" else "Start",
                    icon = Icons.Filled.PowerSettingsNew,
                    onClick = {
                        if (running) {
                            VoiceAssistant.stop(context)
                        } else if (!PermissionHelper.hasRecordAudioPermission(context)) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            message = VoiceAssistant.start(context)
                        }
                    }
                )
                Text(
                    text = when {
                        running && ui.active -> "Running · ${ui.phase.name.lowercase().replace('_', ' ')}"
                        running -> "Starting…"
                        ui.error != null -> ui.error.orEmpty()
                        jarvisSession -> "Jarvis voice session is active"
                        jarvisStandby -> "Jarvis \"Hey Jarvis\" standby owns the mic"
                        blocked != null -> blocked
                        else -> "Stopped"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            SettingRow(Icons.Filled.Hearing, "Wake engine", VoiceSettings.wakeLabel(s.wakeEngine)) { choice = "wake" }
            if (s.wakeEngine == VoiceSettings.WAKE_OPENWAKEWORD) {
                SettingRow(Icons.Filled.GraphicEq, "openWakeWord model", VoiceModels.displayName(s.openWakeWordModel)) { choice = "oww" }
            }
            SettingRow(Icons.Filled.Mic, "Speech recognition", VoiceSettings.sttLabel(s.sttEngine)) { choice = "stt" }
            SettingRow(Icons.Filled.RecordVoiceOver, "Voice output", VoiceSettings.ttsLabel(s.ttsEngine)) { choice = "tts" }
            if (s.ttsEngine == VoiceSettings.TTS_GEMINI) {
                SettingRow(Icons.Filled.MusicNote, "Gemini voice", s.ttsVoice) { choice = "gvoice" }
            }
            if ((s.sttEngine == VoiceSettings.STT_GEMINI || s.ttsEngine == VoiceSettings.TTS_GEMINI) &&
                !GeminiApiKeyProvider.isConfigured()
            ) {
                AndroWarning("Gemini REST engines use your Jarvis Gemini API key, which is not set - Vosk / Jarvis TTS will be used.")
            }
            if (s.sttEngine == VoiceSettings.STT_SHERPA || s.ttsEngine == VoiceSettings.TTS_SHERPA ||
                s.ttsEngine == VoiceSettings.TTS_PIPER || s.wakeEngine == VoiceSettings.WAKE_SHERPA
            ) {
                AndroInfo("sherpa / Piper engines use the models downloaded in \"Offline engines\" below.")
            }
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)

            ToggleRow(Icons.Filled.Hearing, "Wake word", "Listen for the wake word between turns (off = one turn per start)", s.enableWakeWord) { on ->
                update { it.copy(enableWakeWord = on) }
            }
            if (s.enableWakeWord) {
                SliderSetting(
                    label = { "Wake sensitivity: ${"%.2f".format(it)}" },
                    value = s.sensitivity,
                    valueRange = VoiceSettings.MIN_SENSITIVITY..VoiceSettings.MAX_SENSITIVITY,
                    onCommit = { v -> update { it.copy(sensitivity = v) } }
                )
            }
            SliderSetting(
                label = { "End of speech after ${"%.1f".format(it / 1000f)} s silence" },
                value = s.silenceTimeoutMs.toFloat(),
                valueRange = VoiceSettings.MIN_SILENCE_TIMEOUT_MS.toFloat()..VoiceSettings.MAX_SILENCE_TIMEOUT_MS.toFloat(),
                onCommit = { v -> update { it.copy(silenceTimeoutMs = v.toInt()) } }
            )
            SliderSetting(
                label = { "Speaking speed: ${"%.2f".format(it)}x" },
                value = s.speakingSpeed,
                valueRange = VoiceSettings.MIN_SPEED..VoiceSettings.MAX_SPEED,
                onCommit = { v -> update { it.copy(speakingSpeed = v) } }
            )
            ToggleRow(Icons.Filled.Repeat, "Continuous conversation", "Keep listening after each answer (ignored in battery saver)", s.continuousConversation) { on ->
                update { it.copy(continuousConversation = on) }
            }
            ToggleRow(Icons.Filled.RecordVoiceOver, "Read answers aloud", "Speak replies sentence by sentence while they stream", s.autoReadAnswers) { on ->
                update { it.copy(autoReadAnswers = on) }
            }
            ToggleRow(Icons.Filled.VerifiedUser, "Spoken confirmations", "Ask high-risk actions aloud and listen for yes / no (Jarvis's dialog stays live)", s.spokenConfirmations) { on ->
                update { it.copy(spokenConfirmations = on) }
            }
            ToggleRow(Icons.Filled.BatterySaver, "Battery saver listening", "Single-thread STT, no live partials, no continuous mode, wake model skips silence", s.batterySaver) { on ->
                update { it.copy(batterySaver = on) }
            }
            ToggleRow(Icons.Filled.BatterySaver, "Only while charging", "Stop listening when unplugged", s.chargingOnly) { on ->
                update { it.copy(chargingOnly = on) }
            }
            ToggleRow(if (s.offlineOnly) Icons.Filled.CloudOff else Icons.Filled.Cloud, "Offline-only voice", "Never use Gemini REST speech engines", s.offlineOnly) { on ->
                update { it.copy(offlineOnly = on, cloudFallback = !on) }
            }
            ToggleRow(Icons.Filled.Speed, "Low-latency mode", "Skip conversation history for the fastest first token", s.lowLatencyMode) { on ->
                update { it.copy(lowLatencyMode = on) }
            }
            ToggleRow(Icons.Filled.Mic, "Noise suppression / echo cancellation", "Use the system voice-recognition audio source", s.noiseSuppression) { on ->
                update { it.copy(noiseSuppression = on, echoCancellation = on) }
            }
            ToggleRow(Icons.Filled.MusicNote, "Start / end chimes", "Short tones when listening starts and a turn ends", s.playStartSound) { on ->
                update { it.copy(playStartSound = on, playEndSound = on) }
            }
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            ToggleRow(Icons.Filled.Layers, "Floating overlay", "Show the AndroLLM overlay above other apps during a turn (separate from Jarvis's overlay)", s.autoOpenOverlay) { on ->
                update { it.copy(autoOpenOverlay = on) }
                if (on && !OverlayPermission.isGranted(context)) OverlayPermission.openSettings(context)
            }
            if (s.autoOpenOverlay) {
                if (!OverlayPermission.isGranted(context)) {
                    AndroWarning("\"Display over other apps\" is not granted - the overlay stays hidden.")
                }
                SliderSetting(
                    label = { "Overlay opacity: ${(it * 100).toInt()}%" },
                    value = s.overlayTransparency,
                    valueRange = VoiceSettings.MIN_OVERLAY_TRANSPARENCY..VoiceSettings.MAX_OVERLAY_TRANSPARENCY,
                    onCommit = { v -> update { it.copy(overlayTransparency = v) } }
                )
                SliderSetting(
                    label = { "Animation speed: ${"%.1f".format(it)}x" },
                    value = s.animationSpeed,
                    valueRange = VoiceSettings.MIN_ANIMATION_SPEED..VoiceSettings.MAX_ANIMATION_SPEED,
                    onCommit = { v -> update { it.copy(animationSpeed = v) } }
                )
            }
            AndroInfo("Voice commands: open settings / models, new chat, delete or summarize the conversation, stop speaking, mute / unmute, offline mode on / off, switch theme, disable voice.")
        }
        message?.let {
            HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper), modifier = Modifier.weight(1f))
                TextButton(onClick = { message = null }) { Text("OK", color = MaterialTheme.ledger.deskInk) }
            }
        }
    }

    when (choice) {
        "wake" -> VoiceChoiceDialog("Wake engine", VoiceSettings.WAKE_ENGINES, s.wakeEngine, { VoiceSettings.wakeLabel(it) }, { choice = null }) { id ->
            update { it.copy(wakeEngine = id) }
        }
        "oww" -> {
            val models = remember { VoiceModels.availableOpenWakeWordModels(context) }
            VoiceChoiceDialog("openWakeWord model", models, s.openWakeWordModel, VoiceModels::displayName, { choice = null }) { id ->
                update { it.copy(openWakeWordModel = id) }
            }
        }
        "stt" -> VoiceChoiceDialog("Speech recognition", VoiceSettings.STT_ENGINES, s.sttEngine, { VoiceSettings.sttLabel(it) }, { choice = null }) { id ->
            update { it.copy(sttEngine = id) }
        }
        "tts" -> VoiceChoiceDialog("Voice output", VoiceSettings.TTS_ENGINES, s.ttsEngine, { VoiceSettings.ttsLabel(it) }, { choice = null }) { id ->
            update { it.copy(ttsEngine = id) }
        }
        "gvoice" -> VoiceChoiceDialog("Gemini voice", GEMINI_VOICES, s.ttsVoice, { it }, { choice = null }) { id ->
            update { it.copy(ttsVoice = id) }
        }
    }
}

/** whisper.cpp model downloads + transcription tuning (used by the AndroLLM voice mode). */
@Composable
internal fun WhisperModelsCard() {
    val context = LocalContext.current
    val store = remember { VoiceSettingsStore.get(context) }
    val s by store.state.collectAsStateWithLifecycle()
    val manager = remember { WhisperModelManager.get(context) }
    val states by manager.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var nativeOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        nativeOk = withContext(Dispatchers.Default) { WhisperNative.loaded }
        manager.refresh()
    }

    AndroCard(
        title = "whisper.cpp models",
        subtitle = "Offline speech recognition for the AndroLLM voice mode. Downloads are user-initiated (Hugging Face)."
    ) {
        if (nativeOk == false) {
            AndroWarning("libwhisper.so is not packaged in this build - whisper.cpp cannot run (native build not enabled).")
        }
        for (model in WhisperModels.ALL) {
            val state = states[model.id] ?: WhisperDownloadState.NotInstalled
            val selected = s.whisperModel == model.id
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selected,
                        enabled = state is WhisperDownloadState.Installed,
                        onClick = { scope.launch { store.update { it.copy(whisperModel = model.id) } } }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${model.displayName} · ${model.sizeLabel}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = MaterialTheme.ledger.deskPaper)
                        )
                        Text(
                            when (state) {
                                is WhisperDownloadState.Installed -> if (selected) "Installed · selected" else "Installed"
                                is WhisperDownloadState.Downloading -> "Downloading ${(state.fraction * 100).toInt()}%"
                                is WhisperDownloadState.Failed -> "Failed: ${state.message}"
                                else -> if (model.multilingual) "Multilingual" else "English only"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                        )
                    }
                    when (state) {
                        is WhisperDownloadState.Installed -> IconButton(onClick = { scope.launch { manager.delete(model) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.ledger.deskInk)
                        }
                        is WhisperDownloadState.Downloading -> TextButton(onClick = { manager.cancelDownload(model.id) }) {
                            Text("Cancel", color = MaterialTheme.ledger.deskInk)
                        }
                        else -> IconButton(onClick = { manager.startDownload(model) }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download", tint = MaterialTheme.ledger.lampDeep)
                        }
                    }
                }
                if (state is WhisperDownloadState.Downloading) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.ledger.deskHairline)
        SettingRow(Icons.Filled.GraphicEq, "Language", if (s.sttLanguage == "auto") "Auto-detect" else s.sttLanguage) {
            scope.launch { store.update { it.copy(sttLanguage = if (it.sttLanguage == "auto") "en" else "auto") } }
        }
        ToggleRow(Icons.Filled.GraphicEq, "Translate to English", "Translate non-English speech (multilingual models)", s.sttTranslate) { on ->
            scope.launch { store.update { it.copy(sttTranslate = on) } }
        }
        ToggleRow(Icons.Filled.GraphicEq, "Live partial transcripts", "Re-transcribe a sliding window while you speak", s.sttStreaming) { on ->
            scope.launch { store.update { it.copy(sttStreaming = on) } }
        }
        SliderSetting(
            label = { "Beam size: ${it.toInt()}" },
            value = s.sttBeamSize.toFloat(),
            valueRange = VoiceSettings.MIN_STT_BEAM.toFloat()..VoiceSettings.MAX_STT_BEAM.toFloat(),
            onCommit = { v -> scope.launch { store.update { it.copy(sttBeamSize = v.toInt()) } } },
            steps = VoiceSettings.MAX_STT_BEAM - VoiceSettings.MIN_STT_BEAM - 1
        )
        SliderSetting(
            label = { "Max recording: ${it.toInt()} s" },
            value = s.sttMaxSeconds.toFloat(),
            valueRange = VoiceSettings.MIN_STT_MAX_SECONDS.toFloat()..VoiceSettings.MAX_STT_MAX_SECONDS.toFloat(),
            onCommit = { v -> scope.launch { store.update { it.copy(sttMaxSeconds = v.toInt()) } } }
        )
    }
}

@Composable
private fun VoiceChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    label: (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.isEmpty()) Text("Nothing available.")
                for (option in options) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option); onDismiss() })
                        Text(label(option), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun AndroCard(title: String, subtitle: String, content: @Composable () -> Unit) {
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
private fun AndroWarning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.ledger.emberRed, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskPaper))
    }
}

@Composable
private fun AndroInfo(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
