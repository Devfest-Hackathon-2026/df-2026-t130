// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.request.PhoneRequestStore.Added
import io.github.incallagent.rotep.request.PhoneRequestStore.Status
import io.github.incallagent.rotep.request.PhoneRequestStore.Withdrawn
import io.github.incallagent.rotep.ui.RequestForm
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRequestStoreTest {
    private class MemoryLedger : PhoneRequestStore.Ledger {
        var saved: Pair<String, Status>? = null
        var failing = false

        override fun save(id: String, status: Status): Boolean {
            if (failing) return false
            saved = id to status
            return true
        }

        override fun load() = saved
    }

    private val ledger = MemoryLedger()
    private var now = 1_000_000L
    private var nextId = 0
    private val store = PhoneRequestStore(ledger, { now }, { "id-${++nextId}" })
    private val form = RequestForm(businessName = "店", purpose = Purpose.CANCEL)
    private val report = buildJsonObject { put("outcome", "booked") }

    private fun add(): String = (store.add(form, "https://example.com") as Added.Ok).id

    private fun status(id: String) = store.get(id)?.status

    private val fire = java.time.Instant.parse("2026-11-09T03:00:00Z")

    // --- a call at a later time ---

    @Test
    fun aScheduledRequestWaitsWithoutExpiring() {
        val id = add()
        assertTrue(store.scheduled(id, fire, changedOnPhone = true))
        assertEquals(id to Status.SCHEDULED, ledger.saved)
        now += PhoneRequestStore.WAITING_MS * 3
        assertEquals(Status.SCHEDULED, status(id))
        assertEquals(fire, store.get(id)!!.scheduledFor)
        assertNull(store.incoming.value)
    }

    @Test
    fun aScheduledRequestStopsNewOnes() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        assertEquals(Added.Busy(id), store.add(form, null))
    }

    @Test
    fun aRequestThatNoLongerWaitsCannotBeScheduled() {
        val id = add()
        store.decline(id)
        assertFalse(store.scheduled(id, fire, changedOnPhone = false))
    }

    @Test
    fun aScheduleThatCannotBeWrittenDownFails() {
        val id = add()
        ledger.failing = true
        assertFalse(store.scheduled(id, fire, changedOnPhone = false))
        assertEquals(Status.WAITING, status(id))
    }

    @Test
    fun aScheduleThatFailsAfterwardsCanBeApprovedAgain() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        store.notScheduled(id)
        assertEquals(Status.WAITING, status(id))
        assertEquals(id, store.incoming.value!!.id)
    }

    @Test
    fun theAlarmTurnsTheRequestIntoACall() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = true)
        assertEquals(PhoneRequestStore.ScheduledStart.Request(id, true), store.scheduledCallStarts(id))
        assertEquals(id to Status.CALLING, ledger.saved)
        store.finished(id, report)
        assertEquals(Status.FINISHED, status(id))
    }

    @Test
    fun theAlarmOfAnOwnCallHasNoRequest() {
        assertEquals(PhoneRequestStore.ScheduledStart.NoRequest, store.scheduledCallStarts("my-own-call"))
    }

    @Test
    fun aRequestThatCannotBeWrittenDownIsNotDialed() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        ledger.failing = true
        assertEquals(PhoneRequestStore.ScheduledStart.NotWritten, store.scheduledCallStarts(id))
        ledger.failing = false
        assertEquals(Status.SCHEDULED, status(id))
    }

    @Test
    fun anOwnCallIsNotTakenForALeftoverInTheLedger() {
        ledger.saved = "old" to Status.SCHEDULED
        val fresh = PhoneRequestStore(ledger, { now })
        assertEquals(PhoneRequestStore.ScheduledStart.NoRequest, fresh.scheduledCallStarts("my-own-call"))
        assertEquals("old" to Status.SCHEDULED, ledger.saved)
    }

    @Test
    fun aLeftoverInTheLedgerStillKeepsNewRequestsOut() {
        ledger.saved = "old" to Status.SCHEDULED
        val fresh = PhoneRequestStore(ledger, { now })
        assertEquals(Added.Busy("old"), fresh.add(form, null))
    }

    @Test
    fun aRestartClosesWhatStillWaitsForItsTime() {
        ledger.saved = "old" to Status.SCHEDULED
        ledger.scheduledCallLost()
        assertEquals("old" to Status.NOT_PLACED, ledger.saved)
        assertEquals(Status.NOT_PLACED, PhoneRequestStore(ledger, { now }).get("old")!!.status)
    }

    @Test
    fun aRestartLeavesAnythingElseInTheLedgerAlone() {
        ledger.saved = "old" to Status.CALLING
        ledger.scheduledCallLost()
        assertEquals("old" to Status.CALLING, ledger.saved)
        ledger.saved = null
        ledger.scheduledCallLost()
        assertNull(ledger.saved)
    }

    @Test
    fun aCallThatDidNotStartAfterAllIsClosed() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        store.scheduledCallStarts(id)
        store.callNotPlaced(id, "通話の準備ができませんでした")
        val seen = store.get(id)!!
        assertEquals(Status.NOT_PLACED, seen.status)
        assertEquals("通話の準備ができませんでした", seen.reason)
        assertTrue(store.add(form, null) is Added.Ok)
    }

    @Test
    fun onlyACallThatStartedIsClosedThatWay() {
        val id = add()
        store.callNotPlaced(id, "理由")
        assertEquals(Status.WAITING, status(id))
    }

    @Test
    fun theRequestCanBeFoundFromTheLedgerAfterTheProcessDied() {
        ledger.saved = "old" to Status.SCHEDULED
        val fresh = PhoneRequestStore(ledger, { now })
        assertEquals(PhoneRequestStore.ScheduledStart.Request("old", null), fresh.scheduledCallStarts("old"))
        fresh.finished("old", report)
        assertEquals(report, fresh.get("old")!!.report)
    }

    @Test
    fun givingUpEndsTheRequestAndSaysWhy() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        store.scheduledEnded(Status.NOT_PLACED, "理由")
        val seen = store.get(id)!!
        assertEquals(Status.NOT_PLACED, seen.status)
        assertEquals("理由", seen.reason)
    }

    @Test
    fun theUserTakingItBackIsToldToo() {
        val id = add()
        store.scheduled(id, fire, changedOnPhone = false)
        store.scheduledEnded(Status.CANCELLED, null)
        assertEquals(Status.CANCELLED, status(id))
    }

    @Test
    fun aScheduledRequestSurvivesTheProcess() {
        assertEquals(Status.SCHEDULED, afterProcessDeath(Status.SCHEDULED))
        assertEquals(Status.NOT_PLACED, afterProcessDeath(Status.NOT_PLACED))
    }

    // --- through the server ---

    private fun addRelayed(id: String = "relay-1"): String = (store.add(form, null, null, id) as Added.Ok).id

    @Test
    fun aRelayedRequestKeepsItsId() {
        assertEquals("relay-1", addRelayed())
        assertEquals("relay-1", store.incoming.value!!.id)
    }

    @Test
    fun theSameIdAgainIsNotANewRequest() {
        addRelayed()
        assertEquals(Added.AlreadyHandled(Status.WAITING), store.add(form, null, null, "relay-1"))
        assertEquals("relay-1", store.incoming.value!!.id)
    }

    @Test
    fun theSameIdAgainTellsHowItEnded() {
        addRelayed()
        store.decline("relay-1")
        assertEquals(Added.AlreadyHandled(Status.DECLINED), store.add(form, null, null, "relay-1"))
    }

    @Test
    fun theSameIdAgainDoesNotCountAgainstTheLimit() {
        addRelayed()
        repeat(PhoneRequestStore.MAX_PER_WINDOW * 2) { store.add(form, null, null, "relay-1") }
        store.decline("relay-1")
        assertEquals(Added.Ok("relay-2"), store.add(form, null, null, "relay-2"))
    }

    @Test
    fun onlyThisProcessKnowsWhatWasDelivered() {
        addRelayed()
        assertEquals(Status.WAITING, store.delivered("relay-1"))
        assertNull(store.delivered("other"))
        assertNull(PhoneRequestStore(ledger, { now }).delivered("relay-1"))
    }

    private fun afterTheProcessDied() = PhoneRequestStore(ledger, { now })

    @Test
    fun aRequestThroughTheServerIsStillFoundAfterTheProcessDied() {
        addRelayed()
        assertTrue(store.calling("relay-1"))
        val fresh = afterTheProcessDied()
        assertEquals("relay-1", fresh.latest())
        assertEquals(Status.UNKNOWN, fresh.get("relay-1")!!.status)
    }

    @Test
    fun aCallThatDiedWithTheProcessIsToldToTheServer() {
        addRelayed()
        assertTrue(store.calling("relay-1"))
        assertEquals("relay-1", afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun whatTheUserDecidedBeforeTheProcessDiedIsToldToTheServer() {
        addRelayed()
        store.decline("relay-1")
        assertEquals("relay-1", afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun aCallARestartTookAwayIsToldToTheServer() {
        addRelayed()
        assertTrue(store.scheduled("relay-1", fire, changedOnPhone = false))
        ledger.scheduledCallLost()
        assertEquals("relay-1", afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun whatTheServerWillSendAgainIsNotToldAsOver() {
        addRelayed()
        assertNull(afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun aCallWaitingForItsTimeIsNotToldAsOver() {
        addRelayed()
        assertTrue(store.scheduled("relay-1", fire, changedOnPhone = false))
        assertNull(afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun aRequestOverAdbIsNeverToldToTheServer() {
        val id = add()
        assertTrue(store.calling(id))
        assertNull(afterTheProcessDied().leftoverForServer())
    }

    @Test
    fun aRequestOverAdbIsNotReportedWhenItsAlarmComesInANewProcess() {
        val id = add()
        assertTrue(store.scheduled(id, fire, changedOnPhone = false))
        val fresh = afterTheProcessDied()
        val told = ArrayList<String>()
        fresh.listener = { told += it }
        fresh.scheduledCallStarts(id)
        assertTrue(told.isEmpty())
    }

    @Test
    fun aRequestThroughTheServerIsReportedWhenItsAlarmComesInANewProcess() {
        addRelayed()
        assertTrue(store.scheduled("relay-1", fire, changedOnPhone = false))
        val fresh = afterTheProcessDied()
        val told = ArrayList<String>()
        fresh.listener = { told += it }
        fresh.scheduledCallStarts("relay-1")
        assertEquals(listOf("relay-1"), told)
    }

    @Test
    fun aLeftoverStaysMarkedWhenItExpires() {
        addRelayed()
        val fresh = afterTheProcessDied()
        assertTrue(fresh.expireLeftover())
        assertEquals("relay-1", fresh.latest())
    }

    /** Keeps the last few, as the app's ledger does. */
    private class RecentLedger : PhoneRequestStore.Ledger {
        var recent: String? = null
        var last: Pair<String, Status>? = null

        override fun save(id: String, status: Status): Boolean {
            recent = RecentRequests.added(recent, id, status)
            last = id to status
            return true
        }

        override fun load() = last

        override fun find(id: String) = RecentRequests.find(recent, id)
    }

    @Test
    fun anOldRequestDeliveredAgainIsNotANewOneEvenAfterAnotherCameBehindIt() {
        val recent = RecentLedger()
        val first = PhoneRequestStore(recent, { now })
        first.add(form, null, null, "relay-1")
        assertTrue(first.calling("relay-1"))
        // The process dies in the call; a request over adb comes next and is turned down
        val second = PhoneRequestStore(recent, { now }, { "id-after" })
        second.add(form, "https://example.com")
        second.decline("id-after")
        val third = PhoneRequestStore(recent, { now })
        assertEquals(Added.AlreadyHandled(Status.UNKNOWN), third.add(form, null, null, "relay-1"))
        assertNull(third.incoming.value)
    }

    @Test
    fun anOldRequestThatNobodyApprovedMayWaitAgain() {
        val recent = RecentLedger()
        PhoneRequestStore(recent, { now }).add(form, null, null, "relay-1")
        val fresh = PhoneRequestStore(recent, { now })
        assertEquals(Added.Ok("relay-1"), fresh.add(form, null, null, "relay-1"))
    }

    @Test
    fun whatBecameOfAnOlderRequestCanStillBeRead() {
        val recent = RecentLedger()
        val first = PhoneRequestStore(recent, { now })
        first.add(form, null, null, "relay-1")
        first.decline("relay-1")
        first.add(form, null, null, "relay-2")
        assertEquals(Status.DECLINED, PhoneRequestStore(recent, { now }).get("relay-1")!!.status)
    }

    @Test
    fun onlyTheLastFewAreRemembered() {
        var saved: String? = null
        for (i in 1..12) saved = RecentRequests.added(saved, "id-$i", Status.FINISHED)
        assertEquals(Status.FINISHED, RecentRequests.find(saved, "id-12"))
        assertEquals(Status.FINISHED, RecentRequests.find(saved, "id-3"))
        assertNull(RecentRequests.find(saved, "id-2"))
    }

    @Test
    fun aRequestWrittenAgainKeepsOnlyItsLatestStatus() {
        var saved = RecentRequests.added(null, "a", Status.WAITING)
        saved = RecentRequests.added(saved, "b", Status.WAITING)
        saved = RecentRequests.added(saved, "a", Status.CALLING)
        assertEquals(Status.CALLING, RecentRequests.find(saved, "a"))
        assertEquals(2, saved.lines().size)
    }

    @Test
    fun aLineThisVersionCannotReadIsNoStatus() {
        assertNull(RecentRequests.find("a\tNOT_A_STATUS", "a"))
        assertNull(RecentRequests.find(null, "a"))
        assertNull(RecentRequests.find("", "a"))
    }

    @Test
    fun theTimeAskedForIsShownOnTheBanner() {
        val at = java.time.Instant.parse("2026-11-09T03:00:00Z")
        store.add(form, null, at, "relay-1")
        assertEquals(at, store.incoming.value!!.callAt)
    }

    @Test
    fun aChangeOfARelayedRequestIsHeard() {
        val heard = ArrayList<String>()
        store.listener = { heard += it }
        val id = addRelayed()
        store.scheduled(id, fire, changedOnPhone = false)
        store.scheduledCallStarts(id)
        store.finished(id, report)
        assertEquals(listOf(id, id, id, id), heard)
    }

    @Test
    fun whyACallCouldNotGetReadyIsHeardEachTime() {
        val heard = ArrayList<String>()
        store.listener = { heard += it }
        val id = addRelayed()
        heard.clear()
        store.couldNotPrepare(id, "Wi-Fi につながっていなかったので、電話をかけませんでした")
        assertEquals(listOf(id), heard)
        store.couldNotPrepare(id, "電話の準備が時間内に終わらなかったので、電話をかけませんでした")
        assertEquals(listOf(id, id), heard)
    }

    @Test
    fun nothingIsHeardAboutARequestFromTheComputer() {
        val heard = ArrayList<String>()
        store.listener = { heard += it }
        add()
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun readingDoesNotCountAsAChange() {
        val heard = ArrayList<String>()
        store.listener = { heard += it }
        val id = addRelayed()
        store.get(id)
        store.refresh()
        assertEquals(1, heard.size)
    }

    @Test
    fun theLatestIsKnown() {
        assertNull(store.latest())
        val id = add()
        assertEquals(id, store.latest())
    }

    @Test
    fun theLatestSurvivesTheProcess() {
        ledger.saved = "old" to Status.WAITING
        assertEquals("old", PhoneRequestStore(ledger, { now }).latest())
    }

    @Test
    fun aNewRequestWaitsAndIsShown() {
        val id = add()
        assertEquals(Status.WAITING, status(id))
        assertEquals(id to Status.WAITING, ledger.saved)
        val incoming = store.incoming.value!!
        assertEquals(id, incoming.id)
        assertEquals("店", incoming.shopName)
        assertEquals(Purpose.CANCEL, incoming.purpose)
        assertEquals("https://example.com", incoming.numberSource)
        assertFalse(incoming.opened)
    }

    @Test
    fun onlyOneAtATime() {
        val id = add()
        assertEquals(Added.Busy(id), store.add(form, null))
        store.calling(id)
        assertEquals(Added.Busy(id), store.add(form, null))
    }

    @Test
    fun theNextOneCanComeOnceTheLastHasEnded() {
        val first = add()
        store.decline(first)
        assertTrue(store.add(form, null) is Added.Ok)
    }

    @Test
    fun aBurstOfRequestsIsStopped() {
        repeat(PhoneRequestStore.MAX_PER_WINDOW) { store.decline(add()) }
        assertEquals(Added.TooMany, store.add(form, null))
        now += PhoneRequestStore.RATE_WINDOW_MS
        assertTrue(store.add(form, null) is Added.Ok)
    }

    @Test
    fun aRefusedRequestDoesNotCountAgainstTheBurst() {
        val id = add()
        repeat(PhoneRequestStore.MAX_PER_WINDOW * 2) { store.add(form, null) }
        store.decline(id)
        assertTrue(store.add(form, null) is Added.Ok)
    }

    @Test
    fun anUnansweredRequestExpires() {
        val id = add()
        now += PhoneRequestStore.WAITING_MS - 1
        assertEquals(Status.WAITING, status(id))
        now += 1
        assertEquals(Status.EXPIRED, status(id))
        assertEquals(id to Status.EXPIRED, ledger.saved)
        assertNull(store.incoming.value)
        assertNull(store.open(id))
        assertFalse(store.calling(id))
    }

    @Test
    fun openingShowsItIsOpen() {
        val id = add()
        assertEquals(form, store.open(id))
        assertTrue(store.incoming.value!!.opened)
        assertEquals(Status.WAITING, status(id))
    }

    @Test
    fun declining() {
        val id = add()
        store.decline(id)
        assertEquals(Status.DECLINED, status(id))
        assertNull(store.incoming.value)
        assertFalse(store.isWaiting(id))
    }

    @Test
    fun theSenderCanTakeItBackOnlyBeforeTheCall() {
        val waiting = add()
        assertEquals(Withdrawn.DONE, store.withdraw(waiting))
        assertEquals(Status.CANCELLED, status(waiting))
        assertEquals(Withdrawn.TOO_LATE, store.withdraw(waiting))

        val calling = add()
        store.calling(calling)
        assertEquals(Withdrawn.TOO_LATE, store.withdraw(calling))
        assertEquals(Withdrawn.NOT_FOUND, store.withdraw("nobody"))
    }

    @Test
    fun aRequestTakenBackCanNoLongerBeApproved() {
        val id = add()
        store.open(id)
        store.withdraw(id)
        assertFalse(store.isWaiting(id))
        assertFalse(store.calling(id))
        assertEquals(Status.CANCELLED, status(id))
    }

    @Test
    fun theCallIsOnDiskBeforeItIsDialed() {
        val id = add()
        assertTrue(store.calling(id))
        assertEquals(id to Status.CALLING, ledger.saved)
        assertNull(store.incoming.value)
    }

    @Test
    fun aCallThatCannotBeWrittenDownIsNotDialed() {
        val id = add()
        ledger.failing = true
        assertFalse(store.calling(id))
        assertEquals(Status.WAITING, status(id))
        assertTrue(store.isWaiting(id))
        ledger.failing = false
        assertTrue(store.calling(id))
    }

    @Test
    fun approvingTwiceOnlyWorksOnce() {
        val id = add()
        assertTrue(store.calling(id))
        assertFalse(store.calling(id))
    }

    @Test
    fun aCallThatDidNotStartCanBeApprovedAgain() {
        val id = add()
        store.calling(id)
        store.notStarted(id)
        assertEquals(Status.WAITING, status(id))
        assertEquals(id to Status.WAITING, ledger.saved)
        assertEquals(id, store.incoming.value!!.id)
        assertTrue(store.calling(id))
    }

    @Test
    fun theResultIsKeptForTheSender() {
        val id = add()
        store.calling(id)
        store.finished(id, report)
        val seen = store.get(id)!!
        assertEquals(Status.FINISHED, seen.status)
        assertEquals(report, seen.report)
        assertEquals(id to Status.FINISHED, ledger.saved)
    }

    @Test
    fun aResultForARequestNotCallingIsDropped() {
        val id = add()
        store.finished(id, report)
        assertEquals(Status.WAITING, status(id))
        assertNull(store.get(id)!!.report)
    }

    @Test
    fun anEndedRequestIsForgottenAfterAWhile() {
        val id = add()
        store.calling(id)
        store.finished(id, report)
        now += PhoneRequestStore.KEPT_MS - 1
        assertEquals(report, store.get(id)!!.report)
        now += 1
        val seen = store.get(id)!!
        assertNull(seen.report)
        assertEquals(Status.UNKNOWN, seen.status)
    }

    @Test
    fun onlyTheLastFewEndedRequestsAreKept() {
        val ids = List(PhoneRequestStore.MAX_KEPT + 1) { add().also(store::decline) }
        assertNull(store.get(ids.first()))
        assertEquals(Status.DECLINED, status(ids.last()))
    }

    @Test
    fun anUnknownIdIsNull() {
        add()
        assertNull(store.get("nobody"))
    }

    // --- a new process, with only the ledger left ---

    private fun afterProcessDeath(status: Status): Status? {
        ledger.saved = "old" to status
        return PhoneRequestStore(ledger, { now }).get("old")?.status
    }

    @Test
    fun aCallThatMayHaveBeenMadeIsUnknownAfterTheProcessDied() {
        assertEquals(Status.UNKNOWN, afterProcessDeath(Status.CALLING))
        assertEquals(Status.UNKNOWN, afterProcessDeath(Status.FINISHED))
    }

    @Test
    fun aRequestThatWasNeverCalledIsExpiredAfterTheProcessDied() {
        assertEquals(Status.EXPIRED, afterProcessDeath(Status.WAITING))
        assertEquals(Status.EXPIRED, afterProcessDeath(Status.EXPIRED))
    }

    @Test
    fun theUsersAnswerSurvivesTheProcess() {
        assertEquals(Status.DECLINED, afterProcessDeath(Status.DECLINED))
        assertEquals(Status.CANCELLED, afterProcessDeath(Status.CANCELLED))
    }

    @Test
    fun aNewProcessHasNothingWaiting() {
        ledger.saved = "old" to Status.WAITING
        val fresh = PhoneRequestStore(ledger, { now })
        assertNull(fresh.incoming.value)
        assertNotNull(fresh.add(form, null) as? Added.Ok)
    }

    @Test
    fun aRequestLostWithTheLastProcessIsMarkedOnce() {
        ledger.saved = "old" to Status.WAITING
        val fresh = PhoneRequestStore(ledger, { now })
        assertTrue(fresh.expireLeftover())
        assertEquals("old" to Status.EXPIRED, ledger.saved)
        assertFalse(fresh.expireLeftover())
    }

    @Test
    fun nothingIsLostWhenNothingWaited() {
        ledger.saved = "old" to Status.CALLING
        assertFalse(PhoneRequestStore(ledger, { now }).expireLeftover())
        assertEquals("old" to Status.CALLING, ledger.saved)
        ledger.saved = null
        assertFalse(PhoneRequestStore(ledger, { now }).expireLeftover())
    }

    @Test
    fun aRequestOfThisProcessIsNotLeftOver() {
        add()
        assertFalse(store.expireLeftover())
        assertEquals(Status.WAITING, ledger.saved!!.second)
    }

    @Test
    fun refreshingNoticesAnExpiredRequest() {
        add()
        now += PhoneRequestStore.WAITING_MS
        assertNotNull(store.incoming.value)
        store.refresh()
        assertNull(store.incoming.value)
    }

    @Test
    fun anotherIdIsNotAnsweredFromTheLedger() {
        ledger.saved = "old" to Status.CALLING
        assertNull(PhoneRequestStore(ledger, { now }).get("other"))
    }
}
