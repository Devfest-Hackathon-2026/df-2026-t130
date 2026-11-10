// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.calendar

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.SavedResult
import io.github.incallagent.rotep.app.SavedResultJson
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.call.CallSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

class CalendarRulesTest {
    // Everything is fictitious
    private val shop = "レストラン・サンプル"
    private val number = "03-0000-0000"
    private val booking = ConfirmedBooking(
        start = LocalDateTime.of(2026, 11, 14, 19, 0),
        partySize = 2,
        nameUsed = "ヤマダ",
        confirmedPreferences = listOf("禁煙席"),
        shopConditions = listOf("キャンセル料は前日から"),
    )

    private fun result(
        status: OutcomeStatus = OutcomeStatus.BOOKED,
        booking: ConfirmedBooking? = this.booking,
        violations: List<OutcomeCheck.Violation> = emptyList(),
        purpose: Purpose = Purpose.NEW_BOOKING,
        dialed: Boolean = true,
    ) = CallSession.Result(
        outcome = CallOutcome(
            policyId = "policy",
            status = status,
            booking = booking,
            pendingItems = listOf("駐車場は折り返し"),
            summary = "お店の人は、窓際は埋まっていると言いました",
            endedAt = Instant.parse("2026-11-08T10:05:00.123456Z"),
        ),
        violations = violations,
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "-",
        answered = true,
        talkMs = 60_000,
        purpose = purpose,
        dialed = dialed,
    )

    private fun event(minutes: Int? = null, purpose: Purpose = Purpose.NEW_BOOKING, booking: ConfirmedBooking = this.booking, phone: String? = number, shopName: String? = shop) =
        CalendarRules.event(booking, shopName, phone, minutes, purpose, reservationName = "ヤマダ")

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun aDateThatCannotBeRightStaysOutOfTheCalendar() {
        // The call of the results here is on 2026-11-08
        fun at(y: Int, m: Int, d: Int) = result(booking = booking.copy(start = LocalDateTime.of(y, m, d, 19, 0)))
        assertFalse("a wrong year", CalendarRules.qualifies(inCall = true, at(2025, 11, 14)))
        assertFalse("years ahead", CalendarRules.qualifies(inCall = true, at(2028, 11, 14)))
        assertFalse("before the call", CalendarRules.qualifies(inCall = true, at(2026, 11, 6)))
        assertTrue("the same day", CalendarRules.qualifies(inCall = true, at(2026, 11, 8)))
        assertTrue("far ahead but possible", CalendarRules.qualifies(inCall = true, at(2027, 11, 8)))
    }

    @Test
    fun aCleanBookingOfARealCallQualifies() {
        assertTrue(CalendarRules.qualifies(inCall = true, result()))
        assertTrue(CalendarRules.qualifies(inCall = true, result(status = OutcomeStatus.BOOKED_ALTERNATIVE)))
        assertTrue(CalendarRules.qualifies(inCall = true, result(purpose = Purpose.CHANGE)))
    }

    @Test
    fun aPracticeOrARefusedDialDoesNot() {
        assertFalse(CalendarRules.qualifies(inCall = false, result()))
        assertFalse(CalendarRules.qualifies(inCall = true, result(dialed = false)))
    }

    @Test
    fun aBookingTheCheckFoundFaultWithDoesNot() {
        val r = result(violations = listOf(OutcomeCheck.Violation("booking.start", "希望の日時の範囲外です")))
        assertFalse(CalendarRules.qualifies(inCall = true, r))
    }

    @Test
    fun cancelsAndInquiriesDoNot() {
        assertFalse(CalendarRules.qualifies(inCall = true, result(purpose = Purpose.CANCEL)))
        assertFalse(CalendarRules.qualifies(inCall = true, result(purpose = Purpose.INQUIRY)))
    }

    @Test
    fun onlyABookedStatusWithABookingDoes() {
        for (status in OutcomeStatus.entries - setOf(OutcomeStatus.BOOKED, OutcomeStatus.BOOKED_ALTERNATIVE)) {
            assertFalse("$status", CalendarRules.qualifies(inCall = true, result(status = status)))
        }
        assertFalse(CalendarRules.qualifies(inCall = true, result(booking = null)))
    }

    @Test
    fun theTitleSaysWhichShop() {
        assertEquals("レストラン・サンプルの予約", event().title)
        assertEquals("予約", event(shopName = null).title)
        assertEquals("予約", event(shopName = " ").title)
    }

    @Test
    fun itStartsWhenTheShopSaidInJapanTime() {
        assertEquals(millis("2026-11-14T10:00:00Z"), event().startMillis)
        assertEquals("Asia/Tokyo", event().timeZone)
    }

    @Test
    fun itLastsAsLongAsAskedOrTwoHours() {
        val start = event().startMillis
        assertEquals(start + 90 * 60_000L, event(minutes = 90).endMillis)
        assertEquals(start + 120 * 60_000L, event(minutes = null).endMillis)
        assertEquals(start + 120 * 60_000L, event(minutes = 0).endMillis)
    }

    @Test
    fun theDescriptionHasTheFacts() {
        assertEquals(
            "人数：2名\n予約名：ヤマダ\nお店の電話番号：03-0000-0000\nAI（アイてる!?）が電話で取った予約です",
            event().description,
        )
    }

    @Test
    fun aChangedBookingSaysSo() {
        assertTrue(event(purpose = Purpose.CHANGE).description.endsWith("AI（アイてる!?）が電話で変更した予約です"))
    }

    @Test
    fun whatIsUnknownIsLeftOut() {
        val bare = ConfirmedBooking(start = booking.start)
        val e = CalendarRules.event(bare, "店", null, null, Purpose.NEW_BOOKING, reservationName = " ")
        assertEquals("AI（アイてる!?）が電話で取った予約です", e.description)
    }

    @Test
    fun theNameTheAiReportedIsNotWrittenOnlyTheOneApproved() {
        val said = ConfirmedBooking(start = booking.start, nameUsed = "店員の方の名前")
        assertFalse(CalendarRules.event(said, "店", null, null, Purpose.NEW_BOOKING).description.contains("店員"))
        assertTrue(CalendarRules.event(said, "店", null, null, Purpose.NEW_BOOKING, reservationName = "ヤマダ").description.contains("予約名：ヤマダ"))
    }

    @Test
    fun nothingFromTheConversationGoesIn() {
        val r = result()
        val e = event()
        val text = e.title + e.description
        for (said in listOf(r.outcome.summary, r.outcome.pendingItems.single(), booking.shopConditions.single(), booking.confirmedPreferences.single())) {
            assertFalse(said, text.contains(said))
        }
    }

    @Test
    fun printedEventsAndRowsNameNobody() {
        val text = event().toString() + google.toString()
        for (secret in listOf(shop, number, "ヤマダ", "taro@example.com")) assertFalse(secret, text.contains(secret))
    }

    @Test
    fun aKeptResultHasTheSameKey() {
        val kept = SavedResultJson.decode(SavedResultJson.encode(SavedResult(result(), RotepApp.Stage.L3, shop)))!!
        assertEquals(CalendarRules.key(result().outcome), CalendarRules.key(kept.result.outcome))
    }

    @Test
    fun theKeyNamesNothingButTheResult() {
        assertEquals("policy@${millis("2026-11-08T10:05:00.123Z")}", CalendarRules.key(result().outcome))
    }

    private val google = CalendarRow(5, "com.google", "taro@example.com", "taro@example.com", isPrimary = true, accessLevel = 700, syncEvents = true)

    @Test
    fun thePrimaryGoogleCalendarIsChosen() {
        val holidays = google.copy(id = 1, isPrimary = false, ownerAccount = "ja.japanese#holiday@group.v.calendar.google.com", accessLevel = 200)
        assertEquals(google, CalendarRules.choose(listOf(holidays, google)))
    }

    @Test
    fun anAccountsOwnCalendarIsItsPrimaryWhenTheColumnIsEmpty() {
        val row = google.copy(isPrimary = false)
        assertEquals(row, CalendarRules.choose(listOf(row)))
    }

    @Test
    fun otherKindsAreNotChosen() {
        assertNull(CalendarRules.choose(listOf(google.copy(accountType = "com.example"))))
        assertNull(CalendarRules.choose(listOf(google.copy(accountType = "LOCAL"))))
        assertNull(CalendarRules.choose(listOf(google.copy(isPrimary = false, ownerAccount = "shared@example.com"))))
        assertNull("cannot be written", CalendarRules.choose(listOf(google.copy(accessLevel = 200))))
        assertNull("would never reach Google", CalendarRules.choose(listOf(google.copy(syncEvents = false))))
        assertNull(CalendarRules.choose(emptyList()))
    }

    @Test
    fun withSeveralAccountsTheFirstAddedIsChosen() {
        val later = google.copy(id = 9, accountName = "work@example.com", ownerAccount = "work@example.com")
        assertEquals(google, CalendarRules.choose(listOf(later, google)))
    }

    @Test
    fun theSwitchStartsOff() {
        assertFalse(Settings().addToCalendar)
    }

    @Test
    fun theManifestDeclaresThePermissionsAskedFor() {
        // Unit tests run in the module's directory
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in CALENDAR_PERMISSIONS) assertTrue(p, manifest.contains("android:name=\"$p\""))
    }
}
