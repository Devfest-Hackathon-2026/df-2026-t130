// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.rotep.shell.CallPicture

/**
 * What one look at the phone's calls ([CallPicture]) means for the call that manual control placed. Started only
 * when the phone had no call before dialing. Plain Kotlin, so a unit test sees it.
 *
 * A problem here never harms the call: when the helper cannot tell, or something else is going on,
 * the look says [Step.GAVE_UP] and the user is back to the buttons.
 */
internal class ManualCallWatch(
    private val startedAtMs: Long,
    private val config: Config = Config(),
) {
    data class Config(
        /** No call has shown up this long after dialing: the watch stops. */
        val appearLimitMs: Long = 30_000,
        /** So many looks in a row without an answer: the watch stops. */
        val unknownLimit: Int = 6,
        /** A moment without a call does not end it: it must be gone in this many looks in a row. */
        val goneLooks: Int = 2,
        /** A hold or a second call stops the AI, which cannot be undone: it must show in this many looks in a row. */
        val otherLooks: Int = 2,
    )

    enum class Step { KEEP, ANSWERED, ENDED, GAVE_UP }

    private var ringingSeen = false
    private var answered = false
    private var unknowns = 0
    private var gone = 0
    private var others = 0
    private var stopped = false

    fun look(picture: Int, nowMs: Long): Step {
        if (stopped) return Step.KEEP
        if (picture != CallPicture.OTHER) others = 0
        if (picture == CallPicture.UNKNOWN) {
            unknowns++
            return if (unknowns >= config.unknownLimit) giveUp() else Step.KEEP
        }
        unknowns = 0
        return when (picture) {
            CallPicture.DIALING -> {
                gone = 0
                ringingSeen = true
                Step.KEEP
            }
            CallPicture.ACTIVE -> {
                gone = 0
                when {
                    answered -> Step.KEEP
                    // Answered without ever being seen ringing: whether this is the placed call is a guess
                    !ringingSeen -> giveUp()
                    else -> {
                        answered = true
                        Step.ANSWERED
                    }
                }
            }
            CallPicture.ENDING -> {
                gone = 0
                Step.KEEP
            }
            CallPicture.NONE, CallPicture.RINGING_ONLY -> whenNoCall(nowMs)
            CallPicture.OTHER -> {
                gone = 0
                others++
                if (others < config.otherLooks) Step.KEEP else giveUp()
            }
            else -> giveUp() // a number this build does not know
        }
    }

    private fun whenNoCall(nowMs: Long): Step {
        if (ringingSeen || answered) {
            gone++
            if (gone < config.goneLooks) return Step.KEEP
            stopped = true
            return Step.ENDED
        }
        return if (nowMs - startedAtMs >= config.appearLimitMs) giveUp() else Step.KEEP
    }

    private fun giveUp(): Step {
        stopped = true
        return Step.GAVE_UP
    }
}
