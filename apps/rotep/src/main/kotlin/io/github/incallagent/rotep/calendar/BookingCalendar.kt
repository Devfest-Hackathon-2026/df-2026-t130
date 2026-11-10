// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.calendar

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.CallSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class CalendarState {
    /** Not in the calendar: the switch was off, or adding failed ([CalendarRecord.failure]). */
    NOT_ADDED,

    /** About to write the event. If the app ended here, nobody knows whether the event exists. */
    ADDING,
    ADDED,

    /** The user took the event out again. */
    REMOVED,
}

/** Why a booking was not added, in the words of the result screen. */
enum class CalendarFailure(val text: String) {
    NO_PERMISSION("カレンダーの許可がないので、追加できませんでした"),
    NO_CALENDAR("この端末に Google のカレンダーが見つからないので、追加できませんでした"),
    ERROR("カレンダーに追加できませんでした"),
    UNKNOWN("カレンダーに追加できたか分かりません。カレンダーのアプリで確かめて、入っていなければ、ここから追加してください"),
}

/**
 * What is known about the calendar and the last kept result. Ids and a length only: never a shop, a
 * name or a number (ADR-036). [minutes]: the length asked for, needed if the user opens the calendar
 * app after the app has restarted.
 */
data class CalendarRecord(
    val key: String,
    val state: CalendarState,
    val failure: CalendarFailure? = null,
    val eventId: Long? = null,
    val minutes: Int? = null,
)

/** Keeps one [CalendarRecord]. A save is on disk when it returns true. */
interface CalendarLedger {
    fun save(record: CalendarRecord): Boolean

    fun load(): CalendarRecord?

    fun clear()
}

/** The phone's calendars. The calls may throw: SecurityException when the permission was taken back. */
interface DeviceCalendar {
    fun hasPermission(): Boolean

    fun calendars(): List<CalendarRow>

    /** The new event's id, or null. */
    fun insert(calendarId: Long, event: BookingEvent): Long?

    /** Nothing happens when the event is already gone. */
    fun delete(eventId: Long)
}

/** What the screens are told. [record] can be about another call: [calendarUi] checks. */
data class CalendarStatus(
    val record: CalendarRecord? = null,
    val undoing: Boolean = false,
    /** Undoing failed, or no app could open the event. Gone with the next action. */
    val problem: String? = null,
    /** The calendar app was opened for this result in this run; whether it was saved cannot be known. */
    val openedKey: String? = null,
)

/** What the settings screen found out about the phone's calendars. */
sealed interface CalendarCheck {
    data class Found(val account: String?) : CalendarCheck {
        override fun toString() = "Found"
    }

    data object NoPermission : CalendarCheck

    data object NoCalendar : CalendarCheck

    data object Failed : CalendarCheck
}

/** What to show beside the switch, or null. */
fun calendarCheckProblem(check: CalendarCheck): String? = when (check) {
    is CalendarCheck.Found -> null
    CalendarCheck.NoPermission -> "カレンダーの許可がありません。端末の設定の「アプリ」で「${UserMessages.APP_NAME}」を選び、「権限」でカレンダーを許可してください"
    CalendarCheck.NoCalendar -> "この端末に Google のカレンダーが見つかりません。Google アカウントを追加して、カレンダーの同期をオンにしてください"
    CalendarCheck.Failed -> "カレンダーを読めませんでした。少し待ってから試してください"
}

/** The calendar part of the result screen. It says nothing about the booking itself. */
sealed interface CalendarUi {
    data object Hidden : CalendarUi

    data object Adding : CalendarUi

    data class Added(val undoing: Boolean = false, val problem: String? = null) : CalendarUi

    data class CanAdd(val note: String? = null) : CalendarUi
}

private const val REMOVED_NOTE = "カレンダーから消しました"
private const val OPENED_NOTE = "カレンダーの画面を開きました。保存すると予定が入ります"
private const val UNDO_FAILED = "取り消せませんでした。カレンダーのアプリで消してください"
private const val NO_CALENDAR_APP = "カレンダーのアプリが見つかりませんでした"

/** Only the record of the result on the screen counts: an older call's "取り消す" must not appear under this one. */
fun calendarUi(result: CallSession.Result?, status: CalendarStatus): CalendarUi {
    val record = status.record?.takeIf { result != null && it.key == CalendarRules.key(result.outcome) } ?: return CalendarUi.Hidden
    return when (record.state) {
        CalendarState.ADDING -> CalendarUi.Adding
        CalendarState.ADDED -> CalendarUi.Added(status.undoing, status.problem)
        CalendarState.NOT_ADDED, CalendarState.REMOVED -> CalendarUi.CanAdd(
            status.problem
                ?: OPENED_NOTE.takeIf { status.openedKey == record.key }
                ?: record.failure?.text
                ?: REMOVED_NOTE.takeIf { record.state == CalendarState.REMOVED },
        )
    }
}

/**
 * Puts a booking in the phone's calendar and remembers that it did (ADR-039).
 *
 * The record on disk is what counts: it is written before the event, so a result is added at most
 * once, also if the app ends halfway. [status] is its copy for the screens. Everything that touches
 * the disk or the provider runs on [onWorker], one at a time and never on the main thread.
 */
class BookingCalendar(
    private val device: DeviceCalendar,
    private val ledger: CalendarLedger,
    private val onWorker: (() -> Unit) -> Unit,
    private val onMain: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow(CalendarStatus(restored()))
    val status: StateFlow<CalendarStatus> = _status

    /** What only the call knows, for the calendar app's screen. In memory only: the number is never written down. */
    private class FromCall(val key: String, val shopName: String, val phoneNumber: String, val timeZone: String, val reservationName: String?)

    @Volatile
    private var fromCall: FromCall? = null

    /** An add that never finished is not tried again: the event may be there. */
    private fun restored(): CalendarRecord? = ledger.load()?.let {
        if (it.state == CalendarState.ADDING) it.copy(state = CalendarState.NOT_ADDED, failure = CalendarFailure.UNKNOWN) else it
    }

    /**
     * A call whose result is kept has ended; only then, so that the one record follows the one kept
     * result. From the call's thread, ahead of what ends the call: must not throw or wait.
     */
    fun callEnded(result: CallSession.Result, policy: DelegationPolicy, autoAdd: Boolean) {
        try {
            if (!CalendarRules.qualifies(inCall = true, result)) {
                forget()
                return
            }
            val booking = result.outcome.booking ?: return
            val key = CalendarRules.key(result.outcome)
            val shop = policy.target.businessName
            val number = policy.target.phoneNumber
            val minutes = policy.request.durationMinutes
            val name = policy.disclosure.reservationName
            val event = CalendarRules.event(booking, shop, number, minutes, result.purpose, policy.timeZone, name)
            fromCall = FromCall(key, shop, number, policy.timeZone, name)
            val first = CalendarRecord(key, if (autoAdd) CalendarState.ADDING else CalendarState.NOT_ADDED, minutes = minutes)
            // Before the result screen opens, so that it never shows the button of a result being added
            _status.value = CalendarStatus(first)
            onWorker { if (autoAdd) add(first, event) else keep(first) }
        } catch (t: Throwable) {
            log("result not handled (${t.javaClass.simpleName})")
        }
    }

    /** Worker. */
    private fun keep(record: CalendarRecord) {
        try {
            if (!ledger.save(record)) log("record not saved")
        } catch (t: Throwable) {
            log("record not saved (${t.javaClass.simpleName})")
        }
    }

    /** Worker. */
    private fun add(first: CalendarRecord, event: BookingEvent) {
        val done = try {
            ledger.load()?.takeIf { it.key == first.key } ?: addOnce(first, event)
        } catch (e: SecurityException) {
            notAdded(first, CalendarFailure.NO_PERMISSION)
        } catch (t: Throwable) {
            log("add failed (${t.javaClass.simpleName})")
            notAdded(first, CalendarFailure.ERROR)
        }
        keep(done)
        _status.update { it.copy(record = done) }
    }

    private fun addOnce(first: CalendarRecord, event: BookingEvent): CalendarRecord {
        if (!ledger.save(first)) return notAdded(first, CalendarFailure.ERROR)
        if (!device.hasPermission()) return notAdded(first, CalendarFailure.NO_PERMISSION)
        val calendar = CalendarRules.choose(device.calendars()) ?: return notAdded(first, CalendarFailure.NO_CALENDAR)
        val id = device.insert(calendar.id, event) ?: return notAdded(first, CalendarFailure.ERROR)
        log("added")
        return first.copy(state = CalendarState.ADDED, eventId = id)
    }

    private fun notAdded(first: CalendarRecord, why: CalendarFailure): CalendarRecord {
        log("not added ($why)")
        return first.copy(state = CalendarState.NOT_ADDED, failure = why)
    }

    /** "取り消す": takes out the event this app added. */
    fun undo() {
        val now = _status.value
        val record = now.record?.takeIf { it.state == CalendarState.ADDED && !now.undoing } ?: return
        val id = record.eventId ?: return
        _status.update { it.copy(undoing = true, problem = null) }
        onWorker {
            val removed = try {
                device.delete(id)
                log("removed")
                true
            } catch (t: Throwable) {
                log("not removed (${t.javaClass.simpleName})")
                false
            }
            if (removed) {
                val done = record.copy(state = CalendarState.REMOVED, eventId = null)
                keep(done)
                _status.update { it.copy(record = done, undoing = false) }
            } else {
                _status.update { it.copy(undoing = false, problem = UNDO_FAILED) }
            }
        }
    }

    /** The event for the calendar app's own screen: this call's details, or what a kept result still has. */
    fun eventFor(result: CallSession.Result, shopName: String?): BookingEvent? {
        val booking = result.outcome.booking ?: return null
        val key = CalendarRules.key(result.outcome)
        val call = fromCall?.takeIf { it.key == key }
        val minutes = _status.value.record?.takeIf { it.key == key }?.minutes
        return CalendarRules.event(booking, call?.shopName ?: shopName, call?.phoneNumber, minutes, result.purpose, call?.timeZone ?: CalendarRules.DEFAULT_TIME_ZONE, reservationName = call?.reservationName)
    }

    /** The calendar app's screen opened. */
    fun opened(result: CallSession.Result) {
        _status.update { it.copy(openedKey = CalendarRules.key(result.outcome), problem = null) }
    }

    fun noCalendarApp() {
        _status.update { it.copy(problem = NO_CALENDAR_APP) }
    }

    fun hasPermission(): Boolean = device.hasPermission()

    /** Which calendar would be used; [done] runs on the main thread. */
    fun check(done: (CalendarCheck) -> Unit) {
        onWorker {
            val found = try {
                when {
                    !device.hasPermission() -> CalendarCheck.NoPermission
                    else -> CalendarRules.choose(device.calendars())?.let { CalendarCheck.Found(it.accountName) } ?: CalendarCheck.NoCalendar
                }
            } catch (e: SecurityException) {
                CalendarCheck.NoPermission
            } catch (t: Throwable) {
                log("check failed (${t.javaClass.simpleName})")
                CalendarCheck.Failed
            }
            onMain { done(found) }
        }
    }

    /** The kept result is gone, or the new one has nothing to do with the calendar. The event, if any, stays. */
    fun forget() {
        fromCall = null
        _status.value = CalendarStatus()
        onWorker {
            try {
                ledger.clear()
            } catch (t: Throwable) {
                log("record not cleared (${t.javaClass.simpleName})")
            }
        }
    }
}
