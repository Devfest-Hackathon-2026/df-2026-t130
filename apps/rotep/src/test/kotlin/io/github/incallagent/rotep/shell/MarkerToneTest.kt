// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10

class MarkerToneTest {
    private val rate = MarkerTone.SAMPLE_RATE
    private val tone = MarkerTone.frames(MarkerTone.TONE_MS)
    private val fade = MarkerTone.frames(MarkerTone.FADE_MS)

    /** Goertzel: power of [hz] in samples[from until from + count]. */
    private fun power(samples: ShortArray, from: Int, count: Int, hz: Int): Double {
        val coeff = 2.0 * cos(2.0 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until from + count) {
            val s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun zeroCrossings(samples: ShortArray, from: Int, count: Int): Int {
        var n = 0
        for (i in from + 1 until from + count) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) n++
        }
        return n
    }

    @Test
    fun patternIsThreeSecondsOfThreeOneSecondTones() {
        assertEquals(listOf(880, 1320, 1760), MarkerTone.FREQUENCIES_HZ)
        assertEquals(48_000, tone)
        assertEquals(MarkerTone.frames(MarkerTone.PATTERN_MS), MarkerTone.pattern().size)
        assertEquals(3 * 48_000, MarkerTone.pattern().size)
        assertEquals(10 * 48_000, MarkerTone.cycleFrames())
    }

    @Test
    fun eachToneHasItsOwnPitch() {
        val p = MarkerTone.pattern()
        for ((n, hz) in MarkerTone.FREQUENCIES_HZ.withIndex()) {
            val own = power(p, n * tone, tone, hz)
            for (other in MarkerTone.FREQUENCIES_HZ) {
                if (other == hz) continue
                val ratioDb = 10 * log10(own / power(p, n * tone, tone, other))
                assertTrue("tone $n: $hz Hz over $other Hz is only $ratioDb dB", ratioDb > 40)
            }
            // The steady middle (0.9 s): two zero crossings per period.
            val steady = MarkerTone.frames(900)
            val crossings = zeroCrossings(p, n * tone + MarkerTone.frames(50), steady)
            assertEquals("tone $n", 2.0 * hz * 0.9, crossings.toDouble(), 3.0)
        }
    }

    @Test
    fun defaultPeakIsMinus12DbfsAndRmsIsThreeDbLower() {
        val p = MarkerTone.pattern()
        assertEquals(-12f, LevelMeter.measure(p).peakDbfs, 0.1f)
        for (n in 0 until 3) {
            // Inside one tone, away from the fades.
            val level = LevelMeter.measure(p, n * tone + fade, tone - 2 * fade)
            assertEquals("tone $n peak", -12f, level.peakDbfs, 0.1f)
            assertEquals("tone $n rms", -15.01f, level.rmsDbfs, 0.1f)
        }
    }

    @Test
    fun levelCanBeChanged() {
        assertEquals(-24f, LevelMeter.measure(MarkerTone.pattern(-24.0)).peakDbfs, 0.1f)
        assertEquals(-6f, LevelMeter.measure(MarkerTone.pattern(-6.0)).peakDbfs, 0.1f)
        assertEquals(1.0, MarkerTone.amplitude(0.0), 1e-12)
        assertEquals(0.5012, MarkerTone.amplitude(-6.0), 1e-4)
        // Never above full scale.
        assertEquals(1.0, MarkerTone.amplitude(6.0), 1e-12)
        assertEquals(0f, LevelMeter.measure(MarkerTone.pattern(3.0)).peakDbfs, 0.1f)
    }

    @Test
    fun everyToneFadesInAndOut() {
        val p = MarkerTone.pattern()
        val peak = MarkerTone.amplitude(-12.0) * 32767
        for (n in 0 until 3) {
            val start = n * tone
            val end = start + tone - 1
            assertEquals("first frame of tone $n", 0, p[start].toInt())
            assertEquals("last frame of tone $n", 0, p[end].toInt())
            // The first and last millisecond stay far below the peak.
            val ms = MarkerTone.frames(1)
            for (i in 0 until ms) {
                assertTrue(abs(p[start + i].toInt()) < peak * 0.03)
                assertTrue(abs(p[end - i].toInt()) < peak * 0.03)
            }
        }
        assertEquals(0.0, MarkerTone.fadeGain(0, tone, fade), 0.0)
        assertEquals(0.5, MarkerTone.fadeGain(fade / 2, tone, fade), 1e-9)
        assertEquals(1.0, MarkerTone.fadeGain(fade, tone, fade), 0.0)
        assertEquals(1.0, MarkerTone.fadeGain(tone / 2, tone, fade), 0.0)
        assertEquals(0.0, MarkerTone.fadeGain(tone - 1, tone, fade), 0.0)
    }

    @Test
    fun theJointsDoNotClick() {
        val p = MarkerTone.pattern()
        val peak = MarkerTone.amplitude(-12.0) * 32767
        // The steepest a 1760 Hz sine at this peak can change between two frames, plus rounding.
        val limit = peak * 2 * PI * 1760 / rate + 2
        var worst = 0
        for (i in 1 until p.size) worst = maxOf(worst, abs(p[i] - p[i - 1]))
        assertTrue("largest step $worst > $limit", worst <= limit)
    }

    @Test
    fun cycleIsTonesThenSilenceAgainAndAgain() {
        val pattern = MarkerTone.pattern()
        val cycleFrames = MarkerTone.cycleFrames()
        val cycle = MarkerCycle(pattern, cycleFrames)
        // Read two cycles in odd-sized chunks.
        val out = ShortArray(2 * cycleFrames)
        val chunk = ShortArray(997)
        var at = 0
        while (at < out.size) {
            val n = minOf(chunk.size, out.size - at)
            cycle.read(chunk, n)
            chunk.copyInto(out, at, 0, n)
            at += n
        }
        for (c in 0 until 2) {
            val base = c * cycleFrames
            assertArrayEquals(pattern, out.copyOfRange(base, base + pattern.size))
            for (i in base + pattern.size until base + cycleFrames) assertEquals(0, out[i].toInt())
        }
        assertEquals(0, cycle.position)
    }

    @Test
    fun markersPerTest() {
        assertEquals(2, MarkerTone.markersIn(20_000))
        assertEquals(listOf(0, 10), MarkerTone.markerStartSeconds(20_000))
        assertEquals(1, MarkerTone.markersIn(10_000))
        assertEquals(3, MarkerTone.markersIn(30_000))
        assertEquals(listOf(0, 10, 20), MarkerTone.markerStartSeconds(30_000))
        assertEquals(1, MarkerTone.markersIn(3_000))
        assertEquals(0, MarkerTone.markersIn(2_999))
        // The second marker is cut at 12 s, so only one is whole.
        assertEquals(1, MarkerTone.markersIn(12_000))
    }
}
