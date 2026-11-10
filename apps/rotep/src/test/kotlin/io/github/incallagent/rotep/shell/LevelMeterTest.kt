// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class LevelMeterTest {
    private fun sine(frames: Int, amplitude: Int, rate: Int = 48000, hz: Int = 1000): ShortArray =
        ShortArray(frames) { i -> (amplitude * sin(2.0 * PI * hz * i / rate)).roundToInt().toShort() }

    @Test
    fun digitalSilenceIsTheFloor() {
        val level = LevelMeter.measure(ShortArray(4800))
        assertEquals(LevelMeter.FLOOR_DBFS, level.peakDbfs, 0f)
        assertEquals(LevelMeter.FLOOR_DBFS, level.rmsDbfs, 0f)
    }

    @Test
    fun fullScaleSquareWaveIsZeroDbfsForPeakAndRms() {
        val samples = ShortArray(4800) { i -> if (i % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val level = LevelMeter.measure(samples)
        assertEquals(0f, level.peakDbfs, 0.001f)
        assertEquals(0f, level.rmsDbfs, 0.001f)
    }

    @Test
    fun fullScaleSineHasRmsAboutMinus3Db() {
        // 1 kHz at 48 kHz: 4800 frames hold exactly 100 whole cycles.
        val level = LevelMeter.measure(sine(4800, 32767))
        assertEquals(0f, level.peakDbfs, 0.01f)
        assertEquals(-3.01f, level.rmsDbfs, 0.05f)
    }

    @Test
    fun halfAmplitudeIsAboutSixDbLower() {
        val level = LevelMeter.measure(sine(4800, 16384))
        assertEquals(-6.02f, level.peakDbfs, 0.05f)
        assertEquals(-9.03f, level.rmsDbfs, 0.05f)
    }

    @Test
    fun theMostNegativeSampleIsStillFullScale() {
        val samples = ShortArray(100) { 0 }
        samples[10] = Short.MIN_VALUE
        assertEquals(0f, LevelMeter.measure(samples).peakDbfs, 0.001f)
    }

    @Test
    fun oneLeastSignificantBitIsAboutMinus90Db() {
        val samples = ShortArray(100) { 1 }
        val level = LevelMeter.measure(samples)
        assertEquals(-90.31f, level.peakDbfs, 0.05f)
        assertEquals(-90.31f, level.rmsDbfs, 0.05f)
    }

    @Test
    fun measureCanLookAtPartOfAnArray() {
        val samples = ShortArray(300)
        for (i in 100 until 200) samples[i] = 16384
        val quiet = LevelMeter.measure(samples, 0, 100)
        val loud = LevelMeter.measure(samples, 100, 100)
        assertEquals(LevelMeter.FLOOR_DBFS, quiet.rmsDbfs, 0f)
        assertEquals(-6.02f, loud.rmsDbfs, 0.05f)
    }

    @Test
    fun noSamplesGiveTheFloor() {
        assertEquals(Level(LevelMeter.FLOOR_DBFS, LevelMeter.FLOOR_DBFS), LevelMeter.levelOf(0, 0.0, 0))
    }

    @Test
    fun aWindowIs100Milliseconds() {
        assertEquals(4800, LevelMeter.windowFrames(48000))
        assertEquals(1600, LevelMeter.windowFrames(16000))
        assertEquals(800, LevelMeter.windowFrames(8000))
        assertEquals(1, LevelMeter.windowFrames(1))
    }

    @Test
    fun windowReportsOnlyWhenFull() {
        val seen = ArrayList<Level>()
        val window = LevelWindow(100) { seen.add(it) }
        window.feed(ShortArray(99) { 1000 }, 99)
        assertTrue(seen.isEmpty())
        window.feed(ShortArray(1) { 1000 }, 1)
        assertEquals(1, seen.size)
    }

    @Test
    fun chunkSizeDoesNotChangeTheResult() {
        val all = sine(4800 * 3, 20000)
        val whole = ArrayList<Level>()
        LevelWindow(4800) { whole.add(it) }.feed(all, all.size)

        for (chunk in intArrayOf(1, 7, 240, 1000, 4800, 5000)) {
            val pieces = ArrayList<Level>()
            val window = LevelWindow(4800) { pieces.add(it) }
            var at = 0
            while (at < all.size) {
                val n = minOf(chunk, all.size - at)
                window.feed(all.copyOfRange(at, at + n), n)
                at += n
            }
            assertEquals("chunk=$chunk", whole, pieces)
        }
        assertEquals(3, whole.size)
    }

    @Test
    fun eachWindowStartsFresh() {
        val seen = ArrayList<Level>()
        val window = LevelWindow(10) { seen.add(it) }
        window.feed(ShortArray(10) { Short.MAX_VALUE }, 10)
        window.feed(ShortArray(10), 10)
        assertEquals(2, seen.size)
        assertTrue(seen[0].rmsDbfs > -0.01f)
        assertEquals(LevelMeter.FLOOR_DBFS, seen[1].rmsDbfs, 0f)
        assertEquals(LevelMeter.FLOOR_DBFS, seen[1].peakDbfs, 0f)
    }

    @Test
    fun countOnlyCoversTheGivenSamples() {
        // The buffer is bigger than what was read: the rest must not be measured.
        val seen = ArrayList<Level>()
        val window = LevelWindow(5) { seen.add(it) }
        val buffer = ShortArray(10) { 30000 }
        window.feed(buffer, 5)
        assertEquals(1, seen.size)
        window.feed(buffer, 0)
        assertEquals(1, seen.size)
    }
}
