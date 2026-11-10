// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.telecom.CallAudioState
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.service.RotepInCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fully automatic control: the app places the call (TelecomManager.placeCall, CALL_PHONE) and controls it
 * through RotepInCallService, a non-UI InCallService. Telecom binds that service only while the
 * MANAGE_ONGOING_CALLS app op is allowed (by adb, apps/rotep/a4.md) and the service is enabled:
 * it is enabled right before placeCall and disabled again in [close].
 *
 * Safety rules:
 * - The number must pass [DialCheck] (the approved number only) and [EmergencyNumbers]
 * - No call is placed while another call is going on (TelecomManager.isInCall)
 * - Only the call the app placed is operated: outgoing, the same number in full, created right
 *   after placeCall ([Config.earlyMs], [Config.lateMs]). Other calls, emergency calls included,
 *   are only watched; nothing is ever done to them
 * - Hold, another call going on, a conference, an emergency call, or losing the service: one
 *   [CallEvent.Failed] (CallSession stops the AI and the user takes over), the microphone is
 *   unmuted if this class muted it, and from then on the call is only watched until it ends
 * - The number is compared in memory only and never logged
 *
 * State lives on [scheduler]'s thread: the main thread in the app, where RotepInCallService calls
 * the [InCallHub.Sink] methods. The [CallControl] methods post to it.
 */
class TelecomCallControl(
    private val phone: TelecomPhone,
    private val approvedNumber: String,
    private val scheduler: CallScheduler,
    private val log: (String) -> Unit = {},
    private val config: Config = Config(),
) : CallControl, InCallHub.Sink {
    data class Config(
        /** [CallEvent.Failed] when the placed call has not reached the service by then. */
        val bindTimeoutMs: Long = 20_000,
        /** [CallEvent.Failed] when the call has not ended this long after [hangUp]. */
        val hangUpTimeoutMs: Long = 10_000,
        /** The placed call may have been created this much before placeCall returned (clock steps). */
        val earlyMs: Long = 2_000,
        /** ...and at most this much after. */
        val lateMs: Long = 60_000,
        /** One DTMF tone, then a pause before the next one. */
        val toneMs: Long = 250,
        val gapMs: Long = 150,
        /** Nobody is at the phone (a call at a time the user chose): nobody can answer the question which SIM to use. */
        val nobodyThere: Boolean = false,
    )

    private class Placed(val digits: String, val atMs: Long) {
        override fun toString() = "Placed(<${digits.length} digits>)"
    }

    @Volatile private var listener: ((CallEvent) -> Unit)? = null

    // Only on the scheduler's thread
    private var dialRequested = false
    private var placed: Placed? = null
    private var closed = false
    private var port: InCallPort? = null
    private var target: CallHandle? = null
    private var targetStatus: CallStatus? = null
    private val others = LinkedHashMap<CallHandle, CallView>()
    private var dialingSent = false
    private var activeSent = false
    private var ended = false
    private var lost = false
    private var unbinding = false
    private var mutedByUs = false
    private var bindTimeout: CallScheduler.Cancellable? = null
    private var hangUpTimeout: CallScheduler.Cancellable? = null
    private val tones = ArrayDeque<Char>()
    private var toneOn = false
    private var toneTask: CallScheduler.Cancellable? = null
    private var audio: CallAudio? = null

    /** Where the call was before the user turned the speaker on: "off" goes back there. */
    private var routeBeforeSpeaker: Int? = null
    private val _panel = MutableStateFlow(CallPanel())

    override val panel: StateFlow<CallPanel> = _panel

    override fun setListener(listener: (CallEvent) -> Unit) {
        this.listener = listener
    }

    override fun dial(number: String) = scheduler.post { doDial(number) }

    override fun hangUp() = scheduler.post { doHangUp() }

    override fun sendDtmf(digits: String) = scheduler.post { doDtmf(digits) }

    override fun setMuted(muted: Boolean) = scheduler.post { doMute(muted) }

    override fun setSpeaker(on: Boolean) = scheduler.post { doSpeaker(on) }

    override fun close() {
        listener = null
        scheduler.post { doClose() }
    }

    // --- InCallHub.Sink (RotepInCallService, main thread) ---

    override fun wantsCalls(): Boolean = placed != null && !closed

    override fun onCallAdded(call: CallHandle, view: CallView, port: InCallPort) {
        if (closed) return
        this.port = port
        onView(call, view)
        updatePanel()
    }

    override fun onCallChanged(call: CallHandle, view: CallView) {
        if (closed) return
        onView(call, view)
        updatePanel()
    }

    override fun onCallRemoved(call: CallHandle) {
        if (closed) return
        if (call == target) targetEnded("unknown") else others.remove(call)
        updatePanel()
    }

    override fun onUnbinding(port: InCallPort) {
        if (closed) return
        log("telecom: in-call service unbinding")
        unbinding = true
        if (mutedByUs) {
            mutedByUs = false
            runCatching { port.setMuted(false) }
        }
        stopTones()
        this.port = null
        // InCallService.onUnbind reports every live call as DISCONNECTED next, although the real
        // call may go on: decide here instead
        when {
            placed == null -> {}
            target == null -> lose("unbound", "通話の操作が切れました。電話の画面で切ってください")
            !ended -> targetEnded("unbound")
        }
        updatePanel()
    }

    override fun onCallAudioChanged(audio: CallAudio) {
        if (closed) return
        this.audio = audio
        updatePanel()
    }

    // --- steps ---

    private fun doDial(number: String) {
        if (dialRequested || closed) return
        dialRequested = true
        val digits = when (val c = DialCheck.check(number, approvedNumber)) {
            is DialCheck.Result.Refused -> return refuse("number", c.reason)
            is DialCheck.Result.Ok -> c.digits
        }
        EmergencyNumbers.problem(number, phone::isEmergencyNumber, dials = true)?.let { return refuse("emergency", it) }
        phone.controlProblem()?.let { return refuse("not-ready", it) }
        val busy = try {
            phone.isInCall()
        } catch (t: Throwable) {
            log("telecom: isInCall failed ${t.javaClass.simpleName}")
            return refuse("in-call-unknown", "ほかの通話があるかを確かめられないので、電話をかけません")
        }
        if (busy) return refuse("in-call", "ほかの通話の途中なので、電話をかけません")
        // Ready for the service before it can be bound: Telecom binds it as soon as the call exists
        InCallHub.attach(this)
        placed = Placed(digits, phone.wallClockMs())
        try {
            phone.setInCallServiceEnabled(true)
            phone.placeCall(digits)
        } catch (t: Throwable) {
            placed = null
            stopService()
            log("telecom: place failed ${t.javaClass.simpleName}")
            return refuse("place", if (t is SecurityException) "発信が許可されませんでした" else "発信できませんでした。少し待ってから、もう一度試してください")
        }
        log("telecom: call placed")
        updatePanel()
        bindTimeout = scheduler.postDelayed(config.bindTimeoutMs) { onBindTimeout() }
    }

    private fun onBindTimeout() {
        bindTimeout = null
        if (closed || lost || target != null) return
        lose(
            "not-found",
            if (port == null) {
                "発信はしましたが、アプリから通話を操作できません。電話の画面で切ってください"
            } else {
                "発信はしましたが、かけた通話を見分けられませんでした。電話の画面で切ってください"
            },
        )
    }

    private fun onView(call: CallHandle, v: CallView) {
        when {
            call == target -> onTarget(v)
            target == null && isPlacedCall(v) -> {
                target = call
                others.remove(call)
                bindTimeout?.cancel()
                bindTimeout = null
                log("telecom: placed call found (${call.id})")
                onTarget(v)
            }
            v.status == CallStatus.DISCONNECTED -> others.remove(call)
            else -> others[call] = v
        }
        checkOthers()
    }

    private fun onTarget(v: CallView) {
        targetStatus = v.status
        if (v.status != CallStatus.ACTIVE) stopTones()
        if (v.status == CallStatus.DISCONNECTED) return targetEnded(v.disconnectReason ?: "unknown")
        if (ended || lost) return
        when {
            v.emergency -> lose("emergency", "緊急通報として扱われる通話になりました")
            v.conference -> lose("conference", "会議通話になりました")
            v.status == CallStatus.HOLDING -> lose("hold", "保留になりました")
            v.choosingAccount && config.nobodyThere -> giveUpSimChoice()
            v.status == CallStatus.DIALING -> sendDialing()
            v.status == CallStatus.ACTIVE -> if (!activeSent) {
                sendDialing()
                activeSent = true
                log("telecom: answered")
                emit(CallEvent.Active)
            }
            else -> log("telecom: placed call ${v.status}")
        }
    }

    /**
     * The phone asks which SIM to use and nobody is there. Answered later, the call would go out long
     * after the time the user approved, so it is ended; nothing has gone out yet.
     */
    private fun giveUpSimChoice() {
        try {
            target?.disconnect()
        } catch (e: Throwable) {
            return lose("sim-choice", SIM_CHOICE)
        }
        lost = true
        refuse("sim-choice", SIM_CHOICE)
    }

    /** Other calls are only watched. Ringing alone (call waiting) changes nothing for the placed call. */
    private fun checkOthers() {
        if (placed == null || lost || ended) return
        for (v in others.values) {
            if (v.emergency) return lose("other-emergency", "緊急通報の通話があります")
            // Not recognized yet, may still be the placed call (the bind timeout covers it)
            if (target == null && v.outgoing && v.status == CallStatus.DIALING && inWindow(v)) continue
            when (v.status) {
                CallStatus.ACTIVE, CallStatus.DIALING -> return lose("other-call", "ほかの通話が始まりました")
                CallStatus.HOLDING -> return lose("other-held", "保留中のほかの通話があります")
                else -> {}
            }
        }
    }

    private fun isPlacedCall(v: CallView): Boolean {
        val p = placed ?: return false
        return v.outgoing && !v.emergency && v.digits == p.digits && inWindow(v)
    }

    private fun inWindow(v: CallView): Boolean {
        val p = placed ?: return false
        return v.createdAtMs >= p.atMs - config.earlyMs && v.createdAtMs <= p.atMs + config.lateMs
    }

    private fun sendDialing() {
        if (dialingSent || lost) return
        dialingSent = true
        emit(CallEvent.Dialing)
    }

    private fun targetEnded(reason: String) {
        if (ended) return
        ended = true
        hangUpTimeout?.cancel()
        hangUpTimeout = null
        stopTones()
        if (unbinding) lose("unbound", "通話の操作が切れました。電話の画面で続けてください")
        unmuteIfWeMuted()
        val r = if (unbinding) "unbound" else reason
        log("telecom: placed call ended reason=$r")
        updatePanel()
        emit(CallEvent.Ended(r))
    }

    /**
     * Stop operating the call: one [CallEvent.Failed]; the end is still reported. After placeCall
     * the call exists and may be ringing at the shop even when the app never found it (the bind
     * timeout, another call, the service gone): [CallEvent.Dialing] goes first then, so that the
     * session alerts the user instead of ending quietly as if nothing had been dialed.
     */
    private fun lose(code: String, detail: String) {
        if (lost) return
        if (placed != null) sendDialing()
        lost = true
        bindTimeout?.cancel()
        bindTimeout = null
        hangUpTimeout?.cancel()
        hangUpTimeout = null
        stopTones()
        unmuteIfWeMuted()
        log("telecom: control given up: $code")
        updatePanel()
        emit(CallEvent.Failed(detail))
    }

    private fun refuse(code: String, detail: String) {
        log("telecom: not dialed: $code")
        emit(CallEvent.Failed(detail))
    }

    private fun doHangUp() {
        if (closed) return
        val t = target
        if (t == null || ended || lost) {
            log("telecom: hang up skipped (found=${t != null} ended=$ended lost=$lost)")
            return
        }
        stopTones()
        log("telecom: hanging up")
        try {
            t.disconnect()
        } catch (e: Throwable) {
            log("telecom: hang up failed ${e.javaClass.simpleName}")
            return lose("hang-up", "電話を切れませんでした。電話の画面で切ってください")
        }
        if (hangUpTimeout == null) {
            hangUpTimeout = scheduler.postDelayed(config.hangUpTimeoutMs) {
                hangUpTimeout = null
                if (!ended && !closed) lose("hang-up-timeout", "電話が切れませんでした。電話の画面で切ってください")
            }
        }
    }

    private fun doDtmf(digits: String) {
        if (closed || ended || lost || target == null || targetStatus != CallStatus.ACTIVE) {
            log("telecom: dtmf skipped (${digits.length} digits)")
            return
        }
        for (c in digits) if (c in DTMF_DIGITS) tones.addLast(c)
        nextTone()
    }

    /** Every playDtmfTone is followed by its stopDtmfTone before the next tone (Call's contract). */
    private fun nextTone() {
        if (toneOn || toneTask != null) return
        val t = target ?: return
        val c = tones.removeFirstOrNull() ?: return
        try {
            t.playDtmfTone(c)
        } catch (e: Throwable) {
            log("telecom: dtmf failed ${e.javaClass.simpleName}")
            tones.clear()
            return
        }
        toneOn = true
        toneTask = scheduler.postDelayed(config.toneMs) {
            toneOn = false
            runCatching { t.stopDtmfTone() }
            toneTask = scheduler.postDelayed(config.gapMs) {
                toneTask = null
                nextTone()
            }
        }
    }

    private fun stopTones() {
        tones.clear()
        toneTask?.cancel()
        toneTask = null
        if (toneOn) {
            toneOn = false
            target?.let { t -> runCatching { t.stopDtmfTone() } }
        }
    }

    private fun doMute(muted: Boolean) {
        if (closed) return
        val p = port
        if (!muted) {
            mutedByUs = false
            if (p != null) runCatching { p.setMuted(false) }
            return
        }
        if (p == null || ended || lost || targetStatus != CallStatus.ACTIVE) {
            log("telecom: mute skipped (state=$targetStatus lost=$lost)")
            return
        }
        try {
            p.setMuted(true)
            mutedByUs = true
        } catch (t: Throwable) {
            log("telecom: mute failed ${t.javaClass.simpleName}")
        }
    }

    /**
     * The user's speaker button. At the end of the call Telecom goes back to the usual route by
     * itself (NO_FOCUS); doing it here too could change the route of another call that goes on.
     */
    @Suppress("DEPRECATION") // requestCallEndpointChange needs API 34; setAudioRoute works on all of them
    private fun doSpeaker(on: Boolean) {
        val p = port
        val a = audio
        if (closed || ended || lost || target == null || p == null || a == null || a.speakerOn == on) {
            log("telecom: speaker skipped")
            return
        }
        if (on) routeBeforeSpeaker = a.route
        val route = if (on) CallAudioState.ROUTE_SPEAKER else routeBeforeSpeaker ?: CallAudioState.ROUTE_WIRED_OR_EARPIECE
        try {
            p.setRoute(route)
        } catch (t: Throwable) {
            log("telecom: speaker failed ${t.javaClass.simpleName}")
        }
    }

    /** The mute applies to the whole phone: never leave it on. */
    private fun unmuteIfWeMuted() {
        if (!mutedByUs) return
        mutedByUs = false
        port?.let { p -> runCatching { p.setMuted(false) } }
    }

    private fun updatePanel() {
        val usable = !closed && !ended && !lost && target != null && port != null
        val a = audio
        _panel.value = CallPanel(
            speakerAvailable = usable && a != null,
            speakerOn = a?.speakerOn == true,
            micAvailable = usable && targetStatus == CallStatus.ACTIVE,
            micOff = a?.muted == true,
            keypadAvailable = usable && targetStatus == CallStatus.ACTIVE,
            hangUpAvailable = usable,
            watchesCall = true,
            openPhoneOffered = placed != null && !closed && !ended && (lost || target == null),
        )
    }

    private fun doClose() {
        if (closed) return
        stopTones()
        unmuteIfWeMuted()
        closed = true
        bindTimeout?.cancel()
        bindTimeout = null
        hangUpTimeout?.cancel()
        hangUpTimeout = null
        stopService()
        target = null
        others.clear()
        port = null
        audio = null
        updatePanel()
        log("telecom: closed")
    }

    private fun stopService() {
        InCallHub.detach(this)
        try {
            phone.setInCallServiceEnabled(false)
        } catch (t: Throwable) {
            log("telecom: in-call service not disabled: ${t.javaClass.simpleName}")
        }
    }

    private fun emit(e: CallEvent) {
        listener?.invoke(e)
    }

    companion object {
        private const val DTMF_DIGITS = "0123456789*#"
        private const val SIM_CHOICE = "どの SIM を使うかを選ぶ必要があり、選ぶ人がいませんでした"

        /** AppOpsManager.OPSTR_MANAGE_ONGOING_CALLS, which is a system API. */
        private const val OP_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls"

        fun create(context: Context, approvedNumber: String, nobodyThere: Boolean = false, log: (String) -> Unit): TelecomCallControl =
            TelecomCallControl(
                AndroidTelecomPhone(context),
                approvedNumber,
                HandlerScheduler(Handler(Looper.getMainLooper()), onError = { log("telecom: step failed ${it.javaClass.simpleName}") }),
                log,
                Config(nobodyThere = nobodyThere),
            )

        /**
         * For the screens: null when fully automatic control can work on this phone, otherwise why not.
         * The runtime permissions are asked when the call starts, so they are not checked here.
         */
        fun readinessProblem(context: Context, appOpAtCallTime: Boolean = false): String? {
            val calling = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_CALLING else PackageManager.FEATURE_TELEPHONY
            if (!context.packageManager.hasSystemFeature(calling)) return "この端末は電話をかけられません"
            // [appOpAtCallTime]: it is set just before the call (ADR-049)
            if (!appOpAtCallTime && !appOpAllowed(context)) {
                return "全自動で電話をかける許可がありません。設定の「困ったとき」から診断の画面を開いて許可してください"
            }
            return null
        }

        /** The same check as Telecom's: only MODE_ALLOWED counts (the permission itself is signature only). */
        // Deprecated in SDK 37 for checkOpNoThrow(String, Int, String), whose String form is new there;
        // the phone runs Android 16, where this one is the current form
        @Suppress("DEPRECATION")
        fun appOpAllowed(context: Context): Boolean = try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val op = AppOpsManager.permissionToOp(Manifest.permission.MANAGE_ONGOING_CALLS) ?: OP_MANAGE_ONGOING_CALLS
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }
}

/** What [TelecomCallControl] needs from Android: [AndroidTelecomPhone] in the app, a fake in tests. */
interface TelecomPhone {
    /** null, or why calls cannot be controlled now (no telephony, the app op, permissions). */
    fun controlProblem(): String?

    /** TelecomManager.isInCall: any call, other apps' calls included. Throws without READ_PHONE_STATE. */
    fun isInCall(): Boolean

    /** TelephonyManager.isEmergencyNumber for one form of the number. Throws when the phone cannot tell. */
    fun isEmergencyNumber(number: String): Boolean

    fun setInCallServiceEnabled(enabled: Boolean)

    /** TelecomManager.placeCall to tel:[digits]. */
    fun placeCall(digits: String)

    /** Milliseconds since the epoch, the clock of Call.Details.getCreationTimeMillis(). */
    fun wallClockMs(): Long
}

class AndroidTelecomPhone(context: Context) : TelecomPhone {
    private val app = context.applicationContext
    private val emergency = EmergencyNumbers.of(app)

    override fun controlProblem(): String? {
        TelecomCallControl.readinessProblem(app)?.let { return it }
        for (p in listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)) {
            if (app.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return UserMessages.missingPermissions(listOf(p))
        }
        return null
    }

    override fun isInCall(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("READ_PHONE_STATE")
        }
        return app.getSystemService(TelecomManager::class.java).isInCall
    }

    override fun isEmergencyNumber(number: String): Boolean = emergency(number)

    override fun setInCallServiceEnabled(enabled: Boolean) = RotepInCallService.setEnabled(app, enabled)

    override fun placeCall(digits: String) {
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("CALL_PHONE")
        }
        app.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, digits, null), Bundle())
    }

    override fun wallClockMs(): Long = System.currentTimeMillis()
}

/** [CallScheduler] on a Handler's thread. A step that throws is reported to [onError], never thrown on. */
class HandlerScheduler(
    private val handler: Handler,
    private val onError: (Throwable) -> Unit = {},
) : CallScheduler {
    override fun now(): Long = SystemClock.uptimeMillis()

    override fun post(task: () -> Unit) {
        handler.post(wrap(task))
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): CallScheduler.Cancellable {
        val r = wrap(task)
        handler.postDelayed(r, delayMs)
        return CallScheduler.Cancellable { handler.removeCallbacks(r) }
    }

    private fun wrap(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
