package com.jarvis.assistant.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jarvis.assistant.ui.compose.model.ChatFontSize
import com.jarvis.assistant.ui.compose.model.ThemeMode
import com.jarvis.assistant.ui.compose.model.UiDensity

/**
 * THE SIGNUP BLACKOUT — direction contract.
 * THESIS: the whole app wears the sign-in page: pure-black grounds, white
 *   ink, quiet gray secondaries, hairline borders, one #EDEDED primary.
 *   No terracotta, no warmth, no wallpaper tinting, no per-user accent —
 *   every ThemeMode resolves to the same black.
 */

/**
 * Dark color scheme for AndroLLM — the blackout (all modes share it).
 */
private val DarkColors = darkColorScheme(
    primary = DarkLedger.lampAmber,
    onPrimary = DarkLedger.inkOnLamp,
    primaryContainer = DarkLedger.terracottaSoft,
    onPrimaryContainer = DarkLedger.terracottaDeep,
    secondary = DarkLedger.lampGlow,
    onSecondary = DarkLedger.inkOnLamp,
    secondaryContainer = DarkLedger.terracottaSoft,
    onSecondaryContainer = DarkLedger.terracottaDeep,
    tertiary = DarkLedger.lampAmber,
    onTertiary = DarkLedger.inkOnLamp,
    tertiaryContainer = DarkLedger.terracottaSoft,
    onTertiaryContainer = DarkLedger.terracottaDeep,
    error = DarkLedger.emberRed,
    onError = DarkLedger.emberOnRed,
    errorContainer = DarkLedger.emberRedSoft,
    onErrorContainer = DarkLedger.emberRedHard,
    background = DarkLedger.deskNight,
    onBackground = DarkLedger.deskPaper,
    surface = DarkLedger.deskWalnut,
    onSurface = DarkLedger.deskPaper,
    surfaceVariant = DarkLedger.deskWalnutDeep,
    onSurfaceVariant = DarkLedger.deskInk,
    surfaceTint = DarkLedger.lampAmber,
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = DarkLedger.lampDeep,
    surfaceBright = DarkLedger.deskNightRaised,
    surfaceDim = DarkLedger.deskNight,
    surfaceContainerLowest = Color(0xFF10100F),
    surfaceContainerLow = DarkLedger.deskWalnutDeep,
    surfaceContainer = DarkLedger.deskWalnut,
    surfaceContainerHigh = DarkLedger.deskWalnutRaised,
    surfaceContainerHighest = DarkLedger.deskNightRaised,
    outline = DarkLedger.deskHairline,
    outlineVariant = DarkLedger.deskHairlineSoft
)

/**
 * True AMOLED dark scheme — pure #000000 ground so OLED pixels switch off.
 * Keeps the same warm ink and terracotta accent for a consistent identity.
 */
private val AmoledColors = darkColorScheme(
    primary = AmoledLedger.lampAmber,
    onPrimary = AmoledLedger.inkOnLamp,
    primaryContainer = AmoledLedger.terracottaSoft,
    onPrimaryContainer = AmoledLedger.terracottaDeep,
    secondary = AmoledLedger.lampGlow,
    onSecondary = AmoledLedger.inkOnLamp,
    secondaryContainer = AmoledLedger.terracottaSoft,
    onSecondaryContainer = AmoledLedger.terracottaDeep,
    tertiary = AmoledLedger.lampAmber,
    onTertiary = AmoledLedger.inkOnLamp,
    tertiaryContainer = AmoledLedger.terracottaSoft,
    onTertiaryContainer = AmoledLedger.terracottaDeep,
    error = AmoledLedger.emberRed,
    onError = AmoledLedger.emberOnRed,
    errorContainer = AmoledLedger.emberRedSoft,
    onErrorContainer = AmoledLedger.emberRedHard,
    background = Color.Black,
    onBackground = AmoledLedger.deskPaper,
    surface = AmoledLedger.deskWalnut,
    onSurface = AmoledLedger.deskPaper,
    surfaceVariant = AmoledLedger.deskWalnutDeep,
    onSurfaceVariant = AmoledLedger.deskInk,
    surfaceTint = AmoledLedger.lampAmber,
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = AmoledLedger.lampDeep,
    surfaceBright = AmoledLedger.deskNightRaised,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = AmoledLedger.deskWalnutDeep,
    surfaceContainer = AmoledLedger.deskWalnut,
    surfaceContainerHigh = AmoledLedger.deskWalnutRaised,
    surfaceContainerHighest = AmoledLedger.deskNightRaised,
    outline = AmoledLedger.deskHairline,
    outlineVariant = AmoledLedger.deskHairlineSoft
)

/**
 * Light color scheme for AndroLLM — blackout; identical black, never parchment.
 */
private val LightColors = lightColorScheme(
    primary = LightLedger.lampAmber,
    onPrimary = LightLedger.inkOnLamp,
    primaryContainer = LightLedger.terracottaSoft,
    onPrimaryContainer = LightLedger.terracottaDeep,
    secondary = LightLedger.lampDeep,
    onSecondary = LightLedger.inkOnLamp,
    secondaryContainer = LightLedger.terracottaSoft,
    onSecondaryContainer = LightLedger.terracottaDeep,
    tertiary = LightLedger.lampAmber,
    onTertiary = LightLedger.inkOnLamp,
    tertiaryContainer = LightLedger.terracottaSoft,
    onTertiaryContainer = LightLedger.terracottaDeep,
    error = LightLedger.emberRed,
    onError = LightLedger.emberOnRed,
    errorContainer = LightLedger.emberRedSoft,
    onErrorContainer = LightLedger.emberRedHard,
    background = LightLedger.deskNight,
    onBackground = LightLedger.deskPaper,
    surface = LightLedger.deskWalnut,
    onSurface = LightLedger.deskPaper,
    surfaceVariant = LightLedger.deskWalnutDeep,
    onSurfaceVariant = LightLedger.deskInk,
    surfaceTint = LightLedger.lampAmber,
    inverseSurface = ParchmentInverse,
    inverseOnSurface = OnParchmentInverse,
    inversePrimary = LampGlow,
    surfaceBright = ParchmentBright,
    surfaceDim = ParchmentDim,
    surfaceContainerLowest = ParchmentLowest,
    surfaceContainerLow = ParchmentLow,
    surfaceContainer = ParchmentContainer,
    surfaceContainerHigh = ParchmentHigh,
    surfaceContainerHighest = ParchmentHighest,
    outline = LightLedger.deskHairline,
    outlineVariant = LightLedger.deskHairlineSoft
)

/** The active [LedgerColors] for the current composition (light or dark). */
val LocalLedgerColors = staticCompositionLocalOf { LightLedger }

/** Reads the active parchment palette — components should use this, never a hardcoded token. */
val MaterialTheme.ledger: LedgerColors
    @ReadOnlyComposable
    @Composable
    get() = LocalLedgerColors.current

/**
 * The Compose theme for every ported AndroLLM screen.
 *
 * By default ([followJarvis] = true) the colours come from the ACTIVE JARVIS
 * THEME via [JarvisThemeBridge]: the same `?attr/theme*` tokens the XML UI
 * uses (Pearl White, Sunset, Stealth, Gold, ... and the two AndroLLM themes
 * `androllm_night` / `androllm_parchment`, which use the original ledgers).
 * [themeMode] only applies when [followJarvis] is false (legacy behaviour).
 * [dynamicColor] and [accentColor] are accepted for API compatibility.
 */
@Composable
fun AndroLLMTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    accentColor: Color? = null,
    followJarvis: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme
    val ledger: LedgerColors
    if (followJarvis) {
        val context = LocalContext.current
        val themeKey = JarvisThemeBridge.currentKey()
        val customHex = runCatching { com.jarvis.assistant.JarvisApp.instance.preferences.customThemeColor }.getOrDefault("")
        val palette = remember(context, themeKey, customHex) { JarvisThemeBridge.resolve(context) }
        colorScheme = palette.colorScheme
        ledger = palette.ledger
    } else {
        val darkTheme = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.AMOLED -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        val amoled = themeMode == ThemeMode.AMOLED
        colorScheme = when {
            amoled -> AmoledColors
            darkTheme -> DarkColors
            else -> LightColors
        }
        ledger = when {
            amoled -> AmoledLedger
            darkTheme -> DarkLedger
            else -> LightLedger
        }
    }

    CompositionLocalProvider(LocalLedgerColors provides ledger) {
        CompositionLocalProvider(
            // The theme ink, not raw black/white: every uncolored Text inherits
            // this so it stays readable on light (Pearl/Parchment) and dark grounds.
            LocalContentColor provides colorScheme.onBackground
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = AndroLLMTypography,
                shapes = AndroLLMShapes,
                content = content
            )
        }
    }
}

/**
 * Applies the user's density (compact / default / comfortable) and chat font
 * size as a global scale. Overriding [LocalDensity] lets every dp-based
 * spacing and every sp-based text react without touching a single component.
 * The scaling multiplies the platform density/fontScale so system font-size
 * settings continue to apply on top.
 */
@Composable
fun ProvideUiScale(
    density: UiDensity = UiDensity.DEFAULT,
    fontSize: ChatFontSize = ChatFontSize.MEDIUM,
    content: @Composable () -> Unit
) {
    val base = LocalDensity.current
    val densityFactor = when (density) {
        UiDensity.COMPACT -> 0.92f
        UiDensity.DEFAULT -> 1f
        UiDensity.COMFORTABLE -> 1.08f
    }
    val fontFactor = when (fontSize) {
        ChatFontSize.SMALL -> 0.9f
        ChatFontSize.MEDIUM -> 1f
        ChatFontSize.LARGE -> 1.15f
    }
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density * densityFactor,
            fontScale = base.fontScale * fontFactor
        )
    ) {
        content()
    }
}
/**
 * Maya colour scheme — the companion look. Dark only: deep charcoal-navy
 * ground (#14161C), #1A1D26 / #1F2330 surfaces, #F8FAFC ink, #94A3B8
 * secondary ink and the electric-blue #1E90FF primary with a #4F7CFF accent.
 */
val MayaColorScheme: ColorScheme = darkColorScheme(
    primary = MayaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A2A44),
    onPrimaryContainer = MayaTintSoft,
    secondary = MayaAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C2440),
    onSecondaryContainer = MayaTintSofter,
    tertiary = MayaCyanGlow,
    onTertiary = Color(0xFF04202A),
    tertiaryContainer = Color(0xFF0F2A33),
    onTertiaryContainer = MayaTintSofter,
    error = MayaDanger,
    onError = Color.White,
    errorContainer = Color(0xFF3A1419),
    onErrorContainer = Color(0xFFFFB4AB),
    background = MayaBg,
    onBackground = MayaText,
    surface = MayaSurface,
    onSurface = MayaText,
    surfaceVariant = MayaSurfaceRaised,
    onSurfaceVariant = MayaTextSecondary,
    surfaceTint = MayaPrimary,
    inverseSurface = MayaText,
    inverseOnSurface = MayaBg,
    inversePrimary = MayaLightBlue,
    surfaceBright = MayaSurfaceRaised,
    surfaceDim = MayaBgEdge,
    surfaceContainerLowest = MayaBgEdge,
    surfaceContainerLow = Color(0xFF171A21),
    surfaceContainer = MayaSurface,
    surfaceContainerHigh = MayaSurfaceRaised,
    surfaceContainerHighest = Color(0xFF262B38),
    outline = MayaHairline,
    outlineVariant = Color(0xFF222633)
)

/** The Maya ledger: the same palette expressed in AndroLLM's ledger roles. */
val MayaLedger: LedgerColors = LedgerColors(
    deskNight = MayaBg,
    deskNightRaised = Color(0xFF171A21),
    deskWalnut = MayaSurface,
    deskWalnutRaised = MayaSurfaceRaised,
    deskWalnutDeep = Color(0xFF111319),
    deskPaper = MayaText,
    deskPaperDim = Color(0xFFE2E8F0),
    deskInk = MayaTextSecondary,
    deskInkFaint = MayaTextMuted,
    deskHairline = MayaHairline,
    deskHairlineSoft = Color(0xFF222633),
    lampAmber = MayaPrimary,
    lampGlow = MayaLightBlue,
    lampDeep = MayaAccent,
    lampHalo = Color(0x401E90FF),
    inkOnLamp = Color.White,
    emberRed = MayaDangerSoft,
    emberRedSoft = Color(0xFF3A1419),
    emberRedHard = Color(0xFFFFB4AB),
    emberOnRed = Color.White,
    terracottaSoft = Color(0xFF1A2A44),
    terracottaDeep = MayaTintSoft,
    cloudWhite = MayaText,
    cloudGlassSurface = MayaSurface.copy(alpha = 0.8f),
    cloudGlassSurfaceVariant = MayaSurfaceRaised.copy(alpha = 0.9f),
    cloudGlassBorder = MayaHairline.copy(alpha = 0.6f),
    cloudGlassBorderHighlight = MayaPrimary.copy(alpha = 0.2f),
    cloudMoonGlow = MayaPrimary.copy(alpha = 0.25f),
    cloudParticleTint = MayaCyanGlow.copy(alpha = 0.25f),
    revolutCyberCyan = MayaCyanGlow,
    revolutGoldTier = MayaWarning,
    revolutNeonEmerald = MayaSuccess,
    sunsetGlowAmber = MayaOrange
)

/**
 * Material theme for Compose content shown while the Maya theme is active
 * (character sheet, Maya settings pages): [MayaColorScheme] + Inter typography.
 * Falls back to [AndroLLMTheme] (which follows the active Jarvis theme) when
 * Maya is not the selected theme, so other skins keep their own colours.
 */
@Composable
fun MayaTheme(
    forceMaya: Boolean = false,
    content: @Composable () -> Unit
) {
    val isMaya = forceMaya || JarvisThemeBridge.currentKey() == com.jarvis.assistant.util.ThemeManager.THEME_MAYA
    if (!isMaya) {
        AndroLLMTheme(content = content)
        return
    }
    CompositionLocalProvider(LocalLedgerColors provides MayaLedger) {
        CompositionLocalProvider(LocalContentColor provides MayaColorScheme.onBackground) {
            MaterialTheme(
                colorScheme = MayaColorScheme,
                typography = MayaTypography,
                shapes = AndroLLMShapes,
                content = content
            )
        }
    }
}
