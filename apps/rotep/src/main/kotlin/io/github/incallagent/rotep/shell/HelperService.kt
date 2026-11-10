// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The helper: it runs in its own process as the shell user (uid 2000), started from a PC with adb
 * and `app_process` (see [HelperMain]). Everything that needs shell power happens here; the app only
 * sends commands over [IHelperService] and receives messages over [IHelperCallback].
 *
 * [HelperMain] builds the Context (see [HelperContext]) and the shell-named [ShellContext], resolves
 * the app's uid and passes them in. The process is not a normal app process: do not expect every
 * Context API to work. Recording is done under the shell name ([ShellContext]); when that could not
 * be built, [shell] is null and the helper refuses to record.
 *
 * The helper is "connected" while it holds the app's callback ([attach]). When the app process dies
 * the helper does **not** end: it goes back to "not connected" and [HelperMain] hands its binder to
 * the app again (see [HelperLifetime]). The helper ends in these cases (the process must not stay
 * behind):
 *  - [destroy]: the app calls it for "終了", and for a helper that a newer one replaced;
 *  - [quit]: [HelperMain] calls it when the app refuses the helper because the versions differ;
 *  - it has been without the app for [HelperLifetime.DISCONNECT_LIMIT_MS].
 */
@Keep
internal class HelperService(
    private val context: Context,
    private val appUid: Int,
    private val shell: ShellContext?,
    /** [HelperVersion] of the installed app, as read when the helper started. */
    private val version: Int,
) : IHelperService.Stub() {
    private val ownUid = Process.myUid()

    private val lock = Any()
    private val exiting = AtomicBoolean(false)

    @Volatile
    private var callback: IHelperCallback? = null

    /** The recipient that is linked to the death of [callback] (null when not connected). */
    private var deathRecipient: IBinder.DeathRecipient? = null

    /**
     * SystemClock.uptimeMillis() of the moment the helper last had no app: its start, or the death of
     * the app. It is written before [callback] is cleared, so the watchdog never reads a stale value.
     * Uptime stops while the phone sleeps: the helper cannot reach the app then, and must not end the
     * moment the phone wakes up.
     */
    @Volatile
    private var disconnectedSinceMs = SystemClock.uptimeMillis()

    @Volatile
    private var meter: DownlinkMeter? = null

    init {
        Log.i(TAG, "started: uid=$ownUid pid=${Process.myPid()} version=$version")
        startWatchdog()
    }

    /** True while the helper holds the app's callback. */
    fun isConnected(): Boolean = callback != null

    // --- AIDL ---------------------------------------------------------------------------------

    override fun destroy() {
        if (!callerAllowed()) {
            Log.w(TAG, "destroy refused: caller not allowed")
            return
        }
        shutdown("終了の指示（destroy）")
    }

    /** Ends the helper. [HelperMain] calls this (locally) when the app refuses the helper. */
    fun quit(reason: String) = shutdown(reason)

    override fun attach(callback: IHelperCallback) {
        enforceCaller()
        val binder = callback.asBinder()
        val recipient = IBinder.DeathRecipient { onAppGone(binder) }
        // If the app died before this call, linkToDeath throws and the helper simply stays "not connected".
        binder.linkToDeath(recipient, 0)
        synchronized(lock) {
            val old = this.callback
            val oldRecipient = deathRecipient
            if (old != null && oldRecipient != null) attempt { old.asBinder().unlinkToDeath(oldRecipient, 0) }
            this.callback = callback
            deathRecipient = recipient
        }
        Log.i(TAG, "attached")
    }

    override fun helperInfo(): String {
        enforceCaller()
        return HelperInfoText.format(Process.myPid(), Process.myUid(), version)
    }

    override fun environmentReport(): String {
        enforceCaller()
        return asSelf {
            val meterLine = if (meter?.isRunning == true) "受話の音量の計測: 動作中" else "受話の音量の計測: 止まっている"
            EnvCollector(context, shell).collect(meterLine)
        }
    }

    override fun startDownlinkMeter(): Boolean {
        enforceCaller()
        val cb = callback ?: return false
        val shellContext = shell
        if (shellContext == null) {
            // Not running as the shell user: there is no package name that would be right.
            val report = EnvReport.formatMeter(
                MeterFacts(
                    phase = "失敗",
                    audioSource = null,
                    sampleRate = null,
                    triedRates = emptyList(),
                    routedDeviceType = null,
                    configFound = null,
                    clientAudioSource = null,
                    silenced = null,
                    configDeviceType = null,
                    attributionPackage = null,
                    attributionUid = Process.myUid(),
                ),
            )
            attempt { cb.onMeterStopped(report, "手伝い役の uid が 2000（shell）ではないため、録音しない") }
            return false
        }
        synchronized(lock) {
            if (meter?.isRunning == true) return false
            val audio = asSelf { context.getSystemService(AudioManager::class.java) } ?: return false
            val m = DownlinkMeter(shellContext, audio, MeterSink(cb))
            meter = m
            m.start()
            return true
        }
    }

    override fun stopDownlinkMeter() {
        enforceCaller()
        // The meter thread reports the stop by itself.
        meter?.requestStop()
    }

    // --- Ending ---------------------------------------------------------------------------------

    /** The app process died (the death of [binder], the callback it gave): back to "not connected". */
    private fun onAppGone(binder: IBinder) {
        synchronized(lock) {
            val current = callback
            // A death notice of an older callback is not about the app we are connected to.
            if (current == null || current.asBinder() !== binder) return
            disconnectedSinceMs = SystemClock.uptimeMillis()
            callback = null
            deathRecipient = null
        }
        Log.i(TAG, "the app is gone; the helper stays and tries to reach it again")
        // Nobody listens to the levels any more, and no marker tone may go on into a call alone.
        meter?.requestStop()
        uplinkTest?.requestStop()
        stopCallAudio()
        // The user has to be able to hear the call the AI has left
        releaseCallQuiet()
    }

    private fun shutdown(reason: String) {
        if (!exiting.compareAndSet(false, true)) return
        Log.i(TAG, "exit: $reason")
        // The call's sound first: nothing brings it back after this process has ended.
        attempt { releaseCallQuiet() }
        // The marker tone first: its track is released and its record sent before the process ends.
        attempt { audioBridge?.let { stopBridgeAndWait(it) } }
        attempt { uplinkTest?.let { stopUplinkAndWait(it) } }
        attempt { meter?.stopAndWait(STOP_WAIT_MS) }
        val cb = callback
        if (cb != null) {
            attempt { cb.onHelperExiting(reason) }
            val recipient = deathRecipient
            if (recipient != null) attempt { cb.asBinder().unlinkToDeath(recipient, 0) }
        }
        // The process must end here: unbinding alone would leave it running.
        System.exit(0)
    }

    /** Ends the helper when it has been without the app for too long. */
    private fun startWatchdog() {
        val t = Thread({
            while (!exiting.get()) {
                try {
                    Thread.sleep(HelperLifetime.POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // callback is read first: when it is null, disconnectedSinceMs is already the new value.
                if (callback == null &&
                    HelperLifetime.shouldEnd(SystemClock.uptimeMillis() - disconnectedSinceMs)
                ) {
                    shutdown("アプリにつながらないまま ${HelperLifetime.DISCONNECT_LIMIT_MS / 60_000} 分たった")
                    return@Thread
                }
            }
        }, "helper-watchdog")
        t.isDaemon = true
        t.start()
    }

    // --- Caller check -------------------------------------------------------------------------

    private fun callerAllowed(): Boolean = CallerPolicy.isAllowed(Binder.getCallingUid(), appUid, ownUid)

    private fun enforceCaller() {
        if (!callerAllowed()) throw SecurityException("呼び出し元が許可されていない")
    }

    // --- Messages to the app ------------------------------------------------------------------

    private inner class MeterSink(private val cb: IHelperCallback) : DownlinkMeter.Sink {
        override fun onStarted(report: String) = send { cb.onMeterStarted(report) }

        override fun onLevel(level: Level, totalFrames: Long) =
            send { cb.onLevel(level.peakDbfs, level.rmsDbfs, totalFrames) }

        override fun onStopped(report: String, reason: String) = send { cb.onMeterStopped(report, reason) }

        private inline fun send(block: () -> Unit) {
            try {
                block()
            } catch (_: RemoteException) {
                // The app is gone: nobody listens, so stop measuring.
                meter?.requestStop()
            }
        }
    }

    private companion object {
        const val TAG = "RotepHelper"
        const val STOP_WAIT_MS = 1_000L

        /** Telecom makes the call audible as it goes active: the mute has to come after that. */
        const val QUIET_DELAY_MS = 1_500L
    }

    // --- Uplink test ----------------------------------------------------------------------------
    // Kept together at the end of the class; the work is done by UplinkTest.

    @Volatile
    private var uplinkTest: UplinkTest? = null

    override fun startUplinkTest(test: Int, delaySeconds: Int, playSeconds: Int, levelDbfs: Int): Boolean {
        enforceCaller()
        val cb = callback ?: return false
        synchronized(lock) {
            if (uplinkTest?.isActive == true) return false
            // Never a marker tone into a call where the AI talks: the call audio comes first
            if (audioBridge?.isActive == true) return false
            val audio = asSelf { context.getSystemService(AudioManager::class.java) } ?: return false
            val t = UplinkTests.create(shell, audio, test, delaySeconds, playSeconds, levelDbfs, UplinkSink(cb))
                ?: return false
            uplinkTest = t
            t.start()
            return true
        }
    }

    override fun stopUplinkTest() {
        enforceCaller()
        // The test thread sends its record by itself.
        uplinkTest?.requestStop()
    }

    /** Asks [test] to stop and waits up to [STOP_WAIT_MS] for its thread to end (used when the helper ends). */
    private fun stopUplinkAndWait(test: UplinkTest) {
        test.requestStop()
        val until = SystemClock.elapsedRealtime() + STOP_WAIT_MS
        while (test.isActive && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
    }

    private inner class UplinkSink(private val cb: IHelperCallback) : UplinkTestRun.Sink {
        override fun onPhase(phase: Int, log: String) {
            try {
                cb.onUplinkTest(phase, log)
            } catch (_: RemoteException) {
                // The app is gone: nobody listens, so stop the sound.
                uplinkTest?.requestStop()
            }
        }
    }

    // --- Audio bridge (1b) -----------------------------------------------------------------------
    // Kept together at the end of the class; the work is done by CallAudioBridge.

    @Volatile
    private var audioBridge: CallAudioBridge? = null

    override fun startAudioBridge(
        uplinkMode: Int,
        keepCallQuiet: Boolean,
        downlinkWriteEnd: ParcelFileDescriptor,
        uplinkReadEnd: ParcelFileDescriptor,
    ): Boolean {
        enforceCaller()
        val cb = callback
        if (cb == null) {
            attempt { downlinkWriteEnd.close() }
            attempt { uplinkReadEnd.close() }
            return false
        }
        synchronized(lock) {
            if (audioBridge?.isActive == true) {
                attempt { downlinkWriteEnd.close() }
                attempt { uplinkReadEnd.close() }
                return false
            }
            val audio = asSelf { context.getSystemService(AudioManager::class.java) }
            if (audio == null) {
                attempt { downlinkWriteEnd.close() }
                attempt { uplinkReadEnd.close() }
                return false
            }
            // A marker tone that still waits for its delayed start must not play into this call
            uplinkTest?.let { stopUplinkAndWait(it) }
            // A bridge that cannot run here (not in a call, no TELEPHONY output, ...) has already
            // reported FAILED with the reason through the callback and closed both ends: answer
            // true, so the app waits for that report instead of guessing a reason.
            // The speaker mode's voice must be heard: it reaches the far end through the microphone
            val quiet = if (keepCallQuiet && uplinkMode != AudioUplinkMode.SPEAKER) {
                CallQuiet(AudioManagerVoiceCall(AudioManagerVoiceCall.audioManagerFor(shell, audio))) { Log.i(TAG, it) }
            } else {
                null
            }
            val watch = quiet?.let { q ->
                SilentDownlinkWatch(SystemClock::elapsedRealtime) {
                    Log.i(TAG, "call quiet: the downlink went silent after the mute, giving the sound back")
                    q.release()
                }
            }
            val bridge = CallAudioBridge.create(
                shell,
                audio,
                uplinkMode,
                downlinkWriteEnd,
                uplinkReadEnd,
                AudioBridgeSink(cb),
                downlinkObserver = { pcm, frames -> watch?.feed(pcm, frames) },
            ) ?: return true
            audioBridge = bridge
            callQuiet = quiet
            bridge.start()
            if (quiet != null && watch != null) engageCallQuietLater(bridge, quiet, watch)
            return true
        }
    }

    @Volatile
    private var callQuiet: CallQuiet? = null

    /** After [QUIET_DELAY_MS], while [bridge] still runs. [CallQuiet.engage] does nothing once released. */
    private fun engageCallQuietLater(bridge: CallAudioBridge, quiet: CallQuiet, watch: SilentDownlinkWatch) {
        val t = Thread({
            try {
                Thread.sleep(QUIET_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (bridge.isActive && quiet.engage()) watch.arm()
        }, "helper-call-quiet")
        t.isDaemon = true
        t.start()
    }

    /** Gives the call its sound back, if it was kept quiet. Safe to call more than once. */
    private fun releaseCallQuiet() {
        callQuiet?.release()
        callQuiet = null
    }

    override fun restoreCallSound() {
        enforceCaller()
        releaseCallQuiet()
    }

    override fun stopAudioBridge() {
        enforceCaller()
        stopCallAudio()
    }

    /**
     * Stops the call audio (the bridge of 1b) if it is running. For the helper's own use: when the
     * app is gone, and when the helper ends. Does no caller check, because it is not reachable over
     * Binder. Either side of the bridge reports its own end; this only asks it to begin stopping.
     * (The bridge also ends by itself when the app process dies: the pipes close.)
     */
    fun stopCallAudio() {
        audioBridge?.requestStop()
    }

    /** Asks [bridge] to stop and waits up to [STOP_WAIT_MS] for it to finish (used when the helper ends). */
    private fun stopBridgeAndWait(bridge: CallAudioBridge) {
        bridge.requestStop()
        val until = SystemClock.elapsedRealtime() + STOP_WAIT_MS
        while (bridge.isActive && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
    }

    private inner class AudioBridgeSink(private val cb: IHelperCallback) : CallAudioBridge.Sink {
        override fun onEvent(code: Int, detail: String) = reportThenGiveSoundBack(
            code,
            report = {
                try {
                    cb.onAudioBridge(code, detail)
                } catch (_: RemoteException) {
                    audioBridge?.requestStop()
                }
            },
            giveSoundBack = { releaseCallQuiet() },
        )
    }

    // --- Call control for manual calls ----------------------------------------------------------
    // Telecom gives speaker and DTMF only to an InCallService (fully automatic control); these are the rest.

    override fun callPicture(): Int {
        enforceCaller()
        return CallPicture.of(ShellCommands.callPhones())
    }

    override fun endOngoingCall(): Boolean {
        enforceCaller()
        // Read again now: TelecomManager.endCall ends the foreground call, whichever that is by now
        if (!CallPicture.mayEndCall(ShellCommands.callPhones())) {
            Log.i(TAG, "end call refused: not exactly one call")
            return false
        }
        return asSelf {
            attempt { endCallThroughTelecom() } == true
        }
    }

    @Suppress("DEPRECATION") // The call control API replaced it for companion apps; nothing else ends a call for the shell
    @SuppressLint("MissingPermission") // The helper is the shell user, which holds MODIFY_PHONE_STATE
    private fun endCallThroughTelecom(): Boolean = telecomForShell()?.endCall() == true

    override fun bringAppToFront(): Boolean {
        enforceCaller()
        return ShellCommands.bringAppToFront()
    }

    @SuppressLint("MissingPermission") // The helper is the shell user, which holds READ_PRIVILEGED_PHONE_STATE
    override fun showPhoneCallScreen(): Boolean {
        enforceCaller()
        return asSelf {
            val telecom = telecomForShell() ?: return@asSelf false
            attempt {
                telecom.showInCallScreen(false)
                true
            } == true
        }
    }

    /**
     * A TelecomManager that names the shell package. Telecom checks the caller's package against
     * its uid ([android.app.AppOpsManager.checkPackage]); the helper's own Context names "android".
     * The constructor is hidden; this code runs only in the helper, a bare app_process of the shell
     * user, where the hidden-API enforcement is off (see HelperContext).
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
    private fun telecomForShell(): TelecomManager? {
        val own = shell ?: return null
        return attempt {
            TelecomManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(own)
        }
    }
}
