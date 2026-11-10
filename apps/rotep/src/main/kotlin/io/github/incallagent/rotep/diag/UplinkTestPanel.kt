// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.incallagent.rotep.shell.DiagUiState
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.HelperPhase
import io.github.incallagent.rotep.shell.MarkerTone
import io.github.incallagent.rotep.shell.UplinkPhase
import io.github.incallagent.rotep.shell.UplinkTestKind
import io.github.incallagent.rotep.shell.UplinkTestRules
import io.github.incallagent.rotep.shell.uplinkBusy
import kotlinx.coroutines.delay

/** "5. 送話の試験": the uplink tests 0 to 4 with the marker tone (procedure: apps/rotep/a3.md). */
@Composable
internal fun UplinkTestCard(ui: DiagUiState) {
    val uplink = ui.uplink
    val context = LocalContext.current
    val busy = ui.uplinkBusy
    var test by rememberSaveable { mutableIntStateOf(UplinkTestKind.BASELINE.number) }
    var delaySeconds by rememberSaveable { mutableIntStateOf(UplinkTestRules.DEFAULT_DELAY_SECONDS) }
    var playSeconds by rememberSaveable { mutableIntStateOf(UplinkTestRules.DEFAULT_PLAY_SECONDS) }
    var levelDbfs by rememberSaveable { mutableIntStateOf(UplinkTestRules.DEFAULT_LEVEL_DBFS) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(busy) {
        while (busy) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val selected = UplinkTestKind.fromNumber(test) ?: UplinkTestKind.BASELINE
    val playMs = playSeconds * 1000L

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("5. 送話の試験", style = MaterialTheme.typography.titleSmall)
            Text(
                "通話がつながってから使う。目印の音（高さの違う3つの音、3秒）を10秒ごとに鳴らし、決めた秒数で自動で止まる。" +
                    "試験1〜3 は通話中だけ始まる。音は保存しない。",
                fontSize = 13.sp,
            )
            Column(Modifier.selectableGroup()) {
                for (kind in UplinkTestKind.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = kind.number == test, enabled = !busy, role = Role.RadioButton) {
                                test = kind.number
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind.number == test, onClick = null, enabled = !busy)
                        Text(kind.title, Modifier.padding(start = 4.dp), fontSize = 14.sp)
                    }
                }
            }
            Text(
                "始める前に: ${selected.step}",
                color = if (selected.micMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            ChoiceRow("遅らせる", UplinkTestRules.DELAY_CHOICES, delaySeconds, "秒", !busy) { delaySeconds = it }
            ChoiceRow("鳴らす", UplinkTestRules.PLAY_CHOICES, playSeconds, "秒", !busy) { playSeconds = it }
            ChoiceRow("音量", UplinkTestRules.LEVEL_CHOICES, levelDbfs, "dBFS", !busy) { levelDbfs = it }
            Text(
                "目印の音は ${MarkerTone.markersIn(playMs)} 回（始めてから ${MarkerTone.markerStartSeconds(playMs).joinToString("・")} 秒）",
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { HelperClient.startUplinkTest(selected, delaySeconds, playSeconds, levelDbfs) },
                    enabled = ui.phase == HelperPhase.RUNNING && !busy,
                ) { Text("開始") }
                OutlinedButton(onClick = HelperClient::stopUplinkTest, enabled = busy) { Text("止める") }
            }
            statusText(ui, now)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (uplink.log.isNotEmpty()) {
                Text("記録（いまの試験）", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Mono(uplink.log) }
            }
            if (uplink.records.isNotEmpty()) {
                OutlinedButton(onClick = { copyToClipboard(context, uplink.records.joinToString("\n\n")) }) {
                    Text("記録をコピー（${uplink.records.size} 件）")
                }
            }
        }
    }
}

/** One line about where the test stands, or null when there is nothing to say. */
private fun statusText(ui: DiagUiState, now: Long): String? {
    val u = ui.uplink
    val unfinished = u.requested || u.phase == UplinkPhase.WAITING || u.phase == UplinkPhase.PLAYING
    if (unfinished && !ui.uplinkBusy) return "手伝い役が終わったので、試験も終わった"
    val since = (now - u.sinceMs).coerceAtLeast(0)
    return when {
        u.requested -> "開始を頼んだ…"
        u.phase == UplinkPhase.WAITING -> {
            val left = (u.delaySeconds * 1000L - since).coerceAtLeast(0)
            "待っている: あと ${(left + 999) / 1000} 秒で鳴らし始める"
        }
        u.phase == UplinkPhase.PLAYING -> "鳴らしている: ${(since / 1000).coerceAtMost(u.playSeconds.toLong())} 秒 / ${u.playSeconds} 秒"
        u.phase == UplinkPhase.ENDED -> "終わった（記録の「止まった理由」を見る）"
        else -> null
    }
}

@Composable
private fun ChoiceRow(label: String, choices: List<Int>, value: Int, unit: String, enabled: Boolean, onChoose: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.width(56.dp), fontSize = 13.sp)
        for (choice in choices) {
            val padding = PaddingValues(horizontal = 2.dp)
            if (choice == value) {
                Button(onClick = {}, modifier = Modifier.weight(1f), enabled = enabled, contentPadding = padding) {
                    Text("$choice", fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = { onChoose(choice) }, modifier = Modifier.weight(1f), enabled = enabled, contentPadding = padding) {
                    Text("$choice", fontSize = 12.sp)
                }
            }
        }
        Text(unit, fontSize = 12.sp)
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("rotep-uplink", text))
}
