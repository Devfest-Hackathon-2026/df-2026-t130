// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import io.github.incallagent.rotep.ui.theme.AitelColors
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The only strong motion in the app: Material's expressive springs overshoot a little. */
private val Expressive = MotionScheme.expressive()

/** How high AITEL hops. */
private val HOP = 12.dp

/** How much the face flattens per unit of the landing spring's dip. */
private const val SQUASH = 1.2f

/** How long the lights take to fly out and fade. */
private const val LIGHTS_MS = 700

/**
 * AITEL's face on the hero of a verified booking, in a [box] with the decorative shape in [backdrop].
 * [play]: the celebration plays when the face first appears (the shape blooms from a cookie into a sun,
 * AITEL hops once and lands with a small squash, and four small lights fly out and fade, in under a
 * second), and [onPlay] is called as it starts, so the caller can remember it and not play it again.
 * Only the first value of [play] counts. What stays is always the same: the sun, the face at rest, no
 * lights. So nothing is lost after turning the screen, with the phone's animations removed, or in a
 * preview.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CelebratingFace(
    mood: AitelMood,
    box: Dp,
    face: Dp,
    backdrop: Color,
    play: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hop = remember { Animatable(0f) }
    val lights = remember { Animatable(0f) }
    val still = LocalInspectionMode.current
    val bloom = remember { Animatable(if (play && !still) 0f else 1f) }
    val played by rememberUpdatedState(onPlay)
    LaunchedEffect(Unit) {
        if (!play || still) return@LaunchedEffect
        played()
        // Animations removed on the phone: each spring would end in one frame, and the face would show
        // at the top of its hop for that frame. Skip it all
        if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) {
            bloom.snapTo(1f)
            return@LaunchedEffect
        }
        coroutineScope {
            launch { lights.animateTo(1f, tween(LIGHTS_MS, easing = LinearOutSlowInEasing)) }
            launch { bloom.animateTo(1f, Expressive.slowSpatialSpec()) }
            // Up and down with the same spring: a little past the top, and a little down on landing
            hop.animateTo(1f, Expressive.fastSpatialSpec())
            hop.animateTo(0f, Expressive.fastSpatialSpec())
        }
    }
    val colors = LocalAitelColors.current
    val lift = with(LocalDensity.current) { HOP.toPx() }
    // The lights are drawn over the face's edge, so they show from the start; they stay where they are
    // while the face hops
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
    val path = remember { Path() }
    val scale = remember { Matrix() }
    Box(
        modifier.size(box).drawBehind {
            // The spring may pass its end a little; the shape does not
            morph.toPath(bloom.value.coerceIn(0f, 1f), path, startAngle = 0)
            scale.reset()
            scale.scale(size.width, size.height)
            path.transform(scale)
            drawPath(path, backdrop)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(face).drawWithContent {
                drawContent()
                drawLights(lights.value, colors)
            },
        ) {
            AitelFace(
                mood,
                Modifier.fillMaxSize().graphicsLayer {
                    // Above rest it is in the air. The spring's small dip below rest is the landing: a squash
                    val h = hop.value
                    translationY = -maxOf(h, 0f) * lift
                    val squash = minOf(h, 0f) * SQUASH
                    scaleY = 1f + squash
                    scaleX = 1f - squash / 2f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            )
        }
    }
}

/** One light: its center from the face's center and its radius, in parts of the face's size, and how much it shows (0 to 1). */
internal data class Light(val x: Float, val y: Float, val radius: Float, val alpha: Float)

internal const val LIGHT_COUNT = 4

/** Where the lights fly: beside the face and past its upper corners (0 is to the right; the y axis points down). */
private val ANGLES = listOf(-165.0, -130.0, -50.0, -15.0).map { Math.toRadians(it).toFloat() }

private val SIZES = listOf(0.07f, 0.10f, 0.10f, 0.07f)

/**
 * The [index]th light when the celebration is at [t] (0 to 1): one function of [t], invisible at both
 * ends, so at rest (before, after, with animations removed) there is nothing to see.
 */
internal fun celebrationLight(index: Int, t: Float): Light {
    val p = t.coerceIn(0f, 1f)
    val glow = if (p <= 0f || p >= 1f) 0f else sin(PI.toFloat() * p)
    val distance = 0.52f + 0.12f * p
    val angle = ANGLES[index]
    return Light(cos(angle) * distance, sin(angle) * distance, SIZES[index] * (0.5f + 0.5f * glow), glow)
}

private fun DrawScope.drawLights(t: Float, colors: AitelColors) {
    if (t <= 0f || t >= 1f) return
    val tints = listOf(colors.logo, colors.heroRequest, colors.blush, colors.faceFill)
    val s = size.minDimension
    for (i in 0 until LIGHT_COUNT) {
        val l = celebrationLight(i, t)
        drawSparkle(Offset(size.width / 2 + l.x * s, size.height / 2 + l.y * s), l.radius * s, tints[i].copy(alpha = l.alpha))
    }
}

/** A small four-pointed star. */
private fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val inner = radius * 0.38f
    val path = Path()
    for (k in 0 until 8) {
        val r = if (k % 2 == 0) radius else inner
        val a = k * PI.toFloat() / 4f - PI.toFloat() / 2f
        val x = center.x + cos(a) * r
        val y = center.y + sin(a) * r
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
