// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause

/*
 * Plain values for the calls that RotepInCallService sees (fully automatic control), so that the rules of
 * TelecomCallControl run without Android and in unit tests. RotepInCallService turns each
 * android.telecom.Call into a [CallView] and wraps it as a [CallHandle]. Only the Call.* and
 * DisconnectCause.* constants are used here (inlined by the compiler).
 */

/** The state of a call (Call.STATE_*). */
enum class CallStatus {
    /** Being placed: NEW, DIALING, CONNECTING, SELECT_PHONE_ACCOUNT, PULLING_CALL. */
    DIALING,

    /** Incoming and not answered: RINGING, SIMULATED_RINGING. */
    RINGING,
    ACTIVE,
    HOLDING,
    DISCONNECTING,
    DISCONNECTED,

    /** AUDIO_PROCESSING, and states this app does not know. */
    OTHER;

    companion object {
        fun of(state: Int): CallStatus = when (state) {
            Call.STATE_NEW, Call.STATE_DIALING, Call.STATE_CONNECTING,
            Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_PULLING_CALL -> DIALING
            Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> RINGING
            Call.STATE_ACTIVE -> ACTIVE
            Call.STATE_HOLDING -> HOLDING
            Call.STATE_DISCONNECTING -> DISCONNECTING
            Call.STATE_DISCONNECTED -> DISCONNECTED
            else -> OTHER
        }
    }
}

/** Short codes for [CallEvent.Ended] ("local": hung up on this phone, by the app or by hand). */
object DisconnectReasons {
    const val LOCAL = "local"

    /** Manual control: the user told the app that they hung up. */
    const val USER = "user"

    /** The call was ended on this phone and not by the far end. */
    fun endedHere(reason: String): Boolean = reason == LOCAL || reason == USER

    /** For the result: "（相手が切りました）", or nothing when the code says nothing a person can use. */
    fun inWords(reason: String): String = when (reason) {
        LOCAL, USER -> "（こちらから切りました）"
        "remote" -> "（相手が切りました）"
        "busy" -> "（話し中でした）"
        "rejected" -> "（相手が受けませんでした）"
        "missed" -> "（応答がありませんでした）"
        "canceled" -> "（発信を取り消しました）"
        "error" -> "（通信の失敗で切れました）"
        "restricted" -> "（通話が制限されました）"
        else -> ""
    }

    fun of(code: Int?): String = when (code) {
        DisconnectCause.LOCAL -> LOCAL
        DisconnectCause.REMOTE -> "remote"
        DisconnectCause.BUSY -> "busy"
        DisconnectCause.CANCELED -> "canceled"
        DisconnectCause.REJECTED -> "rejected"
        DisconnectCause.MISSED -> "missed"
        DisconnectCause.ERROR -> "error"
        DisconnectCause.RESTRICTED -> "restricted"
        DisconnectCause.OTHER -> "other"
        DisconnectCause.ANSWERED_ELSEWHERE -> "answered_elsewhere"
        DisconnectCause.CALL_PULLED -> "call_pulled"
        DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "not_supported"
        else -> "unknown"
    }
}

/**
 * What TelecomCallControl needs to know about one call. [digits] is compared in memory only and
 * never logged: [toString] leaves it out.
 */
data class CallView(
    val status: CallStatus,
    /** The number as domestic digits ("0300000000"), or null (no number, or not a Japanese number). */
    val digits: String?,
    /** Call.Details.getCreationTimeMillis(): wall clock, milliseconds since the epoch. */
    val createdAtMs: Long,
    val outgoing: Boolean,
    /** In a conference, or a conference itself. */
    val conference: Boolean,
    /** An emergency call: by its number, or as the network or the emergency callback mode says. */
    val emergency: Boolean,
    /** Set when [status] is DISCONNECTED ([DisconnectReasons]). */
    val disconnectReason: String? = null,
    /** The phone is asking which SIM to use (Call.STATE_SELECT_PHONE_ACCOUNT): nothing has gone out yet. */
    val choosingAccount: Boolean = false,
) {
    override fun toString() = buildString {
        append("CallView(").append(status)
        if (outgoing) append(" outgoing")
        if (conference) append(" conference")
        if (emergency) append(" emergency")
        if (choosingAccount) append(" choosing-account")
        disconnectReason?.let { append(" reason=").append(it) }
        append(")")
    }
}

/** One call in RotepInCallService. TelecomCallControl operates only the call the app placed. */
interface CallHandle {
    /** Opaque id for the log ("call#1"). */
    val id: String

    fun disconnect()

    fun playDtmfTone(digit: Char)

    fun stopDtmfTone()
}

/** How the call sounds now (CallAudioState), as plain values. */
data class CallAudio(
    val muted: Boolean,
    /** One of CallAudioState.ROUTE_*. */
    val route: Int,
) {
    val speakerOn: Boolean get() = route == CallAudioState.ROUTE_SPEAKER
}

/** The bound RotepInCallService: the microphone mute and the audio route belong to the service, not to a call. */
fun interface InCallPort {
    fun setMuted(muted: Boolean)

    /** InCallService.setAudioRoute: CallAudioState.ROUTE_SPEAKER, or a route to go back to. */
    fun setRoute(route: Int) {}
}

/**
 * Connects RotepInCallService, which Telecom creates, with the TelecomCallControl of the running
 * call. The service calls the [Sink] on the main thread.
 */
object InCallHub {
    interface Sink {
        /** Telecom binds the service: true only while a call placed by the app is expected. */
        fun wantsCalls(): Boolean

        fun onCallAdded(call: CallHandle, view: CallView, port: InCallPort)

        fun onCallChanged(call: CallHandle, view: CallView)

        fun onCallRemoved(call: CallHandle)

        /** The service is letting go of Telecom: [port] works during this call only. */
        fun onUnbinding(port: InCallPort)

        /** Telecom reported how the call sounds now. */
        fun onCallAudioChanged(audio: CallAudio) {}
    }

    @Volatile
    var sink: Sink? = null
        private set

    fun attach(s: Sink) {
        sink = s
    }

    fun detach(s: Sink) {
        if (sink === s) sink = null
    }
}
