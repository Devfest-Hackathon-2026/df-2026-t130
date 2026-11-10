// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colors: text is readable on what it is drawn on, the practice band stands apart. */
class ColorContrastTest {
    private val themes = listOf(
        Triple("Light", AitelLight, AitelHeroLight),
        Triple("Dark", AitelDark, AitelHeroDark),
    )

    /** WCAG 2 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAtLeast(min: Double, label: String, a: Color, b: Color) {
        val ratio = contrast(a, b)
        assertTrue("$label: %.2f < %.1f".format(ratio, min), ratio >= min)
    }

    /** Text (first) on what the screens draw it on (second). */
    private fun pairs(s: ColorScheme) = listOf(
        "onPrimary/primary (buttons)" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer (notices, tonal button)" to (s.onSecondaryContainer to s.secondaryContainer),
        "inverseOnSurface/inverseSurface (practice band)" to (s.inverseOnSurface to s.inverseSurface),
        "onErrorContainer/errorContainer (error notices)" to (s.onErrorContainer to s.errorContainer),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/surfaceContainerHighest (cards)" to (s.onSurface to s.surfaceContainerHighest),
        "onSurfaceVariant/surfaceContainerHighest (small text in cards)" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "error/surface (error text)" to (s.error to s.surface),
        "error/surfaceContainerHighest (error text in cards)" to (s.error to s.surfaceContainerHighest),
        "primary/surface (text buttons, the title's second part)" to (s.primary to s.surface),
    )

    @Test
    fun textIsReadableOnWhatItIsDrawnOn() {
        for ((name, scheme, _) in themes) {
            for ((pair, colors) in pairs(scheme)) assertAtLeast(4.5, "$name $pair", colors.first, colors.second)
        }
    }

    /** The four bright heroes, by name. */
    private fun bright(h: AitelColors) =
        listOf("request" to h.heroRequest, "confirm" to h.heroConfirm, "call" to h.heroCall, "result" to h.heroResult)

    @Test
    fun heroTextIsReadable() {
        for ((mode, s, h) in themes) {
            // Both lines of a hero title are navy, on all four heroes and in both themes
            for ((hero, bg) in bright(h)) assertAtLeast(4.5, "$mode ink on $hero", h.ink, bg)
            // The quiet result block (anything but a checked booking)
            assertAtLeast(4.5, "$mode onSurface on the quiet result", s.onSurface, h.card)
        }
    }

    @Test
    fun thePracticeBandStandsApart() {
        for ((mode, s, h) in themes) {
            // Not like a notice or an error ...
            assertAtLeast(3.0, "$mode band vs notices", s.inverseSurface, s.secondaryContainer)
            assertAtLeast(3.0, "$mode band vs errors", s.inverseSurface, s.errorContainer)
            // ... nor like a checked booking (cream against teal in the dark theme: 2.4)
            assertAtLeast(2.0, "$mode band vs the booked hero", s.inverseSurface, h.heroResult)
        }
    }

    @Test
    fun noRoleIsLeftAtTheDefaultPurple() {
        for ((name, scheme, _) in themes) {
            val default = if (name == "Light") lightColorScheme() else darkColorScheme()
            val roles = listOf<Pair<String, (ColorScheme) -> Color>>(
                "primary" to { it.primary }, "primaryContainer" to { it.primaryContainer },
                "inversePrimary" to { it.inversePrimary }, "secondary" to { it.secondary },
                "secondaryContainer" to { it.secondaryContainer }, "tertiary" to { it.tertiary },
                "tertiaryContainer" to { it.tertiaryContainer }, "surface" to { it.surface },
                "surfaceVariant" to { it.surfaceVariant }, "surfaceTint" to { it.surfaceTint },
                "outline" to { it.outline }, "surfaceContainerHighest" to { it.surfaceContainerHighest },
                "inverseSurface" to { it.inverseSurface },
                "primaryFixed" to { it.primaryFixed }, "tertiaryFixed" to { it.tertiaryFixed },
            )
            for ((role, get) in roles) assertNotEquals("$name.$role", get(default), get(scheme))
        }
    }

    @Test
    fun theNoticesAndTheErrorsDiffer() {
        for ((name, s, _) in themes) assertNotEquals(name, s.errorContainer, s.secondaryContainer)
    }

    @Test
    fun theKeyFactsAreReadableAndStandOut() {
        for ((name, s, h) in themes) {
            assertAtLeast(4.5, "$name onSurface on the key facts", s.onSurface, h.card)
            assertAtLeast(4.5, "$name onSurfaceVariant on the key facts", s.onSurfaceVariant, h.card)
            assertAtLeast(4.5, "$name small text on the page", s.onSurfaceVariant, s.surface)
        }
        for ((name, s, h) in themes) {
            assertAtLeast(4.5, "$name error text on a card", s.error, h.card)
            assertAtLeast(3.0, "$name outline of the key facts on the page", s.primary, s.surface)
        }
        assertAtLeast(1.3, "Dark key facts on the page", AitelHeroDark.card, AitelDark.surface)
    }

    @Test
    fun everyPageIsReadable() {
        for ((name, s, h) in themes) {
            for (page in listOf(h.pageRequest, h.pageConfirm, h.pageCall, h.pageGood, h.pageQuiet)) {
                assertAtLeast(4.5, "$name text on a page", s.onSurface, page)
                assertAtLeast(4.5, "$name small text on a page", s.onSurfaceVariant, page)
                assertAtLeast(4.5, "$name text buttons on a page", s.primary, page)
                assertAtLeast(4.5, "$name errors on a page", s.error, page)
            }
        }
    }

    @Test
    fun theLabelsOnTheNavyCardAreReadable() {
        for (accent in listOf(AitelHeroLight.heroConfirm, AitelHeroLight.heroResult)) {
            assertAtLeast(4.5, "accent label on navy", accent, AitelLight.inverseSurface)
        }
        assertAtLeast(4.5, "navy label on cream", AitelDark.inverseOnSurface, AitelDark.inverseSurface)
    }
}
