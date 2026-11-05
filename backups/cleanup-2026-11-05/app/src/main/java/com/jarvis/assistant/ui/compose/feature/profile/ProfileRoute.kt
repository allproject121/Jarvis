package com.jarvis.assistant.ui.compose.feature.profile

import android.app.Application
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.compose.components.CloudAdaptiveNavigation
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.CloudUsageBar
import com.jarvis.assistant.ui.compose.components.SectionHeader
import com.jarvis.assistant.ui.compose.components.bounceClick
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * Profile (ported from AndroLLM). Jarvis has no Firebase account, so identity
 * maps to the local persona stored in AppPreferences; account actions
 * (sign-out / delete account) are shown disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ProfileViewModel = viewModel { ProfileViewModel(application) }

    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var showPersonalityDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = ComposeRoutes.PROFILE,
            onTabSelected = { tab -> if (tab.route != ComposeRoutes.PROFILE) navigate(tab.route) },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Text(
                            text = "Profile",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.ledger.deskPaper
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.ledger.deskNight)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    IdentityHeader(identity = identity)
                }

                item {
                    SectionHeader(title = "Your Intelligence", subtitle = "Live on-device statistics")
                    Spacer(modifier = Modifier.height(10.dp))
                    StatsGrid(stats = stats)
                }

                item {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        CloudUsageBar(
                            label = "Model Storage",
                            valueText = if (stats.storageTotalBytes > 0) {
                                "${Formatter.formatShortFileSize(context, stats.storageFreeBytes)} free of " +
                                    Formatter.formatShortFileSize(context, stats.storageTotalBytes)
                            } else {
                                "Unavailable"
                            },
                            fraction = if (stats.storageTotalBytes > 0) {
                                stats.storageFreeBytes.toFloat() / stats.storageTotalBytes
                            } else {
                                0f
                            },
                            accent = MaterialTheme.ledger.lampAmber
                        )
                    }
                }

                if (stats.readyModelNames.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "Downloaded Models",
                            subtitle = "${stats.readyModelNames.size} ready on this device"
                        )
                    }
                    items(stats.readyModelNames, key = { it }) { name ->
                        ModelRow(name = name)
                    }
                }

                item {
                    SectionHeader(title = "Spaces & Account", subtitle = "Everything about your AI")
                    Spacer(modifier = Modifier.height(10.dp))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ActionRow(
                                icon = Icons.Filled.Star,
                                title = "Edit Profile",
                                subtitle = "Your name, assistant name & details",
                                accent = MaterialTheme.ledger.lampAmber,
                                onClick = { showEditDialog = true }
                            )
                            ActionRow(
                                icon = Icons.Filled.Face,
                                title = "Personality",
                                subtitle = identity.personality.ifBlank { "Default" },
                                accent = MaterialTheme.ledger.lampGlow,
                                onClick = { showPersonalityDialog = true }
                            )
                            ActionRow(
                                icon = Icons.Filled.Code,
                                title = "Prompt Studio",
                                subtitle = "Curated prompt templates",
                                accent = MaterialTheme.ledger.lampGlow,
                                onClick = { navigate(ComposeRoutes.PROMPTS) }
                            )
                            ActionRow(
                                icon = Icons.Filled.Cloud,
                                title = "Cloud Brains",
                                subtitle = "Gemini / OpenAI keys & routing mode",
                                accent = MaterialTheme.ledger.lampAmber,
                                onClick = { navigate(ComposeRoutes.CLOUD) }
                            )
                            ActionRow(
                                icon = Icons.Filled.Speed,
                                title = "Developer Mode",
                                subtitle = "Live engine & device telemetry",
                                accent = MaterialTheme.ledger.lampAmber,
                                onClick = { navigate(ComposeRoutes.DEVELOPER) }
                            )
                            ActionRow(
                                icon = Icons.Filled.Tune,
                                title = "Settings",
                                subtitle = "Appearance, generation, privacy",
                                accent = MaterialTheme.ledger.lampGlow,
                                onClick = { navigate(ComposeRoutes.SETTINGS) }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showEditDialog) {
        EditProfileDialog(
            identity = identity,
            onDismiss = { showEditDialog = false },
            onSave = { name, assistant, location, birthday, age ->
                showEditDialog = false
                viewModel.saveIdentity(name, assistant, location, birthday, age)
            }
        )
    }

    if (showPersonalityDialog) {
        AlertDialog(
            onDismissRequest = { showPersonalityDialog = false },
            title = { Text("Personality") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    viewModel.personalities.forEach { option ->
                        val selected = option == identity.personality
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPersonalityDialog = false
                                    viewModel.setPersonality(option)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = option)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPersonalityDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun EditProfileDialog(
    identity: ProfileIdentity,
    onDismiss: () -> Unit,
    onSave: (name: String, assistant: String, location: String, birthday: String, age: String) -> Unit
) {
    var name by remember { mutableStateOf(identity.userName) }
    var assistant by remember { mutableStateOf(identity.assistantName) }
    var location by remember { mutableStateOf(identity.location) }
    var birthday by remember { mutableStateOf(identity.birthday) }
    var age by remember { mutableStateOf(identity.age) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.ledger.lampGlow, MaterialTheme.ledger.lampAmber, MaterialTheme.ledger.lampDeep)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initialsOf(name),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.inkOnLamp
                        )
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Your name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = assistant,
                    onValueChange = { assistant = it },
                    singleLine = true,
                    label = { Text("Assistant name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    singleLine = true,
                    label = { Text("Location") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = birthday,
                    onValueChange = { birthday = it },
                    singleLine = true,
                    label = { Text("Birthday") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    singleLine = true,
                    label = { Text("Age") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Avatar upload needs a cloud account and is not available in Jarvis.",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, assistant, location, birthday, age) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun IdentityHeader(identity: ProfileIdentity) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.ledger.lampGlow, MaterialTheme.ledger.lampAmber, MaterialTheme.ledger.lampDeep)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initialsOf(identity.userName),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.inkOnLamp
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = identity.userName.ifBlank { "Local Intelligence" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            Text(
                text = "Assistant: ${identity.assistantName.ifBlank { "JARVIS" }}" +
                    (identity.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.ledger.deskInk
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudChip(text = "Local Profile", accentColor = MaterialTheme.ledger.lampGlow, icon = Icons.Filled.Memory)
                if (identity.personality.isNotBlank()) {
                    CloudChip(text = identity.personality, accentColor = MaterialTheme.ledger.lampAmber, icon = Icons.Filled.Face)
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: ProfileStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCell(
            value = if (stats.conversationTurns >= 0) stats.conversationTurns.toString() else "—",
            label = "Chat Turns",
            accent = MaterialTheme.ledger.lampGlow,
            modifier = Modifier.weight(1f)
        )
        StatCell(
            value = if (stats.memoryCount >= 0) stats.memoryCount.toString() else "—",
            label = "Memories",
            accent = MaterialTheme.ledger.lampAmber,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCell(
            value = stats.modelsReady.toString(),
            label = "Models Ready",
            accent = MaterialTheme.ledger.lampGlow,
            modifier = Modifier.weight(1f)
        )
        StatCell(
            value = stats.modelCatalogSize.toString(),
            label = "In Catalog",
            accent = MaterialTheme.ledger.lampAmber,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.ledger.deskInk
                )
            )
        }
    }
}

@Composable
private fun ModelRow(name: String) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = null,
                tint = MaterialTheme.ledger.lampGlow,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .bounceClick(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.ledger.deskInk
                )
            )
        }
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "AI"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}
