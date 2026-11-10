// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.rotep.call.CallScheduler
import java.time.Instant

/**
 * What happens when the time of a scheduled call has come and the foreground service is up, one step
 * at a time on [scheduler]'s thread (the main thread in the app). Everything that can stop the call
 * is asked again here; if anything does, no call is placed and the user is told why. It is never
 * tried again (ADR-040).
 *
 * A step that waits (for the helper, for a read that failed for the moment) looks at the file first:
 * a call taken back in the meantime is simply not placed.
 */
internal class ScheduledCallRunner(
    private val host: Host,
    private val scheduler: CallScheduler,
    private val log: (String) -> Unit = {},
    private val config: Config = Config(),
) {
    data class Config(
        /** The helper connects a few seconds after the app's process was started, and the network comes back a moment after the phone wakes up. */
        val helperWaitMs: Long = 15_000,
        /** When the app has to start the helper itself (ADR-049): more than its own time limit. */
        val preparedHelperWaitMs: Long = 35_000,
        val helperPollMs: Long = 500,
        /** A saved call or key that cannot be read just now: how often, and how far apart, it is read again. The key store can stay busy for a while after the phone wakes up. */
        val readRetries: Int = 30,
        val readRetryMs: Long = 1_000,
    ) {
        /** Every wait of a run to its end, with room for reads that are slow: past this, nothing is going to come of it. */
        val longestRunMs: Long get() = 2 * readRetries * readRetryMs + maxOf(helperWaitMs, preparedHelperWaitMs) + RUN_SLACK_MS
    }

    interface Host {
        fun now(): Instant

        fun read(): ScheduledCallStore.Read

        /** Without reading it: the file is still there. */
        fun stillScheduled(): Boolean

        /** Starts the helper if the app has to do that for a call (ADR-049). true: it has begun, the wait may be longer. */
        fun prepareHelper(): Boolean = false

        /** Why the helper cannot be had, once that is known; null: not known to be a problem. */
        fun helperProblem(): String? = null

        /** No call is going to be placed: what [prepareHelper] turned on goes back. */
        fun releaseHelper() {}

        fun helperReady(): Boolean

        /** The AI needs the network. false only when there clearly is none. */
        fun networkReady(): Boolean

        /** Everything the app asks before any call, in the same words, or null. */
        fun problem(call: ScheduledCall): String?

        fun readKey(): KeyRead

        /** Only when everything passed. Deletes the saved call first. */
        fun dial(call: ScheduledCall, key: String)

        /** No call was placed: the saved call is deleted, and the user is told why. */
        fun notPlaced(reason: String)

        /** The call was taken back: no call, and nothing to tell. */
        fun stopQuietly()
    }

    sealed interface KeyRead {
        class Key(val value: String) : KeyRead {
            override fun toString() = "Key(<hidden>)"
        }

        /** Saved, but not readable just now. */
        data object NotNow : KeyRead

        data class Problem(val reason: String) : KeyRead
    }

    /** Which run the waiting steps belong to: those of a run that is over do nothing. */
    private var run = 0

    /** The foreground service reported that it is up. */
    fun start() {
        log("due")
        run++
        guarded { readCall(config.readRetries) }
    }

    /** Nothing that is still waiting goes on: no call comes of this run. */
    fun giveUp() {
        run++
    }

    /** A step that throws would otherwise leave the app waiting for a call that never comes. */
    private fun guarded(step: () -> Unit) {
        val mine = run
        try {
            step()
        } catch (t: Throwable) {
            log("step failed: ${t.javaClass.simpleName}")
            // Not when it was the end of the run that failed: that is not done twice
            if (mine != run) return
            try {
                notPlaced("failed", COULD_NOT_CHECK)
            } catch (again: Throwable) {
                log("not told: ${again.javaClass.simpleName}")
            }
        }
    }

    private fun later(delayMs: Long, step: () -> Unit) {
        val mine = run
        scheduler.postDelayed(delayMs) { if (mine == run) guarded(step) }
    }

    private fun readCall(triesLeft: Int) {
        when (val read = host.read()) {
            ScheduledCallStore.Read.None -> stop()
            ScheduledCallStore.Read.Unreadable -> notPlaced("unreadable", UNREADABLE)
            ScheduledCallStore.Read.NotNow ->
                if (triesLeft > 0) {
                    later(config.readRetryMs) { readCall(triesLeft - 1) }
                } else {
                    notPlaced("not-now", UNREADABLE)
                }
            is ScheduledCallStore.Read.Found -> {
                val late = ScheduleRules.fireProblem(read.call.fireAt, host.now())
                if (late != null) notPlaced("time", late) else waitUntilReady(read.call, scheduler.now() + if (host.prepareHelper()) config.preparedHelperWaitMs else config.helperWaitMs)
            }
        }
    }

    /** The helper comes a few seconds after the app's process was started, and the network after the phone woke up. */
    private fun waitUntilReady(call: ScheduledCall, deadline: Long) {
        if (!host.stillScheduled()) return stop()
        if ((host.helperReady() && host.networkReady()) || host.helperProblem() != null || scheduler.now() >= deadline) return check(call)
        later(config.helperPollMs) { waitUntilReady(call, deadline) }
    }

    private fun check(call: ScheduledCall) {
        // Without a network the shop would answer and hear nothing
        val problem = host.helperProblem() ?: host.problem(call) ?: NO_NETWORK.takeUnless { host.networkReady() }
        if (problem != null) notPlaced("check", problem) else readKey(call, config.readRetries)
    }

    private fun readKey(call: ScheduledCall, triesLeft: Int) {
        if (!host.stillScheduled()) return stop()
        when (val key = host.readKey()) {
            is KeyRead.Key -> {
                log("placing the call")
                host.dial(call, key.value)
            }
            KeyRead.NotNow ->
                if (triesLeft > 0) {
                    later(config.readRetryMs) { readKey(call, triesLeft - 1) }
                } else {
                    notPlaced("key-not-now", KEY_NOT_NOW)
                }
            is KeyRead.Problem -> notPlaced("key", key.reason)
        }
    }

    private fun notPlaced(code: String, reason: String) {
        run++
        log("not placed: $code")
        letGoOfHelper()
        host.notPlaced(reason)
    }

    private fun stop() {
        run++
        log("taken back")
        letGoOfHelper()
        host.stopQuietly()
    }

    private fun letGoOfHelper() {
        try {
            host.releaseHelper()
        } catch (t: Throwable) {
            log("helper not let go: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        const val UNREADABLE = "保存していた依頼を読めなかったので、電話をかけませんでした"
        const val KEY_NOT_NOW = "API キーをいま読めなかったので、電話をかけませんでした"
        const val NO_NETWORK = "ネットワークにつながっていなかったので、電話をかけませんでした"
        const val COULD_NOT_CHECK = "電話の前の確認の途中で問題が起きたので、電話をかけませんでした"
        private const val RUN_SLACK_MS = 30_000L
    }
}
