package com.jarvis.assistant.ui.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Signup Blackout palette, resolvable per theme.
 *
 * Every mode wears the sign-in page: pure-black grounds, white ink, quiet
 * gray secondaries, hairline borders, and the single #EDEDED primary.
 * Components read the current palette via [MaterialTheme.ledger] instead of
 * hardcoding a token, which is what flips the whole app at once.
 */
@Immutable
data class LedgerColors(
    val deskNight: Color,
    val deskNightRaised: Color,
    val deskWalnut: Color,
    val deskWalnutRaised: Color,
    val deskWalnutDeep: Color,
    val deskPaper: Color,
    val deskPaperDim: Color,
    val deskInk: Color,
    val deskInkFaint: Color,
    val deskHairline: Color,
    val deskHairlineSoft: Color,
    val lampAmber: Color,
    val lampGlow: Color,
    val lampDeep: Color,
    val lampHalo: Color,
    val inkOnLamp: Color,
    val emberRed: Color,
    val emberRedSoft: Color,
    val emberRedHard: Color,
    val emberOnRed: Color,
    val terracottaSoft: Color,
    val terracottaDeep: Color,
    val cloudWhite: Color,
    val cloudGlassSurface: Color,
    val cloudGlassSurfaceVariant: Color,
    val cloudGlassBorder: Color,
    val cloudGlassBorderHighlight: Color,
    val cloudMoonGlow: Color,
    val cloudParticleTint: Color,
    val revolutCyberCyan: Color,
    val revolutGoldTier: Color,
    val revolutNeonEmerald: Color,
    val sunsetGlowAmber: Color
)

/** The blackout desk with the aurora-violet accent — everywhere. */
val LightLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF0A0A0A),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)

/** Night mode — same blackout + aurora violet; the mode switch stays calm. */
val DarkLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF0A0A0A),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)

/**
 * The AMOLED desk — pure-black floor, aurora-violet accent. Identical role
 * names, zero luminance on the ground.
 */
val AmoledLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF070707),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)

/**
 * AndroLLM's original daylight "Parchment Ledger" (documentation/DESIGN.md):
 * warm parchment canvas, ink text, a single terracotta stamp accent. Used
 * verbatim when the Jarvis theme is `androllm_parchment`.
 */
val ParchmentLedger = LedgerColors(
    deskNight = Color(0xFFF5F4ED),
    deskNightRaised = Color(0xFFECEBE3),
    deskWalnut = Color(0xFFFBFAF4),
    deskWalnutRaised = Color(0xFFFFFFFF),
    deskWalnutDeep = Color(0xFFEFEEE6),
    deskPaper = Color(0xFF141413),
    deskPaperDim = Color(0xFF3D3D3A),
    deskInk = Color(0xFF5E5D59),
    deskInkFaint = Color(0xFF8F8D87),
    deskHairline = Color(0xFFE2DFD3),
    deskHairlineSoft = Color(0xFFF0EEE6),
    lampAmber = Color(0xFFD97757),
    lampGlow = Color(0xFFB3573E),
    lampDeep = Color(0xFFA34E37),
    lampHalo = Color(0x29D97757),
    inkOnLamp = Color(0xFFFBFAF4),
    emberRed = Color(0xFFC7442F),
    emberRedSoft = Color(0xFFF7E1DB),
    emberRedHard = Color(0xFF8C2A1C),
    emberOnRed = Color(0xFFFFFFFF),
    terracottaSoft = Color(0xFFF6E3DA),
    terracottaDeep = Color(0xFF8A3F2A),
    cloudWhite = Color(0xFF141413),
    cloudGlassSurface = Color(0xCCFBFAF4),
    cloudGlassSurfaceVariant = Color(0xE6FFFFFF),
    cloudGlassBorder = Color(0x99D6D3C7),
    cloudGlassBorderHighlight = Color(0x33D97757),
    cloudMoonGlow = Color(0x29D97757),
    cloudParticleTint = Color(0x29D97757),
    revolutCyberCyan = Color(0xFFB3573E),
    revolutGoldTier = Color(0xFF8F8D87),
    revolutNeonEmerald = Color(0xFF3F8F1A),
    sunsetGlowAmber = Color(0xFFB3573E)
)
