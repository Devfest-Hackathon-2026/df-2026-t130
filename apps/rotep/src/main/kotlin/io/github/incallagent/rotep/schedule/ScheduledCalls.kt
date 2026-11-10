// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** The alarms of the one call that waits: [io.github.incallagent.rotep.schedule.AndroidScheduleAlarms] in the app, a fake in tests. */
interface ScheduleAlarms {
    /** null, or why they could not be set. */
    fun arm(noticeAt: Instant, fireAt: Instant): String?

    fun cancel()

    /** false once the system has forgotten them: the phone restarted or the app was force-stopped. */
    fun armed(): Boolean
}

/**
 * The one call that waits for its time: the file and the alarms together. One at a time. The file
 * says whether there is a call, so taking it back and the alarm cannot both win (ADR-040).
 * Main thread. The log gets what happened, never what the call is.
 */
class ScheduledCalls(
    private val store: ScheduledCallStore,
    private val alarms: ScheduleAlarms,
    private val now: () -> Instant,
    private val log: (String) -> Unit = {},
) {
    /** What the band on the first screen shows. */
    data class Info(val shopName: String, val fireAt: Instant)

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info

    val exists: Boolean get() = store.exists

    /**
     * When the app starts. Says what happened if the saved call can no longer be used. [alarmCame]:
     * the process was started for this call, and [ScheduledCallRunner] reads it next: reading it here
     * would drop a file that cannot be read, and the runner would take that for a call taken back.
     */
    fun load(alarmCame: Boolean = false): String? {
        if (alarmCame) return null
        return when (val r = store.read()) {
            is ScheduledCallStore.Read.Found -> {
                _info.value = infoOf(r.call)
                null
            }
            ScheduledCallStore.Read.None -> null
            ScheduledCallStore.Read.NotNow -> null
            ScheduledCallStore.Read.Unreadable -> {
                forget()
                NOT_READABLE
            }
        }
    }

    /** Saves [call] and sets its alarms. null, or why it was not scheduled (then nothing is kept). */
    fun schedule(call: ScheduledCall): String? {
        if (store.exists) return ALREADY
        if (!store.save(call)) return NOT_SAVED
        val refused = alarms.arm(call.fireAt.minus(ScheduleRules.NOTICE_BEFORE), call.fireAt)
        if (refused != null) {
            forget()
            return refused
        }
        _info.value = infoOf(call)
        log("scheduled in ${call.fireAt.epochSecond - now().epochSecond} s")
        return null
    }

    /** "取り消す". Safe at any time: the alarm that may already be on its way finds no file. */
    fun cancel() {
        log("cancelled")
        forget()
    }

    /** The saved call, left where it is. */
    fun read(): ScheduledCallStore.Read = store.read()

    /** Before the call is placed, and only then: no call can be placed twice. false: it was taken back. */
    fun consume(): Boolean {
        if (!store.exists) return false
        forget()
        return true
    }

    /** The call will not be made: nothing of it is kept. */
    fun drop() = forget()

    /**
     * When the app is opened: what to tell the user about a call whose alarm did not come, or null.
     * Not while its time is near (the alarm may be on its way). A saved call that cannot be read is
     * not judged here: the next look tries again. [afterRestart]: the phone has just started, so an
     * alarm that is not set is not on its way either.
     */
    fun tellIfLost(afterRestart: Boolean = false): String? {
        if (!store.exists) {
            _info.value = null
            return null
        }
        val info = _info.value ?: when (val r = store.read()) {
            is ScheduledCallStore.Read.Found -> infoOf(r.call).also { _info.value = it }
            ScheduledCallStore.Read.NotNow -> return null
            ScheduledCallStore.Read.None -> return null
            ScheduledCallStore.Read.Unreadable -> {
                forget()
                return NOT_READABLE
            }
        }
        val t = now()
        val gone = t.isAfter(info.fireAt.plus(ScheduleRules.LATE_LIMIT)) || ((afterRestart || t.isBefore(info.fireAt)) && !alarms.armed())
        if (!gone) return null
        log("alarm did not come")
        forget()
        return ScheduleRules.lostText(info.shopName, info.fireAt)
    }

    private fun forget() {
        alarms.cancel()
        store.clear()
        _info.value = null
    }

    private fun infoOf(call: ScheduledCall) = Info(call.policy.target.businessName, call.fireAt)

    companion object {
        const val ALREADY = "すでに時間を指定した電話があります。先に取り消してください"
        const val NOT_SAVED = "依頼を保存できなかったので、予約しませんでした。もう一度試してください"
        const val NOT_READABLE = "保存していた電話の予定を読めなかったので、取り消しました。必要なら、もう一度依頼してください"
    }
}
