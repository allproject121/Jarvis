package com.jarvis.assistant.ui.compose.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger

internal const val SETTINGS_ACCORDION_DURATION_MS = 220

/**
 * Exclusive accordion ids for Settings. Null means every group is collapsed.
 * Mirrors AndroLLM's groups, remapped onto what Jarvis actually supports.
 */
internal enum class SettingsGroup {
    Account,
    AiEngine,
    Appearance,
    Storage,
    Memory,
    VoiceAssistant,
    SpeechRecognition,
    TextNormalization,
    Automation,
    UiAutomation,
    DevicePermissions,
    Mcp,
    Safety,
    Developer,
    About,
}

internal fun SettingsGroup.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) ||
        keywords.any { it.contains(q, ignoreCase = true) }
}

internal val SettingsGroup.title: String
    get() = when (this) {
        SettingsGroup.Account -> "Account & Profile"
        SettingsGroup.AiEngine -> "AI Engine & API Keys"
        SettingsGroup.Appearance -> "Appearance"
        SettingsGroup.Storage -> "Storage"
        SettingsGroup.Memory -> "Memory"
        SettingsGroup.VoiceAssistant -> "Voice Assistant"
        SettingsGroup.SpeechRecognition -> "Speech Recognition"
        SettingsGroup.TextNormalization -> "Text Normalization"
        SettingsGroup.Automation -> "Automation / Tool Calling"
        SettingsGroup.UiAutomation -> "UI Automation"
        SettingsGroup.DevicePermissions -> "Device Permissions"
        SettingsGroup.Mcp -> "MCP Servers"
        SettingsGroup.Safety -> "Safety & Privacy"
        SettingsGroup.Developer -> "Developer Options"
        SettingsGroup.About -> "About"
    }

internal val SettingsGroup.keywords: List<String>
    get() = when (this) {
        SettingsGroup.Account -> listOf(
            "profile", "name", "account", "assistant name", "persona", "personality"
        )
        SettingsGroup.AiEngine -> listOf(
            "api key", "gemini", "openai", "chatgpt", "ollama", "endpoint", "model", "local",
            "offline", "hybrid", "hermes", "provider", "cloud", "engine", "llama"
        )
        SettingsGroup.Appearance -> listOf(
            "theme", "dark", "light", "amoled", "text size", "density", "blur", "motion", "font", "display"
        )
        SettingsGroup.Storage -> listOf("cache", "free space", "models", "storage", "disk")
        SettingsGroup.Memory -> listOf("memories", "export", "delete", "inspector", "mem0", "on-device")
        SettingsGroup.VoiceAssistant -> listOf(
            "wake word", "overlay", "hands-free", "assistant", "tts", "voice", "language",
            "sensitivity", "sound", "owner", "enroll", "battery"
        )
        SettingsGroup.SpeechRecognition -> listOf("whisper", "vosk", "stt", "recognition")
        SettingsGroup.TextNormalization -> listOf("numbers", "currency", "tts", "speech", "normalize", "sentence")
        SettingsGroup.Automation -> listOf(
            "tool calling", "confirmations", "autonomous", "direct", "permissions",
            "sms", "phone", "calendar", "contacts", "tools", "auto reply"
        )
        SettingsGroup.UiAutomation -> listOf("accessibility", "ui control", "service", "scroll")
        SettingsGroup.DevicePermissions -> listOf(
            "sms", "phone", "calendar", "contacts", "location", "microphone", "notifications", "permissions"
        )
        SettingsGroup.Mcp -> listOf("mcp", "server", "remote tools", "token")
        SettingsGroup.Safety -> listOf("privacy", "safety", "shield")
        SettingsGroup.Developer -> listOf("developer", "logs", "diagnostics", "telemetry", "links")
        SettingsGroup.About -> listOf("version", "privacy", "about", "classic", "jarvis settings")
    }

@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text("Search settings", color = MaterialTheme.ledger.deskInk)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.ledger.lampDeep
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.ledger.lampAmber,
            unfocusedBorderColor = MaterialTheme.ledger.deskHairline,
            focusedTextColor = MaterialTheme.ledger.deskPaper,
            unfocusedTextColor = MaterialTheme.ledger.deskPaper,
            cursorColor = MaterialTheme.ledger.lampAmber
        )
    )
}

/** One entry in the quick-actions strip. */
internal data class QuickAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsQuickActions(
    actions: List<QuickAction>,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Quick actions",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEach { action ->
                    QuickActionChip(icon = action.icon, label = action.label, onClick = action.onClick)
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.ledger.lampGlow.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.ledger.deskPaper
            )
        )
    }
}

@Composable
internal fun SettingsAccordionHeader(
    group: SettingsGroup,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    reduceMotion: Boolean = false
) {
    val duration = if (reduceMotion) 0 else SETTINGS_ACCORDION_DURATION_MS
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        label = "settingsChevron"
    )
    val state = if (expanded) "Expanded" else "Collapsed"

    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    stateDescription = state
                }
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.ledger.lampDeep,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank() && !expanded) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse ${group.title}" else "Expand ${group.title}",
                tint = MaterialTheme.ledger.deskInk,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevron)
            )
        }
    }
}

/**
 * Places an accordion in a [LazyListScope]. Only one group is expanded at a
 * time; the body is composed only while open. The open header is sticky.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.settingsAccordionItem(
    group: SettingsGroup,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    visible: Boolean,
    subtitle: String? = null,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    if (!visible) return
    val duration = if (reduceMotion) 0 else SETTINGS_ACCORDION_DURATION_MS
    val enter = fadeIn(animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing)) +
        expandVertically(
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        )
    val exit = fadeOut(animationSpec = tween(durationMillis = (duration * 0.7f).toInt(), easing = FastOutSlowInEasing)) +
        shrinkVertically(
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )

    if (expanded) {
        stickyHeader(key = "settings-head-${group.name}") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.ledger.deskWalnut)
                    .padding(bottom = 8.dp)
            ) {
                SettingsAccordionHeader(
                    group = group,
                    icon = icon,
                    expanded = true,
                    onToggle = onToggle,
                    subtitle = subtitle,
                    reduceMotion = reduceMotion
                )
            }
        }
        item(key = "settings-body-${group.name}") {
            AnimatedVisibility(
                visible = true,
                enter = enter,
                exit = exit
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    } else {
        item(key = "settings-${group.name}") {
            SettingsAccordionHeader(
                group = group,
                icon = icon,
                expanded = false,
                onToggle = onToggle,
                subtitle = subtitle,
                reduceMotion = reduceMotion
            )
        }
    }
}
