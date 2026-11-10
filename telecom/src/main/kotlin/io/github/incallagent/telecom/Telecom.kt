// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import io.github.incallagent.core.Redact
import java.util.WeakHashMap

/**
 * Call-control helpers shared by the member apps (:apps:<id>) and :poc.
 *
 * Logging rule: never put a [Call] or [Call.Details] into a log string (their toString()
 * contains the handle, i.e. the phone number). Use [CallIds.of] and [describe] instead.
 */
object Telecom

/** Opaque, per-process call ids ("call#1", "call#2", ...). */
object CallIds {
    private val ids = WeakHashMap<Call, Int>()
    private var next = 1

    @Synchronized
    fun of(call: Call): String = "call#" + ids.getOrPut(call) { next++ }
}

object CallStates {
    fun name(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
        Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
        else -> "STATE_?"
    } + "($state)"
}

object DisconnectCauses {
    fun codeName(code: Int): String = when (code) {
        DisconnectCause.UNKNOWN -> "UNKNOWN"
        DisconnectCause.ERROR -> "ERROR"
        DisconnectCause.LOCAL -> "LOCAL"
        DisconnectCause.REMOTE -> "REMOTE"
        DisconnectCause.CANCELED -> "CANCELED"
        DisconnectCause.MISSED -> "MISSED"
        DisconnectCause.REJECTED -> "REJECTED"
        DisconnectCause.BUSY -> "BUSY"
        DisconnectCause.RESTRICTED -> "RESTRICTED"
        DisconnectCause.OTHER -> "OTHER"
        DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "CONNECTION_MANAGER_NOT_SUPPORTED"
        DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
        DisconnectCause.CALL_PULLED -> "CALL_PULLED"
        else -> "CODE_?"
    } + "($code)"

    /**
     * Code + reason only. Label and description are user-facing strings that can contain
     * a number or a name, so they are not logged.
     */
    fun describe(cause: DisconnectCause?): String =
        if (cause == null) "<null>" else "code=${codeName(cause.code)} reason=${cause.reason ?: "<null>"}"
}

object CallAudioStates {
    fun describe(state: CallAudioState?): String =
        if (state == null) "<null>" else "muted=${state.isMuted} route=${CallAudioState.audioRouteToString(state.route)}"
}

/** Masked one-line description of a call, safe for logs. */
fun describe(call: Call): String {
    val d = call.details
    val handle = d.handle
    return "${CallIds.of(call)} state=${CallStates.name(d.state)} " +
        "handle=${Redact.handle(handle?.scheme, handle?.schemeSpecificPart)} " +
        "account=${d.accountHandle?.componentName?.packageName ?: "<none>"} " +
        "conference=${call.parent != null}"
}
