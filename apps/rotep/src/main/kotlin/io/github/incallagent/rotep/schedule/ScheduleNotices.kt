// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import io.github.incallagent.rotep.MainActivity
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.urgentItems
import io.github.incallagent.rotep.ui.headlineText
import io.github.incallagent.rotep.ui.resultHeadline

/** What the notification says when a scheduled call has ended. [urgent]: someone has to look now. */
internal data class EndNotice(val title: String, val text: String, val urgent: Boolean)

/**
 * The result of a scheduled call in a notification. Outside [ScheduleNotices] (no Android) so that a
 * unit test sees it. The words come from the result screen: they name no number and no shop.
 */
internal fun scheduledEndNotice(result: CallSession.Result): EndNotice = when {
    !result.dialed -> EndNotice("電話をかけませんでした", result.detail, urgent = true)
    result.endReason == CallSession.EndReason.CALL_FAILED || result.leftOpen ->
        EndNotice("電話が切れていないかもしれません", result.detail, urgent = true)
    result.outcome.urgentItems().isNotEmpty() -> EndNotice("至急の内容があります", "結果の画面を確かめてください", urgent = true)
    else -> EndNotice("電話が終わりました", headlineText(resultHeadline(result)), urgent = false)
}

/** Whether the notice with 「取り消す」 can show. [channelImportance] null: no notice has made its channel yet, so nobody turned it off. */
internal fun noticesCanShow(appEnabled: Boolean, channelImportance: Int?): Boolean =
    appEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

/**
 * The notifications of a scheduled call. On the lock screen only a version that says nothing about
 * the call. A notification that cannot be shown stops nothing.
 */
object ScheduleNotices {
    private const val SOON_ID = 10
    private const val OUTCOME_ID = 11
    private const val SOON_TIMEOUT_MS = 120_000L

    private enum class Channel(val id: String, val label: String, val importance: Int) {
        /** Comes up over the screen: there is little time, or someone has to look. */
        NEEDS_YOU("scheduled_call", "時間を指定した電話", NotificationManager.IMPORTANCE_HIGH),

        /** Makes its sound without covering the screen: nothing has to be done at once. */
        DONE("scheduled_call_done", "時間を指定した電話の結果", NotificationManager.IMPORTANCE_DEFAULT),

        /** Only in the list of notifications: another call is going on, and neither a sound nor a banner may get into it. */
        QUIET("scheduled_call_quiet", "時間を指定した電話（通話中のお知らせ）", NotificationManager.IMPORTANCE_LOW),
    }

    private fun alerting(quiet: Boolean) = if (quiet) Channel.QUIET else Channel.NEEDS_YOU

    fun canNotify(context: Context): Boolean = try {
        val nm = context.getSystemService(NotificationManager::class.java)
        noticesCanShow(nm.areNotificationsEnabled(), nm.getNotificationChannel(Channel.NEEDS_YOU.id)?.importance)
    } catch (_: Throwable) {
        false
    }

    /** One minute before the call: it can still be taken back from here. [quiet]: another call is going on, so no sound and no pop-up. */
    fun soon(context: Context, quiet: Boolean = false) {
        val cancel = PendingIntent.getBroadcast(
            context,
            CANCEL_REQUEST,
            Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), "取り消す", cancel).build()
        show(context, alerting(quiet), SOON_ID, "まもなく AI が電話をかけます", "1 分後に電話します。かけないときは「取り消す」を押してください") {
            it.addAction(action).setTimeoutAfter(SOON_TIMEOUT_MS).setCategory(Notification.CATEGORY_REMINDER)
        }
    }

    fun clearSoon(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(SOON_ID)
        } catch (t: Throwable) {
            RotepLog.w("schedule", "notice not cleared: ${t.javaClass.simpleName}")
        }
    }

    /** No call was placed. [reason] never holds a number or a name. [quiet]: as in [soon]. */
    fun notPlaced(context: Context, reason: String, quiet: Boolean = false) =
        show(context, alerting(quiet), OUTCOME_ID, "電話をかけませんでした", reason)

    /** 「取り消す」 did not go through. */
    fun notCancelled(context: Context) =
        show(context, Channel.NEEDS_YOU, OUTCOME_ID, "取り消せませんでした", "電話はかかります。アプリを開いて、取り消してください")

    internal fun ended(context: Context, notice: EndNotice) =
        show(context, if (notice.urgent) Channel.NEEDS_YOU else Channel.DONE, OUTCOME_ID, notice.title, notice.text)

    private fun show(
        context: Context,
        channel: Channel,
        id: Int,
        title: String,
        text: String,
        more: (Notification.Builder) -> Notification.Builder = { it },
    ) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channel.id, channel.label, channel.importance))
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // What a locked phone shows. Which call it was is on the screen of the app
            val hidden = Notification.Builder(context, channel.id)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("電話のお知らせ")
                .setContentText("アプリを開いて確かめてください")
                .build()
            val builder = Notification.Builder(context, channel.id)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
            nm.notify(id, more(builder).build())
        } catch (t: Throwable) {
            RotepLog.w("schedule", "notification failed: ${t.javaClass.simpleName}")
        }
    }

    private const val CANCEL_REQUEST = 3
}
