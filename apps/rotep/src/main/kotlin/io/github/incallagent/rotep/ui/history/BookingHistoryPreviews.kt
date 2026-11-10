// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.history.BookingHistory
import io.github.incallagent.rotep.history.Entry
import io.github.incallagent.rotep.intake.IntakeUi
import io.github.incallagent.rotep.ui.IntakeContent
import io.github.incallagent.rotep.ui.LastCallUi
import io.github.incallagent.rotep.ui.RotepFrame
import io.github.incallagent.rotep.ui.preview.RotepPreviews
import io.github.incallagent.rotep.ui.preview.Sample
import io.github.incallagent.rotep.ui.preview.SamplePreview
import java.time.Instant
import java.time.LocalDateTime

// Everything is fictitious
private val BOOKED = Entry(
    id = "1",
    at = Instant.parse("2026-11-09T10:30:00Z"),
    shopName = "テスト食堂",
    phoneNumber = "03-0000-0000",
    purpose = Purpose.NEW_BOOKING,
    bookedAt = LocalDateTime.of(2026, 11, 14, 19, 0),
    partySize = 2,
    reservationName = "テスト",
    headline = "予約できました（第一希望）",
    summary = "11月14日19時に2名で予約しました。キャンセルは前日までです。コースの内容は当日に決めてよいとのことで、アレルギーがあれば前日までに電話がほしいと言われました。",
    transcript = listOf(
        Line(Who.SHOP, "はい、テスト食堂です。"),
        Line(Who.AI, "AI が代理でお電話しています。11月14日の19時に、2名で予約できますか。"),
        Line(Who.SHOP, "はい、お取りできます。お名前をお願いします。"),
        Line(Who.AI, "テストです。"),
    ),
)

private val NOT_REACHED = Entry(
    id = "2",
    at = Instant.parse("2026-11-08T03:05:00Z"),
    shopName = "とても長い名前のテストレストラン 本店 二階 特別室 窓際のお席のご案内窓口",
    phoneNumber = "03-0000-0001",
    purpose = Purpose.INQUIRY,
    headline = "つながりませんでした",
    summary = "AI からの結果の報告はありません。",
)

private val NEEDS_REVIEW = BOOKED.copy(id = "3", at = Instant.parse("2026-11-07T09:00:00Z"), headline = "予約の返事はありましたが、確認が要ります")

private val MANY = List(50) { i ->
    BOOKED.copy(id = "m$i", at = Instant.parse("2026-11-09T10:30:00Z").minusSeconds(i * 86_400L), shopName = "テストのお店 ${i + 1}")
}

class HistorySamples : PreviewParameterProvider<Sample> {
    override val values = sequenceOf(
        Sample("history_list", "記録：一覧") { m ->
            Frame(m) { HistoryListContent(listOf(BOOKED, NOT_REACHED, NEEDS_REVIEW), null, {}, {}, {}, {}) }
        },
        Sample("history_many", "記録：50 件") { m -> Frame(m) { HistoryListContent(MANY, null, {}, {}, {}, {}) } },
        Sample("history_empty", "記録：なし") { m -> Frame(m) { HistoryListContent(emptyList(), null, {}, {}, {}, {}) } },
        Sample("history_problem", "記録：今は読めない") { m ->
            Frame(m) { HistoryListContent(listOf(BOOKED), BookingHistory.NOT_NOW, {}, {}, {}, {}) }
        },
        Sample("history_detail", "記録：詳細") { m -> Frame(m) { HistoryDetailContent(BOOKED, {}, {}, {}) } },
        Sample("history_detail_long", "記録：長い店名の詳細") { m -> Frame(m) { HistoryDetailContent(NOT_REACHED, {}, {}, {}) } },
        Sample("history_section", "記録：設定の節") { m ->
            Frame(m) {
                LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    item { BookingHistorySectionContent(keep = true, count = 3, problem = null, onKeep = {}, onOpen = {}) }
                }
            }
        },
        Sample("history_first_screen", "記録：最初の画面の狭いカード") { m -> FirstScreen(m, recordsCount = 12) },
        Sample("history_first_screen_many", "記録：前回の電話なしで 50 件") { m -> FirstScreen(m, recordsCount = 50, last = null) },
    )
}

private val LAST_CALL = LastCallUi("予約できました（第一希望）", "テスト食堂、11月9日(月) 19:30")

@Composable
private fun FirstScreen(modifier: Modifier, recordsCount: Int, last: LastCallUi? = LAST_CALL) {
    RotepFrame(screen = Screen.INTAKE, message = null, onOpenSettings = {}, onDismissMessage = {}, modifier = modifier) {
        IntakeContent(
            ui = IntakeUi(),
            hasKey = true,
            practice = false,
            onType = {},
            onSay = { true },
            onAnswer = {},
            onRetry = {},
            onStop = {},
            onByHand = {},
            onToConfirm = {},
            onRestart = {},
            onOpenSettings = {},
            lastCall = last,
            recordsCount = recordsCount,
        )
    }
}

@Composable
private fun Frame(modifier: Modifier, content: @Composable () -> Unit) {
    RotepFrame(screen = Screen.HISTORY, message = null, onOpenSettings = {}, onDismissMessage = {}, modifier = modifier, content = content)
}

@RotepPreviews
@Composable
internal fun HistoryPreview(@PreviewParameter(HistorySamples::class) sample: Sample) = SamplePreview(sample)
