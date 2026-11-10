// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

/**
 * Right after dialing, the phone app's call screen comes up and covers this app. This decides when
 * to bring it back: the app's screen went out of sight shortly after dialing, and it has not been
 * brought back too often already.
 *
 * Plain Kotlin, so a unit test sees it. Threads: called from the main thread and the call's thread.
 */
class ReturnToApp(
    private val windowMs: Long = WINDOW_MS,
    private val maxReturns: Int = MAX_RETURNS,
) {
    private var openUntilMs: Long? = null
    private var returns = 0

    /** A call was just placed. */
    @Synchronized
    fun dialed(nowMs: Long) {
        openUntilMs = nowMs + windowMs
        returns = 0
    }

    /** The call is over, or the user went to the phone's call screen on purpose. */
    @Synchronized
    fun stop() {
        openUntilMs = null
    }

    /** The app's screen went out of sight. true: ask for it to come back, now. */
    @Synchronized
    fun screenHidden(nowMs: Long): Boolean {
        val until = openUntilMs ?: return false
        if (nowMs > until) {
            openUntilMs = null
            return false
        }
        if (returns >= maxReturns) return false
        returns++
        return true
    }

    companion object {
        /** The phone app shows its call screen a moment after the call is placed. */
        const val WINDOW_MS = 10_000L

        /** Twice at most: once when it comes up, once if it comes up again; it must not become a fight. */
        const val MAX_RETURNS = 2
    }
}
