// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import io.github.incallagent.core.policy.PhoneNumbers
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.call.CallAudio
import io.github.incallagent.rotep.call.CallHandle
import io.github.incallagent.rotep.call.CallStatus
import io.github.incallagent.rotep.call.CallView
import io.github.incallagent.rotep.call.DisconnectReasons
import io.github.incallagent.rotep.call.EmergencyNumbers
import io.github.incallagent.rotep.call.InCallHub
import io.github.incallagent.rotep.call.InCallPort
import io.github.incallagent.telecom.CallIds

/**
 * Fully automatic control: the non-UI InCallService of TelecomCallControl. Telecom binds it without the app being
 * the default dialer because the service requires BIND_INCALL_SERVICE, declares no
 * IN_CALL_SERVICE_UI meta-data, and the MANAGE_ONGOING_CALLS app op is allowed (adb, a4.md).
 * Disabled in the manifest: TelecomCallControl enables it right before placing the call and
 * disables it when the call is over.
 *
 * It never becomes a foreground service and never posts a notification (CallService is the
 * foreground service of the AI call). When no call placed by the app is expected (for example the
 * app stopped during a call and left the service enabled), it gives Telecom no binder, so it gets
 * no call at all, and disables itself.
 *
 * Calls go on to [InCallHub] as plain values. A Call or its Details is never logged: their
 * toString() contains the number (ADR-021).
 */
class RotepInCallService : InCallService() {
    private class Handle(val call: Call, override val id: String) : CallHandle {
        override fun disconnect() = call.disconnect()

        override fun playDtmfTone(digit: Char) = call.playDtmfTone(digit)

        override fun stopDtmfTone() = call.stopDtmfTone()
    }

    private val handles = HashMap<Call, Handle>()
    private val emergencyNumber = HashMap<Call, Boolean>()
    private val port = object : InCallPort {
        override fun setMuted(muted: Boolean) = this@RotepInCallService.setMuted(muted)

        @Suppress("DEPRECATION") // requestCallEndpointChange needs API 34; setAudioRoute works on all of them
        override fun setRoute(route: Int) = this@RotepInCallService.setAudioRoute(route)
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed(call)

        override fun onDetailsChanged(call: Call, details: Call.Details) = changed(call)

        override fun onParentChanged(call: Call, parent: Call?) = changed(call)

        override fun onChildrenChanged(call: Call, children: List<Call>) = changed(call)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (InCallHub.sink?.wantsCalls() != true) {
            RotepLog.i("incall", "bound with no call expected: no binder, disabling the service")
            Handler(Looper.getMainLooper()).post { runCatching { setEnabled(applicationContext, false) } }
            return null
        }
        RotepLog.i("incall", "bound")
        return super.onBind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val sink = InCallHub.sink ?: return
        val h = Handle(call, CallIds.of(call))
        handles[call] = h
        call.registerCallback(callback)
        val v = view(call)
        RotepLog.i("incall", "added ${h.id} $v")
        sink.onCallAdded(h, v, port)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        emergencyNumber.remove(call)
        val h = handles.remove(call) ?: return
        RotepLog.i("incall", "removed ${h.id}")
        InCallHub.sink?.onCallRemoved(h)
    }

    @Deprecated("Deprecated in API 34", ReplaceWith("onCallEndpointChanged and onMuteStateChanged"))
    @Suppress("DEPRECATION") // minSdk 31: the replacements need API 34, and Telecom still calls this one
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState ?: return
        InCallHub.sink?.onCallAudioChanged(CallAudio(muted = audioState.isMuted, route = audioState.route))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        RotepLog.i("incall", "unbinding")
        // Before super: the mute can still be taken off, and the calls are not yet reported ended
        InCallHub.sink?.onUnbinding(port)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        for (c in handles.keys) c.unregisterCallback(callback)
        handles.clear()
        emergencyNumber.clear()
        super.onDestroy()
    }

    private fun changed(call: Call) {
        val h = handles[call] ?: return
        val v = view(call)
        RotepLog.i("incall", "changed ${h.id} $v")
        InCallHub.sink?.onCallChanged(h, v)
    }

    private fun view(call: Call): CallView {
        val d = call.details
        val number = d.handle?.schemeSpecificPart
        return CallView(
            status = CallStatus.of(d.state),
            digits = number?.let { PhoneNumbers.normalizeJp(it) },
            createdAtMs = d.creationTimeMillis,
            outgoing = d.callDirection == Call.Details.DIRECTION_OUTGOING,
            conference = call.parent != null || call.children.isNotEmpty() ||
                d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            emergency = d.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) ||
                d.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
                emergencyNumber.getOrPut(call) { isEmergencyNumber(number) },
            disconnectReason = if (d.state == Call.STATE_DISCONNECTED) DisconnectReasons.of(d.disconnectCause?.code) else null,
            choosingAccount = d.state == Call.STATE_SELECT_PHONE_ACCOUNT,
        )
    }

    /** Asked once per call. When the phone cannot tell, the other rules still apply. */
    private fun isEmergencyNumber(number: String?): Boolean {
        if (number.isNullOrEmpty()) return false
        return EmergencyNumbers.check(number, EmergencyNumbers.of(this)) == EmergencyNumbers.Answer.Yes
    }

    companion object {
        /** Enabled: Telecom may bind it. Off: back to the manifest's state (disabled). */
        fun setEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, RotepInCallService::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
            RotepLog.i("incall", if (enabled) "service enabled" else "service disabled")
        }
    }
}
