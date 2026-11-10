// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Continuous-phase sine generator producing 16-bit mono PCM.
 *
 * @param frequencyHz tone frequency (the PoC uses 1 kHz)
 * @param sampleRate output sample rate in Hz
 * @param amplitude 0.0..1.0 of full scale (0.5 = about -6 dBFS)
 */
class SineTone(
    val frequencyHz: Double,
    val sampleRate: Int,
    val amplitude: Double = 0.5,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(frequencyHz > 0 && frequencyHz < sampleRate / 2.0) { "frequency must be below Nyquist" }
        require(amplitude in 0.0..1.0) { "amplitude must be within 0..1" }
    }

    private val step = 2.0 * PI * frequencyHz / sampleRate
    private var phase = 0.0

    /** Fill [buf] from [offset] for [length] samples, continuing the phase from the previous call. */
    fun fill(buf: ShortArray, offset: Int = 0, length: Int = buf.size - offset) {
        val peak = amplitude * Short.MAX_VALUE
        for (i in offset until offset + length) {
            buf[i] = (sin(phase) * peak).roundToInt().toShort()
            phase += step
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }
    }

    companion object {
        /** Number of samples in [ms] milliseconds at [sampleRate]. */
        fun samplesFor(ms: Int, sampleRate: Int): Int = sampleRate * ms / 1000
    }
}
