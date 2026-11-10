// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BridgeSidesTest {
    /** Waits (at most 10 s) until [condition] holds; false when it never did. */
    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(1)
        }
        return true
    }

    // --- firstThatStarts: nothing made is left behind ---------------------------------------------

    /** Stands in for an AudioRecord / AudioTrack: all that matters is whether it was let go. */
    private class Thing(val id: Int) {
        var released = 0
    }

    private class Opening(val failStartOf: Set<Int> = emptySet(), val releaseThrows: Boolean = false) {
        val log = ArrayList<String>()
        val made = ArrayList<Thing>()

        fun open(candidate: Int): Thing {
            log += "open $candidate"
            return Thing(candidate).also { made += it }
        }

        fun start(thing: Thing) {
            if (thing.id in failStartOf) throw IllegalStateException("start of ${thing.id} failed")
            log += "start ${thing.id}"
        }

        fun release(thing: Thing) {
            thing.released++
            log += "release ${thing.id}"
            if (releaseThrows) throw RuntimeException("release of ${thing.id} failed")
        }
    }

    @Test
    fun aThingThatDoesNotStartIsReleasedBeforeTheNextCandidateIsTried() {
        val o = Opening(failStartOf = setOf(1, 2))
        val got = firstThatStarts(listOf(1, 2, 3), "none", o::open, o::start, o::release)
        assertEquals(3, got.id)
        assertEquals(
            listOf("open 1", "release 1", "open 2", "release 2", "open 3", "start 3"),
            o.log,
        )
        assertEquals(listOf(1, 1, 0), o.made.map { it.released })
    }

    @Test
    fun aThingThatStartsIsNeverReleased() {
        val o = Opening()
        val got = firstThatStarts(listOf(7, 8), "none", o::open, o::start, o::release)
        assertEquals(7, got.id)
        assertEquals(listOf("open 7", "start 7"), o.log)
        assertEquals(0, got.released)
    }

    @Test
    fun whatWasNeverMadeIsNotReleased() {
        val o = Opening()
        val released = ArrayList<Int>()
        val got = firstThatStarts(
            listOf(1, 2, 3),
            "none",
            open = { c ->
                when (c) {
                    1 -> throw SecurityException("no permission")
                    2 -> null
                    else -> o.open(c)
                }
            },
            start = o::start,
            release = { released += it.id },
        )
        assertEquals(3, got.id)
        assertTrue(released.isEmpty())
    }

    @Test
    fun whenNothingWorksTheFirstFailureIsThrownWithTheOthersAttached() {
        val o = Opening(failStartOf = setOf(1, 2, 3))
        try {
            firstThatStarts(listOf(1, 2, 3), "none", o::open, o::start, o::release)
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("start of 1 failed", e.message)
            assertEquals(listOf("start of 2 failed", "start of 3 failed"), e.suppressed.map { it.message })
        }
        // Every thing that was made is let go, each exactly once.
        assertEquals(listOf(1, 1, 1), o.made.map { it.released })
    }

    @Test
    fun aReleaseThatFailsHidesNeitherTheStartFailureNorTheNextTry() {
        val o = Opening(failStartOf = setOf(1), releaseThrows = true)
        val got = firstThatStarts(listOf(1, 2), "none", o::open, o::start, o::release)
        assertEquals(2, got.id)
        assertEquals(1, o.made[0].released)

        val single = Opening(failStartOf = setOf(1), releaseThrows = true)
        try {
            firstThatStarts(listOf(1), "none", single::open, single::start, single::release)
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("start of 1 failed", e.message)
            assertEquals(listOf("release of 1 failed"), e.suppressed.map { it.message })
        }
    }

    @Test
    fun candidatesThatCannotBeUsedAtAllGiveTheNoneMessage() {
        try {
            firstThatStarts<Int, Thing>(listOf(1, 2), "候補がなかった", open = { null }, start = {}, release = {})
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("候補がなかった", e.message)
        }
        try {
            firstThatStarts<Int, Thing>(emptyList(), "empty", open = { null }, start = {}, release = {})
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("empty", e.message)
        }
    }

    @Test
    fun everyThingMadeIsEitherTheOneReturnedOrReleasedExactlyOnce() {
        // All 16 ways four candidates can fail to start.
        for (pattern in 0 until 16) {
            val failing = (0 until 4).filter { pattern and (1 shl it) != 0 }.toSet()
            val o = Opening(failStartOf = failing)
            val got = runCatching { firstThatStarts((0 until 4).toList(), "none", o::open, o::start, o::release) }.getOrNull()
            val expectedWinner = (0 until 4).firstOrNull { it !in failing }
            assertEquals("pattern $pattern", expectedWinner, got?.id)
            for (thing in o.made) {
                val expected = if (thing === got) 0 else 1
                assertEquals("pattern $pattern, thing ${thing.id}", expected, thing.released)
            }
            // Nothing after the winner was made.
            assertEquals("pattern $pattern", if (expectedWinner == null) 4 else expectedWinner + 1, o.made.size)
        }
    }

    // --- failureText: the kind of failure stays, numbers and conversation do not ----------------------

    @Test
    fun aFailureKeepsItsClassAndAScrubbedMessage() {
        val text = failureText(IllegalStateException("call to 090-1234-5678 failed"))
        assertTrue(text, text.startsWith("IllegalStateException: "))
        assertTrue(text, text.contains(ReportText.NUMBER_MASK))
        assertFalse(text, text.contains("090"))
        assertEquals("IllegalStateException", failureText(IllegalStateException()))
    }

    @Test
    fun aReflectiveWrapperIsLookedThrough() {
        assertEquals("SecurityException: denied", failureText(InvocationTargetException(SecurityException("denied"))))
    }

    @Test
    fun theOtherCandidatesFailuresAreShownAtMostTwo() {
        val first = IllegalStateException("24 kHz")
        first.addSuppressed(SecurityException("48 kHz"))
        assertEquals("IllegalStateException: 24 kHz（ほかの候補: SecurityException: 48 kHz）", failureText(first))

        val many = IllegalStateException("msg-a")
        many.addSuppressed(RuntimeException("msg-b"))
        many.addSuppressed(RuntimeException("msg-c"))
        many.addSuppressed(RuntimeException("msg-d"))
        val text = failureText(many)
        assertTrue(text, text.contains("msg-a") && text.contains("msg-b") && text.contains("msg-c") && !text.contains("msg-d"))
    }

    // --- BridgeSides: both sides, one report, inactive only when both let go ---------------------------

    /** Two fake sides on real threads; every step is logged in the order it happened. */
    private class Rig(
        downlinkRun: () -> BridgeEndReason = { BridgeEndReason.Stopped("down") },
        uplinkRun: () -> BridgeEndReason = { BridgeEndReason.Stopped("up") },
        downlinkRelease: () -> Unit = {},
        uplinkRelease: () -> Unit = {},
        report: (Int, String) -> Unit = { _, _ -> },
        threadStarter: ((String, () -> Unit) -> Unit)? = null,
    ) {
        val log = CopyOnWriteArrayList<String>()
        val reports = CopyOnWriteArrayList<Pair<Int, String>>()
        val threads = CopyOnWriteArrayList<Thread>()
        val stopRequests = AtomicInteger(0)

        val sides = BridgeSides(
            downlink = BridgeSides.Side(
                name = "受話",
                run = downlinkRun,
                requestStop = { stopRequests.incrementAndGet() },
                release = {
                    log += "release:down"
                    downlinkRelease()
                },
            ),
            uplink = BridgeSides.Side(
                name = "送話",
                run = uplinkRun,
                requestStop = { stopRequests.incrementAndGet() },
                release = {
                    log += "release:up"
                    uplinkRelease()
                },
            ),
            closePipes = { log += "close-pipes" },
            report = { code, detail ->
                log += "report:$code"
                reports += code to detail
                report(code, detail)
            },
            startThread = threadStarter ?: { name, body ->
                val t = Thread({ body() }, name)
                t.isDaemon = true
                threads += t
                t.start()
            },
            note = { log += "note" },
        )

        fun joinAll() {
            for (t in threads) {
                t.join(10_000)
                assertFalse("${t.name} is still running", t.isAlive)
            }
        }
    }

    @Test
    fun theBridgeStaysActiveUntilTheSlowerSideHasLetGo() {
        val uplinkMayEnd = CountDownLatch(1)
        val rig = Rig(
            uplinkRun = {
                assertTrue(uplinkMayEnd.await(10, TimeUnit.SECONDS))
                BridgeEndReason.Stopped("up")
            },
        )
        assertTrue("active from the start", rig.sides.isActive)
        rig.sides.start()

        // The downlink side ends first and has let go of its own record, closed the pipes and reported.
        rig.threads[0].join(10_000)
        assertFalse(rig.threads[0].isAlive)
        assertEquals(listOf(2 to "down"), rig.reports.toList())
        assertEquals(listOf("release:down", "close-pipes", "report:2"), rig.log.toList())
        // The uplink side still holds its track: the bridge is not over yet.
        assertTrue("the uplink side has not let go yet", rig.sides.isActive)

        uplinkMayEnd.countDown()
        rig.joinAll()
        assertFalse(rig.sides.isActive)
        assertEquals("one report only", 1, rig.reports.size)
        assertEquals(listOf("release:down", "close-pipes", "report:2", "release:up", "note"), rig.log.toList())
    }

    @Test
    fun aSideThatThrowsKeepsItsExceptionClassButNotNumbersAndEndsTheOtherSide() {
        val uplinkWasAskedToStop = CountDownLatch(1)
        lateinit var rig: Rig
        rig = Rig(
            downlinkRun = { throw IllegalStateException("record 090-1234-5678 broke") },
            uplinkRun = {
                // Ends only when asked to stop, like a side that waits for the other one; and only
                // after the report is out, so that the report is the downlink's whatever the timing.
                assertTrue(awaitUntil { rig.stopRequests.get() > 0 && rig.reports.isNotEmpty() })
                uplinkWasAskedToStop.countDown()
                BridgeEndReason.Stopped("up")
            },
        )
        rig.sides.start()
        rig.joinAll()
        assertTrue(uplinkWasAskedToStop.await(1, TimeUnit.SECONDS))

        assertEquals(1, rig.reports.size)
        val (code, detail) = rig.reports[0]
        assertEquals(AudioBridgeEvent.FAILED, code)
        assertTrue(detail, detail.contains("受話"))
        assertTrue(detail, detail.contains("IllegalStateException"))
        assertTrue(detail, detail.contains(ReportText.NUMBER_MASK))
        assertFalse(detail, detail.contains("090"))
        // Both audio objects were let go, and the bridge is over.
        assertTrue(rig.log.contains("release:down") && rig.log.contains("release:up"))
        assertFalse(rig.sides.isActive)
    }

    @Test
    fun theReasonsMapToTheirEventCodes() {
        val cases = listOf(
            BridgeEndReason.Stopped("why") to (AudioBridgeEvent.STOPPED to "why"),
            BridgeEndReason.Silenced to (AudioBridgeEvent.SILENCED to ""),
            BridgeEndReason.Failed("detail") to (AudioBridgeEvent.FAILED to "detail"),
        )
        for ((reason, expected) in cases) {
            lateinit var rig: Rig
            rig = Rig(
                downlinkRun = { reason },
                // The other side ends after the report is out, so the reported one is the downlink's.
                uplinkRun = {
                    assertTrue(awaitUntil { rig.reports.isNotEmpty() })
                    BridgeEndReason.Stopped("other")
                },
            )
            rig.sides.start()
            rig.joinAll()
            assertEquals(listOf(expected), rig.reports.toList())
        }
    }

    @Test
    fun aReleaseThatThrowsStopsNeitherTheReportNorTheCountdown() {
        val rig = Rig(
            downlinkRelease = { throw RuntimeException("release failed") },
            uplinkRelease = { throw IllegalStateException("release failed") },
        )
        rig.sides.start()
        rig.joinAll()
        assertEquals(1, rig.reports.size)
        assertFalse(rig.sides.isActive)
    }

    @Test
    fun aReportThatThrowsStillEndsTheBridgeAndIsNoted() {
        val rig = Rig(report = { _, _ -> throw RuntimeException("callback gone") })
        rig.sides.start()
        rig.joinAll()
        assertFalse(rig.sides.isActive)
        assertTrue(rig.log.toString(), rig.log.count { it == "note" } >= 1)
    }

    @Test
    fun exactlyOneReportEvenWhenBothSidesEndTogether() {
        repeat(300) { round ->
            val go = CountDownLatch(1)
            val rig = Rig(
                downlinkRun = {
                    go.await()
                    BridgeEndReason.Stopped("down")
                },
                uplinkRun = {
                    go.await()
                    BridgeEndReason.Failed("up")
                },
            )
            rig.sides.start()
            go.countDown()
            rig.joinAll()
            assertEquals("round $round", 1, rig.reports.size)
            assertEquals("round $round", 1, rig.log.count { it == "close-pipes" })
            assertEquals("round $round", 1, rig.log.count { it == "release:down" })
            assertEquals("round $round", 1, rig.log.count { it == "release:up" })
            assertFalse("round $round", rig.sides.isActive)
        }
    }

    /** [Rig] whose thread starter throws for the thread named [failing] (the others start normally). */
    private fun rigWhoseThreadCannotStart(failing: String, otherSideRun: () -> BridgeEndReason): Rig {
        lateinit var rig: Rig
        rig = Rig(
            downlinkRun = otherSideRun,
            uplinkRun = otherSideRun,
            threadStarter = { name, body ->
                if (name == failing) throw OutOfMemoryError("cannot create a thread")
                val t = Thread({ body() }, name)
                t.isDaemon = true
                rig.threads += t
                t.start()
            },
        )
        return rig
    }

    @Test
    fun aThreadThatCannotBeMadeEndsTheBridgeInsteadOfLeavingItHalfAlive() {
        for (failing in listOf("audio-bridge-uplink", "audio-bridge-downlink")) {
            lateinit var rig: Rig
            // The side that did start ends only when asked to stop, as a side blocked on a pipe does.
            // (And only once the failure is reported, so the report is the thread's whatever the timing.)
            rig = rigWhoseThreadCannotStart(failing) {
                assertTrue(awaitUntil { rig.stopRequests.get() > 0 && rig.reports.isNotEmpty() })
                BridgeEndReason.Stopped("stopped")
            }
            rig.sides.start()
            rig.joinAll()

            assertFalse("$failing: no side is left holding the audio", rig.sides.isActive)
            assertEquals("$failing: one report", 1, rig.reports.size)
            val (code, detail) = rig.reports[0]
            assertEquals(AudioBridgeEvent.FAILED, code)
            assertTrue(detail, detail.contains("スレッドを作れなかった") && detail.contains("OutOfMemoryError"))
            // Both audio objects were let go, each exactly once, though one side never ran.
            assertEquals(1, rig.log.count { it == "release:down" })
            assertEquals(1, rig.log.count { it == "release:up" })
        }
    }

    @Test
    fun aStopRequestReachesBothSides() {
        val rig = Rig()
        rig.sides.requestStop()
        assertEquals(2, rig.stopRequests.get())
        rig.sides.start()
        rig.joinAll()
        // And each side that ends asks both to stop (the other one must end with it).
        assertTrue(rig.stopRequests.get() >= 4)
    }

    @Test
    fun theSameFailureTwiceIsNotAttachedToItself() {
        // A failure that is thrown by two candidates (the same instance) is not attached to itself.
        val shared = IllegalStateException("same")
        try {
            firstThatStarts<Int, Thing>(listOf(1, 2), "none", open = { throw shared }, start = {}, release = {})
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertSame(shared, e)
            assertEquals(0, e.suppressed.size)
        }
    }
}
