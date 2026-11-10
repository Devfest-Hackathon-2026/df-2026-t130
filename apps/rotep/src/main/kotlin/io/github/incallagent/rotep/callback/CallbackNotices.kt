// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.callback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.incallagent.rotep.MainActivity
import io.github.incallagent.rotep.app.RotepLog

/**
 * The notice that a shop may be calling back. It never makes a sound (the phone is already ringing)
 * and is not shown at all on the lock screen: it names the shop, and [Notification.VISIBILITY_PRIVATE]
 * would only hide that when the phone is set to hide sensitive content, which is not the default.
 */
internal object CallbackNotices {
    private const val ID = 30

    private enum class Channel(val id: String, val label: String, val importance: Int) {
        /** Comes up over the screen, without a sound. */
        RINGING("callback_notice", "お店からの折り返し", NotificationManager.IMPORTANCE_HIGH),

        /** Only in the list of notifications: a call of the AI is going on, and a pop-up must not cover its screen. */
        QUIET("callback_notice_quiet", "お店からの折り返し（通話中のお知らせ）", NotificationManager.IMPORTANCE_LOW),
    }

    fun canNotify(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }.getOrDefault(false)

    /** [quiet]: see [Channel.QUIET]. A notice that cannot be shown stops nothing. */
    fun show(context: Context, notice: CallbackNotice, quiet: Boolean) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = if (quiet) Channel.QUIET else Channel.RINGING
            nm.createNotificationChannel(
                NotificationChannel(channel.id, channel.label, channel.importance).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val built = Notification.Builder(context, channel.id)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(Notification.BigTextStyle().bigText(notice.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build()
            nm.notify(ID, built)
        } catch (t: Throwable) {
            RotepLog.w("callback", "notice failed: ${t.javaClass.simpleName}")
        }
    }
}
