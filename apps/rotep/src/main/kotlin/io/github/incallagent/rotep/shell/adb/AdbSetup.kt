// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.incallagent.rotep.app.KeystoreCipher
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.call.ShellAudioPaths
import io.github.incallagent.rotep.call.TelecomCallControl
import io.github.incallagent.rotep.service.AdbPairingNotice
import io.github.incallagent.rotep.service.AdbPairingService
import io.github.incallagent.rotep.service.AdbStartNotice
import io.github.incallagent.rotep.service.AdbStartReceiver
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.HelperPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the diagnostics screen shows of the phone-only start of the helper. */
data class AdbSetupUi(
    /** One of the buttons' work is running on the background thread. */
    val busy: Boolean = false,
    /** The pairing service is on and looks for the phone's pairing window. */
    val searching: Boolean = false,
    /** The pairing port that was found, or 0. */
    val pairingPort: Int = 0,
    /** The switch "再起動のあと自動で起動する". */
    val autoStart: Boolean = true,
    /** The switch 「開発者向けオプションを普段は切っておく」 (ADR-049). */
    val onDemand: Boolean = false,
    val events: List<String> = emptyList(),
)

/**
 * Starts the helper with no PC: the app connects to the phone's own adbd through Wireless debugging
 * and runs the same lines a PC would (see [HelperBoot]). Whether the helper runs is read from
 * [HelperClient] only ([HelperClient.isHelperRunning]).
 *
 * One object for the whole process, like [HelperClient]; all network work runs on one background
 * thread, one job at a time. Nothing here logs the pairing code or the key; the events hold fixed
 * texts and port numbers only.
 */
object AdbSetup {
    private const val TAG = "RotepAdb"
    private const val MAX_EVENTS = 40
    private const val FIND_TIMEOUT_MS = 8_000L

    /** Looking for another port after one refused: the other record is usually there already. */
    private const val RETRY_FIND_TIMEOUT_MS = 4_000L
    private const val HELPER_TIMEOUT_MS = 15_000L

    /** After a restart Wireless debugging may still be coming up. */
    private const val BOOT_FIND_TIMEOUT_MS = 15_000L

    /** Just after Wireless debugging was turned on, the new record can take a while to appear. */
    private const val BOOT_RETRY_FIND_TIMEOUT_MS = 10_000L

    /** All of getting the helper ready for a call, counted from the request (ADR-049). */
    private const val PREPARE_BUDGET_MS = 30_000L

    /** The helper gets this long to end by itself before the switches go off. */
    private const val HELPER_STOP_WAIT_MS = 3_000L

    /** When a switch is left on and a call goes on: how soon to look again. */
    private const val RESTORE_RETRY_MS = 10_000L
    private const val READ_TIMEOUT_MS = 15_000
    private const val MIN_READ_TIMEOUT_MS = 2_000L

    /** All steps of a start from a broadcast together. */
    private const val BROADCAST_BUDGET_MS = 45_000L

    /** After this the broadcast is ended even if a step still runs: the system gives a receiver about a minute. */
    private const val BROADCAST_FINISH_MS = 50_000L

    /** The name the phone shows in its list of paired devices. */
    private const val DEVICE_NAME = "AITEL"

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rotep-adb").apply { isDaemon = true }
    }

    private val jobs = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(AdbSetupUi())
    val state: StateFlow<AdbSetupUi> = mutableState.asStateFlow()

    private lateinit var app: Application
    private var search: AdbServiceFinder.Search? = null
    private val store: AdbKeyStore by lazy { AdbKeyStore(app.noBackupFilesDir, KeystoreCipher(AdbKeyStore.KEYSTORE_ALIAS)) }
    private val finder: AdbServiceFinder by lazy { AdbServiceFinder(app) }
    private val flags: AdbFlags by lazy { AdbFlags(app) }
    private val system: AdbSystemState by lazy { AdbSystemState(app) }
    private val onDemandHelper = object : OnDemandHelper {
        override val ready: Boolean get() = ShellAudioPaths.factory != null

        override val stopping: Boolean get() = HelperClient.state.value.phase == HelperPhase.STOPPING

        override fun start(allowCalls: Boolean, budget: AdbBudget, stopped: () -> Boolean) = startForCall(allowCalls, budget, stopped)

        override fun allowCalls() = allowCallsWithHelperThere()

        override fun stopAndWait() {
            if (!HelperClient.isHelperRunning) return
            HelperClient.stopHelper("電話が終わった")
            val end = SystemClock.elapsedRealtime() + HELPER_STOP_WAIT_MS
            while (HelperClient.state.value.phase != HelperPhase.STOPPED && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        }
    }
    private val onDemand: AdbOnDemand by lazy {
        AdbOnDemand(system, OnDemandMarkerFile(app.noBackupFilesDir), onDemandHelper, System::currentTimeMillis)
    }
    private val restoreRetry = Runnable { restoreIfLeftOn() }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext as Application
        mutableState.update { it.copy(autoStart = flags.autoStart, onDemand = flags.onDemand) }
        AdbHelperWatch.start(app)
        // The app may have died during a call, or the phone restarted, with a switch still on
        if (AdbFeatures.ON_DEMAND && onDemand.leftOn) restoreIfLeftOn()
    }

    /** What the setup guide shows. */
    internal val status: AdbSystemState get() = system

    /**
     * The switch on the diagnostics screen. Off: after a restart the app only asks, it does not start the
     * helper, and the right to change the secure setting is taken back (at once if the phone can be
     * reached, else the next time the helper is started).
     */
    fun setAutoStart(on: Boolean) {
        flags.autoStart = on
        mutableState.update { it.copy(autoStart = on) }
        // Taken back only when neither of the two needs it, and nothing is left to put back with it
        if (!keepsSecureSettings(on, flags.onDemand) && !onDemand.leftOn) revokeSecureSettings()
    }

    private fun revokeSecureSettings() = run("権限の取り消し") {
        if (!system.canWriteSecureSettings()) return@run
        val revoked = onFoundPort { port ->
            AdbConnect.withShell(port, store.loadOrCreate()) { adb -> adb.shell(HelperBoot.revokeSecureSettingsCommand()) }
        }
        if (revoked == null) {
            event("いまはワイヤレスデバッグにつなげないので、次に手伝い役を起動するときに権限を取り消します")
            return@run
        }
        event("ワイヤレスデバッグを自動でオンにする権限を取り消しました")
    }

    // --- Debugging only during a call (ADR-049) ---------------------------------------------------------

    /** The switch is on. From the file: after a restart nothing else has been set up. */
    val onDemandEnabled: Boolean get() = AdbFeatures.ON_DEMAND && ::app.isInitialized && flags.onDemand

    /** See [AdbOnDemand.helperIsRunning]. */
    internal fun helperIsRunning() {
        if (AdbFeatures.ON_DEMAND && ::app.isInitialized) onDemand.helperIsRunning()
    }

    /** The helper went away because the app ended it. */
    internal val helperStopExpected: Boolean get() = ::app.isInitialized && onDemand.expectedStop

    /** What has to be done before the switch can be on, or before a call with it, or null. */
    fun onDemandSetupProblem(): String? = when {
        !flags.paired -> "開発者向けオプションを普段は切っておくには、先に診断の画面でペアリングしてください"
        !system.canWriteSecureSettings() -> "開発者向けオプションを普段は切っておくには、「再起動のあと、Wi-Fi につながったら自動で起動する」をオンのまま、診断の画面で手伝い役を 1 回起動してください"
        else -> null
    }

    fun setOnDemand(on: Boolean) {
        flags.onDemand = on
        mutableState.update { it.copy(onDemand = on) }
        if (!keepsSecureSettings(flags.autoStart, on) && !onDemand.leftOn) revokeSecureSettings()
    }

    /** The way to give up [prepareForCall] before it is over. */
    class PrepareTicket internal constructor() {
        internal val cancelled = AtomicBoolean(false)

        fun cancel() = cancelled.set(true)
    }

    /**
     * Before a call with the switch on (ADR-049): the debugging switches on, the helper started, and for
     * a fully automatic call ([allowCalls]) the permission for it set. [done] gets null, or why no call
     * can be placed, on the background thread. When it got null, [finishAfterCall] has to follow however the
     * call ends.
     */
    fun prepareForCall(allowCalls: Boolean, done: (String?) -> Unit): PrepareTicket {
        val ticket = PrepareTicket()
        val budget = AdbBudget(SystemClock::elapsedRealtime, PREPARE_BUDGET_MS)
        var outcome = AdbStartOutcome.FAILED
        run("電話の準備", { done(AdbStartPolicy.onDemandProblem(outcome)) }) {
            outcome = onDemand.prepare(allowCalls, budget, ticket.cancelled::get)
            // Keeps the switches looked at when the app dies during the call
            if (onDemand.leftOn) AdbStartReceiver.checkPutBackLater(app)
            Log.i(TAG, "before a call: $outcome")
        }
        return ticket
    }

    /** With no call: the whole way there and back, the result in the events (the button on the diagnostics screen). */
    fun tryPrepareForCall() = run("電話の準備の試し") {
        event("電話の準備を試します")
        val began = SystemClock.elapsedRealtime()
        val outcome = onDemand.prepare(true, AdbBudget(SystemClock::elapsedRealtime, PREPARE_BUDGET_MS)) { false }
        val problem = AdbStartPolicy.onDemandProblem(outcome)
        if (problem != null) {
            event("準備できませんでした: $problem")
            return@run
        }
        event("手伝い役が動きました（${(SystemClock.elapsedRealtime() - began) / 100 / 10.0} 秒）")
        val back = onDemand.finish()
        val now = "USB デバッグ：${onOff(system.usb)}、ワイヤレスデバッグ：${onOff(system.wifi)}"
        event(if (back) "元に戻しました（$now）" else "元に戻せませんでした（$now）")
    }

    private fun onOff(value: Int) = if (value == 0) "オフ" else "オン"

    private fun startForCall(allowCalls: Boolean, budget: AdbBudget, stopped: () -> Boolean): AdbStartOutcome {
        if (!system.wifiConnected()) return AdbStartOutcome.NO_WIFI
        // No fixed wait: the phone answers by turning the switch back off, or adbd can be found
        val giveUp = { stopped() || !system.wirelessDebuggingOn() }
        val result = try {
            AdbPorts.firstListening(
                find = { refused ->
                    finder.findOnce(AdbPorts.CONNECT_TYPE, budget.limit(if (refused.isEmpty()) BOOT_FIND_TIMEOUT_MS else BOOT_RETRY_FIND_TIMEOUT_MS), refused, giveUp)
                },
                onRefused = ::portRefused,
                attempt = { port ->
                    val keys = store.loadExisting() ?: return AdbStartOutcome.NOT_PAIRED
                    runStartScript(port, keys, budget.limit(READ_TIMEOUT_MS.toLong()).coerceAtLeast(MIN_READ_TIMEOUT_MS).toInt(), allowCalls)
                },
            )
        } catch (e: AdbNotAuthorizedException) {
            keyRefused()
            return AdbStartOutcome.NOT_PAIRED
        } catch (e: ConnectException) {
            null
        }
        return when {
            result != null -> if (HelperBoot.scriptStarted(result) && waitForHelper(budget.limit(HELPER_TIMEOUT_MS))) AdbStartOutcome.STARTED else AdbStartOutcome.FAILED
            stopped() -> AdbStartOutcome.FAILED
            !system.wirelessDebuggingOn() -> AdbStartOutcome.NETWORK_NOT_ALLOWED
            else -> AdbStartOutcome.NO_PORT
        }
    }

    /** The helper was there already, started by the person: only the permission may be missing. */
    private fun allowCallsWithHelperThere(): AdbStartOutcome {
        if (TelecomCallControl.appOpAllowed(app)) return AdbStartOutcome.ALREADY_RUNNING
        val keys = store.loadExisting() ?: return AdbStartOutcome.NOT_PAIRED
        val done = onFoundPort { port -> AdbConnect.withShell(port, keys) { adb -> adb.shell(HelperBoot.appOpsSetCommand()) } }
        return if (done == null) AdbStartOutcome.NO_PORT else AdbStartOutcome.ALREADY_RUNNING
    }

    /** However a call ended, or did not begin. Any thread; does nothing when nothing was changed for it. */
    fun finishAfterCall() {
        if (!::app.isInitialized) return
        worker.execute {
            try {
                finishNow()
            } catch (t: Throwable) {
                Log.w(TAG, "finish failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun finishNow() {
        if (!AdbFeatures.ON_DEMAND || !onDemand.leftOn) return
        val done = onDemand.finish()
        Log.i(TAG, if (done) "debugging switches are as they were" else "debugging switches not put back")
        AdbStartReceiver.cancelPutBackCheck(app)
        if (!done) AdbStartNotice.show(app, AdbStartPolicy.NOT_PUT_BACK)
    }

    /** What a call left behind that did not end the normal way. Any thread. */
    fun restoreIfLeftOn() {
        if (!::app.isInitialized) return
        worker.execute {
            try {
                restoreNow()
            } catch (t: Throwable) {
                Log.w(TAG, "restore failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** On the background thread: also from the alarm that is set for an app that died during a call. */
    fun restoreNow() {
        if (!AdbFeatures.ON_DEMAND || !onDemand.leftOn) return
        val call = if (runCatching { RotepApp.callRunning() }.getOrDefault(false)) PhoneCallState.ON else system.phoneCallState()
        when (onDemand.restoreIfLeftOn(call)) {
            OnDemandRestore.NOTHING_LEFT -> Unit
            OnDemandRestore.PUT_BACK -> {
                Log.i(TAG, "debugging switches put back after an interrupted call")
                AdbStartReceiver.cancelPutBackCheck(app)
            }
            OnDemandRestore.FAILED -> {
                AdbStartReceiver.cancelPutBackCheck(app)
                AdbStartNotice.show(app, AdbStartPolicy.NOT_PUT_BACK)
            }
            OnDemandRestore.LATER -> {
                AdbStartReceiver.checkPutBackLater(app)
                mainHandler.removeCallbacks(restoreRetry)
                mainHandler.postDelayed(restoreRetry, RESTORE_RETRY_MS)
            }
        }
    }

    /** True when this phone has been paired through this screen (the key was accepted). */
    val paired: Boolean get() = flags.paired

    // --- Pairing ------------------------------------------------------------------------------------

    /** Starts the short foreground service that keeps looking for the pairing window while Settings is in front. */
    fun startPairing(context: Context) {
        init(context)
        try {
            context.startForegroundService(Intent(context, AdbPairingService::class.java))
        } catch (t: IllegalStateException) {
            event("ペアリングを始められませんでした（${t.javaClass.simpleName}）")
        }
    }

    /** Called by the service once it is in the foreground. */
    fun beginSearch() {
        mutableState.update { it.copy(searching = true, pairingPort = 0) }
        stopSearch()
        val canNotify = AdbPairingNotice.canNotify(app)
        event("ペアリングの窓を探しています。設定の「ペア設定コードによるデバイスのペア設定」を開いてください")
        if (!canNotify) event("通知が許可されていないので、コードを入れる欄が通知に出ません。「手で入れる」の欄にポートとコードを入れてください（分割画面で設定の画面を残します）")
        search = try {
            finder.Search(AdbPorts.PAIRING_TYPE) { port ->
                mutableState.update { it.copy(pairingPort = port) }
                if (canNotify) {
                    event("ペアリングの窓が見つかりました（ポート $port）。通知に 6 桁のコードを入れてください")
                    AdbPairingNotice.showCodeInput(app)
                } else {
                    event("ペアリングの窓が見つかりました（ポート $port）。「手で入れる」の欄にこのポートと 6 桁のコードを入れてください")
                }
            }
        } catch (t: RuntimeException) {
            event("mDNS を使えませんでした（${t.javaClass.simpleName}）。手で入力の欄を使ってください")
            null
        }
    }

    private fun stopSearch() {
        search?.stop()
        search = null
    }

    /** The service ends: on success, on a time-out, or when asked. */
    fun endSearch() {
        stopSearch()
        mutableState.update { it.copy(searching = false) }
    }

    /** The code from the notification. */
    fun submitCode(code: String) {
        val port = mutableState.value.pairingPort
        if (port == 0) {
            event("ペアリングの窓がまだ見つかっていません。少し待ってからもう一度入れてください")
            AdbPairingNotice.showCodeInput(app)
            return
        }
        pair(port, code, fromNotification = true)
    }

    /** Pairing with the port and code typed on the diagnostics screen (when the notification cannot be used). */
    fun pairManually(portText: String, codeText: String) {
        val port = AdbPorts.parsePort(portText)
        if (port == null) {
            event("ポートの番号が正しくありません")
            return
        }
        pair(port, codeText, fromNotification = false)
    }

    private fun pair(port: Int, codeText: String, fromNotification: Boolean) {
        val code = AdbPorts.parseCode(codeText)
        if (code == null) {
            event("コードは 6 桁の数字です")
            if (fromNotification) AdbPairingNotice.showCodeInput(app)
            return
        }
        run("ペアリング") {
            AdbPairingNotice.showWorking(app)
            event("ペアリングしています（ポート $port）")
            AdbConnect.pair(port, code, store.loadOrCreate(), DEVICE_NAME)
            flags.paired = true
            flags.keyRefusals = 0
            event("ペアリングできました。次に「手伝い役を起動」を押します")
            AdbPairingNotice.showDone(app, "ペアリングできました")
            stopPairingService()
        }
    }

    private fun stopPairingService() {
        endSearch()
        app.stopService(Intent(app, AdbPairingService::class.java))
    }

    // --- Starting the helper --------------------------------------------------------------------------

    /** Connects to adbd (the port found by mDNS or [manualPort]) and starts the helper. */
    fun startHelper(manualPortText: String) = run("手伝い役の起動") {
        if (HelperClient.isHelperRunning) {
            event("手伝い役はもう動いています")
            return@run
        }
        val result = onPort(manualPortText) { port -> runStartScript(port, store.loadOrCreate()) } ?: return@run
        if (!HelperBoot.scriptStarted(result)) {
            event("手伝い役の起動の行が通りませんでした（終了コード ${result.exitCode}）")
            return@run
        }
        flags.paired = true
        event("起動を頼みました。手伝い役がつながるのを待っています")
        if (waitForHelper()) {
            event("手伝い役が動き始めました")
        } else {
            event("${HELPER_TIMEOUT_MS / 1000} 秒たっても手伝い役がつながりません。診断の画面の出来事と logcat（RotepHelper）を見てください")
        }
    }

    /**
     * The start script (the one a PC runs) over the app's own adb connection. In the same connection, the
     * app gets the right to turn Wireless debugging on after a restart while the automatic start is on,
     * and loses it when the automatic start is off (see [setAutoStart]).
     */
    private fun runStartScript(port: Int, keys: AdbKeys, readTimeoutMs: Int = READ_TIMEOUT_MS, allowCalls: Boolean = false): AdbShellResult {
        val script = app.assets.open(HelperBoot.SCRIPT_ASSET).bufferedReader().use { it.readText() }
        return AdbConnect.withShell(port, keys, readTimeoutMs) { adb ->
            flags.keyRefusals = 0 // Whatever answered here knows the key
            val result = adb.shell(script)
            adb.shell(if (keepsSecureSettings(flags.autoStart, flags.onDemand) || onDemand.leftOn) HelperBoot.grantSecureSettingsCommand() else HelperBoot.revokeSecureSettingsCommand())
            if (allowCalls) adb.shell(HelperBoot.appOpsSetCommand())
            result
        }
    }

    // --- After a restart or an update (the broadcast receiver in service/) -------------------------------

    /**
     * After a restart: nothing happens unless the person has paired this phone. Then the helper is
     * started once Wi-Fi is there, and if that does not work the person gets a notification.
     */
    fun onRestarted(context: Context) {
        init(context)
        // With debugging only there for calls, a restart has nothing to start and nothing to say
        if (!AdbFeatures.BOOT_START || !flags.paired || onDemandEnabled) return
        if (!flags.autoStart) {
            notify(AdbStartOutcome.AUTO_OFF)
            return
        }
        notify(AdbStartOutcome.NO_WIFI)
        startWhenWifiIsThere()
    }

    /** The app was updated: the old helper ends itself, and the new one starts as after a restart. */
    fun onUpdated(context: Context) {
        init(context)
        if (!AdbFeatures.BOOT_START || !flags.paired || onDemandEnabled) return
        AdbStartNotice.show(app, AdbStartPolicy.UPDATED)
        if (flags.autoStart) startWhenWifiIsThere()
    }

    /** A broadcast comes when Wi-Fi is there (at once, when it already is), and [startFromBroadcast] goes on from it. */
    private fun startWhenWifiIsThere() {
        flags.startRetries = 0
        flags.bootStartPending = true
        AdbStartReceiver.waitForWifi(app)
    }

    @Volatile
    private var lastRestartMs: Long? = null

    /** The helper went away without the app ending it. The person has been told; see [AdbStartPolicy.restartsByItself]. */
    internal fun helperWentAway() {
        if (!::app.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val restart = AdbStartPolicy.restartsByItself(
            automatic = AdbFeatures.BOOT_START && flags.autoStart && flags.paired && !onDemandEnabled,
            debuggingStillOn = system.usbDebuggingOn() || system.wirelessDebuggingOn(),
            wifiThere = system.wifiConnected(),
            sinceLastMs = lastRestartMs?.let { now - it },
        )
        if (restart) {
            lastRestartMs = now
            startWhenWifiIsThere()
        }
    }

    /**
     * Tries to start the helper for a broadcast: Wi-Fi came up after a restart, or the "起動" button of a
     * notification ([byUser]). [done] is called when it has ended (the broadcast can then finish).
     */
    fun startFromBroadcast(byUser: Boolean, done: () -> Unit) {
        if (!byUser && (!AdbFeatures.BOOT_START || !flags.bootStartPending)) {
            done()
            return
        }
        // The receiver is finished at the latest here, even if the job were stuck: a broadcast is limited to about a minute.
        val finished = AtomicBoolean(false)
        val finishOnce = { if (finished.compareAndSet(false, true)) done() }
        val watchdog = Runnable { finishOnce() }
        mainHandler.postDelayed(watchdog, BROADCAST_FINISH_MS)
        run("手伝い役の起動", finishOnce) {
            val outcome = try {
                autoStart(byUser)
            } catch (e: Exception) {
                // Any failure ends the waiting and is told: otherwise "waiting for Wi-Fi" would stay and every Wi-Fi event would retry.
                Log.w(TAG, "start failed: ${e.javaClass.simpleName}")
                AdbStartPolicy.outcomeOf(e)
            }
            event("手伝い役の起動: $outcome")
            val tries = flags.startRetries
            when (AdbStartPolicy.afterStart(outcome, byUser, tries)) {
                AdbAfterStart.WAIT_FOR_WIFI -> {
                    flags.bootStartPending = true
                    AdbStartReceiver.waitForWifi(app)
                    notify(outcome)
                }
                AdbAfterStart.TRY_AGAIN -> {
                    flags.startRetries = tries + 1
                    AdbStartReceiver.tryAgainLater(app, AdbStartPolicy.RETRY_DELAYS_MS[tries])
                    AdbStartNotice.show(app, AdbStartPolicy.TRYING_AGAIN)
                }
                AdbAfterStart.DONE -> {
                    flags.bootStartPending = false
                    flags.startRetries = 0
                    AdbStartReceiver.stopWaitingForWifi(app)
                    notify(outcome)
                }
            }
            mainHandler.removeCallbacks(watchdog)
        }
    }

    /** Work for a receiver, on the same worker as everything else here. */
    fun onWorker(job: () -> Unit) {
        worker.execute(job)
    }

    private fun notify(outcome: AdbStartOutcome) {
        AdbStartPolicy.notice(outcome)?.let { AdbStartNotice.show(app, it) } ?: AdbStartNotice.clear(app)
    }

    private fun autoStart(byUser: Boolean): AdbStartOutcome {
        if (!byUser && !flags.autoStart) return AdbStartOutcome.AUTO_OFF
        if (HelperClient.isHelperRunning) return AdbStartOutcome.ALREADY_RUNNING
        if (!system.wifiConnected()) return AdbStartOutcome.NO_WIFI
        val budget = AdbBudget(SystemClock::elapsedRealtime, BROADCAST_BUDGET_MS)
        if (!system.wirelessDebuggingOn()) {
            if (!system.canWriteSecureSettings()) return AdbStartOutcome.NO_PERMISSION
            if (!system.turnOnWirelessDebugging()) return AdbStartOutcome.NETWORK_NOT_ALLOWED
        }
        return try {
            val result = AdbPorts.firstListening(
                find = { refused ->
                    finder.findOnce(AdbPorts.CONNECT_TYPE, budget.limit(if (refused.isEmpty()) BOOT_FIND_TIMEOUT_MS else RETRY_FIND_TIMEOUT_MS), refused)
                },
                onRefused = ::portRefused,
                attempt = { port ->
                    // Read only: right after a restart the keystore can still be busy, and that must not replace the paired key.
                    val keys = store.loadExisting() ?: return AdbStartOutcome.NOT_PAIRED
                    runStartScript(port, keys, budget.limit(READ_TIMEOUT_MS.toLong()).coerceAtLeast(MIN_READ_TIMEOUT_MS).toInt())
                },
            ) ?: return AdbStartOutcome.NO_PORT
            when {
                !HelperBoot.scriptStarted(result) -> AdbStartOutcome.FAILED
                waitForHelper(budget.limit(HELPER_TIMEOUT_MS)) -> AdbStartOutcome.STARTED
                else -> AdbStartOutcome.FAILED
            }
        } catch (e: AdbNotAuthorizedException) {
            keyRefused()
            AdbStartOutcome.NOT_PAIRED
        }
    }

    /** Allows the fully automatic call control (`appops set`) over the same kind of connection. */
    fun allowAutomaticCalls(manualPortText: String) = run("全自動の許可") {
        val allowed = onPort(manualPortText) { port ->
            AdbConnect.withShell(port, store.loadOrCreate()) { adb ->
                adb.shell(HelperBoot.appOpsSetCommand())
                HelperBoot.appOpsAllowed(adb.shell(HelperBoot.appOpsGetCommand()).output)
            }
        } ?: return@run
        if (allowed) event("全自動の許可が入りました") else event("全自動の許可を確かめられませんでした（appops の結果が allow ではない）")
    }

    /** [block] on the port typed by hand, or else on one found by mDNS. null: there is no port, and the person has been told. */
    private fun <T : Any> onPort(manualPortText: String, block: (Int) -> T): T? {
        if (manualPortText.isNotBlank()) {
            val port = AdbPorts.parsePort(manualPortText)
            if (port == null) {
                event("ポートの番号が正しくありません")
                return null
            }
            return block(port)
        }
        event("ワイヤレスデバッグのポートを探しています")
        val result = onFoundPort(block)
        if (result == null) event("ポートが見つかりません。ペアリングのやり直しは要りません。Wi-Fi につながっていて、ワイヤレスデバッグがオンか確かめるか、ポートを手で入れてください")
        return result
    }

    /** [block] on a port found by mDNS that accepts the connection (see [AdbPorts.firstListening]). null: none was found. */
    private fun <T : Any> onFoundPort(block: (Int) -> T): T? = AdbPorts.firstListening(
        find = { refused -> finder.findOnce(AdbPorts.CONNECT_TYPE, if (refused.isEmpty()) FIND_TIMEOUT_MS else RETRY_FIND_TIMEOUT_MS, refused) },
        onRefused = ::portRefused,
        attempt = { port ->
            event("ポート $port につなぎます")
            block(port)
        },
    )

    /** In the log only: the next port usually works, and a failure on the screen sends people off to pair again. */
    private fun portRefused(port: Int) {
        Log.i(TAG, "port $port refused the connection; looking for another")
    }

    /** A pairing is only given up when the key was refused several times in a row (see [AdbStartPolicy.pairingLost]). */
    private fun keyRefused() {
        val refusals = flags.keyRefusals + 1
        flags.keyRefusals = refusals
        if (AdbStartPolicy.pairingLost(refusals)) flags.paired = false
    }

    private fun waitForHelper(timeoutMs: Long = HELPER_TIMEOUT_MS): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (HelperClient.isHelperRunning) return true
            Thread.sleep(200)
        }
        return HelperClient.isHelperRunning
    }

    // --- The background thread --------------------------------------------------------------------

    private fun run(what: String, done: () -> Unit = {}, job: () -> Unit) {
        // Jobs can overlap (one running, one waiting): busy ends with the last one.
        jobs.incrementAndGet()
        mutableState.update { it.copy(busy = true) }
        worker.execute {
            try {
                job()
            } catch (t: Throwable) {
                if (t is AdbNotAuthorizedException) keyRefused()
                val message = reasonFor(t)
                event("$what を完了できませんでした: $message")
                if (what == "ペアリング") reportPairingFailure(message)
                Log.w(TAG, "$what failed: ${t.javaClass.simpleName}")
            } finally {
                if (jobs.decrementAndGet() == 0) mutableState.update { it.copy(busy = false) }
                done()
            }
        }
    }

    /**
     * After a failed try the pairing window stays open on the phone (a wrong code can be corrected), so
     * the input is offered again. If the window has closed meanwhile, the next try says so, and the
     * search goes on: a new window updates the port and asks for the code again.
     */
    private fun reportPairingFailure(message: String) {
        if (mutableState.value.searching) {
            AdbPairingNotice.showCodeInput(app, "うまくいきませんでした。コードを確かめて、もう一度入れてください")
        } else {
            AdbPairingNotice.showDone(app, "ペアリングできませんでした: $message")
        }
    }

    private fun reasonFor(t: Throwable): String = when (t) {
        is AdbNotAuthorizedException -> "この端末にアプリの鍵が登録されていません。先に「ペアリング」を行ってください"
        is AdbPairingException -> "コードが違うか、時間切れです。設定の画面で新しいコードを出してやり直してください"
        is ConnectException -> "ワイヤレスデバッグのポートにつなげません。ペアリングのやり直しは要りません。ワイヤレスデバッグがオンかを確かめて、もう一度押してください"
        is SocketTimeoutException -> "応答がありません。ワイヤレスデバッグの設定を確かめてください"
        is AdbProtocolException -> "adbd の答えが想定と違います（${t.message}）"
        is IOException -> "通信に失敗しました（${t.javaClass.simpleName}）"
        else -> "失敗しました（${t.javaClass.simpleName}）"
    }

    private fun event(text: String) {
        val line = "${LocalTime.now().format(clock)} $text"
        Log.i(TAG, text)
        mutableState.update { it.copy(events = (it.events + line).takeLast(MAX_EVENTS)) }
    }
}
