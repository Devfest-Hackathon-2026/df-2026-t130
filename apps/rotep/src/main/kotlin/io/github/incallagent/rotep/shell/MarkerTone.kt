// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The marker tone of the uplink tests: three tones of different pitch, one second each. It is
 * easy to tell apart from speech and from line noise, also when the line compresses the sound.
 * Pure Kotlin, no Android classes, so it runs in unit tests.
 *
 * One cycle is the three tones ([PATTERN_MS]) followed by silence up to [CYCLE_MS]. A test plays one
 * cycle after another, so a 20 s test holds exactly two markers, starting at 0 s and 10 s. The far
 * end can say "heard" in the silence after each one.
 *
 * The level is the peak in dBFS (0 = full scale). A sine with that peak has an RMS 3 dB lower.
 * Each tone fades in and out over [FADE_MS] (raised cosine), so the joints do not click.
 */
object MarkerTone {
    /** The rate the tests play at (accepted by both injection methods: 8 to 48 kHz). */
    const val SAMPLE_RATE = 48_000

    /** The three pitches, in the order they are played. All are inside the narrow-band phone range. */
    val FREQUENCIES_HZ: List<Int> = listOf(880, 1320, 1760)

    const val TONE_MS = 1_000
    const val PATTERN_MS = 3_000
    const val CYCLE_MS = 10_000
    const val FADE_MS = 20
    const val DEFAULT_LEVEL_DBFS = -12

    /** Full scale of 16-bit PCM for a positive peak. */
    private const val FULL_SCALE = 32767.0

    /** Frames (mono samples) in [ms] at [sampleRate]. */
    fun frames(ms: Int, sampleRate: Int = SAMPLE_RATE): Int = (sampleRate.toLong() * ms / 1000).toInt()

    /** Peak amplitude relative to full scale (0.0 .. 1.0). Levels above 0 dBFS are taken as 0 dBFS. */
    fun amplitude(levelDbfs: Double): Double = 10.0.pow(minOf(levelDbfs, 0.0) / 20.0)

    /**
     * Gain of the fade at frame [i] of a tone that is [length] frames long: 0 at the first and the
     * last frame, rising along a raised cosine to 1 after [fadeFrames].
     */
    fun fadeGain(i: Int, length: Int, fadeFrames: Int): Double {
        if (fadeFrames <= 0) return 1.0
        val edge = minOf(i, length - 1 - i)
        if (edge <= 0) return 0.0
        if (edge >= fadeFrames) return 1.0
        return 0.5 * (1.0 - cos(PI * edge / fadeFrames))
    }

    /** The three tones one after another: [PATTERN_MS] of 16-bit PCM, mono, with the peak at [levelDbfs]. */
    fun pattern(levelDbfs: Double = DEFAULT_LEVEL_DBFS.toDouble(), sampleRate: Int = SAMPLE_RATE): ShortArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val toneFrames = frames(TONE_MS, sampleRate)
        val fadeFrames = frames(FADE_MS, sampleRate)
        val peak = amplitude(levelDbfs) * FULL_SCALE
        val out = ShortArray(toneFrames * FREQUENCIES_HZ.size)
        for ((n, hz) in FREQUENCIES_HZ.withIndex()) {
            val base = n * toneFrames
            val step = 2.0 * PI * hz / sampleRate
            for (i in 0 until toneFrames) {
                out[base + i] = (peak * fadeGain(i, toneFrames, fadeFrames) * sin(step * i)).roundToInt().toShort()
            }
        }
        return out
    }

    /** One cycle in frames: the tones and the silence after them. */
    fun cycleFrames(sampleRate: Int = SAMPLE_RATE): Int = frames(CYCLE_MS, sampleRate)

    /** How many markers a test of [playMs] holds in full. */
    fun markersIn(playMs: Long): Int = if (playMs < PATTERN_MS) 0 else ((playMs - PATTERN_MS) / CYCLE_MS + 1).toInt()

    /** Where each full marker of a test of [playMs] starts, in seconds from the start of the sound. */
    fun markerStartSeconds(playMs: Long): List<Int> = List(markersIn(playMs)) { it * CYCLE_MS / 1000 }
}

/**
 * Hands out the marker cycle again and again: the tones of [pattern], then silence up to
 * [cycleFrames]. Only the tones are kept in memory.
 */
class MarkerCycle(private val pattern: ShortArray, private val cycleFrames: Int) {
    init {
        require(cycleFrames > 0 && cycleFrames >= pattern.size) { "the cycle must hold the whole pattern" }
    }

    /** The next frame inside the current cycle. */
    var position: Int = 0
        private set

    /** Fills out[0 until count] with the next [count] frames. */
    fun read(out: ShortArray, count: Int) {
        require(count in 0..out.size) { "count must fit into out" }
        for (k in 0 until count) {
            out[k] = if (position < pattern.size) pattern[position] else 0
            position++
            if (position == cycleFrames) position = 0
        }
    }
}
