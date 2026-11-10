// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 16-bit little-endian mono PCM: the wire format of both audio pipes of 1b (see [AudioPipe]). */
object Pcm16 {
    fun toShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buf.getShort(it * 2) }
    }

    fun toBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }
}

/**
 * A low-pass FIR filter (windowed-sinc), the anti-aliasing step before downsampling ("高い音を
 * 落として" in the plan): without it, sound above the new Nyquist frequency folds back into the
 * audible band instead of disappearing. Pure Kotlin.
 */
internal object LowPassFilter {
    /**
     * [taps] values (must be odd; the filter is symmetric, so it delays every frequency equally),
     * a sinc at [cutoffHz] relative to [sampleRate] shaped by a Kaiser window. [KAISER_BETA] 7 puts
     * the stopband about 70 dB down, and [TAPS] makes the transition from "flat" to "down" about
     * 1 kHz wide at 48 kHz (narrower, in Hz, at the lower rates).
     */
    fun design(cutoffHz: Double, sampleRate: Double, taps: Int): DoubleArray {
        require(taps % 2 == 1) { "taps must be odd: $taps" }
        require(cutoffHz > 0 && cutoffHz < sampleRate / 2.0) { "cutoffHz must be inside the Nyquist range" }
        val fc = cutoffHz / sampleRate
        val m = taps - 1
        val edge = besselI0(KAISER_BETA)
        val h = DoubleArray(taps)
        for (i in 0 until taps) {
            val k = i - m / 2.0
            val sinc = if (k == 0.0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val r = 2.0 * i / m - 1.0
            val window = besselI0(KAISER_BETA * sqrt(maxOf(0.0, 1.0 - r * r))) / edge
            h[i] = sinc * window
        }
        val dcGain = h.sum()
        for (i in h.indices) h[i] = h[i] / dcGain
        return h
    }

    /** Modified Bessel function of the first kind, order 0 (the Kaiser window's shape). */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (true) {
            val half = x / (2.0 * k)
            term *= half * half
            sum += term
            if (term < 1e-12 * sum) return sum
            k++
        }
    }

    const val KAISER_BETA = 7.0

    /**
     * Checked on a PC against the plan's numbers (flat to 7 kHz within 0.5 dB, everything from
     * 8 kHz up below -60 dB) for every device rate this project tries: see ResamplerTest.
     */
    const val TAPS = 221
}

/**
 * Resamples a stream of mono 16-bit PCM from [fromRate] to [toRate], continuously across many
 * calls to [process] (the output picks up exactly where the previous call left off: no gap, no
 * repeated or dropped sample at the boundary). Downsampling applies [LowPassFilter] first;
 * upsampling and equal rates do not need it. Pure Kotlin, so this is the one place that is both
 * used on the device and checked on a PC.
 */
class StreamResampler(private val fromRate: Int, private val toRate: Int) {
    private val filter: DoubleArray? = if (toRate < fromRate) {
        // Passband edge at 7/8 of the new Nyquist, stopband at the new Nyquist (for 16 kHz this is
        // the plan's own numbers: passband flat to 7 kHz, stopband from 8 kHz). The filter's nominal
        // cutoff sits between the two, where a windowed-sinc filter is at about half power.
        val passbandEdge = toRate / 2.0 * 0.875
        val stopbandEdge = toRate / 2.0
        LowPassFilter.design((passbandEdge + stopbandEdge) / 2.0, fromRate.toDouble(), LowPassFilter.TAPS)
    } else {
        null
    }
    private val historySize = (filter?.size ?: 1) - 1
    private var history = DoubleArray(historySize)

    // The (optionally filtered) samples, still at fromRate, not yet consumed by resampling.
    private var pending = DoubleArray(0)

    // Fractional index into `pending` of the next output sample.
    private var phase = 0.0

    /** Resamples one chunk. Call with consecutive chunks of the same stream for continuity. */
    fun process(input: ShortArray): ShortArray {
        if (fromRate == toRate) return input.copyOf()
        val stage = filter?.let { filtered(it, input) } ?: DoubleArray(input.size) { input[it].toDouble() }
        val combined = DoubleArray(pending.size + stage.size)
        pending.copyInto(combined)
        stage.copyInto(combined, pending.size)

        val ratio = fromRate.toDouble() / toRate
        // One output sample for every `ratio` of the way from `phase` to the last pair of input
        // samples, so this many at most (two to spare for rounding at the edge). A plain array, not
        // a list: a list of Short would box every sample, fifty times a second.
        val out = ShortArray((((combined.size - 1 - phase) / ratio).toInt() + 2).coerceAtLeast(0))
        var count = 0
        var pos = phase
        while (floor(pos).toInt() + 1 < combined.size) {
            val base = floor(pos).toInt()
            val frac = pos - base
            val sample = combined[base] + (combined[base + 1] - combined[base]) * frac
            out[count++] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += ratio
        }
        val drop = floor(pos).toInt().coerceAtMost(combined.size)
        pending = combined.copyOfRange(drop, combined.size)
        phase = pos - drop
        return if (count == out.size) out else out.copyOf(count)
    }

    private fun filtered(taps: DoubleArray, input: ShortArray): DoubleArray {
        val extended = DoubleArray(history.size + input.size)
        history.copyInto(extended)
        for (i in input.indices) extended[history.size + i] = input[i].toDouble()
        val out = DoubleArray(input.size)
        val m = taps.size
        for (i in input.indices) {
            val base = history.size + i
            var acc = 0.0
            for (k in 0 until m) acc += taps[k] * extended[base - k]
            out[i] = acc
        }
        if (history.size > 0) history = extended.copyOfRange(extended.size - history.size, extended.size)
        return out
    }
}

/**
 * Carves a stream of byte arrays of any size into pieces of exactly [chunkBytes], carrying any
 * remainder to the next call (and keeping the original order). Used so audio handed to
 * [CallAudioPath.Listener.onDownlink][io.github.incallagent.rotep.call.CallAudioPath.Listener] is
 * always exactly 20 ms, no matter how [StreamResampler] happened to split it. Pure Kotlin.
 */
class FixedChunker(private val chunkBytes: Int) {
    init {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
    }

    private var carry = ByteArray(0)

    /** Zero or more pieces of exactly [chunkBytes], in order. */
    fun process(input: ByteArray): List<ByteArray> {
        val combined = ByteArray(carry.size + input.size)
        carry.copyInto(combined)
        input.copyInto(combined, carry.size)
        val out = ArrayList<ByteArray>(combined.size / chunkBytes)
        var offset = 0
        while (combined.size - offset >= chunkBytes) {
            out += combined.copyOfRange(offset, offset + chunkBytes)
            offset += chunkBytes
        }
        carry = combined.copyOfRange(offset, combined.size)
        return out
    }

    /** The leftover that has not reached [chunkBytes] yet (e.g. the last, shorter slice of an
     * utterance): returned once and cleared, or null when there is none. */
    fun drain(): ByteArray? {
        if (carry.isEmpty()) return null
        val last = carry
        carry = ByteArray(0)
        return last
    }
}
