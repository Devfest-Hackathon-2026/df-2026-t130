// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveAuth

/** What the thinking model is asked for in one turn (ADR-053). */
sealed interface BrainInput {
    /** The disclosure is being said: what comes after it. */
    data object Opening : BrainInput

    /** What the other side said since the last turn. [cutOff]: the line of ours they talked over, as it was written. */
    data class ShopSaid(val text: String, val cutOff: String? = null) : BrainInput

    /** From the app, not from the other side: a button the user pressed, the time limit. */
    data class Note(val text: String) : BrainInput

    /** The answers to the tools of the last turn, with what the other side said in the meantime. */
    data class ToolResults(val results: List<FunctionResponse>, val shopSaid: String? = null) : BrainInput
}

/** Why no answer came. Reasons only: never the text of an error. */
enum class BrainFailure { KEY_REFUSED, RATE_LIMITED, OVERLOADED, TIMEOUT, NETWORK, REFUSED, OTHER }

/** One turn of the thinking model. [say]: what is to be spoken and has not gone out sentence by sentence, null when nothing is left. [calls]: in the order given. */
data class BrainOutput(
    val say: String? = null,
    val calls: List<FunctionCall> = emptyList(),
    val failed: BrainFailure? = null,
)

/** Decides everything that is said and done in a call. Keeps the conversation itself. */
interface CallBrain {
    /** Before the first turn, to have the instruction cached. Its failure does not matter. */
    fun warmUp()

    /**
     * [onLine] gets each sentence as soon as it is whole (up to 「。」「！」「？」), and [done] comes once after them, with
     * what is left. On any thread, unless the returned cancel is called first. A turn that is cancelled leaves nothing
     * in the conversation, the sentences that went out included: whoever cancelled it tells the next turn what they were.
     */
    fun think(input: BrainInput, onLine: (String) -> Unit = {}, done: (BrainOutput) -> Unit): () -> Unit
}

/** One WebSocket of the voice model (the ears or the mouth). */
interface VoiceSocket {
    /** false before it opened or after it closed. */
    fun send(text: String): Boolean

    fun cancel()

    interface Listener {
        fun onOpen()

        fun onMessage(text: String)

        fun onClosed(code: Int, reason: String)

        /** [detail] never holds the key. */
        fun onFailure(detail: String)
    }
}

fun interface VoiceSockets {
    fun open(auth: LiveAuth, listener: VoiceSocket.Listener): VoiceSocket
}

/** The words the voice says without asking the thinking model, as they are said (in kana where it matters). */
data class DualSpoken(val disclosure: String, val fallback: String, val voiceName: String?)
