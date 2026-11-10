// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.app.Application
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class HelperPhase(val label: String) {
    /** No helper is connected (not started yet, or it has ended). */
    STOPPED("つながっていない"),
    RUNNING("動いている"),

    /** The end was asked for; waiting for the helper's process to go. */
    STOPPING("終了中…"),
}

/** The downlink meter as the screen sees it. */
data class MeterUi(
    /** The start was asked for and the helper has not answered yet. */
    val requested: Boolean = false,
    val running: Boolean = false,
    val peakDbfs: Float = LevelMeter.FLOOR_DBFS,
    val rmsDbfs: Float = LevelMeter.FLOOR_DBFS,
    val totalFrames: Long = 0,
    /** SystemClock.elapsedRealtime() of the last level message. */
    val lastLevelAtMs: Long = 0,
    val startedAtMs: Long = 0,
    val startReport: String = "",
    val stopReport: String = "",
    val stopReason: String = "",
)

data class DiagUiState(
    /** The commands the person runs on the PC to start the helper (two lines, see [LaunchCommand]). */
    val launchCommand: String = "",
    val phase: HelperPhase = HelperPhase.STOPPED,
    val helperInfo: String = "",
    val report: String = "",
    val reportBusy: Boolean = false,
    val meter: MeterUi = MeterUi(),
    val events: List<String> = emptyList(),
    val uplink: UplinkUi = UplinkUi(),
)

/**
 * The app's reply to a helper that tried to register (see [HelperProvider]). When it is a refusal,
 * [retry] says whether the helper should try again later (true) or end (false).
 */
data class RegisterReply(val accepted: Boolean, val callback: IBinder?, val reason: String?, val retry: Boolean)

/**
 * Links [recipient] to the death of the helper behind [binder]. Returns null when that worked. Returns
 * the refusal to send back when the helper's process is already gone: linkToDeath throws a
 * RemoteException then, and a helper that is gone must never be shown as running. The refusal says
 * "try again" ([RegisterReply.retry]), so a helper that is started again is taken as usual. Any other
 * failure is not hidden: it goes up to [HelperProvider], which answers it with a refusal as well.
 */
internal fun linkToHelperDeath(binder: IBinder, recipient: IBinder.DeathRecipient): RegisterReply? =
    try {
        binder.linkToDeath(recipient, 0)
        null
    } catch (_: RemoteException) {
        RegisterReply(accepted = false, callback = null, reason = "手伝い役がもういない", retry = true)
    }

/**
 * App side of the helper. The helper is started by the person with the adb commands this object
 * shows; it registers itself through [HelperProvider], which calls [onHelperRegistered]. From then
 * on this object passes commands to the helper and shows its messages. One object for the whole
 * process, so the state survives when the screen is recreated.
 *
 * The helper outlives this app process: when the app dies the helper stays, and registers again as
 * soon as the app is back (the registration itself starts the app's process). A helper that registers
 * while another one is connected replaces it, and the older one is told to end. The helper's end:
 * [stopHelper] calls the helper's destroy directly, and its process ends.
 *
 * Nothing here logs phone numbers, names or call audio: events and reports hold only fixed texts,
 * ids and exception class names.
 */
object HelperClient {
    private const val TAG = "RotepDiag"
    private const val STOP_TIMEOUT_MS = 3_000L
    private const val MAX_EVENTS = 12

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rotep-diag-io").apply { isDaemon = true }
    }
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    private val mutableState = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = mutableState.asStateFlow()

    private lateinit var app: Application
    private var initialized = false

    @Volatile
    private var service: IHelperService? = null

    /**
     * The binder of the helper this object counts as current (running, or being ended). A death
     * notice from any other binder (an older helper that dies late) is ignored.
     */
    @Volatile
    private var currentBinder: IBinder? = null

    // --- Setup --------------------------------------------------------------------------------

    @Synchronized
    fun init(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        mutableState.update { it.copy(launchCommand = LaunchCommand.build()) }
    }

    // --- Registration (called by HelperProvider, on a binder thread) --------------------------

    // Synchronized so that two helpers that register at the same moment cannot both be accepted.
    @Synchronized
    fun onHelperRegistered(serviceBinder: IBinder, version: Int, pid: Int, uid: Int): RegisterReply {
        if (!initialized) return RegisterReply(false, null, "アプリの準備ができていない", retry = true)
        val installed = InstalledApp.read(app.packageManager, app.packageName)
            ?: return RegisterReply(false, null, "アプリの版を読めなかった", retry = true)
        if (!VersionGate.accepts(installed.version, version)) {
            event("版が合わない手伝い役を断った")
            // For good: a helper from an older install must end, and the person starts a new one.
            return RegisterReply(false, null, "版が合わない", retry = false)
        }
        // Link to the helper's death before anything is changed. It fails when the helper's process is
        // already gone (it died after its call and before this line). Then nothing here changes: the
        // state does not become "running", an older helper is not ended, a running STOPPING timeout is
        // not cancelled, and the helper gets a refusal that lets it try again. A death notice that comes
        // at once waits for this method to end (handleHelperDeath takes the same lock), so it finds the
        // helper below as the current one.
        val gone = linkToHelperDeath(serviceBinder, IBinder.DeathRecipient { main.post { handleHelperDeath(serviceBinder) } })
        if (gone != null) {
            event("手伝い役が、つながる前にいなくなっていた。登録を断った")
            return gone
        }
        // A helper that registers while another one is connected replaces it.
        val older = service
        val olderBinder = currentBinder
        main.removeCallbacks(stopTimeout)
        val svc = IHelperService.Stub.asInterface(serviceBinder)
        service = svc
        currentBinder = serviceBinder
        mutableState.update {
            it.copy(
                phase = HelperPhase.RUNNING,
                helperInfo = "pid=$pid uid=$uid",
                meter = if (older != null) MeterUi() else it.meter,
            )
        }
        if (older != null && olderBinder !== serviceBinder) {
            event("新しい手伝い役に替えた。古いほうを終わらせる")
            io.execute { attempt { older.destroy() } }
        }
        event("手伝い役がつながった（pid=$pid uid=$uid）")
        io.execute { verifyHelper(svc, installed.version) }
        return RegisterReply(true, callback.asBinder(), null, retry = false)
    }

    /**
     * Asks the helper who it is and checks the version it reports. This is the second check, after the
     * gate in [onHelperRegistered]; a helper that does not report a matching version is ended.
     */
    private fun verifyHelper(svc: IHelperService, expectedVersion: Int) {
        val info = attempt { svc.helperInfo() } ?: return
        // A newer helper may have replaced this one in the meantime: then this one is not ours to judge.
        if (service !== svc) return
        if (!VersionGate.accepts(expectedVersion, HelperInfoText.parseVersion(info))) {
            event("手伝い役が名乗った版が合わない")
            stopHelper("版が合わない")
            return
        }
        mutableState.update { if (it.phase == HelperPhase.RUNNING) it.copy(helperInfo = info) else it }
    }

    // --- The helper ---------------------------------------------------------------------------

    private val stopTimeout = Runnable {
        if (mutableState.value.phase == HelperPhase.STOPPING) {
            currentBinder = null
            mutableState.update { it.copy(phase = HelperPhase.STOPPED) }
            event("手伝い役が終わった知らせが来なかった。念のため adb で残っていないか確かめる")
        }
    }

    /**
     * The helper's process ended (its binder died). Runs on the main thread, under this object's lock
     * like [onHelperRegistered], so it never sees the state half way through a registration.
     */
    @Synchronized
    private fun handleHelperDeath(binder: IBinder) {
        if (binder !== currentBinder) return
        currentBinder = null
        main.removeCallbacks(stopTimeout)
        val phase = mutableState.value.phase
        if (phase == HelperPhase.STOPPED) return
        service = null
        mutableState.update {
            it.copy(phase = HelperPhase.STOPPED, helperInfo = "", meter = it.meter.copy(requested = false, running = false))
        }
        event(if (phase == HelperPhase.STOPPING) "手伝い役が終わった" else "手伝い役との接続が切れた（手伝い役は終わった）")
    }

    /**
     * Ends the helper for good: its process must be gone afterwards. Safe to call at any time and
     * more than once. While it ends, the screen is in [HelperPhase.STOPPING].
     */
    fun stopHelper(reason: String) {
        val svc = service
        service = null
        // The death notice is kept on purpose: it is what tells the screen that the helper is gone.
        if (mutableState.value.phase == HelperPhase.RUNNING) {
            mutableState.update {
                it.copy(phase = HelperPhase.STOPPING, meter = it.meter.copy(requested = false, running = false))
            }
            event("手伝い役を終わらせる（$reason）")
            main.removeCallbacks(stopTimeout)
            main.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
        }
        io.execute {
            attempt { svc?.stopAudioBridge() }
            attempt { svc?.stopUplinkTest() }
            attempt { svc?.stopDownlinkMeter() }
            attempt { if (svc != null && svc.asBinder().pingBinder()) svc.destroy() }
        }
    }

    // --- Commands -----------------------------------------------------------------------------

    fun fetchReport() {
        val svc = service ?: run {
            event("先に手伝い役を起動する")
            return
        }
        mutableState.update { it.copy(reportBusy = true) }
        io.execute {
            val text = try {
                svc.environmentReport()
            } catch (t: Throwable) {
                "報告を取れなかった: ${ReportText.exceptionLine(t)}"
            }
            logLines("report", text)
            mutableState.update { it.copy(report = text, reportBusy = false) }
        }
    }

    fun startMeter() {
        val svc = service ?: run {
            event("先に手伝い役を起動する")
            return
        }
        mutableState.update { it.copy(meter = MeterUi(requested = true)) }
        io.execute {
            try {
                if (!svc.startDownlinkMeter()) {
                    mutableState.update { it.copy(meter = it.meter.copy(requested = false)) }
                    event("受話の音量を始められなかった（すでに動いているか、条件を満たしていない）")
                }
            } catch (t: Throwable) {
                mutableState.update { it.copy(meter = it.meter.copy(requested = false)) }
                event("受話の音量を頼めなかった: ${ReportText.exceptionLine(t)}")
            }
        }
    }

    fun stopMeter() {
        val svc = service ?: return
        io.execute {
            try {
                svc.stopDownlinkMeter()
            } catch (t: Throwable) {
                event("停止を頼めなかった: ${ReportText.exceptionLine(t)}")
            }
        }
    }

    // --- Messages from the helper (Binder threads) ----------------------------------------------

    private val callback = object : IHelperCallback.Stub() {
        override fun onLevel(peakDbfs: Float, rmsDbfs: Float, totalFrames: Long) {
            val now = SystemClock.elapsedRealtime()
            mutableState.update {
                it.copy(
                    meter = it.meter.copy(
                        peakDbfs = peakDbfs,
                        rmsDbfs = rmsDbfs,
                        totalFrames = totalFrames,
                        lastLevelAtMs = now,
                    ),
                )
            }
        }

        override fun onMeterStarted(report: String?) {
            val now = SystemClock.elapsedRealtime()
            val text = report.orEmpty()
            mutableState.update {
                it.copy(meter = MeterUi(running = true, startedAtMs = now, lastLevelAtMs = now, startReport = text))
            }
            logLines("meter-start", text)
            event("受話の音量の計測が始まった")
        }

        override fun onMeterStopped(report: String?, reason: String?) {
            val text = report.orEmpty()
            val why = reason.orEmpty()
            mutableState.update {
                it.copy(meter = it.meter.copy(requested = false, running = false, stopReport = text, stopReason = why))
            }
            logLines("meter-stop", text)
            event("受話の音量の計測が終わった（$why）")
        }

        override fun onHelperExiting(reason: String?) {
            event("手伝い役が終わる（${reason.orEmpty()}）")
        }

        override fun onUplinkTest(phase: Int, log: String?) = handleUplinkTest(phase, log.orEmpty())

        override fun onAudioBridge(event: Int, detail: String?) = handleAudioBridge(event, detail.orEmpty())
    }

    // --- Events -------------------------------------------------------------------------------

    private fun event(text: String) {
        val line = "${LocalTime.now().format(clock)} $text"
        Log.i(TAG, text)
        mutableState.update { it.copy(events = (it.events + line).takeLast(MAX_EVENTS)) }
    }

    /** Reports hold ids and fixed words only, so they may go to logcat. */
    private fun logLines(label: String, text: String) {
        for (line in text.lineSequence()) if (line.isNotBlank()) Log.i(TAG, "$label: $line")
    }

    // --- Uplink test ----------------------------------------------------------------------------

    private const val MAX_UPLINK_RECORDS = 12

    /** Starts one uplink test. The helper keeps the values inside their bounds (see UplinkTestRules). */
    fun startUplinkTest(kind: UplinkTestKind, delaySeconds: Int, playSeconds: Int, levelDbfs: Int) {
        val svc = service ?: run {
            event("先に手伝い役を起動する")
            return
        }
        val helper = mutableState.value.helperInfo
        val now = SystemClock.elapsedRealtime()
        mutableState.update {
            it.copy(
                uplink = it.uplink.copy(
                    requested = true,
                    phase = UplinkPhase.IDLE,
                    sinceMs = now,
                    helperInfo = helper,
                    test = kind.number,
                    delaySeconds = delaySeconds,
                    playSeconds = playSeconds,
                    log = "",
                ),
            )
        }
        event("送話の試験${kind.number}を頼む（遅らせる $delaySeconds 秒、鳴らす $playSeconds 秒、$levelDbfs dBFS）")
        io.execute {
            try {
                if (!svc.startUplinkTest(kind.number, delaySeconds, playSeconds, levelDbfs)) {
                    mutableState.update { it.copy(uplink = it.uplink.copy(requested = false)) }
                    event("送話の試験を始められなかった（すでに動いているか、条件を満たしていない）")
                }
            } catch (t: Throwable) {
                mutableState.update { it.copy(uplink = it.uplink.copy(requested = false)) }
                event("送話の試験を頼めなかった: ${ReportText.exceptionLine(t)}")
            }
        }
    }

    /** Stops the uplink test at once, also while it waits. The record arrives through the callback. */
    fun stopUplinkTest() {
        val svc = service ?: return
        io.execute {
            try {
                svc.stopUplinkTest()
            } catch (t: Throwable) {
                event("送話の試験の停止を頼めなかった: ${ReportText.exceptionLine(t)}")
            }
        }
    }

    /** From the helper (Binder thread). One-way calls arrive one at a time and in order. */
    private fun handleUplinkTest(phase: Int, log: String) {
        val now = SystemClock.elapsedRealtime()
        val before = mutableState.value.uplink.phase
        mutableState.update {
            val u = it.uplink
            val firstEnd = phase == UplinkPhase.ENDED && u.phase != UplinkPhase.ENDED
            it.copy(
                uplink = u.copy(
                    requested = false,
                    phase = phase,
                    sinceMs = if (u.phase != phase) now else u.sinceMs,
                    log = log,
                    records = if (firstEnd) (u.records + log).takeLast(MAX_UPLINK_RECORDS) else u.records,
                ),
            )
        }
        if (before == phase) return
        when (phase) {
            UplinkPhase.WAITING -> event("送話の試験: 遅らせて始めるのを待っている")
            UplinkPhase.PLAYING -> event("送話の試験: 鳴らし始めた")
            UplinkPhase.ENDED -> {
                logLines("uplink", log)
                event("送話の試験が終わった")
            }
        }
    }

    // --- Audio bridge (1b) ---------------------------------------------------------------------

    fun interface BridgeListener {
        /** code: see AudioBridgeEvent. Called from a Binder thread, one at a time and in order. */
        fun onBridgeEvent(code: Int, detail: String)
    }

    @Volatile
    private var bridgeListener: BridgeListener? = null

    /**
     * True while a helper is connected and attached, so the audio bridge can be asked for.
     * `call/ShellAudioPaths.kt` offers the app's audio path only while this is true: the confirm
     * screen then refuses a call whose voice could not be played, instead of dialing it.
     */
    val isHelperRunning: Boolean get() = service != null && mutableState.value.phase == HelperPhase.RUNNING

    /**
     * Starts the call audio bridge (see shell/CallAudioBridge.kt). Synchronous: a helper that is
     * not running, or already running a bridge, is answered at once (false); otherwise progress
     * arrives through [listener] (STARTED, then exactly one of STOPPED/SILENCED/FAILED).
     *
     * The caller must close both [downlinkWriteEnd] and [uplinkReadEnd] itself once this returns:
     * handing them to the AIDL call duplicates them for the helper but does not close the caller's
     * own copies, and an extra copy left open here would stop the helper's side from ever seeing
     * the far end closed.
     */
    fun startAudioBridge(
        mode: Int,
        keepCallQuiet: Boolean,
        downlinkWriteEnd: ParcelFileDescriptor,
        uplinkReadEnd: ParcelFileDescriptor,
        listener: BridgeListener,
    ): Boolean {
        val svc = service ?: return false
        // Set before the call: the helper's first report can arrive before the call returns.
        val previous = bridgeListener
        bridgeListener = listener
        val ok = try {
            svc.startAudioBridge(mode, keepCallQuiet, downlinkWriteEnd, uplinkReadEnd)
        } catch (t: Throwable) {
            event("音声の通り道を頼めなかった: ${ReportText.exceptionLine(t)}")
            false
        }
        if (!ok) bridgeListener = previous
        return ok
    }

    /**
     * Asks the helper to give the call its sound back and waits for it (a Binder call). Says nothing
     * about whether the sound is back: the caller reads that from the stream (`call/CallSoundGuard.kt`).
     */
    fun restoreCallSound() {
        val svc = service ?: return
        try {
            svc.restoreCallSound()
        } catch (t: Throwable) {
            event("通話の音を戻すよう頼めなかった: ${ReportText.exceptionLine(t)}")
        }
    }

    /**
     * One look at the calls on the phone ([CallPicture]). A Binder call that takes a moment: never
     * on the main thread. [CallPicture.UNKNOWN] when no helper is there or it could not tell.
     */
    fun callPicture(): Int {
        val svc = service ?: return CallPicture.UNKNOWN
        return try {
            svc.callPicture()
        } catch (t: Throwable) {
            event("通話の様子を読めなかった: ${ReportText.exceptionLine(t)}")
            CallPicture.UNKNOWN
        }
    }

    /** Ends the phone's one ongoing call through the helper. Waits for it: never on the main thread. */
    fun endOngoingCall(): Boolean {
        val svc = service ?: return false
        return try {
            svc.endOngoingCall()
        } catch (t: Throwable) {
            event("電話を切るよう頼めなかった: ${ReportText.exceptionLine(t)}")
            false
        }
    }

    /** Brings the app's main screen to the front. Returns at once, from any thread. */
    fun bringAppToFront() {
        val svc = service ?: return
        io.execute { attempt { svc.bringAppToFront() } }
    }

    /** Shows the phone app's call screen. Returns at once, from any thread. */
    fun showPhoneCallScreen() {
        val svc = service ?: return
        io.execute { attempt { svc.showPhoneCallScreen() } }
    }

    /** Stops the audio bridge, if one is running. Safe to call when none is, and from any thread. */
    fun stopAudioBridge() {
        val svc = service ?: return
        io.execute {
            try {
                svc.stopAudioBridge()
            } catch (t: Throwable) {
                event("音声の通り道の停止を頼めなかった: ${ReportText.exceptionLine(t)}")
            }
        }
    }

    /** From the helper (Binder thread). */
    private fun handleAudioBridge(code: Int, detail: String) {
        val listener = bridgeListener
        if (code != AudioBridgeEvent.STARTED) bridgeListener = null
        when (code) {
            AudioBridgeEvent.STARTED -> event("音声の通り道が動き始めた")
            AudioBridgeEvent.STOPPED -> event("音声の通り道が終わった（${ReportText.scrub(detail)}）")
            AudioBridgeEvent.SILENCED -> event("音声の通り道の受話が黙らされた")
            AudioBridgeEvent.FAILED -> event("音声の通り道が失敗した: ${ReportText.scrub(detail)}")
        }
        listener?.onBridgeEvent(code, detail)
    }
}

/** The uplink test as the screen sees it. */
data class UplinkUi(
    /** The start was asked for and the helper has not answered yet. */
    val requested: Boolean = false,
    /** One of [UplinkPhase]. */
    val phase: Int = UplinkPhase.IDLE,
    /** SystemClock.elapsedRealtime() when [phase] began. */
    val sinceMs: Long = 0,
    /** The helper (pid and uid) the test was asked of. A test of an earlier helper is over. */
    val helperInfo: String = "",
    val test: Int = -1,
    val delaySeconds: Int = 0,
    val playSeconds: Int = 0,
    /** The record of the latest test. It holds no numbers or names. */
    val log: String = "",
    /** Records of the tests that ended, oldest first, to copy them all at once. */
    val records: List<String> = emptyList(),
)

/** True while an uplink test is asked for, waiting or playing in the helper that runs now. */
val DiagUiState.uplinkBusy: Boolean
    get() = phase == HelperPhase.RUNNING &&
        uplink.helperInfo == helperInfo &&
        (uplink.requested || uplink.phase == UplinkPhase.WAITING || uplink.phase == UplinkPhase.PLAYING)
