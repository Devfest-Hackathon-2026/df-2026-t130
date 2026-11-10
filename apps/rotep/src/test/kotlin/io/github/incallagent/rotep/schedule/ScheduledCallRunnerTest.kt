// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.rotep.call.FakeScheduler
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ScheduledCallRunnerTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val fireAt: Instant = ScheduleRules.instantOf(today.atTime(11, 0))
    private val call = ScheduledCall(PracticePolicy.approved(today, fireAt), fireAt)

    private class Host(private val call: ScheduledCall) : ScheduledCallRunner.Host {
        var now: Instant = call.fireAt
        var reads = ArrayDeque<ScheduledCallStore.Read>()
        var scheduled = true
        var helper = true
        var network = true
        var problem: String? = null
        var keyReads = ArrayDeque<ScheduledCallRunner.KeyRead>()
        val dialed = ArrayList<ScheduledCall>()
        val keys = ArrayList<String>()
        val notPlaced = ArrayList<String>()
        var stopped = 0
        var checks = 0
        var startsHelper = false
        var prepared = 0
        var released = 0
        var setupProblem: String? = null
        var readFailure: Throwable? = null
        var networkFailure: Throwable? = null
        var releaseFailure: Throwable? = null

        override fun prepareHelper(): Boolean {
            prepared++
            return startsHelper
        }

        override fun helperProblem() = setupProblem

        override fun releaseHelper() {
            released++
            releaseFailure?.let { throw it }
        }

        override fun now() = now

        override fun read(): ScheduledCallStore.Read {
            readFailure?.let { throw it }
            return reads.removeFirstOrNull() ?: ScheduledCallStore.Read.Found(call)
        }

        override fun stillScheduled() = scheduled

        override fun helperReady() = helper

        override fun networkReady(): Boolean {
            networkFailure?.let { throw it }
            return network
        }

        override fun problem(call: ScheduledCall): String? {
            checks++
            return problem
        }

        override fun readKey() = keyReads.removeFirstOrNull() ?: ScheduledCallRunner.KeyRead.Key("the-key")

        override fun dial(call: ScheduledCall, key: String) {
            dialed += call
            keys += key
        }

        override fun notPlaced(reason: String) {
            notPlaced += reason
        }

        override fun stopQuietly() {
            stopped++
        }
    }

    private val scheduler = FakeScheduler()
    private val host = Host(call)
    private val logs = ArrayList<String>()
    private val runner = ScheduledCallRunner(host, scheduler, { logs += it })
    private val retries = ScheduledCallRunner.Config().readRetries

    private fun start() {
        runner.start()
        scheduler.runUntilIdle()
    }

    private fun assertNoCall() = assertTrue(host.dialed.isEmpty())

    @Test
    fun whenAllIsWellTheCallIsPlacedOnce() {
        start()
        assertEquals(listOf(call), host.dialed)
        assertEquals(listOf("the-key"), host.keys)
        assertTrue(host.notPlaced.isEmpty())
        assertEquals(1, host.checks)
    }

    @Test
    fun theHelperIsAskedForOnceAndTheCallIsPlacedWhenItIsThere() {
        host.startsHelper = true
        start()
        assertEquals(1, host.prepared)
        assertEquals(listOf(call), host.dialed)
        assertEquals(0, host.released)
    }

    @Test
    fun aHelperThatCannotBeStartedClosesTheCallWithTheReason() {
        host.startsHelper = true
        host.helper = false
        host.setupProblem = "Wi-Fi につながっていなかったので、電話をかけませんでした"
        start()
        assertNoCall()
        assertEquals(listOf("Wi-Fi につながっていなかったので、電話をかけませんでした"), host.notPlaced)
        assertEquals(1, host.released)
    }

    @Test
    fun theWaitForAHelperTheAppStartsIsLonger() {
        host.startsHelper = true
        host.helper = false
        runner.start()
        // Past the ordinary wait, not past the longer one
        scheduler.advance(20_000)
        assertNoCall()
        assertEquals(0, host.checks)
        host.helper = true
        scheduler.advance(1_000)
        assertEquals(listOf(call), host.dialed)
    }

    @Test
    fun aCallTakenBackDuringThePreparationIsNotPlacedAndTheSwitchesGoBack() {
        host.startsHelper = true
        host.helper = false
        runner.start()
        scheduler.advance(1_000)
        host.scheduled = false
        scheduler.advance(1_000)
        assertNoCall()
        assertEquals(1, host.stopped)
        assertEquals(1, host.released)
    }

    @Test
    fun aLateAlarmStartsNothing() {
        host.now = call.fireAt.plus(ScheduleRules.LATE_LIMIT).plusSeconds(1)
        start()
        assertEquals(0, host.prepared)
        assertNoCall()
    }

    @Test
    fun aCallTakenBackFindsNoFileAndIsNotPlaced() {
        host.reads.addLast(ScheduledCallStore.Read.None)
        start()
        assertNoCall()
        assertEquals(1, host.stopped)
        assertTrue(host.notPlaced.isEmpty())
    }

    @Test
    fun aSavedCallThatCannotBeReadIsNotPlacedAndTheUserIsTold() {
        host.reads.addLast(ScheduledCallStore.Read.Unreadable)
        start()
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.UNREADABLE), host.notPlaced)
    }

    @Test
    fun aFirstStepThatThrowsEndsTheRunWithoutACall() {
        host.readFailure = IllegalStateException("no")
        start()
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.COULD_NOT_CHECK), host.notPlaced)
        assertEquals(1, host.released)
        assertTrue("$logs", logs.contains("step failed: IllegalStateException"))
    }

    @Test
    fun aLaterStepThatThrowsEndsTheRunToo() {
        host.helper = false
        start()
        host.networkFailure = IllegalStateException("no")
        host.helper = true
        scheduler.advance(1_000)
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.COULD_NOT_CHECK), host.notPlaced)
    }

    @Test
    fun aHelperThatCannotBeLetGoStillEndsTheRun() {
        host.problem = "手伝い役が要ります"
        host.releaseFailure = IllegalStateException("no")
        start()
        assertNoCall()
        assertEquals(listOf("手伝い役が要ります"), host.notPlaced)
    }

    @Test
    fun aRunGivenUpDoesNothingMore() {
        host.helper = false
        start()
        runner.giveUp()
        host.helper = true
        scheduler.advance(60_000)
        assertNoCall()
        assertTrue(host.notPlaced.isEmpty())
    }

    @Test
    fun theNextRunIsNotStoppedByTheOneGivenUp() {
        host.helper = false
        start()
        runner.giveUp()
        host.helper = true
        start()
        assertEquals(listOf(call), host.dialed)
        scheduler.advance(60_000)
        assertEquals("once", listOf(call), host.dialed)
    }

    @Test
    fun theLimitOfARunIsLongerThanEveryWaitInIt() {
        val c = ScheduledCallRunner.Config()
        assertTrue(c.longestRunMs > c.preparedHelperWaitMs + 2 * c.readRetries * c.readRetryMs)
        assertTrue(c.longestRunMs > c.helperWaitMs + 2 * c.readRetries * c.readRetryMs)
    }

    @Test
    fun aSavedCallThatCannotBeReadJustNowIsReadAgain() {
        host.reads.addLast(ScheduledCallStore.Read.NotNow)
        host.reads.addLast(ScheduledCallStore.Read.NotNow)
        runner.start()
        scheduler.advance(2_000)
        assertEquals(listOf(call), host.dialed)
    }

    @Test
    fun aSavedCallThatStaysUnreadableIsGivenUp() {
        repeat(retries + 1) { host.reads.addLast(ScheduledCallStore.Read.NotNow) }
        runner.start()
        scheduler.advance(retries * 1_000L)
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.UNREADABLE), host.notPlaced)
    }

    @Test
    fun anAlarmLaterThanTheLimitIsNotACall() {
        host.now = fireAt.plusSeconds(301)
        start()
        assertNoCall()
        assertTrue(host.notPlaced.single().contains("5 分以上過ぎていた"))
        assertEquals("the checks are not reached", 0, host.checks)
    }

    @Test
    fun theHelperIsWaitedForAfterTheProcessStarted() {
        host.helper = false
        runner.start()
        scheduler.advance(3_000)
        assertNoCall()
        host.helper = true
        scheduler.advance(500)
        assertEquals(listOf(call), host.dialed)
    }

    @Test
    fun aHelperThatDoesNotComeIsTheChecksToSay() {
        host.helper = false
        host.problem = "手伝い役が要ります"
        runner.start()
        scheduler.advance(14_000)
        assertEquals("not yet", 0, host.checks)
        scheduler.advance(2_000)
        assertNoCall()
        assertEquals(1, host.checks)
        assertEquals(listOf("手伝い役が要ります"), host.notPlaced)
    }

    @Test
    fun theNetworkIsWaitedForToo() {
        // It can take a moment after the phone wakes up
        host.network = false
        runner.start()
        scheduler.advance(3_000)
        assertNoCall()
        host.network = true
        scheduler.advance(500)
        assertEquals(listOf(call), host.dialed)
    }

    @Test
    fun withoutANetworkNoCallIsPlaced() {
        // The shop would answer and hear nothing
        host.network = false
        runner.start()
        scheduler.advance(16_000)
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.NO_NETWORK), host.notPlaced)
    }

    @Test
    fun whatElseStopsTheCallIsSaidFirst() {
        host.network = false
        host.problem = "手伝い役が要ります"
        runner.start()
        scheduler.advance(16_000)
        assertEquals(listOf("手伝い役が要ります"), host.notPlaced)
    }

    @Test
    fun aCallTakenBackDuringTheWaitIsNotPlaced() {
        host.helper = false
        runner.start()
        scheduler.advance(1_000)
        host.scheduled = false
        scheduler.advance(1_000)
        assertNoCall()
        assertEquals(1, host.stopped)
        assertEquals(0, host.checks)
    }

    @Test
    fun anyFailedCheckStopsTheCallWithItsReason() {
        // The same list of reasons as an ordinary call: only one may reach the user, and no call
        for (reason in listOf("通話の途中です", "API キーがありません", "承認されていません", "緊急の番号です", "電話の許可がありません")) {
            val host = Host(call).also { it.problem = reason }
            ScheduledCallRunner(host, FakeScheduler()).start()
            assertTrue(reason, host.dialed.isEmpty())
            assertEquals(listOf(reason), host.notPlaced)
        }
    }

    @Test
    fun aKeyStoreThatIsBusyForAWhileDoesNotCostTheCall() {
        repeat(20) { host.keyReads.addLast(ScheduledCallRunner.KeyRead.NotNow) }
        runner.start()
        scheduler.advance(20_000)
        assertEquals(listOf(call), host.dialed)
    }

    @Test
    fun aKeyThatCannotBeReadJustNowIsReadAgainThenGivenUp() {
        repeat(retries + 1) { host.keyReads.addLast(ScheduledCallRunner.KeyRead.NotNow) }
        runner.start()
        scheduler.advance(retries * 1_000L)
        assertNoCall()
        assertEquals(listOf(ScheduledCallRunner.KEY_NOT_NOW), host.notPlaced)
    }

    @Test
    fun aKeyThatIsGoneStopsTheCall() {
        host.keyReads.addLast(ScheduledCallRunner.KeyRead.Problem("キーがありません"))
        start()
        assertNoCall()
        assertEquals(listOf("キーがありません"), host.notPlaced)
    }

    @Test
    fun aCallIsNeverTriedAgain() {
        host.problem = "手伝い役が要ります"
        runner.start()
        scheduler.advance(60 * 60 * 1000L)
        assertEquals(1, host.notPlaced.size)
        assertEquals(1, host.checks)
    }

    @Test
    fun theLogHoldsNoKeyAndNoName() {
        start()
        for (line in logs) {
            assertTrue(line, !line.contains("the-key") && !line.contains(PracticePolicy.SHOP_NAME))
        }
    }
}
