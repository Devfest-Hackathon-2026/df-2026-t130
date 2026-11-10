// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The celebration's lights: nothing shows at rest, and they stay near the face, inside the block. */
class CelebrationTest {
    @Test
    fun invisibleAtBothEnds() {
        for (i in 0 until LIGHT_COUNT) {
            for (t in listOf(-0.5f, 0f, 1f, 1.5f)) assertEquals("light $i at $t", 0f, celebrationLight(i, t).alpha, 0f)
            assertTrue("light $i shows halfway", celebrationLight(i, 0.5f).alpha > 0.9f)
        }
    }

    @Test
    fun stayNearTheFace() {
        // The face is 92 dp; the hero block has at least 16 dp of padding around it
        val limit = 0.5f + 16f / 92f
        for (i in 0 until LIGHT_COUNT) {
            for (step in 0..100) {
                val l = celebrationLight(i, step / 100f)
                assertTrue("light $i at $step %: x", abs(l.x) + l.radius <= limit)
                assertTrue("light $i at $step %: y", abs(l.y) + l.radius <= limit)
            }
        }
    }
}
