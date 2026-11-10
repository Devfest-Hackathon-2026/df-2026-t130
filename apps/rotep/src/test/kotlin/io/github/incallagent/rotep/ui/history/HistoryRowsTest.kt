// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.history

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.history.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class HistoryRowsTest {
    // Everything is fictitious. 2026-11-09T10:30Z is 19:30 in Japan
    private val entry = Entry(
        id = "a",
        at = Instant.parse("2026-11-09T10:30:00Z"),
        shopName = "テスト食堂",
        phoneNumber = "03-0000-0001",
        purpose = Purpose.NEW_BOOKING,
        headline = "予約できました（第一希望）",
        summary = "11月14日19時に2名で予約しました",
    )

    private fun rows(e: Entry) = historyRows(e).map { it.label to it.value }

    @Test
    fun theCallTimeIsShownInJapan() {
        assertEquals("11月9日(月) 19:30", callTime(entry.at))
    }

    @Test
    fun aBookingShowsAllOfItsFacts() {
        val booked = entry.copy(bookedAt = LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, reservationName = "テスト")
        assertEquals(
            listOf(
                "結果" to "予約できました（第一希望）",
                "電話した日時" to "11月9日(月) 19:30",
                "用件" to "新しい予約",
                "電話番号" to "03-0000-0001",
                "予約の日時" to "11月14日(土) 19:00",
                "人数" to "2名",
                "予約名" to "テスト",
                "要約" to "11月14日19時に2名で予約しました",
            ),
            rows(booked),
        )
    }

    @Test
    fun whatWasNotBookedIsLeftOut() {
        val labels = rows(entry).map { it.first }
        for (absent in listOf("予約の日時", "人数", "予約名")) assertFalse(absent, absent in labels)
    }

    @Test
    fun aBlankSummaryIsLeftOut() {
        assertFalse("要約" in rows(entry.copy(summary = " ")).map { it.first })
    }

    @Test
    fun everyPurposeHasItsWord() {
        assertEquals(
            listOf("新しい予約", "予約の変更", "予約の取り消し", "問い合わせ"),
            listOf(Purpose.NEW_BOOKING, Purpose.CHANGE, Purpose.CANCEL, Purpose.INQUIRY).map(::purposeText),
        )
    }

    @Test
    fun theConversationSaysWhoSpoke() {
        assertEquals("お店：はい", transcriptLine(Line(Who.SHOP, "はい")))
        assertEquals("AI：予約を", transcriptLine(Line(Who.AI, "予約を")))
    }
}
