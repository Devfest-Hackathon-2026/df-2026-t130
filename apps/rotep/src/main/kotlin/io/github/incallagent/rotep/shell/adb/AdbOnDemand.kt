// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import io.github.incallagent.rotep.app.UserMessages
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The phone's two debugging switches, 0 or 1 (`adb_enabled`, `adb_wifi_enabled`). These are all the app
 * changes: never the main switch of the developer options. Setting one throws when the app may not.
 */
internal interface DebugSwitches {
    var usb: Int
    var wifi: Int
}

/**
 * What the app has changed for a call, written before it is changed: the app can die or the phone
 * restart at any moment. [wroteUsb] and [wroteWifi] tell a value the app left from one the person set
 * since then. [startedHelper]: only then is the helper the app's to stop.
 */
internal data class OnDemandMarker(
    val originalUsb: Int,
    val originalWifi: Int,
    val wroteUsb: Int,
    val wroteWifi: Int,
    val startedHelper: Boolean,
    val atMs: Long,
) {
    fun encoded(): String = "$originalUsb,$originalWifi,$wroteUsb,$wroteWifi,${if (startedHelper) 1 else 0},$atMs"

    companion object {
        private const val FIELDS = 6

        fun parse(text: String?): OnDemandMarker? {
            val parts = text?.trim()?.split(',')?.map { it.toLongOrNull() ?: return null } ?: return null
            if (parts.size != FIELDS) return null
            return OnDemandMarker(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4] != 0L, parts[5])
        }
    }
}

internal interface OnDemandMarkerStore {
    fun read(): OnDemandMarker?

    /** Throws when it could not be written: nothing may be changed then. */
    fun write(marker: OnDemandMarker)

    fun clear()
}

/** In noBackupFilesDir, replaced in one step: half a marker must not be read as the person's settings. */
internal class OnDemandMarkerFile(dir: File) : OnDemandMarkerStore {
    private val file = File(dir, NAME)
    private val temporary = File(dir, "$NAME.new")

    override fun read(): OnDemandMarker? = try {
        if (file.exists()) OnDemandMarker.parse(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    override fun write(marker: OnDemandMarker) {
        temporary.writeText(marker.encoded())
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun clear() {
        temporary.delete()
        file.delete()
    }

    private companion object {
        const val NAME = "adb_on_demand_marker"
    }
}

/** What the switches are for. The app's own part, apart from Android so that a test can stand in. */
internal interface OnDemandHelper {
    /** The audio path can be used: this alone counts as success (ShellAudioPaths.factory). */
    val ready: Boolean

    /** On its way out: not running any more, and not to be counted on. */
    val stopping: Boolean

    /** With the switches on: finds adbd and starts the helper within [budget], giving up when [stopped] says so. */
    fun start(allowCalls: Boolean, budget: AdbBudget, stopped: () -> Boolean): AdbStartOutcome

    /** The helper is there already: only the permission for a fully automatic call may be missing. */
    fun allowCalls(): AdbStartOutcome

    /** Ends it and waits for that, for a limited time. */
    fun stopAndWait()
}

/** Whether a phone call goes on, as far as the phone says. UNKNOWN: the app may not ask. */
internal enum class PhoneCallState { NONE, ON, UNKNOWN }

internal enum class OnDemandRestore { NOTHING_LEFT, PUT_BACK, FAILED, LATER }

/**
 * Debugging on the phone only for the time of a call (ADR-049): the switches are really off between
 * calls, nothing here hides them from other apps. In order: USB debugging on, Wireless debugging on, the
 * helper started, Wireless debugging back; so during the call only USB debugging is on, which keeps
 * adbd and with it the helper when the Wi-Fi drops. After the call: the helper stopped, USB debugging back.
 *
 * Everything runs on one background thread, one job at a time, so a [finish] and the next [prepare]
 * cannot overlap.
 */
internal class AdbOnDemand(
    private val switches: DebugSwitches,
    private val markers: OnDemandMarkerStore,
    private val helper: OnDemandHelper,
    private val nowMs: () -> Long,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** The helper is going away because the app asked it to: no notice about that. */
    @Volatile
    var expectedStop = false
        private set

    /** A helper is running: if it goes away from now on, nobody asked for that. */
    fun helperIsRunning() {
        expectedStop = false
    }

    /** Something changed for a call is still to be put back. */
    val leftOn: Boolean get() = markers.read() != null

    /**
     * [AdbStartOutcome.STARTED] or [AdbStartOutcome.ALREADY_RUNNING]: the call can go on, and [finish] has
     * to follow however it ends. Anything else: everything has been put back already.
     * [stopped]: the person gave up; a job that only begins after that (or after [budget]) changes nothing.
     */
    fun prepare(allowCalls: Boolean, budget: AdbBudget, stopped: () -> Boolean): AdbStartOutcome {
        if (stopped() || budget.remainingMs() == 0L) return AdbStartOutcome.FAILED
        while (helper.stopping && budget.remainingMs() > 0 && !stopped()) sleep(POLL_MS)
        if (helper.ready) return if (allowCalls) helper.allowCalls() else AdbStartOutcome.ALREADY_RUNNING
        // A marker left from before: those are the person's values, what is there now may be the app's
        val left = markers.read()
        val marker = OnDemandMarker(
            originalUsb = left?.originalUsb ?: switches.usb,
            originalWifi = left?.originalWifi ?: switches.wifi,
            wroteUsb = ON,
            wroteWifi = ON,
            // A helper that was already there returned above: from here on, one that appears is this one's
            startedHelper = true,
            atMs = nowMs(),
        )
        try {
            markers.write(marker)
        } catch (e: IOException) {
            return AdbStartOutcome.FAILED
        }
        expectedStop = false
        val outcome = try {
            switches.usb = ON
            switches.wifi = ON
            helper.start(allowCalls, budget, stopped)
        } catch (e: SecurityException) {
            AdbStartOutcome.NO_PERMISSION
        } catch (e: Exception) {
            AdbStartPolicy.outcomeOf(e)
        }
        if (outcome != AdbStartOutcome.STARTED || !helper.ready) {
            finish()
            return if (outcome == AdbStartOutcome.STARTED) AdbStartOutcome.FAILED else outcome
        }
        return try {
            // This order: if the app ends in between, the marker still says it wrote 1, and the value is no longer 1
            if (switches.wifi == marker.wroteWifi) switches.wifi = marker.originalWifi
            markers.write(marker.copy(wroteWifi = marker.originalWifi))
            AdbStartOutcome.STARTED
        } catch (e: Exception) {
            finish()
            AdbStartOutcome.FAILED
        }
    }

    /** Stops the helper if this started it and puts back what still has the value this wrote. false: it could not; the marker stays. */
    fun finish(): Boolean {
        val marker = markers.read() ?: return true
        if (marker.startedHelper) {
            expectedStop = true
            helper.stopAndWait()
        }
        return try {
            if (marker.wroteWifi != marker.originalWifi && switches.wifi == marker.wroteWifi) switches.wifi = marker.originalWifi
            if (marker.wroteUsb != marker.originalUsb && switches.usb == marker.wroteUsb) switches.usb = marker.originalUsb
            markers.clear()
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * When the app starts, or comes back: what a call left that did not end the normal way. Not during
     * a call. When the app may not ask whether there is one, it waits until any call must have ended.
     */
    fun restoreIfLeftOn(call: PhoneCallState): OnDemandRestore {
        val marker = markers.read() ?: return OnDemandRestore.NOTHING_LEFT
        val wait = call == PhoneCallState.ON || (call == PhoneCallState.UNKNOWN && nowMs() - marker.atMs < UNKNOWN_CALL_HOLD_MS)
        if (wait) return OnDemandRestore.LATER
        return if (finish()) OnDemandRestore.PUT_BACK else OnDemandRestore.FAILED
    }

    private companion object {
        const val ON = 1
        const val POLL_MS = 100L

        /** Far longer than a call of the AI can last. */
        const val UNKNOWN_CALL_HOLD_MS = 30 * 60 * 1000L
    }
}

/** The right to change the switches stays while one of the two features needs it. */
internal fun keepsSecureSettings(autoStart: Boolean, onDemand: Boolean): Boolean = autoStart || onDemand

/** What the helper's absence means for a call. Outside RotepApp (no Android), for unit tests. [setupProblem]: see AdbSetup.onDemandSetupProblem. */
internal fun helperProblemForCall(onDemand: Boolean, setupProblem: String?, helperThere: Boolean): String? = when {
    onDemand -> setupProblem
    helperThere -> null
    else -> UserMessages.HELPER_NOT_RUNNING
}
