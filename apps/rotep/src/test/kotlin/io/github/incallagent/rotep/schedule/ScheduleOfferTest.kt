// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import android.app.NotificationManager
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.app.RotepApp.Stage
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSession.EndReason
import io.github.incallagent.rotep.call.URGENT_MARK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScheduleOfferTest {
    private fun blocked(
        alreadyScheduled: Boolean = false,
        stage: Stage = Stage.L3,
        keepMicOn: Boolean = false,
        checkFailed: Boolean = false,
        blocker: String? = null,
        lookupHold: String? = null,
    ) = scheduleBlocked(alreadyScheduled, stage, keepMicOn, checkFailed, blocker, lookupHold)

    @Test
    fun itCanBeUsedWithL3AndTheHelper() {
        assertNull(blocked())
    }

    @Test
    fun theTimeAnotherAiAskedForIsShownAsItsWish() {
        assertNull(suggestionNote(null))
        val note = suggestionNote(ScheduleRules.instantOf(java.time.LocalDate.of(2026, 11, 10).atTime(11, 30)))
        assertTrue(note!!, note.contains("ほかの AI の希望"))
        assertTrue(note, note.contains("11月10日(火) 11:30"))
    }

    @Test
    fun onlyOneAtATime() {
        assertEquals(ScheduledCalls.ALREADY, blocked(alreadyScheduled = true))
    }

    @Test
    fun itSaysWhichWayOfCallingToChooseWhenItIsNotTheFullyAutomaticOne() {
        for (stage in listOf(Stage.L0, Stage.L2)) {
            val why = blocked(stage = stage)!!
            assertTrue(why, why.contains("「全自動で電話をかける」"))
            assertFalse(why, why.contains("L3"))
        }
    }

    @Test
    fun withTheMicrophoneKeptOnNoCallIsPutOffToATime() {
        // Nobody is at the phone: the room would go to the shop and to Gemini
        val why = blocked(keepMicOn = true)!!
        assertTrue(why, why.contains("端末のマイクを止めない"))
        assertTrue(why, why.contains("部屋の音"))
    }

    @Test
    fun whatStopsTheApprovalStopsThisToo() {
        assertTrue(blocked(checkFailed = true)!!.contains("内容の確認で問題"))
        assertEquals("手伝い役がいません", blocked(blocker = "手伝い役がいません"))
        assertEquals("調べたことを確かめて", blocked(lookupHold = "調べたことを確かめて"))
    }

    // --- the notification when the call is over ---

    private fun result(
        status: OutcomeStatus = OutcomeStatus.BOOKED,
        reason: EndReason = EndReason.AI_HUNG_UP,
        dialed: Boolean = true,
        detail: String = "-",
        pending: List<String> = emptyList(),
        leftOpen: Boolean = false,
    ) = CallSession.Result(
        outcome = CallOutcome(policyId = "p", status = status, pendingItems = pending, summary = "-", endedAt = Instant.EPOCH),
        violations = emptyList(),
        reportedByAi = true,
        endReason = reason,
        detail = detail,
        answered = dialed,
        talkMs = 1_000,
        dialed = dialed,
        leftOpen = leftOpen,
    )

    @Test
    fun aCallThatWentAsPlannedSaysHowItCameOut() {
        val notice = scheduledEndNotice(result())
        assertEquals("電話が終わりました", notice.title)
        assertEquals("予約できました（第一希望）", notice.text)
        assertFalse(notice.urgent)
    }

    @Test
    fun aScheduledCallNeedsTheNoticeBeforeItToBeAbleToShow() {
        assertTrue("no notice has made the channel yet", noticesCanShow(appEnabled = true, channelImportance = null))
        assertTrue(noticesCanShow(appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_HIGH))
        assertFalse("its channel was turned off", noticesCanShow(appEnabled = true, channelImportance = NotificationManager.IMPORTANCE_NONE))
        assertFalse("the app's notifications are off", noticesCanShow(appEnabled = false, channelImportance = null))
    }

    @Test
    fun aCallThatWasNeverPlacedSaysWhy() {
        val notice = scheduledEndNotice(result(dialed = false, reason = EndReason.CALL_FAILED, detail = "ほかの通話の途中なので、電話をかけません"))
        assertEquals("電話をかけませんでした", notice.title)
        assertEquals("ほかの通話の途中なので、電話をかけません", notice.text)
        assertTrue(notice.urgent)
    }

    @Test
    fun aCallTheAppLostSaysToLookAtThePhone() {
        val notice = scheduledEndNotice(result(reason = EndReason.CALL_FAILED, detail = "保留になりました。電話の画面で確かめて、切ってください"))
        assertTrue(notice.title.contains("切れていない"))
        assertTrue(notice.text.contains("電話の画面"))
        assertTrue(notice.urgent)
    }

    @Test
    fun aCallThatMayStillBeOpenSaysToLookAtThePhoneWhateverTheReason() {
        val notice = scheduledEndNotice(result(reason = EndReason.AI_CONNECTION_LOST, detail = "AI との接続が切れました。電話の画面で確かめて、切ってください", leftOpen = true))
        assertTrue(notice.title.contains("切れていない"))
        assertTrue(notice.text.contains("電話の画面"))
        assertTrue(notice.urgent)
    }

    @Test
    fun somethingUrgentComesFirst() {
        val notice = scheduledEndNotice(result(status = OutcomeStatus.NEEDS_USER_DECISION, pending = listOf("${URGENT_MARK}折り返してください")))
        assertEquals("至急の内容があります", notice.title)
        assertTrue(notice.urgent)
    }

    @Test
    fun aCallThatFailedTheOrdinaryWayIsNotUrgent() {
        val notice = scheduledEndNotice(result(status = OutcomeStatus.NOT_REACHED, reason = EndReason.NOT_ANSWERED))
        assertEquals("つながりませんでした", notice.text)
        assertFalse(notice.urgent)
    }
}
