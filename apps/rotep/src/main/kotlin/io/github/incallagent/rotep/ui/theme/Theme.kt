// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val AitelTypography = Typography().withJapaneseLineBreaks()

private val AitelShapes =Shapes(large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp))

/**
 * AITEL's theme, in one place: fixed light and dark color schemes (Material3 roles) plus the hero
 * colors ([LocalAitelColors]), the system font, and the corner steps. [darkTheme] follows the
 * phone's setting; the previews set it. No wallpaper colors: the color of the stop button and of the
 * warnings must not change per phone. The motion is Material's standard scheme, on purpose: the
 * screens and their parts move calmly. Only the celebration of a verified booking uses the expressive
 * one (CelebratingFace). Corners come in steps, so shapes differ in importance: 28 dp for the heroes and
 * the key facts, 20 dp for the sections and the call's header (Material's expressive corner scale).
 */
@Composable
fun RotepTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAitelColors provides if (darkTheme) AitelHeroDark else AitelHeroLight) {
        MaterialTheme(
            colorScheme = if (darkTheme) AitelDark else AitelLight,
            motionScheme = MotionScheme.standard(),
            shapes = AitelShapes,
            typography = AitelTypography,
            content = content,
        )
    }
}
