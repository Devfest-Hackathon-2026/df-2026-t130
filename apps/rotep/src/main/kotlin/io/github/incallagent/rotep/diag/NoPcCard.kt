// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.incallagent.rotep.shell.HelperPhase
import io.github.incallagent.rotep.shell.adb.AdbFeatures
import io.github.incallagent.rotep.shell.adb.AdbSetup
import io.github.incallagent.rotep.ui.setup.SetupGuideActivity

/** The phone-only start of the helper: pairing once, then starting it, without a PC (ADR-041). */
@Composable
internal fun NoPcCard(phase: HelperPhase) {
    val ui by AdbSetup.state.collectAsState()
    val context = LocalContext.current
    var port by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // What to do once the permissions have been asked for (granted or not: the screen says what is missing).
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke()
        afterPermissions = null
    }
    val withPermissions: (() -> Unit) -> Unit = { action ->
        val missing = missingPermissions(context)
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            askPermissions.launch(missing.toTypedArray())
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PC なしの起動（ワイヤレスデバッグ）", style = MaterialTheme.typography.titleSmall)
            Text(
                "スマホの「ワイヤレスデバッグ」を使って、PC なしで手伝い役を起動します。" +
                    "Wi-Fi につながっていて、開発者向けオプションが有効なことが必要です。",
                fontSize = 13.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openDeveloperSettings(context) }) { Text("開発者向けオプションを開く") }
                OutlinedButton(onClick = { context.startActivity(Intent(context, SetupGuideActivity::class.java)) }) { Text("案内") }
            }
            Text("A. 最初の 1 回：ペアリング", style = MaterialTheme.typography.labelLarge)
            Text(
                "「ペアリングを始める」を押してから、設定の「ワイヤレスデバッグ」をオンにして" +
                    "「ペア設定コードによるデバイスのペア設定」を開きます。通知に入力欄が出たら、" +
                    "設定の画面を閉じずに、通知へ 6 桁のコードを入れます。",
                fontSize = 12.sp,
            )
            Button(
                onClick = { withPermissions { AdbSetup.startPairing(context) } },
                enabled = !ui.searching && !ui.busy,
            ) { Text(if (ui.searching) "ペアリングの窓を探しています…" else "ペアリングを始める") }
            Text("B. 手伝い役の起動", style = MaterialTheme.typography.labelLarge)
            Text(
                "ワイヤレスデバッグがオンのときに押します。ペアリングは最初の 1 回だけで、次からはここだけです。",
                fontSize = 12.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { withPermissions { AdbSetup.startHelper(port) } },
                    enabled = !ui.busy && phase != HelperPhase.RUNNING,
                ) { Text("手伝い役を起動") }
                OutlinedButton(
                    onClick = { withPermissions { AdbSetup.allowAutomaticCalls(port) } },
                    enabled = !ui.busy,
                ) { Text("全自動を許可") }
            }
            Text("「全自動を許可」は、全自動の電話に要る許可を入れます。1 度で残ります。", fontSize = 12.sp)
            if (AdbFeatures.ON_DEMAND && ui.onDemand) TryPrepareForCall(busy = ui.busy, onTry = AdbSetup::tryPrepareForCall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = ui.autoStart, onCheckedChange = { AdbSetup.setAutoStart(it) })
                Text("再起動のあと、Wi-Fi につながったら自動で起動する", fontSize = 13.sp)
            }
            Text(
                "オンの間は、「手伝い役を起動」を押したときに、アプリに「設定を変更する権限」を入れます。" +
                    "再起動のあと、この権限でワイヤレスデバッグを自動でオンにします。オフにすると権限を取り消します（すぐに取り消せないときは、次に起動するときに）。",
                fontSize = 12.sp,
            )
            Text("C. 手で入れるとき（通知やポートの自動検出がうまくいかないときの予備）", style = MaterialTheme.typography.labelLarge)
            Text(
                "ペアリングは「ペア設定コード」の画面のポート、起動はワイヤレスデバッグの画面の" +
                    "「IP アドレスとポート」の番号です。自動で見つかるときは空のままにします。",
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ポート") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6 桁のコード（ペアリングのときだけ）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedButton(
                onClick = {
                    AdbSetup.pairManually(port, code)
                    code = ""
                },
                enabled = !ui.busy && port.isNotEmpty() && code.length == 6,
            ) { Text("この値でペアリング") }
            ui.events.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

/** With 「開発者向けオプションを普段は切っておく」 on: what a call does first, without the call. Apart, so that a preview can show it. */
@Composable
internal fun TryPrepareForCall(busy: Boolean, onTry: () -> Unit) {
    OutlinedButton(onClick = onTry, enabled = !busy) { Text("電話の準備を試す") }
    Text("電話はかけません。手伝い役を起動できるか確かめて、元に戻します。結果はこのカードのいちばん下に出ます。", style = MaterialTheme.typography.bodySmall)
}

/** Notifications carry the code input; on Android 17 the local network needs its own permission. */
private fun missingPermissions(context: Context): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= ANDROID_17 && !granted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private const val ANDROID_17 = 37

private fun openDeveloperSettings(context: Context) {
    // The extra opens the list at the Wireless debugging row where the Settings app supports it.
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
    runCatching { context.startActivity(intent) }
}
