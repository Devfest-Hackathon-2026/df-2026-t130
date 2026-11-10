// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.Approval
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.ui.RequestForm
import io.github.incallagent.rotep.ui.WindowInput
import io.github.incallagent.rotep.shell.adb.AdbStartOutcome
import io.github.incallagent.rotep.shell.adb.AdbStartPolicy
import io.github.incallagent.rotep.shell.adb.helperProblemForCall
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64

class PhoneRequestHandlerTest {
    private class FakePhone : PhoneRequestHandler.Phone {
        var running = false
        var dial: String? = null
        var setup: String? = null
        var practising = false
        var notified = true
        var notices = 0
        var cleared = 0
        var schedule: String? = null

        override fun now(): Instant = Instant.parse("2026-11-08T03:00:00Z")

        override fun today(): LocalDate = LocalDate.of(2026, 11, 8)

        override fun callRunning() = running

        override fun dialProblem(policy: DelegationPolicy) = dial

        override fun readiness() = setup

        override fun practice() = practising

        override fun notifyUser(): Boolean {
            notices++
            return notified
        }

        override fun clearNotice() {
            cleared++
        }

        override fun scheduleSetupProblem() = schedule
    }

    private class MemoryLedger : PhoneRequestStore.Ledger {
        var saved: Pair<String, PhoneRequestStore.Status>? = null

        override fun save(id: String, status: PhoneRequestStore.Status): Boolean {
            saved = id to status
            return true
        }

        override fun load() = saved
    }

    private val phone = FakePhone()
    private val store = PhoneRequestStore(MemoryLedger(), { 0L })
    private val handler = PhoneRequestHandler(store, phone)

    private val number = "03-0000-0001"
    private val booking =
        """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""

    private fun encoded(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    private fun ask(method: String, json: String?): JsonObject =
        PhoneRequestCodec.decode(handler.handle(method, json?.let(::encoded)))!!

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.isOk() = getValue("ok").jsonPrimitive.booleanOrNull == true

    private fun create(json: String = booking) = ask("create", json)

    private fun createdId() = create().text("id")

    private fun idArgument(id: String) = """{"id":"$id"}"""

    @Test
    fun theLineIsUp() {
        assertTrue(ask("ping", "{}").isOk())
    }

    @Test
    fun anUnknownMethodIsRefused() {
        assertEquals("unknown_method", ask("dial", "{}").text("error"))
    }

    @Test
    fun aGoodRequestWaitsAndTheUserIsTold() {
        val reply = create()
        assertTrue(reply.isOk())
        assertEquals("waiting_for_approval", reply.text("status"))
        assertEquals("true", reply.text("phone_notified"))
        assertEquals(1, phone.notices)
        assertEquals("店", store.incoming.value!!.shopName)
        assertFalse(reply.containsKey("phone_setup_needed"))
    }

    @Test
    fun theReplySaysSoWhenTheUserCannotSeeTheNotification() {
        phone.notified = false
        assertEquals("false", create().text("phone_notified"))
    }

    @Test
    fun whatMustBeSetUpFirstIsPassedOn() {
        phone.setup = "API キーがありません"
        assertEquals("API キーがありません", create().text("phone_setup_needed"))
    }

    @Test
    fun theSenderIsToldWhenThePhoneIsSetToPractise() {
        assertEquals("false", create().text("practice"))
        store.decline(store.incoming.value!!.id)
        phone.practising = true
        val reply = create()
        assertEquals("true", reply.text("practice"))
        assertTrue(reply.text("practice_note").contains("電話はかかりません"))
    }

    @Test
    fun aPractiseIsNotReportedAsACall() {
        val id = createdId()
        store.calling(id)
        store.finished(id, callReport(result(), changedOnPhone = false, practice = true))
        val reply = ask("get", idArgument(id))
        assertTrue(reply.text("message").contains("電話はしていません"))
        val report = reply.getValue("result").jsonObject
        assertEquals("true", report.text("practice"))
        assertEquals("false", report.text("verified"))
        assertTrue(ask("cancel", idArgument(id)).text("message").contains("電話はしていません"))
    }

    @Test
    fun takingBackAfterTheProcessDiedSaysWhatIsWrittenDown() {
        val ledger = MemoryLedger()
        ledger.saved = "old" to PhoneRequestStore.Status.WAITING
        val fresh = PhoneRequestHandler(PhoneRequestStore(ledger, { 0L }), phone)
        val reply = PhoneRequestCodec.decode(fresh.handle("cancel", encoded(idArgument("old"))))!!
        assertEquals("already_ended", reply.text("error"))
        assertEquals("expired", reply.text("status"))
    }

    @Test
    fun theNumberIsNeverSentBack() {
        val id = createdId()
        assertFalse(ask("get", idArgument(id)).toString().contains("0000-0001"))
        assertFalse(create().toString().contains("0000-0001"))
    }

    @Test
    fun whatCannotBeReadIsRefused() {
        assertEquals("invalid", PhoneRequestCodec.decode(handler.handle("create", "***"))!!.text("error"))
        assertEquals("invalid", PhoneRequestCodec.decode(handler.handle("create", null))!!.text("error"))
        assertEquals("invalid", PhoneRequestCodec.decode(handler.handle("get", null))!!.text("error"))
    }

    @Test
    fun duringACallNothingIsTaken() {
        phone.running = true
        assertEquals("busy", create().text("error"))
        assertNull(store.incoming.value)
        assertEquals(0, phone.notices)
    }

    @Test
    fun aSecondRequestIsToldWhichOneWaits() {
        val id = createdId()
        val reply = create()
        assertEquals("busy", reply.text("error"))
        assertEquals(id, reply.text("existing_id"))
        assertEquals(1, phone.notices)
    }

    @Test
    fun aRefusedFieldKeepsTheRequestOut() {
        val reply = create("""{"shop_name":"店","phone_number":"$number","approved":true}""")
        assertEquals("invalid", reply.text("error"))
        assertTrue(reply.getValue("problems").jsonArray.toString().contains("approved"))
        assertNull(store.incoming.value)
    }

    private fun questionsOf(reply: JsonObject) = reply.getValue("questions").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }

    @Test
    fun whatTheConfirmScreenWouldRefuseIsRefusedHere() {
        val past = """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2020-01-01","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""
        assertTrue(create(past).getValue("problems").jsonArray.toString().contains("過去"))
        val badNumber = """{"shop_name":"店","phone_number":"119","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""
        assertTrue(create(badNumber).getValue("problems").jsonArray.toString().contains("電話番号"))
        assertNull(store.incoming.value)
    }

    @Test
    fun whatIsMissingIsAskedForAndNothingIsKept() {
        val reply = create("""{"shop_name":"店"}""")
        assertTrue(reply.isOk())
        assertEquals("needs_info", reply.text("status"))
        assertEquals(listOf("phone_number", "windows", "party_size", "reservation_name"), questionsOf(reply))
        assertFalse(reply.containsKey("id"))
        assertNull(store.incoming.value)
        assertEquals(0, phone.notices)
    }

    @Test
    fun whatEachPurposeNeedsIsAskedFor() {
        val cancel = """{"shop_name":"店","phone_number":"$number","purpose":"cancel"}"""
        assertEquals(listOf("reservation_name", "notes_for_shop"), questionsOf(create(cancel)))
        val inquiry = """{"shop_name":"店","phone_number":"$number","purpose":"inquiry"}"""
        assertEquals(listOf("notes_for_shop"), questionsOf(create(inquiry)))
    }

    @Test
    fun theAnswersGoThroughTheSameChecksAsTheFirstRequest() {
        assertEquals("needs_info", create("""{"shop_name":"店","phone_number":"119"}""").text("status"))
        val answered = """{"shop_name":"店","phone_number":"119","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト"}"""
        assertEquals("invalid", create(answered).text("error"))
        assertNull(store.incoming.value)
    }

    @Test
    fun theAnswersEndUpInTheRequestTheOwnerReads() {
        val answered = """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":3,"reservation_name":"読むだけ"}"""
        val id = create(answered).text("id")
        assertEquals("読むだけ", store.open(id)!!.reservationName)
    }

    // --- a call at a time the sender would like ---

    private val atNoon = "2026-11-09T12:00:00+09:00"

    private fun withCallAt(at: String) =
        """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-14","from":"19:00"}],"party_size":2,"reservation_name":"テスト","call_at":"$at"}"""

    @Test
    fun theTimeAskedForIsPassedOnButDecidesNothing() {
        val reply = create(withCallAt(atNoon))
        assertEquals("waiting_for_approval", reply.text("status"))
        assertEquals("2026-11-09T12:00+09:00", reply.text("call_at"))
        assertEquals(Instant.parse("2026-11-09T03:00:00Z"), store.incoming.value!!.callAt)
    }

    @Test
    fun aTimeThatCannotBeCalledAtIsRefused() {
        assertEquals("invalid", create(withCallAt("2026-11-09T03:00:00+09:00")).text("error"))
        assertEquals("invalid", create(withCallAt("2026-11-07T12:00:00+09:00")).text("error"))
        assertNull(store.incoming.value)
    }

    @Test
    fun theTimeOfTheCallCannotBeAfterTheWishes() {
        val tooLate = """{"shop_name":"店","phone_number":"$number","windows":[{"date":"2026-11-09","from":"11:00"}],"party_size":2,"reservation_name":"テスト","call_at":"$atNoon"}"""
        val problems = create(tooLate).getValue("problems").jsonArray.toString()
        assertTrue(problems.contains("call_at"))
    }

    @Test
    fun aTimeWithoutAnOffsetIsRefused() {
        assertEquals("invalid", create(withCallAt("2026-11-09T12:00:00")).text("error"))
    }

    @Test
    fun whatMustBeSetUpForATimeIsPassedOn() {
        phone.schedule = "時間を指定するには手伝い役が要ります"
        assertEquals("時間を指定するには手伝い役が要ります", create(withCallAt(atNoon)).text("phone_setup_needed"))
    }

    @Test
    fun readingAScheduledRequestSaysWhen() {
        val id = createdId()
        val fire = Instant.parse("2026-11-09T03:00:00Z")
        store.scheduled(id, fire, changedOnPhone = false)
        val reply = ask("get", idArgument(id))
        assertEquals("scheduled", reply.text("status"))
        assertEquals("2026-11-09T12:00+09:00", reply.text("scheduled_for"))
    }

    @Test
    fun aScheduledRequestCannotBeTakenBackByTheSender() {
        val id = createdId()
        store.scheduled(id, Instant.parse("2026-11-09T03:00:00Z"), changedOnPhone = false)
        val reply = ask("cancel", idArgument(id))
        assertEquals("too_late", reply.text("error"))
        assertTrue(reply.text("message").contains("本人"))
    }

    @Test
    fun withTheSwitchOnAHelperThatIsNotRunningIsNotReportedAsSetupNeeded() {
        phone.setup = helperProblemForCall(onDemand = true, setupProblem = null, helperThere = false)
        assertFalse(create().containsKey("phone_setup_needed"))
    }

    @Test
    fun withTheSwitchOnALostPairingIsReportedAsSetupNeeded() {
        phone.setup = helperProblemForCall(onDemand = true, setupProblem = "ペアリングが要ります", helperThere = false)
        assertEquals("ペアリングが要ります", create().text("phone_setup_needed"))
    }

    @Test
    fun whyTheHelperCouldNotBeStartedReachesTheOtherAi() {
        val id = createdId()
        val reason = AdbStartPolicy.onDemandProblem(AdbStartOutcome.NO_WIFI)!!
        store.scheduled(id, Instant.parse("2026-11-09T03:00:00Z"), changedOnPhone = false)
        store.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, reason)
        val reply = ask("get", idArgument(id))
        assertEquals("not_placed", reply.text("status"))
        assertEquals(reason, reply.text("message"))
    }

    @Test
    fun aRequestWhoseCallCouldNotGetReadyStillWaitsAndSaysWhy() {
        val id = createdId()
        val reason = AdbStartPolicy.onDemandProblem(AdbStartOutcome.NO_WIFI)!!
        store.couldNotPrepare(id, reason)
        val reply = ask("get", idArgument(id))
        assertEquals("waiting_for_approval", reply.text("status"))
        assertEquals(reason, reply.text("message"))
    }

    @Test
    fun theReasonIsGoneOnceTheCallBegins() {
        val id = createdId()
        store.couldNotPrepare(id, "Wi-Fi につながっていなかったので、電話をかけませんでした")
        assertTrue(store.calling(id))
        assertEquals("電話をしています", ask("get", idArgument(id)).text("message"))
    }

    @Test
    fun aCallThatCouldNotBePlacedSaysWhy() {
        val id = createdId()
        store.scheduled(id, Instant.parse("2026-11-09T03:00:00Z"), changedOnPhone = false)
        store.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, "手伝い役がいません")
        val reply = ask("get", idArgument(id))
        assertEquals("not_placed", reply.text("status"))
        assertEquals("手伝い役がいません", reply.text("message"))
    }

    // --- through the server ---

    @Test
    fun aRequestFromTheServerKeepsItsId() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val reply = PhoneRequestCodec.decode(handler.handle("create", encoded(booking), id))!!
        assertEquals(id, reply.text("id"))
        assertEquals(id, store.incoming.value!!.id)
    }

    @Test
    fun withoutAnIdTheLatestIsRead() {
        val id = createdId()
        assertEquals(id, ask("get", "{}").text("id"))
    }

    @Test
    fun withoutAnIdAndWithNothingToReadTheSenderIsWarned() {
        assertEquals("not_found", ask("get", "{}").text("error"))
    }

    private fun delivered(id: String, json: String = booking) = PhoneRequestCodec.decode(handler.handle("create", encoded(json), id))!!

    private val serverId = "12345678-1234-1234-1234-123456789abc"

    @Test
    fun theSameDeliveryTwiceIsOneRequestAndOneNotice() {
        delivered(serverId)
        val again = delivered(serverId)
        assertTrue(again.isOk())
        assertEquals("waiting_for_approval", again.text("status"))
        assertEquals(serverId, again.text("id"))
        assertEquals(1, phone.notices)
    }

    @Test
    fun aDeliveryAgainDuringItsOwnCallIsNotRefusedAsBusy() {
        delivered(serverId)
        assertTrue(store.calling(serverId))
        phone.running = true
        val again = delivered(serverId)
        assertTrue(again.isOk())
        assertEquals("calling", again.text("status"))
    }

    @Test
    fun aDeliveryAgainAfterItEndedTellsHowItEnded() {
        delivered(serverId)
        store.decline(serverId)
        assertEquals("declined", delivered(serverId).text("status"))
    }

    @Test
    fun anotherRequestWhileOneWaitsIsBusyAndSaysWhatToDoNext() {
        delivered(serverId)
        val other = delivered("99999999-1234-1234-1234-123456789abc")
        assertEquals("busy", other.text("error"))
        assertEquals(serverId, other.text("existing_id"))
        assertTrue(other.text("message").contains("get_call_request"))
    }

    @Test
    fun aRequestDeliveredAgainAfterItWasApprovedDoesNotComeBack() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val ledger = MemoryLedger()
        ledger.saved = id to PhoneRequestStore.Status.SCHEDULED
        val fresh = PhoneRequestStore(ledger, { 0L })
        val reply = PhoneRequestCodec.decode(PhoneRequestHandler(fresh, phone).handle("create", encoded(booking), id))!!
        assertEquals("scheduled", reply.text("status"))
        assertEquals(id, reply.text("id"))
        assertNull(fresh.incoming.value)
    }

    @Test
    fun aRequestDeliveredAgainThatNobodyApprovedWaitsAgain() {
        val id = "12345678-1234-1234-1234-123456789abc"
        for (status in listOf(PhoneRequestStore.Status.WAITING, PhoneRequestStore.Status.EXPIRED)) {
            val ledger = MemoryLedger()
            ledger.saved = id to status
            val fresh = PhoneRequestStore(ledger, { 0L })
            val reply = PhoneRequestCodec.decode(PhoneRequestHandler(fresh, phone).handle("create", encoded(booking), id))!!
            assertEquals("waiting_for_approval", reply.text("status"))
            assertEquals(id, fresh.incoming.value!!.id)
        }
    }

    @Test
    fun aNumberThePhoneWillNotCallIsRefused() {
        phone.dial = "緊急の番号です"
        assertTrue(create().getValue("problems").jsonArray.toString().contains("緊急の番号です"))
        assertNull(store.incoming.value)
    }

    @Test
    fun readingAWaitingRequest() {
        val id = createdId()
        val reply = ask("get", idArgument(id))
        assertEquals(id, reply.text("id"))
        assertEquals("waiting_for_approval", reply.text("status"))
        assertFalse(reply.containsKey("result"))
    }

    @Test
    fun readingAFinishedRequest() {
        val id = createdId()
        store.calling(id)
        store.finished(id, buildJsonObject { put("outcome", "booked") })
        val reply = ask("get", idArgument(id))
        assertEquals("finished", reply.text("status"))
        assertEquals("booked", reply.getValue("result").jsonObject.text("outcome"))
    }

    @Test
    fun anUnknownIdWarnsAgainstSendingAgain() {
        val reply = ask("get", idArgument("nobody"))
        assertEquals("not_found", reply.text("error"))
        assertTrue(reply.text("message").contains("送り直さず"))
    }

    @Test
    fun takingARequestBack() {
        val id = createdId()
        val reply = ask("cancel", idArgument(id))
        assertEquals("cancelled", reply.text("status"))
        assertEquals(1, phone.cleared)
        assertNull(store.incoming.value)
    }

    @Test
    fun takingBackTwiceSaysTheSameThing() {
        val id = createdId()
        ask("cancel", idArgument(id))
        val again = ask("cancel", idArgument(id))
        assertTrue(again.isOk())
        assertEquals("cancelled", again.text("status"))
    }

    @Test
    fun aRequestThatEndedSaysHowItEnded() {
        val id = createdId()
        store.decline(id)
        val reply = ask("cancel", idArgument(id))
        assertEquals("already_ended", reply.text("error"))
        assertEquals("declined", reply.text("status"))
        assertTrue(reply.text("message").contains("電話はしていません"))
    }

    @Test
    fun aCallThatStartedCannotBeTakenBack() {
        val id = createdId()
        store.calling(id)
        assertEquals("too_late", ask("cancel", idArgument(id)).text("error"))
        assertEquals(0, phone.cleared)
    }

    @Test
    fun takingBackAnUnknownId() {
        assertEquals("not_found", ask("cancel", idArgument("nobody")).text("error"))
    }

    @Test
    fun anIdThatIsNotAStringIsRefused() {
        assertEquals("invalid", ask("get", """{"id":1}""").text("error"))
        assertEquals("invalid", ask("get", """{"id":"${"a".repeat(65)}"}""").text("error"))
    }

    // --- the report ---

    private fun result(
        status: OutcomeStatus = OutcomeStatus.BOOKED,
        violations: List<OutcomeCheck.Violation> = emptyList(),
        reportedByAi: Boolean = true,
    ) = CallSession.Result(
        outcome = CallOutcome(
            policyId = "p",
            status = status,
            booking = ConfirmedBooking(LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, nameUsed = "テスト", shopConditions = listOf("2時間制")),
            pendingItems = listOf("コースの確認"),
            summary = "予約できました",
            endedAt = Instant.parse("2026-11-08T03:10:00Z"),
        ),
        violations = violations,
        reportedByAi = reportedByAi,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "AI が切りました",
        answered = true,
        talkMs = 65_000,
    )

    @Test
    fun theReportCarriesTheOutcome() {
        val report = callReport(result(), changedOnPhone = false, practice = false)
        assertEquals("booked", report.text("outcome"))
        assertEquals("false", report.text("practice"))
        assertEquals("true", report.text("verified"))
        assertEquals("予約できました", report.text("summary"))
        assertEquals("コースの確認", report.getValue("pending_items").jsonArray.single().jsonPrimitive.content)
        val booking = report.getValue("booking").jsonObject
        assertEquals("2026-11-14T19:00", booking.text("start"))
        assertEquals("2", booking.text("party_size"))
        assertEquals("テスト", booking.text("name_used"))
        assertEquals("2時間制", booking.getValue("shop_conditions").jsonArray.single().jsonPrimitive.content)
        assertEquals("true", report.text("answered"))
        assertEquals("65", report.text("talk_seconds"))
        assertEquals("ai_hung_up", report.text("end_reason"))
        assertEquals("AI が切りました", report.text("detail"))
        assertEquals("false", report.text("changed_on_phone"))
    }

    @Test
    fun whetherTheOwnerChangedItCanBeUnknown() {
        val report = callReport(result(), changedOnPhone = null, practice = false)
        assertEquals(JsonNull, report["changed_on_phone"])
    }

    @Test
    fun aReportThatDidNotPassTheCheckIsNotVerified() {
        val broken = callReport(result(violations = listOf(OutcomeCheck.Violation("start", "希望の範囲の外です"))), false, practice = false)
        assertEquals("false", broken.text("verified"))
        assertEquals("希望の範囲の外です", broken.getValue("violations").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun aCallTheAiDidNotReportOnIsNotVerified() {
        assertEquals("false", callReport(result(reportedByAi = false), false, practice = false).text("verified"))
    }

    // --- what the user changed ---

    private val today: LocalDate = LocalDate.of(2026, 11, 8)
    private val asked = RequestForm(
        businessName = "店",
        phoneNumber = number,
        windows = listOf(WindowInput("2026-11-14", "19:00", "")),
        partyPreferred = "2",
        reservationName = "テスト",
    )

    private fun built(form: RequestForm) =
        (form.toPolicy("id", Instant.parse("2026-11-08T03:00:00Z"), today) as RequestForm.Built.Ok).policy

    private fun approved(form: RequestForm) = built(form).copy(approval = Approval(approvedAt = Instant.parse("2026-11-08T03:01:00Z")))

    @Test
    fun approvingAsItCameIsNoChange() {
        assertFalse(changedOnPhone(asked, approved(asked), today))
    }

    @Test
    fun aCorrectedNumberIsAChange() {
        assertTrue(changedOnPhone(asked, approved(asked.copy(phoneNumber = "03-0000-0002")), today))
    }

    @Test
    fun givingTheAiMoreRoomIsAChange() {
        assertTrue(changedOnPhone(asked, approved(asked.copy(mayAcceptOutsideWindows = true)), today))
        assertTrue(changedOnPhone(asked, approved(asked.copy(maxBudgetPerPersonYen = "8000")), today))
    }
}
