package com.jarvis.assistant.maya.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.AndroLLMTheme
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Maya-style "Settings > Connectors": one card per service with status, masked token field,
 * Test button and help text; Telegram "Detect chat"; Google OAuth (user's own client id, PKCE)
 * plus a "paste access token" fallback.
 */
class ConnectorsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOCUS = "focus_service"
        const val EXTRA_OAUTH_RESULT = "oauth_result"

        fun intent(context: Context, focus: String? = null): Intent =
            Intent(context, ConnectorsActivity::class.java).apply {
                if (focus != null) putExtra(EXTRA_FOCUS, focus)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        try { ThemeManager.applyTheme(this) } catch (_: Exception) { }
        super.onCreate(savedInstanceState)
        val focus = intent.getStringExtra(EXTRA_FOCUS)
        setContent {
            AndroLLMTheme {
                ConnectorsScreen(focus = focus, tick = refreshTick.intValue, onBack = { finish() })
            }
        }
        findViewById<android.view.View>(android.R.id.content)?.let { try { ThemeManager.applyDynamicBackground(it) } catch (_: Exception) { } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshTick.intValue++
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
        try { com.jarvis.assistant.connectors.AppConnectorsManager.getInstance(this).refreshConnectorsList() } catch (_: Exception) { }
    }
}

private suspend fun testService(context: Context, s: ConnectorService): String = when (s) {
    ConnectorService.NOTION -> NotionClient(context).testConnection()
    ConnectorService.LINEAR -> LinearClient(context).testConnection()
    ConnectorService.TODOIST -> TodoistClient(context).testConnection()
    ConnectorService.GITLAB -> GitLabClient(context).testConnection()
    ConnectorService.VERCEL -> VercelClient(context).testConnection()
    ConnectorService.TELEGRAM -> TelegramClient(context).testConnection()
    ConnectorService.GOOGLE -> GoogleDriveClient(context).testConnection()
    ConnectorService.GITHUB -> GitHubExtraClient(context).testConnection()
}

private fun tokenKey(s: ConnectorService): String? = when (s) {
    ConnectorService.NOTION -> ConnectorSecrets.NOTION_TOKEN
    ConnectorService.LINEAR -> ConnectorSecrets.LINEAR_TOKEN
    ConnectorService.TODOIST -> ConnectorSecrets.TODOIST_TOKEN
    ConnectorService.GITLAB -> ConnectorSecrets.GITLAB_TOKEN
    ConnectorService.VERCEL -> ConnectorSecrets.VERCEL_TOKEN
    ConnectorService.TELEGRAM -> ConnectorSecrets.TELEGRAM_BOT_TOKEN
    ConnectorService.GOOGLE -> null
    ConnectorService.GITHUB -> null
}

private fun currentToken(context: Context, s: ConnectorService): String? = when (s) {
    ConnectorService.GITHUB -> ConnectorSecrets.githubToken(context)
    ConnectorService.GOOGLE -> ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_MANUAL_TOKEN)
        ?: ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_REFRESH_TOKEN)
    else -> tokenKey(s)?.let { ConnectorSecrets.get(context, it) }
}

private fun saveToken(context: Context, s: ConnectorService, value: String) {
    when (s) {
        ConnectorService.GITHUB -> com.jarvis.assistant.connectors.GitHubConnector(context).token = value.trim()
        ConnectorService.GOOGLE -> GoogleOAuthHelper.setManualAccessToken(context, value)
        else -> tokenKey(s)?.let { ConnectorSecrets.set(context, it, value) }
    }
    ConnectorSecrets.setLastStatus(context, s, null)
}

@Composable
private fun ConnectorsScreen(focus: String?, tick: Int, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val services = remember { ConnectorService.entries.toList() }
    LaunchedEffect(focus) {
        val idx = services.indexOfFirst { it.id == focus }
        if (idx >= 0) listState.scrollToItem(idx + 1)
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Connectors", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "GitHub, Notion, Telegram and more. Tokens are stored encrypted on this phone only. Jarvis' voice/chat tools (notion_*, linear_*, todoist_*, gitlab_*, drive_*, telegram_*, vercel_*, github_*) use them.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(services, key = { it.id }) { s -> ServiceCard(s, tick) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ServiceCard(s: ConnectorService, tick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localTick by remember { mutableIntStateOf(0) }
    val linked = remember(tick, localTick) { ConnectorSecrets.isLinked(context, s) }
    val saved = remember(tick, localTick) { currentToken(context, s) }
    val last = remember(tick, localTick) { ConnectorSecrets.lastStatus(context, s) }
    var tokenInput by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    // Extra fields
    var gitlabHost by remember(tick) { mutableStateOf(ConnectorSecrets.get(context, ConnectorSecrets.GITLAB_HOST) ?: "") }
    var chatIdInput by remember(tick) { mutableStateOf(ConnectorSecrets.telegramChatId(context) ?: "") }
    var clientId by remember(tick) { mutableStateOf(ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_CLIENT_ID) ?: "") }
    var clientSecret by remember(tick) { mutableStateOf(ConnectorSecrets.get(context, ConnectorSecrets.GOOGLE_CLIENT_SECRET) ?: "") }

    if (s == ConnectorService.GOOGLE) {
        val oauth by GoogleOAuthHelper.lastResult.collectAsState()
        LaunchedEffect(oauth) {
            oauth?.let {
                message = it.substringAfter(':')
                localTick++
                GoogleOAuthHelper.clearLastResult()
            }
        }
    }

    val (statusText, statusColor) = when {
        last != null && last.startsWith("error:") -> "Error" to Color(0xFFF87171)
        linked -> "Linked" to Color(0xFF4ADE80)
        else -> "Not linked" to Color(0xFF94A3B8)
    }

    fun runTest() {
        busy = true
        message = null
        scope.launch {
            message = try {
                val r = testService(context, s)
                ConnectorSecrets.setLastStatus(context, s, "ok")
                "✓ $r"
            } catch (e: ConnectorException) {
                ConnectorSecrets.setLastStatus(context, s, "error:${e.userMessage}")
                "✗ ${e.userMessage}"
            } catch (e: Exception) {
                ConnectorSecrets.setLastStatus(context, s, "error:${e.message}")
                "✗ ${e.message}"
            }
            busy = false
            localTick++
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(s.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("● $statusText", color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (saved != null) {
                Text(
                    when (s) {
                        ConnectorService.GOOGLE -> GoogleOAuthHelper.linkMode(context)
                        ConnectorService.TELEGRAM -> "Token ${ConnectorSecrets.mask(saved)}" +
                            (ConnectorSecrets.telegramChatId(context)?.let { " · chat ${ConnectorSecrets.get(context, ConnectorSecrets.TELEGRAM_CHAT_NAME) ?: it}" } ?: " · chat not detected")
                        else -> "Saved: ${ConnectorSecrets.mask(saved)}"
                    },
                    fontSize = 12.sp
                )
            }
            if (last != null && last.startsWith("error:") && message == null) {
                Text(last.removePrefix("error:"), fontSize = 12.sp, color = Color(0xFFF87171))
            }

            // ---- Google OAuth section
            if (s == ConnectorService.GOOGLE) {
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text("Your Google OAuth client id") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it },
                    label = { Text("Client secret (only for 'Web' clients)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = clientId.isNotBlank() && !busy,
                    onClick = {
                        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_CLIENT_ID, clientId)
                        ConnectorSecrets.set(context, ConnectorSecrets.GOOGLE_CLIENT_SECRET, clientSecret.ifBlank { null })
                        try {
                            context.startActivity(GoogleOAuthHelper.buildAuthIntent(context))
                        } catch (e: ConnectorException) {
                            message = e.userMessage
                        } catch (e: Exception) {
                            message = "Browser nahi khula: ${e.message}"
                        }
                    }
                ) { Text("Link Google account") }
                Text("Redirect URI: ${GoogleOAuthHelper.REDIRECT_URI}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ---- Token field (all services; Google = paste access token fallback)
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = {
                    Text(
                        when (s) {
                            ConnectorService.GOOGLE -> "Paste access token (OAuth Playground)"
                            ConnectorService.TELEGRAM -> "Bot token from @BotFather"
                            ConnectorService.NOTION -> "Integration secret"
                            ConnectorService.LINEAR -> "Personal API key"
                            else -> "API token"
                        }
                    )
                },
                placeholder = { if (saved != null) Text(ConnectorSecrets.mask(saved)) },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show", fontSize = 11.sp) } },
                modifier = Modifier.fillMaxWidth()
            )
            if (s == ConnectorService.GITLAB) {
                OutlinedTextField(
                    value = gitlabHost, onValueChange = { gitlabHost = it },
                    label = { Text("Host (default https://gitlab.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            if (s == ConnectorService.TELEGRAM) {
                OutlinedTextField(
                    value = chatIdInput, onValueChange = { chatIdInput = it },
                    label = { Text("Chat id (or tap Detect chat)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && (tokenInput.isNotBlank() || s == ConnectorService.GITLAB || s == ConnectorService.TELEGRAM),
                    onClick = {
                        if (tokenInput.isNotBlank()) saveToken(context, s, tokenInput)
                        if (s == ConnectorService.GITLAB) ConnectorSecrets.set(context, ConnectorSecrets.GITLAB_HOST, gitlabHost.ifBlank { null })
                        if (s == ConnectorService.TELEGRAM && chatIdInput.isNotBlank()) ConnectorSecrets.set(context, ConnectorSecrets.TELEGRAM_CHAT_ID, chatIdInput)
                        tokenInput = ""
                        localTick++
                        try { com.jarvis.assistant.connectors.AppConnectorsManager.getInstance(context).refreshConnectorsList() } catch (_: Exception) { }
                        if (ConnectorSecrets.isLinked(context, s)) runTest()
                    }
                ) { Text("Save") }
                OutlinedButton(enabled = !busy && linked, onClick = { runTest() }) { Text(if (busy) "…" else "Test") }
                if (s == ConnectorService.TELEGRAM) {
                    OutlinedButton(
                        enabled = !busy && linked,
                        onClick = {
                            busy = true
                            scope.launch {
                                message = try {
                                    val chat = TelegramClient(context).detectChatId()
                                    chatIdInput = chat.id
                                    "✓ Chat mil gaya: ${chat.title} (${chat.id})"
                                } catch (e: ConnectorException) {
                                    "✗ ${e.userMessage}"
                                }
                                busy = false
                                localTick++
                            }
                        }
                    ) { Text("Detect chat") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide help" else "How to get it") }
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.tokenUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(context, s.tokenUrl, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Open token page") }
                if (linked) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        ConnectorSecrets.unlink(context, s)
                        message = "${s.label} unlinked"
                        localTick++
                        try { com.jarvis.assistant.connectors.AppConnectorsManager.getInstance(context).refreshConnectorsList() } catch (_: Exception) { }
                    }) { Text("Unlink", color = Color(0xFFF87171)) }
                }
            }
            if (showHelp) Text(s.help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let {
                Text(it, fontSize = 12.sp, color = if (it.startsWith("✗")) Color(0xFFF87171) else MaterialTheme.colorScheme.primary)
            }
        }
    }
}
