package com.jarvis.assistant.ui.compose.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Collapsible groups for the AndroLLM feature layer settings. Each section is owned by its
 * feature package; this file only mounts them inside the Compose Settings screen.
 */
@Composable
internal fun AndroLlmFeatureSections(onOpenToolDebug: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "AndroLLM features",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = "Optional add-ons. Gemini Live stays Jarvis' primary brain.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        CollapsibleGroup("engines", "Engines · LiteRT-LM & cloud gateway") { EnginesSettingsSection() }
        CollapsibleGroup("models", "Models & downloads") { ModelsSettingsSection() }
        CollapsibleGroup("chat", "Chat, attachments & export") { ChatSettingsSection() }
        CollapsibleGroup("memory", "Memory") { MemorySettingsSection() }
        CollapsibleGroup("voice", "Voice · wake word, ASR, TTS, normalization") { VoiceSettingsSection() }
        CollapsibleGroup("agent", "Agent · tools, planner & Tool Debug") { AgentSettingsSection(onOpenToolDebug = onOpenToolDebug) }
        CollapsibleGroup("automation", "Screen automation & tools") { AutomationSettingsSection() }
        CollapsibleGroup("permissions", "Permissions & feature access") { PermissionsSettingsSection() }
    }
}

@Composable
private fun CollapsibleGroup(key: String, title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content() }
        }
    }
}
