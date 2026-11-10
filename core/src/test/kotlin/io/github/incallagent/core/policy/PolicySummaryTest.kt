// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicySummaryTest {
    @Test
    fun rendersTheExample() {
        val text = PolicySummary.render(Fixtures.example())
        val expected = listOf(
            "電話先：ビストロ・サンプル（03-0000-0000）",
            "用件：新しい予約",
            "人数：2名（2〜3名なら可）",
            "  1. 11月14日(土) 19:00",
            "  2. 11月14日(土) 18:30〜20:00 の間に開始",
            "  3. 11月15日(日) 18:00〜19:30 の間に開始",
            "条件：禁煙席（必須）、個室（できれば）",
            "予約名：ヤマダ",
            "  - 予算：1人 8,000円まで",
            "  - キャンセル料などの条件への同意：しない（折り返す）",
            "最初のあいさつ：「お忙しいところ失礼いたします。こちらはAIによる代理のお電話です。」",
            "通話の上限：5分",
        )
        for (line in expected) {
            assertTrue("missing line: $line\n---\n$text", text.lines().contains(line))
        }
    }

    /** docs/delegation-policy.md section 5 shows the full output; keep it identical. */
    @Test
    fun documentationMatchesOutput() {
        val doc = File("../docs/delegation-policy.md").readText().replace("\r\n", "\n")
        val block = Regex("```\n(電話先：.*?)```", RegexOption.DOT_MATCHES_ALL).find(doc)
            ?: throw AssertionError("summary example block not found in docs/delegation-policy.md")
        assertEquals(PolicySummary.render(Fixtures.example()), block.groupValues[1])
    }

    private fun policyFor(purpose: Purpose, authority: Authority = Authority()) =
        Fixtures.example().copy(purpose = purpose, authority = authority)

    /** The lines under "AIが決めてよいこと". */
    private fun authorityLines(p: DelegationPolicy) = PolicySummary.render(p).lines()
        .dropWhile { it != "AIが決めてよいこと：" }.drop(1)
        .takeWhile { it.startsWith("  - ") }

    @Test
    fun aCancellationAsksOnlyAboutTheFeeAndTheAnsweringMachine() {
        assertEquals(
            listOf(
                "  - キャンセル料がかかっても取り消す（金額の上限はありません）：しない（折り返す）",
                "  - 留守番電話への伝言：しない（折り返す）",
            ),
            authorityLines(policyFor(Purpose.CANCEL)),
        )
        assertEquals(
            listOf(
                "  - キャンセル料がかかっても取り消す（金額の上限はありません）：してよい",
                "  - 留守番電話への伝言：してよい",
            ),
            authorityLines(policyFor(Purpose.CANCEL, Authority(mayAcceptCancellationPolicy = true, mayLeaveVoicemail = true))),
        )
    }

    @Test
    fun anInquiryAsksOnlyAboutTheAnsweringMachine() {
        assertEquals(listOf("  - 留守番電話への伝言：しない（折り返す）"), authorityLines(policyFor(Purpose.INQUIRY)))
    }

    /** The model must not hold an authority the approval screen did not show. */
    @Test
    fun anAuthorityGivenAnywayIsShownForEveryPurpose() {
        val given = Authority(
            mayAcceptOutsideWindows = true,
            maxBudgetPerPersonYen = 5000,
            mayAcceptCancellationPolicy = true,
            mayAcceptPrepayment = true,
            mayJoinWaitlist = true,
            mayLeaveVoicemail = true,
        )
        assertEquals(
            listOf(
                "  - 範囲外の日時での予約：してよい",
                "  - キャンセル料がかかっても取り消す（金額の上限はありません）：してよい",
                "  - 前払い・予約金への同意：してよい",
                "  - キャンセル待ちへの登録：してよい",
                "  - 留守番電話への伝言：してよい",
            ),
            authorityLines(policyFor(Purpose.CANCEL, given)),
        )
        assertEquals(
            listOf(
                "  - 範囲外の日時での予約：してよい",
                "  - 予算：1人 5,000円まで",
                "  - キャンセル料などの条件への同意：してよい",
                "  - 前払い・予約金への同意：してよい",
                "  - キャンセル待ちへの登録：してよい",
                "  - 留守番電話への伝言：してよい",
            ),
            authorityLines(policyFor(Purpose.INQUIRY, given)),
        )
    }

    /** Nothing applies a budget to a cancellation, so it must not read as a limit on the fee. */
    @Test
    fun aCancellationDoesNotShowABudgetEvenWhenOneIsSet() {
        assertEquals(
            listOf(
                "  - キャンセル料がかかっても取り消す（金額の上限はありません）：しない（折り返す）",
                "  - 留守番電話への伝言：しない（折り返す）",
            ),
            authorityLines(policyFor(Purpose.CANCEL, Authority(maxBudgetPerPersonYen = 5000))),
        )
    }

    @Test
    fun aCancellationAndAnInquiryStillShowTheNameAndTheNumberToGive() {
        for (purpose in listOf(Purpose.CANCEL, Purpose.INQUIRY)) {
            val lines = PolicySummary.render(policyFor(purpose)).lines()
            assertTrue(lines.toString(), lines.contains("予約名：ヤマダ"))
            assertTrue(lines.toString(), lines.contains("連絡先として伝える番号：090-0000-0000"))
        }
    }

    @Test
    fun theNotesAreNamedAfterThePurpose() {
        val labels = mapOf(
            Purpose.NEW_BOOKING to "お店に伝えること",
            Purpose.CHANGE to "もとの予約と、変えたいこと",
            Purpose.CANCEL to "取り消す予約",
            Purpose.INQUIRY to "聞きたいこと",
        )
        for ((purpose, label) in labels) {
            val lines = PolicySummary.render(policyFor(purpose)).lines()
            assertTrue(lines.toString(), lines.contains("$label：誕生日のお祝いです"))
        }
    }

    @Test
    fun aChangeReadsLikeANewBookingApartFromThePurposeAndTheNotes() {
        val booking = PolicySummary.render(Fixtures.example()).lines()
        val change = PolicySummary.render(Fixtures.example().copy(purpose = Purpose.CHANGE)).lines()
        assertEquals(booking.size, change.size)
        assertEquals(
            listOf(
                "用件：新しい予約" to "用件：予約の変更",
                "お店に伝えること：誕生日のお祝いです" to "もとの予約と、変えたいこと：誕生日のお祝いです",
            ),
            booking.zip(change).filter { (a, b) -> a != b },
        )
    }
}
