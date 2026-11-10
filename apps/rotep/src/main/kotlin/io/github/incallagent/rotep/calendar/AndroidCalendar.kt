// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.edit
import io.github.incallagent.rotep.call.CallSession
import java.util.concurrent.Executors

val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/** One worker of its own: the call's scheduler is shut down right after the result. */
fun bookingCalendar(context: Context, log: (String) -> Unit): BookingCalendar {
    val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "calendar").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())
    return BookingCalendar(AndroidDeviceCalendar(context), PrefsCalendarLedger(context), { worker.execute(it) }, { main.post(it) }, log)
}

/**
 * The phone's calendar provider. Events added here reach Google Calendar through the phone's own sync.
 * The permissions are checked by [BookingCalendar], which also handles them being taken back since.
 */
@SuppressLint("MissingPermission")
private class AndroidDeviceCalendar(context: Context) : DeviceCalendar {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    override fun hasPermission(): Boolean = CALENDAR_PERMISSIONS.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun calendars(): List<CalendarRow> =
        resolver.query(Calendars.CONTENT_URI, COLUMNS, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(CalendarRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0, c.getInt(5), c.getInt(6) != 0))
                }
            }
        }.orEmpty()

    override fun insert(calendarId: Long, event: BookingEvent): Long? {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, event.title)
            put(Events.DESCRIPTION, event.description)
            put(Events.DTSTART, event.startMillis)
            put(Events.DTEND, event.endMillis)
            put(Events.EVENT_TIMEZONE, event.timeZone)
            // Added without a look from the user, and it holds a name and a number: a shared calendar shows only "busy"
            put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
        }
        return resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId)
    }

    override fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    private companion object {
        // In the order calendars() reads them
        val COLUMNS = arrayOf(
            Calendars._ID,
            Calendars.ACCOUNT_TYPE,
            Calendars.ACCOUNT_NAME,
            Calendars.OWNER_ACCOUNT,
            Calendars.IS_PRIMARY,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.SYNC_EVENTS,
        )
    }
}

/** commit(): the record has to be on disk before the event is written. */
private class PrefsCalendarLedger(context: Context) : CalendarLedger {
    private val prefs = context.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    override fun save(record: CalendarRecord): Boolean = prefs.edit()
        .putString(KEY, record.key)
        .putString(STATE, record.state.name)
        .putString(FAILURE, record.failure?.name)
        .putLong(EVENT, record.eventId ?: NONE)
        .putLong(MINUTES, record.minutes?.toLong() ?: NONE)
        .commit()

    override fun load(): CalendarRecord? {
        val key = prefs.getString(KEY, null) ?: return null
        val state = CalendarState.entries.find { it.name == prefs.getString(STATE, null) } ?: return null
        return CalendarRecord(
            key,
            state,
            CalendarFailure.entries.find { it.name == prefs.getString(FAILURE, null) },
            prefs.getLong(EVENT, NONE).takeIf { it != NONE },
            prefs.getLong(MINUTES, NONE).takeIf { it != NONE }?.toInt(),
        )
    }

    override fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private companion object {
        const val KEY = "key"
        const val STATE = "state"
        const val FAILURE = "failure"
        const val EVENT = "event"
        const val MINUTES = "minutes"
        const val NONE = -1L
    }
}

/** The calendar app's own "new event" screen: it needs no permission, and the user saves it there. */
private fun insertIntent(event: BookingEvent): Intent = Intent(Intent.ACTION_INSERT, Events.CONTENT_URI)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
    .putExtra(Events.TITLE, event.title)
    .putExtra(Events.DESCRIPTION, event.description)

/** "カレンダーに追加". [context]: the screen's activity. */
fun openCalendarApp(context: Context, calendar: BookingCalendar, result: CallSession.Result, shopName: String?) {
    val event = calendar.eventFor(result, shopName) ?: return
    try {
        context.startActivity(insertIntent(event))
        calendar.opened(result)
    } catch (e: ActivityNotFoundException) {
        calendar.noCalendarApp()
    } catch (e: SecurityException) {
        calendar.noCalendarApp()
    }
}
