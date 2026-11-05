package com.jarvis.assistant.ui.compose.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jarvis.assistant.androllm.permissions.PermissionHandler
import com.jarvis.assistant.androllm.permissions.PermissionManager
import com.jarvis.assistant.androllm.permissions.PermissionState
import com.jarvis.assistant.ui.compose.components.CloudCapsuleButton
import com.jarvis.assistant.ui.compose.components.CloudChip
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger

/**
 * Settings → Permissions & Access (ported from AndroLLM's
 * PermissionsAccessScreen / PermissionSetupCard). Every gate registered in
 * [PermissionManager] gets a card explaining what it is for, its live state
 * and the right action (Enable / Try Again / Open Settings). States are
 * re-read on resume (returning from system settings) and after each dialog.
 *
 * Also mounts [PermissionRequestHost], so lazy requests raised by tools are
 * shown while this section is visible.
 */
@Composable
fun PermissionsSettingsSection() {
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val manager = remember(context) { PermissionManager.getInstance(context) }

    // Bumped after a dialog result or after returning from system settings.
    var refreshTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    val handlers = remember(manager) { manager.handlers }
    val states = remember(handlers, refreshTick) {
        handlers.associateWith { manager.status(it, activity) }
    }

    PermissionRequestHost()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Each row explains what the permission is used for. Nothing here is required " +
                "to talk to Jarvis — enable only what you want. Tools also ask at the moment they need access.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.ledger.deskInk,
                lineHeight = 17.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        if (handlers.isEmpty()) {
            Text(
                text = "No permission gates are available on this device.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            )
        } else {
            handlers.forEach { handler ->
                PermissionAccessCard(
                    handler = handler,
                    state = states[handler] ?: PermissionState.UNAVAILABLE,
                    onRequest = {
                        manager.onPermissionRequested(handler)
                        val perms = manager.runtimePermissions(handler)
                        if (perms.isNotEmpty()) {
                            runCatching { permissionLauncher.launch(perms.toTypedArray()) }
                                .onFailure { Log.w(TAG, "permission dialog failed for ${handler.id}", it) }
                        } else {
                            refreshTick++
                        }
                    },
                    onOpenSettings = {
                        val opened = manager.openSettings(handler)
                        if (!opened) {
                            Log.w(TAG, "no system screen available for ${handler.id}")
                            refreshTick++
                        }
                    }
                )
            }
        }
    }
}

/**
 * Shows the newest lazy [com.jarvis.assistant.androllm.permissions.PermissionRequest]
 * raised through [PermissionManager.requestFeature] (e.g. by the SMS, calendar
 * or voice-recorder tools) as an explained dialog, then launches the system
 * permission dialog or the matching settings screen. Mount it on any screen
 * that should surface such requests (it renders nothing when idle).
 */
@Composable
fun PermissionRequestHost() {
    val context = LocalContext.current
    val manager = remember(context) { PermissionManager.getInstance(context) }
    val pending by manager.pendingRequest.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val request = pending ?: return
    val handler = manager.handler(request.handlerId)
    AlertDialog(
        onDismissRequest = { manager.consumeRequest(request) },
        title = {
            Text(
                "Allow ${handler?.title ?: request.feature.displayName}?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.deskPaper
            )
        },
        text = {
            Column {
                Text(request.reason, color = MaterialTheme.ledger.deskPaper)
                handler?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it.explanation, color = MaterialTheme.ledger.deskInk, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                manager.consumeRequest(request)
                if (handler != null) {
                    if (request.permissions.isNotEmpty()) {
                        manager.onPermissionRequested(handler)
                        runCatching { launcher.launch(request.permissions.toTypedArray()) }
                            .onFailure { manager.openSettings(handler) }
                    } else {
                        manager.openSettings(handler)
                    }
                }
            }) {
                Text(
                    if (request.permissions.isNotEmpty()) "Allow" else "Open Settings",
                    color = MaterialTheme.ledger.lampGlow
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { manager.consumeRequest(request) }) {
                Text("Not now", color = MaterialTheme.ledger.deskInk)
            }
        }
    )
}

/**
 * One gate card: what it does, why it's asked, its live status, and the
 * right action for that status (Enable / Try Again / Open Settings).
 */
@Composable
private fun PermissionAccessCard(
    handler: PermissionHandler,
    state: PermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PermissionIconBox(handler.id)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = handler.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = handler.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk,
                            lineHeight = 16.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                PermissionStatusChip(state)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = handler.explanation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInk,
                    lineHeight = 17.sp
                )
            )

            when (state) {
                PermissionState.GRANTED -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (handler.needsSettingsScreen) "✓ Enabled" else "✓ Granted",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.lampDeep,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                PermissionState.DENIED -> {
                    Spacer(modifier = Modifier.height(12.dp))
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
                        text = "Not enabled — features that need it will ask when used.",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }

                PermissionState.PERMANENTLY_DENIED -> {
                    Spacer(modifier = Modifier.height(12.dp))
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

                PermissionState.NEEDS_SETTINGS -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    CloudCapsuleButton(
                        text = if (handler.id == "accessibility") "Enable Accessibility" else "Open Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                PermissionState.NOT_REQUIRED, PermissionState.UNAVAILABLE -> Unit
            }
        }
    }
}

/** The live status chip (✓ Granted / ○ Not enabled / ⚠ Blocked / …). */
@Composable
private fun PermissionStatusChip(state: PermissionState) {
    val (text, color) = when (state) {
        PermissionState.GRANTED -> "✓ Granted" to MaterialTheme.ledger.lampDeep
        PermissionState.DENIED -> "○ Not enabled" to MaterialTheme.ledger.lampAmber
        PermissionState.PERMANENTLY_DENIED -> "⚠ Blocked" to MaterialTheme.ledger.emberRed
        PermissionState.NEEDS_SETTINGS -> "○ Needs settings" to MaterialTheme.ledger.lampAmber
        PermissionState.NOT_REQUIRED -> "Not required" to MaterialTheme.ledger.deskInk
        PermissionState.UNAVAILABLE -> "Unavailable" to MaterialTheme.ledger.deskInk
    }
    CloudChip(text = text, accentColor = color)
}

/** Feature glyph in a soft lamp-halo well. */
@Composable
private fun PermissionIconBox(id: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampHalo.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = permissionIconFor(id),
            contentDescription = null,
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun permissionIconFor(id: String): ImageVector = when (id) {
    "voice_assistant" -> Icons.Filled.Mic
    "notifications" -> Icons.Filled.Notifications
    "accessibility" -> Icons.Filled.TouchApp
    "notification_access" -> Icons.Filled.MarkChatRead
    "contacts" -> Icons.Filled.Contacts
    "sms" -> Icons.Filled.Sms
    "calendar" -> Icons.Filled.Event
    "camera" -> Icons.Filled.PhotoCamera
    "location" -> Icons.Filled.Place
    "bluetooth" -> Icons.Filled.Bluetooth
    "alarms" -> Icons.Filled.Alarm
    else -> Icons.Filled.Notifications
}

/** Resolves the hosting [Activity] from any context, or null when none exists. */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private const val TAG = "PermissionsSection"
