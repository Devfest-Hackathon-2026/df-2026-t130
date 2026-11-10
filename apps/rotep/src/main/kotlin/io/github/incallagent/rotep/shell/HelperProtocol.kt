// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.os.IBinder

/**
 * The contract between the app and the helper that runs as the shell user (uid 2000).
 *
 * The helper is started from a PC with `adb shell` and `app_process` (see [LaunchCommand]). It hands
 * its binder to the app's content provider. The app only accepts a caller that is the shell or root
 * ([RegistrationPolicy]) and whose app version matches the installed app ([VersionGate]); the helper
 * only hands its binder to a provider that belongs to the app ([ProviderOwnerPolicy]).
 *
 * Everything here is plain Kotlin (the one Android type, IBinder, is only carried and never called),
 * so it is covered by unit tests on the PC.
 */
object HelperProtocol {
    /** This app's own package. One package per member, so it is a fixed constant. */
    const val APP_PACKAGE = "io.github.incallagent.rotep"

    /** The content provider that receives the helper's binder (declared in the manifest). */
    const val AUTHORITY = "$APP_PACKAGE.helper"

    /** The class that `app_process` runs as the entry point of the helper. For the test that keeps the start script in step. */
    const val MAIN_CLASS = "$APP_PACKAGE.shell.HelperMain"

    /** The process name the helper shows in `ps` and `pidof` (set with app_process --nice-name). For the test that keeps the start script in step. */
    const val PROCESS_NAME = "rotep-helper"

    /** The provider method the helper calls to register itself. */
    const val METHOD_REGISTER = "register"

    // Keys in the register call (helper -> app) and its result (app -> helper).
    const val KEY_SERVICE = "service" // IBinder: the helper's IHelperService
    const val KEY_VERSION = "version" // int: HelperVersion of the installed app, as the helper saw it at its start
    const val KEY_PID = "pid" // int: the helper's pid (shown on the screen only)
    const val KEY_UID = "uid" // int: the helper's uid (shown on the screen only)
    const val KEY_ACCEPTED = "accepted" // boolean: the app took the helper
    const val KEY_REASON = "reason" // String: why the app refused (when not accepted)
    const val KEY_RETRY = "retry" // boolean (when not accepted): true = try again later, false = the helper ends
    const val KEY_CALLBACK = "callback" // IBinder: the app's IHelperCallback (when accepted)
}

/** Who may register a helper with the app. The real caller uid comes from Binder, never from the extras. */
object RegistrationPolicy {
    private const val ROOT_UID = 0

    /** Only the shell user (uid 2000) or root may register a helper. */
    fun accepts(callingUid: Int): Boolean = callingUid == ShellIdentity.SHELL_UID || callingUid == ROOT_UID
}

/** The version number of an install of the app, in the helper's and the app's words. */
object HelperVersion {
    /**
     * Seconds since the epoch of the app's last install or update. It changes every time the app is
     * installed again, so a helper that was started from the old install is told apart from the new
     * app. (A reinstall takes longer than one second.)
     */
    fun fromLastUpdate(lastUpdateTimeMillis: Long): Int =
        (lastUpdateTimeMillis / 1000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The app takes a helper only when the version the helper saw at its start matches the installed
 * app. A helper left over from an older install carries the old value, so the new app refuses it and
 * that stale process ends itself.
 */
object VersionGate {
    fun accepts(expected: Int, got: Int?): Boolean = got != null && got == expected
}

/** Whose provider the helper reached, seen from the uid of the app the helper was started for. */
enum class ProviderOwner {
    /** The owner has the uid the helper knows for the app. */
    THE_APP,

    /**
     * The owner is known and it is not that app: the app was installed again with another uid (the helper
     * still knows the old one), or another app holds the provider's name.
     */
    ANOTHER_APP,

    /** The owner (or the helper's own idea of the app's uid) is missing or not a uid: nothing can be said. */
    UNKNOWN,
}

/** The helper hands its binder only to a provider whose owner is the app (the same uid as the app's package). */
object ProviderOwnerPolicy {
    fun check(providerUid: Int?, appUid: Int): ProviderOwner = when {
        providerUid == null || providerUid < 0 || appUid < 0 -> ProviderOwner.UNKNOWN
        providerUid == appUid -> ProviderOwner.THE_APP
        else -> ProviderOwner.ANOTHER_APP
    }

    /** [providerUid] is null when it could not be read: then the answer is no. */
    fun accepts(providerUid: Int?, appUid: Int): Boolean = check(providerUid, appUid) == ProviderOwner.THE_APP

    /**
     * What the helper does about a provider owned by [providerUid]: null when it is the app's own
     * provider (the call goes on), [Handoff.AnotherApp] when it is somebody else's (the helper ends and
     * never tries again: it was started for an install that is gone), and [Handoff.NotReached] when the
     * owner cannot be read (nothing is known, so the helper tries again later).
     */
    fun handoffFor(providerUid: Int?, appUid: Int): Handoff? = when (check(providerUid, appUid)) {
        ProviderOwner.THE_APP -> null
        ProviderOwner.ANOTHER_APP -> Handoff.AnotherApp("provider uid=$providerUid, app uid=$appUid")
        ProviderOwner.UNKNOWN -> Handoff.NotReached
    }
}

/**
 * What one try to hand the helper's binder to the app came to. It holds no Bundle, so that the decision
 * on it ([LinkPolicy]) can be tested on the PC.
 */
sealed interface Handoff {
    /** The app took the helper and gave its callback binder. */
    class Accepted(val callback: IBinder) : Handoff

    /** The app answered no. [retry] says whether it may take the helper later ([HelperProtocol.KEY_RETRY]). */
    class Refused(val reason: String, val retry: Boolean) : Handoff

    /** The app could not be reached this time (not installed, its provider not ready, a call failed). */
    object NotReached : Handoff

    /** The provider belongs to another app than the one this helper was started for ([ProviderOwner.ANOTHER_APP]). */
    class AnotherApp(val detail: String) : Handoff
}

/** What the helper's link loop does after a try. */
enum class LinkStep {
    /** The app took the helper: go back to watching the connection. */
    CONNECTED,

    /** Not now: wait ([HelperLifetime.retryDelayMs]) and try again. */
    TRY_AGAIN,

    /** Never again: the helper ends, so the process does not stay behind. */
    END,
}

object LinkPolicy {
    fun step(handoff: Handoff): LinkStep = when (handoff) {
        is Handoff.Accepted -> LinkStep.CONNECTED
        is Handoff.Refused -> if (handoff.retry) LinkStep.TRY_AGAIN else LinkStep.END
        Handoff.NotReached -> LinkStep.TRY_AGAIN
        is Handoff.AnotherApp -> LinkStep.END
    }
}

/** The one line [IHelperService.helperInfo] returns: pid, uid and the version the helper saw at its start. */
object HelperInfoText {
    private val VERSION = Regex("""(?:^|\s)version=(\d+)(?:\s|$)""")

    fun format(pid: Int, uid: Int, version: Int): String = "pid=$pid uid=$uid version=$version"

    /** The version in [info], or null when the text has none (a helper from an older build). */
    fun parseVersion(info: String?): Int? = info?.let { VERSION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
}

/**
 * How long the helper lives without the app, and how often it tries to reach the app.
 *
 * The helper does not end when the app dies: it goes back to "not connected" and hands its binder to
 * the app again (which also starts the app's process). The first try comes at once (within [POLL_MS]
 * of the app's death); after a failed try the helper waits [FIRST_RETRY_MS], and after that
 * [LATER_RETRY_MS] between tries.
 *
 * It ends only when it is told to ([IHelperService.destroy]), when the app refuses it for good because
 * the versions differ, when the app's provider belongs to another app (the app was installed again
 * with another uid; see [LinkPolicy]), or when it has been without the app for [DISCONNECT_LIMIT_MS].
 */
object HelperLifetime {
    const val FIRST_RETRY_MS = 2_000L
    const val LATER_RETRY_MS = 5_000L
    const val DISCONNECT_LIMIT_MS = 10 * 60 * 1000L

    /** How often the helper looks at its own state while it waits. */
    const val POLL_MS = 1_000L

    /** The wait before the next try, after [failedTries] tries in a row have failed (at least 1). */
    fun retryDelayMs(failedTries: Int): Long = if (failedTries <= 1) FIRST_RETRY_MS else LATER_RETRY_MS

    /** True when the helper has been without the app for too long and must end. */
    fun shouldEnd(disconnectedForMs: Long): Boolean = disconnectedForMs >= DISCONNECT_LIMIT_MS
}

/**
 * The commands the diagnostics screen shows and the person runs on the PC.
 *
 * The script (apps/rotep/helper/rotep-helper.sh) is pushed to /data/local/tmp once and then run with
 * `adb shell sh`. A script keeps `$()`, `|`, `<`, `>` and `&` off the PC command line, so the commands
 * are the same in PowerShell, cmd and bash. The APK path is looked up on the device (`pm path`)
 * each time, so the commands do not change when the app is installed again.
 */
object LaunchCommand {
    /** Where the script is in the repository (relative to the top folder). */
    const val SCRIPT_IN_REPO = "apps/rotep/helper/rotep-helper.sh"

    /** Where the script is on the device. /data/local/tmp can be written only by the shell and root. */
    const val SCRIPT_ON_DEVICE = "/data/local/tmp/rotep-helper.sh"

    /** Puts the script on the device (needed once, and again when the script changes). */
    fun push(): String = "adb push $SCRIPT_IN_REPO $SCRIPT_ON_DEVICE"

    /** Starts the helper. It keeps running after the cable is unplugged. */
    fun run(): String = "adb shell sh $SCRIPT_ON_DEVICE"

    /** Runs the helper in the foreground and prints its errors (to find out why it does not start). */
    fun foreground(): String = "adb shell sh $SCRIPT_ON_DEVICE fg"

    /** The two commands in the order they are run. */
    fun build(): String = push() + "\n" + run()
}
