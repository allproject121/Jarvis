package com.jarvis.assistant.ui.compose.feature.splash

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.compose.components.AuroraBackground
import com.jarvis.assistant.ui.compose.components.AuroraCyan
import com.jarvis.assistant.ui.compose.components.AuroraViolet
import com.jarvis.assistant.ui.compose.components.CloudBugdroidLogo
import com.jarvis.assistant.ui.compose.components.rememberReduceMotion
import com.jarvis.assistant.ui.compose.navigation.ComposeRoutes
import com.jarvis.assistant.ui.compose.theme.ledger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent first-run flags for the Compose UI, kept in the
 * "compose_ui_prefs" SharedPreferences file.
 */
object ComposeStartDestination {
    const val PREFS_NAME = "compose_ui_prefs"
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    const val KEY_SETUP_DONE = "setup_done"

    fun isOnboardingDone(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean = true) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_DONE, done)
            .apply()
    }

    fun isSetupDone(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, done: Boolean = true) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, done)
            .apply()
    }

    /** The route the splash hands over to. */
    fun nextRoute(context: Context): String =
        if (isOnboardingDone(context)) ComposeRoutes.HOME else ComposeRoutes.ONBOARDING
}

private const val SPLASH_HOLD_MS = 1800L

/**
 * Cinematic splash (ported from AndroLLM): the mark fades and spring-scales in,
 * floats weightlessly, the tagline fades in, then hands over to Home (returning
 * users) or Onboarding (first run).
 */
@Composable
fun SplashRoute(navigate: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(if (reduceMotion) 1f else 0.55f) }
    val taglineAlpha = remember { Animatable(0f) }
    val loadingAlpha = remember { Animatable(0f) }
    val bob = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            launch {
                while (isActive) {
                    bob.animateTo(1f, tween(durationMillis = 1900, easing = FastOutSlowInEasing))
                    bob.animateTo(-1f, tween(durationMillis = 1900, easing = FastOutSlowInEasing))
                }
            }
        }
        launch { logoAlpha.animateTo(1f, tween(durationMillis = 800)) }
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            delay(1200)
            taglineAlpha.animateTo(1f, tween(durationMillis = 700))
        }
        launch {
            delay(1700)
            loadingAlpha.animateTo(1f, tween(durationMillis = 500))
        }

        delay(SPLASH_HOLD_MS)
        delay(if (reduceMotion) 300L else 700L)
        navigate(ComposeStartDestination.nextRoute(context))
    }

    AuroraBackground(
        accentA = MaterialTheme.ledger.lampAmber,
        accentB = MaterialTheme.ledger.lampGlow,
        reduceMotion = reduceMotion
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .scale(logoScale.value)
                        .alpha(logoAlpha.value)
                        .graphicsLayer { translationY = bob.value * 7.dp.toPx() },
                    contentAlignment = Alignment.Center
                ) {
                    CloudBugdroidLogo(size = 160.dp)
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Jarvis",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    modifier = Modifier.alpha(taglineAlpha.value)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Private AI. Powered by You.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.ledger.lampDeep,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.alpha(taglineAlpha.value)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Text(
                    text = "Initializing…",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.deskInk
                    ),
                    modifier = Modifier.alpha(loadingAlpha.value)
                )
            }
        }
    }
}
