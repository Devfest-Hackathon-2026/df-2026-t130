// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.calendar

import android.provider.CalendarContract
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.app.keepsResult
import io.github.incallagent.rotep.call.CallSession
import java.time.Instant
import java.time.ZoneId

/** One event to put in the calendar. toString() says nothing that could name a shop or a person (ADR-021). */
data class BookingEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timeZone: String,
    val description: String,
) {
    override fun toString() = "BookingEvent(start=$startMillis, end=$endMillis)"
}

/** One of the phone's calendars. */
data class CalendarRow(
    val id: Long,
    val accountType: String?,
    val accountName: String?,
    val ownerAccount: String?,
    val isPrimary: Boolean,
    val accessLevel: Int,
    val syncEvents: Boolean,
) {
    override fun toString() = "CalendarRow(id=$id)"
}

/** What goes in the calendar and where. No Android calls, so unit tests see every rule (ADR-039). */
object CalendarRules {
    private const val GOOGLE_ACCOUNT = "com.google"
    private const val DEFAULT_MINUTES = 120
    const val DEFAULT_TIME_ZONE = "Asia/Tokyo"

    /**
     * Only a real call, only a booking made or moved, and only when the check against the policy
     * found nothing: what the AI misheard must not end up in the user's calendar (ADR-024).
     */
    fun qualifies(inCall: Boolean, result: CallSession.Result): Boolean {
        val status = result.outcome.status
        val booked = status == OutcomeStatus.BOOKED || status == OutcomeStatus.BOOKED_ALTERNATIVE
        val forABooking = result.purpose == Purpose.NEW_BOOKING || result.purpose == Purpose.CHANGE
        val booking = result.outcome.booking ?: return false
        return keepsResult(inCall, result) && booked && forABooking && result.violations.isEmpty() && possibleDate(booking, result.outcome.endedAt)
    }

    /**
     * Whether the date can be one a shop gave on this call. With "範囲外も可" nothing else looks at it,
     * and a wrong year is only noticed in the calendar. From the day before the call (the clock of
     * the phone may be off) to a year after it.
     */
    private fun possibleDate(booking: ConfirmedBooking, callEnded: Instant): Boolean {
        val day = booking.start.toLocalDate()
        val callDay = callEnded.atZone(ZoneId.of(DEFAULT_TIME_ZONE)).toLocalDate()
        return !day.isBefore(callDay.minusDays(1)) && !day.isAfter(callDay.plusYears(1))
    }

    /** Which result a record is about. Names nothing. The kept result comes back with the same value. */
    fun key(outcome: CallOutcome): String = "${outcome.policyId}@${outcome.endedAt.toEpochMilli()}"

    /**
     * The event for [booking]. [phoneNumber] null: the number is not known any more, because a kept
     * result never holds one (ADR-036). Only facts: the summary and the shop's conditions come out
     * of the conversation, which stays out of the calendar. [reservationName] is the one the user
     * approved: the name the AI reports is its own words, and may not be that name.
     */
    fun event(
        booking: ConfirmedBooking,
        shopName: String?,
        phoneNumber: String?,
        minutes: Int?,
        purpose: Purpose,
        timeZone: String = DEFAULT_TIME_ZONE,
        reservationName: String? = null,
    ): BookingEvent {
        val start = booking.start.atZone(ZoneId.of(timeZone))
        val end = start.plusMinutes((minutes?.takeIf { it > 0 } ?: DEFAULT_MINUTES).toLong())
        val done = if (purpose == Purpose.CHANGE) "変更した" else "取った"
        val lines = listOfNotNull(
            booking.partySize?.let { "人数：${it}名" },
            reservationName?.takeIf { it.isNotBlank() }?.let { "予約名：$it" },
            phoneNumber?.takeIf { it.isNotBlank() }?.let { "お店の電話番号：$it" },
            "AI（${UserMessages.APP_NAME}）が電話で${done}予約です",
        )
        return BookingEvent(
            title = shopName?.takeIf { it.isNotBlank() }?.let { "${it}の予約" } ?: "予約",
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = end.toInstant().toEpochMilli(),
            timeZone = timeZone,
            description = lines.joinToString("\n"),
        )
    }

    /**
     * The calendar to write to: the primary calendar of a Google account on this phone, that syncs
     * and can be written to. With several accounts, the one added first (the settings show which).
     *
     * Decided here and not in the query: the provider computes IS_PRIMARY for what it returns (the
     * account's own calendar counts when the column is empty), while a WHERE clause sees the raw column.
     */
    fun choose(rows: List<CalendarRow>): CalendarRow? = rows
        .filter { it.accountType == GOOGLE_ACCOUNT }
        .filter { it.isPrimary || (it.accountName != null && it.accountName == it.ownerAccount) }
        .filter { it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && it.syncEvents }
        .minByOrNull { it.id }
}
