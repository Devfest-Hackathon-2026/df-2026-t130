// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.incallagent.rotep.shell.DiagUiState
import io.github.incallagent.rotep.shell.EnvReport
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.HelperPhase
import kotlinx.coroutines.delay

/** The whole diagnostics screen: the smallest set of buttons that exercise the helper. */
@Composable
internal fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val ui by HelperClient.state.collectAsState()
    val allReports = listOf(ui.report, ui.meter.startReport, ui.meter.stopReport).joinToString("\n")
    // Here and not in the card: the card leaves the composition when the list scrolls past it
    DisposableEffect(Unit) { onDispose { VoiceProbeRunner.cancel() } }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("診断（手伝い役）", style = MaterialTheme.typography.headlineSmall)
            Text("通話の音声は保存しません。音量の数字だけを出します。", style = MaterialTheme.typography.bodyMedium)
        }
        if (EnvReport.hasAttributionProblem(allReports)) {
            item {
                Banner(
                    "録音の名義が shell になっていない。受話は断られるか、無音になる。報告の「録音の名義」の行を見る。",
                    MaterialTheme.colorScheme.error,
                )
            }
        }
        if (EnvReport.hasSilencedWarning(allReports)) {
            item { Banner("システムが録音を黙らせている（isClientSilenced = true）。音は来ない。", MaterialTheme.colorScheme.secondary) }
        }
        item { VoiceProbeCard() }
        item { NoPcCard(ui.phase) }
        item { LaunchCard(ui) }
        item { HelperStateCard(ui) }
        item { ReportCard(ui) }
        item { MeterCard(ui) }
        item { UplinkTestCard(ui) }
        item { EventsCard(ui.events) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = color, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LaunchCard(ui: DiagUiState) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. 手伝い役の起動", style = MaterialTheme.typography.titleSmall)
            Text(
                "PC を使うときの予備です。PC（USB でつないだ端末）で、次の2本を実行します。1本目は最初の1回だけ。" +
                    "起動した手伝い役は、USB を抜いても残ります。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ui.launchCommand.isNotEmpty()) SelectionContainer { Mono(ui.launchCommand) }
            OutlinedButton(
                onClick = { copyToClipboard(context, ui.launchCommand) },
                enabled = ui.launchCommand.isNotEmpty(),
            ) { Text("コマンドをコピー") }
        }
    }
}

@Composable
private fun HelperStateCard(ui: DiagUiState) {
    val running = ui.phase == HelperPhase.RUNNING
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("2. 手伝い役の状態", style = MaterialTheme.typography.titleSmall)
            Text(
                "状態: ${ui.phase.label}",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            if (ui.helperInfo.isNotEmpty()) Mono("手伝い役: ${ui.helperInfo}")
            OutlinedButton(
                onClick = { HelperClient.stopHelper("終了ボタン") },
                enabled = running,
            ) { Text("終了") }
            Text(
                "この画面を閉じても、アプリが落ちても、手伝い役は終わりません。終わらせるのは「終了」です。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReportCard(ui: DiagUiState) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("3. 環境の報告", style = MaterialTheme.typography.titleSmall)
            Text("通話の前と、通話中に、1回ずつ押す。", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = HelperClient::fetchReport,
                    enabled = ui.phase == HelperPhase.RUNNING && !ui.reportBusy,
                ) { Text(if (ui.reportBusy) "取得中…" else "報告を取る") }
                OutlinedButton(
                    onClick = { copyToClipboard(context, ui.report) },
                    enabled = ui.report.isNotEmpty(),
                ) { Text("コピー") }
            }
            if (ui.report.isNotEmpty()) SelectionContainer { Mono(ui.report) }
        }
    }
}

@Composable
private fun MeterCard(ui: DiagUiState) {
    val meter = ui.meter
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(meter.running) {
        while (meter.running) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("4. 受話の音量", style = MaterialTheme.typography.titleSmall)
            Text(
                "通話がつながってから「開始」。協力者に話してもらい、棒が動くか見る。60 秒で自動で止まる。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = HelperClient::startMeter,
                    enabled = ui.phase == HelperPhase.RUNNING && !meter.running && !meter.requested,
                ) { Text(if (meter.requested) "開始を頼んだ…" else "開始") }
                OutlinedButton(onClick = HelperClient::stopMeter, enabled = meter.running) { Text("停止") }
            }
            LevelBar("ピーク", meter.peakDbfs, meter.running)
            LevelBar("RMS", meter.rmsDbfs, meter.running)
            if (meter.running) {
                val sound = if (MeterFormat.isSounding(meter.rmsDbfs)) "音が来ている" else "いまは無音"
                Mono("経過 ${MeterFormat.elapsedText(now - meter.startedAtMs)} / $sound / 受け取ったサンプル ${meter.totalFrames}")
                MeterFormat.noDataText(now - meter.lastLevelAtMs)?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleSmall)
                }
            }
            if (meter.stopReason.isNotEmpty()) Mono("止まった理由: ${meter.stopReason}")
            if (meter.startReport.isNotEmpty()) {
                Text("開始の報告", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Mono(meter.startReport) }
            }
            if (meter.stopReport.isNotEmpty()) {
                Text("停止の報告", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Mono(meter.stopReport) }
            }
            if (meter.startReport.isNotEmpty() || meter.stopReport.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, listOf(meter.startReport, meter.stopReport, meter.stopReason).filter { it.isNotEmpty() }.joinToString("\n\n"))
                    },
                ) { Text("音量の報告をコピー") }
            }
        }
    }
}

@Composable
private fun LevelBar(label: String, dbfs: Float, active: Boolean) {
    val fraction = if (active) MeterFormat.barFraction(dbfs) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                // The card has the same colour as surfaceVariant, so the empty track needs its own.
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            if (active) MeterFormat.dbText(dbfs) else "—",
            Modifier.width(96.dp).padding(start = 8.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun EventsCard(events: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("出来事（新しいものが下）", style = MaterialTheme.typography.titleSmall)
            if (events.isEmpty()) Mono("まだない") else events.forEach { Mono(it) }
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("rotep-diag", text))
}
