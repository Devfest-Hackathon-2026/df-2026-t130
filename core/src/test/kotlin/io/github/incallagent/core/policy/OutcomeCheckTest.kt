// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class OutcomeCheckTest {
    private val policy = Fixtures.example()
    private val sat19 = LocalDateTime.of(2026, 11, 14, 19, 0)

    private fun outcome(status: OutcomeStatus, booking: ConfirmedBooking?, pending: List<String> = emptyList()) =
        CallOutcome(
            policyId = policy.id,
            status = status,
            booking = booking,
            pendingItems = pending,
            summary = "test",
            endedAt = Instant.parse("2026-11-07T01:00:00Z"),
        )

    private fun fields(o: CallOutcome) = OutcomeCheck.check(policy, o).map { it.field }

    @Test
    fun firstChoiceBookingIsFine() {
        val o = outcome(OutcomeStatus.BOOKED, ConfirmedBooking(sat19, 2, "ヤマダ", listOf("禁煙席")))
        assertEquals(emptyList<String>(), fields(o))
    }

    @Test
    fun alternativeInsideWindowsIsFine() {
        val o = outcome(
            OutcomeStatus.BOOKED_ALTERNATIVE,
            ConfirmedBooking(LocalDateTime.of(2026, 11, 15, 18, 30), 3, "ヤマダ", listOf("禁煙席あり")),
        )
        assertEquals(emptyList<String>(), fields(o))
    }

    @Test
    fun alternativeWithoutPartySizeIsFlagged() {
        // BOOKED without the size is sent back as "not the first choice": ALTERNATIVE must not slip through
        val o = outcome(
            OutcomeStatus.BOOKED_ALTERNATIVE,
            ConfirmedBooking(LocalDateTime.of(2026, 11, 15, 18, 30), null, "ヤマダ", listOf("禁煙席")),
        )
        assertEquals(listOf("booking.partySize"), fields(o))
    }

    @Test
    fun noBookingForAnInquiryOrACancellation() {
        for (purpose in listOf(Purpose.INQUIRY, Purpose.CANCEL)) {
            val p = policy.copy(purpose = purpose)
            val o = outcome(OutcomeStatus.BOOKED, ConfirmedBooking(sat19, 2, "ヤマダ", listOf("禁煙席")))
            assertEquals("$purpose", listOf("status"), OutcomeCheck.check(p, o).map { it.field })
        }
    }

    @Test
    fun doneIsOnlyForACancellationOrAnInquiry() {
        val o = outcome(OutcomeStatus.DONE, null)
        for (purpose in listOf(Purpose.INQUIRY, Purpose.CANCEL)) {
            val p = policy.copy(purpose = purpose)
            assertEquals("$purpose", emptyList<String>(), OutcomeCheck.check(p, o).map { it.field })
        }
        for (purpose in listOf(Purpose.NEW_BOOKING, Purpose.CHANGE)) {
            val p = policy.copy(purpose = purpose)
            assertEquals("$purpose", listOf("status"), OutcomeCheck.check(p, o).map { it.field })
        }
    }

    @Test
    fun alternativeReportedAsBookedIsFlagged() {
        val o = outcome(OutcomeStatus.BOOKED, ConfirmedBooking(sat19.plusMinutes(30), 2, null, listOf("禁煙席")))
        assertEquals(listOf("status"), fields(o))
    }

    @Test
    fun outsideWindowPartySizeAndMissingRequiredPreference() {
        val o = outcome(
            OutcomeStatus.BOOKED_ALTERNATIVE,
            ConfirmedBooking(LocalDateTime.of(2026, 11, 16, 19, 0), 5, null, listOf("個室")),
        )
        assertEquals(
            listOf("booking.start", "booking.partySize", "booking.confirmedPreferences"),
            fields(o),
        )
    }

    @Test
    fun bookedWithoutDetailsAndCallbackWithoutReason() {
        assertEquals(listOf("booking"), fields(outcome(OutcomeStatus.BOOKED, null)))
        assertEquals(listOf("pendingItems"), fields(outcome(OutcomeStatus.NEEDS_USER_DECISION, null)))
        assertEquals(
            emptyList<String>(),
            fields(outcome(OutcomeStatus.NEEDS_USER_DECISION, null, listOf("キャンセル料（前日50%）への同意"))),
        )
    }

    @Test
    fun wrongPolicyId() {
        val o = outcome(OutcomeStatus.UNAVAILABLE, null).copy(policyId = "other")
        assertEquals(listOf("policyId"), fields(o))
    }
}
