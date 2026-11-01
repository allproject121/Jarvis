package com.jarvis.assistant.ui.compose.feature.voice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import com.jarvis.assistant.ui.compose.theme.ledger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.androllm.voice.assistant.VoiceOverlayEvent
import com.jarvis.assistant.background.JarvisLiveError
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.util.PermissionHelper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// AndroLLM VoiceSettings defaults (Jarvis has no overlay customisation settings).
private const val OVERLAY_TRANSPARENCY = 0.78f
private const val ANIMATION_SPEED = 1.0f
private const val OVERLAY_SIZE = 1.0f

/**
 * Gemini Live-style voice screen ported from AndroLLM's VoiceOverlay, driven by
 * Jarvis's real voice pipeline (JarvisConversationService + OfflineVoiceConversationEngine).
 */
@Composable
fun VoiceRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: VoiceViewModel = viewModel { VoiceViewModel(app) }

    val sessionOn by vm.isSessionOn.collectAsStateWithLifecycle()
    val standby by vm.isStandby.collectAsStateWithLifecycle()
    val convState by vm.conversationState.collectAsStateWithLifecycle()
    val level by vm.audioLevel.collectAsStateWithLifecycle()
    val userText by vm.userTranscript.collectAsStateWithLifecycle()
    val answer by vm.assistantResponse.collectAsStateWithLifecycle()
    val status by vm.connectionStatus.collectAsStateWithLifecycle()
    val liveError by vm.liveError.collectAsStateWithLifecycle()
    val micMuted by vm.micMuted.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedMs.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val sherpaConfig by vm.sherpaConfig.collectAsStateWithLifecycle()
    val sherpaStatus by vm.sherpaStatus.collectAsStateWithLifecycle()
    val voiceDownloads by vm.voiceDownloads.collectAsStateWithLifecycle()
    val androRunning by vm.androRunning.collectAsStateWithLifecycle()
    val androState by vm.androState.collectAsStateWithLifecycle()
    val androSettings by vm.androSettings.collectAsStateWithLifecycle()
    var showEngines by remember { mutableStateOf(false) }

    val sherpaMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.recheckSherpa() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            PermissionHelper.hasRecordAudioPermission(context)
        if (micGranted) {
            vm.startSession()
        } else {
            vm.showNotice(VoiceNotice("Microphone permission is required for voice conversations."))
        }
    }

    val onStart: () -> Unit = {
        val missing = buildList<String> {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) vm.startSession() else permissionLauncher.launch(missing.toTypedArray())
    }

    val snapshot = VoiceSnapshot(
        sessionOn = sessionOn,
        standby = standby,
        conversationState = convState,
        micLevel = level.coerceIn(0f, 1f),
        transcript = userText,
        answerText = answer,
        connectionStatus = status,
        micMuted = micMuted,
        elapsedMs = elapsed,
        modelProvider = if (sessionOn) vm.modelProvider else "",
        modelName = if (sessionOn) vm.modelName else ""
    )

    if (androRunning) {
        // Optional AndroLLM voice mode owns the voice screen while its service runs
        // (Jarvis's own service is guaranteed to be off then).
        VoiceOverlayScreen(
            state = androSnapshot(androState),
            liveError = null,
            notice = notice,
            engineSummary = "AndroLLM voice · " + androEngineSummary(androSettings),
            onOpenEngines = { navigate(ComposeRoutes.SETTINGS) },
            onStart = vm::startAndroVoice,
            onStop = vm::stopAndroVoice,
            onCancel = { vm.androOverlayEvent(VoiceOverlayEvent.Cancel) },
            onToggleMute = { vm.androOverlayEvent(VoiceOverlayEvent.ToggleMute) },
            onOpenChat = { navigate(ComposeRoutes.CHAT) },
            onOpenConversation = {
                vm.androOverlayEvent(VoiceOverlayEvent.OpenConversation)
                navigate(ComposeRoutes.CHAT)
            },
            onClose = back,
            onDismissError = {},
            onFixError = { navigate(ComposeRoutes.SETTINGS) },
            onNoticeAction = { vm.dismissNotice() },
            onDismissNotice = vm::dismissNotice,
            animationSpeed = androSettings.animationSpeed,
            transparency = androSettings.overlayTransparency,
            overlaySize = androSettings.overlaySize
        )
        return
    }

    VoiceOverlayScreen(
        state = snapshot,
        liveError = liveError,
        notice = notice,
        engineSummary = vm.engineSummary(sherpaConfig, sherpaStatus),
        onOpenEngines = { showEngines = true },
        onStart = onStart,
        onStop = vm::stopSession,
        onCancel = vm::cancelTurn,
        onToggleMute = vm::toggleMicMute,
        onOpenChat = { navigate(ComposeRoutes.CHAT) },
        onOpenConversation = { navigate(ComposeRoutes.CHAT) },
        onClose = back,
        onDismissError = vm::clearLiveError,
        onFixError = { navigate(ComposeRoutes.SETTINGS) },
        onNoticeAction = { action ->
            when (action) {
                VoiceNoticeAction.BATTERY_OPTIMIZATION -> PermissionHelper.requestBatteryOptimization(context)
                VoiceNoticeAction.OPEN_SETTINGS -> navigate(ComposeRoutes.SETTINGS)
                VoiceNoticeAction.NONE -> Unit
            }
            vm.dismissNotice()
        },
        onDismissNotice = vm::dismissNotice
    )

    if (showEngines) {
        val needsMic = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        }
        VoiceEnginesDialog(
            config = sherpaConfig,
            status = sherpaStatus,
            downloads = voiceDownloads,
            sessionOn = sessionOn,
            standby = standby,
            onToggleWake = { on ->
                vm.setSherpaWakeWord(on)
                if (on && needsMic()) sherpaMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onToggleAsr = vm::setSherpaAsr,
            onToggleTts = vm::setSherpaTts,
            onSelectPack = vm::selectVoicePack,
            onDictate = {
                if (needsMic()) sherpaMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else vm.startSherpaDictation()
            },
            onStopDictation = vm::stopSherpaDictation,
            onOpenSettings = {
                showEngines = false
                navigate(ComposeRoutes.SETTINGS)
            },
            onDismiss = { showEngines = false }
        )
    }
}

/** Snapshot of Jarvis voice state used by the overlay (replaces AndroLLM's VoiceUiState). */
internal data class VoiceSnapshot(
    val sessionOn: Boolean,
    val standby: Boolean,
    val conversationState: ConversationState,
    val micLevel: Float,
    val transcript: String,
    val answerText: String,
    val connectionStatus: String,
    val micMuted: Boolean,
    val elapsedMs: Long,
    val modelProvider: String,
    val modelName: String,
    /** AndroLLM voice mode only: waiting for the wake word between turns. */
    val wakeListening: Boolean = false
)

@Composable
internal fun VoiceOverlayScreen(
    state: VoiceSnapshot,
    liveError: JarvisLiveError?,
    notice: VoiceNotice?,
    engineSummary: String,
    onOpenEngines: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit,
    onDismissError: () -> Unit,
    onFixError: () -> Unit,
    onNoticeAction: (VoiceNoticeAction) -> Unit,
    onDismissNotice: () -> Unit,
    animationSpeed: Float = ANIMATION_SPEED,
    overlaySize: Float = OVERLAY_SIZE,
    transparency: Float = OVERLAY_TRANSPARENCY,
    showEnginesChip: Boolean = true
) {
    val display = OverlayDisplay.from(state).let { it.copy(color = it.state.themedColor()) }
    val speed = animationSpeed
    val scrimAlpha = transparency

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.ledger.deskNight.copy(alpha = scrimAlpha))
    ) {
        AuroraBackdrop(color = display.color, speed = speed, transparency = scrimAlpha)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))
            StatusHeader(display = display, state = state, speed = speed)

            if (liveError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ErrorBanner(error = liveError, onDismiss = onDismissError, onFix = onFixError)
            }

            Spacer(modifier = Modifier.weight(0.5f))
            MascotOrb(display = display, micLevel = state.micLevel, size = overlaySize, speed = speed)
            Spacer(modifier = Modifier.weight(0.5f))

            TranscriptBlock(state = state, display = display)

            if (display.state == OverlayDisplayState.LISTENING ||
                display.state == OverlayDisplayState.SPEAKING
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                AudioLevelMeter(
                    level = if (state.micMuted) 0f else state.micLevel,
                    color = display.color,
                    speed = speed
                )
            }

            AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
                if (notice != null) {
                    NoticeChip(notice = notice, onAction = onNoticeAction, onDismiss = onDismissNotice)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            ModelChip(provider = state.modelProvider, model = state.modelName)
            Spacer(modifier = Modifier.height(8.dp))
            if (showEnginesChip) {
                VoiceEnginesChip(summary = engineSummary, onClick = onOpenEngines)
            } else if (engineSummary.isNotBlank()) {
                Text(engineSummary, color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.height(14.dp))
            ControlRow(
                state = state,
                display = display,
                onStart = onStart,
                onStop = onStop,
                onCancel = onCancel,
                onToggleMute = onToggleMute,
                onOpenChat = onOpenChat,
                onOpenConversation = onOpenConversation,
                onClose = onClose
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Display model ────────────────────────────────────────────────────────────

internal enum class OverlayDisplayState(
    val label: String,
    val emoji: String,
    val baseColor: Color,
    val isTurnInProgress: Boolean
) {
    OFF("Voice off", "⏻", Color(0xFF888888), false),
    STANDBY("Standby", "💤", Color(0xFFA3A3A3), false),
    LISTENING("Start speaking", "🎤", Color(0xFFFFFFFF), false),
    THINKING("Thinking", "🧠", Color(0xFFEDEDED), true),
    GENERATING("Responding", "✨", Color(0xFFFFFFFF), true),
    SPEAKING("Speaking", "🔊", Color(0xFF4ADE80), true),
    DONE("Done", "✅", Color(0xFF888888), false);
}

/** The state colour in the active Jarvis theme (ink-based, so it reads on light grounds too). */
@Composable
internal fun OverlayDisplayState.themedColor(): Color {
    val l = MaterialTheme.ledger
    return when (this) {
        OverlayDisplayState.OFF, OverlayDisplayState.DONE, OverlayDisplayState.STANDBY -> l.deskInk
        OverlayDisplayState.LISTENING, OverlayDisplayState.GENERATING -> l.deskPaper
        OverlayDisplayState.THINKING -> l.deskPaperDim
        OverlayDisplayState.SPEAKING -> l.revolutNeonEmerald
    }
}

internal data class OverlayDisplay(
    val state: OverlayDisplayState,
    val color: Color,
    val showCancel: Boolean,
    val showOpenConversation: Boolean
) {
    companion object {
        fun from(s: VoiceSnapshot): OverlayDisplay {
            val ds = when {
                !s.sessionOn && s.standby -> OverlayDisplayState.STANDBY
                s.sessionOn && s.wakeListening -> OverlayDisplayState.STANDBY
                !s.sessionOn -> OverlayDisplayState.OFF
                else -> when (s.conversationState) {
                    ConversationState.LISTENING -> OverlayDisplayState.LISTENING
                    ConversationState.THINKING ->
                        if (s.answerText.isNotBlank()) OverlayDisplayState.GENERATING else OverlayDisplayState.THINKING
                    ConversationState.SPEAKING -> OverlayDisplayState.SPEAKING
                    ConversationState.IDLE ->
                        if (s.answerText.isNotBlank()) OverlayDisplayState.DONE else OverlayDisplayState.LISTENING
                }
            }
            return OverlayDisplay(
                state = ds,
                color = ds.baseColor,
                showCancel = s.sessionOn && ds.isTurnInProgress,
                showOpenConversation = ds == OverlayDisplayState.DONE && s.answerText.isNotBlank()
            )
        }
    }
}

// ── Aurora glass background ──────────────────────────────────────────────────

@Composable
private fun AuroraBackdrop(color: Color, speed: Float, transparency: Float) {
    val ink = MaterialTheme.ledger.deskPaper
    val ground = MaterialTheme.ledger.deskNight
    val raised = MaterialTheme.ledger.deskWalnutRaised
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (9000 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )
    Box(Modifier.fillMaxSize()) {
        val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(60.dp) else Modifier
        Canvas(modifier = Modifier.fillMaxSize().then(blurModifier)) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.28f * transparency), Color.Transparent),
                    center = Offset(w * (0.25f + drift * 0.2f), h * 0.25f),
                    radius = w * 0.6f
                ),
                radius = w * 0.6f,
                center = Offset(w * (0.25f + drift * 0.2f), h * 0.25f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ink.copy(alpha = 0.10f * transparency), Color.Transparent),
                    center = Offset(w * (0.75f - drift * 0.15f), h * 0.6f),
                    radius = w * 0.55f
                ),
                radius = w * 0.55f,
                center = Offset(w * (0.75f - drift * 0.15f), h * 0.6f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(raised.copy(alpha = 0.5f * transparency), Color.Transparent),
                    center = Offset(w * 0.5f, h * 1.05f),
                    radius = w * 0.7f
                ),
                radius = w * 0.7f,
                center = Offset(w * 0.5f, h * 1.05f)
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(ground.copy(alpha = 0.35f), Color.Transparent, ground.copy(alpha = 0.3f))
                )
            )
        }
    }
}

// ── Status header ────────────────────────────────────────────────────────────

@Composable
private fun StatusHeader(display: OverlayDisplay, state: VoiceSnapshot, speed: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = display.state,
            transitionSpec = {
                (fadeIn(tween(240)) + scaleIn(initialScale = 0.85f, animationSpec = tween(240)))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, animationSpec = tween(160)))
            },
            label = "status"
        ) { ds ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${ds.emoji}  ${ds.label}",
                    color = MaterialTheme.ledger.deskPaper,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (ds == OverlayDisplayState.THINKING || ds == OverlayDisplayState.GENERATING) {
                    Spacer(modifier = Modifier.width(10.dp))
                    StreamingDots(color = ds.themedColor(), speed = speed)
                }
            }
        }

        // Elapsed timer chip (thinking / generating)
        AnimatedVisibility(
            visible = display.state == OverlayDisplayState.THINKING ||
                display.state == OverlayDisplayState.GENERATING,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatElapsed(state.elapsedMs),
                    color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Subtitle for the Jarvis-specific off / standby / connection states
        val subtitle = when (display.state) {
            OverlayDisplayState.OFF -> "Tap Start to begin a voice session"
            OverlayDisplayState.STANDBY -> "Say the wake word, or tap Start"
            else -> state.connectionStatus.takeIf { it.isNotBlank() && it != "READY" }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StreamingDots(color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "dots")
    fun dotSpec(delay: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween((420 / speed).toInt(), delayMillis = (delay / speed).toInt()),
        repeatMode = RepeatMode.Restart
    )
    val d0 by transition.animateFloat(0f, 1f, dotSpec(0), label = "d0")
    val d1 by transition.animateFloat(0f, 1f, dotSpec(140), label = "d1")
    val d2 by transition.animateFloat(0f, 1f, dotSpec(280), label = "d2")
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(d0, d1, d2).forEach { v ->
            val alpha = (1f - abs(v * 2f - 1f)).coerceIn(0.15f, 1f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

// ── Mascot orb ───────────────────────────────────────────────────────────────

@Composable
private fun MascotOrb(display: OverlayDisplay, micLevel: Float, size: Float, speed: Float) {
    val baseSize = (240.dp * size).coerceIn(160.dp, 300.dp)
    val mascotSize = (118.dp * size).coerceIn(84.dp, 148.dp)

    val transition = rememberInfiniteTransition(label = "mascot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (700 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2600 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val stateScale = when (display.state) {
        OverlayDisplayState.LISTENING -> 1.0f
        OverlayDisplayState.THINKING -> 0.45f
        OverlayDisplayState.GENERATING -> 0.52f
        OverlayDisplayState.SPEAKING -> 0.93f + 0.07f * breath
        OverlayDisplayState.OFF, OverlayDisplayState.STANDBY -> 0.85f
        else -> 1.0f
    }
    val idlePulse = when (display.state) {
        OverlayDisplayState.THINKING, OverlayDisplayState.GENERATING -> 0.03f * pulse
        OverlayDisplayState.OFF -> 0f
        else -> 0.02f * pulse
    }
    val scale by animateFloatAsState(
        targetValue = stateScale + idlePulse,
        animationSpec = tween(durationMillis = (500 / speed).toInt()),
        label = "mascotScale"
    )
    val liftUp by animateFloatAsState(
        targetValue = if (display.state == OverlayDisplayState.THINKING ||
            display.state == OverlayDisplayState.GENERATING
        ) -40f else 0f,
        animationSpec = tween(durationMillis = (500 / speed).toInt()),
        label = "mascotLift"
    )

    Box(
        modifier = Modifier
            .size(baseSize)
            .offset(y = liftUp.dp),
        contentAlignment = Alignment.Center
    ) {
        val glowAlpha = when (display.state) {
            OverlayDisplayState.THINKING -> 0.45f + 0.35f * pulse
            OverlayDisplayState.GENERATING -> 0.4f + 0.3f * pulse
            OverlayDisplayState.SPEAKING -> 0.3f + 0.25f * breath
            OverlayDisplayState.OFF -> 0.12f
            else -> 0.25f
        }
        Box(
            modifier = Modifier
                .size(baseSize * 0.72f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(display.color.copy(alpha = glowAlpha), Color.Transparent)
                    )
                )
        )

        if (display.state == OverlayDisplayState.LISTENING) {
            RippleRing(color = display.color, speed = speed, micLevel = micLevel)
        }

        WaveformRing(
            color = display.color,
            micLevel = when (display.state) {
                OverlayDisplayState.LISTENING -> micLevel
                OverlayDisplayState.SPEAKING -> 0.55f + 0.25f * pulse
                else -> 0f
            },
            speed = speed
        )

        if (display.state == OverlayDisplayState.THINKING) {
            GradientRing(color = display.color, speed = speed)
        }

        if (display.state == OverlayDisplayState.THINKING ||
            display.state == OverlayDisplayState.GENERATING
        ) {
            OrbitingParticles(color = display.color, speed = speed)
        }

        Box(modifier = Modifier.scale(scale), contentAlignment = Alignment.Center) {
            CloudBugdroidLogo(size = mascotSize)
        }
    }
}

@Composable
private fun RippleRing(color: Color, speed: Float, micLevel: Float) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1400 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )
    val progress2 by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1400 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple2"
    )
    val amplitude = 0.5f + 0.5f * micLevel
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.5f
        val p2 = (progress2 - 0.5f).coerceIn(0f, 1f)
        drawCircle(
            color = color.copy(alpha = ((1f - progress) * 0.35f * amplitude).coerceIn(0f, 1f)),
            radius = base * (0.55f + progress * 0.5f),
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = ((1f - p2) * 0.3f * amplitude).coerceIn(0f, 1f)),
            radius = base * (0.5f + p2 * 0.6f),
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

@Composable
private fun WaveformRing(color: Color, micLevel: Float, speed: Float) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1200 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.42f
        val bars = 36
        for (i in 0 until bars) {
            val angle = (i.toFloat() / bars) * (2 * Math.PI).toFloat()
            val envelope = 0.5f + 0.5f * abs(sin(angle * 3f + phase))
            val barLen = 4.dp.toPx() + 26.dp.toPx() * envelope * micLevel.coerceIn(0f, 1f)
            val c = cos(angle)
            val s = sin(angle)
            drawLine(
                color = color.copy(alpha = (0.55f + 0.45f * envelope).coerceIn(0f, 1f)),
                start = Offset(center.x + c * radius, center.y + s * radius),
                end = Offset(center.x + c * (radius + barLen), center.y + s * (radius + barLen)),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun GradientRing(color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "gradRing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2200 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.36f
        val segments = 48
        val twoPi = (2 * Math.PI).toFloat()
        val rot = (rotation * Math.PI / 180.0).toFloat()
        for (i in 0 until segments) {
            val a0 = (i.toFloat() / segments) * twoPi + rot
            val a1 = a0 + twoPi / segments
            val r1 = radius + 2.5f.dp.toPx()
            val alpha = if (i % 6 < 3) 0.9f else 0.35f
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(center.x + cos(a0) * radius, center.y + sin(a0) * radius),
                end = Offset(center.x + cos(a1) * r1, center.y + sin(a1) * r1),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun OrbitingParticles(color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "particles")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2600 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleAngle"
    )
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * 0.46f
        repeat(7) { i ->
            val a = angle + (i.toFloat() / 7) * (2 * Math.PI).toFloat()
            val wobble = 0.9f + 0.1f * sin(a * 3f)
            val px = center.x + cos(a) * radius * wobble
            val py = center.y + sin(a) * radius * wobble
            val alpha = 0.25f + 0.4f * (0.5f + 0.5f * sin(a * 2f))
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0.15f, 0.8f)),
                radius = 2.5f.dp.toPx() * (0.7f + 0.3f * wobble),
                center = Offset(px, py)
            )
        }
    }
}

// ── Transcript + answer ──────────────────────────────────────────────────────

@Composable
private fun TranscriptBlock(state: VoiceSnapshot, display: OverlayDisplay) {
    val hasTranscript = state.transcript.isNotBlank()
    val hasAnswer = state.answerText.isNotBlank()
    AnimatedVisibility(
        visible = state.sessionOn && (hasTranscript || hasAnswer),
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.97f, animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.transcript.isNotBlank()) {
                Text(
                    text = state.transcript,
                    color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.92f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.06f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            if (state.answerText.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buildAnswerText(
                        text = state.answerText,
                        accent = display.color,
                        streaming = display.state == OverlayDisplayState.GENERATING
                    ),
                    color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.95f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(display.color.copy(alpha = 0.08f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Answer text with AndroLLM's blinking block cursor while the reply is streaming. */
@Composable
private fun buildAnswerText(text: String, accent: Color, streaming: Boolean): AnnotatedString {
    val transition = rememberInfiniteTransition(label = "cursor")
    val visible by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )
    return buildAnnotatedString {
        append(text)
        if (streaming) {
            append(" ")
            withStyle(SpanStyle(color = accent.copy(alpha = visible.coerceIn(0f, 1f)))) {
                append("█")
            }
        }
    }
}

// ── Live mic level meter ─────────────────────────────────────────────────────

@Composable
private fun AudioLevelMeter(level: Float, color: Color, speed: Float) {
    val transition = rememberInfiniteTransition(label = "meter")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (160 / speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meterPhase"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(24) { i ->
            val wave = 0.5f + 0.5f * abs(sin(i * 0.9f + phase * 6.283f))
            val h: Dp = 6.dp + 22.dp * (wave * level.coerceIn(0.05f, 1f))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = (0.35f + 0.65f * wave).coerceIn(0f, 1f)))
            )
        }
    }
}

// ── Notices / errors ─────────────────────────────────────────────────────────

@Composable
private fun NoticeChip(notice: VoiceNotice, onAction: (VoiceNoticeAction) -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.1f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = notice.text,
            color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.9f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp)
        )
        if (notice.actionLabel != null) {
            TextButton(onClick = { onAction(notice.action) }) {
                Text(notice.actionLabel, color = MaterialTheme.ledger.revolutNeonEmerald, fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onClick = onDismiss) {
            Text("OK", color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ErrorBanner(error: JarvisLiveError, onDismiss: () -> Unit, onFix: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.ledger.emberRed.copy(alpha = 0.14f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.ledger.emberRed, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(error.title, color = MaterialTheme.ledger.deskPaper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(error.message, color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss", color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.7f)) }
            TextButton(onClick = onFix) { Text(error.actionText, color = MaterialTheme.ledger.emberRed, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── Model chip ───────────────────────────────────────────────────────────────

@Composable
private fun ModelChip(provider: String, model: String) {
    AnimatedVisibility(
        visible = model.isNotBlank(),
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.ledger.deskPaper.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.ledger.deskPaper.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (provider.isNotBlank()) "$provider · $model" else model,
                color = MaterialTheme.ledger.deskPaper.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────

@Composable
private fun ControlRow(
    state: VoiceSnapshot,
    display: OverlayDisplay,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Start (no active session) — Jarvis-specific primary action
        AnimatedVisibility(
            visible = !state.sessionOn,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f)
        ) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.deskPaperDim),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.ledger.deskNight)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start voice session", color = MaterialTheme.ledger.deskNight, fontWeight = FontWeight.SemiBold)
            }
        }

        // Open Conversation (finished turn)
        AnimatedVisibility(
            visible = display.showOpenConversation,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f)
        ) {
            Button(
                onClick = onOpenConversation,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.deskPaperDim),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.ledger.deskNight)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Conversation", color = MaterialTheme.ledger.deskNight, fontWeight = FontWeight.SemiBold)
            }
        }

        val topPad = if (!state.sessionOn || display.showOpenConversation) 14.dp else 0.dp
        Row(
            modifier = Modifier.padding(top = topPad),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = display.showCancel, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                RoundIconButton(
                    icon = Icons.Filled.Close,
                    tint = MaterialTheme.ledger.emberRed,
                    background = MaterialTheme.ledger.emberRed.copy(alpha = 0.14f),
                    contentDescription = "Cancel",
                    onClick = onCancel
                )
            }
            // Mic mute (Jarvis mutes the microphone; it has no separate TTS mute)
            RoundIconButton(
                icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                tint = if (state.micMuted) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskPaper.copy(alpha = 0.9f),
                background = MaterialTheme.ledger.deskPaper.copy(alpha = 0.12f),
                contentDescription = if (state.micMuted) "Unmute microphone" else "Mute microphone",
                onClick = onToggleMute
            )
            RoundIconButton(
                icon = Icons.Filled.Keyboard,
                tint = MaterialTheme.ledger.deskPaper.copy(alpha = 0.9f),
                background = MaterialTheme.ledger.deskPaper.copy(alpha = 0.12f),
                contentDescription = "Open chat",
                onClick = onOpenChat
            )
            if (state.sessionOn) {
                RoundIconButton(
                    icon = Icons.Filled.PowerSettingsNew,
                    tint = MaterialTheme.ledger.emberRed,
                    background = MaterialTheme.ledger.emberRed.copy(alpha = 0.14f),
                    contentDescription = "End voice session",
                    onClick = onStop
                )
            }
            RoundIconButton(
                icon = Icons.Filled.Close,
                tint = MaterialTheme.ledger.deskPaper.copy(alpha = 0.9f),
                background = MaterialTheme.ledger.deskPaper.copy(alpha = 0.12f),
                contentDescription = "Close",
                onClick = onClose
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundIconButton(
    icon: ImageVector,
    tint: Color,
    background: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, shape = CircleShape, color = background, contentColor = tint) {
        Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
}
