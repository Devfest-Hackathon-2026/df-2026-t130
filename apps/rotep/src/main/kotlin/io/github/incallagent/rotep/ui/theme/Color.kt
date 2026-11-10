// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * The colors of アイてる!? - AITEL -: bright, flat, held together by one navy (2026-11-08, from the
 * owner's color-variation image: amber, lavender, sky, teal, navy and cream, flat). Two parts:
 *
 * 1. The Material3 roles (all 48, so none falls back to the default purple). Accent roles take the
 *    tone of the Material3 scheme (light: primary 40, containers 90, on-containers 10; dark: primary
 *    80, containers 30, on-containers 90) as CIELAB L*, so two roles contrast as in Material3.
 *    Hue / chroma (CIELAB LCh): primary violet 300 / 75 (the main buttons and the text buttons: a
 *    color of their own, so text buttons do not look like the navy text), secondary yellow 85 / 60
 *    (notices, "相手が出た"), tertiary 240 / 30 (unused; kept away from the success teal), error red
 *    35 / 60. Text is navy on the colored pages (light) and cream on navy (dark); the practice
 *    band "通話なし（練習）" is the inverse surface: navy with cream text (light), the other way (dark).
 *
 * 2. AitelColors: what Material has no role for. The four hero colors (the block at the top of the
 *    request, confirm, call and result screens; the teal only for a booking the check found nothing
 *    wrong with), navy text on them in both themes, the celebration's cream and pink lights, and the
 *    logo's orange (the title only, never a state). ColorContrastTest checks the heroes and the text on
 *    them; the lights and the logo are decoration and are not checked.
 */

internal val AitelLight = lightColorScheme(
    primary = Color(0xFF5A3ED4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF001257),
    inversePrimary = Color(0xFFCBBEFF),
    secondary = Color(0xFF775A00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDEA2),
    onSecondaryContainer = Color(0xFF241A00),
    tertiary = Color(0xFF006684),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEE9FF),
    onTertiaryContainer = Color(0xFF001F2A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF2A2240),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2A2240),
    surfaceVariant = Color(0xFFECE8F2),
    onSurfaceVariant = Color(0xFF524C68),
    surfaceTint = Color(0xFF5A3ED4),
    inverseSurface = Color(0xFF2A2240),
    inverseOnSurface = Color(0xFFFFF4E6),
    error = Color(0xFFAC3229),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3A0B00),
    outline = Color(0xFF7E7992),
    outlineVariant = Color(0xFFD6D2E2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF3F1F8),
    surfaceContainerHigh = Color(0xFFEEEBF5),
    surfaceContainerHighest = Color(0xFFE8E5F0),
    surfaceContainerLow = Color(0xFFF8F6FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDD9E6),
    primaryFixed = Color(0xFFE6DEFF),
    primaryFixedDim = Color(0xFFCBBEFF),
    onPrimaryFixed = Color(0xFF001257),
    onPrimaryFixedVariant = Color(0xFF2A36AE),
    secondaryFixed = Color(0xFFFFDEA2),
    secondaryFixedDim = Color(0xFFEFC053),
    onSecondaryFixed = Color(0xFF241A00),
    onSecondaryFixedVariant = Color(0xFF5A4300),
    tertiaryFixed = Color(0xFFBEE9FF),
    tertiaryFixedDim = Color(0xFF80D1F6),
    onTertiaryFixed = Color(0xFF001F2A),
    onTertiaryFixedVariant = Color(0xFF004D64),
)

internal val AitelDark = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF00228A),
    primaryContainer = Color(0xFF2A36AE),
    onPrimaryContainer = Color(0xFFE6DEFF),
    inversePrimary = Color(0xFF5A3ED4),
    secondary = Color(0xFFEFC053),
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = Color(0xFF33333B),
    onSecondaryContainer = Color(0xFFFFDEA2),
    tertiary = Color(0xFF80D1F6),
    onTertiary = Color(0xFF003546),
    tertiaryContainer = Color(0xFF004D64),
    onTertiaryContainer = Color(0xFFBEE9FF),
    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFFFF4E6),
    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFFFF4E6),
    surfaceVariant = Color(0xFF464557),
    onSurfaceVariant = Color(0xFFC6C4DB),
    surfaceTint = Color(0xFFCBBEFF),
    inverseSurface = Color(0xFFFFF4E6),
    inverseOnSurface = Color(0xFF2A2240),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF690003),
    errorContainer = Color(0xFF8E1213),
    onErrorContainer = Color(0xFFFFDAD3),
    outline = Color(0xFF908EA4),
    outlineVariant = Color(0xFF464557),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38383D),
    surfaceContainer = Color(0xFF1B1B1F),
    surfaceContainerHigh = Color(0xFF242428),
    surfaceContainerHighest = Color(0xFF2E2E33),
    surfaceContainerLow = Color(0xFF151518),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceDim = Color(0xFF0F0F11),
    primaryFixed = Color(0xFFE6DEFF),
    primaryFixedDim = Color(0xFFCBBEFF),
    onPrimaryFixed = Color(0xFF001257),
    onPrimaryFixedVariant = Color(0xFF2A36AE),
    secondaryFixed = Color(0xFFFFDEA2),
    secondaryFixedDim = Color(0xFFEFC053),
    onSecondaryFixed = Color(0xFF241A00),
    onSecondaryFixedVariant = Color(0xFF5A4300),
    tertiaryFixed = Color(0xFFBEE9FF),
    tertiaryFixedDim = Color(0xFF80D1F6),
    onTertiaryFixed = Color(0xFF001F2A),
    onTertiaryFixedVariant = Color(0xFF004D64),
)

/** The colors Material has no role for: the heroes, the text on them, the celebration's lights, the logo. */
@Immutable
data class AitelColors(
    /** 依頼 */
    val heroRequest: Color,
    /** 確認と承認 */
    val heroConfirm: Color,
    /** 通話中 */
    val heroCall: Color,
    /** 結果, only when booked and the check found nothing */
    val heroResult: Color,
    /** Text on the four heroes (navy in both themes). The quiet result block uses onSurface. */
    val ink: Color,
    /** Two of the celebration's lights. */
    val faceFill: Color,
    val blush: Color,
    /** The logo's orange: the title's offset shadow only. */
    val logo: Color,
    /** The cards on the page (the sections, the quiet result): white in the light theme. */
    val card: Color,
    /**
     * The whole page behind each screen: its hero's color, much lighter (light theme) or barely there
     * (dark), so every screen is one color. [pageGood] only for a verified booking; [pageQuiet] for
     * settings and every other result, so nothing unverified looks like a success.
     */
    val pageRequest: Color,
    val pageConfirm: Color,
    val pageCall: Color,
    val pageGood: Color,
    val pageQuiet: Color,
)

internal val AitelHeroLight = AitelColors(
    heroRequest = Color(0xFFFFB020),
    heroConfirm = Color(0xFFB79BF2),
    heroCall = Color(0xFF7FCDF7),
    heroResult = Color(0xFF3DC6B3),
    ink = Color(0xFF2A2240),
    faceFill = Color(0xFFFFF4E6),
    blush = Color(0xFFFF9BC4),
    logo = Color(0xFFFF6B2C),
    card = Color(0xFFFFFFFF),
    pageRequest = Color(0xFFFFE4B1),
    pageConfirm = Color(0xFFE6DCFA),
    pageCall = Color(0xFFD2EEFC),
    pageGood = Color(0xFFBBEBE4),
    pageQuiet = Color(0xFFF1EEF7),
)

/** The same hues a little deeper on the near-black page; the text on them stays navy. */
internal val AitelHeroDark = AitelColors(
    heroRequest = Color(0xFFF0A31C),
    heroConfirm = Color(0xFFA68BE6),
    heroCall = Color(0xFF6BB8E3),
    heroResult = Color(0xFF33B3A1),
    ink = Color(0xFF2A2240),
    faceFill = Color(0xFFFFF4E6),
    blush = Color(0xFFFF9BC4),
    logo = Color(0xFFFF6B2C),
    card = Color(0xFF2C2C31),
    pageRequest = Color(0xFF0F0F11),
    pageConfirm = Color(0xFF121219),
    pageCall = Color(0xFF0E121A),
    pageGood = Color(0xFF0D1615),
    pageQuiet = Color(0xFF0F0F11),
)

/** The decorative shape behind the bird: the hero's own color, lighter. */
internal fun heroBackdrop(band: Color): Color = lerp(band, Color.White, 0.45f)

/** Provided by RotepTheme; using it outside the theme is a mistake, so it fails loudly. */
val LocalAitelColors = staticCompositionLocalOf<AitelColors> { error("AitelColors are provided by RotepTheme") }
