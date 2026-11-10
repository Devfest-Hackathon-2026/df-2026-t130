// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telecom.TelecomManager
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.service.AdbStartNotice
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.HelperPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.ConnectException

/** Switches for the phone-only start. One place, so that a part that does not work on the device can be cut for the submission. */
internal object AdbFeatures {
    /**
     * The automatic start after a restart and the notices of it (and of an app update). false: nothing
     * happens by itself after a restart; the helper is started with the buttons on the diagnostics screen.
     */
    const val BOOT_START: Boolean = true

    /**
     * 「開発者向けオプションを普段は切っておく」 (ADR-049). false: the switch and the button are not there,
     * and nothing about the calls changes.
     */
    const val ON_DEMAND: Boolean = true
}

/**
 * How long one start from a broadcast may take. A broadcast receiver has about a minute before the
 * system counts it as stuck, so the steps share less than that.
 */
internal class AdbBudget(private val nowMs: () -> Long, totalMs: Long) {
    private val end = nowMs() + totalMs

    fun remainingMs(): Long = (end - nowMs()).coerceAtLeast(0)

    /** [stepMs], or less when the whole has less left. */
    fun limit(stepMs: Long): Long = minOf(stepMs, remainingMs())
}

/** How an attempt to start the helper with nobody pressing a button (after a restart, or a tap on a notification) ended. */
internal enum class AdbStartOutcome {
    STARTED,
    ALREADY_RUNNING,

    /** The person switched the automatic start off on the diagnostics screen. */
    AUTO_OFF,

    /** The phone's list of paired keys does not have the app's key: it was removed or expired. */
    NOT_PAIRED,
    NO_WIFI,

    /** Wireless debugging is off and the app may not turn it on (WRITE_SECURE_SETTINGS is missing). */
    NO_PERMISSION,

    /** The system turned Wireless debugging off again: this network was not "always allowed". */
    NETWORK_NOT_ALLOWED,
    NO_PORT,
    FAILED,
}

/** What follows a start from a broadcast. */
internal enum class AdbAfterStart {
    /** Over: what the outcome says is what the person hears. */
    DONE,

    /** Wi-Fi is not there: the start goes on when it comes. */
    WAIT_FOR_WIFI,

    /** It may work a little later: the app tries again by itself. */
    TRY_AGAIN,
}

/** A notification about the start of the helper. */
internal data class AdbNotice(
    val title: String,
    val text: String,
    /** Offers the "起動" button (tries to start the helper again). */
    val startAction: Boolean,
    /** Goes away by itself after this long, when set. */
    val timeoutMs: Long? = null,
)

/** The small decisions of the automatic start, apart from Android so that they can be tested. */
internal object AdbStartPolicy {
    private const val STARTED_NOTICE_MS = 60_000L

    /** How long to wait before each further try of a start that may work a little later. */
    val RETRY_DELAYS_MS = listOf(60_000L, 180_000L, 600_000L)

    /** Right after a restart the record of the port can come late, and the keystore can still be busy. */
    private val MAY_WORK_LATER = setOf(AdbStartOutcome.NO_PORT, AdbStartOutcome.FAILED)

    /**
     * What follows a start from a broadcast. No Wi-Fi: it goes on when Wi-Fi is there, after the "起動"
     * button too, because the notice says so. [byUser]: the person sees the result, so nothing is tried
     * again behind their back.
     */
    fun afterStart(outcome: AdbStartOutcome, byUser: Boolean, triesSoFar: Int): AdbAfterStart = when {
        outcome == AdbStartOutcome.NO_WIFI -> AdbAfterStart.WAIT_FOR_WIFI
        !byUser && outcome in MAY_WORK_LATER && triesSoFar < RETRY_DELAYS_MS.size -> AdbAfterStart.TRY_AGAIN
        else -> AdbAfterStart.DONE
    }

    /** At most one start by itself in this time, for a helper that keeps dying. */
    const val RESTART_INTERVAL_MS = 10 * 60 * 1000L

    /**
     * The helper went away without the app ending it: whether the app brings it back without asking.
     * Both debugging switches off on a Wi-Fi that is there is the person's choice (a bank's app, for
     * example): they are not turned on again behind their back. A lost Wi-Fi turns them off as well.
     */
    fun restartsByItself(automatic: Boolean, debuggingStillOn: Boolean, wifiThere: Boolean, sinceLastMs: Long?): Boolean =
        automatic && (debuggingStillOn || !wifiThere) && (sinceLastMs == null || sinceLastMs >= RESTART_INTERVAL_MS)

    /** Refusals of the app's key in a row before the pairing counts as lost: one proves little, anything can answer on a port. */
    const val KEY_REFUSALS_FOR_LOST_PAIRING = 3

    fun pairingLost(refusalsInARow: Int): Boolean = refusalsInARow >= KEY_REFUSALS_FOR_LOST_PAIRING

    /** What a failure of a start means. It must always become an outcome, so that the waiting ends and the person is told. */
    fun outcomeOf(failure: Throwable): AdbStartOutcome = when (failure) {
        is AdbNotAuthorizedException -> AdbStartOutcome.NOT_PAIRED
        // Nothing listens on any port that was offered: Wireless debugging is off
        is ConnectException -> AdbStartOutcome.NO_PORT
        else -> AdbStartOutcome.FAILED
    }

    /** Why a call that needed the helper was not placed, in words another AI can pass on (ADR-049); null: the helper is there. */
    fun onDemandProblem(outcome: AdbStartOutcome): String? = when (outcome) {
        AdbStartOutcome.STARTED, AdbStartOutcome.ALREADY_RUNNING -> null
        AdbStartOutcome.NOT_PAIRED -> "アプリの鍵がスマホから外れていたので、電話をかけませんでした。開発者向けオプションをオンにして、診断の画面でペアリングをやり直す必要があります"
        AdbStartOutcome.NO_WIFI -> "Wi-Fi につながっていなかったので、電話をかけませんでした"
        AdbStartOutcome.NO_PERMISSION -> "USB デバッグをオンにする権限がなかったので、電話をかけませんでした。開発者向けオプションをオンにして、診断の画面で手伝い役を 1 回起動する必要があります"
        AdbStartOutcome.NETWORK_NOT_ALLOWED -> "この Wi-Fi が「常に許可」になっていなかったので、電話をかけませんでした"
        AdbStartOutcome.AUTO_OFF, AdbStartOutcome.NO_PORT, AdbStartOutcome.FAILED -> "電話の準備が時間内に終わらなかったので、電話をかけませんでした"
    }

    /** What the person sees for [outcome]; null: nothing to say. */
    fun notice(outcome: AdbStartOutcome): AdbNotice? = when (outcome) {
        AdbStartOutcome.STARTED -> AdbNotice("手伝い役を起動しました", "電話の準備ができています", startAction = false, timeoutMs = STARTED_NOTICE_MS)
        AdbStartOutcome.ALREADY_RUNNING -> null
        AdbStartOutcome.AUTO_OFF -> AdbNotice("手伝い役を起動してください", "自動の起動はオフです。「起動」を押してください", startAction = true)
        AdbStartOutcome.NOT_PAIRED -> AdbNotice(
            "もう一度ペアリングしてください",
            "この端末からアプリの鍵が外れました（長くつながないと外れます）。設定の「困ったとき」から診断の画面を開いて、ペアリングをやり直してください",
            startAction = false,
        )
        AdbStartOutcome.NO_WIFI -> AdbNotice("Wi-Fi を待っています", "Wi-Fi につながると、手伝い役を起動します", startAction = false)
        AdbStartOutcome.NO_PERMISSION -> AdbNotice(
            "手伝い役を起動してください",
            "ワイヤレスデバッグをオンにしてから「起動」を押してください",
            startAction = true,
        )
        AdbStartOutcome.NETWORK_NOT_ALLOWED -> AdbNotice(
            "このネットワークでの許可が要ります",
            "画面に出た確認で「常に許可」を選んでから、「起動」を押してください",
            startAction = true,
        )
        AdbStartOutcome.NO_PORT -> AdbNotice(
            "手伝い役を起動できませんでした",
            "ペアリングのやり直しは要りません。ワイヤレスデバッグがオンか確かめて、「起動」を押してください",
            startAction = true,
        )
        AdbStartOutcome.FAILED -> AdbNotice(
            "手伝い役を起動できませんでした",
            "設定の「困ったとき」から診断の画面を開いて、手伝い役の状態と出来事を確かめてください",
            startAction = true,
        )
    }

    /** A start from a broadcast that did not work, and will be tried again. */
    val TRYING_AGAIN = AdbNotice("手伝い役の起動をやり直します", "少し待ってから、もう一度試します。すぐに試すときは「起動」を押してください", startAction = true)

    /** The app was installed again: the old helper ends itself when the new app refuses it. */
    val UPDATED = AdbNotice("手伝い役を起動し直してください", "アプリを更新したので、手伝い役は終わりました。「起動」で起動し直します", startAction = true)

    /** After a call the switches could not be put back (ADR-049). */
    val NOT_PUT_BACK = AdbNotice("USB デバッグを元に戻せませんでした", "銀行のアプリなどが起動しないことがあります。端末の設定で、USB デバッグをオフにしてください", startAction = false)

    /** The helper went away by itself with the switches only on for calls. */
    val HELPER_GONE_ON_DEMAND = AdbNotice("手伝い役が止まりました", "電話の音声が使えない状態です。電話の途中で開発者向けオプションを切ると止まります", startAction = false)

    /** The helper was gone while the app was running. */
    val HELPER_GONE = AdbNotice(
        "手伝い役が止まりました",
        "電話の音声が使えない状態です。USB デバッグとワイヤレスデバッグがどちらもオフになると止まります。「起動」で起動し直します",
        startAction = true,
    )
}

/** What is remembered between runs. Not secret: the key is in [AdbKeyStore]. */
internal class AdbFlags(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The app's key was accepted by the phone (a pairing worked, and no connection has refused it since). */
    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    /** After a restart the helper is started without asking. On by default; the switch is on the diagnostics screen. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** A start after the restart is still to do (the phone was waiting for Wi-Fi). */
    var bootStartPending: Boolean
        get() = prefs.getBoolean(KEY_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING, value).apply()

    /** How many times a start has been tried again by itself. See [AdbStartPolicy.afterStart]. */
    var startRetries: Int
        get() = prefs.getInt(KEY_RETRIES, 0)
        set(value) = prefs.edit().putInt(KEY_RETRIES, value).apply()

    /** How many times in a row the app's key was refused. See [AdbStartPolicy.pairingLost]. */
    var keyRefusals: Int
        get() = prefs.getInt(KEY_REFUSALS, 0)
        set(value) = prefs.edit().putInt(KEY_REFUSALS, value).apply()

    /** 「開発者向けオプションを普段は切っておく」 (ADR-049). Off by default; apart from [autoStart]. */
    var onDemand: Boolean
        get() = prefs.getBoolean(KEY_ON_DEMAND, false)
        set(value) = prefs.edit().putBoolean(KEY_ON_DEMAND, value).apply()

    private companion object {
        const val FILE = "adb_setup"
        const val KEY_PAIRED = "paired"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_PENDING = "boot_start_pending"
        const val KEY_ON_DEMAND = "on_demand"
        const val KEY_REFUSALS = "key_refusals"
        const val KEY_RETRIES = "start_retries"
    }
}

/** What the phone says about the things that a start needs. */
internal class AdbSystemState(private val context: Context) : DebugSwitches {
    @Suppress("DEPRECATION") // allNetworks is the simplest form that also sees a Wi-Fi network without internet access
    fun wifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun wirelessDebuggingOn(): Boolean = globalInt(ADB_WIFI_ENABLED) == 1

    fun usbDebuggingOn(): Boolean = globalInt(Settings.Global.ADB_ENABLED) == 1

    fun developerOptionsOn(): Boolean = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Turns Wireless debugging on. false: the system turned it off again at once because this network
     * has not been "always allowed" (the system shows its own question on the screen).
     */
    fun turnOnWirelessDebugging(): Boolean {
        Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        repeat(SETTLE_STEPS) {
            Thread.sleep(SETTLE_STEP_MS)
            if (!wirelessDebuggingOn()) return false
        }
        return true
    }

    override var usb: Int
        get() = globalInt(Settings.Global.ADB_ENABLED)
        set(value) {
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, value)
        }

    override var wifi: Int
        get() = globalInt(ADB_WIFI_ENABLED)
        set(value) {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, value)
        }

    /** Needs READ_PHONE_STATE: without it the phone does not say. */
    fun phoneCallState(): PhoneCallState {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return PhoneCallState.UNKNOWN
        return try {
            if (context.getSystemService(TelecomManager::class.java)?.isInCall == true) PhoneCallState.ON else PhoneCallState.NONE
        } catch (_: SecurityException) {
            PhoneCallState.UNKNOWN
        }
    }

    private fun globalInt(name: String): Int = Settings.Global.getInt(context.contentResolver, name, 0)

    private companion object {
        /** Hidden in the SDK but readable (marked @Readable); WRITE_SECURE_SETTINGS can write it. */
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val SETTLE_STEPS = 15
        const val SETTLE_STEP_MS = 200L
    }
}

/**
 * Tells the person when the helper disappeared while the app was running (the process of the helper
 * died, for example because adbd stopped). A helper that was ended on purpose goes through
 * [HelperPhase.STOPPING] first, so it is not reported.
 */
internal object AdbHelperWatch {
    private var started = false

    @Synchronized
    fun start(app: Application) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var previous = HelperClient.state.value.phase
            HelperClient.state.collect { ui ->
                if (ui.phase == HelperPhase.RUNNING) AdbSetup.helperIsRunning()
                if (previous == HelperPhase.RUNNING && ui.phase == HelperPhase.STOPPED && !AdbSetup.helperStopExpected) {
                    // During a call the sound would be heard in the call: the notification is silent then.
                    val inCall = runCatching { RotepApp.callRunning() }.getOrDefault(false)
                    AdbStartNotice.show(app, if (AdbSetup.onDemandEnabled) AdbStartPolicy.HELPER_GONE_ON_DEMAND else AdbStartPolicy.HELPER_GONE, silent = inCall)
                    // An uncaught failure here would end the app, and this watch with it
                    runCatching { AdbSetup.helperWentAway() }.onFailure { RotepLog.w("adb", "no restart: ${it.javaClass.simpleName}") }
                }
                previous = ui.phase
            }
        }
    }
}
