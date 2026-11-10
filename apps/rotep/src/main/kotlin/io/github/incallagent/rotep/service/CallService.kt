// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.github.incallagent.rotep.MainActivity
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog

/**
 * Keeps the app in the foreground state while one delegated call runs, so the microphone (the practice)
 * and the AI connection keep working when the phone app or the home screen is in front.
 *
 * It must be started while the app is in front (before dialing): Android does not let a
 * microphone service start from the background, and placing a call puts the phone app in front.
 * Type: microphone (the practice, the app records) or phoneCall (real calls; MANAGE_OWN_CALLS is declared).
 * The one start from the background is the alarm of a call at a time the user chose (phoneCall),
 * which Android allows for an alarm clock ([startAwake]).
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getIntExtra(EXTRA_TYPE, 0) ?: 0
        if (type == 0) {
            // Restarted by the system without a call: nothing to keep alive
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), type)
            RotepLog.i("service", "foreground type=${typeName(type)}")
            // Before init, which can take longer than the wait for this report (the keystore)
            RotepApp.serviceUp()
            // A call at a time the user chose starts here, in a process that may be new
            RotepApp.init(this)
            RotepApp.serviceStarted()
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException, SecurityException (missing permission), ...
            RotepLog.w("service", "startForeground failed: ${t.javaClass.simpleName}")
            RotepApp.init(this)
            RotepApp.serviceFailed(t.javaClass.simpleName)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RotepLog.i("service", "stopped")
        releaseAwake()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "通話の代行", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("AI が電話の用件を進めています")
            .setContentText("開くと、状態と「AI を止めて本人に代わる」が出ます")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val EXTRA_TYPE = "foreground_type"
        private const val CHANNEL = "call"
        private const val NOTIFICATION_ID = 1
        private const val AWAKE_TAG = "rotep:scheduled_call"

        private var awake: PowerManager.WakeLock? = null

        /** Returns null, or the class of what kept the service from starting (e.g. the app is not in front): it goes to the log, and the message of an exception does not (ADR-021). */
        fun start(context: Context, type: Int): String? = try {
            context.startForegroundService(Intent(context, CallService::class.java).putExtra(EXTRA_TYPE, type))
            null
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }

        /**
         * [start] from the alarm of a call at a time the user chose (ADR-040): the CPU is kept on from
         * here, in the receiver, until the service ends or at most [awakeMs]. Nobody is looking at
         * the phone, and the alarm's own hold ends with the receiver.
         */
        fun startAwake(context: Context, type: Int, awakeMs: Long): String? {
            holdAwake(context, awakeMs)
            return start(context, type)?.also { releaseAwake() }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }

        @Synchronized
        private fun holdAwake(context: Context, awakeMs: Long) {
            releaseAwake()
            try {
                awake = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AWAKE_TAG)
                    .apply {
                        setReferenceCounted(false)
                        acquire(awakeMs)
                    }
            } catch (t: Throwable) {
                RotepLog.w("service", "wake lock failed: ${t.javaClass.simpleName}")
            }
        }

        @Synchronized
        private fun releaseAwake() {
            awake?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
            awake = null
        }

        fun typeName(type: Int) = when (type) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE -> "microphone"
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL -> "phoneCall"
            else -> type.toString()
        }
    }
}
