// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.history

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.keepsInHistory
import io.github.incallagent.rotep.app.keptNumberOf
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.history.BookingHistoryStore.Read
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.ui.retentionNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class BookingEntriesTest {
    private val endedAt: Instant = Instant.parse("2026-11-09T02:10:00Z")
    private val policy: DelegationPolicy = PracticePolicy.approved(LocalDate.of(2026, 11, 9), endedAt)

    // Everything is fictitious
    private fun result(
        status: OutcomeStatus = OutcomeStatus.BOOKED,
        booking: ConfirmedBooking? = ConfirmedBooking(LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2, nameUsed = "テスト"),
        summary: String = "11月14日19時に2名で予約しました",
        violations: List<OutcomeCheck.Violation> = emptyList(),
        dialed: Boolean = true,
    ) = CallSession.Result(
        outcome = CallOutcome(policyId = "p", status = status, booking = booking, summary = summary, endedAt = endedAt),
        violations = violations,
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "AI が切りました",
        answered = true,
        talkMs = 65_000,
        purpose = policy.purpose,
        dialed = dialed,
    )

    private fun of(r: CallSession.Result = result(), lines: List<Line> = emptyList()) = BookingEntries.of("id-1", policy, r, lines)

    @Test
    fun theNumberForAFollowUpComesOnlyFromTheVeryCallThatWasKept() {
        val kept = of()
        assertEquals(kept.phoneNumber, keptNumberOf(kept, result()))
        assertNull("an earlier call to a shop of the same name", keptNumberOf(kept.copy(at = endedAt.minusSeconds(60)), result()))
        assertNull("that call was not kept", keptNumberOf(null, result()))
    }

    @Test
    fun whatTheCallWasIsKept() {
        val e = of()
        assertEquals("id-1", e.id)
        assertEquals(endedAt, e.at)
        assertEquals(policy.target.businessName, e.shopName)
        assertEquals(policy.target.phoneNumber, e.phoneNumber)
        assertEquals(policy.purpose, e.purpose)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 0), e.bookedAt)
        assertEquals(2, e.partySize)
        assertEquals("テスト", e.reservationName)
        assertEquals("11月14日19時に2名で予約しました", e.summary)
    }

    @Test
    fun theNumberKeyIsLeftToTheStore() {
        assertNull(of().numberKey)
    }

    @Test
    fun onlyWhatTheShopAndTheAiSaidIsKept() {
        val lines = listOf(
            Line(Who.SHOP, "はい"),
            Line(Who.TOOL, "report_outcome：booked"),
            Line(Who.INFO, "プッシュ音：1"),
            Line(Who.AI, "予約をお願いします"),
        )
        assertEquals(listOf(Line(Who.SHOP, "はい"), Line(Who.AI, "予約をお願いします")), of(lines = lines).transcript)
    }

    @Test
    fun aVeryLongConversationIsCutAtTheLimit() {
        val lines = List(100) { Line(if (it % 2 == 0) Who.SHOP else Who.AI, "あ".repeat(1_000)) }
        val kept = of(lines = lines).transcript
        assertEquals(BookingEntries.MAX_TRANSCRIPT_CHARS, kept.sumOf { it.text.length })
        assertEquals(lines.first(), kept.first())
    }

    @Test
    fun aVeryLongSummaryIsCut() {
        assertEquals(BookingEntries.MAX_SUMMARY_CHARS, of(result(summary = "あ".repeat(10_000))).summary.length)
    }

    @Test
    fun withoutABookingThereIsNoTimeOrName() {
        val e = of(result(status = OutcomeStatus.UNAVAILABLE, booking = null, summary = "満席でした"))
        assertNull(e.bookedAt)
        assertNull(e.partySize)
        assertNull(e.reservationName)
        assertEquals("予約できませんでした", e.headline)
    }

    @Test
    fun aBookingThatFailedTheCheckIsNotKeptAsBooked() {
        val e = of(result(violations = listOf(OutcomeCheck.Violation("start", "希望の範囲の外です"))))
        assertEquals("予約の返事はありましたが、確認が要ります", e.headline)
    }

    @Test
    fun theHeadlineIsTheOneTheResultScreenShows() {
        assertEquals("予約できました（第一希望）", of().headline)
    }

    @Test
    fun theCancelledOrAskedPurposeIsKept() {
        val cancel = policy.copy(purpose = Purpose.CANCEL)
        assertEquals(Purpose.CANCEL, BookingEntries.of("id", cancel, result(), emptyList()).purpose)
    }

    // --- which calls are kept ---

    @Test
    fun onlyACallPlacedWithTheSwitchOnAllAlongIsKept() {
        assertTrue(keepsInHistory(inCall = true, result(), onAtStart = true, onNow = true))
        assertFalse("a practice is not a call", keepsInHistory(inCall = false, result(), onAtStart = true, onNow = true))
        assertFalse("never dialed", keepsInHistory(inCall = true, result(dialed = false), onAtStart = true, onNow = true))
        assertFalse("off by default", keepsInHistory(inCall = true, result(), onAtStart = false, onNow = false))
        assertFalse("turned off during the call", keepsInHistory(inCall = true, result(), onAtStart = true, onNow = false))
        assertFalse("turned on during the call", keepsInHistory(inCall = true, result(), onAtStart = false, onNow = true))
    }

    // --- what the screens say ---

    @Test
    fun aPracticeIsNotToldThatSomethingStaysOnThePhone() {
        val text = retentionNotice(keepsHistory = false, practice = true)
        assertTrue(text, text.contains("練習の内容は、この端末に残しません"))
        assertFalse(text, text.contains("残します"))
        assertTrue("what is sent is still sent", text.contains("Google"))
        assertEquals(text, retentionNotice(keepsHistory = true, practice = true))
    }

    @Test
    fun theConfirmScreenSaysWhatStaysOnThePhone() {
        val off = retentionNotice(keepsHistory = false)
        assertTrue(off.contains("会話の文も保存しません"))

        val on = retentionNotice(keepsHistory = true)
        assertFalse("not with the switch on", on.contains("会話の文も保存しません"))
        for (said in listOf("電話の記録", "店名", "電話番号", "用件", "予約の内容", "結果と要約", "会話の文字", "暗号化", "90 日", "50 件", "音声は保存しません")) {
            assertTrue(said, on.contains(said))
        }
        assertTrue("sent to Gemini either way", off.contains("Google（Gemini）") && on.contains("Google（Gemini）"))
    }

    @Test
    fun theConfirmScreenSaysWhatGoesToClaude() {
        for (keepsHistory in listOf(true, false)) {
            val text = retentionNotice(keepsHistory = keepsHistory)
            for (said in listOf("Google（Gemini）", "相手の言葉の文字起こし", "予約名", "連絡先の番号", "Anthropic（Claude）", "規約に沿って一定期間記録されます")) {
                assertTrue(said, text.contains(said))
            }
        }
    }

    @Test
    fun inAPracticeTheWordsThatGoToClaudeAreYourOwn() {
        val text = retentionNotice(keepsHistory = false, practice = true)
        assertTrue(text, text.contains("話した言葉の文字起こし"))
        assertTrue(text, text.contains("Anthropic（Claude）"))
        assertFalse(text, text.contains("相手の言葉"))
    }

    @Test
    fun withClaudeSwitchedOffTheNoticeDoesNotNameAnthropic() {
        for (keepsHistory in listOf(true, false)) {
            val text = retentionNotice(keepsHistory = keepsHistory, dualBrain = false)
            assertTrue(text, text.contains("通話の音声と文字起こしは、AI の処理のために Google（Gemini）に送られます。"))
            assertFalse(text, text.contains("Anthropic"))
        }
    }

    @Test
    fun theEndOfACallWaitsForTheWrittenRecord() {
        val logs = ArrayList<String>()
        val work = FutureTask<Unit> { }
        work.run()
        waitFor(work, 1_000) { logs += it }
        assertTrue(logs.toString(), logs.isEmpty())
    }

    @Test
    fun butNotForEver() {
        val logs = ArrayList<String>()
        waitFor(FutureTask<Unit> { }, 1) { logs += it }
        assertEquals(listOf("not finished in time: TimeoutException"), logs)
    }

    @Test
    fun whatIsShownStaysWhileTheFileCannotBeRead() {
        val e = of()
        val shown = BookingHistory.State(listOf(e))
        val after = BookingHistory.stateAfter(Read.NotNow, shown)
        assertEquals(listOf(e), after.entries)
        assertEquals(BookingHistory.NOT_NOW, after.problem)
    }

    @Test
    fun theUserHearsThatUnreadableEntriesWereDeleted() {
        val after = BookingHistory.stateAfter(Read.Found(emptyList(), lost = true), BookingHistory.State())
        assertEquals(BookingHistory.LOST, after.problem)
    }

    @Test
    fun whatCouldNotBeReadIsSaidInItsOwnWords() {
        val part = BookingHistory.stateAfter(Read.Found(emptyList(), partly = true), BookingHistory.State())
        assertEquals(BookingHistory.PARTLY_LOST, part.problem)
        val all = BookingHistory.stateAfter(Read.Found(emptyList(), lost = true, partly = true), BookingHistory.State())
        assertEquals(BookingHistory.LOST, all.problem)
    }

    @Test
    fun aGoodReadClearsTheProblem() {
        val before = BookingHistory.State(problem = BookingHistory.NOT_NOW)
        assertNull(BookingHistory.stateAfter(Read.Found(emptyList()), before).problem)
    }
}
