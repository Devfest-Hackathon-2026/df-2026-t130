// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Operations on the one call the app places for an approved delegation policy.
 *
 * - NoCallControl: no call at all (the practice); [dial] reports [CallEvent.Active] at once
 * - ManualCallControl: the app places the call and, through the helper, sees the answer and the
 *   end; what it cannot do stays by hand (manual control)
 * - TelecomCallControl: TelecomManager.placeCall and a non-UI InCallService (fully automatic control)
 *
 * Implementations never act on other calls or on emergency calls, and never log the number.
 */
interface CallControl {
    /** Receives every [CallEvent]. Set before [dial]. Callbacks may come from any thread. */
    fun setListener(listener: (CallEvent) -> Unit)

    /** Places the call. [number] must be the approved target number of the policy. */
    fun dial(number: String)

    /** The AI (end_call) wants the call ended. */
    fun hangUp()

    /** The user pressed "電話を切る". */
    fun userHangUp() = hangUp()

    /** Digits 0-9, * and # only (checked by the tool handler before this is called). */
    fun sendDtmf(digits: String)

    /** Mutes or unmutes the phone's own microphone on this call. */
    fun setMuted(muted: Boolean)

    /** The speaker on or off ([CallPanel.speakerAvailable]). */
    fun setSpeaker(on: Boolean) {}

    /** What the call screen can do on this call right now. */
    val panel: StateFlow<CallPanel> get() = NO_PANEL

    /** Releases listeners and services after the call ended. Safe to call more than once. */
    fun close() {}
}

/**
 * The buttons of the call screen, as the call control knows them: a button is there only while the
 * app can really do it on this call. What it cannot do stays on the phone app's call screen.
 */
data class CallPanel(
    val speakerAvailable: Boolean = false,
    val speakerOn: Boolean = false,
    val micAvailable: Boolean = false,
    val micOff: Boolean = false,
    val keypadAvailable: Boolean = false,
    val hangUpAvailable: Boolean = false,
    /**
     * The app sees by itself when the far end answers and when the call ends, so the user need not
     * tell it ("相手が出た", "切った").
     */
    val watchesCall: Boolean = false,
    /** Offer "電話の画面を開く": what the app cannot do from here is on the phone's call screen. */
    val openPhoneOffered: Boolean = false,
)

private val NO_PANEL: StateFlow<CallPanel> = MutableStateFlow(CallPanel())

/** What happened to the call. Texts are for the log and the screen: never a number or a name. */
sealed interface CallEvent {
    /** The call is being placed and has not been answered yet. */
    data object Dialing : CallEvent

    /** The far end answered. */
    data object Active : CallEvent

    /** The call ended. [reason] is a short code such as "local", "remote" or "busy". */
    data class Ended(val reason: String) : CallEvent

    /** The call could not be placed or controlled. */
    data class Failed(val detail: String) : CallEvent
}
