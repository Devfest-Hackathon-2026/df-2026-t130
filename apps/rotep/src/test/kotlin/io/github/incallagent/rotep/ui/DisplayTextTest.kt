// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.OutcomeCheck.Violation
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The words the screens show in place of the codes the code works with. */
class DisplayTextTest {
    @Test
    fun theCallScreenDoesNotListToolCallsOrTimings() {
        val lines = listOf(
            Line(Who.SHOP, "はい、〇〇です"),
            Line(Who.TOOL, "evaluate_offer：accept=true"),
            Line(Who.INFO, "AI が話し始めるまで 1.2 秒"),
            Line(Who.AI, "予約をお願いしたいのですが"),
            Line(Who.INFO, "プッシュ音：1"),
        )
        assertEquals(listOf(lines[0], lines[3], lines[4]), visibleCallLines(lines))
    }

    @Test
    fun theLineAboveApproveSaysWhoReceivesWhat() {
        assertEquals("電話の相手の声は Google に、相手の言葉の文字と依頼の内容は Anthropic に送られます", dataSentLine(practice = false, dualBrain = true))
        assertEquals("あなたの声は Google に、あなたの言葉の文字と依頼の内容は Anthropic に送られます", dataSentLine(practice = true, dualBrain = true))
        assertEquals("電話の相手の声と依頼の内容は Google に送られます", dataSentLine(practice = false, dualBrain = false))
        assertEquals("あなたの声と依頼の内容は Google に送られます", dataSentLine(practice = true, dualBrain = false))
    }

    @Test
    fun theLineUnderTheStateIsNotASecondSayingOfIt() {
        val talking = io.github.incallagent.rotep.call.CallSession.Snapshot(phase = io.github.incallagent.rotep.call.CallSession.Phase.TALKING, status = "AI が話しています")
        assertEquals(null, callStatusLine(talking))
        assertEquals("AI に接続しています", callStatusLine(talking.copy(status = "AI に接続しています")))
        val ended = talking.copy(phase = io.github.incallagent.rotep.call.CallSession.Phase.ENDED, status = "終わりました")
        assertEquals(null, callStatusLine(ended))
        assertEquals("電話が終わりました", phaseLabel(ended.phase))
    }

    @Test
    fun theStatesHaveNoTradeWords() {
        for (p in io.github.incallagent.rotep.call.CallSession.Phase.entries) {
            val text = phaseLabel(p)
            for (w in listOf("終話", "発信", "待機", "締めくくり")) assertFalse("$p: $text", text.contains(w))
        }
    }

    @Test
    fun theCheckSaysItInWordsOfTheScreen() {
        assertEquals("第一希望とは違う内容で予約されています", violationText(Violation("status", "第一希望ではないので BOOKED_ALTERNATIVE にしてください")))
        assertEquals("予約の電話ですが、予約できたかどうかの報告がありません", violationText(Violation("status", "予約の用件では DONE は使えません")))
        assertEquals("希望の日時の範囲外です：11月14日(土) 19:00", violationText(Violation("booking.start", "希望の日時の範囲外です：2026-11-14T19:00")))
        assertEquals("許可した人数の範囲外です：4名", violationText(Violation("booking.partySize", "許可した人数の範囲外です：4")))
        assertEquals("人数が確認されていません", violationText(Violation("booking.partySize", "人数が確認されていません")))
        for (m in listOf("第一希望ではないので BOOKED_ALTERNATIVE にしてください", "予約の用件では DONE は使えません")) {
            assertFalse(violationText(Violation("status", m)).contains(Regex("[A-Z]{4}")))
        }
    }
}
