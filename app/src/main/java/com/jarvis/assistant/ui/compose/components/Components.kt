package com.jarvis.assistant.ui.compose.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.compose.theme.DeskCardShape
import com.jarvis.assistant.ui.compose.theme.DeskHairline
import com.jarvis.assistant.ui.compose.theme.DeskHairlineSoft
import com.jarvis.assistant.ui.compose.theme.DeskInk
import com.jarvis.assistant.ui.compose.theme.DeskInkFaint
import com.jarvis.assistant.ui.compose.theme.DeskPaper
import com.jarvis.assistant.ui.compose.theme.DeskPillShape
import com.jarvis.assistant.ui.compose.theme.DeskWalnut
import com.jarvis.assistant.ui.compose.theme.DeskWalnutDeep
import com.jarvis.assistant.ui.compose.theme.DeskWalnutRaised
import com.jarvis.assistant.ui.compose.theme.InkOnLamp
import com.jarvis.assistant.ui.compose.theme.LampAmber
import com.jarvis.assistant.ui.compose.theme.LampGlow
import com.jarvis.assistant.ui.compose.theme.LampHalo
import com.jarvis.assistant.ui.compose.theme.ledger
import com.jarvis.assistant.ui.compose.designsystem.BentoSurface
import com.jarvis.assistant.ui.compose.designsystem.bentoTokens

/**
 * Backward compatible wrapper for the desk background.
 */
@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CloudAtmosphericBackground(modifier = modifier, content = content)
}

/**
 * The shared section card — now the Dark Bento card (2026-11-05): 24dp radius, lighter-top
 * gradient, soft corner glow, 64dp sheen and a 1dp hairline, all from the Jarvis theme attrs
 * (see [com.jarvis.assistant.ui.compose.designsystem.BentoSurface]). Cards nested inside
 * another card render as plate tiles. Pressed states still settle the card a fraction.
 */
@Composable
fun CloudGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )
    val bento = bentoTokens()

    BentoSurface(
        modifier = modifier
            .scale(scale)
            .pointerInput(onClick) {
                if (onClick != null) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onClick() }
                    )
                }
            },
        contentPadding = contentPadding,
        borderColor = if (isPressed) bento.accent.copy(alpha = 0.65f) else borderColor
    ) {
        content()
    }
}

/**
 * The terracotta stamp button — the one accent control on the desk. Press
 * compresses; the disabled state returns to quiet cream with ink.
 */
@Composable
fun CloudCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    gradient: androidx.compose.ui.graphics.Brush? = null
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    val bento = bentoTokens()
    Box(
        modifier = modifier
            .scale(scale)
            .clip(DeskPillShape)
            .background(
                when {
                    !enabled -> bento.plate
                    isPressed -> bento.accentFill.copy(alpha = 0.85f)
                    else -> bento.accentFill
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) bento.onAccent else bento.muted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) bento.onAccent else bento.muted
                )
            )
        }
    }
}

/**
 * BrandButton implementation mapped to the lamp button for compatibility.
 */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    CloudCapsuleButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    )
}

/**
 * SectionCard mapped to the walnut panel for compatibility.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CloudGlassCard(modifier = modifier, content = content)
}

/**
 * A ruled chip — small caps label on a hairline, amber when live.
 */
@Composable
fun CloudChip(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    icon: ImageVector? = null
) {
    val bento = bentoTokens()
    val tint = if (accentColor == Color.Unspecified) bento.accent else accentColor
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = bento.plate,
        border = BorderStroke(1.dp, if (accentColor == Color.Unspecified) bento.plateBorder else accentColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = tint
                )
            )
        }
    }
}

/**
 * A section heading: the serif voice and a small-caps ledger caption beneath.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val bento = bentoTokens()
            Text(
                text = title,
                color = bento.text,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = bento.muted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * The desk's empty state: a blank sheet on the parchment.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    CloudGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentPadding = PaddingValues(32.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .background(bentoTokens().accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = bentoTokens().accent,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.ledger.deskPaper
                ),
                textAlign = TextAlign.Center
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.ledger.deskInk
                    ),
                    textAlign = TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(modifier = Modifier.height(24.dp))
                action()
            }
        }
    }
}

/**
 * Centered amber loading ring.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = bentoTokens().accentFill,
            trackColor = bentoTokens().plate,
            strokeWidth = 3.dp,
            modifier = Modifier.padding(24.dp).size(36.dp)
        )
    }
}

/**
 * The wordmark: the desk's voice set in ink above the parchment.
 */
@Composable
fun DeskWordmark(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    Text(
        text = "AndroLLM",
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = size.value.sp,
            color = MaterialTheme.ledger.deskPaper
        )
    )
}

/**
 * The lit terracotta seal — the single live point on a loaded model card.
 * Breathes slowly while active, sits dim when idle.
 */
@Composable
fun LampDot(
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    size: Dp = 8.dp
) {
    val glow by animateFloatAsState(
        targetValue = if (lit) 1f else 0.35f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lampGlow"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampHalo.copy(alpha = glow * 0.55f))
            .shadow(
                elevation = if (lit) 6.dp else 0.dp,
                shape = CircleShape,
                spotColor = MaterialTheme.ledger.lampAmber.copy(alpha = 0.5f)
            )
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (lit) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInkFaint)
        )
    }
}