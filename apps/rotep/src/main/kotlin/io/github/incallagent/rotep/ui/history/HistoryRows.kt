// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.history

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.history.Entry
import io.github.incallagent.rotep.ui.dateTime
import java.time.Instant
import java.time.ZoneId

/*
 * What the screens of 「電話の記録」 draw, as plain values. Nothing here needs Android or Compose, so
 * JUnit can check it.
 */

/** One fact of a kept call on the detail screen. */
class HistoryRow(val label: String, val value: String)

private val JAPAN: ZoneId = ZoneId.of("Asia/Tokyo")

fun callTime(at: Instant): String = dateTime(at.atZone(JAPAN).toLocalDateTime())

fun purposeText(purpose: Purpose): String = when (purpose) {
    Purpose.NEW_BOOKING -> "新しい予約"
    Purpose.CHANGE -> "予約の変更"
    Purpose.CANCEL -> "予約の取り消し"
    Purpose.INQUIRY -> "問い合わせ"
}

/** Only what there is: a call that booked nothing has no time, party or name. */
fun historyRows(entry: Entry): List<HistoryRow> = buildList {
    add(HistoryRow("結果", entry.headline))
    add(HistoryRow("電話した日時", callTime(entry.at)))
    add(HistoryRow("用件", purposeText(entry.purpose)))
    add(HistoryRow("電話番号", entry.phoneNumber))
    entry.bookedAt?.let { add(HistoryRow("予約の日時", dateTime(it))) }
    entry.partySize?.let { add(HistoryRow("人数", "${it}名")) }
    entry.reservationName?.let { add(HistoryRow("予約名", it)) }
    if (entry.summary.isNotBlank()) add(HistoryRow("要約", entry.summary))
}

fun transcriptLine(line: CallSession.Line): String =
    (if (line.who == CallSession.Who.SHOP) "お店：" else "AI：") + line.text

/** All of one call, as text to read anywhere. Only what there is. */
fun copyText(entry: Entry): String = buildString {
    appendLine("店名：${entry.shopName}")
    appendLine("電話番号：${entry.phoneNumber}")
    appendLine("用件：${purposeText(entry.purpose)}")
    appendLine("電話した日時：${callTime(entry.at)}")
    entry.bookedAt?.let { appendLine("予約の日時：${dateTime(it)}") }
    entry.partySize?.let { appendLine("人数：${it}名") }
    entry.reservationName?.let { appendLine("予約名：$it") }
    appendLine("結果：${entry.headline}")
    if (entry.summary.isNotBlank()) appendLine("要約：${entry.summary}")
    if (entry.transcript.isNotEmpty()) {
        appendLine()
        appendLine("会話（聞き取りの結果）")
        entry.transcript.forEach { appendLine(transcriptLine(it)) }
    }
}.trimEnd()

/** What screen readers say for the copy button. */
fun copyDescription(entry: Entry): String = "${entry.shopName}の記録をコピー"

/** From Android 13 the system shows that something was copied: said twice, it would only get in the way. */
fun showsOwnCopyNotice(sdk: Int): Boolean = sdk <= LAST_SDK_WITHOUT_COPY_NOTICE

private const val LAST_SDK_WITHOUT_COPY_NOTICE = 32

fun showsRecordsCard(count: Int, callRunning: Boolean): Boolean = count > 0 && !callRunning

fun recordsCardText(count: Int): String = "電話の記録（$count 件）"
