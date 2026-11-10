// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.app.SecretCipher
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.schedule.ScheduleAlarms
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.schedule.ScheduledCall
import io.github.incallagent.rotep.schedule.ScheduledCallStore
import io.github.incallagent.rotep.schedule.ScheduledCalls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A request that came through the server and was approved for a time, with the same pieces and in
 * the same order as RotepApp uses them: the saved call and its alarm, the request store, and what
 * is told to the server.
 */
class ScheduledRequestRoundTripTest {
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it + 1).toByte() }.toByteArray()

        override fun decrypt(data: ByteArray) = data.map { (it - 1).toByte() }.toByteArray()

        override fun deleteKey() {}
    }

    private class FakeAlarms : ScheduleAlarms {
        var set = false

        override fun arm(noticeAt: Instant, fireAt: Instant): String? {
            set = true
            return null
        }

        override fun cancel() {
            set = false
        }

        override fun armed() = set
    }

    private class FakeTransport : RelayTransport {
        val inbox = ArrayList<List<RelayCommand>>()
        val states = ArrayList<JsonObject>()
        val replies = ArrayList<JsonObject>()

        override fun next(instance: String, waitSeconds: Int) = inbox.removeFirstOrNull().orEmpty()

        override fun reply(cmd: String, reply: JsonObject) {
            replies += reply
        }

        override fun state(id: String, state: JsonObject) {
            states += state
        }
    }

    private class MemoryLedger : PhoneRequestStore.Ledger {
        var saved: Pair<String, PhoneRequestStore.Status>? = null

        override fun save(id: String, status: PhoneRequestStore.Status): Boolean {
            saved = id to status
            return true
        }

        override fun load() = saved
    }

    private class FakePhone(private val at: Instant) : PhoneRequestHandler.Phone {
        override fun now() = at

        override fun today(): LocalDate = ScheduleRules.today(at)

        override fun callRunning() = false

        override fun dialProblem(policy: DelegationPolicy): String? = null

        override fun readiness(): String? = null

        override fun practice() = false

        override fun notifyUser() = true

        override fun clearNotice() {}
    }

    private val today = LocalDate.of(2026, 11, 9)
    private val now: Instant = ScheduleRules.instantOf(today.atTime(10, 0))
    private val fireAt: Instant = ScheduleRules.instantOf(today.atTime(11, 0))
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val booking =
        """{"shop_name":"店","phone_number":"03-0000-0001","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト","call_at":"2026-11-09T11:00:00+09:00"}"""

    private lateinit var dir: File
    private val ledger = MemoryLedger()
    private val transport = FakeTransport()
    private val alarms = FakeAlarms()
    private lateinit var store: PhoneRequestStore
    private lateinit var handler: PhoneRequestHandler
    private lateinit var client: RelayClient
    private lateinit var calls: ScheduledCalls

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("relay").toFile()
        store = PhoneRequestStore(ledger, { 0L })
        handler = PhoneRequestHandler(store, FakePhone(now))
        client = RelayClient(transport, handler, "proc-1")
        calls = newCalls()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newCalls() = ScheduledCalls(ScheduledCallStore(dir, FakeCipher()), alarms, { now })

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun requestArrives() {
        transport.inbox += listOf(RelayCommand("c1", "create", id, Json.parseToJsonElement(booking).jsonObject))
        client.pollOnce(0)
        assertEquals("waiting_for_approval", transport.replies.single().text("status"))
    }

    /** "…に電話する" on the confirm screen: RotepApp.scheduleCall. */
    private fun ownerApprovesForTheTime(): Boolean {
        assertNull(calls.schedule(ScheduledCall(PracticePolicy.approved(today, now).copy(id = id), fireAt)))
        if (!store.scheduled(id, fireAt, changedOnPhone = false)) {
            calls.drop()
            return false
        }
        return true
    }

    private fun result() = CallSession.Result(
        outcome = CallOutcome(
            policyId = "p",
            status = OutcomeStatus.BOOKED,
            booking = ConfirmedBooking(LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, nameUsed = "テスト"),
            summary = "予約できました",
            endedAt = Instant.parse("2026-11-09T02:10:00Z"),
        ),
        violations = emptyList(),
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "AI が切りました",
        answered = true,
        talkMs = 65_000,
    )

    @Test
    fun approvedThenCalledAtTheTimeThenReported() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())

        // The server hears each step as its own status
        client.pushState(id)
        val scheduled = transport.states.removeAt(0)
        assertEquals("scheduled", scheduled.text("status"))
        assertEquals("2026-11-09T11:00+09:00", scheduled.text("scheduled_for"))
        assertTrue(alarms.set)

        // The alarm: RotepApp's dial, after the saved call has been taken
        assertTrue(calls.consume())
        val started = store.scheduledCallStarts(id) as PhoneRequestStore.ScheduledStart.Request
        assertEquals(id, started.id)
        client.pushState(id)
        assertEquals("calling", transport.states.removeAt(0).text("status"))

        // The result of the call
        store.finished(started.id, callReport(result(), started.changedOnPhone ?: false, practice = false))
        client.pushState(id)
        val finished = transport.states.removeAt(0)
        assertEquals("finished", finished.text("status"))
        val report = finished.getValue("result").jsonObject
        assertEquals("booked", report.text("outcome"))
        assertEquals("予約できました", report.text("summary"))
        assertFalse(finished.toString().contains("0000-0001"))
    }

    @Test
    fun takenBackInTheMomentBeforeApprovalNoCallWaits() {
        requestArrives()
        store.withdraw(id)
        assertFalse(ownerApprovesForTheTime())
        assertFalse(calls.exists)
        assertFalse(alarms.set)
        assertEquals(PhoneRequestStore.Status.CANCELLED, store.get(id)!!.status)
    }

    @Test
    fun aRequestDeclinedAtTheTimeOfSchedulingIsNotCalled() {
        requestArrives()
        store.decline(id)
        assertFalse(ownerApprovesForTheTime())
        assertEquals(PhoneRequestStore.ScheduledStart.NoRequest, store.scheduledCallStarts(id))
    }

    @Test
    fun theSavedCallSaysWhichRequestItIsFor() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())
        val saved = (ScheduledCallStore(dir, FakeCipher()).read() as ScheduledCallStore.Read.Found).call
        assertEquals(id, saved.policy.id)
    }

    @Test
    fun anOwnCallIsNotReportedToAnAiFromALeftoverInTheLedger() {
        ledger.saved = id to PhoneRequestStore.Status.SCHEDULED
        val newStore = PhoneRequestStore(ledger, { 0L })
        assertEquals(PhoneRequestStore.ScheduledStart.NoRequest, newStore.scheduledCallStarts("own-policy"))
    }

    @Test
    fun aCallThatFailsToStartClosesTheRequest() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())
        assertTrue(calls.consume())
        store.scheduledCallStarts(id)
        // RotepApp.launch could not prepare the call
        store.callNotPlaced(id, "通話の準備ができませんでした")
        client.pushState(id)
        assertEquals("not_placed", transport.states.last().text("status"))
    }

    @Test
    fun aCallThatCouldNotBePlacedIsReportedAsSuch() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())
        // RotepApp.scheduledNotPlaced
        calls.drop()
        store.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, "手伝い役がいませんでした")
        client.pushState(id)
        val state = transport.states.removeAt(0)
        assertEquals("not_placed", state.text("status"))
        assertEquals("手伝い役がいませんでした", state.text("message"))
    }

    @Test
    fun theOwnerTakingItBackIsReported() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())
        // RotepApp.cancelScheduledCall
        calls.cancel()
        store.scheduledEnded(PhoneRequestStore.Status.CANCELLED, "本人が取り消しました")
        client.pushState(id)
        assertEquals("cancelled", transport.states.removeAt(0).text("status"))
    }

    @Test
    fun theAiCannotTakeBackACallTheOwnerApproved() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())
        transport.inbox += listOf(RelayCommand("c2", "cancel", id, Json.parseToJsonElement("""{"id":"$id"}""").jsonObject))
        client.pollOnce(0)
        assertEquals("too_late", transport.replies.last().text("error"))
        assertEquals(PhoneRequestStore.Status.SCHEDULED, store.get(id)!!.status)
    }

    @Test
    fun theAlarmInANewProcessStillFindsTheRequest() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())

        val newStore = PhoneRequestStore(ledger, { 0L })
        val newClient = RelayClient(transport, PhoneRequestHandler(newStore, FakePhone(now)), "proc-2")
        val newCalls = newCalls()
        assertTrue(newCalls.exists)

        assertTrue(newCalls.consume())
        val started = newStore.scheduledCallStarts(id) as PhoneRequestStore.ScheduledStart.Request
        assertEquals(id, started.id)
        assertNull(started.changedOnPhone)

        newStore.finished(started.id, callReport(result(), false, practice = false))
        newClient.pushState(id)
        assertEquals("finished", transport.states.last().text("status"))
    }

    @Test
    fun aRequestApprovedInTheLastProcessIsNotDeliveredAsNew() {
        requestArrives()
        assertTrue(ownerApprovesForTheTime())

        val newStore = PhoneRequestStore(ledger, { 0L })
        val newClient = RelayClient(transport, PhoneRequestHandler(newStore, FakePhone(now)), "proc-2")
        transport.inbox += listOf(RelayCommand("c3", "create", id, Json.parseToJsonElement(booking).jsonObject))
        newClient.pollOnce(0)
        assertEquals("scheduled", transport.replies.last().text("status"))
        assertNull(newStore.incoming.value)
    }
}
