// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog
import kotlin.concurrent.thread

/**
 * With the app in the background, one look at the server every 15 minutes (the shortest period the
 * system allows; Doze can make it longer at night). A request found this way raises the usual notice.
 * Only ever scheduled while a server is set up.
 */
class RelayPollJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "relay-job", isDaemon = true) {
            try {
                RotepApp.init(applicationContext)
                PhoneRelay.pollOnce()
            } catch (t: Throwable) {
                // Nothing here may end the app, and the system must always hear that the job is over
                RotepLog.w("relay", "background check failed: ${t.javaClass.simpleName}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 4041
        private const val PERIOD_MS = 15 * 60_000L

        /** Schedules the job when [enabled], otherwise takes it away. Safe to call at any time. */
        fun sync(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            // Scheduling again would start its 15 minutes over
            if (scheduler.getPendingJob(JOB_ID) != null) return
            try {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, RelayPollJob::class.java))
                        .setPeriodic(PERIOD_MS)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                // The service or RECEIVE_BOOT_COMPLETED is missing from the manifest: the foreground way still works
                RotepLog.w("relay", "background check not scheduled: ${e.javaClass.simpleName}")
            }
        }
    }
}
