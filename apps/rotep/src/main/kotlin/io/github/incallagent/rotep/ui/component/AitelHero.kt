// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.ui.theme.AitelLogoFamily
import io.github.incallagent.rotep.ui.theme.CallPhaseStyle
import io.github.incallagent.rotep.ui.theme.HeroTitleStyle
import io.github.incallagent.rotep.ui.theme.HeroTitleStyleLargeText
import io.github.incallagent.rotep.ui.theme.LocalAitelColors

/**
 * The app's title on every screen: "アイてる!? - AITEL -", the short name (as the launcher shows it)
 * big, with the logo's orange offset shadow, then "- AITEL -" small. Not a heading: each screen's name is.
 */
@Composable
fun AitelTitle(modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    val sub = stringResource(R.string.app_title_sub)
    val spoken = stringResource(R.string.app_title_spoken)
    // A logo: it grows with the user's text size only up to 130 %, so it stays whole on one line
    // next to "設定" (the screen's own words grow fully)
    val density = LocalDensity.current
    val grow = minOf(density.fontScale, 1.3f)
    val (big, small) = with(density) { listOf((24f * grow).dp.toSp(), (15f * grow).dp.toSp()) }
    // As in the logo: the same letters again in orange, a little down and right (6 % of their size).
    // Only here, on the page: on the colored heroes the shadow would not show
    val shift = with(density) { (24f * grow * 0.06f).dp.toPx() }
    val shadow = Shadow(color = LocalAitelColors.current.logo, offset = Offset(shift, shift), blurRadius = 0f)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = AitelLogoFamily, fontSize = big, shadow = shadow)) { append(name) }
            withStyle(SpanStyle(fontFamily = AitelLogoFamily, fontSize = small, color = MaterialTheme.colorScheme.primary)) {
                append(" ")
                append(sub)
            }
        },
        modifier = modifier.semantics { contentDescription = spoken },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Which hero, so the colors are chosen here and not on each screen. */
enum class HeroKind {
    REQUEST,
    CONFIRM,

    /** Booked, and the check found nothing: the only bright result. */
    RESULT_GOOD,

    /** Any other result: a quiet block, so nothing unverified looks like a success. */
    RESULT_OTHER,
}

/**
 * Clips above and below only: the moving words stay inside their line, and the big letters, which may
 * reach a pixel past their box at the sides, are not cut when they stand still.
 */
private fun Modifier.clipTopAndBottom() = drawWithContent {
    clipRect(left = -size.width, right = size.width * 2) { this@drawWithContent.drawContent() }
}

/** Text at 150 % or more: the heroes shrink and drop the face, so the screen's content stays in reach. */
@Composable
internal fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

/**
 * The colored block at the top of a screen: the screen's name (a heading for screen readers), a short
 * big title ([first], then [second]) in navy, as in the owner's color-variation image, and AITEL's face.
 * [celebrate]: the hero of a verified booking ([HeroKind.RESULT_GOOD]) plays its celebration when it
 * appears and calls [onCelebrate] as it starts; the caller remembers that, so it plays once.
 * [titleIsHeading]: the big title is a heading of its own, for a result with no other headline.
 */
@Composable
fun AitelHero(
    kind: HeroKind,
    screenName: String,
    first: String,
    second: String?,
    mood: AitelMood,
    modifier: Modifier = Modifier,
    showFace: Boolean = true,
    celebrate: Boolean = false,
    onCelebrate: () -> Unit = {},
    titleIsHeading: Boolean = false,
) {
    val colors = LocalAitelColors.current
    val scheme = MaterialTheme.colorScheme
    val (background, text) = when (kind) {
        HeroKind.REQUEST -> colors.heroRequest to colors.ink
        HeroKind.CONFIRM -> colors.heroConfirm to colors.ink
        HeroKind.RESULT_GOOD -> colors.heroResult to colors.ink
        HeroKind.RESULT_OTHER -> colors.card to scheme.onSurface
    }
    val large = largeText()
    Surface(color = background, contentColor = text, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(screenName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                // Two parts, each up to two lines: a long shop name is cut, never the question after
                // it. Screen readers read them as one phrase.
                Column(Modifier.semantics(mergeDescendants = true) { if (titleIsHeading) heading() }) {
                    val style = if (large) HeroTitleStyleLargeText else HeroTitleStyle
                    Text(first, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (second != null) Text(second, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showFace && !large) {
                Spacer(Modifier.size(8.dp))
                // Only a verified booking celebrates; the lights stay inside the block
                HeroFace(kind, background, mood, celebrate, onCelebrate)
            }
        }
    }
}

/**
 * The fixed top of the call screen: the state in big letters (the screen's first heading) and the
 * face. [compact] (a short screen): no face and no name line, less padding, so the buttons at the
 * bottom keep their room. Large text drops the face too, so the state keeps its width. The state may
 * take two lines (a narrow phone, large text), never more: the top must stay small.
 *
 * When the state changes, the new words come up a little from below as the old ones fade away upward,
 * and the face cross-fades (no bounce: the call screen moves calmly). The height is not animated:
 * during the change the header is as tall as the taller words.
 */
@Composable
fun AitelCallHeader(
    screenName: String,
    phase: String,
    mood: AitelMood,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    announce: Boolean = false,
    below: @Composable () -> Unit = {},
) {
    val colors = LocalAitelColors.current
    val motion = MaterialTheme.motionScheme
    val showFace = !compact && !largeText()
    Surface(color = colors.heroCall, contentColor = colors.ink, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFace) {
                Crossfade(mood, Modifier.size(52.dp), animationSpec = motion.defaultEffectsSpec(), label = "face") {
                    AitelFace(it, Modifier.fillMaxSize())
                }
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (!compact) Text(screenName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
                // One heading, the screen's name and the state, on the outside: during the change both
                // words are on screen. [announce]: no other line says the state, so this one reads it out
                AnimatedContent(
                    targetState = phase,
                    modifier = Modifier.clipTopAndBottom().semantics {
                        heading()
                        contentDescription = "$screenName：$phase"
                        if (announce) liveRegion = LiveRegionMode.Polite
                    },
                    transitionSpec = {
                        (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 3 } + fadeOut(motion.fastEffectsSpec()))
                            .using(null)
                    },
                    label = "phase",
                ) { words ->
                    Text(
                        words,
                        style = CallPhaseStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                below()
            }
        }
    }
}
