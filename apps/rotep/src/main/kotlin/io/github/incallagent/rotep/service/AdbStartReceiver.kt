// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.service

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.diag.DiagnosticsActivity
import io.github.incallagent.rotep.schedule.ScheduleNotices
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.adb.AdbNotice
import io.github.incallagent.rotep.shell.adb.AdbSetup

/**
 * After a restart or an update of the app, and for the "起動" button of the notifications:
 *  - tells the person if the call at a time they chose was lost (the alarm is gone after a restart);
 *  - after a restart, starts the helper once Wi-Fi is there (the system delivers that as a broadcast,
 *    so no foreground service is needed, which is not allowed from BOOT_COMPLETED for most types);
 *  - after an update, says that the helper has to be started again.
 *
 * Not exported: only the system (restart, update) and the app's own PendingIntents reach it.
 */
class AdbStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        HelperClient.init(app)
        AdbSetup.init(app)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> inBackground {
                guarded("schedule") { reportLostScheduledCall(app) }
                guarded("restart") { AdbSetup.onRestarted(app) }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> inBackground {
                guarded("schedule") { reportLostScheduledCall(app) }
                guarded("update") { AdbSetup.onUpdated(app) }
            }
            ACTION_WIFI -> startInBackground(byUser = false)
            ACTION_START -> startInBackground(byUser = true)
            ACTION_PUT_BACK -> inBackground { guarded("restore") { AdbSetup.restoreNow() } }
        }
    }

    /** Keeps the broadcast open while the work runs: the process may exist only for this. */
    private fun startInBackground(byUser: Boolean) {
        val pending = goAsync()
        AdbSetup.startFromBroadcast(byUser) { pending.finish() }
    }

    /** A receiver's work off the main thread, with the broadcast kept open until it ends. */
    private fun inBackground(job: () -> Unit) {
        val pending = goAsync()
        AdbSetup.onWorker {
            try {
                job()
            } finally {
                pending.finish()
            }
        }
    }

    /** One step of the work: a failure is logged and the next step still runs, and the process does not end. */
    private fun guarded(step: String, job: () -> Unit) {
        try {
            job()
        } catch (t: Throwable) {
            RotepLog.w("adb", "$step failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Without RotepApp.init: that also reads the saved API keys, and a keystore that is still busy right
     * after a restart must not make them look broken. This reads only the scheduled call.
     */
    private fun reportLostScheduledCall(app: Application) {
        RotepApp.lostScheduledCallMessage(app)?.let { ScheduleNotices.notPlaced(app, it) }
    }

    internal companion object {
        const val ACTION_WIFI = "io.github.incallagent.rotep.adb.WIFI"
        const val ACTION_START = "io.github.incallagent.rotep.adb.START"
        const val ACTION_PUT_BACK = "io.github.incallagent.rotep.adb.PUT_BACK"
        private const val WIFI_REQUEST = 1
        private const val PUT_BACK_REQUEST = 3
        private const val RETRY_REQUEST = 4

        /** Longer than a call normally lasts: if the switches are still on by then, they are looked at. */
        private const val PUT_BACK_CHECK_MS = 10 * 60 * 1000L

        fun wifiCallback(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            WIFI_REQUEST,
            Intent(context, AdbStartReceiver::class.java).setAction(ACTION_WIFI),
            // The system adds the network to this intent, so it has to be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        /** A broadcast when Wi-Fi is there (at once, when it already is). */
        fun waitForWifi(context: Context) {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, wifiCallback(context))
        }

        /** Tries the start again later, the same way as when Wi-Fi comes up. */
        fun tryAgainLater(context: Context, delayMs: Long) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    PendingIntent.getBroadcast(
                        context,
                        RETRY_REQUEST,
                        Intent(context, AdbStartReceiver::class.java).setAction(ACTION_WIFI),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }.onFailure { RotepLog.w("adb", "no alarm: ${it.javaClass.simpleName}") }
        }

        private fun putBackIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            PUT_BACK_REQUEST,
            Intent(context, AdbStartReceiver::class.java).setAction(ACTION_PUT_BACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** For when the app dies during a call: nothing else would put the debugging switches back. */
        fun checkPutBackLater(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + PUT_BACK_CHECK_MS,
                    putBackIntent(context),
                )
            }.onFailure { RotepLog.w("adb", "no alarm: ${it.javaClass.simpleName}") }
        }

        fun cancelPutBackCheck(context: Context) {
            runCatching { context.getSystemService(AlarmManager::class.java).cancel(putBackIntent(context)) }
        }

        fun stopWaitingForWifi(context: Context) {
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback(context)) }
        }
    }
}

/** The notification about the start of the helper: one at a time, replaced by the next. */
internal object AdbStartNotice {
    private const val ID = 22
    private const val CHANNEL = "adb_start"
    private const val QUIET_CHANNEL = "adb_start_quiet"
    private const val START_REQUEST = 2

    fun show(context: Context, notice: AdbNotice, silent: Boolean = false) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            // [silent]: a channel without sound, for example while a call is going on.
            val channel = if (silent) QUIET_CHANNEL else CHANNEL
            val importance = if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            manager.createNotificationChannel(NotificationChannel(channel, if (silent) "手伝い役の起動（音なし）" else "手伝い役の起動", importance))
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, DiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(Notification.BigTextStyle().bigText(notice.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                // The same notice can be sent again when the Wi-Fi changes: once is enough to make a sound.
                .setOnlyAlertOnce(true)
            if (notice.startAction) {
                val start = PendingIntent.getBroadcast(
                    context,
                    START_REQUEST,
                    Intent(context, AdbStartReceiver::class.java).setAction(AdbStartReceiver.ACTION_START),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_media_play), "起動", start).build())
            }
            notice.timeoutMs?.let { builder.setTimeoutAfter(it) }
            manager.notify(ID, builder.build())
        }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}
