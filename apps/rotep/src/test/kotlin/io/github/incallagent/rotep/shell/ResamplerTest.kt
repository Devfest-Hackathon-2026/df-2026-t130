// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(hz: Double, rate: Int, seconds: Double, amplitude: Double = 10_000.0): ShortArray =
        ShortArray((rate * seconds).toInt()) { i -> (amplitude * sin(2.0 * PI * hz * i / rate)).roundToInt().toShort() }

    /** RMS of samples[from until to] in the same unit as the samples. */
    private fun rms(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double): Double = 20.0 * log10(ratio)

    /** Gain in dB of a [hz] sine through a [from] -> [to] resample, measured away from the start-up edge. */
    private fun gainDb(hz: Double, from: Int, to: Int): Double {
        val input = tone(hz, from, 1.0)
        val output = StreamResampler(from, to).process(input)
        // Skip the first 50 ms (the filter's start-up); compare steady-state RMS.
        val skipIn = from / 20
        val skipOut = to / 20
        return db(rms(output, skipOut, output.size) / rms(input, skipIn, input.size))
    }

    // --- The filter's response (the plan's own numbers, 48 kHz -> 16 kHz) --------------------------

    @Test
    fun passbandIsFlatToSevenKilohertz() {
        for (hz in listOf(100.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0)) {
            val gain = gainDb(hz, 48_000, 16_000)
            assertEquals("$hz Hz", 0.0, gain, 0.5)
        }
    }

    @Test
    fun everythingFromEightKilohertzUpIsBelowMinusSixtyDb() {
        // Sound above the new Nyquist frequency (8 kHz) must not fold back into the band.
        for (hz in listOf(8000.0, 8500.0, 9000.0, 10_000.0, 12_000.0, 15_000.0, 18_000.0, 21_000.0, 23_000.0)) {
            val gain = gainDb(hz, 48_000, 16_000)
            assertTrue("$hz Hz is only $gain dB down", gain < -60.0)
        }
    }

    @Test
    fun downsamplingKeepsTheFrequency() {
        // 1 kHz at 48 kHz is 48 samples per cycle; at 16 kHz it must be 16. Count zero crossings,
        // after the first 50 ms: the tone starts abruptly, and the filter rings briefly at its edge.
        val output = StreamResampler(48_000, 16_000).process(tone(1000.0, 48_000, 1.0))
        assertEquals(16_000.0, output.size.toDouble(), 2.0)
        val skip = 800
        var crossings = 0
        for (i in skip + 1 until output.size) if ((output[i - 1] < 0) != (output[i] < 0)) crossings++
        // 0.95 s of a 1 kHz tone: 1900 crossings.
        assertEquals(1900.0, crossings.toDouble(), 4.0)
    }

    // --- Other rates -------------------------------------------------------------------------------

    @Test
    fun everyDeviceRateComesOutAsSixteenKilohertz() {
        for (rate in listOf(48_000, 44_100, 32_000, 24_000)) {
            val output = StreamResampler(rate, 16_000).process(tone(1000.0, rate, 1.0))
            assertEquals("length from $rate Hz", 16_000.0, output.size.toDouble(), 3.0)
            assertEquals("1 kHz level from $rate Hz", 0.0, gainDb(1000.0, rate, 16_000), 0.5)
            assertTrue("above Nyquist from $rate Hz", gainDb(8500.0.coerceAtMost(rate / 2.0 - 100), rate, 16_000) < -50.0)
        }
    }

    @Test
    fun sixteenKilohertzPassesThroughUnchanged() {
        val input = tone(1000.0, 16_000, 0.1)
        assertArrayEquals(input, StreamResampler(16_000, 16_000).process(input))
    }

    @Test
    fun upsamplingTwentyFourToFortyEightKeepsTheFrequencyAndLevel() {
        val input = tone(1000.0, 24_000, 1.0)
        val output = StreamResampler(24_000, 48_000).process(input)
        assertEquals(48_000.0, output.size.toDouble(), 3.0)
        var crossings = 0
        for (i in 1 until output.size) if ((output[i - 1] < 0) != (output[i] < 0)) crossings++
        assertEquals(2000.0, crossings.toDouble(), 4.0)
        assertEquals(0.0, db(rms(output) / rms(input)), 0.3)
    }

    // --- Streaming: chunk by chunk gives the same as all at once ----------------------------------

    @Test
    fun chunkedOutputEqualsWholeOutput() {
        for (rate in listOf(48_000, 44_100, 32_000)) {
            val input = tone(1300.0, rate, 0.5) // 0.5 s: many 20 ms chunks
            val whole = StreamResampler(rate, 16_000).process(input)
            val chunked = ArrayList<Short>()
            val resampler = StreamResampler(rate, 16_000)
            val chunk = rate / 50
            var at = 0
            while (at < input.size) {
                val n = minOf(chunk, input.size - at)
                chunked += resampler.process(input.copyOfRange(at, at + n)).toList()
                at += n
            }
            assertEquals("length from $rate Hz", whole.size, chunked.size)
            for (i in whole.indices) assertEquals("sample $i from $rate Hz", whole[i].toInt(), chunked[i].toInt())
        }
    }

    @Test
    fun oddChunkSizesAlsoLineUp() {
        val input = tone(700.0, 48_000, 0.3)
        val whole = StreamResampler(48_000, 16_000).process(input)
        val resampler = StreamResampler(48_000, 16_000)
        val chunked = ArrayList<Short>()
        var at = 0
        var size = 7
        while (at < input.size) {
            val n = minOf(size, input.size - at)
            chunked += resampler.process(input.copyOfRange(at, at + n)).toList()
            at += n
            size = size * 3 % 997 + 1
        }
        assertEquals(whole.size, chunked.size)
        for (i in whole.indices) assertEquals("sample $i", whole[i].toInt(), chunked[i].toInt())
    }

    @Test
    fun singleSampleAndEmptyChunksAlsoLineUp() {
        // The extreme way to cut a stream: one sample at a time, with an empty chunk in between.
        // (Within one step of rounding: the position is carried over as a number, so the last
        // digits can differ from the all-at-once run.)
        for ((from, to) in listOf(48_000 to 16_000, 44_100 to 16_000, 32_000 to 16_000, 24_000 to 48_000, 16_000 to 24_000)) {
            val input = tone(500.0, from, 0.08)
            val whole = StreamResampler(from, to).process(input)
            val resampler = StreamResampler(from, to)
            val pieces = ArrayList<Short>()
            for (sample in input) {
                pieces += resampler.process(ShortArray(0)).toList()
                pieces += resampler.process(shortArrayOf(sample)).toList()
            }
            assertEquals("length $from -> $to", whole.size, pieces.size)
            for (i in whole.indices) {
                assertTrue("sample $i of $from -> $to", abs(whole[i] - pieces[i]) <= 1)
            }
        }
    }

    @Test
    fun anEmptyChunkGivesNothingAndChangesNothing() {
        for ((from, to) in listOf(48_000 to 16_000, 24_000 to 48_000)) {
            val resampler = StreamResampler(from, to)
            assertEquals(0, resampler.process(ShortArray(0)).size)
            val input = tone(300.0, from, 0.05)
            val withEmpties = ArrayList<Short>()
            withEmpties += resampler.process(input).toList()
            assertEquals(0, resampler.process(ShortArray(0)).size)
            val whole = StreamResampler(from, to).process(input)
            assertEquals(whole.toList(), withEmpties)
        }
    }

    // --- Twenty-millisecond chunks (what CallAudioPath.Listener.onDownlink is promised) ----------

    @Test
    fun resampledStreamCutsIntoExactTwentyMillisecondChunksWithNothingLost() {
        for (rate in listOf(48_000, 44_100, 32_000, 24_000)) {
            val input = tone(900.0, rate, 1.0)
            val resampler = StreamResampler(rate, 16_000)
            val chunker = FixedChunker(bytesFor20ms(16_000))
            val pieces = ArrayList<ByteArray>()
            var at = 0
            val frame = rate / 50
            while (at < input.size) {
                val n = minOf(frame, input.size - at)
                pieces += chunker.process(Pcm16.toBytes(resampler.process(input.copyOfRange(at, at + n))))
                at += n
            }
            assertTrue("from $rate Hz: ${pieces.size} chunks", pieces.size in 48..50)
            for (p in pieces) assertEquals(640, p.size)
            // What came out equals the whole signal resampled at once, cut into 640-byte pieces.
            val whole = Pcm16.toBytes(StreamResampler(rate, 16_000).process(input))
            val joined = pieces.fold(ByteArray(0)) { acc, p -> acc + p }
            assertArrayEquals("from $rate Hz", whole.copyOf(joined.size), joined)
        }
    }

    // --- FixedChunker --------------------------------------------------------------------------------

    @Test
    fun fixedChunkerCarriesTheRemainderInOrder() {
        val chunker = FixedChunker(4)
        assertEquals(0, chunker.process(byteArrayOf(1, 2, 3)).size)
        val out = chunker.process(byteArrayOf(4, 5, 6, 7, 8, 9))
        assertEquals(2, out.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out[0])
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), out[1])
        assertArrayEquals(byteArrayOf(9), chunker.drain())
        assertNull(chunker.drain())
        // Starts clean after a drain.
        assertArrayEquals(byteArrayOf(10, 11, 12, 13), chunker.process(byteArrayOf(10, 11, 12, 13))[0])
    }

    @Test
    fun fixedChunkerLosesNothingWhateverTheInputSizes() {
        val source = ByteArray(10_007) { (it * 31 % 251).toByte() }
        val chunker = FixedChunker(640)
        val out = ArrayList<ByteArray>()
        var at = 0
        var size = 1
        while (at < source.size) {
            val n = minOf(size, source.size - at)
            out += chunker.process(source.copyOfRange(at, at + n))
            at += n
            size = size * 7 % 2003 + 1
        }
        for (p in out) assertEquals(640, p.size)
        val tail = chunker.drain()
        val joined = out.fold(ByteArray(0)) { acc, p -> acc + p } + (tail ?: ByteArray(0))
        assertArrayEquals(source, joined)
    }

    // --- Pcm16 -----------------------------------------------------------------------------------------

    @Test
    fun pcm16RoundTripsAndIsLittleEndian() {
        val samples = shortArrayOf(0, 1, -1, 256, Short.MAX_VALUE, Short.MIN_VALUE, 12345)
        val bytes = Pcm16.toBytes(samples)
        assertEquals(14, bytes.size)
        assertEquals(1, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertEquals(0xFF, bytes[4].toInt() and 0xFF)
        assertEquals(0xFF, bytes[5].toInt() and 0xFF)
        assertArrayEquals(samples, Pcm16.toShorts(bytes))
    }

    @Test
    fun theFilterHasUnityGainAtDc() {
        val taps = LowPassFilter.design(7_500.0, 48_000.0, LowPassFilter.TAPS)
        assertEquals(1.0, taps.sum(), 1e-9)
        assertEquals(0.0, taps.indices.sumOf { abs(taps[it] - taps[taps.size - 1 - it]) }, 1e-12)
    }
}
