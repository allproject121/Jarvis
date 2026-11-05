package com.jarvis.assistant.ui.compose.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.androllm.AndroLlmFlags
import com.jarvis.assistant.androllm.accessibility.debug.AccessibilityDebugStore
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettings
import com.jarvis.assistant.androllm.accessibility.settings.AccessibilitySettingsStore
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.ToolConfirmationManager
import com.jarvis.assistant.ui.compose.components.CloudGlassCard
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.launch

/**
 * "UI Automation" settings — the AndroLLM accessibility-automation knobs
 * (AccessibilitySettingsStore): step budget, LLM planning (also gated by
 * AndroLlmFlags.LLM_UI_PLANNER, default OFF), high-risk confirmation,
 * scroll-into-view, ML Kit OCR and the developer log. The service itself is
 * still Jarvis' JarvisAccessibilityService; this only tunes how ui_run acts.
 */
@Composable
fun AutomationSettingsSection() {
    val context = LocalContext.current
    val store = remember { AccessibilitySettingsStore.getInstance(context) }
    val settings by store.settings.collectAsState(initial = AccessibilitySettings())
    val scope = rememberCoroutineScope()
    val debugEntries by AccessibilityDebugStore.entries.collectAsState()

    var llmFlag by remember { mutableStateOf(AndroLlmFlags.isEnabled(context, AndroLlmFlags.LLM_UI_PLANNER, false)) }
    var serviceOn by remember { mutableStateOf(JarvisAccessibilityService.isRunning()) }

    // Refresh the service status whenever the screen resumes (user may come back from system settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            serviceOn = JarvisAccessibilityService.isRunning()
            llmFlag = AndroLlmFlags.isEnabled(context, AndroLlmFlags.LLM_UI_PLANNER, false)
        }
    }

    fun update(transform: (AccessibilitySettings) -> AccessibilitySettings) {
        scope.launch { runCatching { store.update(transform) } }
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
        Column {
            SettingRow(
                icon = Icons.Filled.Accessibility,
                title = "Accessibility service",
                value = if (serviceOn) "Enabled" else "Off - tap to enable"
            ) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f))

            SliderSetting(
                label = { "Max steps per task: ${it.toInt()}" },
                value = settings.maxSteps.toFloat(),
                valueRange = AccessibilitySettings.MIN_STEPS.toFloat()..AccessibilitySettings.MAX_STEPS.toFloat(),
                steps = AccessibilitySettings.MAX_STEPS - AccessibilitySettings.MIN_STEPS - 1,
                onCommit = { v -> update { it.copy(maxSteps = v.toInt()) } }
            )

            ToggleRow(
                icon = Icons.Filled.Psychology,
                title = "LLM UI planner",
                subtitle = "Let the configured text model pick each step (falls back to rules). Off = fast deterministic planner.",
                checked = llmFlag && settings.llmPlanning,
                onCheckedChange = { on ->
                    AndroLlmFlags.setEnabled(context, AndroLlmFlags.LLM_UI_PLANNER, on)
                    llmFlag = on
                    update { it.copy(llmPlanning = on) }
                }
            )
            ToggleRow(
                icon = Icons.Filled.Security,
                title = "Confirm high-risk steps",
                subtitle = "Ask before tapping send, pay, buy, delete, install, post or call.",
                checked = settings.confirmHighRisk,
                onCheckedChange = { on -> update { it.copy(confirmHighRisk = on) } }
            )
            ToggleRow(
                icon = Icons.Filled.SwapVert,
                title = "Scroll into view",
                subtitle = "Scroll lists to find a target element before tapping it.",
                checked = settings.autoScrollIntoView,
                onCheckedChange = { on -> update { it.copy(autoScrollIntoView = on) } }
            )
            ToggleRow(
                icon = Icons.Filled.DocumentScanner,
                title = "On-screen OCR",
                subtitle = "Merge ML Kit text recognition into screen reads (Android 11+, slower).",
                checked = settings.ocrEnabled,
                onCheckedChange = { on -> update { it.copy(ocrEnabled = on) } }
            )
            ToggleRow(
                icon = Icons.Filled.BugReport,
                title = "Automation developer log",
                subtitle = "Record screen dumps and every automation step.",
                checked = settings.developerMode,
                onCheckedChange = { on -> update { it.copy(developerMode = on) } }
            )

            if (settings.developerMode) {
                HorizontalDivider(color = MaterialTheme.ledger.deskInk.copy(alpha = 0.15f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Recent automation log (${debugEntries.size})",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                    TextButton(onClick = { AccessibilityDebugStore.clear() }) {
                        Text("Clear", color = MaterialTheme.ledger.lampGlow)
                    }
                }
                val recent = debugEntries.takeLast(12).reversed()
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (recent.isEmpty()) {
                        Text(
                            "No entries yet.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                        )
                    }
                    recent.forEach { entry ->
                        Text(
                            entry.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.ledger.deskInk,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders Jarvis' [ToolConfirmationManager] requests (high-risk ui_run steps)
 * as a dialog. Mount once near the app root (or in the chat / settings
 * screen); without a mounted prompt, confirmations time out and the risky
 * step is safely declined.
 */
@Composable
fun ToolConfirmationPrompt() {
    val pending by ToolConfirmationManager.pending.collectAsState()
    val action = pending ?: return
    AlertDialog(
        onDismissRequest = { ToolConfirmationManager.submitDecision(action.id, false) },
        title = { Text("Confirm action", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Text(
                action.description,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk)
            )
        },
        confirmButton = {
            Button(
                onClick = { ToolConfirmationManager.submitDecision(action.id, true) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.emberRed)
            ) {
                Text("Allow", color = MaterialTheme.ledger.emberOnRed)
            }
        },
        dismissButton = {
            TextButton(onClick = { ToolConfirmationManager.submitDecision(action.id, false) }) {
                Text("Deny", color = MaterialTheme.ledger.deskInk)
            }
        }
    )
}
