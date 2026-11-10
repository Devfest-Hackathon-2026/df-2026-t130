// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class BridgeRunTest {
    private val inCall = UplinkTestRules.MODE_IN_CALL
    private val normal = 0

    // --- Downlink -------------------------------------------------------------------------------------

    /** A source that plays a 1 kHz tone, [reads] times 20 ms, and then goes on with silence. */
    private class FakeSource(
        override val nativeRate: Int,
        val reads: Int,
    ) : DownlinkSource {
        var readCount = 0
        var zeroReadsFirst = 0
        var shortReads = false
        var errorAtRead = -1
        var silencedFromRead = Int.MAX_VALUE
        var onRead: (Int) -> Unit = {}
        private var position = 0L

        override fun read(buffer: ShortArray): Int {
            if (zeroReadsFirst > 0) {
                zeroReadsFirst--
                return 0
            }
            onRead(readCount)
            if (readCount == errorAtRead) return -3
            val n = if (shortReads) buffer.size / 2 else buffer.size
            for (i in 0 until n) {
                buffer[i] = (10_000.0 * sin(2.0 * PI * 1000.0 * (position + i) / nativeRate)).roundToInt().toShort()
            }
            position += n
            readCount++
            return n
        }

        override fun isSilenced(): Boolean = readCount >= silencedFromRead
    }

    private class Written(val chunks: List<ByteArray>, val tail: Int)

    private fun decode(out: ByteArrayOutputStream): Written {
        val input = ByteArrayInputStream(out.toByteArray())
        val chunks = ArrayList<ByteArray>()
        while (true) {
            val m = AudioPipe.readMessage(input) ?: break
            chunks += (m as AudioPipe.Message.Chunk).pcm
        }
        return Written(chunks, 0)
    }

    private fun rms(chunks: List<ByteArray>): Double {
        val s = Pcm16.toShorts(chunks.fold(ByteArray(0)) { a, c -> a + c })
        // Skip the filter's start-up.
        var sum = 0.0
        for (i in 800 until s.size) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (s.size - 800))
    }

    @Test
    fun downlinkTurnsNativeAudioIntoTwentyMillisecondChunksAtSixteenKilohertz() {
        for (rate in listOf(48_000, 44_100, 32_000)) {
            val source = FakeSource(rate, reads = 100)
            val out = ByteArrayOutputStream()
            val run = DownlinkBridgeRun(source, out) { if (source.readCount < 100) inCall else normal }
            val reason = run.run()
            assertTrue("from $rate Hz: $reason", reason is BridgeEndReason.Stopped && reason.why.contains("通話が終わった"))
            val written = decode(out)
            for (c in written.chunks) assertEquals(640, c.size)
            assertTrue("from $rate Hz: ${written.chunks.size} chunks", written.chunks.size in 98..100 + DownlinkBridgeRun.CHECK_PERIOD)
            // The 1 kHz tone arrives with its level (10000 peak = about 7071 RMS).
            assertEquals("level from $rate Hz", 7071.0, rms(written.chunks), 150.0)
        }
    }

    @Test
    fun noCallNoCapture() {
        val source = FakeSource(48_000, reads = 100)
        val out = ByteArrayOutputStream()
        for (mode in listOf<Int?>(normal, 3, null)) {
            val reason = DownlinkBridgeRun(source, out) { mode }.run()
            assertTrue("mode $mode: $reason", reason is BridgeEndReason.Stopped)
        }
        assertEquals(0, source.readCount)
        assertEquals(0, out.size())
        val unknown = DownlinkBridgeRun(source, out) { null }.run() as BridgeEndReason.Stopped
        assertTrue(unknown.why, unknown.why.contains("分からない状態"))
    }

    @Test
    fun theObserverSeesEveryReadIncludingEmptyOnes() {
        val source = FakeSource(48_000, 3)
        source.zeroReadsFirst = 2
        val seen = ArrayList<Int>()
        val run = DownlinkBridgeRun(source, ByteArrayOutputStream(), idleSleep = {}, observer = { _, n -> seen += n }) {
            if (source.readCount < 5) inCall else normal
        }
        run.run()
        assertEquals("two empty reads first", listOf(0, 0), seen.take(2))
        assertTrue(seen.drop(2).isNotEmpty() && seen.drop(2).all { it > 0 })
    }

    @Test
    fun aSilencedRecordingIsReportedAsSuchAndWritesStop() {
        val source = FakeSource(48_000, reads = 1000).apply { silencedFromRead = 10 }
        val out = ByteArrayOutputStream()
        val reason = DownlinkBridgeRun(source, out) { inCall }.run()
        assertEquals(BridgeEndReason.Silenced, reason)
        // Checked every CHECK_PERIOD loops, not on every one.
        assertTrue("${source.readCount} reads", source.readCount in 10..(10 + DownlinkBridgeRun.CHECK_PERIOD))
    }

    @Test
    fun aSilencedRecordingFromTheStartPlaysNothing() {
        val source = FakeSource(48_000, reads = 1000).apply { silencedFromRead = 0 }
        val out = ByteArrayOutputStream()
        assertEquals(BridgeEndReason.Silenced, DownlinkBridgeRun(source, out) { inCall }.run())
        assertEquals(0, source.readCount)
        assertEquals(0, out.size())
    }

    @Test
    fun aReadErrorEndsTheRunAsFailed() {
        val source = FakeSource(48_000, reads = 1000).apply { errorAtRead = 5 }
        val reason = DownlinkBridgeRun(source, ByteArrayOutputStream()) { inCall }.run()
        assertTrue("$reason", reason is BridgeEndReason.Failed && reason.detail.contains("-3"))
    }

    @Test
    fun theStopRequestEndsTheRunAtOnce() {
        val source = FakeSource(48_000, reads = 1000)
        val run = DownlinkBridgeRun(source, ByteArrayOutputStream()) { inCall }
        source.onRead = { if (it == 7) run.requestStop() }
        val reason = run.run()
        assertEquals(BridgeEndReason.Stopped(DownlinkBridgeRun.REASON_STOP), reason)
        assertEquals(8, source.readCount)
    }

    @Test
    fun aClosedPipeIsTheAppStoppingNotAFailure() {
        // The app closes its end of the pipe on every ordinary end of a call, so a failed write must
        // not be reported as a failure (the operator would see one at the end of every call).
        val source = FakeSource(48_000, reads = 1000)
        val closed = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
        }
        val reason = DownlinkBridgeRun(source, closed) { inCall }.run()
        assertTrue("$reason", reason is BridgeEndReason.Stopped && reason.why.contains("パイプが閉じられた") && reason.why.contains("IOException"))
    }

    @Test
    fun emptyAndShortReadsAreHandled() {
        val source = FakeSource(48_000, reads = 60).apply {
            zeroReadsFirst = 3
            shortReads = true
        }
        val out = ByteArrayOutputStream()
        DownlinkBridgeRun(source, out) { if (source.readCount < 60) inCall else normal }.run()
        val written = decode(out)
        for (c in written.chunks) assertEquals(640, c.size)
        // 60 to 70 half-reads of 10 ms each (the end of the call is noticed within CHECK_PERIOD
        // loops, and the three empty reads count as loops): 30 to 35 chunks of 20 ms.
        assertTrue("${written.chunks.size} chunks", written.chunks.size in 28..35)
    }

    @Test
    fun anEmptyReadWaitsInsteadOfSpinning() {
        val source = FakeSource(48_000, reads = 20).apply { zeroReadsFirst = 40 }
        val waits = ArrayList<Long>()
        val out = ByteArrayOutputStream()
        DownlinkBridgeRun(source, out, idleSleep = { waits += it }) { if (source.readCount < 20) inCall else normal }.run()
        assertEquals("one short wait for every empty read", List(40) { DownlinkBridgeRun.IDLE_SLEEP_MS }, waits)
        assertTrue(DownlinkBridgeRun.IDLE_SLEEP_MS in 1..20)
        assertTrue("and the audio still comes through", decode(out).chunks.isNotEmpty())
    }

    @Test
    fun aStopRequestEndsARunThatOnlyGetsEmptyReads() {
        val source = FakeSource(48_000, reads = 0).apply { zeroReadsFirst = Int.MAX_VALUE }
        lateinit var run: DownlinkBridgeRun
        var waits = 0
        run = DownlinkBridgeRun(source, ByteArrayOutputStream(), idleSleep = { if (++waits == 5) run.requestStop() }) { inCall }
        assertEquals(BridgeEndReason.Stopped(DownlinkBridgeRun.REASON_STOP), run.run())
        assertEquals(5, waits)
    }

    // --- Uplink ---------------------------------------------------------------------------------------

    private class FakeSink(override val playbackRate: Int) : UplinkSink {
        val events = ArrayList<String>()
        val written = ArrayList<Short>()
        var failAtWrite = -1
        private var writes = 0

        override fun write(pcm: ShortArray): Int {
            events += "w:${pcm.size}"
            written += pcm.toList()
            return if (writes++ == failAtWrite) -6 else pcm.size
        }

        override fun flush() {
            events += "flush"
        }
    }

    private fun agentChunk(samples: Int, seed: Int = 0): ByteArray =
        Pcm16.toBytes(ShortArray(samples) { ((it + seed) * 37 % 20_000 - 10_000).toShort() })

    @Test
    fun uplinkPlaysWhatTheAppSendsAndFlushesWhereTold() {
        val a = agentChunk(480, 0)
        val b = agentChunk(480, 1)
        val c = agentChunk(480, 2)
        val input = ByteArrayInputStream(
            ByteArrayOutputStream().also {
                AudioPipe.writeChunk(it, a)
                AudioPipe.writeChunk(it, b)
                AudioPipe.writeFlush(it)
                AudioPipe.writeChunk(it, c)
            }.toByteArray(),
        )
        val sink = FakeSink(24_000)
        val reason = UplinkBridgeRun(input, sink).run()
        assertTrue("$reason", reason is BridgeEndReason.Stopped && reason.why.contains("パイプが閉じられた"))
        assertEquals(listOf("w:480", "w:480", "flush", "w:480"), sink.events)
        assertArrayEquals(Pcm16.toShorts(a + b + c), sink.written.toShortArray())
    }

    @Test
    fun aDeviceThatNeedsAnotherRateGetsTheVoiceResampled() {
        val tone = ShortArray(24_000) { (10_000.0 * sin(2.0 * PI * 1000.0 * it / 24_000)).roundToInt().toShort() }
        val out = ByteArrayOutputStream()
        // 50 messages of 20 ms: the resampler must carry on from one to the next.
        for (i in 0 until 50) AudioPipe.writeChunk(out, Pcm16.toBytes(tone.copyOfRange(i * 480, (i + 1) * 480)))
        val sink = FakeSink(48_000)
        UplinkBridgeRun(ByteArrayInputStream(out.toByteArray()), sink).run()
        assertEquals(48_000.0, sink.written.size.toDouble(), 3.0)
        var crossings = 0
        for (i in 1 until sink.written.size) if ((sink.written[i - 1] < 0) != (sink.written[i] < 0)) crossings++
        assertEquals(2000.0, crossings.toDouble(), 5.0)
        var sum = 0.0
        for (s in sink.written) sum += s.toDouble() * s
        assertEquals(7071.0, sqrt(sum / sink.written.size), 150.0)
    }

    @Test
    fun aWriteErrorEndsTheRunAsFailedAndNothingMoreIsPlayed() {
        val out = ByteArrayOutputStream()
        for (i in 0 until 5) AudioPipe.writeChunk(out, agentChunk(480, i))
        val sink = FakeSink(24_000).apply { failAtWrite = 1 }
        val reason = UplinkBridgeRun(ByteArrayInputStream(out.toByteArray()), sink).run()
        assertTrue("$reason", reason is BridgeEndReason.Failed && reason.detail.contains("-6"))
        assertEquals(2, sink.events.size)
    }

    @Test
    fun aCorruptStreamEndsTheRunAsFailed() {
        val sink = FakeSink(24_000)
        val reason = UplinkBridgeRun(ByteArrayInputStream(byteArrayOf(0x7F, -1, -1, -1, 1, 2, 3)), sink).run()
        assertTrue("$reason", reason is BridgeEndReason.Failed && reason.detail.contains("IllegalArgumentException"))
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun aStopRequestedBeforehandPlaysNothing() {
        val out = ByteArrayOutputStream()
        AudioPipe.writeChunk(out, agentChunk(480))
        val sink = FakeSink(24_000)
        val run = UplinkBridgeRun(ByteArrayInputStream(out.toByteArray()), sink)
        run.requestStop()
        assertEquals(BridgeEndReason.Stopped(DownlinkBridgeRun.REASON_STOP), run.run())
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun anEmptyChunkIsNotWritten() {
        val out = ByteArrayOutputStream()
        AudioPipe.writeChunk(out, ByteArray(0))
        AudioPipe.writeChunk(out, agentChunk(480))
        val sink = FakeSink(24_000)
        UplinkBridgeRun(ByteArrayInputStream(out.toByteArray()), sink).run()
        assertEquals(listOf("w:480"), sink.events)
    }

    @Test
    fun theRecordCountsTheVoiceApartFromTheSilenceAndSeesTheFlush() {
        val out = ByteArrayOutputStream()
        AudioPipe.writeChunk(out, agentChunk(480))
        AudioPipe.writeChunk(out, ByteArray(960))
        AudioPipe.writeFlush(out)
        val sink = FakeSink(24_000)
        var t = 0L
        val lines = ArrayList<String>()
        val stats = UplinkStats(sink, { lines += it }, { t })
        val run = UplinkBridgeRun(ByteArrayInputStream(out.toByteArray()), sink, stats = stats, now = { t })
        assertEquals(BridgeEndReason.Stopped("パイプが閉じられた（止める指示の見込み）"), run.run())
        // Nothing went over a second, so the record of the run comes out at its end.
        val fields = lines.single().split(",")
        assertEquals("2", fields[1]) // two writes
        assertEquals("960", fields[2]) // 480 + 480 frames
        assertEquals("480", fields[3]) // only the first was voice
        assertEquals("1", fields[9]) // the flush
    }

    // --- The numbers both sides rely on ---------------------------------------------------------------

    @Test
    fun theSharedNumbers() {
        assertEquals(640, bytesFor20ms(DOWNLINK_RATE_HZ))
        assertEquals(960, bytesFor20ms(AGENT_RATE_HZ))
        assertEquals(16_000, DOWNLINK_RATE_HZ)
        assertEquals(24_000, AGENT_RATE_HZ)
        assertTrue(AudioUplinkMode.isValid(0) && AudioUplinkMode.isValid(2))
        assertTrue(!AudioUplinkMode.isValid(-1) && !AudioUplinkMode.isValid(3))
        assertEquals(setOf(1, 2, 3, 4), setOf(AudioBridgeEvent.STARTED, AudioBridgeEvent.STOPPED, AudioBridgeEvent.SILENCED, AudioBridgeEvent.FAILED))
    }
}
