// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.calendar

import io.github.incallagent.core.policy.BookingRequest
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.CallTarget
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.Disclosure
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class BookingCalendarTest {
    // Everything is fictitious
    private val shop = "レストラン・サンプル"
    private val number = "03-0000-0000"
    private val name = "ヤマダ"
    private val account = "taro@example.com"

    private inner class FakeDevice : DeviceCalendar {
        var permission = true
        var rows = listOf(CalendarRow(5, "com.google", account, account, isPrimary = true, accessLevel = 700, syncEvents = true))
        var newId: Long? = 7
        var insertFails: Throwable? = null
        var deleteFails: Throwable? = null
        val inserted = mutableListOf<Pair<Long, BookingEvent>>()
        val deleted = mutableListOf<Long>()

        /** What the record said at the moment of each insert. */
        val recordAtInsert = mutableListOf<CalendarState?>()

        override fun hasPermission() = permission

        override fun calendars() = rows

        override fun insert(calendarId: Long, event: BookingEvent): Long? {
            insertFails?.let { throw it }
            recordAtInsert += ledger.record?.state
            inserted += calendarId to event
            return newId
        }

        override fun delete(eventId: Long) {
            deleteFails?.let { throw it }
            deleted += eventId
        }
    }

    private class FakeLedger : CalendarLedger {
        var record: CalendarRecord? = null
        var writable = true

        override fun save(record: CalendarRecord): Boolean {
            if (writable) this.record = record
            return writable
        }

        override fun load() = record

        override fun clear() {
            record = null
        }
    }

    private val device = FakeDevice()
    private val ledger = FakeLedger()
    private val logs = mutableListOf<String>()

    /** What the worker has been given and has not run yet, in [queued] mode. */
    private val waiting = ArrayDeque<() -> Unit>()
    private var queued = false

    private fun newCalendar() = BookingCalendar(device, ledger, { if (queued) waiting.addLast(it) else it() }, { it() }, { logs += it })

    private fun drain() {
        while (waiting.isNotEmpty()) waiting.removeFirst()()
    }

    private val policy = DelegationPolicy(
        id = "policy",
        createdAt = Instant.EPOCH,
        target = CallTarget(shop, number),
        purpose = Purpose.NEW_BOOKING,
        request = BookingRequest(durationMinutes = 90),
        disclosure = Disclosure(reservationName = name),
    )

    private fun result(
        status: OutcomeStatus = OutcomeStatus.BOOKED,
        booked: Boolean = true,
        endedAt: Instant = Instant.parse("2026-11-08T10:05:00Z"),
    ) = CallSession.Result(
        outcome = CallOutcome(
            policyId = policy.id,
            status = status,
            booking = if (booked) ConfirmedBooking(LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, nameUsed = name) else null,
            summary = "-",
            endedAt = endedAt,
        ),
        violations = emptyList(),
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "-",
        answered = true,
        talkMs = 60_000,
    )

    private val booked = result()

    private fun BookingCalendar.ui(r: CallSession.Result = booked) = calendarUi(r, status.value)

    private fun failedWith(why: CalendarFailure) {
        assertTrue(device.inserted.isEmpty())
        assertEquals(CalendarState.NOT_ADDED, ledger.record?.state)
        assertEquals(why, ledger.record?.failure)
    }

    @Test
    fun aBookingIsAddedAndRemembered() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        assertEquals(1, device.inserted.size)
        val (calendarId, event) = device.inserted.single()
        assertEquals(5L, calendarId)
        assertEquals("レストラン・サンプルの予約", event.title)
        assertEquals(event.startMillis + 90 * 60_000L, event.endMillis)
        assertTrue(event.description.contains(number))
        assertEquals(CalendarRecord(CalendarRules.key(booked.outcome), CalendarState.ADDED, eventId = 7, minutes = 90), ledger.record)
        assertEquals(CalendarUi.Added(), c.ui())
    }

    @Test
    fun theRecordIsOnDiskBeforeTheEventIsWritten() {
        newCalendar().callEnded(booked, policy, autoAdd = true)
        assertEquals(listOf<CalendarState?>(CalendarState.ADDING), device.recordAtInsert)
    }

    @Test
    fun theScreenKnowsBeforeTheWorkerRuns() {
        queued = true
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        assertEquals(CalendarUi.Adding, c.ui())
        assertNull(ledger.record)
        // The screen's copy must not stop the worker: only the record on disk counts
        drain()
        assertEquals(1, device.inserted.size)
        assertEquals(CalendarUi.Added(), c.ui())
    }

    @Test
    fun theSameResultIsNeverAddedTwice() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        c.callEnded(booked, policy, autoAdd = true)
        assertEquals(1, device.inserted.size)
        assertEquals(CalendarUi.Added(), c.ui())
    }

    @Test
    fun readingTheResultAgainAfterARestartAddsNothing() {
        newCalendar().callEnded(booked, policy, autoAdd = true)
        val again = newCalendar()
        assertEquals(CalendarUi.Added(), again.ui())
        assertEquals(1, device.inserted.size)
    }

    @Test
    fun theNextCallsBookingIsAddedToo() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        val next = result(endedAt = Instant.parse("2026-11-09T10:05:00Z"))
        c.callEnded(next, policy, autoAdd = true)
        assertEquals(2, device.inserted.size)
        assertEquals(CalendarUi.Hidden, c.ui(booked))
        assertEquals(CalendarUi.Added(), c.ui(next))
    }

    @Test
    fun withTheSwitchOffItOnlyOffersTheButton() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = false)
        assertTrue(device.inserted.isEmpty())
        assertEquals(CalendarUi.CanAdd(), c.ui())
        assertEquals(CalendarRecord(CalendarRules.key(booked.outcome), CalendarState.NOT_ADDED, minutes = 90), ledger.record)
    }

    @Test
    fun aResultWithoutABookingLeavesNoRecord() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        val other = result(status = OutcomeStatus.NOT_REACHED, booked = false, endedAt = Instant.parse("2026-11-09T10:05:00Z"))
        c.callEnded(other, policy, autoAdd = true)
        assertEquals(1, device.inserted.size)
        assertNull(ledger.record)
        assertEquals(CalendarUi.Hidden, c.ui(other))
    }

    @Test
    fun aResultWithoutItsOwnRecordShowsNothing() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        val practice = result(endedAt = Instant.parse("2026-11-09T10:05:00Z"))
        assertEquals("not the 取り消す of another call", CalendarUi.Hidden, c.ui(practice))
        assertEquals(CalendarUi.Hidden, calendarUi(null, c.status.value))
    }

    @Test
    fun withoutThePermissionNothingIsWritten() {
        device.permission = false
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        failedWith(CalendarFailure.NO_PERMISSION)
        assertEquals(CalendarUi.CanAdd(CalendarFailure.NO_PERMISSION.text), c.ui())
    }

    @Test
    fun aPermissionTakenBackInTheMiddleIsToldApart() {
        device.insertFails = SecurityException("no")
        newCalendar().callEnded(booked, policy, autoAdd = true)
        failedWith(CalendarFailure.NO_PERMISSION)
    }

    @Test
    fun withoutAGoogleCalendarNothingIsWritten() {
        device.rows = emptyList()
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        failedWith(CalendarFailure.NO_CALENDAR)
    }

    @Test
    fun aFailureStaysInTheCalendarPart() {
        device.insertFails = IllegalStateException("provider died")
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        failedWith(CalendarFailure.ERROR)
        assertEquals(CalendarUi.CanAdd(CalendarFailure.ERROR.text), c.ui())
    }

    @Test
    fun anInsertWithoutAnAnswerIsAFailure() {
        device.newId = null
        newCalendar().callEnded(booked, policy, autoAdd = true)
        assertEquals(CalendarState.NOT_ADDED, ledger.record?.state)
        assertEquals(CalendarFailure.ERROR, ledger.record?.failure)
    }

    @Test
    fun withoutARecordOnDiskNothingIsWritten() {
        ledger.writable = false
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        assertTrue(device.inserted.isEmpty())
        assertEquals(CalendarUi.CanAdd(CalendarFailure.ERROR.text), c.ui())
    }

    @Test
    fun nothingEscapesToTheCall() {
        // Stands for anything unexpected on the call's thread (the validator refuses this zone before a call)
        val c = newCalendar()
        c.callEnded(booked, policy.copy(timeZone = "Mars/Base"), autoAdd = true)
        assertTrue(device.inserted.isEmpty())
        assertEquals(CalendarUi.Hidden, c.ui())
    }

    @Test
    fun anAddThatNeverFinishedIsNotTriedAgain() {
        ledger.record = CalendarRecord(CalendarRules.key(booked.outcome), CalendarState.ADDING, minutes = 90)
        val c = newCalendar()
        assertEquals(CalendarUi.CanAdd(CalendarFailure.UNKNOWN.text), c.ui())
        assertTrue(device.inserted.isEmpty())
    }

    @Test
    fun anEventCanBeTakenOutAgain() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        c.undo()
        assertEquals(listOf(7L), device.deleted)
        assertEquals(CalendarState.REMOVED, ledger.record?.state)
        assertNull(ledger.record?.eventId)
        assertEquals(CalendarUi.CanAdd("カレンダーから消しました"), c.ui())
    }

    @Test
    fun anEventTakenOutStaysOutAfterARestart() {
        val first = newCalendar()
        first.callEnded(booked, policy, autoAdd = true)
        first.undo()
        assertEquals(CalendarUi.CanAdd("カレンダーから消しました"), newCalendar().ui())
        assertEquals(1, device.inserted.size)
    }

    @Test
    fun undoingWhileUndoingDoesNothing() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        queued = true
        c.undo()
        assertEquals(CalendarUi.Added(undoing = true), c.ui())
        c.undo()
        drain()
        assertEquals(listOf(7L), device.deleted)
    }

    @Test
    fun aFailedUndoLeavesItAdded() {
        device.deleteFails = SecurityException("no")
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        c.undo()
        assertEquals(CalendarState.ADDED, ledger.record?.state)
        assertEquals(CalendarUi.Added(problem = "取り消せませんでした。カレンダーのアプリで消してください"), c.ui())
    }

    @Test
    fun onlyAnAddedEventCanBeTakenOut() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = false)
        c.undo()
        assertTrue(device.deleted.isEmpty())
    }

    @Test
    fun theCalendarAppGetsWhatTheCallKnew() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = false)
        val event = c.eventFor(booked, "別の名前")!!
        assertEquals("レストラン・サンプルの予約", event.title)
        assertTrue(event.description.contains(number))
        assertEquals(event.startMillis + 90 * 60_000L, event.endMillis)
    }

    @Test
    fun afterARestartTheCalendarAppGetsNoNumberButTheLength() {
        newCalendar().callEnded(booked, policy, autoAdd = false)
        val event = newCalendar().eventFor(booked, shop)!!
        assertEquals("レストラン・サンプルの予約", event.title)
        assertFalse(event.description.contains(number))
        assertEquals(event.startMillis + 90 * 60_000L, event.endMillis)
    }

    @Test
    fun aResultWithNoBookingHasNoEvent() {
        assertNull(newCalendar().eventFor(result(status = OutcomeStatus.FAILED, booked = false), shop))
    }

    @Test
    fun openingTheCalendarAppIsNoted() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = false)
        c.opened(booked)
        assertEquals(CalendarUi.CanAdd("カレンダーの画面を開きました。保存すると予定が入ります"), c.ui())
    }

    @Test
    fun noCalendarAppIsSaid() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = false)
        c.noCalendarApp()
        assertEquals(CalendarUi.CanAdd("カレンダーのアプリが見つかりませんでした"), c.ui())
    }

    @Test
    fun forgettingClearsTheRecordButNotTheEvent() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        c.forget()
        assertNull(ledger.record)
        assertTrue(device.deleted.isEmpty())
        assertEquals(CalendarUi.Hidden, c.ui())
    }

    private fun checked(): CalendarCheck {
        var answer: CalendarCheck? = null
        newCalendar().check { answer = it }
        return answer!!
    }

    @Test
    fun theCheckSaysWhereEventsGo() {
        assertEquals(CalendarCheck.Found(account), checked())
        assertNull(calendarCheckProblem(checked()))
    }

    @Test
    fun theCheckWithoutThePermission() {
        device.permission = false
        assertEquals(CalendarCheck.NoPermission, checked())
        assertTrue(calendarCheckProblem(checked())!!.contains("許可"))
    }

    @Test
    fun theCheckWithoutAGoogleCalendar() {
        device.rows = emptyList()
        assertEquals(CalendarCheck.NoCalendar, checked())
        assertTrue(calendarCheckProblem(checked())!!.contains("Google のカレンダーが見つかりません"))
    }

    @Test
    fun theCheckThatFails() {
        val broken = object : DeviceCalendar by device {
            override fun calendars(): List<CalendarRow> = throw IllegalStateException("no provider")
        }
        var answer: CalendarCheck? = null
        BookingCalendar(broken, ledger, { it() }, { it() }).check { answer = it }
        assertEquals(CalendarCheck.Failed, answer)
    }

    @Test
    fun theCheckAnswersOnTheMainThread() {
        val onMain = mutableListOf<() -> Unit>()
        var answer: CalendarCheck? = null
        BookingCalendar(device, ledger, { it() }, { onMain += it }).check { answer = it }
        assertNull(answer)
        onMain.single()()
        assertEquals(CalendarCheck.Found(account), answer)
    }

    @Test
    fun theLogNamesNobody() {
        val c = newCalendar()
        c.callEnded(booked, policy, autoAdd = true)
        c.undo()
        device.insertFails = IllegalStateException("$shop $number")
        c.callEnded(result(endedAt = Instant.parse("2026-11-09T10:05:00Z")), policy, autoAdd = true)
        assertTrue(logs.isNotEmpty())
        for (line in logs) {
            for (secret in listOf(shop, number, name, account)) assertFalse(line, line.contains(secret))
        }
    }
}
