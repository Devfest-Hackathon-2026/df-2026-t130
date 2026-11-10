// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.shell.CallPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControlTest {
    // Fictitious; never dialed in tests
    private val approved = "045-000-0000"

    @Test
    fun onlyTheApprovedNumberCanBeDialed() {
        assertEquals(DialCheck.Result.Ok("0450000000"), DialCheck.check("045-000-0000", approved))
        assertEquals(DialCheck.Result.Ok("0450000000"), DialCheck.check("+81 45 000 0000", approved))
        assertTrue(DialCheck.check("045-000-0001", approved) is DialCheck.Result.Refused)
    }

    @Test
    fun thePracticeNumberIsNeverDialed() {
        val p = PracticePolicy.PHONE_NUMBER
        val r = DialCheck.check(p, p)
        assertTrue("$r", r is DialCheck.Result.Refused)
        assertTrue((r as DialCheck.Result.Refused).reason.contains("練習用"))
    }

    @Test
    fun emergencyAndShortNumbersAreRefused() {
        for (n in listOf("110", "118", "119", "117", "171", "#31#0300000000", "", "0120-00")) {
            val r = DialCheck.check(n, n)
            assertTrue("$n -> $r", r is DialCheck.Result.Refused)
            assertFalse("the reason never shows the number", (r as DialCheck.Result.Refused).reason.contains(n.ifEmpty { "\u0000" }))
        }
    }

    @Test
    fun internationalAndCarrierPrefixesAreNeverDialed() {
        // 00 and 010 never start a domestic number
        for (n in listOf("003-0000-0000", "00-3000-0000", "010-0000-0000", "0100-000-000")) {
            val r = DialCheck.check(n, n)
            assertTrue("$n -> $r", r is DialCheck.Result.Refused)
            assertFalse("the reason never shows the number", (r as DialCheck.Result.Refused).reason.contains(n))
        }
        // The trunk 0 after +81 ("(0)3") is dropped by core or refused here, never dialed as 00…
        for (n in listOf("+81 (0)3-0000-0001", "+81 03-0000-0001", "+81-(0)3-0000-0001")) {
            when (val r = DialCheck.check(n, n)) {
                is DialCheck.Result.Refused -> {}
                is DialCheck.Result.Ok -> assertEquals(n, "0300000001", r.digits)
            }
        }
        assertEquals(DialCheck.Result.Ok("0300000001"), DialCheck.check("+81 3-0000-0001", "+81 3-0000-0001"))
    }

    @Test
    fun aNumberWithTheCallerIdPrefixSaysToLeaveThePrefixOff() {
        for (n in listOf("184-03-0000-0000", "186 090 0000 0000", "18403-0000-0000", "1860312345678")) {
            val r = DialCheck.check(n, n)
            assertTrue("$n -> $r", r is DialCheck.Result.Refused)
            val reason = (r as DialCheck.Result.Refused).reason
            assertTrue(reason, reason.contains("184・186 を付けずに"))
            assertFalse("the reason never shows the number", reason.contains(n))
        }
    }

    @Test
    fun aShortNumberIsNotTakenForTheCallerIdPrefix() {
        for (n in listOf("184", "186", "18412", "110", "119")) {
            val r = DialCheck.check(n, n) as DialCheck.Result.Refused
            assertFalse(r.reason, r.reason.contains("184・186"))
        }
    }

    @Test
    fun paidInformationNumbersAreRefusedEverywhere() {
        for (n in listOf("0990-000-000", "0180-000-000", "0170-000-000")) {
            val r = DialCheck.check(n, n)
            assertTrue("$n -> $r", r is DialCheck.Result.Refused)
            assertTrue(r.toString(), (r as DialCheck.Result.Refused).reason.contains("有料"))
            assertFalse("the reason never shows the number", r.reason.contains(n))
        }
    }

    @Test
    fun theNumbersShopsReallyUseAreNotRefused() {
        for (n in listOf("0570-000-000", "0120-000-000", "050-0000-0000", "03-0000-0001", "090-0000-0000")) {
            assertTrue(n, DialCheck.check(n, n) is DialCheck.Result.Ok)
        }
    }

    @Test
    fun okDoesNotPrintTheDigits() {
        assertFalse(DialCheck.Result.Ok("0300000000").toString().contains("0300000000"))
    }

    @Test
    fun noCallControlAnswersAtOnceAndEndsOnce() {
        val events = ArrayList<CallEvent>()
        val c = NoCallControl()
        c.setListener { events += it }
        c.dial("03-0000-0000")
        c.dial("03-0000-0000")
        c.hangUp()
        c.hangUp()
        assertEquals(listOf(CallEvent.Active, CallEvent.Ended("local")), events)
        c.close()
    }

    @Test
    fun onlyAnEndOnThisPhoneCountsAsEndedHere() {
        assertTrue(DisconnectReasons.endedHere(DisconnectReasons.LOCAL))
        assertTrue(DisconnectReasons.endedHere(DisconnectReasons.USER))
        for (other in listOf("remote", "busy", "unbound", "unknown", "")) {
            assertFalse(other, DisconnectReasons.endedHere(other))
        }
    }

    @Test
    fun aReasonWithoutWordsSaysNothing() {
        for (code in listOf("unknown", "unbound", "other", "not_supported", "")) assertEquals(code, "", DisconnectReasons.inWords(code))
        for (code in listOf("local", "user", "remote", "busy", "rejected", "missed", "canceled", "error", "restricted")) {
            val words = DisconnectReasons.inWords(code)
            assertTrue(code, words.startsWith("（") && !words.contains(code))
        }
    }

    @Test
    fun unmuteIsAskedForOnlyAfterAMuteWasAskedFor() {
        val mute = ManualCallControl.muteInstruction(muted = true, askedToMute = false)
        assertTrue("$mute", mute!!.contains("端末のマイクを止めて"))
        val unmute = ManualCallControl.muteInstruction(muted = false, askedToMute = true)
        assertTrue("$unmute", unmute!!.contains("マイクを戻して"))
        // The screen wording: "ミュート" does not say which microphone
        assertFalse("$mute", mute.contains("ミュート"))
        assertFalse("$unmute", unmute.contains("ミュート"))
        // Loudspeaker mode (L1(a)) or a hand-over before the answer: nothing was muted, nothing to undo
        assertNull(ManualCallControl.muteInstruction(muted = false, askedToMute = false))
    }

    // --- L2: what the helper can see and do ---

    /** Android and the helper: records what the control asks of them. */
    private class FakeManualPhone : ManualPhone {
        var problem: String? = null
        var placeError: Throwable? = null
        var picture = CallPicture.NONE
        var pictureError: Throwable? = null
        var micWorks = true
        var mic = false
        var endWorks = true
        val actions = ArrayList<String>()

        override fun callProblem() = problem

        override fun placeCall(digits: String) {
            placeError?.let { throw it }
            actions += "place:$digits"
        }

        override fun setMicMuted(muted: Boolean): Boolean {
            actions += "mic:$muted"
            if (!micWorks) return false
            mic = muted
            return true
        }

        override fun isMicMuted() = mic

        override fun callPicture(): Int {
            pictureError?.let { throw it }
            return picture
        }

        override fun endCall(): Boolean {
            actions += "end"
            return endWorks
        }
    }

    private inner class ManualRig {
        val scheduler = FakeScheduler()
        val phone = FakeManualPhone()
        val events = ArrayList<CallEvent>()
        val control = ManualCallControl(phone, approved, scheduler, {})

        init {
            control.setListener { events += it }
        }

        fun run() = scheduler.runUntilIdle()

        fun dial(): ManualRig {
            control.dial(approved)
            run()
            return this
        }

        fun look(picture: Int, times: Int = 1, everyMs: Long = LOOK_MS): ManualRig {
            phone.picture = picture
            repeat(times) { scheduler.advance(everyMs) }
            return this
        }

        /** Dialed, seen ringing, seen answered. */
        fun answered(): ManualRig {
            dial()
            look(CallPicture.DIALING, 2).look(CallPicture.ACTIVE)
            assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), events)
            return this
        }

        /** The AI has the call: the phone's microphone is stopped from here. */
        fun micStopped(): ManualRig {
            control.setMuted(true)
            run()
            assertTrue(phone.mic)
            return this
        }

        val panel get() = control.panel.value
    }

    private val LOOK_MS = 500L

    @Test
    fun theAnswerIsSeenAndThereIsNothingToPress() {
        val r = ManualRig().dial()
        assertEquals(listOf("place:0450000000"), r.phone.actions)
        assertEquals(listOf(CallEvent.Dialing), r.events)
        assertNull("nothing to do by hand", r.control.instruction.value)
        assertTrue(r.panel.watchesCall)
        r.look(CallPicture.DIALING, 4)
        assertEquals("the AI never starts while it rings", listOf(CallEvent.Dialing), r.events)
        r.look(CallPicture.ACTIVE)
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), r.events)
        assertTrue(r.panel.micAvailable)
        assertTrue(r.panel.hangUpAvailable)
        assertFalse(r.panel.speakerAvailable)
        assertFalse(r.panel.keypadAvailable)
        assertTrue("what only the phone app can do is one tap away", r.panel.openPhoneOffered)
    }

    @Test
    fun anAnswerThatWasNeverSeenRingingIsNotTrusted() {
        val r = ManualRig().dial()
        r.look(CallPicture.ACTIVE)
        assertEquals(listOf(CallEvent.Dialing), r.events)
        assertFalse(r.panel.watchesCall)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("相手が出た"))
        // Back to the buttons
        r.control.answered()
        r.run()
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), r.events)
    }

    @Test
    fun noCallIsPlacedWhileAnotherIsGoingOn() {
        // The same refusal as the automatic control
        for (picture in listOf(CallPicture.DIALING, CallPicture.ACTIVE, CallPicture.OTHER)) {
            val r = ManualRig()
            r.phone.picture = picture
            r.dial()
            assertTrue("$picture", r.phone.actions.isEmpty())
            assertEquals("$picture", listOf(CallEvent.Failed("ほかの通話の途中なので、電話をかけません")), r.events)
        }
    }

    @Test
    fun aRingingCallStopsTheDialingInItsOwnWords() {
        val r = ManualRig()
        r.phone.picture = CallPicture.RINGING_ONLY
        r.dial()
        assertTrue(r.phone.actions.isEmpty())
        val failed = r.events.single() as CallEvent.Failed
        assertTrue(failed.detail, failed.detail.contains("着信"))
        assertFalse(failed.detail, failed.detail.contains("通話の途中"))
    }

    @Test
    fun theEndOfTheLastCallIsWaitedForAMoment() {
        val r = ManualRig()
        r.phone.picture = CallPicture.ENDING
        r.dial()
        assertTrue("not yet", r.phone.actions.isEmpty())
        r.scheduler.advance(1_000)
        assertTrue(r.phone.actions.isEmpty())
        r.phone.picture = CallPicture.NONE
        r.scheduler.advance(500)
        assertEquals(listOf("place:0450000000"), r.phone.actions)
        assertTrue("and it is watched", r.panel.watchesCall)
        assertEquals(listOf(CallEvent.Dialing), r.events)
    }

    @Test
    fun aCallThatDoesNotFinishEndingStopsTheDialing() {
        val r = ManualRig()
        r.phone.picture = CallPicture.ENDING
        r.dial()
        r.scheduler.advance(3_500)
        assertTrue(r.phone.actions.isEmpty())
        assertEquals(listOf(CallEvent.Failed("ほかの通話の途中なので、電話をかけません")), r.events)
    }

    @Test
    fun aHelperThatCannotTellDoesNotStopTheCallAndTheButtonsStay() {
        val r = ManualRig()
        r.phone.picture = CallPicture.UNKNOWN
        r.dial()
        assertEquals(listOf("place:0450000000"), r.phone.actions)
        assertFalse(r.panel.watchesCall)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("相手が出た"))
        r.scheduler.advance(5_000)
        assertEquals("no answer is made up", listOf(CallEvent.Dialing), r.events)
    }

    @Test
    fun troubleWithTheHelperNeverEndsTheCall() {
        val r = ManualRig().dial()
        r.phone.pictureError = RuntimeException("no helper")
        r.scheduler.advance(10_000)
        assertEquals(listOf(CallEvent.Dialing), r.events)
        assertFalse(r.panel.watchesCall)
        r.control.answered()
        r.run()
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), r.events)
    }

    @Test
    fun aCallThatNeverShowsUpStopsTheWatch() {
        val r = ManualRig().dial()
        r.scheduler.advance(40_000)
        assertFalse(r.panel.watchesCall)
        assertEquals("no end is made up", listOf(CallEvent.Dialing), r.events)
    }

    @Test
    fun theEndIsSeenAfterTheAnswerAndAMomentWithoutACallDoesNotEndIt() {
        val r = ManualRig().answered()
        r.look(CallPicture.NONE, everyMs = 1_000)
        assertEquals(2, r.events.size)
        r.look(CallPicture.ACTIVE, everyMs = 1_000)
        r.look(CallPicture.NONE, 2, everyMs = 1_000)
        assertEquals(CallEvent.Ended("unknown"), r.events.last())
        assertFalse(r.panel.watchesCall)
    }

    @Test
    fun theCallEndingBeforeTheAnswerIsSeenToo() {
        val r = ManualRig().dial()
        r.look(CallPicture.DIALING, 2).look(CallPicture.NONE, 2)
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Ended("unknown")), r.events)
    }

    @Test
    fun theAisGoodbyeEndsTheCallThroughTheHelperLikeTheButton() {
        val r = ManualRig().answered()
        r.control.hangUp()
        r.run()
        assertTrue(r.phone.actions.contains("end"))
        r.look(CallPicture.NONE, 2, everyMs = 1_000)
        assertEquals(CallEvent.Ended(DisconnectReasons.LOCAL), r.events.last())
    }

    @Test
    fun aRefusedHangUpIsTriedOnceMoreWhileTheWatchGoesOn() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        assertEquals(null, r.control.instruction.value)
        r.phone.endWorks = true
        r.scheduler.advance(3_000)
        assertEquals(2, r.phone.actions.count { it == "end" })
        r.look(CallPicture.NONE, 2, everyMs = 1_000)
        assertEquals(CallEvent.Ended(DisconnectReasons.LOCAL), r.events.last())
    }

    @Test
    fun aCallThatEndedByItselfDuringTheRetryEndsNormally() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.look(CallPicture.NONE, 2, everyMs = 1_000)
        assertTrue("${r.events}", r.events.last() is CallEvent.Ended)
    }

    @Test
    fun aHangUpRefusedTwiceIsLeftToTheUser() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.scheduler.advance(3_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
    }

    @Test
    fun pressingHangUpTwiceAsksOnce() {
        val r = ManualRig().answered()
        r.control.userHangUp()
        r.control.userHangUp()
        r.run()
        assertEquals(1, r.phone.actions.count { it == "end" })
        assertTrue("the call is still being watched", r.panel.watchesCall)
    }

    @Test
    fun aHandOverBeforeTheAnswerClearsTheOldInstruction() {
        val r = ManualRig()
        r.phone.picture = CallPicture.UNKNOWN
        r.dial()
        assertTrue(r.control.instruction.value!!.contains("相手が出た"))
        r.control.setMuted(false)
        r.run()
        assertNull("as before: nothing to do by hand any more", r.control.instruction.value)
    }

    @Test
    fun aHangUpTheHelperRefusesGoesBackToThePhoneScreen() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.userHangUp()
        r.run()
        r.scheduler.advance(3_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        assertFalse("the app no longer says it sees the call", r.panel.watchesCall)
        assertEquals(2, r.events.size)
    }

    @Test
    fun aHangUpThatDoesNotEndTheCallGoesBackToThePhoneScreen() {
        val r = ManualRig().answered()
        r.control.userHangUp()
        r.run()
        r.look(CallPicture.ACTIVE, 12, everyMs = 1_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        assertFalse(r.panel.watchesCall)
    }

    @Test
    fun withoutTheWatchTheHangUpIsAskedForByHand() {
        val r = ManualRig()
        r.phone.picture = CallPicture.UNKNOWN
        r.dial()
        r.control.userHangUp()
        r.run()
        assertFalse(r.phone.actions.contains("end"))
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("切った"))
    }

    @Test
    fun aHoldOrASecondCallStopsTheAiAsTheAutomaticControlDoes() {
        val r = ManualRig().answered()
        r.look(CallPicture.OTHER, 2, everyMs = 1_000)
        assertFalse(r.panel.watchesCall)
        assertFalse(r.panel.hangUpAvailable)
        assertEquals(3, r.events.size)
        assertTrue("${r.events}", r.events.last() is CallEvent.Failed)
    }

    @Test
    fun oneLookAtAHoldIsNotEnough() {
        val r = ManualRig().answered()
        r.look(CallPicture.OTHER, everyMs = 1_000)
        r.look(CallPicture.ACTIVE, everyMs = 1_000)
        r.look(CallPicture.OTHER, everyMs = 1_000)
        assertTrue(r.panel.watchesCall)
        assertEquals(2, r.events.size)
    }

    @Test
    fun aLookThatCouldNotTellBreaksTheRowOfHolds() {
        val r = ManualRig().answered()
        r.look(CallPicture.OTHER, everyMs = 1_000)
        r.look(CallPicture.UNKNOWN, everyMs = 1_000)
        r.look(CallPicture.OTHER, everyMs = 1_000)
        assertTrue(r.panel.watchesCall)
        assertEquals(2, r.events.size)
    }

    @Test
    fun anAnswerMarkedByHandIsHandedOverToo() {
        val r = ManualRig().dial()
        r.look(CallPicture.DIALING)
        r.control.answered()
        r.run()
        r.look(CallPicture.OTHER, 2, everyMs = 1_000)
        assertTrue("${r.events}", r.events.last() is CallEvent.Failed)
    }

    @Test
    fun beforeTheAnswerAHoldOnlyBringsBackTheButtons() {
        val r = ManualRig().dial()
        r.look(CallPicture.DIALING).look(CallPicture.OTHER, 2)
        assertEquals(listOf(CallEvent.Dialing), r.events)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("相手が出た"))
    }

    @Test
    fun whenTheHelperCannotTellTheAiGoesOn() {
        val r = ManualRig().answered()
        r.look(CallPicture.UNKNOWN, 6, everyMs = 1_000)
        assertFalse(r.panel.watchesCall)
        assertEquals(2, r.events.size)
        assertNull(r.control.instruction.value)
    }

    @Test
    fun aHoldDuringAHangUpAsksTheUserToEndTheCallAndStopsTheAi() {
        // "電話を切る" can be pressed while the AI talks: then it is still going
        val r = ManualRig().answered()
        r.control.userHangUp()
        r.run()
        r.look(CallPicture.OTHER, 2, everyMs = 1_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        assertTrue("${r.events}", r.events.last() is CallEvent.Failed)
    }

    @Test
    fun theButtonAndTheWatchAnswerOnlyOnce() {
        val r = ManualRig().dial()
        r.look(CallPicture.DIALING)
        r.control.answered()
        r.control.answered()
        r.run()
        r.look(CallPicture.ACTIVE, everyMs = 1_000)
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), r.events)
    }

    @Test
    fun theHungUpButtonWorksAsBefore() {
        val r = ManualRig()
        r.phone.picture = CallPicture.UNKNOWN
        r.dial()
        r.control.userHungUp()
        r.run()
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Ended(DisconnectReasons.USER)), r.events)
    }

    @Test
    fun theMicrophoneIsStoppedFromHereAndGivenBack() {
        val r = ManualRig().answered()
        r.control.setMuted(true)
        r.run()
        assertTrue(r.phone.mic)
        assertNull("nothing to do by hand", r.control.instruction.value)
        assertTrue(r.panel.micOff)
        r.look(CallPicture.NONE, 2, everyMs = 1_000)
        assertFalse("given back when the call ends", r.phone.mic)
    }

    @Test
    fun closingGivesTheMicrophoneBackToo() {
        val r = ManualRig().answered()
        r.control.setMuted(true)
        r.run()
        r.control.close()
        r.run()
        assertFalse(r.phone.mic)
    }

    @Test
    fun whenTheMicrophoneCannotBeStoppedTheInstructionComesBack() {
        val r = ManualRig().answered()
        r.phone.micWorks = false
        r.control.setMuted(true)
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("端末のマイクを止めて"))
        assertFalse(r.panel.micAvailable)
        r.control.setMuted(false)
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("マイクを戻して"))
    }

    @Test
    fun aMicrophoneThatCannotBeGivenBackAsksTheUserToDoIt() {
        val r = ManualRig().answered()
        r.control.setMuted(true)
        r.run()
        r.phone.micWorks = false
        r.control.setMuted(false)
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("マイクを戻して"))
        assertFalse(r.panel.micAvailable)
    }

    // --- the microphone, when the call is left for the user to end ---

    @Test
    fun withoutTheWatchTheGoodbyeGivesTheMicrophoneBack() {
        val r = ManualRig()
        r.phone.picture = CallPicture.UNKNOWN
        r.dial()
        r.control.answered()
        r.run()
        r.micStopped()
        r.control.hangUp()
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("切った"))
        assertFalse("「切った」may be forgotten, and the microphone is the whole phone's", r.phone.mic)
        assertFalse(r.panel.micOff)
    }

    @Test
    fun aHangUpRefusedTwiceGivesTheMicrophoneBack() {
        val r = ManualRig().answered().micStopped()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        assertTrue("the first refusal is only tried again", r.phone.mic)
        r.scheduler.advance(3_000)
        assertFalse(r.phone.mic)
        assertFalse(r.panel.micOff)
    }

    @Test
    fun aHangUpThatDoesNotEndTheCallGivesTheMicrophoneBack() {
        val r = ManualRig().answered().micStopped()
        r.control.hangUp()
        r.run()
        r.look(CallPicture.ACTIVE, 9, everyMs = 1_000)
        assertTrue("still waiting for the end", r.phone.mic)
        r.look(CallPicture.ACTIVE, 3, everyMs = 1_000)
        assertFalse(r.phone.mic)
    }

    @Test
    fun aCallLeftForTheUserToEndKeepsTheMicrophoneOn() {
        val r = ManualRig().answered().micStopped()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.scheduler.advance(3_000)
        assertFalse(r.phone.mic)
        r.control.setMuted(true)
        r.run()
        assertFalse(r.phone.mic)
        assertFalse("no button that would do nothing", r.panel.micAvailable)
    }

    @Test
    fun theAskForAPushToneGoesAwayByItself() {
        val r = ManualRig().answered()
        r.control.sendDtmf("1")
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("キーパッド"))
        r.look(CallPicture.ACTIVE, 19, everyMs = 1_000)
        assertTrue("there is time to do it", r.control.instruction.value != null)
        r.look(CallPicture.ACTIVE, 2, everyMs = 1_000)
        assertNull(r.control.instruction.value)
    }

    @Test
    fun theAskForAPushToneDoesNotTakeAwayALaterInstruction() {
        val r = ManualRig().answered()
        r.control.sendDtmf("1")
        r.run()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.scheduler.advance(3_000)
        r.scheduler.advance(20_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
    }

    // --- the watch stops while a hang-up is under way ---

    @Test
    fun aWatchThatStopsAfterTheHangUpWasAskedForLeavesItToTheUser() {
        val r = ManualRig().answered().micStopped()
        r.control.hangUp()
        r.run()
        r.look(CallPicture.UNKNOWN, 6, everyMs = 1_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        assertFalse(r.panel.watchesCall)
        assertFalse(r.phone.mic)
        assertEquals("nothing is made up about the end", 2, r.events.size)
    }

    @Test
    fun aWatchThatStopsDuringTheRetryLeavesItToTheUser() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.phone.endWorks = true
        r.look(CallPicture.OTHER, 2, everyMs = 1_000)
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        r.scheduler.advance(10_000)
        assertEquals("not tried again without a watch", 1, r.phone.actions.count { it == "end" })
    }

    @Test
    fun theHangUpInstructionStaysWhenTheMicrophoneComesBack() {
        val r = ManualRig().answered()
        r.phone.endWorks = false
        r.control.hangUp()
        r.run()
        r.scheduler.advance(3_000)
        // The hand-over, and the "マイクを戻す" of the screen
        r.control.setMuted(false)
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
        r.phone.micWorks = false
        r.control.setMuted(false)
        r.run()
        assertTrue("${r.control.instruction.value}", r.control.instruction.value!!.contains("電話の画面で"))
    }

    @Test
    fun aNumberThatCannotBeDialedPlacesNothing() {
        val r = ManualRig()
        r.control.dial("045-000-0001")
        r.run()
        assertTrue(r.phone.actions.isEmpty())
        assertTrue("${r.events}", r.events.single() is CallEvent.Failed)
    }

    @Test
    fun aFailureToPlaceTheCallSaysWhatToDoNotWhichExceptionItWas() {
        val r = ManualRig()
        r.phone.placeError = IllegalStateException("x")
        r.dial()
        val failed = r.events.single() as CallEvent.Failed
        assertFalse(failed.detail, failed.detail.contains("IllegalState"))
        assertTrue(failed.detail, failed.detail.contains("もう一度"))
    }

    @Test
    fun noPermissionPlacesNothing() {
        val r = ManualRig()
        r.phone.problem = "許可がありません"
        r.dial()
        assertTrue(r.phone.actions.isEmpty())
        assertEquals(listOf(CallEvent.Failed("許可がありません")), r.events)
    }

    // --- returning to the app after dialing ---

    @Test
    fun theAppComesBackOnlyRightAfterDialingAndNotForever() {
        val r = ReturnToApp(windowMs = 10_000, maxReturns = 2)
        assertFalse("no call placed", r.screenHidden(0))
        r.dialed(1_000)
        assertTrue(r.screenHidden(2_000))
        assertTrue(r.screenHidden(3_000))
        assertFalse("only so many times", r.screenHidden(4_000))
        r.dialed(20_000)
        assertFalse("too late", r.screenHidden(40_000))
        r.dialed(50_000)
        r.stop()
        assertFalse("the user went to the phone's screen on purpose", r.screenHidden(50_500))
    }
}
