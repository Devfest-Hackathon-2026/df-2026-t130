// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Where [CallSession] runs its steps and timers: one thread in the app, a hand-driven clock in
 * tests. All steps of one session run one after another, so the session needs no locks.
 */
interface CallScheduler {
    /** Monotonic milliseconds (not the wall clock). */
    fun now(): Long

    fun post(task: () -> Unit)

    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}

/** A single background thread. A task that throws is reported to [onError] and the thread goes on. */
class ExecutorScheduler(
    name: String = "call-session",
    private val onError: (Throwable) -> Unit = {},
) : CallScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun post(task: () -> Unit) {
        try {
            executor.execute(wrap(task))
        } catch (_: RejectedExecutionException) {
            // shut down: the session is over
        }
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): CallScheduler.Cancellable = try {
        val f = executor.schedule(wrap(task), delayMs, TimeUnit.MILLISECONDS)
        CallScheduler.Cancellable { f.cancel(false) }
    } catch (_: RejectedExecutionException) {
        CallScheduler.Cancellable {}
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun wrap(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
