// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.AudioChoice
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.app.RotepApp.Stage
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.CallPanel
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.call.CallSession.Snapshot
import io.github.incallagent.rotep.request.PhoneRequestStore
import io.github.incallagent.rotep.ui.component.AitelMood
import io.github.incallagent.rotep.ui.preview.PreviewFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStatesTest {
    @Test
    fun aMadeUpNumberIsNotPresentedAsTheUsers() {
        val note = chatNote("飲食店", placeholderNumber = true)
        assertTrue(note.contains("練習用の架空の番号"))
        assertFalse(note.contains("店名、電話番号"))
        assertTrue(chatNote("飲食店").contains("店名、電話番号"))
    }

    @Test
    fun readingsHaveANoteOfTheirOwn() {
        assertEquals("電話で言う読み：山田（やまだ）", readingsNote(listOf(io.github.incallagent.rotep.call.SpokenReading("山田", "やまだ"))))
        assertEquals(null, readingsNote(emptyList()))
    }

    @Test
    fun theChatNoteShowsHowNamesWillBeSaid() {
        val note = chatNote("飲食店", listOf(io.github.incallagent.rotep.call.SpokenReading("山田", "やまだ")))
        assertTrue(note.contains("電話で言う読み：山田（やまだ）"))
        assertFalse(chatNote("飲食店").contains("電話で言う読み"))
    }

    // --- 通話中 ---

    @Test
    fun elapsedIsTheTalkTimeOnceTheCallEnded() {
        assertEquals(95_000L, callElapsedMs(Snapshot(phase = Phase.ENDED, result = PreviewFixtures.resultBooked), liveMs = 123_456))
        assertEquals(123_456L, callElapsedMs(Snapshot(phase = Phase.TALKING), liveMs = 123_456))
        // Ended without a result yet: no time rather than a running clock
        assertNull(callElapsedMs(Snapshot(phase = Phase.ENDED), liveMs = 5))
    }

    @Test
    fun theLimitShowsOnlyOnceTheOtherSideAnswered() {
        // CallSession starts the limit's clock at the answer
        val ringing = callClock(Snapshot(phase = Phase.RINGING), elapsedMs = 9_000, limitSeconds = 300)
        assertFalse(ringing.answered)
        assertNull(ringing.limitSeconds)
        assertEquals("呼び出し 0:09", clockText(ringing))

        val talking = callClock(Snapshot(phase = Phase.TALKING, answeredAt = 1), elapsedMs = 72_000, limitSeconds = 300)
        assertEquals("会話 1:12（上限 5分）", clockText(talking))
        assertEquals("会話 1:12", clockText(talking.copy(limitSeconds = null)))
    }

    @Test
    fun theAiCanBeStoppedOnlyWhileItMayTalk() {
        val on = Phase.entries.filter { call(it).aiOn }.toSet()
        assertEquals(setOf(Phase.DIALING, Phase.RINGING, Phase.TALKING, Phase.CLOSING), on)
    }

    @Test
    fun theManualButtonsBelongToL2UntilTheEnd() {
        assertTrue(call(Phase.RINGING, Stage.L2).canAnswer)
        assertFalse(call(Phase.TALKING, Stage.L2).canAnswer)
        assertFalse(call(Phase.RINGING, Stage.L0).canAnswer)
        assertTrue(call(Phase.TALKING, Stage.L2).canHangUp)
        assertTrue(call(Phase.HANDED_OVER, Stage.L2).canHangUp)
        assertFalse(call(Phase.IDLE, Stage.L2).canHangUp)
        assertTrue(call(Phase.HANDED_OVER, Stage.L2).showManualButtons)
        assertFalse(call(Phase.ENDED, Stage.L2).showManualButtons)
        assertFalse(call(Phase.TALKING, Stage.L0).showManualButtons)
    }

    @Test
    fun theUserIsToldWhenNotificationsAreOff() {
        val policy = PreviewFixtures.policy
        val said = "通知が許可されていません"
        assertTrue(confirmWarnings(policy, notificationsOff = true).any { said in it })
        assertFalse("a practice has no call to be told about", confirmWarnings(policy, practice = true, notificationsOff = true).any { said in it })
        assertFalse(confirmWarnings(policy).any { said in it })
    }

    private fun cuesShown(phase: Phase, aiReady: Boolean = true, hangingUp: Boolean = false, hangUpAsked: Boolean = false, offered: Boolean = true) =
        CallUiState(Snapshot(phase = phase, aiReady = aiReady, hangingUp = hangingUp), Stage.L3, warning = null, instruction = null, elapsedMs = null, hangUpAsked = hangUpAsked, userCuesOffered = offered).showUserCues

    @Test
    fun theUsersInstructionsAreThereOnlyWhileTheAiHasTheCall() {
        assertTrue(cuesShown(Phase.TALKING))
        for (phase in Phase.entries - Phase.TALKING) assertFalse(phase.name, cuesShown(phase))
        assertFalse("not connected yet", cuesShown(Phase.TALKING, aiReady = false))
    }

    @Test
    fun theUsersInstructionsGoOnceTheCallIsBeingHungUp() {
        assertFalse(cuesShown(Phase.TALKING, hangingUp = true))
        assertFalse("電話を切る was pressed", cuesShown(Phase.TALKING, hangUpAsked = true))
    }

    @Test
    fun theSwitchTakesTheUsersInstructionsAway() {
        assertFalse(cuesShown(Phase.TALKING, offered = false))
    }

    private fun l2(phase: Phase, panel: CallPanel = CallPanel(), elapsedMs: Long? = null) =
        CallUiState(Snapshot(phase = phase), Stage.L2, warning = null, instruction = null, elapsedMs = elapsedMs, panel = panel)

    @Test
    fun theAnswerButtonIsOnlyAFallback() {
        val watched = CallPanel(watchesCall = true)
        assertFalse(l2(Phase.RINGING, watched, elapsedMs = 9_000).showAnswerButton)
        assertTrue("rung for long without an answer", l2(Phase.RINGING, watched, elapsedMs = ANSWER_FALLBACK_MS).showAnswerButton)
        assertTrue("the app cannot see the answer", l2(Phase.RINGING, CallPanel(), elapsedMs = 1_000).showAnswerButton)
        assertFalse(l2(Phase.TALKING, watched, elapsedMs = 60_000).showAnswerButton)
        assertFalse(call(Phase.RINGING, Stage.L3).showAnswerButton)
    }

    @Test
    fun theHungUpButtonIsOnlyForACallTheAppCannotSee() {
        assertFalse(l2(Phase.TALKING, CallPanel(watchesCall = true)).showHungUpButton)
        assertTrue(l2(Phase.TALKING, CallPanel(watchesCall = false)).showHungUpButton)
        assertFalse(l2(Phase.ENDED).showHungUpButton)
        // With neither button there is no row
        assertFalse(l2(Phase.TALKING, CallPanel(watchesCall = true)).showManualButtons)
    }

    @Test
    fun thePanelBelongsToARealCallThatIsGoingOn() {
        val panel = CallPanel(hangUpAvailable = true)
        assertTrue(l2(Phase.TALKING, panel).showPanel)
        assertFalse("nothing to show", l2(Phase.TALKING, CallPanel()).showPanel)
        assertFalse(l2(Phase.ENDED, panel).showPanel)
        assertFalse(call(Phase.TALKING, Stage.L0).copy(panel = panel).showPanel)
        assertTrue(PreviewFixtures.callL3Talking.showPanel)
    }

    @Test
    fun aVoiceThatGoesOutThroughTheMicrophoneLocksItsButtonsOnlyWhileTheAiTalks() {
        val talking = l2(Phase.TALKING, CallPanel(micAvailable = true, speakerAvailable = true)).copy(aiNeedsMic = true)
        assertTrue(talking.panelLocked)
        assertTrue("${talking.panelNote}", talking.panelNote!!.contains("変えられません"))
        val after = l2(Phase.HANDED_OVER, CallPanel(micAvailable = true, speakerAvailable = true)).copy(aiNeedsMic = true)
        assertFalse(after.panelLocked)
        assertNull(after.panelNote)
        assertFalse(l2(Phase.TALKING).panelLocked)
    }

    @Test
    fun aQuietCallSaysWhyTheSpeakerIsSilent() {
        val quiet = l2(Phase.TALKING, CallPanel(speakerAvailable = true, speakerOn = true)).copy(quietWhileAi = true)
        assertTrue("${quiet.panelNote}", quiet.panelNote!!.contains("音を消して"))
        assertNull(l2(Phase.TALKING, CallPanel(speakerAvailable = true, speakerOn = false)).copy(quietWhileAi = true).panelNote)
        assertNull(l2(Phase.HANDED_OVER, CallPanel(speakerAvailable = true, speakerOn = true)).copy(quietWhileAi = true).panelNote)
    }

    @Test
    fun theResultButtonTakesThePlaceOfTheStopOnceThereIsAResult() {
        assertFalse(call(Phase.TALKING).showResultButton)
        assertTrue(PreviewFixtures.callEnded.showResultButton)
    }

    @Test
    fun onlyL0IsWithoutACall() {
        assertFalse(call(Phase.TALKING, Stage.L0).inCall)
        assertTrue(call(Phase.TALKING, Stage.L2).inCall)
    }

    // --- 確認と承認 ---

    @Test
    fun approvalNeedsNoProblemNothingInTheWayAndNoStartAlready() {
        assertTrue(PreviewFixtures.confirmL0.canApprove)
        assertFalse(PreviewFixtures.confirmProblems.canApprove)
        assertFalse(PreviewFixtures.confirmNoKey.canApprove)
        assertFalse(PreviewFixtures.confirmL2Practice.canApprove)
        assertFalse(PreviewFixtures.confirmStarting.canApprove)
    }

    @Test
    fun theReasonNextToTheButtonSaysWhyItCannotBePressed() {
        assertNull(PreviewFixtures.confirmL0.disabledReason)
        assertEquals("内容の確認で問題があります（上の一覧を直してください）", PreviewFixtures.confirmProblems.disabledReason)
        assertEquals(PreviewFixtures.confirmNoKey.blocker, PreviewFixtures.confirmNoKey.disabledReason)
        assertEquals("始めています", PreviewFixtures.confirmStarting.disabledReason)
    }

    @Test
    fun theApproveButtonNamesTheShopAndWhetherACallIsMade() {
        val shop = PreviewFixtures.policy.target.businessName
        assertEquals("承認して電話する。${shop}に電話します", approveDescription(PreviewFixtures.confirmL2))
        val practice = approveDescription(PreviewFixtures.confirmL0)
        assertTrue(practice, practice.startsWith("承認して練習を始める") && practice.contains(shop) && practice.contains("電話はかけません"))
    }

    @Test
    fun thereIsNothingToStopOnceTheHangUpIsAskedFor() {
        assertTrue(call(Phase.CLOSING).aiOn)
        val hangingUp = call(Phase.CLOSING).copy(snapshot = Snapshot(phase = Phase.CLOSING, hangingUp = true))
        assertFalse(hangingUp.aiOn)
    }

    @Test
    fun theStagesOfTodayHaveANoteWithoutTheWordMute() {
        assertNotNull(stageNote(Stage.L0))
        val l2 = stageNote(Stage.L2)
        assertNotNull(l2)
        assertFalse(l2, l2!!.contains("ミュート"))
        assertTrue(l2, l2.contains("端末のマイクを止める"))
    }

    @Test
    fun theCallNotesDoNotPromiseThatTheMicrophoneIsAlwaysStopped() {
        // The switch 「端末のマイクを止めない」 can keep it on
        val l2 = stageNote(Stage.L2)!!
        assertFalse(l2, l2.contains("端末のマイクを止める・"))
        assertTrue(l2, l2.contains("画面に案内が出たときだけ"))
        val l3 = stageNote(Stage.L3)
        assertNotNull(l3)
        assertFalse(l3, l3!!.contains("ミュート"))
        assertFalse(l3, l3.contains("端末のマイクを止める・"))
        assertTrue(l3, l3.contains("端末のマイクの操作"))
    }

    @Test
    fun theNotesOfEveryWayOfCallingAreInEverydayWords() {
        for (stage in Stage.entries) {
            val text = stage.label + stageNote(stage).orEmpty()
            assertFalse(text, Regex("L[0-3]|A3|direct|redirection|音声の通り道").containsMatchIn(text))
        }
    }

    @Test
    fun aHoldOrASecondCallIsTheSameInBothWaysOfCalling() {
        for (stage in listOf(Stage.L2, Stage.L3)) assertTrue(stageNote(stage)!!, stageNote(stage)!!.contains("保留やほかの通話"))
    }

    @Test
    fun theMicrophoneNoteOnlyAppearsOnTheConfirmScreenWhenThereIsOne() {
        val note = Settings(audio = AudioChoice.SHELL_DIRECT, keepMicOn = true).micNote
        assertNotNull(note)
        assertEquals(note, PreviewFixtures.confirmL2.copy(micNote = note).micNote)
        assertNull(PreviewFixtures.confirmL2.micNote)
    }

    // --- 確認の画面の注意 ---

    private val booking = PreviewFixtures.policy

    @Test
    fun aNavigationDialNumberIsPointedOutButAllowed() {
        val navi = booking.copy(target = booking.target.copy(phoneNumber = "0570-000-000"))
        assertTrue(confirmWarnings(navi).any { "0570" in it && "通話料" in it })
        assertFalse(confirmWarnings(booking).any { "0570" in it })
    }

    @Test
    fun aRequestFromAnotherAiNamesWhatThatAiWrote() {
        val note = otherAiNote("飲食店", null)
        for (item in listOf("店名", "電話番号", "連絡先", "予約名", "お店に伝えること")) assertTrue(item, note.contains(item))
        assertTrue(note.contains("結果はその AI に返します"))
        assertFalse(note.contains("出どころ"))
        assertTrue(note.contains("電話先の種類：飲食店"))
        val sourced = otherAiNote("飲食店", "https://example.com")
        assertTrue(sourced.contains("\n\nその AI の申告（アプリは確かめていません）\n番号の出どころ：https://example.com"))
    }

    @Test
    fun aRequestSaysWhichAiItCameFrom() {
        assertTrue(otherAiNote("飲食店", null, "grok").startsWith("Grok から届いた依頼です"))
        assertTrue(otherAiNote("飲食店", null, "muse").startsWith("Muse から届いた依頼です"))
        val incoming = PhoneRequestStore.Incoming("id", "店", Purpose.NEW_BOOKING, null, opened = false, assistant = "muse")
        assertEquals("Muse から電話の依頼が届きました", incomingTitle(incoming))
        assertEquals("Muse からの依頼を開いています", incomingTitle(incoming.copy(opened = true)))
    }

    @Test
    fun aRequestFromAnAiNotKnownStaysAnotherAi() {
        for (assistant in listOf(null, "something else")) {
            assertTrue(otherAiNote("飲食店", null, assistant).startsWith("ほかの AI から届いた依頼です"))
            val incoming = PhoneRequestStore.Incoming("id", "店", Purpose.NEW_BOOKING, null, opened = false, assistant = assistant)
            assertEquals("ほかの AI から電話の依頼が届きました", incomingTitle(incoming))
        }
    }

    @Test
    fun aRequestThatCannotBeApprovedAnyMoreStopsTheButton() {
        val state = ConfirmUiState(booking, problems = emptyList(), blocker = "取り下げられました", stage = Stage.L2)
        assertFalse(state.canApprove)
    }

    @Test
    fun anEmptyContactNumberIsPointedOutForABookingAndACancel() {
        val empty = booking.copy(disclosure = booking.disclosure.copy(callbackNumber = null))
        assertTrue(confirmWarnings(empty).any { it.contains("ほぼ必ず聞きます") })
        assertTrue(confirmWarnings(empty.copy(purpose = Purpose.CANCEL)).any { it.contains("予約を探すのに") })
        assertFalse(confirmWarnings(empty.copy(purpose = Purpose.INQUIRY)).any { it.contains("連絡先の電話番号") })
    }

    @Test
    fun aCancelInThePracticeIsNotToldItCannotBeUndone() {
        assertFalse(confirmWarnings(booking.copy(purpose = Purpose.CANCEL), practice = true).any { it.contains("元に戻せない") })
    }

    @Test
    fun aCancelThatMayPayAnyFeeSaysSo() {
        val cancel = booking.copy(purpose = Purpose.CANCEL, authority = booking.authority.copy(mayAcceptCancellationPolicy = true))
        assertTrue(confirmWarnings(cancel).any { it.contains("金額の上限はありません") })
    }

    @Test
    fun aClinicIsToldThatTheAiDoesNotTakeMedicalAdvice() {
        val clinic = booking.copy(target = booking.target.copy(kind = BusinessKind.CLINIC))
        assertTrue(confirmWarnings(clinic).any { it.contains("医療の判断") })
    }

    @Test
    fun aCancelIsToldToCheckWhatItCancels() {
        assertTrue(confirmWarnings(booking.copy(purpose = Purpose.CANCEL)).any { it.contains("元に戻せない") })
    }

    // --- 用件ごとの見出し ---

    private fun outcome(status: OutcomeStatus, purpose: Purpose) = PreviewFixtures.resultNotReached.let { r ->
        r.copy(outcome = r.outcome.copy(status = status), violations = emptyList(), purpose = purpose)
    }

    @Test
    fun aCancelThatWentThroughSaysSoAndDoesNotCelebrate() {
        val h = resultHeadline(outcome(OutcomeStatus.DONE, Purpose.CANCEL))
        assertEquals(ResultHeadline.CANCELLED, h)
        assertEquals("予約を取り消せました", headlineText(h))
        assertTrue(resultIsGood(h))
        assertFalse(resultCelebrates(h))
    }

    @Test
    fun anInquiryThatGotAnAnswerSaysSo() {
        assertEquals(ResultHeadline.ANSWERED, resultHeadline(outcome(OutcomeStatus.DONE, Purpose.INQUIRY)))
    }

    @Test
    fun aCallTheUserStoppedSaysSoWhateverTheErrandWas() {
        for (purpose in Purpose.entries) {
            for (reason in listOf(CallSession.EndReason.USER_TOOK_OVER, CallSession.EndReason.USER_HUNG_UP)) {
                val stopped = outcome(OutcomeStatus.NEEDS_USER_DECISION, purpose).copy(reportedByAi = false, endReason = reason)
                assertEquals("$purpose $reason", ResultHeadline.STOPPED_BY_USER, resultHeadline(stopped))
            }
        }
        assertEquals("途中で止めました", headlineText(ResultHeadline.STOPPED_BY_USER))
        assertFalse(resultIsGood(ResultHeadline.STOPPED_BY_USER))
    }

    @Test
    fun whatTheAiReportedBeforeTheUserStoppedStillStands() {
        val r = outcome(OutcomeStatus.BOOKED, Purpose.NEW_BOOKING).copy(reportedByAi = true, endReason = CallSession.EndReason.USER_TOOK_OVER)
        assertEquals(ResultHeadline.BOOKED_FIRST, resultHeadline(r))
    }

    @Test
    fun theKeyThatStopsACallIsNamed() {
        assertEquals(MissingKey.GEMINI, missingKey(Settings(hasApiKey = false, hasClaudeKey = true)))
        assertEquals("Gemini first: nothing works without it", MissingKey.GEMINI, missingKey(Settings(hasApiKey = false, hasClaudeKey = false)))
        assertEquals(MissingKey.CLAUDE, missingKey(Settings(hasApiKey = true, hasClaudeKey = false, dualBrain = true)))
        assertNull("with the switch off, Gemini is all it takes", missingKey(Settings(hasApiKey = true, hasClaudeKey = false, dualBrain = false)))
        assertNull(missingKey(Settings(hasApiKey = true, hasClaudeKey = true, dualBrain = true)))
    }

    @Test
    fun withoutAClaudeKeyTheScreenSaysThatGeminiAloneCanDoIt() {
        val note = missingKeyNote(MissingKey.CLAUDE)!!
        assertTrue(note, "「通話の考える役に Claude を使う」" in note)
        assertTrue(note, "Gemini だけ" in note)
        assertNull("nothing goes on without Gemini", missingKeyNote(MissingKey.GEMINI))
    }

    private fun failed(reason: CallSession.EndReason, answered: Boolean = true, detail: String = "終わり方") =
        outcome(OutcomeStatus.FAILED, Purpose.NEW_BOOKING).copy(reportedByAi = false, endReason = reason, answered = answered, detail = detail)

    @Test
    fun aCallThatDidNotConnectSaysToCheckTheNumberAndTryAgain() {
        val step = resultNextStep(outcome(OutcomeStatus.NOT_REACHED, Purpose.NEW_BOOKING))!!
        assertTrue(step, "番号" in step && "かけ直して" in step && "「続き」" in step)
    }

    @Test
    fun aCallCutOffOrNotReportedSaysToAskTheShop() {
        for (reason in listOf(
            CallSession.EndReason.REMOTE_HUNG_UP,
            CallSession.EndReason.AI_CONNECTION_LOST,
            CallSession.EndReason.AUDIO_STOPPED,
            CallSession.EndReason.TIMEOUT,
            CallSession.EndReason.NO_AI_DISCLOSURE,
        )) {
            val step = resultNextStep(failed(reason))!!
            assertTrue("$reason: $step", "お店に確かめて" in step)
        }
    }

    @Test
    fun aCallThatNeverReachedAnyoneDoesNotSendTheUserToTheShop() {
        val step = resultNextStep(failed(CallSession.EndReason.AI_CONNECTION_LOST, answered = false))!!
        assertTrue(step, "まだ話していません" in step)
    }

    @Test
    fun anEndThatWasAboutAKeyAlreadySaysWhatToDo() {
        val keys = listOf(
            UserMessages.aiConnection(UserMessages.LiveEnd.BAD_KEY, connected = false),
            UserMessages.aiConnection(UserMessages.LiveEnd.BRAIN, connected = true),
            UserMessages.aiConnection(UserMessages.LiveEnd.BILLING, connected = true),
        )
        for (detail in keys) assertNull(detail, resultNextStep(failed(CallSession.EndReason.AI_CONNECTION_LOST, detail = detail)))
    }

    @Test
    fun whatIsAlreadySaidOrDoneNeedsNoNextStep() {
        val stopped = outcome(OutcomeStatus.NEEDS_USER_DECISION, Purpose.NEW_BOOKING).copy(reportedByAi = false, endReason = CallSession.EndReason.USER_TOOK_OVER)
        assertNull("the item to look into says it", resultNextStep(stopped))
        assertNull(resultNextStep(PreviewFixtures.resultBooked))
        assertNull("the AI's own report says it", resultNextStep(failed(CallSession.EndReason.REMOTE_HUNG_UP).copy(reportedByAi = true)))
        assertNull("nothing was dialed", resultNextStep(failed(CallSession.EndReason.CALL_FAILED).copy(dialed = false)))
        assertNull("a practice has no shop", resultNextStep(failed(CallSession.EndReason.REMOTE_HUNG_UP), practice = true))
    }

    @Test
    fun aCallRightAwayAtNightGetsAWordOfCautionAndIsNotStopped() {
        val policy = PreviewFixtures.policy
        val said = "お店が閉まっている時間かもしれません"
        assertTrue(confirmWarnings(policy, lateAtNight = true).any { said in it })
        assertFalse("a practice calls no shop", confirmWarnings(policy, practice = true, lateAtNight = true).any { said in it })
        assertFalse(confirmWarnings(policy).any { said in it })
    }

    @Test
    fun theNightIsTheHoursInWhichNoCallCanBeScheduled() {
        fun at(hour: Int, minute: Int) =
            io.github.incallagent.rotep.schedule.ScheduleRules.instantOf(java.time.LocalDate.of(2026, 11, 14).atTime(hour, minute))
        assertTrue(lateAtNight(at(21, 0)))
        assertTrue(lateAtNight(at(3, 0)))
        assertTrue(lateAtNight(at(7, 59)))
        assertFalse(lateAtNight(at(8, 0)))
        assertFalse(lateAtNight(at(20, 59)))
    }

    @Test
    fun doneWithAProblemIsNotShownAsDone() {
        val r = outcome(OutcomeStatus.DONE, Purpose.CANCEL).copy(violations = listOf(OutcomeCheck.Violation("status", "x")))
        assertEquals(ResultHeadline.FAILED, resultHeadline(r))
    }

    @Test
    fun aCancelHandedBackSaysItIsNotDoneYet() {
        val h = resultHeadline(outcome(OutcomeStatus.NEEDS_USER_DECISION, Purpose.CANCEL))
        assertEquals(ResultHeadline.NOT_CANCELLED_YET, h)
        assertTrue(headlineText(h).contains("まだ取り消していません"))
        assertEquals(ResultHeadline.NEEDS_DECISION, resultHeadline(outcome(OutcomeStatus.NEEDS_USER_DECISION, Purpose.NEW_BOOKING)))
    }

    @Test
    fun theHeroOfACancelSaysWhatIsApproved() {
        val state = PreviewFixtures.confirmL2.copy(policy = PreviewFixtures.confirmL2.policy.copy(purpose = Purpose.CANCEL))
        assertEquals("取り消していい？", confirmHero(state).second)
        assertTrue(approveDescription(state).contains("取り消す"))
    }

    @Test
    fun aFailedCancelDoesNotTalkAboutFreeSeats() {
        val h = resultHeadline(outcome(OutcomeStatus.UNAVAILABLE, Purpose.CANCEL))
        assertEquals(ResultHeadline.NOT_CANCELLED, h)
        assertFalse(headlineText(h).contains("空き"))
        assertFalse(resultHero(h).first.contains("空き"))
    }

    @Test
    fun noBookingNoFreeSeatsClaim() {
        // The shop may be closed, or take bookings only on the web
        val h = resultHeadline(outcome(OutcomeStatus.UNAVAILABLE, Purpose.NEW_BOOKING))
        assertEquals("予約できませんでした", headlineText(h))
    }

    @Test
    fun theLineUnderTheHeroSaysMoreThanTheHero() {
        assertTrue(headlineAddsToHero(ResultHeadline.BOOKED_FIRST))
        assertTrue(headlineAddsToHero(ResultHeadline.BOOKED_NEEDS_REVIEW))
        assertFalse(headlineAddsToHero(ResultHeadline.NOT_REACHED))
        assertFalse(headlineAddsToHero(ResultHeadline.FAILED))
    }

    @Test
    fun aChangeReadsAsAChange() {
        assertEquals(ResultHeadline.CHANGED, resultHeadline(outcome(OutcomeStatus.BOOKED, Purpose.CHANGE)))
        assertEquals(ResultHeadline.CHANGED_OTHER, resultHeadline(outcome(OutcomeStatus.BOOKED_ALTERNATIVE, Purpose.CHANGE)))
        assertEquals(ResultHeadline.NOT_CHANGED, resultHeadline(outcome(OutcomeStatus.UNAVAILABLE, Purpose.CHANGE)))
        assertTrue(resultCelebrates(ResultHeadline.CHANGED))
    }

    // --- 結果 ---

    @Test
    fun bookedIsSaidOnlyWhenTheCheckFoundNothing() {
        assertEquals(ResultHeadline.BOOKED_FIRST, resultHeadline(PreviewFixtures.resultBooked))
        assertEquals(ResultHeadline.BOOKED_NEEDS_REVIEW, resultHeadline(PreviewFixtures.resultBookedOutside))
        val alternativeOutside = PreviewFixtures.resultBookedOutside.let {
            it.copy(outcome = it.outcome.copy(status = OutcomeStatus.BOOKED_ALTERNATIVE))
        }
        assertEquals(ResultHeadline.BOOKED_NEEDS_REVIEW, resultHeadline(alternativeOutside))
        val alternativeFine = PreviewFixtures.resultBooked.let {
            it.copy(outcome = it.outcome.copy(status = OutcomeStatus.BOOKED_ALTERNATIVE))
        }
        assertEquals(ResultHeadline.BOOKED_OTHER, resultHeadline(alternativeFine))
        assertEquals(ResultHeadline.NEEDS_DECISION, resultHeadline(PreviewFixtures.resultNeedsDecision))
        assertEquals(ResultHeadline.NOT_REACHED, resultHeadline(PreviewFixtures.resultNotReached))
        assertEquals(ResultHeadline.FAILED, resultHeadline(PreviewFixtures.resultFailed))
        assertFalse(headlineText(ResultHeadline.BOOKED_NEEDS_REVIEW).contains("予約できました"))
    }

    // --- 通話なし（練習）の帯 ---

    // --- 依頼 ---

    @Test
    fun eachAuthoritySaysWhatTheAiDoesNow() {
        assertEquals("いま：しない（確認して折り返す）", authorityStateText(false))
        assertEquals("いま：する", authorityStateText(true))
    }

    @Test
    fun thePartyButtonsRewriteTheFieldWithinOneToFifty() {
        assertEquals("3", partyStep("2", fallback = "", delta = +1))
        assertEquals("1", partyStep("2", fallback = "", delta = -1))
        assertNull(partyStep("1", fallback = "", delta = -1))
        assertNull(partyStep("50", fallback = "", delta = +1))
        // Read as RequestForm reads it: full-width digits and "名"; written back in ASCII
        assertEquals("3", partyStep("２", fallback = "", delta = +1))
        assertEquals("5", partyStep("4名", fallback = "", delta = +1))
        // An empty minimum or maximum starts from the preferred size
        assertEquals("3", partyStep("", fallback = "2", delta = +1))
        assertNull(partyStep("", fallback = "", delta = +1))
        assertNull(partyStep("たくさん", fallback = "2", delta = +1))
    }

    // --- 確認と承認：委任の中身 ---

    @Test
    fun thePolicyInWordsSplitsIntoItemsWithoutLosingALine() {
        val text = io.github.incallagent.core.policy.PolicySummary.render(PreviewFixtures.policy)
        val items = summaryItems(text)
        assertEquals("電話先", items.first().label)
        assertEquals("通話の上限", items.last().label)
        val windows = items.single { it.label == "日時（上から順に聞く）" }
        assertEquals(3, windows.lines.size)
        val authority = items.single { it.label == "AIが決めてよいこと" }
        assertTrue(authority.lines.all { it.startsWith("・") })
        // Every line of the text is somewhere in the items
        val allLines = items.flatMap { it.lines }
        for (line in text.lines().filter { it.isNotBlank() }) {
            if (line.first().isWhitespace()) {
                val trimmed = line.trim().removePrefix("- ")
                assertTrue(line, allLines.any { it.endsWith(trimmed) })
            } else {
                val label = line.substringBefore('：')
                val value = line.substringAfter('：')
                val item = items.first { it.label == label }
                assertTrue(line, value.isBlank() || value in item.lines)
            }
        }
    }

    @Test
    fun aLineWithoutALabelStaysAsItIs() {
        assertEquals(listOf(SummaryItem("", listOf("ラベルのない行"))), summaryItems("ラベルのない行\n"))
    }

    // --- AITEL の帯と顔 ---

    @Test
    fun thePracticeHeroesNeverSayACallIsMade() {
        assertFalse(words(requestHero(practice = true)).contains("電話"))
        assertTrue(words(requestHero(practice = false)).contains("電話"))
        assertFalse(words(confirmHero(PreviewFixtures.confirmL0)).contains("電話"))
        val shop = PreviewFixtures.policy.target.businessName
        assertEquals(HeroText("${shop}に", "電話していい？", AitelMood.ASKING), confirmHero(PreviewFixtures.confirmL2))
    }

    @Test
    fun onlyAJobDoneWithNothingToCheckIsBrightAndHappy() {
        val good = ResultHeadline.entries.filter { resultIsGood(it) }.toSet()
        assertEquals(
            setOf(
                ResultHeadline.BOOKED_FIRST,
                ResultHeadline.BOOKED_OTHER,
                ResultHeadline.CHANGED,
                ResultHeadline.CHANGED_OTHER,
                ResultHeadline.CANCELLED,
                ResultHeadline.ANSWERED,
            ),
            good,
        )
        // The face agrees with the color: the happiest face only for the bright result
        for (h in ResultHeadline.entries) assertEquals(h.name, resultIsGood(h), resultHero(h).mood == AitelMood.GREAT)
        // A booking to review must not read as done
        assertFalse(words(resultHero(ResultHeadline.BOOKED_NEEDS_REVIEW)).contains("できました"))
    }

    @Test
    fun theFaceOnTheCallFollowsThePhaseThenTheResult() {
        assertEquals(AitelMood.GOING, callMood(Snapshot(phase = Phase.DIALING)))
        assertEquals(AitelMood.WAITING, callMood(Snapshot(phase = Phase.RINGING)))
        assertEquals(AitelMood.TALKING, callMood(Snapshot(phase = Phase.TALKING)))
        assertEquals(AitelMood.RESTING, callMood(Snapshot(phase = Phase.HANDED_OVER)))
        assertEquals(AitelMood.GREAT, callMood(Snapshot(phase = Phase.ENDED, result = PreviewFixtures.resultBooked)))
        assertEquals(AitelMood.WORRIED, callMood(Snapshot(phase = Phase.ENDED, result = PreviewFixtures.resultBookedOutside)))
        assertEquals(AitelMood.SAD, callMood(Snapshot(phase = Phase.ENDED, result = PreviewFixtures.resultNotReached)))
        assertEquals(AitelMood.SORRY, callMood(Snapshot(phase = Phase.ENDED, result = PreviewFixtures.resultFailed)))
        assertEquals(AitelMood.RESTING, callMood(Snapshot(phase = Phase.ENDED)))
    }

    private fun words(h: HeroText) = h.first + h.second.orEmpty()

    private fun call(phase: Phase, stage: Stage = Stage.L2) =
        CallUiState(Snapshot(phase = phase), stage, warning = null, instruction = null, elapsedMs = null)

    @Test
    fun theKeyFactsOfTheSummaryComeFirstAndNothingIsLost() {
        val items = summaryItems(io.github.incallagent.core.policy.PolicySummary.render(PreviewFixtures.policy))
        val split = splitSummary(items)
        assertEquals(3, split.key.size)
        assertTrue(split.key[0].label.startsWith("電話先"))
        assertTrue(split.key[1].label.startsWith("日時"))
        assertTrue(split.key[2].label.startsWith("人数"))
        assertEquals("every candidate moves with its label", 3, split.key[1].lines.size)
        assertEquals(items.toSet(), (split.key + split.rest).toSet())
        assertEquals(items.size, split.key.size + split.rest.size)
    }

    @Test
    fun aLabelTheSplitDoesNotKnowStaysInTheRest() {
        val unknown = SummaryItem("あたらしい項目", listOf("値"))
        val split = splitSummary(listOf(unknown))
        assertTrue(split.key.isEmpty())
        assertEquals(listOf(unknown), split.rest)
    }
}
