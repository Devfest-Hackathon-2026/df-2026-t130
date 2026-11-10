// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.incallagent.rotep.MainActivity
import java.time.Instant

/**
 * The two alarms of a scheduled call (ADR-040). The call itself is an alarm clock: it wakes the phone
 * out of Doze and lets the app start its foreground service from the background. The notice one
 * minute before is an ordinary exact alarm, so that the next alarm the phone shows is the time of the
 * call. An alarm clock shows in the status bar and on the lock screen.
 */
class AndroidScheduleAlarms(context: Context) : ScheduleAlarms {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AlarmManager::class.java)

    override fun arm(noticeAt: Instant, fireAt: Instant): String? {
        if (!manager.canScheduleExactAlarms()) return NOT_ALLOWED
        return try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, noticeAt.toEpochMilli(), alarm(NOTICE))
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt.toEpochMilli(), showApp()), alarm(FIRE))
            null
        } catch (_: SecurityException) {
            NOT_ALLOWED
        }
    }

    override fun cancel() {
        for (which in listOf(NOTICE, FIRE)) {
            val pending = alarm(which)
            manager.cancel(pending)
            // So that [armed] does not find it: cancelling the alarm leaves the PendingIntent
            pending.cancel()
        }
    }

    override fun armed(): Boolean =
        PendingIntent.getBroadcast(app, FIRE.code, FIRE.intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null

    private fun alarm(which: Which): PendingIntent =
        PendingIntent.getBroadcast(app, which.code, which.intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** What the system opens from the alarm it shows. */
    private fun showApp(): PendingIntent = PendingIntent.getActivity(
        app,
        0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private inner class Which(val code: Int, val action: String) {
        fun intent() = Intent(app, ScheduleReceiver::class.java).setAction(action)
    }

    private val NOTICE = Which(1, ScheduleReceiver.ACTION_NOTICE)
    private val FIRE = Which(2, ScheduleReceiver.ACTION_FIRE)

    private companion object {
        const val NOT_ALLOWED = "時刻どおりに起こす許可がないので、予約しませんでした。端末の設定の「アラームとリマインダー」でこのアプリを許可してください"
    }
}
