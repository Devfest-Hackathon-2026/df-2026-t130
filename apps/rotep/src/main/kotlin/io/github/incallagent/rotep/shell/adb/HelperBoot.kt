// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import io.github.incallagent.rotep.shell.HelperProtocol

/**
 * What runs as the shell user over the app's own adb connection: the start of the helper and the
 * app op of the fully automatic mode. Nothing that comes from outside the app goes into these commands.
 */
internal object HelperBoot {
    /**
     * The asset with apps/rotep/helper/rotep-helper.sh, the script a PC runs. The app runs its text
     * as it is, so the helper is started in exactly one way: `pm path`, then `setsid app_process` with
     * its output sent to /dev/null, in the background.
     */
    const val SCRIPT_ASSET: String = "rotep-helper.sh"

    private const val PACKAGE = HelperProtocol.APP_PACKAGE

    /** The app op behind the fully automatic mode (L3): Telecom binds RotepInCallService only while it is allowed. */
    const val APP_OP: String = "MANAGE_ONGOING_CALLS"

    /** True when the script's result says the helper was asked to start (the helper itself is seen in HelperClient). */
    fun scriptStarted(result: AdbShellResult): Boolean =
        result.exitCode == 0 && result.output.contains("rotep-helper: started")

    /** Lets the app turn Wireless debugging on after a restart (the setting adb_wifi_enabled). Fixed text, from the app only. */
    fun grantSecureSettingsCommand(): String = "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS"

    /** Takes that right back, when the person turns the automatic start off. */
    fun revokeSecureSettingsCommand(): String = "pm revoke $PACKAGE android.permission.WRITE_SECURE_SETTINGS"

    fun appOpsSetCommand(): String = "appops set $PACKAGE $APP_OP allow"

    fun appOpsGetCommand(): String = "appops get $PACKAGE $APP_OP"

    /** True when the output of [appOpsGetCommand] says the op is allowed. */
    fun appOpsAllowed(output: String): Boolean =
        output.lineSequence().any { line ->
            val words = line.trim().split(Regex("[:;\\s]+"))
            words.size >= 2 && words[0] == APP_OP && words[1] == "allow"
        }
}
