// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ToolCallHandlerTest {
    private class FakeActions : CallActions {
        val ended = ArrayList<String>()
        val dtmf = ArrayList<String>()
        override fun endCall(reason: String) { ended += reason }
        override fun sendDtmf(digits: String) { dtmf += digits }
    }

    private val actions = FakeActions()
    private val handler = ToolCallHandler(TestPolicy.example(), actions) { Instant.parse("2026-11-07T01:00:00Z") }

    private fun handlerFor(purpose: Purpose) =
        ToolCallHandler(TestPolicy.example().copy(purpose = purpose), actions) { Instant.parse("2026-11-07T01:00:00Z") }

    private fun call(name: String, args: String, to: ToolCallHandler = handler) =
        to.handle(FunctionCall("id-1", name, Json.parseToJsonElement(args).jsonObject)).response

    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    private fun JsonObject.instruction() = this["instruction"]!!.jsonPrimitive.content

    private val offerWithAFee =
        """{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"],"requiresCancellationFee":true}"""

    @Test
    fun evaluateOffer() {
        val r = call(LiveTools.EVALUATE_OFFER, """{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"]}""")
        assertTrue(r.bool("accept"))
        assertTrue(r.bool("isFirstChoice"))

        val no = call(LiveTools.EVALUATE_OFFER, """{"start":"2026-11-14T21:00","partySize":2,"confirmedPreferences":["禁煙席"]}""")
        assertFalse(no.bool("accept"))
        assertEquals(1, no["reasons"]!!.jsonArray.size)
    }

    @Test
    fun anOfferInACancellationOrAnInquiryGoesBackToTheErrand() {
        for (purpose in listOf(Purpose.CANCEL, Purpose.INQUIRY)) {
            val r = call(LiveTools.EVALUATE_OFFER,
                """{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"]}""", handlerFor(purpose))
            assertFalse(r.bool("accept"))
            assertEquals("受けない。今回は予約のお願いではないと伝えて、用件に戻る。", r.instruction())
        }
    }

    private val cancellingWithAgreedFee = TestPolicy.example().let {
        it.copy(purpose = Purpose.CANCEL, authority = it.authority.copy(mayAcceptCancellationPolicy = true))
    }

    @Test
    fun aCancellationFeeNobodyAgreedToGoesBackToTheUser() {
        val r = call(LiveTools.EVALUATE_OFFER, offerWithAFee, handlerFor(Purpose.CANCEL))
        assertFalse(r.bool("accept"))
        assertFalse(r.bool("isFirstChoice"))
        assertEquals(
            "取り消さない。自分で決めずに「${TestPolicy.example().conversation.fallbackPhrase}」と伝え、" +
                "report_result は NEEDS_USER_DECISION（pendingItems に金額）で報告する。",
            r.instruction(),
        )
    }

    @Test
    fun aCancellationFeeTheUserAgreedToLetsTheCancellationGoAhead() {
        val r = call(LiveTools.EVALUATE_OFFER, offerWithAFee, ToolCallHandler(cancellingWithAgreedFee, actions))
        assertTrue(r.bool("accept"))
        assertFalse(r.bool("isFirstChoice"))
        assertEquals("取り消しを進めてよい。金額を復唱して確かめ、summary に金額を書く。報告の status は DONE。", r.instruction())
    }

    @Test
    fun aCancellationFeeNeedsNoDateAndIsNotHeldToTheBudget() {
        val feeOnly = """{"requiresCancellationFee":true,"pricePerPersonYen":8001}"""
        assertTrue(call(LiveTools.EVALUATE_OFFER, feeOnly, ToolCallHandler(cancellingWithAgreedFee, actions)).bool("accept"))

        val refused = call(LiveTools.EVALUATE_OFFER, feeOnly, handlerFor(Purpose.CANCEL))
        assertFalse(refused.bool("accept"))
        assertTrue(refused.instruction(), refused.instruction().startsWith("取り消さない。"))
    }

    @Test
    fun aFeeInAnInquiryStaysOnTheErrand() {
        val r = call(LiveTools.EVALUATE_OFFER, offerWithAFee, handlerFor(Purpose.INQUIRY))
        assertFalse(r.bool("accept"))
        assertEquals("受けない。今回は予約のお願いではないと伝えて、用件に戻る。", r.instruction())
    }

    @Test
    fun anAgreedFeeDoesNotTurnOtherOffersInACancellationIntoAYes() {
        val r = call(LiveTools.EVALUATE_OFFER,
            """{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"]}""",
            ToolCallHandler(cancellingWithAgreedFee, actions))
        assertFalse(r.bool("accept"))
        assertEquals("受けない。今回は予約のお願いではないと伝えて、用件に戻る。", r.instruction())
    }

    @Test
    fun aFeeForABookingKeepsTheBookingInstructions() {
        val agreed = TestPolicy.example().let {
            it.copy(authority = it.authority.copy(mayAcceptCancellationPolicy = true))
        }
        val r = call(LiveTools.EVALUATE_OFFER, offerWithAFee, ToolCallHandler(agreed, actions))
        assertTrue(r.bool("accept"))
        assertEquals("受けてよい。日時と人数を復唱して確かめてから受ける。報告の status は BOOKED。", r.instruction())
    }

    @Test
    fun aRefusedOfferForABookingStillAsksForAnotherChoice() {
        for (purpose in listOf(Purpose.NEW_BOOKING, Purpose.CHANGE)) {
            val r = call(LiveTools.EVALUATE_OFFER,
                """{"start":"2026-11-14T21:00","partySize":2,"confirmedPreferences":["禁煙席"]}""", handlerFor(purpose))
            assertFalse(r.bool("accept"))
            assertTrue(r.instruction(), r.instruction().startsWith("受けない。ほかの候補があればお願いし、なければ「"))
        }
    }

    @Test
    fun dateTimeWithSecondsOrOffsetIsAccepted() {
        val r = call(LiveTools.EVALUATE_OFFER, """{"start":"2026-11-14T19:00:00+09:00","partySize":2,"confirmedPreferences":["禁煙席"]}""")
        assertTrue(r.toString(), r.bool("accept"))
    }

    @Test
    fun badDateTimeIsAnError() {
        val r = call(LiveTools.EVALUATE_OFFER, """{"start":"土曜の夜","partySize":2}""")
        assertFalse(r.bool("ok"))
    }

    @Test
    fun endCallNeedsAReportFirst() {
        val refused = call(LiveTools.END_CALL, """{"reason":"done"}""")
        assertFalse(refused.bool("ok"))
        assertTrue(actions.ended.isEmpty())

        val report = call(LiveTools.REPORT_RESULT,
            """{"status":"BOOKED","start":"2026-11-14T19:00","partySize":2,"nameUsed":"ヤマダ","confirmedPreferences":["禁煙席"],"summary":"予約できました"}""")
        assertTrue(report.toString(), report.bool("ok"))
        assertEquals(OutcomeStatus.BOOKED, handler.outcome!!.status)
        assertEquals("example-0001", handler.outcome!!.policyId)

        assertTrue(call(LiveTools.END_CALL, """{"reason":"done"}""").bool("ok"))
        call(LiveTools.END_CALL, """{"reason":"again"}""")
        assertEquals(listOf("done"), actions.ended)
    }

    @Test
    fun reportOutsideThePolicyIsFlagged() {
        val r = call(LiveTools.REPORT_RESULT,
            """{"status":"BOOKED","start":"2026-11-14T21:00","partySize":2,"confirmedPreferences":["禁煙席"],"summary":"予約できました"}""")
        assertFalse(r.bool("ok"))
        assertTrue(handler.outcomeViolations.isNotEmpty())
    }

    @Test
    fun doneCarriesNoBooking() {
        val cancel = handlerFor(Purpose.CANCEL)
        // The date of the reservation that was cancelled does not make it a booking
        val r = call(LiveTools.REPORT_RESULT,
            """{"status":"DONE","start":"2026-11-14T19:00","partySize":2,"summary":"予約を取り消しました"}""", cancel)
        assertTrue(r.toString(), r.bool("ok"))
        assertEquals(OutcomeStatus.DONE, cancel.outcome!!.status)
        assertNull(cancel.outcome!!.booking)
    }

    @Test
    fun doneForABookingIsFlagged() {
        val r = call(LiveTools.REPORT_RESULT,
            """{"status":"DONE","summary":"済みました"}""", handlerFor(Purpose.NEW_BOOKING))
        assertFalse(r.bool("ok"))
    }

    @Test
    fun reportWithUnknownStatusIsAnError() {
        val r = call(LiveTools.REPORT_RESULT, """{"status":"MAYBE","summary":"?"}""")
        assertFalse(r.bool("ok"))
        assertNull(handler.outcome)
    }

    @Test
    fun dtmf() {
        assertTrue(call(LiveTools.SEND_DTMF, """{"digits":"1#"}""").bool("ok"))
        assertFalse(call(LiveTools.SEND_DTMF, """{"digits":"12a"}""").bool("ok"))
        assertEquals(listOf("1#"), actions.dtmf)
    }

    @Test
    fun unknownTool() {
        assertFalse(call("book_flight", "{}").bool("ok"))
    }

    @Test
    fun responseKeepsTheCallId() {
        val r = handler.handle(FunctionCall("abc", LiveTools.SEND_DTMF, Json.parseToJsonElement("""{"digits":"1"}""").jsonObject))
        assertEquals("abc", r.id)
        assertEquals(LiveTools.SEND_DTMF, r.name)
    }
}
