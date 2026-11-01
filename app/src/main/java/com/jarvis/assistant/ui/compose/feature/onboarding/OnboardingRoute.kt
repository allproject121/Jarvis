package com.jarvis.assistant.ui.compose.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.compose.components.AuroraBackground
import com.jarvis.assistant.ui.compose.components.AuroraBlue
import com.jarvis.assistant.ui.compose.components.AuroraCapsuleButton
import com.jarvis.assistant.ui.compose.components.AuroraCyan
import com.jarvis.assistant.ui.compose.components.AuroraEmerald
import com.jarvis.assistant.ui.compose.components.AuroraMagenta
import com.jarvis.assistant.ui.compose.components.AuroraProgressBar
import com.jarvis.assistant.ui.compose.components.AuroraViolet
import com.jarvis.assistant.ui.compose.components.GlassChip
import com.jarvis.assistant.ui.compose.components.StaggeredEntrance
import com.jarvis.assistant.ui.compose.components.rememberReduceMotion
import com.jarvis.assistant.ui.compose.feature.splash.ComposeStartDestination
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Per-page accent pair: (orb high, orb low). */
private val PAGE_ACCENTS = listOf(
    AuroraViolet to AuroraCyan,
    AuroraEmerald to AuroraViolet,
    AuroraCyan to AuroraBlue,
    AuroraBlue to AuroraMagenta,
    AuroraMagenta to AuroraViolet
)
private val PAGE_CHIP_ACCENTS = listOf(
    AuroraViolet, AuroraEmerald, AuroraCyan, AuroraBlue, AuroraMagenta
)

private data class OnboardingPageData(
    val title: String,
    val subtitle: String,
    val chips: List<String>
)

private val PAGES = listOf(
    OnboardingPageData(
        title = "Meet your pocket AI",
        subtitle = "Jarvis turns your phone into a voice-first AI assistant — fast, capable and entirely yours.",
        chips = listOf("Voice", "Assistant", "Free")
    ),
    OnboardingPageData(
        title = "Private by design",
        subtitle = "Local Offline mode keeps every word on your device. Cloud brains are optional and only use the keys you add.",
        chips = listOf("On-Device", "Offline", "Your Keys")
    ),
    OnboardingPageData(
        title = "Ridiculously fast",
        subtitle = "Gemini Live streams answers in real time, with automatic failover to local AI when the network drops.",
        chips = listOf("Live Voice", "Streaming", "Failover")
    ),
    OnboardingPageData(
        title = "Your models, your rules",
        subtitle = "Download GGUF models to run on-device, or point Jarvis at your own Ollama server. Switch any time.",
        chips = listOf("GGUF", "Ollama", "Switch Instantly")
    ),
    OnboardingPageData(
        title = "You’re all set",
        subtitle = "Your assistant is ready whenever you are. No account required to begin.",
        chips = listOf("No Account", "No Limits", "Yours")
    )
)

/**
 * The five-page Aurora introduction (ported from AndroLLM). Skip / Get Started
 * persist `compose_ui_prefs/onboarding_done = true` and continue to Setup.
 */
@Composable
fun OnboardingRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val pageCount = PAGES.size
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage >= pageCount - 1
    var finished by remember { mutableStateOf(false) }

    val complete: () -> Unit = {
        if (!finished) {
            finished = true
            ComposeStartDestination.setOnboardingDone(context, true)
            navigate(ComposeRoutes.SETUP)
        }
    }

    val accentA by animateColorAsState(
        targetValue = PAGE_ACCENTS[currentPage].first,
        animationSpec = tween(700),
        label = "accentA"
    )
    val accentB by animateColorAsState(
        targetValue = PAGE_ACCENTS[currentPage].second,
        animationSpec = tween(700),
        label = "accentB"
    )

    AuroraBackground(
        accentA = accentA,
        accentB = accentB,
        reduceMotion = reduceMotion
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    IconButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage((currentPage - 1).coerceAtLeast(0)) }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.ledger.deskPaperDim
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(1.dp))
                }
                if (!isLastPage) {
                    TextButton(onClick = complete) {
                        Text(
                            text = "Skip",
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 0
            ) { page ->
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                OnboardingPage(
                    page = page,
                    offset = pageOffset,
                    reduceMotion = reduceMotion
                )
            }

            AuroraProgressBar(
                progress = (currentPage + 1).toFloat() / pageCount,
                brush = Brush.horizontalGradient(listOf(accentA, accentB)),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            AuroraCapsuleButton(
                text = if (isLastPage) "Get Started" else "Next",
                onClick = {
                    if (isLastPage) {
                        complete()
                    } else {
                        scope.launch { pagerState.animateScrollToPage((currentPage + 1).coerceAtMost(pageCount - 1)) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingPage(
    page: Int,
    offset: Float,
    reduceMotion: Boolean
) {
    val data = PAGES[page]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val clamped = abs(offset).coerceIn(0f, 1f)
                alpha = 1f - clamped * 0.5f
                translationX = offset * 80f * density
                rotationZ = offset * 3f
                val s = 1f - clamped * 0.08f
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            when (page) {
                0 -> WelcomeScene(reduceMotion = reduceMotion)
                1 -> LocalScene(reduceMotion = reduceMotion)
                2 -> LightningScene(reduceMotion = reduceMotion)
                3 -> ModelsScene(reduceMotion = reduceMotion)
                else -> ReadyScene(reduceMotion = reduceMotion)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        StaggeredEntrance(index = 1) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        StaggeredEntrance(index = 2) {
            Text(
                text = data.subtitle,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.ledger.deskInk,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f
                )
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        StaggeredEntrance(index = 3) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                data.chips.forEach { chip ->
                    GlassChip(text = chip, accent = PAGE_CHIP_ACCENTS[page])
                }
            }
        }
    }
}
