// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.incallagent.rotep.diag.DiagnosticsActivity
import io.github.incallagent.rotep.shell.adb.AdbSetup

/**
 * The few minutes of a Wireless debugging pairing, for the moment when the phone's Settings is in
 * front: the app must keep looking for the pairing port and be able to receive the code from the
 * notification, so that the pairing dialog in Settings stays open.
 *
 * A short foreground service from Android 14, which needs no extra permission and asks for what this
 * is: a user-started job of less than three minutes. It stops by itself after that time.
 */
class AdbPairingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeUp = Runnable { finishWith("時間切れです。診断の画面でもう一度「ペアリングを始める」を押してください") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AdbSetup.init(this)
        val notice = AdbPairingNotice.searching(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(AdbPairingNotice.ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(AdbPairingNotice.ID, notice)
            }
        } catch (t: RuntimeException) {
            AdbSetup.endSearch()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(timeUp)
        handler.postDelayed(timeUp, TIME_LIMIT_MS)
        AdbSetup.beginSearch()
        return START_NOT_STICKY
    }

    // Android 14 calls the first one, Android 15 and later the second. Both mean the same here.
    override fun onTimeout(startId: Int) = timedOut()

    override fun onTimeout(startId: Int, fgsType: Int) = timedOut()

    private fun timedOut() {
        finishWith("時間切れです。診断の画面でもう一度「ペアリングを始める」を押してください")
    }

    private fun finishWith(text: String) {
        AdbSetup.endSearch()
        AdbPairingNotice.showDone(this, text)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeUp)
        AdbSetup.endSearch()
        super.onDestroy()
    }

    private companion object {
        /** A little under the three minutes the system gives a short foreground service. */
        const val TIME_LIMIT_MS = 170_000L
    }
}

/** Takes the 6-digit code that the person typed into the notification. Reached only by the app's own PendingIntent. */
class AdbCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AdbSetup.init(context)
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbPairingNotice.INPUT_KEY)?.toString().orEmpty()
        AdbSetup.submitCode(code)
    }
}

/** The notification of a pairing: looking for the window, the input for the code, the outcome. */
object AdbPairingNotice {
    /** The notification of the foreground service. */
    internal const val ID = 20

    /** The outcome: not the service's id, because the service's notification goes when the service stops. */
    private const val OUTCOME_ID = 21
    internal const val INPUT_KEY = "pairing_code"
    private const val CHANNEL = "adb_pairing"

    internal fun searching(context: Context): Notification = builder(context)
        .setContentTitle("ペアリングの窓を探しています")
        .setContentText("設定 → 開発者向けオプション → ワイヤレスデバッグ →「ペア設定コードによるデバイスのペア設定」を開いてください")
        .setStyle(Notification.BigTextStyle().bigText("設定 → 開発者向けオプション → ワイヤレスデバッグ →「ペア設定コードによるデバイスのペア設定」を開いてください。見つかると、この通知にコードの入力欄が出ます。"))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    fun showCodeInput(context: Context, note: String = "設定の画面を閉じずに、ここに入力します") {
        val input = RemoteInput.Builder(INPUT_KEY).setLabel("6 桁のコード").build()
        val reply = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AdbCodeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_edit),
            "コードを入れる",
            reply,
        ).addRemoteInput(input).build()
        post(
            context,
            builder(context)
                .setContentTitle("6 桁のコードを入れてください")
                .setContentText(note)
                .setStyle(Notification.BigTextStyle().bigText(note))
                .setOngoing(true)
                .addAction(action)
                .build(),
        )
    }

    fun showWorking(context: Context) = post(
        context,
        builder(context).setContentTitle("確かめています…").setOngoing(true).setProgress(0, 0, true).build(),
    )

    fun showDone(context: Context, text: String) = post(
        context,
        builder(context).setContentTitle("ワイヤレスデバッグのペアリング").setContentText(text).setAutoCancel(true).build(),
        OUTCOME_ID,
    )

    private fun post(context: Context, notification: Notification, id: Int = ID) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    /** false: the person turned this app's notifications off, so the code cannot be typed into one. */
    fun canNotify(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(false)

    private fun builder(context: Context): Notification.Builder {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "ペアリング", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
    }
}
