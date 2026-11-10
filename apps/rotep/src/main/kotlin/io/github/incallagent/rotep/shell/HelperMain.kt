// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.Keep

/**
 * The entry point of the helper, run by `app_process` as the shell user from a PC with adb.
 *
 * It is started by the script apps/rotep/helper/rotep-helper.sh (see [LaunchCommand]), which finds the
 * APK with `pm path`, sets it as CLASSPATH and runs, detached from the adb session:
 *
 *   app_process /system/bin --nice-name=rotep-helper io.github.incallagent.rotep.shell.HelperMain
 *
 * What it does, in order:
 *  1. ends at once unless the process is the shell user (uid 2000);
 *  2. makes the main Looper and builds a Context without LoadedApk.makeApplication ([HelperContext]);
 *  3. reads the installed app's uid and version from the package manager;
 *  4. creates the [HelperService];
 *  5. keeps trying to hand the service's binder to the app through its content provider
 *     ([BinderSender]): the first try comes at once, after a failed try it waits 2 seconds, and after
 *     that it tries every 5 seconds ([HelperLifetime]). When the app accepts, it returns its callback
 *     binder and the helper attaches to it. If the app process dies, the helper is "not connected"
 *     again and starts over. What each try came to decides the next step ([LinkPolicy]): the helper
 *     ends at once, and does not try again, when the app refuses it because the versions differ and when
 *     the provider belongs to another app than the one the helper was started for (the app was
 *     installed again with another uid).
 *
 * The main thread keeps a Looper so the process stays alive; the process ends through System.exit
 * inside the service (destroy, a refusal for good, another app's provider, or too long without the app).
 */
@Keep
object HelperMain {
    private const val TAG = "RotepHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != ShellIdentity.SHELL_UID) {
            fail("this process is not the shell user (uid=${Process.myUid()})")
            return
        }
        // Deprecated for apps, but this process has no framework to make the main Looper: it is ours.
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        Log.i(TAG, "helper main: uid=${Process.myUid()} pid=${Process.myPid()}")

        val built = HelperContext.create()
        if (built == null) {
            fail("no Context could be built")
            return
        }
        val installed = InstalledApp.read(built.context.packageManager, HelperProtocol.APP_PACKAGE)
        if (installed == null) {
            fail("the app ${HelperProtocol.APP_PACKAGE} is not visible to the package manager")
            return
        }
        val service = HelperService(built.context, installed.uid, built.shell, installed.version)

        // The link loop runs on its own thread; the main thread loops to keep the process (and its
        // binder threads) alive.
        Thread({ linkLoop(service, installed) }, "helper-link").apply {
            isDaemon = true
            start()
        }
        Looper.loop()
    }

    /** Keeps the helper connected to the app for as long as the helper lives. */
    private fun linkLoop(service: HelperService, installed: InstalledApp) {
        var failedTries = 0
        while (true) {
            try {
                if (service.isConnected()) {
                    failedTries = 0
                    Thread.sleep(HelperLifetime.POLL_MS)
                    continue
                }
                val handoff = BinderSender.register(service, installed.version, installed.uid)
                if (LinkPolicy.step(handoff) == LinkStep.END) {
                    end(service, handoff)
                    return
                }
                if (handoff is Handoff.Accepted && attach(service, handoff.callback)) {
                    failedTries = 0
                    continue
                }
                if (handoff is Handoff.Refused) Log.w(TAG, "register refused for now: ${handoff.reason}")
                failedTries++
                val wait = HelperLifetime.retryDelayMs(failedTries)
                Log.i(TAG, "register: not connected yet (failed tries: $failedTries, next try in ${wait / 1000} s)")
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Ends the helper for good, after a try that [LinkPolicy] counts as final: the app refused it with
     * no way back (the versions differ), or the provider belongs to another app (the app was installed
     * again with another uid, so the helper was started for an install that is gone). The process ends
     * inside [HelperService.quit].
     */
    private fun end(service: HelperService, handoff: Handoff) {
        when (handoff) {
            is Handoff.Refused -> {
                Log.w(TAG, "register refused for good: ${handoff.reason}")
                service.quit("アプリが受け取らなかった（${handoff.reason}）")
            }
            is Handoff.AnotherApp -> {
                Log.w(TAG, "register: the provider belongs to another app (${handoff.detail}); not trying again")
                service.quit("アプリが入れ直されたらしい（${handoff.detail}）")
            }
            else -> Unit
        }
    }

    /** Attaches the helper to the app's callback. Returns true when that worked. */
    private fun attach(service: HelperService, callbackBinder: IBinder): Boolean = try {
        // A local call, not a Binder transaction: the calling uid is the helper's own (shell),
        // which the caller policy allows.
        service.attach(IHelperCallback.Stub.asInterface(callbackBinder))
        Log.i(TAG, "registered with the app")
        true
    } catch (t: Throwable) {
        // For example the app died between its answer and this call.
        Log.w(TAG, "attach failed: ${ReportText.exceptionLine(t)}")
        false
    }

    private fun fail(reason: String) {
        Log.e(TAG, "helper main: $reason, exiting")
        System.exit(1)
    }
}
