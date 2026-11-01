package com.jarvis.assistant.ui.compose.feature.setup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.ui.compose.components.CloudAtmosphericBackground
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * First-run "Let's set up Jarvis" (ported from AndroLLM's PermissionSetupScreen).
 * Explains each capability before its permission is requested, and adds a
 * model step that links to the Models screen for on-device downloads.
 * Finish / Skip → [ComposeRoutes.HOME].
 */
@Composable
fun SetupRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: SetupViewModel = viewModel { SetupViewModel(application) }
    val activity = remember(context) { context.findActivity() }

    var refreshTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    // Returning from Android settings screens (or the Models screen) → re-read everything.
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    val gates = viewModel.gates
    val states = remember(gates, refreshTick) {
        gates.associateWith { viewModel.status(it, activity) }
    }
    val readyModels = remember(refreshTick) { viewModel.readyLocalModelCount() }
    val hasGeminiKey = remember(refreshTick) { viewModel.hasGeminiKey() }
    val hasOpenAiKey = remember(refreshTick) { viewModel.hasOpenAiKey() }

    val grantable = states.filterValues { it != SetupPermissionState.NOT_REQUIRED }
    val enabled = grantable.values.count { it == SetupPermissionState.GRANTED }

    val finish: () -> Unit = {
        viewModel.markSetupDone()
        navigate(ComposeRoutes.HOME)
    }

    CloudAtmosphericBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Top bar: mark + Skip ──────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CloudBugdroidLogo(size = 44.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Jarvis",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = finish) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            // ── Header + progress ─────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Let's set up Jarvis",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pick a brain, then give Jarvis access to the features you want to use.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.ledger.deskInk,
                        lineHeight = 22.sp
                    )
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (grantable.isEmpty()) "Nothing to grant — you're all set" else
                            "$enabled of ${grantable.size} enabled",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    if (grantable.isNotEmpty()) {
                        Text(
                            text = "${(enabled * 100f / grantable.size).toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.lampDeep,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (grantable.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { enabled.toFloat() / grantable.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.ledger.lampAmber,
                        trackColor = MaterialTheme.ledger.deskHairline
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── Cards ─────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StaggeredEntrance(0) { SectionLabel("STEP 1 — CHOOSE A BRAIN") }
                }
                item {
                    StaggeredEntrance(0, instant = true) {
                        BrainCard(
                            icon = Icons.Filled.Layers,
                            title = "On-device model",
                            description = "Private, offline GGUF inference",
                            explanation = "Download a local model so Jarvis keeps working with no network. " +
                                "Smaller models install in under a minute.",
                            ready = readyModels > 0,
                            readyLabel = "✓ $readyModels of ${viewModel.localModelCatalogSize} downloaded",
                            actionText = if (readyModels > 0) "Manage Models" else "Download a Model",
                            onAction = { navigate(ComposeRoutes.MODELS) }
                        )
                    }
                }
                item {
                    StaggeredEntrance(0, instant = true) {
                        BrainCard(
                            icon = Icons.Filled.Cloud,
                            title = "Cloud brain",
                            description = "Gemini Live / ChatGPT keys",
                            explanation = "Optional. Add your own Gemini or OpenAI API key for real-time voice " +
                                "and deeper reasoning. Keys are stored in encrypted storage on this device.",
                            ready = hasGeminiKey || hasOpenAiKey,
                            readyLabel = "✓ " + listOfNotNull(
                                "Gemini".takeIf { hasGeminiKey },
                                "OpenAI".takeIf { hasOpenAiKey }
                            ).joinToString(" + ") + " key configured",
                            actionText = if (hasGeminiKey || hasOpenAiKey) "Manage Keys" else "Add API Key",
                            onAction = { navigate(ComposeRoutes.CLOUD) }
                        )
                    }
                }

                val optional = states.filterValues { it != SetupPermissionState.NOT_REQUIRED }
                if (optional.isNotEmpty()) {
                    item {
                        StaggeredEntrance(1) {
                            Spacer(modifier = Modifier.height(4.dp))
                            SectionLabel("STEP 2 — ENABLE WHAT YOU'LL USE")
                        }
                    }
                    items(optional.keys.toList(), key = { it.id }) { gate ->
                        StaggeredEntrance(0, instant = true) {
                            PermissionSetupCard(
                                gate = gate,
                                state = states.getValue(gate),
                                onRequest = {
                                    viewModel.onRequested(gate)
                                    val perms = viewModel.runtimePermissions(gate)
                                    if (perms.isNotEmpty()) {
                                        permissionLauncher.launch(perms.toTypedArray())
                                    } else {
                                        refreshTick++
                                    }
                                },
                                onOpenSettings = {
                                    if (!viewModel.openSettings(gate)) refreshTick++
                                }
                            )
                        }
                    }
                }

                val notRequired = states.filterValues { it == SetupPermissionState.NOT_REQUIRED }
                if (notRequired.isNotEmpty()) {
                    item {
                        StaggeredEntrance(1) {
                            Spacer(modifier = Modifier.height(4.dp))
                            SectionLabel("NOT REQUIRED ON THIS DEVICE")
                        }
                    }
                    items(notRequired.keys.toList(), key = { it.id }) { gate ->
                        StaggeredEntrance(0, instant = true) {
                            NotRequiredCard(gate = gate)
                        }
                    }
                }

                item {
                    StaggeredEntrance(2) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Everything optional can be changed later in Android Settings, " +
                                "and accessibility can be revoked whenever you like.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.ledger.deskInk,
                                lineHeight = 17.sp
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            // ── Finish ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                CloudCapsuleButton(
                    text = "Finish Setup",
                    onClick = finish,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BrainCard(
    icon: ImageVector,
    title: String,
    description: String,
    explanation: String,
    ready: Boolean,
    readyLabel: String,
    actionText: String,
    onAction: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconWell(icon)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            lineHeight = 16.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                CloudChip(
                    text = if (ready) "✓ Ready" else "○ Not set",
                    accentColor = if (ready) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.lampAmber
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInk,
                    lineHeight = 17.sp
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (ready) {
                Text(
                    text = readyLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.ledger.lampDeep,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            CloudCapsuleButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PermissionSetupCard(
    gate: SetupGate,
    state: SetupPermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconWell(iconFor(gate.id))
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = gate.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = gate.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            lineHeight = 16.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                StatusChip(state)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = gate.explanation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInk,
                    lineHeight = 17.sp
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                SetupPermissionState.GRANTED -> {
                    Text(
                        text = if (gate.needsSettingsScreen) "✓ Enabled in Settings" else "✓ Granted",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.lampDeep,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                SetupPermissionState.DENIED -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CloudCapsuleButton(
                                text = "Enable",
                                onClick = onRequest,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            CloudCapsuleButton(
                                text = "Open Settings",
                                onClick = onOpenSettings,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Not enabled — some features may not work.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.deskInk
                            )
                        )
                    }
                }

                SetupPermissionState.PERMANENTLY_DENIED -> {
                    Column {
                        Text(
                            text = "Permission blocked — open Android Settings to enable it.",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.emberRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CloudCapsuleButton(
                            text = "Open Settings",
                            onClick = onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                SetupPermissionState.NEEDS_SETTINGS -> {
                    CloudCapsuleButton(
                        text = if (gate.kind == SetupGateKind.ACCESSIBILITY) "Enable Accessibility" else "Open Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SetupPermissionState.NOT_REQUIRED -> Unit
            }
        }
    }
}

@Composable
private fun NotRequiredCard(gate: SetupGate) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconWell(iconFor(gate.id), small = true)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gate.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Text(
                    text = gate.explanation,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.ledger.deskInk,
                        lineHeight = 15.sp
                    )
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            CloudChip(
                text = "Not required",
                accentColor = MaterialTheme.ledger.deskInk
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.ledger.deskInk,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun StatusChip(state: SetupPermissionState) {
    val (text, color) = when (state) {
        SetupPermissionState.GRANTED -> "✓ Granted" to MaterialTheme.ledger.lampDeep
        SetupPermissionState.DENIED -> "○ Not enabled" to MaterialTheme.ledger.lampAmber
        SetupPermissionState.PERMANENTLY_DENIED -> "⚠ Blocked" to MaterialTheme.ledger.emberRed
        SetupPermissionState.NEEDS_SETTINGS -> "○ Needs settings" to MaterialTheme.ledger.lampAmber
        SetupPermissionState.NOT_REQUIRED -> "Not required" to MaterialTheme.ledger.deskInk
    }
    CloudChip(text = text, accentColor = color)
}

@Composable
private fun IconWell(icon: ImageVector, small: Boolean = false) {
    val size = if (small) 38.dp else 44.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampHalo.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(if (small) 20.dp else 24.dp)
        )
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "voice_assistant" -> Icons.Filled.Mic
    "notifications" -> Icons.Filled.Notifications
    "accessibility" -> Icons.Filled.TouchApp
    "notification_access" -> Icons.Filled.MarkChatRead
    "overlay" -> Icons.Filled.PictureInPicture
    "contacts" -> Icons.Filled.Contacts
    "phone" -> Icons.Filled.Call
    "sms" -> Icons.Filled.Sms
    "camera" -> Icons.Filled.PhotoCamera
    "location" -> Icons.Filled.Place
    "bluetooth" -> Icons.Filled.Bluetooth
    else -> Icons.Filled.Notifications
}

/** Resolves the hosting [Activity] from any context, or null when none exists. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
