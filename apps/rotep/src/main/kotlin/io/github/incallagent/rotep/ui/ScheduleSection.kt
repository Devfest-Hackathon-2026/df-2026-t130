// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.schedule.ScheduleOffer
import io.github.incallagent.rotep.schedule.suggestionNote
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.schedule.ScheduledCalls
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * On the confirm screen: the same approval, for a time the user chooses. Nothing is approved until
 * the button of the dialog is pressed.
 */
@Composable
fun ScheduleSection(offer: ScheduleOffer, shopName: String, enabled: Boolean, needsWifi: Boolean = false, onApprove: (LocalDateTime) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Section("時間を指定して電話する") {
        Text(SCHEDULE_LEAD, style = MaterialTheme.typography.bodyMedium)
        Small(SCHEDULE_NOTE)
        if (needsWifi) Small("その時刻に、「常に許可」にした Wi-Fi につながっている必要があります")
        suggestionNote(offer.suggestedAt)?.let { Small(it) }
        offer.blocked?.let { Small(it) }
        MainOutlinedButton(onClick = { choosing = true }, Modifier.fillMaxWidth(), enabled = enabled && offer.blocked == null, compact = true) { Text("日時を選ぶ") }
    }
    if (choosing) {
        ScheduleDialog(
            offer,
            shopName,
            onApprove = {
                choosing = false
                onApprove(it)
            },
            onDismiss = { choosing = false },
        )
    }
}

/** The date and the time, and the approval for that time. */
@Composable
private fun ScheduleDialog(offer: ScheduleOffer, shopName: String, onApprove: (LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(offer.first.toLocalDate()) }
    var time by remember { mutableStateOf("%d:%02d".format(Locale.ROOT, offer.first.hour, offer.first.minute)) }
    val at = RequestForm.parseTime(time)?.let { LocalDateTime.of(date, it) }
    val problem = if (at == null) "時は 0〜23、分は 0〜59 で入れてください" else ScheduleRules.problem(at, Instant.now())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("時間を指定して電話する", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleChooser(shopName, offer.today, date, time, problem, onDate = { date = it }, onTime = { time = it })
            }
        },
        confirmButton = {
            val words = if (at != null && problem == null) "${ScheduleRules.whenText(at)}に電話する" else "電話する"
            MainButton(
                onClick = { at?.let(onApprove) },
                modifier = Modifier.semantics { contentDescription = "$shopName に $words ことを承認する" },
                enabled = problem == null,
                compact = true,
            ) { Text(words) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** What the dialog holds. Apart from it, so that a preview can show it. */
@Composable
fun ScheduleChooser(
    shopName: String,
    today: LocalDate,
    date: LocalDate,
    time: String,
    problem: String?,
    onDate: (LocalDate) -> Unit,
    onTime: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("$shopName に電話します")
        ChoiceButtons(ScheduleRules.dates(today), date, { ScheduleRules.dateLabel(it, today) }, onDate)
        // Two fields, because the number keyboard has no ":". The value stays one text, "時:分"
        val hour = time.substringBefore(':')
        val minute = time.substringAfter(':', "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("時", hour, Modifier.weight(1f), keyboard = KeyboardType.Number, isError = problem != null) { onTime(twoDigits(it) + ":" + minute) }
            Field("分", minute, Modifier.weight(1f), keyboard = KeyboardType.Number, isError = problem != null) { onTime(hour + ":" + twoDigits(it)) }
        }
        Small("選べる時刻は 8:00 から 20:59 までです")
        problem?.let { ErrorText(it) }
    }
}

private fun twoDigits(text: String) = text.filter(Char::isDigit).take(2)

/**
 * The call that waits for its time, above the first screen: when, to whom, and "取り消す". Nothing
 * here says anything about the request but the shop.
 */
@Composable
fun ScheduledCallBanner(info: ScheduledCalls.Info, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = LocalAitelColors.current.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(R.drawable.ic_call)
                Text("時間を指定した電話", style = MaterialTheme.typography.labelLarge)
            }
            Text(ScheduleRules.bannerText(info.shopName, info.fireAt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, Modifier.semantics { contentDescription = "時間を指定した電話を取り消す" }) { Text("取り消す") }
            }
        }
    }
}

/** Both bands, when there are two. */
fun bothBanners(first: (@Composable () -> Unit)?, second: (@Composable () -> Unit)?): (@Composable () -> Unit)? = when {
    first == null -> second
    second == null -> first
    else -> {
        {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                first()
                second()
            }
        }
    }
}

private const val SCHEDULE_LEAD = "指定の時刻に AI が電話をかけ、最後まで済ませます。"
private const val SCHEDULE_NOTE = "画面がロックされていても、かかります。1 分前に通知し、結果は通知と「前回の電話」で分かります。AI が続けられないときは、折り返すと伝えて切ります（AI との接続が切れたときは、伝えられないまま切れます）"
