// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The few programs the helper runs as the shell user, with fixed arguments. Nothing from the app
 * goes into a command line, and what they print stays here.
 */
internal object ShellCommands {
    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val AM = "/system/bin/am"
    private const val LIMIT_MS = 3_000L

    /** The app's only activity that may come to the front. */
    private const val APP_ACTIVITY = "${HelperProtocol.APP_PACKAGE}/${HelperProtocol.APP_PACKAGE}.MainActivity"

    /**
     * The call states of every phone in `dumpsys telephony.registry` ([CallPicture]), or an empty
     * list when they could not be read. Stops reading at the end of the per-phone part: the rest of
     * the dump is long and not needed.
     */
    fun callPhones(): List<CallPicture.Phone> =
        withOutput(listOf(DUMPSYS, "telephony.registry")) { lines, cutOff -> CallPicture.phones(lines, cutOff) }.orEmpty()

    /**
     * Brings the app's screen to the front, the same as `adb shell am start` (the shell user holds
     * START_ACTIVITIES_FROM_BACKGROUND). The activity is singleTask, so no second copy starts.
     */
    fun bringAppToFront(): Boolean = withOutput(listOf(AM, "start", "-n", APP_ACTIVITY)) { lines, _ ->
        lines.forEach { _ -> }
        true
    } == true

    /**
     * Runs [argv] and gives its output to [read], with a question it can ask once the output has
     * ended: did [LIMIT_MS] cut it off? The process is ended after [LIMIT_MS] and when [read]
     * returns. Null when it could not be run or the output could not be read.
     */
    private fun <T> withOutput(argv: List<String>, read: (Sequence<String>, () -> Boolean) -> T): T? {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        val cutOff = AtomicBoolean(false)
        val limit = Thread({
            try {
                Thread.sleep(LIMIT_MS)
                cutOff.set(true)
                process.destroy()
            } catch (_: InterruptedException) {
                // finished in time
            }
        }, "helper-command-limit").apply {
            isDaemon = true
            start()
        }
        return try {
            process.inputStream.bufferedReader().useLines { read(it, cutOff::get) }
        } catch (_: IOException) {
            null
        } finally {
            limit.interrupt()
            process.destroy()
        }
    }
}
