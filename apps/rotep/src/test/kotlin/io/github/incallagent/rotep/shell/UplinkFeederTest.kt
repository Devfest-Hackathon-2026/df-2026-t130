// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

class UplinkFeederTest {
    /** A clock that moves only when the feeder sleeps (or the test advances it). */
    private class Clock(origin: Long = 1_000_000L) {
        var t = origin
        val start = t
        val elapsed: Long get() = t - start
    }

    private fun feeder(clock: Clock) = UplinkFeeder(now = { clock.t }, sleep = { clock.t += it })

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) * 7 % 251).toByte() }

    /** Everything [next] has right now (nothing is waited for). */
    private fun UplinkFeeder.takeAll(): List<AudioPipe.Message> {
        val out = ArrayList<AudioPipe.Message>()
        while (true) out += next(0) ?: break
        return out
    }

    private fun List<AudioPipe.Message>.pcm(): ByteArray =
        filterIsInstance<AudioPipe.Message.Chunk>().fold(ByteArray(0)) { a, c -> a + c.pcm }

    private fun List<AudioPipe.Message>.sizes(): List<Int> =
        map { if (it is AudioPipe.Message.Chunk) it.pcm.size else -1 }

    // --- 20 ms pieces ----------------------------------------------------------------------------------

    @Test
    fun theVoiceIsCutIntoTwentyMillisecondPiecesAndNothingIsLost() {
        val clock = Clock()
        val feeder = feeder(clock)
        val voice = bytes(2400) // 50 ms
        feeder.enqueue(voice)
        val out = feeder.takeAll()
        // Two whole pieces, then the shorter end of the voice (nothing else is waiting to join it).
        assertEquals(listOf(960, 960, 480), out.sizes())
        assertArrayEquals(voice, out.pcm())
    }

    @Test
    fun theRestOfOneItemJoinsTheNextWhenMoreIsWaiting() {
        val clock = Clock()
        val feeder = feeder(clock)
        val a = bytes(500, 1)
        val b = bytes(700, 2)
        feeder.enqueue(a)
        feeder.enqueue(b)
        val out = feeder.takeAll()
        assertEquals(listOf(960, 240), out.sizes())
        assertArrayEquals(a + b, out.pcm())
    }

    @Test
    fun anOddByteIsHalfASampleAndIsDropped() {
        val feeder = feeder(Clock())
        feeder.enqueue(bytes(961))
        assertEquals(960, feeder.takeAll().pcm().size)
        // Half a sample alone, or nothing, is not worth sending and does not count as playing.
        val other = feeder(Clock())
        other.enqueue(ByteArray(1))
        other.enqueue(ByteArray(0))
        assertTrue(other.takeAll().isEmpty())
        assertFalse(other.isPlaying())
    }

    // --- Pacing ------------------------------------------------------------------------------------------

    @Test
    fun theVoiceIsHandedOutAtThePaceItPlays() {
        val clock = Clock()
        val feeder = feeder(clock)
        feeder.enqueue(bytes(48_000)) // 1 s
        // The first 120 ms of lead goes out at once (7 pieces: 0 to 120 ms ahead)...
        repeat(7) { assertTrue((feeder.next(0) as AudioPipe.Message.Chunk).pcm.size == 960) }
        assertEquals(0, clock.elapsed)
        // ...the rest only as time passes, so the pipe never holds more than the lead.
        var pieces = 7
        while (feeder.next(0) != null) pieces++
        assertEquals(50, pieces)
        assertTrue("took ${clock.elapsed} ms", clock.elapsed in 850..880)
    }

    @Test
    fun theFractionsOfShortPiecesAddUp() {
        // 100 bytes are 2.08 ms: 48 of them are 100 ms, not 96
        val clock = Clock()
        val f = UplinkFeeder(leadMs = 99, now = { clock.t }, sleep = { clock.t += it })
        repeat(48) {
            f.enqueue(bytes(100))
            assertTrue(f.next(0) is AudioPipe.Message.Chunk)
        }
        val before = clock.t
        f.enqueue(bytes(100))
        f.next(0)
        assertTrue("100 ms are ahead by now, more than the lead", clock.t > before)
    }

    @Test
    fun anIdleGapDoesNotBuildUpCredit() {
        val clock = Clock()
        val feeder = feeder(clock)
        feeder.enqueue(bytes(4800)) // 100 ms
        feeder.takeAll()
        clock.t += 10_000 // nothing to say for 10 s
        feeder.enqueue(bytes(48_000)) // then a whole second
        val before = clock.elapsed
        repeat(7) { feeder.next(0) }
        assertEquals("the first lead goes out at once", before, clock.elapsed)
        feeder.next(0)
        assertTrue("then it waits again", clock.elapsed > before)
    }

    // --- Flush ---------------------------------------------------------------------------------------------

    @Test
    fun aFlushDropsWhatWaitsAndTheHelperIsToldOnceBeforeAnythingNew() {
        val clock = Clock()
        val feeder = feeder(clock)
        feeder.enqueue(bytes(48_000))
        repeat(3) { feeder.next(0) }
        feeder.flush()
        feeder.flush() // twice: one marker is enough
        assertEquals(AudioPipe.Message.Flush, feeder.next(0))
        assertNull("the old voice is not sent late", feeder.next(0))
        // New voice after the cut goes out at once: the pacing starts again.
        val elapsedBefore = clock.elapsed
        feeder.enqueue(bytes(6720, 5)) // 140 ms
        val out = feeder.takeAll()
        assertEquals(7, out.size)
        assertArrayEquals(bytes(6720, 5), out.pcm())
        assertEquals(elapsedBefore, clock.elapsed)
    }

    @Test
    fun aFlushBeforeAnythingPlayedStillSendsOneMarker() {
        val feeder = feeder(Clock())
        feeder.flush()
        assertEquals(listOf(-1), feeder.takeAll().sizes())
        assertTrue(feeder.takeAll().isEmpty())
    }

    @Test
    fun aFlushDuringThePacingWaitCutsTheVoiceAtOnce() {
        val clock = Clock()
        lateinit var feeder: UplinkFeeder
        var slept = 0
        feeder = UplinkFeeder(now = { clock.t }, sleep = {
            clock.t += it
            if (++slept == 2) feeder.flush() // the other thread interrupts while the sender waits
        })
        feeder.enqueue(bytes(48_000))
        repeat(7) { feeder.next(0) }
        // The 8th piece has to wait; the flush comes during the wait: the answer is the marker, not the piece.
        assertEquals(AudioPipe.Message.Flush, feeder.next(0))
        assertNull(feeder.next(0))
    }

    // --- isPlaying ---------------------------------------------------------------------------------------

    @Test
    fun isPlayingWhileAnythingWaitsAndForTheGraceAfterTheLastPiece() {
        val clock = Clock()
        val feeder = feeder(clock)
        assertFalse(feeder.isPlaying())
        feeder.enqueue(bytes(4800))
        assertTrue("queued", feeder.isPlaying())
        repeat(4) {
            assertTrue(feeder.isPlaying())
            feeder.next(0)
        }
        // The fifth and last piece was handed over just now.
        feeder.next(0)
        assertTrue(feeder.isPlaying())
        clock.t += UplinkFeeder.GRACE_MS - 1
        assertTrue("still inside the grace", feeder.isPlaying())
        clock.t += 1
        assertFalse("after the grace", feeder.isPlaying())
    }

    @Test
    fun aFlushEndsIsPlayingAtOnce() {
        val clock = Clock()
        val feeder = feeder(clock)
        feeder.enqueue(bytes(4800))
        feeder.takeAll()
        assertTrue(feeder.isPlaying())
        feeder.flush()
        assertFalse(feeder.isPlaying())
    }

    @Test
    fun theGraceCoversTheLeadAndTheHelpersBuffer() {
        assertTrue(UplinkFeeder.GRACE_MS >= UplinkFeeder.LEAD_MS + UPLINK_BUFFER_MS)
    }

    // --- PlaybackTracker ----------------------------------------------------------------------------------

    @Test
    fun nothingPlayedMeansNotPlayingWhateverTheClockSays() {
        var t = 5L // a clock that starts small must not look like "just played"
        val tracker = PlaybackTracker(300) { t }
        assertFalse(tracker.isPlaying())
        t = 100
        assertFalse(tracker.isPlaying())
    }

    @Test
    fun theGraceStartsOnlyWhenSomethingWasReallyHandedOver() {
        var t = 1_000L
        val tracker = PlaybackTracker(300) { t }
        tracker.onQueued()
        tracker.onDone(handedOver = false)
        assertFalse(tracker.isPlaying())
        tracker.onQueued()
        tracker.onDone(handedOver = true)
        assertTrue(tracker.isPlaying())
        t += 299
        assertTrue(tracker.isPlaying())
        t += 1
        assertFalse(tracker.isPlaying())
    }

    @Test
    fun moreDoneThanQueuedNeverGoesNegative() {
        val tracker = PlaybackTracker(300) { 1_000L }
        tracker.onDone(handedOver = false)
        tracker.onDone(handedOver = false)
        tracker.onQueued()
        assertTrue(tracker.isPlaying())
    }

    // --- Counting from several threads -----------------------------------------------------------------

    @Test
    fun countsUpFromTwoThreadsAreNotLost() {
        val tracker = PlaybackTracker(300) { 1_000L }
        val each = 200_000
        val go = CountDownLatch(1)
        val threads = List(2) {
            Thread {
                go.await()
                repeat(each) { tracker.onQueued() }
            }
        }
        threads.forEach { it.start() }
        go.countDown()
        threads.forEach { it.join(20_000) }
        // Exactly 2 * each were counted: one fewer done and it is still playing, one more and it is not.
        repeat(2 * each - 1) { tracker.onDone(handedOver = false) }
        assertTrue("one item must still be counted", tracker.isPlaying())
        tracker.onDone(handedOver = false)
        assertFalse("and now none", tracker.isPlaying())
    }

    @Test
    fun countsUpAndDownAtTheSameTimeDoNotDrift() {
        val tracker = PlaybackTracker(300) { 1_000L }
        val n = 200_000
        repeat(n) { tracker.onQueued() } // enough to count down from, so no count down is dropped at zero
        val go = CountDownLatch(1)
        val up = Thread {
            go.await()
            repeat(n) { tracker.onQueued() }
        }
        val down = Thread {
            go.await()
            repeat(n) { tracker.onDone(handedOver = false) }
        }
        up.start()
        down.start()
        go.countDown()
        up.join(20_000)
        down.join(20_000)
        // n + n - n = n are left.
        repeat(n - 1) { tracker.onDone(handedOver = false) }
        assertTrue("one item must still be counted", tracker.isPlaying())
        tracker.onDone(handedOver = false)
        assertFalse("and now none", tracker.isPlaying())
    }

    @Test
    fun theEnqueuingThreadAndTheSenderRunningTogetherLeaveNothingCounted() {
        // The real shape: one thread enqueues, another takes pieces with next(). When all of it has
        // been handed out, nothing may be left counted: a count that stays above zero would keep
        // isPlaying() true for good (and a count that is too low would end it mid-voice).
        val clock = AtomicLong(1_000_000L)
        val feeder = UplinkFeeder(now = { clock.get() }, sleep = { clock.addAndGet(it) })
        val items = 20_000
        val itemBytes = 10
        val total = items * itemBytes
        val producer = Thread { repeat(items) { feeder.enqueue(bytes(itemBytes, it)) } }
        producer.start()
        var received = 0
        val deadline = System.nanoTime() + 30_000_000_000L
        while (received < total) {
            val message = feeder.next(20)
            if (message is AudioPipe.Message.Chunk) received += message.pcm.size
            assertTrue("timed out at $received of $total bytes", System.nanoTime() < deadline)
        }
        producer.join(20_000)
        assertEquals(total, received)
        clock.addAndGet(UplinkFeeder.GRACE_MS)
        assertFalse("nothing is left counted after the grace", feeder.isPlaying())
    }

    // --- Keep-alive --------------------------------------------------------------------------------------

    private fun keepAliveFeeder(clock: Clock) =
        UplinkFeeder(now = { clock.t }, sleep = { clock.t += it }, keepOutputAlive = true)

    private fun List<AudioPipe.Message>.allSilent(): Boolean =
        all { it is AudioPipe.Message.Chunk && it.pcm.all { b -> b == 0.toByte() } }

    @Test
    fun silenceKeepsTheOutputFedAtThePaceItPlays() {
        val clock = Clock()
        val feeder = keepAliveFeeder(clock)
        val first = feeder.takeAll()
        // The silence lead goes out at once: five 20 ms pieces, 0 to 80 ms ahead.
        assertEquals(5, first.size)
        assertTrue(first.allSilent())
        assertEquals(960, (first[0] as AudioPipe.Message.Chunk).pcm.size)
        assertEquals(0, clock.elapsed)
        // Then one piece per 20 ms of the clock, and nothing in between.
        clock.t += 19
        assertNull(feeder.next(0))
        clock.t += 1
        assertTrue(feeder.next(0) is AudioPipe.Message.Chunk)
        assertNull(feeder.next(0))
        // A second of silence is 50 pieces.
        var pieces = 0
        repeat(1_000) {
            clock.t += 1
            while (feeder.next(0) != null) pieces++
        }
        assertEquals(50, pieces)
    }

    @Test
    fun voiceGoesOutBeforeTheSilenceAndTheSilenceComesBackAfterIt() {
        val clock = Clock()
        val feeder = keepAliveFeeder(clock)
        feeder.takeAll() // the first silence
        val voice = bytes(1_920) // two pieces
        feeder.enqueue(voice)
        val out = feeder.takeAll()
        assertArrayEquals(voice, out.pcm())
        assertEquals(listOf(960, 960), out.sizes())
        assertFalse("voice is not silence", out.allSilent())
        // The voice went out; once the clock has moved on the silence fills in again.
        clock.t += 1_000
        val later = feeder.takeAll()
        assertTrue(later.isNotEmpty())
        assertTrue(later.allSilent())
    }

    @Test
    fun aFlushCutsTheVoiceAndTheSilenceStartsAgainFromTheMarker() {
        val clock = Clock()
        val feeder = keepAliveFeeder(clock)
        feeder.enqueue(bytes(48_000))
        repeat(3) { feeder.next(0) }
        feeder.flush()
        assertEquals(AudioPipe.Message.Flush, feeder.next(0))
        val after = feeder.takeAll()
        assertEquals("the same start as the first silence", 5, after.size)
        assertTrue(after.allSilent())
    }

    @Test
    fun aFlushInTheMiddleOfTheSilenceIsCarriedOnce() {
        val feeder = keepAliveFeeder(Clock())
        feeder.takeAll()
        feeder.flush()
        val out = feeder.takeAll()
        assertEquals(AudioPipe.Message.Flush, out[0])
        assertTrue(out.drop(1).allSilent())
    }

    @Test
    fun silenceIsNotCountedAsPlaying() {
        val clock = Clock()
        val feeder = keepAliveFeeder(clock)
        repeat(200) {
            feeder.takeAll()
            clock.t += 20
            assertFalse("silence is not voice", feeder.isPlaying())
        }
    }

    @Test
    fun afterTheVoiceThePlayingEndsWithTheGraceEvenWithSilenceGoingOn() {
        val clock = Clock()
        val feeder = keepAliveFeeder(clock)
        feeder.takeAll()
        feeder.enqueue(bytes(960))
        assertTrue("queued", feeder.isPlaying())
        feeder.takeAll() // the voice piece and whatever silence is due
        clock.t += UplinkFeeder.GRACE_MS - 1
        feeder.takeAll()
        assertTrue("still inside the grace", feeder.isPlaying())
        clock.t += 1
        feeder.takeAll()
        assertFalse("the silence does not keep it going", feeder.isPlaying())
    }

    @Test
    fun nextDoesNotWaitWhileSilenceIsDue() {
        val feeder = keepAliveFeeder(Clock())
        repeat(5) { assertTrue(feeder.next(50) is AudioPipe.Message.Chunk) }
    }

    @Test
    fun theTelephonyFeederHandsTheVoiceAsFarAheadAsTheTrackHolds() {
        val clock = Clock()
        val feeder = UplinkFeeder.forTelephonyOutput(now = { clock.t }, sleep = { clock.t += it })
        val voice = bytes(96_000) // 2 s
        feeder.enqueue(voice)
        // 16 pieces, 0 to 300 ms ahead (the whole of UPLINK_BUFFER_MS), go out at once...
        repeat(16) { assertTrue(feeder.next(0) is AudioPipe.Message.Chunk) }
        assertEquals(0, clock.elapsed)
        // ...the next one waits for the clock.
        feeder.next(0)
        assertTrue("then it waits", clock.elapsed > 0)
    }

    @Test
    fun theTelephonyFeederKeepsTheOutputFed() {
        val clock = Clock()
        val feeder = UplinkFeeder.forTelephonyOutput(now = { clock.t }, sleep = { clock.t += it })
        val out = feeder.takeAll()
        assertEquals(5, out.size)
        assertTrue(out.allSilent())
    }

    @Test
    fun theOtherFeedersStayQuietWhenNothingIsQueued() {
        // Only the telephony feeder keeps the output fed: the default one has nothing to say.
        val feeder = feeder(Clock())
        assertTrue(feeder.takeAll().isEmpty())
    }

    // --- The clock --------------------------------------------------------------------------------------

    @Test
    fun theDefaultClockOnlyGoesForward() {
        var last = monotonicMillis()
        repeat(100_000) {
            val t = monotonicMillis()
            assertTrue("went back from $last to $t", t >= last)
            last = t
        }
        val before = monotonicMillis()
        Thread.sleep(30)
        val moved = monotonicMillis() - before
        assertTrue("moved $moved ms in 30 ms of sleep", moved in 20..5_000)
    }

    @Test
    fun theFirstLeadGoesOutAtOnceWhereverTheClocksZeroIs() {
        // A monotonic clock starts anywhere (even below zero), and the first pieces must not wait for it.
        for (origin in listOf(0L, -1_000_000L, 5_000_000_000L)) {
            val clock = Clock(origin)
            val feeder = feeder(clock)
            feeder.enqueue(bytes(960 * 7)) // 7 pieces: 0 to 120 ms ahead
            repeat(7) { assertTrue("origin $origin", feeder.next(0) is AudioPipe.Message.Chunk) }
            assertEquals("origin $origin", 0, clock.elapsed)
        }
    }

    @Test
    fun aFeederWithTheDefaultClockNeedsNoSleepForItsFirstLead() {
        val feeder = UplinkFeeder(sleep = { fail("slept $it ms within the lead") })
        feeder.enqueue(bytes(960 * 7))
        repeat(7) { assertNotNull(feeder.next(0)) }
        assertTrue(feeder.isPlaying())
    }
}
