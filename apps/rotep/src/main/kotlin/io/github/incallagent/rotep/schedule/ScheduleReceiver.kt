// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog
import kotlin.concurrent.thread

/**
 * The alarms of a scheduled call, and "取り消す" on its notice. Not exported: only the app's own
 * PendingIntents reach it, and no Intent carries anything but its action.
 *
 * The process may have been started just for this.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // Without RotepApp.init first: the exemption from the background limits only lasts seconds
            ACTION_FIRE -> RotepApp.scheduledCallDue(app)
            ACTION_NOTICE -> afterSetUp(app) { RotepApp.scheduledCallSoon() }
            ACTION_CANCEL -> afterSetUp(app) {
                try {
                    RotepApp.cancelScheduledCall()
                } catch (t: Throwable) {
                    // The call is still going to be placed: this must not pass in silence
                    ScheduleNotices.notCancelled(app)
                    throw t
                }
            }
        }
    }

    /** [RotepApp.init] reads files and the key store: not on the main thread, where a broadcast has only seconds. */
    private fun afterSetUp(app: Context, step: () -> Unit) {
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        thread(name = "schedule-broadcast", isDaemon = true) {
            prepareThenStep({ RotepApp.init(app) }, { main.post(it) }, step, pending::finish) { RotepLog.w("schedule", it) }
        }
    }

    companion object {
        const val ACTION_NOTICE = "io.github.incallagent.rotep.schedule.NOTICE"
        const val ACTION_FIRE = "io.github.incallagent.rotep.schedule.FIRE"
        const val ACTION_CANCEL = "io.github.incallagent.rotep.schedule.CANCEL"
    }
}

/**
 * [prepare] on this thread, then [step] on the main thread by way of [onMain], and [done] in any case.
 * A set-up that failed does not stop [step]: 「取り消す」 still goes through if it can.
 */
internal fun prepareThenStep(prepare: () -> Unit, onMain: (() -> Unit) -> Unit, step: () -> Unit, done: () -> Unit, log: (String) -> Unit) {
    try {
        prepare()
    } catch (t: Throwable) {
        log("set-up failed: ${t.javaClass.simpleName}")
    }
    onMain {
        try {
            step()
        } catch (t: Throwable) {
            log("step failed: ${t.javaClass.simpleName}")
        } finally {
            done()
        }
    }
}
