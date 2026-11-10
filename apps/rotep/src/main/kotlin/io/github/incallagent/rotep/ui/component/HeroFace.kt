// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.ui.theme.heroBackdrop

private val BOX = 92.dp

/** Nearly the box: the bird is slimmer than the shape behind it, so the shape shows around it. */
private val FACE_ON_SHAPE = 88.dp

/**
 * AITEL's face on a hero, on one of Material's decorative shapes in a lighter tint of the block's [band]
 * color. The shape is only decoration (it means nothing and is not read aloud) and stays inside the
 * face's box, so it takes no room from the words. The quiet results have no shape. A verified booking
 * can [celebrate]: its shape blooms from a cookie into a sun (see [CelebratingFace]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroFace(kind: HeroKind, band: Color, mood: AitelMood, celebrate: Boolean, onCelebrate: () -> Unit) {
    val polygon = when (kind) {
        HeroKind.REQUEST -> MaterialShapes.Cookie9Sided
        HeroKind.CONFIRM -> MaterialShapes.Clover4Leaf
        HeroKind.RESULT_GOOD -> {
            CelebratingFace(mood, BOX, FACE_ON_SHAPE, heroBackdrop(band), play = celebrate, onPlay = onCelebrate)
            return
        }
        HeroKind.RESULT_OTHER -> {
            AitelFace(mood, Modifier.size(BOX))
            return
        }
    }
    Box(Modifier.size(BOX), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(heroBackdrop(band), polygon.toShape()))
        AitelFace(mood, Modifier.size(FACE_ON_SHAPE))
    }
}
