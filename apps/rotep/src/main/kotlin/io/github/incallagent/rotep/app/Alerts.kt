// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.incallagent.rotep.MainActivity

/** Tells the user what needs them: the AI stopped, a call is over, a request arrived. */
object Alerts {
    private val main = Handler(Looper.getMainLooper())

    private const val NOTIFICATION_ID = 2
    private const val REQUEST_NOTIFICATION_ID = 3

    /** true while the main screen can be seen (MainActivity between onStart and onStop). */
    @Volatile
    var mainScreenShown = false

    /**
     * Two beeps: in a call they go to the call audio (the earpiece), otherwise to notifications. In
     * a call the phone also vibrates and shows a notification, because by default the user is not
     * listening to the call and may not be holding the phone. The notification says nothing about
     * the call itself: it can show on the lock screen.
     */
    fun takeOver(context: Context, inCall: Boolean) {
        try {
            val tone = ToneGenerator(if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            main.postDelayed({ tone.release() }, 1_000)
        } catch (t: Throwable) {
            RotepLog.w("alert", "tone failed: ${t.javaClass.simpleName}")
        }
        if (inCall) vibrateAndNotify(context, Channel.TAKE_OVER, "AI が止まりました", "電話を確かめて、お店との電話を続けるか、切ってください")
    }

    /** Whether the phone shows this app's notifications at all. Not known: as if it does. */
    fun canNotify(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(true)

    /** The call left something urgent for the user: the result starts with it. */
    fun urgent(context: Context) = vibrateAndNotify(context, Channel.URGENT, "至急の内容があります", "結果の画面を確かめてください")

    /**
     * A call is over. CallService's notification just goes away, and the user is often not looking
     * at the phone. This replaces "AI が止まりました", which no longer applies; a user who has the app
     * on the screen sees the result there, so then that old notification is only taken away. Says
     * nothing about the call itself: it can show on the lock screen.
     */
    fun callOver(context: Context) {
        try {
            if (mainScreenShown) {
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } else {
                notify(context, Channel.CALL_OVER, "電話が終わりました", "結果の画面を確かめてください")
            }
        } catch (t: Throwable) {
            RotepLog.w("alert", "notification failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Another AI left a request. False if the user cannot see the notification: the sender then says
     * so itself. Says nothing about the request: it can show on the lock screen.
     */
    fun requestArrived(context: Context, expiresAfterMs: Long): Boolean = try {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Null until the first notification made the channel: not blocked then
        val channelOff = nm.getNotificationChannel(Channel.REQUEST.id)?.importance == NotificationManager.IMPORTANCE_NONE
        if (nm.areNotificationsEnabled() && !channelOff) {
            notify(context, Channel.REQUEST, "電話の依頼が届きました", "アプリを開いて、内容を確かめてください", REQUEST_NOTIFICATION_ID, expiresAfterMs)
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        RotepLog.w("alert", "notification failed: ${t.javaClass.simpleName}")
        false
    }

    fun clearRequest(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION_ID)
        } catch (t: Throwable) {
            RotepLog.w("alert", "notification not cleared: ${t.javaClass.simpleName}")
        }
    }

    private enum class Channel(
        val id: String,
        val label: String,
        val importance: Int = NotificationManager.IMPORTANCE_HIGH,
    ) {
        TAKE_OVER("take_over", "本人に代わるとき"),
        URGENT("urgent", "至急の知らせ"),
        REQUEST("phone_request", "ほかの AI からの依頼"),

        /** Makes its sound without covering the screen: nothing has to be done at once. */
        CALL_OVER("call_over", "電話が終わったとき", NotificationManager.IMPORTANCE_DEFAULT),
    }

    private fun vibrateAndNotify(context: Context, channel: Channel, title: String, text: String) {
        try {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        } catch (t: Throwable) {
            RotepLog.w("alert", "vibrate failed: ${t.javaClass.simpleName}")
        }
        try {
            notify(context, channel, title, text)
        } catch (t: Throwable) {
            RotepLog.w("alert", "notification failed: ${t.javaClass.simpleName}")
        }
    }

    private fun notify(
        context: Context,
        channel: Channel,
        title: String,
        text: String,
        id: Int = NOTIFICATION_ID,
        timeoutMs: Long? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel.id, channel.label, channel.importance))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, channel.id)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        timeoutMs?.let { builder.setTimeoutAfter(it) }
        nm.notify(id, builder.build())
    }
}
