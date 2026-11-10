// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import kotlin.math.log10
import kotlin.math.sqrt

/** Peak and RMS of one window of 16-bit PCM in dBFS (0 = full scale, [LevelMeter.FLOOR_DBFS] = silence). */
data class Level(val peakDbfs: Float, val rmsDbfs: Float)

/**
 * Level maths for the downlink meter. Pure Kotlin, no Android classes, so it runs in unit tests.
 * The audio itself is never kept: only these two numbers per window leave the helper.
 */
object LevelMeter {
    /** Reported for digital silence and for anything quieter than this. */
    const val FLOOR_DBFS = -120f

    /** Length of one measuring window. */
    const val WINDOW_MS = 100

    private const val FULL_SCALE = 32768.0

    /** Samples (mono frames) in one window at [sampleRate]. */
    fun windowFrames(sampleRate: Int): Int = (sampleRate.toLong() * WINDOW_MS / 1000).toInt().coerceAtLeast(1)

    /** [amplitude] is relative to full scale (0.0 .. 1.0). */
    fun toDbfs(amplitude: Double): Float {
        if (!(amplitude > 0.0)) return FLOOR_DBFS
        val db = 20.0 * log10(amplitude)
        return if (db < FLOOR_DBFS) FLOOR_DBFS else db.toFloat()
    }

    /** Builds a [Level] from running totals: [peakAbs] is the largest |sample| (0..32768). */
    fun levelOf(peakAbs: Int, sumSquares: Double, frames: Int): Level {
        if (frames <= 0) return Level(FLOOR_DBFS, FLOOR_DBFS)
        val rms = sqrt(sumSquares / frames) / FULL_SCALE
        return Level(toDbfs(peakAbs / FULL_SCALE), toDbfs(rms))
    }

    /** Measures [count] samples of [samples] starting at [offset]. */
    fun measure(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset): Level {
        var peak = 0
        var sumSquares = 0.0
        for (i in offset until offset + count) {
            val s = samples[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSquares += s.toDouble() * s
        }
        return levelOf(peak, sumSquares, count)
    }
}

/**
 * Cuts a stream of PCM chunks of any size into windows of exactly [windowFrames] samples and calls
 * [onWindow] with the level of each complete window. A partly filled window is kept for the next chunk.
 */
class LevelWindow(private val windowFrames: Int, private val onWindow: (Level) -> Unit) {
    init {
        require(windowFrames > 0) { "windowFrames must be positive" }
    }

    private var filled = 0
    private var peak = 0
    private var sumSquares = 0.0

    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = samples[i].toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSquares += s.toDouble() * s
            filled++
            if (filled == windowFrames) {
                val level = LevelMeter.levelOf(peak, sumSquares, windowFrames)
                filled = 0
                peak = 0
                sumSquares = 0.0
                onWindow(level)
            }
        }
    }
}
