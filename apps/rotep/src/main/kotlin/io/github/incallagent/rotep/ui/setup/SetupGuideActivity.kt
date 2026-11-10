// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.setup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.HelperPhase
import io.github.incallagent.rotep.shell.adb.AdbSetup
import io.github.incallagent.rotep.ui.MainOutlinedButton
import io.github.incallagent.rotep.ui.Notice
import io.github.incallagent.rotep.ui.ScreenName
import io.github.incallagent.rotep.ui.Section
import io.github.incallagent.rotep.ui.SmallIcon
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import io.github.incallagent.rotep.ui.theme.RotepTheme
import kotlinx.coroutines.delay

/**
 * The first setup without a PC, in order, with what the phone says about each step right now.
 * Opened from the diagnostics screen. The pairing and the start themselves are done on that screen.
 */
class SetupGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bar icons that follow the light/dark theme
        enableEdgeToEdge()
        HelperClient.init(application)
        AdbSetup.init(application)
        setContent {
            RotepTheme {
                Scaffold(containerColor = LocalAitelColors.current.pageQuiet) { padding -> SetupGuideScreen(Modifier.padding(padding)) }
            }
        }
    }
}

/** What each row of the guide shows: read again every second, because the settings change outside the app. */
internal data class GuideStatus(
    val developerOptions: Boolean,
    val wifi: Boolean,
    val usbDebugging: Boolean,
    val wirelessDebugging: Boolean,
    val paired: Boolean,
)

@Composable
private fun SetupGuideScreen(modifier: Modifier = Modifier) {
    val helper by HelperClient.state.collectAsState()
    var status by remember { mutableStateOf(readStatus()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = readStatus()
            delay(1_000)
        }
    }
    SetupGuideContent(status, helperRunning = helper.phase == HelperPhase.RUNNING, modifier)
}

/** The guide itself, from what the phone says about each step: apart from it, so that a preview can show it. */
@Composable
internal fun SetupGuideContent(status: GuideStatus, helperRunning: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenName("最初の準備（PC なし）")
        Guide("上から順に進めます。診断の画面の「PC なしの起動」と一緒に使います。")
        Step("1. Wi-Fi につなぐ", status.wifi, "接続している", "つながっていない") {
            Guide("手伝い役を起動するときは Wi-Fi が要ります（ワイヤレスデバッグは Wi-Fi のときだけ使えます）。")
            OpenButton("Wi-Fi の設定を開く") { open(context, Settings.ACTION_WIFI_SETTINGS) }
        }
        Step("2. 開発者向けオプションを有効にする", status.developerOptions, "有効", "無効") {
            Guide("設定 → 端末情報 → ビルド番号を 7 回タップします（機種によって場所が少し違います）。")
            OpenButton("端末情報を開く") { open(context, Settings.ACTION_DEVICE_INFO_SETTINGS) }
        }
        Step("3. USB デバッグをオンのままにする", status.usbDebugging, "オン", "オフ") {
            Guide(
                "USB デバッグとワイヤレスデバッグのどちらもオフになると、手伝い役は消えます。" +
                    "Wi-Fi が切れてワイヤレスデバッグが切れても手伝い役が残るよう、USB デバッグはオンのままにしてください。",
            )
            // Something to keep in mind, not something that went wrong: not the error color
            Notice(
                "銀行や決済のアプリには、USB デバッグがオンだと起動しないものがあります。" +
                    "そのようなアプリを使う時間だけオフにする場合は、そのあと手伝い役を起動し直してください。",
                icon = R.drawable.ic_warning,
            )
            OpenButton("開発者向けオプションを開く") { open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        }
        Step("4. ワイヤレスデバッグをオンにする", status.wirelessDebugging, "オン", "オフ") {
            Guide("「このネットワークでワイヤレスデバッグを許可しますか」と聞かれたら「常に許可」を選びます。再起動のあとの自動の起動にも要ります。")
        }
        Step("5. ペアリング（最初の 1 回）", status.paired, "済んでいる", "まだ") {
            Guide("診断の画面の「ペアリングを始める」を押し、設定の画面を閉じずに、通知へ 6 桁のコードを入れます。")
        }
        Step("6. 手伝い役を起動する", helperRunning, "動いている", "つながっていない") {
            Guide("診断の画面の「手伝い役を起動」を押します。「全自動を許可」も 1 度押しておきます。")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One step: its title, what the phone says about it now (a check mark only for what is done), then what to do. */
@Composable
private fun Step(title: String, ok: Boolean, okText: String, notOkText: String, details: @Composable ColumnScope.() -> Unit) {
    Section(title) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(
                if (ok) R.drawable.ic_check_circle else R.drawable.ic_info,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("いま：${if (ok) okText else notOkText}", style = MaterialTheme.typography.labelLarge)
        }
        details()
    }
}

@Composable
private fun Guide(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun OpenButton(label: String, onClick: () -> Unit) {
    MainOutlinedButton(onClick = onClick, Modifier.fillMaxWidth(), compact = true) { Text(label) }
}

private fun readStatus(): GuideStatus {
    val system = AdbSetup.status
    return GuideStatus(
        developerOptions = system.developerOptionsOn(),
        wifi = system.wifiConnected(),
        usbDebugging = system.usbDebuggingOn(),
        wirelessDebugging = system.wirelessDebuggingOn(),
        paired = AdbSetup.paired,
    )
}

private fun open(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action)) }
}
