// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.incallagent.rotep.app.Alerts
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.request.PhoneRelay
import io.github.incallagent.rotep.ui.RotepUi
import io.github.incallagent.rotep.ui.theme.RotepTheme

/**
 * The delegated call app: 依頼 → 確認と承認 → 通話中 → 結果, plus 設定.
 * The state lives in [RotepApp], so a running call survives closing this screen.
 */
class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        Alerts.mainScreenShown = true
        RotepApp.restoreCallSoundIfLeft()
        RotepApp.refreshIncoming()
        RotepApp.refreshScheduled()
        PhoneRelay.foreground(true)
    }

    override fun onStop() {
        Alerts.mainScreenShown = false
        RotepApp.mainScreenHidden()
        PhoneRelay.foreground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bar icons that follow the light/dark theme
        enableEdgeToEdge()
        // Names, numbers and what was written would stay in the picture of the recent apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        } else {
            // Android 12 has no way to leave out just that picture
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        RotepApp.init(this)
        setContent {
            RotepTheme {
                Scaffold { padding ->
                    // Consumed, so the keyboard's room inside (RotepFrame's imePadding) does not add the
                    // navigation bar a second time
                    RotepUi(this, Modifier.padding(padding).consumeWindowInsets(padding))
                }
            }
        }
    }
}
