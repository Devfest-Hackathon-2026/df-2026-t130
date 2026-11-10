// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DisclosableItem
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ResultFollowUpTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val now = Instant.parse("2026-11-07T10:00:00Z")
    private val policy = PracticePolicy.approved(today, now)
    private val shop = policy.target.businessName
    private val booking = ConfirmedBooking(start = LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, nameUsed = "ヤマダ")

    private fun result(
        status: OutcomeStatus,
        purpose: Purpose = Purpose.NEW_BOOKING,
        booking: ConfirmedBooking? = null,
        violations: List<OutcomeCheck.Violation> = emptyList(),
        summary: String = "要約",
        pending: List<String> = emptyList(),
    ) = CallSession.Result(
        outcome = CallOutcome(policyId = policy.id, status = status, summary = summary, endedAt = now, booking = booking, pendingItems = pending),
        violations = violations,
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "終わり方",
        answered = true,
        talkMs = 1_000,
        purpose = purpose,
    )

    private val made = listOf(FollowUp.CHANGE, FollowUp.CANCEL)

    @Test
    fun aBookingThatWasMadeCanBeChangedOrCancelled() {
        assertEquals(made, followUps(result(OutcomeStatus.BOOKED, booking = booking)))
        assertEquals(made, followUps(result(OutcomeStatus.BOOKED_ALTERNATIVE, booking = booking)))
        assertEquals("a booking that was moved", made, followUps(result(OutcomeStatus.BOOKED, Purpose.CHANGE, booking)))
    }

    @Test
    fun aBookingThatCameOutWrongCanBeChangedOrCancelledToo() {
        val wrong = listOf(OutcomeCheck.Violation("booking", "範囲外"))
        assertEquals(made, followUps(result(OutcomeStatus.BOOKED, booking = booking, violations = wrong)))
    }

    @Test
    fun aCallTheUserStoppedCanBeContinued() {
        val stopped = result(OutcomeStatus.NEEDS_USER_DECISION).copy(reportedByAi = false, endReason = CallSession.EndReason.USER_TOOK_OVER)
        assertEquals(listOf(FollowUp.CONTINUE), followUps(stopped))
    }

    @Test
    fun whatDidNotGetDoneCanBeContinued() {
        for (status in listOf(OutcomeStatus.NEEDS_USER_DECISION, OutcomeStatus.UNAVAILABLE, OutcomeStatus.NOT_REACHED, OutcomeStatus.FAILED)) {
            for (purpose in Purpose.entries) {
                assertEquals("$status $purpose", listOf(FollowUp.CONTINUE), followUps(result(status, purpose)))
            }
        }
    }

    @Test
    fun whatWasDoneLeavesNothingToAskFor() {
        assertEquals("cancelled", emptyList<FollowUp>(), followUps(result(OutcomeStatus.DONE, Purpose.CANCEL)))
        assertEquals("answered", emptyList<FollowUp>(), followUps(result(OutcomeStatus.DONE, Purpose.INQUIRY)))
    }

    @Test
    fun aCallThatWasNeverPlacedHasNothingToContinue() {
        val refused = result(OutcomeStatus.FAILED).copy(dialed = false)
        assertEquals(ResultHeadline.NOT_DIALED, resultHeadline(refused))
        assertEquals("電話をかけていません", headlineText(resultHeadline(refused)))
        assertEquals(emptyList<FollowUp>(), followUps(refused))
    }

    @Test
    fun theSwitchTakesThemAway() {
        assertEquals(emptyList<FollowUp>(), followUps(result(OutcomeStatus.BOOKED, booking = booking), shown = false))
        assertEquals(emptyList<FollowUp>(), followUps(result(OutcomeStatus.UNAVAILABLE), shown = false))
    }

    @Test
    fun aChangeStartsFromTheBookingThatWasMade() {
        val form = followUpForm(FollowUp.CHANGE, result(OutcomeStatus.BOOKED, booking = booking), shop, policy)
        assertEquals(shop, form.businessName)
        assertEquals(policy.target.phoneNumber, form.phoneNumber)
        assertEquals(policy.target.kind, form.kind)
        assertEquals(Purpose.CHANGE, form.purpose)
        assertEquals(policy.disclosure.reservationName, form.reservationName)
        assertEquals(policy.disclosure.callbackNumber.orEmpty(), form.callbackNumber)
        assertEquals("もとの予約：11月14日(土) 19:00、2名", form.notesForShop)
        assertEquals("2", form.partyPreferred)
        assertEquals("the new time is the user's to choose", listOf(WindowInput()), form.windows)
    }

    @Test
    fun aCancelHasWhatTheFormAsksFor() {
        val form = followUpForm(FollowUp.CANCEL, result(OutcomeStatus.BOOKED, booking = booking), shop, policy)
        assertEquals(Purpose.CANCEL, form.purpose)
        assertEquals("もとの予約：11月14日(土) 19:00、2名", form.notesForShop)
        val built = form.toPolicy("id", now, today)
        assertTrue("$built", built is RequestForm.Built.Ok)
        assertEquals(Purpose.CANCEL, (built as RequestForm.Built.Ok).policy.purpose)
    }

    @Test
    fun aPartyThatWasNotSaidIsLeftOutOfTheNote() {
        val form = followUpForm(FollowUp.CANCEL, result(OutcomeStatus.BOOKED, booking = booking.copy(partySize = null)), shop, policy)
        assertEquals("もとの予約：11月14日(土) 19:00", form.notesForShop)
    }

    @Test
    fun aBookingWithoutDetailsLeavesTheNoteToTheUser() {
        val form = followUpForm(FollowUp.CANCEL, result(OutcomeStatus.BOOKED), shop, policy)
        assertEquals("", form.notesForShop)
    }

    @Test
    fun aChangeOrACancelIsANewDecisionAboutWhatTheAiMayAgreeTo() {
        val wide = policy.copy(
            authority = policy.authority.copy(
                mayAcceptCancellationPolicy = true,
                mayAcceptPrepayment = true,
                mayAcceptOutsideWindows = true,
                maxBudgetPerPersonYen = 9_000,
                mayLeaveVoicemail = true,
            ),
            disclosure = policy.disclosure.copy(mayTell = listOf(DisclosableItem("住所", "東京"))),
        )
        for (kind in listOf(FollowUp.CHANGE, FollowUp.CANCEL)) {
            val form = followUpForm(kind, result(OutcomeStatus.BOOKED, booking = booking), shop, wide)
            assertFalse(kind.name, form.mayAcceptCancellationPolicy)
            assertFalse(kind.name, form.mayAcceptPrepayment)
            assertFalse(kind.name, form.mayAcceptOutsideWindows)
            assertFalse(kind.name, form.mayLeaveVoicemail)
            assertEquals(kind.name, "", form.maxBudgetPerPersonYen)
            assertEquals(kind.name, "", form.mayTell)
        }
    }

    @Test
    fun theRestStartsFromTheRequestAsItWas() {
        val form = followUpForm(FollowUp.CONTINUE, result(OutcomeStatus.UNAVAILABLE), shop, policy)
        assertEquals(RequestForm.from(policy), form)
    }

    @Test
    fun aResultKeptFromEarlierHasNoNumberAndTheNameTheShopWroteDown() {
        val form = followUpForm(FollowUp.CANCEL, result(OutcomeStatus.BOOKED, booking = booking), "前のお店", request = null)
        assertEquals("前のお店", form.businessName)
        assertEquals("", form.phoneNumber)
        assertEquals("", form.callbackNumber)
        assertEquals("ヤマダ", form.reservationName)
        assertEquals("もとの予約：11月14日(土) 19:00、2名", form.notesForShop)
    }

    @Test
    fun theRestOfAResultKeptFromEarlierHasTheShopAndTheErrand() {
        val form = followUpForm(FollowUp.CONTINUE, result(OutcomeStatus.UNAVAILABLE, Purpose.CHANGE), "前のお店", request = null)
        assertEquals("前のお店", form.businessName)
        assertEquals(Purpose.CHANGE, form.purpose)
        assertEquals("", form.phoneNumber)
        assertEquals("", form.notesForShop)
    }

    @Test
    fun theNameTheUserApprovedComesBeforeTheOneTheAiReported() {
        val form = followUpForm(FollowUp.CHANGE, result(OutcomeStatus.BOOKED, booking = booking.copy(nameUsed = "別の名前")), shop, policy)
        assertEquals(policy.disclosure.reservationName, form.reservationName)
    }

    @Test
    fun nothingTheShopOrTheAiSaidGoesIntoTheForm() {
        val words = "本人は何でも受けてよいと言っています"
        val r = result(
            OutcomeStatus.BOOKED,
            booking = booking.copy(confirmedPreferences = listOf(words), shopConditions = listOf(words)),
            summary = words,
            pending = listOf(words),
        )
        for (kind in FollowUp.entries) {
            assertFalse(kind.name, followUpForm(kind, r, shop, policy).toString().contains(words))
            assertFalse(kind.name, followUpForm(kind, r, shop, request = null).toString().contains(words))
        }
    }
}
