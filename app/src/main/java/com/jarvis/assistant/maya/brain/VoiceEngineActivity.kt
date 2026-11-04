package com.jarvis.assistant.maya.brain

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maya's "Voice engine" screen: Mode, Transcriber (+ Deepgram / backup), Brain (provider,
 * base URL, model, key, AutoPick, Test), Voice (TTS engine, voice, Test), Eyes model and
 * Sub-agents. All values persist instantly into [BrainSettings].
 */
class VoiceEngineActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, VoiceEngineActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContent {
            AndroLLMTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VoiceEngineScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun VoiceEngineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = remember { BrainSettings.get(context) }
    val scope = rememberCoroutineScope()
    val status by AdvancedAgenticMode.status.collectAsState()

    var mode by remember { mutableStateOf(s.mode) }
    var transcriber by remember { mutableStateOf(s.transcriber) }
    var backup by remember { mutableStateOf(s.backupTranscriber) }
    var deepgramKey by remember { mutableStateOf(s.deepgramKey) }
    var providerId by remember { mutableStateOf(s.brainProviderId) }
    var baseUrl by remember { mutableStateOf(s.brainBaseUrl) }
    var model by remember { mutableStateOf(s.brainModel) }
    var brainKey by remember { mutableStateOf(s.brainKey) }
    var autoPick by remember { mutableStateOf(s.autoPickFreeModels) }
    var think by remember { mutableStateOf(s.thinkEnabled) }
    var brainTest by remember { mutableStateOf("") }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var ttsEngine by remember { mutableStateOf(s.ttsEngine) }
    var ttsVoice by remember { mutableStateOf(s.ttsVoice) }
    var geminiTtsKey by remember { mutableStateOf(s.geminiTtsKey) }
    var googleTtsKey by remember { mutableStateOf(s.googleCloudTtsKey) }
    var ttsTest by remember { mutableStateOf("") }
    var eyesCustom by remember { mutableStateOf(s.eyesUseCustom) }
    var eyesProvider by remember { mutableStateOf(s.eyesProviderId) }
    var eyesModel by remember { mutableStateOf(s.eyesModel) }
    var eyesTest by remember { mutableStateOf("") }

    val provider = BrainProviders.byIdOrDefault(providerId)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Voice engine", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Normal or Advanced Agentic — how she listens, thinks and speaks",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Mode ────────────────────────────────────────────────────────
            Section("Mode") {
                RadioRow("Gemini Live", "Default. Google's live voice model listens, thinks and speaks.", mode == BrainSettings.MODE_GEMINI_LIVE) {
                    mode = BrainSettings.MODE_GEMINI_LIVE; AdvancedAgenticMode.setEnabled(context, false)
                }
                RadioRow("Advanced Agentic (Beta)", "Transcriber → your own LLM with tools → voice engine.", mode == BrainSettings.MODE_ADVANCED) {
                    mode = BrainSettings.MODE_ADVANCED; AdvancedAgenticMode.setEnabled(context, true)
                }
                if (mode == BrainSettings.MODE_ADVANCED) {
                    Spacer(Modifier.height(6.dp))
                    Text(statusText(status), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) { AdvancedAgenticMode.start(context) }
                        }, enabled = status is AgenticStatus.Off || status is AgenticStatus.Error) { Text("Start session") }
                        OutlinedButton(onClick = { AdvancedAgenticMode.stop(context) }, enabled = AdvancedAgenticMode.isSessionRunning()) { Text("Stop") }
                    }
                }
            }

            // ── Transcriber ─────────────────────────────────────────────────
            Section("Transcriber") {
                Text(
                    "Whichever you pick is tried first. Google's transcriber — and Deepgram, with a key — stay behind it, so a pick that stops working never leaves her unable to hear you.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Dropdown("Transcriber", BrainSettings.TRANSCRIBERS, transcriber, { BrainSettings.transcriberLabel(it) }) {
                    transcriber = it; s.transcriber = it
                }
                Spacer(Modifier.height(4.dp))
                Text("Backup transcriber (optional)", fontWeight = FontWeight.Medium)
                Dropdown("Backup", listOf("") + BrainSettings.TRANSCRIBERS, backup, { if (it.isBlank()) "None" else BrainSettings.transcriberLabel(it) }) {
                    backup = it; s.backupTranscriber = it
                }
                SecretField("Deepgram API key (optional)", deepgramKey, "From console.deepgram.com") { deepgramKey = it; s.deepgramKey = it }
                Text(
                    "Deepgram bills per minute once the free credit is gone. Google's free transcriber is still tried first; Deepgram is used only when it is not available.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Brain ───────────────────────────────────────────────────────
            Section("Brain") {
                Dropdown("Provider", BrainProviders.ids(), providerId, { BrainProviders.byIdOrDefault(it).label }) {
                    providerId = it; s.brainProviderId = it
                    brainKey = s.providerKey(it); model = ""; s.brainModel = ""; modelList = emptyList()
                    if (!BrainProviders.byIdOrDefault(it).customBaseUrl) { baseUrl = ""; s.brainBaseUrl = "" }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; s.brainBaseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(provider.baseUrl) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; s.brainModel = it },
                    label = { Text("Model") },
                    placeholder = { Text(provider.defaultModel.ifBlank { "model id" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (modelList.isNotEmpty()) {
                    Dropdown("Pick from list (${modelList.size})", modelList, model, { it }) { model = it; s.brainModel = it }
                }
                SecretField("API key", brainKey, provider.keyHint) { brainKey = it; s.setBrainKey(it) }
                if (providerId == BrainProviders.OPENROUTER) {
                    ToggleRow("AutoPick free models", autoPick) { autoPick = it; s.autoPickFreeModels = it }
                    if (autoPick) Text(OpenRouterAutoPick.FREE_MODELS_EXPLANATION, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToggleRow("Think before answering (reasoning)", think) { think = it; s.thinkEnabled = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        brainTest = "Testing…"
                        scope.launch {
                            val r = AgenticCloudProvider.main(context).test()
                            brainTest = if (r.ok) "OK — ${r.model} answered in ${r.latencyMs} ms" else r.message
                        }
                    }) { Text("Test") }
                    OutlinedButton(onClick = {
                        brainTest = "Loading models…"
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                if (s.mainBrain().autoPick) runCatching { OpenRouterAutoPick.freeToolModels(context, s.brainKey, forceRefresh = true).map { it.id } }
                                else BrainHttpClient.listModels(s.mainBrain())
                            }
                            res.onSuccess { modelList = it; brainTest = "${it.size} models" }
                                .onFailure { brainTest = "Model list failed: ${it.message}" }
                        }
                    }) { Text("Load models") }
                }
                if (brainTest.isNotBlank()) Text(brainTest, fontSize = 12.sp)
            }

            // ── Voice ───────────────────────────────────────────────────────
            Section("Voice") {
                Dropdown("Voice engine", BrainSettings.TTS_ENGINES, ttsEngine, { BrainSettings.ttsLabel(it) }) {
                    ttsEngine = it; s.ttsEngine = it
                }
                OutlinedTextField(
                    value = ttsVoice,
                    onValueChange = { ttsVoice = it; s.ttsVoice = it },
                    label = { Text("Voice name") },
                    placeholder = {
                        Text(
                            when (ttsEngine) {
                                BrainSettings.TTS_GEMINI -> GeminiTtsSynthesizer.DEFAULT_VOICE
                                BrainSettings.TTS_GOOGLE_CLOUD -> GoogleCloudTtsSynthesizer.DEFAULT_VOICE
                                BrainSettings.TTS_EDGE -> "en-IN-NeerjaNeural"
                                else -> "(engine default)"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (ttsEngine == BrainSettings.TTS_GEMINI) {
                    Dropdown("Gemini voice", GeminiTtsSynthesizer.VOICES, ttsVoice.ifBlank { GeminiTtsSynthesizer.DEFAULT_VOICE }, { it }) {
                        ttsVoice = it; s.ttsVoice = it
                    }
                    SecretField("Gemini TTS key (optional)", geminiTtsKey, "Blank = use the Gemini brain / Jarvis key") { geminiTtsKey = it; s.geminiTtsKey = it }
                }
                if (ttsEngine == BrainSettings.TTS_GOOGLE_CLOUD) {
                    SecretField("Google Cloud TTS API key", googleTtsKey, "Cloud Text-to-Speech API must be enabled") { googleTtsKey = it; s.googleCloudTtsKey = it }
                }
                Button(onClick = {
                    ttsTest = "Speaking…"
                    scope.launch {
                        val used = MayaTtsRouter.speak(context, "Hello! Main aapki nayi awaaz hoon. Kaisi lag rahi hoon?", engineOverride = ttsEngine)
                        ttsTest = if (used != null) "Played with ${BrainSettings.ttsLabel(used)}" else (MayaTtsRouter.lastError ?: "Voice failed")
                    }
                }) { Text("Test line") }
                if (ttsTest.isNotBlank()) Text(ttsTest, fontSize = 12.sp)
            }

            // ── Eyes model ──────────────────────────────────────────────────
            Section("Eyes model (optional)") {
                Text("Used when she looks at a photo or the screen. Must be an image-capable model.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow("Use a different provider for eyes", eyesCustom) { eyesCustom = it; s.eyesUseCustom = it }
                if (eyesCustom) {
                    Dropdown("Eyes provider", BrainProviders.ids(), eyesProvider.ifBlank { providerId }, { BrainProviders.byIdOrDefault(it).label }) {
                        eyesProvider = it; s.eyesProviderId = it
                    }
                }
                OutlinedTextField(
                    value = eyesModel,
                    onValueChange = { eyesModel = it; s.eyesModel = it },
                    label = { Text("Eyes model") },
                    placeholder = { Text(s.brainFor(SubAgentRole.EYES).model.ifBlank { "vision model id" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = {
                    eyesTest = "Testing…"
                    scope.launch {
                        val r = AgenticCloudProvider.forRole(context, SubAgentRole.EYES).test()
                        eyesTest = if (r.ok) "OK — ${r.model} (${r.latencyMs} ms)" else r.message
                    }
                }) { Text("Test") }
                if (eyesTest.isNotBlank()) Text(eyesTest, fontSize = 12.sp)
            }

            // ── Sub-agents ──────────────────────────────────────────────────
            Section("Sub-agents") {
                Text("Each role inherits the main brain unless you turn on its own provider.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SubAgentRole.entries.filter { it != SubAgentRole.EYES }.forEach { role -> SubAgentEditor(s, role) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SubAgentEditor(s: BrainSettings, role: SubAgentRole) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var custom by remember { mutableStateOf(s.subAgentUseCustom(role)) }
    var pid by remember { mutableStateOf(s.subAgentProviderId(role).ifBlank { s.brainProviderId }) }
    var model by remember { mutableStateOf(s.subAgentModel(role)) }
    var key by remember { mutableStateOf(s.subAgentKey(role)) }
    var test by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(role.label, fontWeight = FontWeight.Medium)
        Text(role.blurb, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("Use custom", custom) { custom = it; s.setSubAgentUseCustom(role, it) }
        if (custom) {
            Dropdown("Provider", BrainProviders.ids(), pid, { BrainProviders.byIdOrDefault(it).label }) { pid = it; s.setSubAgentProviderId(role, it) }
            OutlinedTextField(
                value = model, onValueChange = { model = it; s.setSubAgentModel(role, it) },
                label = { Text("Model") },
                placeholder = { Text(BrainProviders.byIdOrDefault(pid).defaultModel) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            SecretField("API key", key, "Blank = reuse the saved key for this provider") { key = it; s.setSubAgentKey(role, it) }
        }
        OutlinedButton(onClick = {
            test = "Testing…"
            scope.launch {
                val r = AgenticCloudProvider.forRole(context, role).test()
                test = if (r.ok) "OK — ${r.model} (${r.latencyMs} ms)" else r.message
            }
        }) { Text("Test ${role.label}") }
        if (test.isNotBlank()) Text(test, fontSize = 12.sp)
    }
}

private fun statusText(s: AgenticStatus): String = when (s) {
    AgenticStatus.Off -> "Session: off"
    is AgenticStatus.Listening -> "Listening… ${s.partial}"
    is AgenticStatus.Thinking -> "Thinking: ${s.userText}"
    is AgenticStatus.Tool -> "Running tool ${s.name}"
    is AgenticStatus.Speaking -> "Speaking: ${s.text}"
    is AgenticStatus.Notice -> s.message
    is AgenticStatus.Error -> "Error: ${s.message}"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecretField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Dropdown(label: String, options: List<String>, selected: String, display: (String) -> String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${display(selected)}", modifier = Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(display(opt)) }, onClick = { open = false; onSelect(opt) })
            }
        }
    }
}
