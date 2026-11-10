// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Builds a Context for the helper, which runs from `app_process` as the shell user and is not a
 * normal app process. It never calls LoadedApk.makeApplication for the app.
 *
 * First route ([ROUTE_SYSTEM_MAIN]): `ActivityThread.systemMain()`, then `getSystemContext()`.
 * systemMain() also builds the system package's Application, and that step
 * (LoadedApk.makeApplicationInner) is where the Shizuku helper failed on this device. The system
 * Context is made before that step, so an exception from systemMain() is caught and the thread is
 * taken from `currentActivityThread()`.
 *
 * Second route ([ROUTE_NEW_THREAD]), used only when the first one gives no Context: the approach of
 * scrcpy's Workarounds (Genymobile, Apache-2.0, see NOTICE). Construct a bare `ActivityThread`, make it
 * the current system thread, and give it a ConfigurationController.
 *
 * In both routes the same best-effort fills follow, as in scrcpy: the application info (package
 * com.android.shell) and a fake application whose base Context is the [ShellContext]. The system
 * Context is wrapped in [ShellContext] so that recordings carry the shell name (com.android.shell,
 * uid 2000).
 *
 * Every fill is best effort: one that fails on a given ROM is logged and skipped, and the helper still
 * starts with the system Context and the [ShellContext] name overrides. The result is checked on the
 * device (the environment report's "録音の名義" line and logcat tag RotepHelper).
 */
// Lint models an app process that targets API 37, where reflection on these fields is blocked. This
// code runs only in the helper: a bare app_process of the shell user, where the runtime's hidden-API
// enforcement is off (the same is true for the scrcpy server, which uses the same fields).
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal object HelperContext {
    private const val TAG = "RotepHelper"
    private const val ACTIVITY_THREAD = "android.app.ActivityThread"
    private const val ROUTE_SYSTEM_MAIN = "systemMain"
    private const val ROUTE_NEW_THREAD = "new-thread"

    /**
     * The Context for the helper and, when it could be built, the [ShellContext] that names the
     * shell. [context] is the best Context available ([shell] when present, else the raw system one).
     */
    data class Built(val context: Context, val shell: ShellContext?)

    /** Creates the helper's Context, or null when no route could build even the system Context. */
    fun create(): Built? {
        for (route in listOf(ROUTE_SYSTEM_MAIN, ROUTE_NEW_THREAD)) {
            val steps = StringBuilder()
            val built = try {
                tryRoute(route, steps)
            } catch (t: Throwable) {
                Log.w(TAG, "context: route $route failed: ${ReportText.exceptionLine(t)} ($steps)")
                null
            }
            if (built != null) {
                Log.i(TAG, "context: built via $route ($steps shell=${built.shell != null})")
                return built
            }
        }
        Log.e(TAG, "context: no route could build a Context")
        return null
    }

    private fun tryRoute(route: String, steps: StringBuilder): Built? {
        val activityThread = (if (route == ROUTE_SYSTEM_MAIN) systemMainThread(steps) else newActivityThread(steps))
            ?: return null
        if (route == ROUTE_NEW_THREAD) {
            // systemMain() makes this itself; a bare thread does not.
            step(steps, "config") { fillConfigurationController(activityThread) }
        }
        step(steps, "appInfo") { fillAppInfo(activityThread) }

        val systemContext = runCatching {
            Class.forName(ACTIVITY_THREAD).getMethod("getSystemContext").invoke(activityThread) as Context
        }.getOrNull()
        if (systemContext == null) {
            Log.w(TAG, "context: getSystemContext failed on route $route ($steps)")
            return null
        }
        val shell = ShellContext.create(systemContext)
        val forApp: Context = shell ?: systemContext
        step(steps, "appContext") { fillAppContext(activityThread, forApp) }
        return Built(forApp, shell)
    }

    private fun systemMainThread(steps: StringBuilder): Any? {
        val cls = Class.forName(ACTIVITY_THREAD)
        try {
            cls.getMethod("systemMain").invoke(null)
            steps.append("sm ")
        } catch (t: Throwable) {
            // Reflection wraps the cause. The thread may still be usable, see the class comment.
            Log.w(TAG, "context: systemMain threw: ${ReportText.exceptionLine(t.cause ?: t)}")
            steps.append("!sm ")
        }
        return runCatching { cls.getMethod("currentActivityThread").invoke(null) }.getOrNull()
    }

    private fun newActivityThread(steps: StringBuilder): Any? = runCatching {
        val cls = Class.forName(ACTIVITY_THREAD)
        val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        val at = ctor.newInstance()
        cls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)
        runCatching {
            cls.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true)
        }
        steps.append("at ")
        at
    }.getOrElse {
        Log.w(TAG, "context: ActivityThread constructor failed: ${ReportText.exceptionLine(it)}")
        null
    }

    /** The thread needs a ConfigurationController (Android 12 and newer) before some getters work. */
    private fun fillConfigurationController(activityThread: Any) {
        val cls = Class.forName(ACTIVITY_THREAD)
        val ccClass = Class.forName("android.app.ConfigurationController")
        val internalClass = Class.forName("android.app.ActivityThreadInternal")
        val ctor = ccClass.getDeclaredConstructor(internalClass).apply { isAccessible = true }
        val cc = ctor.newInstance(activityThread)
        cls.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(activityThread, cc)
    }

    /** Makes ActivityThread.currentPackageName()/getProcessName() return the shell name. */
    private fun fillAppInfo(activityThread: Any) {
        val cls = Class.forName(ACTIVITY_THREAD)
        val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val appBindData = appBindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val appInfo = ApplicationInfo().apply { packageName = ShellIdentity.SHELL_PACKAGE }
        appBindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(appBindData, appInfo)
        cls.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, appBindData)
    }

    /** Makes ActivityThread.currentApplication() return a non-null application (audio paths read it). */
    private fun fillAppContext(activityThread: Any, context: Context) {
        val cls = Class.forName(ACTIVITY_THREAD)
        val application = Instrumentation.newApplication(Application::class.java, context)
        cls.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, application)
    }

    private inline fun step(steps: StringBuilder, name: String, block: () -> Unit) {
        try {
            block()
            steps.append(name).append(' ')
        } catch (t: Throwable) {
            Log.w(TAG, "context: step $name skipped: ${ReportText.exceptionLine(t)}")
            steps.append('!').append(name).append(' ')
        }
    }
}
