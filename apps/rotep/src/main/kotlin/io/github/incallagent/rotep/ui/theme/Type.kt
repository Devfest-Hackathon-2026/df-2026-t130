// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import io.github.incallagent.rotep.R

/*
 * Text: the phone's font for everything people read (body, labels, buttons, fields), and two display
 * fonts for the few big words (see NOTICE; both SIL OFL 1.1, bundled unchanged):
 *   - Dela Gothic One: the title "アイてる!? - AITEL -"
 *   - 源暎きわみゴ (GenEi Kiwami Gothic): the big headings (heroes, the call's state, the result)
 * Both have a single, very heavy weight: they are used at FontWeight.Normal, never bolded again.
 * A character missing from a display font is drawn with the phone's font.
 */

/** Dela Gothic One, for the app's title. */
val AitelLogoFamily = FontFamily(Font(R.font.dela_gothic_one, FontWeight.Normal))

/** 源暎きわみゴ, for the big headings. */
val AitelDisplayFamily = FontFamily(Font(R.font.genei_kiwami_go, FontWeight.Normal))

/**
 * How the big headings (and the practice band) break lines: by phrase where the phone can (Japanese,
 * Android 13 and later), into lines of even length, so a word is not split and no "の" or "た" is left
 * alone on the last line; and by the strict Japanese rules, so no line starts with "・" or a small kana.
 */
val HeadingLineBreak = LineBreak(
    strategy = LineBreak.Strategy.Balanced,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

/**
 * How running text breaks lines: by the strict Japanese rules (no line starts with a closing bracket,
 * "ー" or a small kana) and at the end of a phrase where the phone can, so "キーと同じ" or "相手が切った）"
 * is not torn apart. Earlier phones ignore what they do not know.
 */
val BodyLineBreak = LineBreak(
    strategy = LineBreak.Strategy.Simple,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

private fun TextStyle.broken() = copy(lineBreak = BodyLineBreak)

/** Material's type scale with [BodyLineBreak] on every style (the sizes and weights are Material's). */
fun Typography.withJapaneseLineBreaks(): Typography = copy(
    displayLarge = displayLarge.broken(),
    displayMedium = displayMedium.broken(),
    displaySmall = displaySmall.broken(),
    headlineLarge = headlineLarge.broken(),
    headlineMedium = headlineMedium.broken(),
    headlineSmall = headlineSmall.broken(),
    titleLarge = titleLarge.broken(),
    titleMedium = titleMedium.broken(),
    titleSmall = titleSmall.broken(),
    bodyLarge = bodyLarge.broken(),
    bodyMedium = bodyMedium.broken(),
    bodySmall = bodySmall.broken(),
    labelLarge = labelLarge.broken(),
    labelMedium = labelMedium.broken(),
    labelSmall = labelSmall.broken(),
    displayLargeEmphasized = displayLargeEmphasized.broken(),
    displayMediumEmphasized = displayMediumEmphasized.broken(),
    displaySmallEmphasized = displaySmallEmphasized.broken(),
    headlineLargeEmphasized = headlineLargeEmphasized.broken(),
    headlineMediumEmphasized = headlineMediumEmphasized.broken(),
    headlineSmallEmphasized = headlineSmallEmphasized.broken(),
    titleLargeEmphasized = titleLargeEmphasized.broken(),
    titleMediumEmphasized = titleMediumEmphasized.broken(),
    titleSmallEmphasized = titleSmallEmphasized.broken(),
    bodyLargeEmphasized = bodyLargeEmphasized.broken(),
    bodyMediumEmphasized = bodyMediumEmphasized.broken(),
    bodySmallEmphasized = bodySmallEmphasized.broken(),
    labelLargeEmphasized = labelLargeEmphasized.broken(),
    labelMediumEmphasized = labelMediumEmphasized.broken(),
    labelSmallEmphasized = labelSmallEmphasized.broken(),
)

/*
 * The two parts of a hero's title are two texts, one above the other: each keeps its whole line
 * height (no trim), so the gap between the parts is the gap between lines.
 */
private val WholeLines = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/** The big two-color titles of the heroes. */
val HeroTitleStyle = TextStyle(
    fontFamily = AitelDisplayFamily,
    fontSize = 30.sp,
    lineHeight = 38.sp,
    lineHeightStyle = WholeLines,
    lineBreak = HeadingLineBreak,
)

/** The same when the user's text is already large (150 % or more). */
val HeroTitleStyleLargeText = TextStyle(
    fontFamily = AitelDisplayFamily,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    lineHeightStyle = WholeLines,
    lineBreak = HeadingLineBreak,
)

/** The call's state at the top of the call screen. */
val CallPhaseStyle = TextStyle(fontFamily = AitelDisplayFamily, fontSize = 22.sp, lineHeight = 28.sp, lineBreak = HeadingLineBreak)

/** The headline of the result. */
val ResultHeadlineStyle = TextStyle(fontFamily = AitelDisplayFamily, fontSize = 24.sp, lineHeight = 32.sp, lineBreak = HeadingLineBreak)

/** The same at large text (150 % or more): it stays smaller than the hero's title above it. */
val ResultHeadlineStyleLargeText = TextStyle(fontFamily = AitelDisplayFamily, fontSize = 20.sp, lineHeight = 26.sp, lineBreak = HeadingLineBreak)

/**
 * Times on the screen ("会話 1:12（上限 5分）"): tabular digits, so the width does not move every
 * second. Fonts without the feature ignore it.
 */
val ClockStyle = TextStyle(fontSize = 14.sp, fontFeatureSettings = "tnum")
