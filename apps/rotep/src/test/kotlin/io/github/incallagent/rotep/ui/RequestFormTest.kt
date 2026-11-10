// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RequestFormTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val now = today.atTime(9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant()

    @Test
    fun practiceExampleRoundTrips() {
        val practice = PracticePolicy.draft(today, now)
        val built = RequestForm.from(practice).toPolicy(practice.id, practice.createdAt, today)
        assertTrue(built.toString(), built is RequestForm.Built.Ok)
        val p = (built as RequestForm.Built.Ok).policy
        assertEquals(practice, p)
        assertFalse(p.isApproved)
        assertEquals(emptyList<Any>(), PolicyValidator.validate(p, now))
    }

    @Test
    fun typedRequestBecomesAPolicy() {
        val form = RequestForm(
            businessName = "テスト食堂",
            phoneNumber = "03-0000-0000",
            purpose = Purpose.NEW_BOOKING,
            windows = listOf(WindowInput("11/14", "19時", ""), WindowInput("2026年11月15日", "18:00", "19：30"), WindowInput()),
            partyPreferred = "２",
            partyMax = "3",
            requiredPreferences = "禁煙席、カウンター",
            optionalPreferences = "個室",
            reservationName = "ヤマダ",
            callbackNumber = "090-0000-0000",
            mayTell = "メールアドレス：yamada@example.com\n\n",
            maxBudgetPerPersonYen = "8,000円",
            mayLeaveVoicemail = true,
            maxCallMinutes = "3",
        )
        val p = (form.toPolicy("id-1", now, today) as RequestForm.Built.Ok).policy
        assertEquals(2, p.request.windows.size)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 0), p.request.windows[0].earliestStart)
        assertEquals(p.request.windows[0].earliestStart, p.request.windows[0].latestStart)
        assertEquals(LocalDateTime.of(2026, 11, 15, 19, 30), p.request.windows[1].latestStart)
        assertEquals(2, p.request.partySize!!.preferred)
        assertEquals(2, p.request.partySize!!.min)
        assertEquals(3, p.request.partySize!!.max)
        assertEquals(listOf("禁煙席", "カウンター"), p.request.preferences.filter { it.required }.map { it.text })
        assertEquals(8000, p.authority.maxBudgetPerPersonYen)
        assertTrue(p.authority.mayLeaveVoicemail)
        assertEquals("メールアドレス", p.disclosure.mayTell.single().label)
        assertEquals(180, p.conversation.maxCallSeconds)
        assertEquals(emptyList<Any>(), PolicyValidator.validate(p.copy(approval = null), now))
    }

    private val typed = RequestForm(businessName = "テスト食堂", phoneNumber = "03-0000-0000", windows = listOf(WindowInput("11/14", "19時", "")))

    @Test
    fun aCallLimitAboveTheMostIsRefused() {
        val errors = (typed.copy(maxCallMinutes = "16").toPolicy("id", now, today) as RequestForm.Built.Errors).messages
        assertEquals(listOf("通話の上限は 15 分までにしてください"), errors)
    }

    @Test
    fun theMostIsAllowed() {
        val p = (typed.copy(maxCallMinutes = "15").toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertEquals(900, p.conversation.maxCallSeconds)
    }

    @Test
    fun aNumberTooLongToCountIsNoNumber() {
        val errors = (typed.copy(maxCallMinutes = "99999999999").toPolicy("id", now, today) as RequestForm.Built.Errors).messages
        assertEquals(listOf("通話の上限（分）は数字で書いてください"), errors)
    }

    // --- a cancel, and what the other purposes do not use ---

    private val cancel = RequestForm(
        businessName = "テスト食堂",
        phoneNumber = "03-0000-0000",
        purpose = Purpose.CANCEL,
        notesForShop = "11月14日 19時 2名の予約",
        reservationName = "ヤマダ",
    )

    @Test
    fun aCancelNeedsNoTimeOrPartySizeAndPassesTheCheck() {
        val p = (cancel.toPolicy("id-c", now, today) as RequestForm.Built.Ok).policy
        assertEquals(Purpose.CANCEL, p.purpose)
        assertTrue(p.request.windows.isEmpty())
        assertNull("the form's default of 2 is not asked for", p.request.partySize)
        assertEquals(emptyList<Any>(), PolicyValidator.validate(p, now))
    }

    @Test
    fun aCancelIsRefusedWithoutANameOrTheBookingItCancels() {
        val noName = cancel.copy(reservationName = " ").toPolicy("id", now, today) as RequestForm.Built.Errors
        assertTrue(noName.messages.toString(), noName.messages.any { it.contains("予約名") })
        val noBooking = cancel.copy(notesForShop = "").toPolicy("id", now, today) as RequestForm.Built.Errors
        assertTrue(noBooking.messages.toString(), noBooking.messages.any { it.contains("取り消す予約") })
    }

    @Test
    fun aChangeHasToSayWhatChanges() {
        val form = cancel.copy(
            purpose = Purpose.CHANGE,
            notesForShop = "",
            windows = listOf(WindowInput("11/14", "19:00")),
            partyPreferred = "2",
        )
        val built = form.toPolicy("id", now, today) as RequestForm.Built.Errors
        assertTrue(built.messages.toString(), built.messages.any { it.contains("変えたいこと") })
    }

    @Test
    fun whatIsHiddenForACancelDoesNotCount() {
        // Left over from before the purpose was changed: a bad date must not stop the cancel
        val form = cancel.copy(
            windows = listOf(WindowInput("あした", "19:00")),
            partyPreferred = "たくさん",
            requiredPreferences = "禁煙席",
            maxBudgetPerPersonYen = "高い",
            mayAcceptPrepayment = true,
            mayAcceptOutsideWindows = true,
            mayAcceptCancellationPolicy = true,
        )
        val p = (form.toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertTrue(p.request.preferences.isEmpty())
        assertNull(p.authority.maxBudgetPerPersonYen)
        assertFalse(p.authority.mayAcceptPrepayment)
        assertFalse(p.authority.mayAcceptOutsideWindows)
        assertTrue("the cancel fee stays the user's choice", p.authority.mayAcceptCancellationPolicy)
    }

    @Test
    fun anInquiryHasToSayWhatItAsks() {
        val built = cancel.copy(purpose = Purpose.INQUIRY, notesForShop = "  ").toPolicy("id", now, today) as RequestForm.Built.Errors
        assertTrue(built.messages.toString(), built.messages.any { it.contains("聞きたいこと") })
    }

    @Test
    fun changingThePurposeEmptiesTheNoteAndTheFeeConsentThatMeantSomethingElse() {
        val booking = RequestForm(notesForShop = "誕生日のお祝い", mayAcceptCancellationPolicy = true)
        val toCancel = booking.withPurpose(Purpose.CANCEL)
        assertEquals("", toCancel.notesForShop)
        assertFalse("a booking's terms are not a fee paid now", toCancel.mayAcceptCancellationPolicy)
        val back = cancel.copy(mayAcceptCancellationPolicy = true).withPurpose(Purpose.NEW_BOOKING)
        assertFalse(back.mayAcceptCancellationPolicy)
        val change = booking.withPurpose(Purpose.CHANGE)
        assertTrue("the same decision for a booking and a change", change.mayAcceptCancellationPolicy)
        assertEquals("choosing the same one again keeps the note", booking, booking.withPurpose(Purpose.NEW_BOOKING))
    }

    @Test
    fun anInquiryAgreesToNoFee() {
        val p = (cancel.copy(purpose = Purpose.INQUIRY, mayAcceptCancellationPolicy = true).toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertFalse(p.authority.mayAcceptCancellationPolicy)
    }

    @Test
    fun aCancelHasAReasonToGiveThatTheUserCanSeeAndReplace() {
        val p = (cancel.toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertTrue(p.disclosure.mayTell.any { "理由" in it.label })
        val own = (cancel.copy(mayTell = "取り消しの理由：引っ越し").toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertEquals(listOf("引っ越し"), own.disclosure.mayTell.map { it.value })
    }

    @Test
    fun aTypedPartyRangeKeepsThePreferredSize() {
        assertEquals("2", RequestForm(partyMin = "2", partyMax = "4").withKind(BusinessKind.CLINIC).partyPreferred)
    }

    @Test
    fun anInquiryDoesNotSayHowManyPeopleItMayBook() {
        val p = (cancel.copy(purpose = Purpose.INQUIRY).toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertNull(p.request.partySize)
    }

    @Test
    fun aWaitlistIsNeverAcceptedFromTheForm() {
        // A waitlist entry would be reported as a booking
        val practice = PracticePolicy.draft(today, now)
        val p = (RequestForm.from(practice).toPolicy("id", now, today) as RequestForm.Built.Ok).policy
        assertFalse(p.authority.mayJoinWaitlist)
    }

    @Test
    fun theUsualPartySizeFollowsTheKindUnlessTheUserChangedIt() {
        val form = RequestForm()
        assertEquals("2", form.partyPreferred)
        val salon = form.withKind(BusinessKind.SALON)
        assertEquals("1", salon.partyPreferred)
        assertEquals("2", salon.withKind(BusinessKind.RESTAURANT).partyPreferred)
        assertEquals("kept", "4", form.copy(partyPreferred = "4").withKind(BusinessKind.CLINIC).partyPreferred)
    }

    @Test
    fun parseErrorsAreListed() {
        val form = RequestForm(
            windows = listOf(WindowInput("あした", "19:00"), WindowInput("11/14", "25:00")),
            partyPreferred = "ふたり",
            mayTell = "ラベルだけ",
        )
        val errors = (form.toPolicy("id", now, today) as RequestForm.Built.Errors).messages
        assertEquals(4, errors.size)
        assertTrue(errors[0], errors[0].startsWith("希望の日時 1"))
        assertTrue(errors[1], errors[1].startsWith("希望の日時 2"))
    }

    @Test
    fun datesWithoutAYearAreTheNextSuchDay() {
        assertEquals(LocalDate.of(2026, 11, 14), RequestForm.parseDate("11/14", today))
        assertEquals(LocalDate.of(2027, 1, 5), RequestForm.parseDate("1/5", today))
        assertEquals(LocalDate.of(2026, 11, 7), RequestForm.parseDate("11月7日", today))
        assertNull(RequestForm.parseDate("2/30", today))
        assertEquals(LocalTime.of(9, 5), RequestForm.parseTime("9:05"))
        assertEquals(LocalTime.of(18, 30), RequestForm.parseTime("18時30分"))
        assertNull(RequestForm.parseTime("19:75"))
    }

    @Test
    fun february29WithoutAYearIsNeverMovedToAnotherDay() {
        // Still to come this year (a leap year)
        assertEquals(LocalDate.of(2028, 2, 29), RequestForm.parseDate("2/29", LocalDate.of(2028, 1, 10)))
        // Next year is a leap year
        assertEquals(LocalDate.of(2028, 2, 29), RequestForm.parseDate("2/29", LocalDate.of(2027, 11, 8)))
        // It has just passed, and next year has none: not the 28th of next year
        assertNull(RequestForm.parseDate("2/29", LocalDate.of(2028, 3, 1)))
        // Neither this year nor next
        assertNull(RequestForm.parseDate("2/29", LocalDate.of(2026, 11, 8)))
    }
}
