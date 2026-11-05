package com.jarvis.assistant.ui.compose.feature.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import com.jarvis.assistant.ui.compose.theme.ledger
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.androllm.voice.AndroLlmVoiceConfig
import com.jarvis.assistant.androllm.voice.SherpaVoiceStatus
import com.jarvis.assistant.androllm.voice.VoiceDownloadState
import com.jarvis.assistant.androllm.voice.VoiceModelCatalog
import com.jarvis.assistant.androllm.voice.VoiceModelKind
import com.jarvis.assistant.androllm.voice.VoiceModelPack

/** Small chip under the orb summarising which voice engines are active. */
@Composable
internal fun VoiceEnginesChip(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Tune,
            contentDescription = "Voice engines",
            tint = MaterialTheme.ledger.deskPaper.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = summary,
            color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.75f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Engine/model selection + status for the voice screen. Jarvis's own engines are
 * listed first as the defaults; the sherpa-onnx alternatives can be switched on and
 * pointed at an installed model here (downloads live in Settings → Voice).
 */
@Composable
internal fun VoiceEnginesDialog(
    config: AndroLlmVoiceConfig,
    status: SherpaVoiceStatus,
    downloads: Map<String, VoiceDownloadState>,
    sessionOn: Boolean,
    standby: Boolean,
    onToggleWake: (Boolean) -> Unit,
    onToggleAsr: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onSelectPack: (VoiceModelPack) -> Unit,
    onDictate: () -> Unit,
    onStopDictation: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice engines", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SectionLabel("Jarvis (default)")
                EngineLine("Brain & voice", "Gemini Live" + if (sessionOn) " - active" else "")
                EngineLine("Wake word", "Vosk \"Hey Jarvis\"" + if (standby) " - listening" else "")
                EngineLine("Offline fallback", "Vosk ASR + Android TTS / PocketTTS")

                HorizontalDivider()
                SectionLabel("sherpa-onnx (optional)")
                if (status.nativeAvailable == false) {
                    Text(
                        "Native library unavailable: ${status.nativeError ?: "unknown error"}",
                        fontSize = 12.sp
                    )
                }

                SwitchLine(
                    title = "Wake word",
                    detail = if (config.wakeWordEnabled) status.wakeState.label else "Off",
                    checked = config.wakeWordEnabled,
                    onChange = onToggleWake
                )
                PackChoices(VoiceModelKind.WAKE_WORD, config.wakeWordPackId, downloads, onSelectPack)

                SwitchLine(
                    title = "Speech recognition",
                    detail = when {
                        !config.asrEnabled -> "Off"
                        status.dictation.active -> status.dictation.partial.ifBlank { "Listening..." }
                        status.dictation.error != null -> status.dictation.error
                        status.dictation.finalText.isNotBlank() -> "Last: \"${status.dictation.finalText}\""
                        else -> "Ready for dictation"
                    },
                    checked = config.asrEnabled,
                    onChange = onToggleAsr
                )
                PackChoices(VoiceModelKind.ASR, config.asrPackId, downloads, onSelectPack)
                if (config.asrEnabled) {
                    TextButton(
                        onClick = if (status.dictation.active) onStopDictation else onDictate,
                        enabled = !sessionOn && !standby
                    ) {
                        Text(if (status.dictation.active) "Stop dictation" else "Dictate (offline)")
                    }
                }

                SwitchLine(
                    title = "Offline TTS",
                    detail = if (!config.ttsEnabled) "Off" else if (status.speaking) "Speaking" else "Ready (read-aloud)",
                    checked = config.ttsEnabled,
                    onChange = onToggleTts
                )
                PackChoices(VoiceModelKind.TTS, config.ttsPackId, downloads, onSelectPack)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onOpenSettings) { Text("Models & settings") } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EngineLine(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(value, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PackChoices(
    kind: VoiceModelKind,
    selectedId: String,
    downloads: Map<String, VoiceDownloadState>,
    onSelect: (VoiceModelPack) -> Unit
) {
    VoiceModelCatalog.ofKind(kind).forEach { pack ->
        val state = downloads[pack.id]
        val installed = state is VoiceDownloadState.Installed
        val selected = pack.id == selectedId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = installed) { onSelect(pack) }
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (selected) "●" else "○", fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                pack.title + when (state) {
                    is VoiceDownloadState.Installed -> ""
                    is VoiceDownloadState.Downloading -> " - downloading ${(state.fraction * 100).toInt()}%"
                    is VoiceDownloadState.Failed -> " - download failed"
                    else -> " - not downloaded"
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
