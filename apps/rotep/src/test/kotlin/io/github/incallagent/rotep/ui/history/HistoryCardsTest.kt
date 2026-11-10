// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.history

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.app.historyOpenedFrom
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.history.Entry
import io.github.incallagent.rotep.ui.retentionNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class HistoryCardsTest {
    // Everything is fictitious. 2026-11-09T10:30Z is 19:30 in Japan
    private val entry = Entry(
        id = "a",
        at = Instant.parse("2026-11-09T10:30:00Z"),
        shopName = "テスト食堂",
        phoneNumber = "03-0000-0001",
        purpose = Purpose.NEW_BOOKING,
        headline = "つながりませんでした",
        summary = "AI からの結果の報告はありません。",
    )

    private val booked = entry.copy(
        bookedAt = LocalDateTime.of(2026, 11, 14, 19, 0),
        partySize = 2,
        reservationName = "テスト",
        headline = "予約できました（第一希望）",
        summary = "11月14日19時に2名で予約しました",
        transcript = listOf(Line(Who.SHOP, "はい、テスト食堂です"), Line(Who.AI, "AI が代理でお電話しています")),
    )

    @Test
    fun theRecordIsKeptUnlessTheUserTurnedItOff() {
        assertTrue(Settings().keepBookingHistory)
    }

    @Test
    fun theWholeCallIsCopiedAsReadableText() {
        assertEquals(
            """
            店名：テスト食堂
            電話番号：03-0000-0001
            用件：新しい予約
            電話した日時：11月9日(月) 19:30
            予約の日時：11月14日(土) 19:00
            人数：2名
            予約名：テスト
            結果：予約できました（第一希望）
            要約：11月14日19時に2名で予約しました

            会話（聞き取りの結果）
            お店：はい、テスト食堂です
            AI：AI が代理でお電話しています
            """.trimIndent(),
            copyText(booked),
        )
    }

    @Test
    fun whatTheCallDidNotHaveIsLeftOutOfTheCopy() {
        val text = copyText(entry)
        for (absent in listOf("予約の日時", "人数", "予約名", "会話")) assertFalse(absent, absent in text)
        assertTrue(text.endsWith("要約：AI からの結果の報告はありません。"))
    }

    @Test
    fun aBlankSummaryIsLeftOutOfTheCopy() {
        assertFalse("要約" in copyText(entry.copy(summary = " ")))
    }

    @Test
    fun screenReadersHearWhoseRecordIsCopied() {
        assertEquals("テスト食堂の記録をコピー", copyDescription(entry))
    }

    @Test
    fun onlyOlderAndroidNeedsTheAppToSayItWasCopied() {
        assertTrue(showsOwnCopyNotice(31))
        assertTrue(showsOwnCopyNotice(32))
        assertFalse("Android 13 says so itself", showsOwnCopyNotice(33))
        assertFalse(showsOwnCopyNotice(36))
    }

    @Test
    fun theCardOnTheFirstScreenNeedsARecordAndNoCall() {
        assertTrue(showsRecordsCard(count = 1, callRunning = false))
        assertFalse("nothing to show", showsRecordsCard(count = 0, callRunning = false))
        assertFalse("not over a call", showsRecordsCard(count = 3, callRunning = true))
    }

    @Test
    fun theCardSaysHowManyThereAre() {
        assertEquals("電話の記録（12 件）", recordsCardText(12))
    }

    @Test
    fun backGoesToTheScreenItWasOpenedFrom() {
        assertEquals(Screen.INTAKE, historyOpenedFrom(current = Screen.INTAKE, remembered = Screen.SETTINGS))
        assertEquals(Screen.SETTINGS, historyOpenedFrom(current = Screen.SETTINGS, remembered = Screen.INTAKE))
    }

    @Test
    fun openingItAgainDoesNotForgetWhereItCameFrom() {
        assertEquals(Screen.INTAKE, historyOpenedFrom(current = Screen.HISTORY, remembered = Screen.INTAKE))
    }

    @Test
    fun theConfirmScreenCallsItByItsName() {
        val on = retentionNotice(keepsHistory = true)
        assertTrue("電話の記録" in on)
        assertFalse("控え" in on)
    }
}
