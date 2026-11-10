// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SineToneTest {
    @Test
    fun samplesFor20msAt16k() {
        assertEquals(320, SineTone.samplesFor(20, 16_000))
        assertEquals(960, SineTone.samplesFor(20, 48_000))
    }

    @Test
    fun oneKhzAt16kHasPeriodOf16Samples() {
        val tone = SineTone(1_000.0, 16_000, 0.5)
        val buf = ShortArray(32)
        tone.fill(buf)
        assertEquals(0, buf[0].toInt())
        // One period later the waveform repeats (allow rounding error)
        for (i in 0 until 16) {
            assertTrue("sample $i", abs(buf[i] - buf[i + 16]) <= 1)
        }
    }

    @Test
    fun amplitudeIsRespected() {
        val tone = SineTone(1_000.0, 16_000, 0.5)
        val buf = ShortArray(1_600)
        tone.fill(buf)
        val peak = buf.maxOf { abs(it.toInt()) }
        assertTrue(peak in 16_000..16_384)
    }

    @Test
    fun phaseContinuesAcrossCalls() {
        val a = SineTone(1_000.0, 16_000)
        val whole = ShortArray(40).also { a.fill(it) }
        val b = SineTone(1_000.0, 16_000)
        val first = ShortArray(13).also { b.fill(it) }
        val second = ShortArray(27).also { b.fill(it) }
        assertEquals(whole.toList(), (first + second).toList())
    }
}
