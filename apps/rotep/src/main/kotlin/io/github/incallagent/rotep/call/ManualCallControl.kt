// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.edit
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.shell.CallPicture
import io.github.incallagent.rotep.shell.HelperClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual control: the app places the call with TelecomManager.placeCall (CALL_PHONE), only to the
 * approved number ([DialCheck]). Everything that can be done from here without the permission of
 * fully automatic control is done from here:
 * - The answer and the end of the call are seen through the helper ([ManualCallWatch]): the AI starts
 *   when the far end answers, with nothing to press. [answered] and [userHungUp] ("相手が出た",
 *   "切った") remain as the fallback for when the helper cannot tell
 * - "電話を切る" and the AI's end_call end the call through the helper ([userHangUp], [hangUp]). When
 *   that cannot be done, the user is asked to and gets the microphone back at once
 * - The phone's microphone is stopped with AudioManager ([setMuted]), which is what the phone app's
 *   own button does
 * Speaker and push tones work only through Telecom's InCallService (fully automatic control).
 *
 * A problem with the helper never ends the call or stops the AI: it only brings back the buttons
 * and the instructions ([instruction]). A hold or a second call does stop the AI, as in fully automatic control
 * ([CallEvent.Failed]).
 *
 * State lives on [scheduler]'s thread; the methods here post to it. The helper's answers take a
 * moment, so that thread is not the session's.
 */
class ManualCallControl internal constructor(
    private val phone: ManualPhone,
    private val approvedNumber: String,
    private val scheduler: CallScheduler,
    private val log: (String) -> Unit,
    private val config: Config = Config(),
) : CallControl {
    constructor(context: Context, approvedNumber: String, log: (String) -> Unit = {}) : this(
        AndroidManualPhone(context),
        approvedNumber,
        ExecutorScheduler("call-manual", onError = { log("manual: step failed ${it.javaClass.simpleName}") }),
        log,
    )

    internal data class Config(
        val ringingLookMs: Long = 500,
        val talkingLookMs: Long = 1_000,
        /** The call has not ended this long after "電話を切る": back to the phone's screen. */
        val hangUpTimeoutMs: Long = 10_000,
        val watch: ManualCallWatch.Config = ManualCallWatch.Config(),
    )

    private val _instruction = MutableStateFlow<String?>(null)

    /** What the user has to do by hand now, or null. */
    val instruction: StateFlow<String?> = _instruction

    // watchesCall until dialing says otherwise: "相手が出た" does not flash up before the call exists
    private val _panel = MutableStateFlow(CallPanel(watchesCall = true))
    override val panel: StateFlow<CallPanel> = _panel

    @Volatile private var listener: ((CallEvent) -> Unit)? = null

    // Only on the scheduler's thread
    private var dialed = false
    private var active = false
    private var ended = false
    private var closed = false
    private var watch: ManualCallWatch? = null
    private var hangUpAsked = false
    private var hangUpTimeout: CallScheduler.Cancellable? = null
    private var hangUpRetry: CallScheduler.Cancellable? = null

    /** The app stopped the phone's microphone and has not given it back yet. */
    private var micStoppedHere = false
    private var micOff = false

    /** The microphone could not be stopped from here: it is the user's, as the instructions say. */
    private var micByHand = false

    /** The user was asked to mute the phone's microphone and has not been asked to unmute it yet. */
    private var askedToMute = false

    /** The call is the user's to end: the microphone is not stopped any more, "切った" may be forgotten. */
    private var leftToUser = false

    override fun setListener(listener: (CallEvent) -> Unit) {
        this.listener = listener
    }

    override fun dial(number: String) = scheduler.post { doDial(number) }

    /** The user pressed "相手が出た". */
    fun answered() = scheduler.post { markAnswered() }

    /** The user pressed "切った". */
    fun userHungUp() = scheduler.post {
        if (!closed) endWith(DisconnectReasons.USER)
    }

    /** The AI said goodbye: the call is ended here too, and left to the user only when that cannot be done. */
    override fun hangUp() = scheduler.post { endTheCall() }

    /** The user pressed "電話を切る". */
    override fun userHangUp() = scheduler.post { endTheCall() }

    override fun sendDtmf(digits: String) = scheduler.post {
        if (!active || ended || closed) return@post
        val ask = "電話の画面のキーパッドで「$digits」を押してください"
        _instruction.value = ask
        // Nothing tells when it was done: it goes away by itself
        scheduler.postDelayed(DTMF_ASK_MS) { if (_instruction.value == ask) _instruction.value = null }
    }

    override fun setMuted(muted: Boolean) = scheduler.post { doMute(muted) }

    override fun close() {
        listener = null
        scheduler.post { doClose() }
    }

    // --- steps (on the scheduler) ---

    private fun doDial(number: String) {
        if (dialed || closed) return
        val digits = when (val check = DialCheck.check(number, approvedNumber)) {
            is DialCheck.Result.Refused -> return emit(CallEvent.Failed(check.reason))
            is DialCheck.Result.Ok -> check.digits
        }
        phone.callProblem()?.let { return emit(CallEvent.Failed(it)) }
        placeWhenFree(digits, scheduler.now() + ENDING_WAIT_MS)
    }

    /** The last call can take a moment to go away: one that is only on its way out is waited for. */
    private fun placeWhenFree(digits: String, deadline: Long) {
        if (dialed || closed) return
        // Before the call exists: what comes later is then this call
        val before = runCatching { phone.callPicture() }.getOrDefault(CallPicture.UNKNOWN)
        if (before == CallPicture.ENDING && scheduler.now() < deadline) {
            scheduler.postDelayed(ENDING_LOOK_MS) { placeWhenFree(digits, deadline) }
            return
        }
        when (before) {
            // UNKNOWN only says that the helper could not read it: the call goes on, with the buttons
            CallPicture.NONE, CallPicture.UNKNOWN -> {}
            CallPicture.RINGING_ONLY -> return emit(CallEvent.Failed(RINGING_NOW))
            else -> return emit(CallEvent.Failed(IN_ANOTHER_CALL))
        }
        try {
            phone.placeCall(digits)
        } catch (e: SecurityException) {
            return emit(CallEvent.Failed("発信が許可されませんでした"))
        } catch (t: Throwable) {
            log("manual: place failed ${t.javaClass.simpleName}")
            return emit(CallEvent.Failed("発信できませんでした。少し待ってから、もう一度試してください"))
        }
        dialed = true
        log("manual: call placed")
        if (before == CallPicture.NONE) {
            watch = ManualCallWatch(scheduler.now(), config.watch)
            lookLater()
        } else {
            log("manual: not watching (picture $before)")
            _instruction.value = ANSWER_BY_HAND
        }
        updatePanel()
        emit(CallEvent.Dialing)
    }

    private fun lookLater() {
        scheduler.postDelayed(if (active) config.talkingLookMs else config.ringingLookMs) { look() }
    }

    private fun look() {
        val w = watch ?: return
        if (closed || ended) return
        val picture = runCatching { phone.callPicture() }.getOrDefault(CallPicture.UNKNOWN)
        when (w.look(picture, scheduler.now())) {
            ManualCallWatch.Step.KEEP -> {}
            ManualCallWatch.Step.ANSWERED -> {
                log("manual: answer seen")
                markAnswered()
            }
            ManualCallWatch.Step.ENDED -> {
                log("manual: call gone")
                endWith(if (hangUpAsked) DisconnectReasons.LOCAL else UNKNOWN_END)
                return
            }
            ManualCallWatch.Step.GAVE_UP -> {
                log("manual: stopped watching (picture $picture)")
                watchGaveUp(picture)
            }
        }
        if (micStoppedHere || active) micOff = runCatching { phone.isMicMuted() }.getOrDefault(micOff)
        updatePanel()
        if (watch != null) lookLater()
    }

    private fun watchGaveUp(picture: Int) {
        // Nothing will show any more whether a hang-up under way worked: only the user can finish it
        if (hangUpAsked || hangUpRetry != null) {
            leaveHangUpToUser()
        } else {
            stopWatching()
            if (!active) _instruction.value = ANSWER_BY_HAND
        }
        // As in automatic control the AI does not go on into a held or a second call. "Cannot tell" leaves the call as it is
        if (active && picture == CallPicture.OTHER) emit(CallEvent.Failed(HOLD_OR_SECOND_CALL))
    }

    private fun markAnswered() {
        if (!dialed || active || ended || closed) return
        active = true
        _instruction.value = null
        updatePanel()
        emit(CallEvent.Active)
    }

    private fun endTheCall(lastTry: Boolean = false) {
        if (!dialed || ended || closed || hangUpAsked) return
        if (!lastTry && hangUpRetry != null) return
        if (watch == null) return leaveHangUpToUser()
        val asked = runCatching { phone.endCall() }.getOrDefault(false)
        log("manual: hang up asked=$asked")
        if (!asked && !lastTry) {
            // The helper could not see the call just then, or it is ending by itself: the watch goes on
            hangUpRetry = scheduler.postDelayed(HANG_UP_RETRY_MS) {
                hangUpRetry = null
                endTheCall(lastTry = true)
            }
            return
        }
        if (!asked) return leaveHangUpToUser()
        hangUpAsked = true
        if (hangUpTimeout == null) {
            hangUpTimeout = scheduler.postDelayed(config.hangUpTimeoutMs) {
                hangUpTimeout = null
                if (!ended && !closed) {
                    log("manual: not ended after the hang up")
                    leaveHangUpToUser()
                }
            }
        }
    }

    /**
     * The app cannot end this call. The user does it on the phone's screen, and has the microphone
     * back from now on: it is the whole phone's, and "切った" may be forgotten.
     */
    private fun leaveHangUpToUser() {
        leftToUser = true
        _instruction.value = HANG_UP_BY_HAND
        giveBackMic()
        stopWatching()
        updatePanel()
    }

    /** The AudioManager calls are the ones the phone app's own microphone button makes (ADR-032's mute is another stream). */
    private fun doMute(muted: Boolean) {
        if (closed || ended || (muted && leftToUser)) return
        val done = runCatching { phone.setMicMuted(muted) }.getOrDefault(false)
        if (done) {
            micStoppedHere = muted
            micOff = muted
            // "戻す" also takes away an instruction that no longer applies, as it always did
            if (!muted) {
                askedToMute = false
                showMicInstruction(null)
            }
        } else {
            log("manual: microphone not as asked for")
            micByHand = true
            // Also when it was stopped from here: it must not stay stopped without a word
            showMicInstruction(muteInstruction(muted, askedToMute || micStoppedHere))
            askedToMute = muted
        }
        updatePanel()
    }

    /** Whatever happens to the microphone, a call left for the user to end still has to be ended. */
    private fun showMicInstruction(text: String?) {
        if (text != null || _instruction.value != HANG_UP_BY_HAND) _instruction.value = text
    }

    /** The microphone applies to the whole phone: never leave it stopped. */
    private fun giveBackMic() {
        if (!micStoppedHere) return
        micStoppedHere = false
        runCatching { phone.setMicMuted(false) }
        micOff = runCatching { phone.isMicMuted() }.getOrDefault(micOff)
    }

    private fun endWith(reason: String) {
        if (!dialed || ended) return
        ended = true
        stopWatching()
        giveBackMic()
        _instruction.value = null
        updatePanel()
        emit(CallEvent.Ended(reason))
    }

    private fun stopWatching() {
        watch = null
        hangUpTimeout?.cancel()
        hangUpTimeout = null
        hangUpRetry?.cancel()
        hangUpRetry = null
    }

    private fun updatePanel() {
        val going = dialed && !ended && !closed
        _panel.value = CallPanel(
            micAvailable = going && active && !micByHand && !leftToUser,
            micOff = micOff,
            hangUpAvailable = going && watch != null,
            watchesCall = going && watch != null,
            openPhoneOffered = going,
        )
    }

    private fun doClose() {
        if (closed) return
        closed = true
        stopWatching()
        giveBackMic()
        _instruction.value = null
        updatePanel()
        (scheduler as? ExecutorScheduler)?.shutdown()
    }

    private fun emit(e: CallEvent) {
        listener?.invoke(e)
    }

    companion object {
        private const val ANSWER_BY_HAND = "相手が出たら、この画面の「相手が出た」を押してください"
        private const val HANG_UP_RETRY_MS = 3_000L
        private const val HANG_UP_BY_HAND = "電話の画面で電話を切ってから、「切った」を押してください"
        private const val HOLD_OR_SECOND_CALL = "保留になったか、ほかの通話が始まりました"
        private const val IN_ANOTHER_CALL = "ほかの通話の途中なので、電話をかけません"
        private const val RINGING_NOW = "着信が鳴っているので、電話をかけません"
        private const val DTMF_ASK_MS = 20_000L
        private const val ENDING_LOOK_MS = 300L
        private const val ENDING_WAIT_MS = 3_000L
        private const val UNKNOWN_END = "unknown"

        /**
         * What the user has to do on the phone screen when the AI needs the microphone [muted], or
         * gives the call back (false), and the app could not do it itself. Unmuting is asked for only
         * after muting was ([askedToMute]): in the loudspeaker mode and before the answer
         * nothing was muted, and pressing the mute button then would cut the user off. null: nothing
         * to do by hand (this also clears an instruction that no longer applies; the call screen
         * already tells the user to talk).
         */
        fun muteInstruction(muted: Boolean, askedToMute: Boolean): String? = when {
            muted -> "電話の画面で、端末のマイクを止めてください（AI の声だけが相手に届くように）"
            askedToMute -> "電話の画面で、止めた端末のマイクを戻して、ご自分で話してください"
            else -> null
        }
    }
}

/**
 * The phone's microphone as this app left it. It is stopped on the whole phone, so a process that
 * dies during the call would leave it stopped: written down here, and given back when the process
 * starts again ([io.github.incallagent.rotep.shell.HelperProvider]: the helper starts it within a
 * moment) and when the app comes to the front. One flag in the app's private preferences, nothing
 * about the call.
 */
internal class LeftMic(private val memo: Memo, private val mic: Mic) {
    interface Memo {
        var stopped: Boolean
    }

    interface Mic {
        var muted: Boolean
    }

    fun note() {
        memo.stopped = true
    }

    fun forget() {
        memo.stopped = false
    }

    /** Not during a call of ours, where it is stopped on purpose. When it does not come back, the note stays for the next try. */
    fun giveBackIfLeft() {
        if (!memo.stopped) return
        runCatching { mic.muted = false }
        if (runCatching { !mic.muted }.getOrDefault(false)) forget()
    }

    companion object {
        private const val PREFS = "call_sound"
        private const val KEY = "mic_stopped"

        fun of(context: Context): LeftMic {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val audio = app.getSystemService(AudioManager::class.java)
            return LeftMic(
                object : Memo {
                    override var stopped: Boolean
                        get() = prefs.getBoolean(KEY, false)
                        set(value) = prefs.edit { if (value) putBoolean(KEY, true) else remove(KEY) }
                },
                object : Mic {
                    override var muted: Boolean
                        get() = audio.isMicrophoneMute
                        set(value) {
                            audio.isMicrophoneMute = value
                        }
                },
            )
        }
    }
}

/** What [ManualCallControl] needs from Android and the helper: [AndroidManualPhone] in the app, a fake in tests. */
internal interface ManualPhone {
    /** null, or why no call can be placed. */
    fun callProblem(): String?

    /** TelecomManager.placeCall to tel:[digits]. */
    fun placeCall(digits: String)

    /** Stops or gives back the phone's microphone. false: it is not as asked for now. */
    fun setMicMuted(muted: Boolean): Boolean

    fun isMicMuted(): Boolean

    /** One of [CallPicture]. */
    fun callPicture(): Int

    /** Ends the one ongoing call. false: nothing was ended. */
    fun endCall(): Boolean
}

private class AndroidManualPhone(context: Context) : ManualPhone {
    private val app = context.applicationContext
    private val left = LeftMic.of(app)

    override fun callProblem(): String? =
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            UserMessages.missingPermissions(listOf(Manifest.permission.CALL_PHONE))
        } else {
            null
        }

    override fun placeCall(digits: String) {
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("CALL_PHONE")
        }
        app.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, digits, null), Bundle())
    }

    override fun setMicMuted(muted: Boolean): Boolean {
        val audio = app.getSystemService(AudioManager::class.java)
        if (muted) left.note() // before: a death right after it must still find this
        audio.isMicrophoneMute = muted
        val asAsked = audio.isMicrophoneMute == muted
        if (!muted && asAsked) left.forget()
        return asAsked
    }

    override fun isMicMuted(): Boolean = app.getSystemService(AudioManager::class.java).isMicrophoneMute

    override fun callPicture(): Int = HelperClient.callPicture()

    override fun endCall(): Boolean = HelperClient.endOngoingCall()
}
