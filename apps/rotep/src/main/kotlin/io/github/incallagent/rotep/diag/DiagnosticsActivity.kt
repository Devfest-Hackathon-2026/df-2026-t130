// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.adb.AdbSetup
import io.github.incallagent.rotep.ui.theme.RotepTheme

/**
 * The diagnostics screen ("診断"): the screen that checks the helper (the launch commands, the helper's state, the
 * environment report and the downlink level). It has its own task and no launcher icon: it is opened
 * from the settings screen ("困ったとき") by this class name.
 *
 * The helper is started from a PC with adb (the screen shows the commands), or with no PC through
 * the phone's Wireless debugging ([NoPcCard], shell/adb/, ADR-041). Closing this screen does
 * **not** end the helper, and neither does the death of the app process: the helper stays and finds
 * the app again. Only the "終了" button ends it (see [HelperClient]).
 */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bar icons that follow the light/dark theme
        enableEdgeToEdge()
        HelperClient.init(application)
        AdbSetup.init(application)
        setContent {
            RotepTheme {
                Scaffold { padding -> DiagnosticsScreen(Modifier.padding(padding)) }
            }
        }
    }
}
