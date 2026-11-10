// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.call.CallSession.EndReason
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TelecomCallControlTest {
    // Fictitious; never dialed in tests
    private val approved = "045-000-0000"
    private val digits = "0450000000"
    private val otherNumber = "09000000000"

    private val rigs = ArrayList<Rig>()

    @After
    fun closeAll() {
        for (r in rigs) {
            r.control.close()
            r.run()
        }
        assertNull("no control is left in the hub", InCallHub.sink)
    }

    /** The phone: records what the control asks of it. */
    private class FakePhone : TelecomPhone {
        var problem: String? = null
        var inCall = false
        var inCallError: Throwable? = null
        val emergency = HashSet<String>()
        var emergencyError: Throwable? = null
        var placeError: Throwable? = null
        val actions = ArrayList<String>()

        override fun controlProblem() = problem

        override fun isInCall(): Boolean {
            inCallError?.let { throw it }
            return inCall
        }

        override fun isEmergencyNumber(number: String): Boolean {
            emergencyError?.let { throw it }
            return number in emergency
        }

        override fun setInCallServiceEnabled(enabled: Boolean) {
            actions += if (enabled) "enable" else "disable"
        }

        override fun placeCall(digits: String) {
            placeError?.let { throw it }
            actions += "place:$digits"
        }

        override fun wallClockMs() = T0
    }

    private class FakeCall(override val id: String) : CallHandle {
        val actions = ArrayList<String>()
        var disconnectError: Throwable? = null

        override fun disconnect() {
            disconnectError?.let { throw it }
            actions += "disconnect"
        }

        override fun playDtmfTone(digit: Char) {
            actions += "play:$digit"
        }

        override fun stopDtmfTone() {
            actions += "stop"
        }
    }

    private inner class Rig(config: TelecomCallControl.Config = TelecomCallControl.Config()) {
        val scheduler = FakeScheduler()
        val phone = FakePhone()
        val events = ArrayList<CallEvent>()
        val mutes = ArrayList<Boolean>()
        val routes = ArrayList<Int>()
        val port = object : InCallPort {
            override fun setMuted(muted: Boolean) {
                mutes += muted
            }

            override fun setRoute(route: Int) {
                routes += route
            }
        }
        val control = TelecomCallControl(phone, approved, scheduler, config = config)
        val placed = FakeCall("call#1")

        init {
            control.setListener { events += it }
            rigs += this
        }

        fun run() = scheduler.runUntilIdle()

        fun dial(number: String = approved): Rig {
            control.dial(number)
            run()
            return this
        }

        fun add(call: FakeCall, v: CallView): Rig {
            control.onCallAdded(call, v, port)
            run()
            return this
        }

        fun change(call: FakeCall, v: CallView): Rig {
            control.onCallChanged(call, v)
            run()
            return this
        }

        /** Dialed, the placed call found and answered. */
        fun active(): Rig {
            dial()
            add(placed, view(CallStatus.DIALING))
            change(placed, view(CallStatus.ACTIVE))
            assertEquals(listOf(CallEvent.Dialing, CallEvent.Active), events)
            return this
        }

        fun mute(): Rig {
            control.setMuted(true)
            run()
            assertEquals(listOf(true), mutes)
            return this
        }
    }

    /** A call as RotepInCallService reports it; by default the placed call. */
    private fun view(
        status: CallStatus,
        digits: String? = this.digits,
        createdAtMs: Long = T0 + 300,
        outgoing: Boolean = true,
        conference: Boolean = false,
        emergency: Boolean = false,
        reason: String? = null,
        choosingAccount: Boolean = false,
    ) = CallView(status, digits, createdAtMs, outgoing, conference, emergency, reason, choosingAccount)

    private fun incoming(status: CallStatus, emergency: Boolean = false) =
        view(status, digits = otherNumber, outgoing = false, emergency = emergency)

    // --- which SIM to use ---

    @Test
    fun aCallWaitingForTheChoiceOfASimIsEndedWhenNobodyIsAtThePhone() {
        // Chosen hours later, the call would go out long after the time the user approved
        val r = Rig(TelecomCallControl.Config(nobodyThere = true)).dial()
        r.add(r.placed, view(CallStatus.DIALING, choosingAccount = true))
        assertTrue(r.placed.actions.contains("disconnect"))
        assertEquals("nothing went out: not reported as dialed", 1, r.events.size)
        assertTrue("${r.events}", r.events.single() is CallEvent.Failed)
    }

    @Test
    fun aCallWaitingForTheChoiceOfASimIsEndedOnce() {
        val r = Rig(TelecomCallControl.Config(nobodyThere = true)).dial()
        r.add(r.placed, view(CallStatus.DIALING, choosingAccount = true))
        r.change(r.placed, view(CallStatus.DIALING, choosingAccount = true))
        assertEquals(1, r.placed.actions.count { it == "disconnect" })
        assertEquals(1, r.events.size)
    }

    @Test
    fun aCallThatCannotBeEndedWhileTheSimIsChosenIsReportedAsPlaced() {
        val r = Rig(TelecomCallControl.Config(nobodyThere = true)).dial()
        r.placed.disconnectError = IllegalStateException("gone")
        r.add(r.placed, view(CallStatus.DIALING, choosingAccount = true))
        assertEquals("the session has to tell the user to look at the phone", CallEvent.Dialing, r.events.first())
        assertTrue("${r.events}", r.events.last() is CallEvent.Failed)
    }

    @Test
    fun withSomeoneAtThePhoneTheChoiceOfASimIsTheirs() {
        val r = Rig().dial()
        r.add(r.placed, view(CallStatus.DIALING, choosingAccount = true))
        assertFalse(r.placed.actions.contains("disconnect"))
        assertEquals(listOf<CallEvent>(CallEvent.Dialing), r.events)
    }

    // --- dialing ---

    @Test
    fun theServiceIsEnabledBeforeTheApprovedNumberIsPlaced() {
        val r = Rig().dial()
        assertEquals(listOf("enable", "place:$digits"), r.phone.actions)
        assertSame(r.control, InCallHub.sink)
        assertTrue(r.control.wantsCalls())
        assertTrue("nothing until the service shows the call", r.events.isEmpty())
    }

    @Test
    fun onlyTheApprovedNumberIsDialed() {
        for (n in listOf("045-000-0001", "110", "119", PracticePolicy.PHONE_NUMBER)) {
            val r = Rig().dial(n)
            assertTrue(n, r.phone.actions.isEmpty())
            assertTrue("$n -> ${r.events}", r.events.single() is CallEvent.Failed)
        }
    }

    @Test
    fun emergencyNumbersOfThePhoneAreNeverDialed() {
        val r = Rig()
        r.phone.emergency += digits
        r.dial()
        assertTrue(r.phone.actions.isEmpty())
        assertTrue((r.events.single() as CallEvent.Failed).detail.contains("緊急の番号"))

        val unknown = Rig()
        unknown.phone.emergencyError = IllegalStateException("telephony")
        unknown.dial()
        assertTrue("dials only when the phone can tell", unknown.phone.actions.isEmpty())
        assertTrue((unknown.events.single() as CallEvent.Failed).detail.contains("確かめられませんでした"))
    }

    @Test
    fun withoutTheAppOpNothingIsDialed() {
        val r = Rig()
        r.phone.problem = "MANAGE_ONGOING_CALLS の AppOps がありません"
        r.dial()
        assertTrue(r.phone.actions.isEmpty())
        assertEquals(CallEvent.Failed("MANAGE_ONGOING_CALLS の AppOps がありません"), r.events.single())
        assertNull(InCallHub.sink)
    }

    @Test
    fun noCallIsPlacedDuringAnotherCall() {
        val r = Rig()
        r.phone.inCall = true
        r.dial()
        assertTrue(r.phone.actions.isEmpty())
        assertEquals(CallEvent.Failed("ほかの通話の途中なので、電話をかけません"), r.events.single())
        assertNull(InCallHub.sink)

        val unknown = Rig()
        unknown.phone.inCallError = SecurityException("READ_PHONE_STATE")
        unknown.dial()
        assertTrue(unknown.phone.actions.isEmpty())
        val detail = (unknown.events.single() as CallEvent.Failed).detail
        assertTrue(detail, detail.contains("確かめられない"))
        assertFalse("for the log, not for the screen", detail.contains("Exception"))
    }

    @Test
    fun aRefusedPlaceCallDisablesTheServiceAgain() {
        val r = Rig()
        r.phone.placeError = SecurityException("CALL_PHONE")
        r.dial()
        assertEquals(listOf("enable", "disable"), r.phone.actions)
        assertEquals(CallEvent.Failed("発信が許可されませんでした"), r.events.single())
        assertNull(InCallHub.sink)
        assertFalse(r.control.wantsCalls())
    }

    @Test
    fun dialingTwiceDialsOnce() {
        val r = Rig().dial().dial()
        assertEquals(listOf("enable", "place:$digits"), r.phone.actions)
    }

    // --- which call is the placed one ---

    @Test
    fun thePlacedCallIsReportedFromDialingToTheEnd() {
        val r = Rig().active()
        r.change(r.placed, view(CallStatus.ACTIVE))
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        r.control.onCallRemoved(r.placed)
        r.run()
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Active, CallEvent.Ended("local")), r.events)
        r.control.close()
        r.run()
        assertEquals("disable", r.phone.actions.last())
        assertNull(InCallHub.sink)
        assertFalse(r.control.wantsCalls())
    }

    @Test
    fun aPlacedCallCreatedJustBeforePlaceCallReturnedCounts() {
        val r = Rig().dial()
        r.add(r.placed, view(CallStatus.DIALING, createdAtMs = T0 - 1_000))
        assertEquals(listOf(CallEvent.Dialing), r.events)
    }

    @Test
    fun onlyAnOutgoingCallWithTheFullNumberCreatedAfterDialingIsThePlacedCall() {
        val notOurs = listOf(
            "another number" to view(CallStatus.DIALING, digits = "0450000001"),
            "no number" to view(CallStatus.DIALING, digits = null),
            "incoming" to view(CallStatus.RINGING, outgoing = false),
            "created before dialing" to view(CallStatus.DIALING, createdAtMs = T0 - 10_000),
            "created long after dialing" to view(CallStatus.DIALING, createdAtMs = T0 + 120_000),
            "an emergency call" to view(CallStatus.DIALING, emergency = true),
        )
        for ((what, v) in notOurs) {
            val r = Rig().dial()
            val call = FakeCall("call#9")
            r.add(call, v)
            // Not taken as the placed call: never answered for it (a call that cannot be ours, like
            // one created before dialing, makes the control give up at once, with Dialing first)
            assertFalse(what, r.events.contains(CallEvent.Active))
            r.scheduler.advance(20_000)
            assertTrue("$what -> ${r.events}", r.events.lastOrNull() is CallEvent.Failed)
            r.control.hangUp()
            r.control.sendDtmf("1")
            r.run()
            assertTrue("$what: nothing is done to it", call.actions.isEmpty())
        }
    }

    @Test
    fun aCallNeverShownToTheServiceFailsAfterTheBindTimeout() {
        val r = Rig().dial()
        r.scheduler.advance(19_999)
        assertTrue(r.events.isEmpty())
        r.scheduler.advance(1)
        // Dialing first: the call was placed and may be ringing, so the session alerts the user
        assertEquals(CallEvent.Dialing, r.events.first())
        val f = r.events.last() as CallEvent.Failed
        assertEquals(2, r.events.size)
        assertTrue(f.detail, f.detail.contains("アプリから通話を操作できません"))
        assertFalse(f.detail, f.detail.contains("InCallService"))
        // Too late: nothing more is reported or done
        r.add(r.placed, view(CallStatus.ACTIVE))
        r.control.hangUp()
        r.run()
        assertEquals(2, r.events.size)
        assertTrue(r.placed.actions.isEmpty())
    }

    // --- hold, other calls, conference, emergency ---

    @Test
    fun holdStopsTheControlAndUnmutes() {
        val r = Rig().active().mute()
        r.change(r.placed, view(CallStatus.HOLDING))
        assertEquals(CallEvent.Failed("保留になりました"), r.events.last())
        assertEquals(listOf(true, false), r.mutes)
        // Back from hold: no second Active, and nothing is operated any more
        r.change(r.placed, view(CallStatus.ACTIVE))
        r.control.sendDtmf("1")
        r.control.setMuted(true)
        r.control.hangUp()
        r.run()
        assertTrue(r.placed.actions.isEmpty())
        assertEquals(listOf(true, false), r.mutes)
        // The end is still reported
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        assertEquals(
            listOf(CallEvent.Dialing, CallEvent.Active, CallEvent.Failed("保留になりました"), CallEvent.Ended("local")),
            r.events,
        )
    }

    @Test
    fun anotherCallGoingOnStopsTheControl() {
        val r = Rig().active().mute()
        val other = FakeCall("call#2")
        r.add(other, incoming(CallStatus.RINGING))
        assertEquals("ringing alone (call waiting) changes nothing", 2, r.events.size)
        r.change(other, incoming(CallStatus.ACTIVE))
        assertEquals(CallEvent.Failed("ほかの通話が始まりました"), r.events.last())
        assertEquals(listOf(true, false), r.mutes)
        assertTrue("nothing is done to the other call", other.actions.isEmpty())
    }

    @Test
    fun anotherCallAlreadyActiveWhenThePlacedCallIsFoundStopsTheControl() {
        val r = Rig().dial()
        val other = FakeCall("call#2")
        r.add(other, incoming(CallStatus.ACTIVE))
        assertEquals(listOf(CallEvent.Dialing, CallEvent.Failed("ほかの通話が始まりました")), r.events)
        r.add(r.placed, view(CallStatus.ACTIVE))
        assertEquals("never Active after the control was given up", 2, r.events.size)
    }

    @Test
    fun aConferenceStopsTheControl() {
        val r = Rig().active()
        r.change(r.placed, view(CallStatus.ACTIVE, conference = true))
        assertEquals(CallEvent.Failed("会議通話になりました"), r.events.last())
    }

    @Test
    fun anEmergencyCallStopsTheControlEvenWhileRinging() {
        val r = Rig().active().mute()
        val other = FakeCall("call#2")
        r.add(other, incoming(CallStatus.RINGING, emergency = true))
        assertEquals(CallEvent.Failed("緊急通報の通話があります"), r.events.last())
        assertEquals("unmuted at once", listOf(true, false), r.mutes)
        assertTrue(other.actions.isEmpty())
    }

    @Test
    fun thePlacedCallTreatedAsAnEmergencyCallIsLeftAlone() {
        val r = Rig().active()
        r.change(r.placed, view(CallStatus.ACTIVE, emergency = true))
        assertTrue(r.events.last() is CallEvent.Failed)
        r.control.hangUp()
        r.control.sendDtmf("1")
        r.run()
        assertTrue(r.placed.actions.isEmpty())
    }

    // --- DTMF, mute, hang-up ---

    @Test
    fun dtmfPlaysOneToneAtATime() {
        val r = Rig().active()
        r.control.sendDtmf("1#")
        r.run()
        assertEquals(listOf("play:1"), r.placed.actions)
        r.scheduler.advance(250)
        assertEquals(listOf("play:1", "stop"), r.placed.actions)
        r.scheduler.advance(150)
        assertEquals(listOf("play:1", "stop", "play:#"), r.placed.actions)
        r.scheduler.advance(250)
        r.scheduler.advance(1_000)
        assertEquals(listOf("play:1", "stop", "play:#", "stop"), r.placed.actions)
    }

    @Test
    fun dtmfStopsWhenTheCallIsHeld() {
        val r = Rig().active()
        r.control.sendDtmf("123")
        r.run()
        r.change(r.placed, view(CallStatus.HOLDING))
        r.scheduler.advance(5_000)
        assertEquals(listOf("play:1", "stop"), r.placed.actions)
    }

    @Test
    fun dtmfAndMuteWaitForTheAnswer() {
        val r = Rig().dial()
        r.add(r.placed, view(CallStatus.DIALING))
        r.control.sendDtmf("1")
        r.control.setMuted(true)
        r.run()
        assertTrue(r.placed.actions.isEmpty())
        assertTrue(r.mutes.isEmpty())
    }

    @Test
    fun theMuteIsTakenOffWhenTheCallEnds() {
        val r = Rig().active().mute()
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "remote"))
        assertEquals(listOf(true, false), r.mutes)
        assertEquals(CallEvent.Ended("remote"), r.events.last())
    }

    @Test
    fun closingUnmutesAndDisablesTheService() {
        val r = Rig().active().mute()
        r.control.close()
        r.run()
        assertEquals(listOf(true, false), r.mutes)
        assertEquals("disable", r.phone.actions.last())
        assertNull(InCallHub.sink)
        // Nothing after close
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        assertEquals(2, r.events.size)
    }

    @Test
    fun hangUpDisconnectsOnlyThePlacedCall() {
        val r = Rig().active()
        val other = FakeCall("call#2")
        r.add(other, incoming(CallStatus.RINGING))
        r.control.hangUp()
        r.run()
        assertEquals(listOf("disconnect"), r.placed.actions)
        assertTrue(other.actions.isEmpty())
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        r.scheduler.advance(60_000)
        assertEquals("no hang-up timeout after the end", CallEvent.Ended("local"), r.events.last())
    }

    @Test
    fun aHangUpThatDoesNotEndTheCallIsAFailure() {
        val r = Rig().active()
        r.control.hangUp()
        r.run()
        r.scheduler.advance(10_000)
        val f = r.events.last() as CallEvent.Failed
        assertTrue(f.detail, f.detail.contains("電話の画面で切って"))
    }

    // --- the service ---

    @Test
    fun losingTheServiceDuringTheCallIsAFailureThenAnEnd() {
        val r = Rig().active().mute()
        r.control.onUnbinding(r.port)
        r.run()
        assertEquals(listOf(true, false), r.mutes)
        assertTrue(r.events[2] is CallEvent.Failed)
        assertEquals(CallEvent.Ended("unbound"), r.events[3])
        // InCallService then reports the call DISCONNECTED although it may go on: nothing more
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "unknown"))
        assertEquals(4, r.events.size)
    }

    @Test
    fun theServiceTakesCallsOnlyWhileACallIsExpected() {
        val r = Rig()
        assertFalse(r.control.wantsCalls())
        r.dial()
        assertTrue(r.control.wantsCalls())
        r.control.close()
        r.run()
        assertFalse(r.control.wantsCalls())
    }

    @Test
    fun callViewsNeverPrintTheNumber() {
        assertFalse(view(CallStatus.ACTIVE).toString().contains(digits))
        assertFalse(incoming(CallStatus.RINGING).toString().contains(otherNumber))
    }

    @Test
    fun androidStatesAndCausesBecomeShortCodes() {
        assertEquals(CallStatus.DIALING, CallStatus.of(Call.STATE_NEW))
        assertEquals(CallStatus.DIALING, CallStatus.of(Call.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(CallStatus.DIALING, CallStatus.of(Call.STATE_CONNECTING))
        assertEquals(CallStatus.ACTIVE, CallStatus.of(Call.STATE_ACTIVE))
        assertEquals(CallStatus.HOLDING, CallStatus.of(Call.STATE_HOLDING))
        assertEquals(CallStatus.RINGING, CallStatus.of(Call.STATE_SIMULATED_RINGING))
        assertEquals(CallStatus.DISCONNECTED, CallStatus.of(Call.STATE_DISCONNECTED))
        assertEquals(CallStatus.OTHER, CallStatus.of(Call.STATE_AUDIO_PROCESSING))
        assertEquals(CallStatus.OTHER, CallStatus.of(99))
        assertEquals("local", DisconnectReasons.of(DisconnectCause.LOCAL))
        assertEquals("remote", DisconnectReasons.of(DisconnectCause.REMOTE))
        assertEquals("busy", DisconnectReasons.of(DisconnectCause.BUSY))
        assertEquals("unknown", DisconnectReasons.of(null))
    }

    // --- with CallSession ---

    private val today = LocalDate.of(2026, 11, 7)
    private val now = today.atTime(10, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant()

    private inner class SessionRig(val config: CallSession.Config = CallSession.Config()) {
        val r = Rig()
        val audio = FakeAudioPath()
        val live = FakeLive()
        val results = ArrayList<CallSession.Result>()
        val alerts = ArrayList<EndReason>()
        val session = PracticePolicy.approved(today, now).let { base ->
            CallSession(
                policy = base.copy(target = base.target.copy(phoneNumber = approved)),
                audio = audio,
                control = r.control,
                live = live,
                scheduler = r.scheduler,
                today = today,
                config = config,
                listener = object : CallSession.Listener {
                    override fun onAlert(reason: EndReason, text: String) {
                        alerts += reason
                    }

                    override fun onResult(result: CallSession.Result) {
                        results += result
                    }
                },
                wallClock = { now },
            )
        }
        val phase get() = session.state.value.phase

        /** Dialed, answered, and the AI is ready. */
        fun talking(): SessionRig {
            session.start()
            r.run()
            assertEquals(listOf("enable", "place:$digits"), r.phone.actions)
            r.add(r.placed, view(CallStatus.DIALING))
            assertEquals(Phase.RINGING, phase)
            r.change(r.placed, view(CallStatus.ACTIVE))
            assertEquals(Phase.TALKING, phase)
            val expected = if (config.muteMicDuringAi) listOf(true) else emptyList()
            assertEquals("the phone's microphone while the AI talks", expected, r.mutes)
            live.ready()
            r.run()
            return this
        }
    }

    @Test
    fun withCallSessionACallTheAppNeverFindsAlertsTheUser() {
        // Placed, but the service never shows it: the shop's phone may be ringing
        val s = SessionRig()
        s.session.start()
        s.r.run()
        assertEquals(listOf("enable", "place:$digits"), s.r.phone.actions)
        assertEquals(Phase.DIALING, s.phase)
        s.r.scheduler.advance(20_000)
        s.r.run()
        assertEquals(listOf(EndReason.CALL_FAILED), s.alerts)
        val result = s.results.single()
        assertEquals(EndReason.CALL_FAILED, result.endReason)
        assertEquals(Phase.ENDED, s.phase)
    }

    @Test
    fun withCallSessionARefusalBeforeDialingEndsQuietly() {
        // Nothing was placed: no alert, one result
        val s = SessionRig()
        s.r.phone.inCall = true
        s.session.start()
        s.r.run()
        assertFalse(s.r.phone.actions.toString(), s.r.phone.actions.any { it.startsWith("place") })
        assertTrue(s.alerts.isEmpty())
        assertEquals(EndReason.CALL_FAILED, s.results.single().endReason)
    }

    @Test
    fun withCallSessionAChoiceThatKeepsTheMicrophoneOnNeverMutesTheCall() {
        // "手伝い役・direct、端末のマイクは止めない" (and the loudspeaker): Telecom's mute would stop the AI's voice too
        val s = SessionRig(CallSession.Config(muteMicDuringAi = false)).talking()
        s.live.audio()
        s.r.run()
        assertTrue(s.r.mutes.isEmpty())
        // Handing over only takes a mute off (none was on): never true
        s.r.change(s.r.placed, view(CallStatus.HOLDING))
        assertEquals(Phase.HANDED_OVER, s.phase)
        assertFalse(s.r.mutes.contains(true))
    }

    @Test
    fun withCallSessionTheAiHangsUpThePlacedCall() {
        val s = SessionRig().talking()
        s.live.tool(
            "report_result",
            """{"status":"BOOKED","start":"2026-11-14T19:00","partySize":2,"nameUsed":"ヤマダ",""" +
                """"confirmedPreferences":["禁煙席"],"summary":"予約できました"}""",
        )
        s.r.run()
        s.live.tool("end_call", """{"reason":"用件が済んだ"}""")
        s.r.run()
        assertEquals(listOf("disconnect"), s.r.placed.actions)
        s.r.change(s.r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        val result = s.results.single()
        assertEquals(EndReason.AI_HUNG_UP, result.endReason)
        assertEquals(OutcomeStatus.BOOKED, result.outcome.status)
        assertEquals(false, s.r.mutes.last())
        assertEquals("disable", s.r.phone.actions.last())
        assertNull(InCallHub.sink)
    }

    @Test
    fun withCallSessionAHoldHandsOverAndTheEndLeavesOneResult() {
        val s = SessionRig().talking()
        s.r.change(s.r.placed, view(CallStatus.HOLDING))
        assertEquals(Phase.HANDED_OVER, s.phase)
        assertEquals(listOf(EndReason.CALL_FAILED), s.alerts)
        assertEquals(false, s.r.mutes.last())
        assertTrue(s.live.cancelled)
        s.r.change(s.r.placed, view(CallStatus.DISCONNECTED, reason = "local"))
        val result = s.results.single()
        assertEquals(EndReason.CALL_FAILED, result.endReason)
        assertTrue(result.detail, result.detail.contains("保留になりました"))
        assertEquals("disable", s.r.phone.actions.last())
    }

    // --- the buttons of the call screen ---

    private val earpiece = CallAudio(muted = false, route = CallAudioState.ROUTE_EARPIECE)
    private val onSpeaker = CallAudio(muted = false, route = CallAudioState.ROUTE_SPEAKER)

    @Test
    fun beforeThePlacedCallIsFoundOnlyThePhoneScreenIsOffered() {
        val r = Rig().dial()
        val p = r.control.panel.value
        assertFalse(p.hangUpAvailable || p.micAvailable || p.speakerAvailable || p.keypadAvailable)
        assertTrue(p.openPhoneOffered)
    }

    @Test
    fun whileItRingsTheSpeakerAndTheHangUpWork() {
        val r = Rig().dial()
        r.add(r.placed, view(CallStatus.DIALING))
        r.control.onCallAudioChanged(earpiece)
        val p = r.control.panel.value
        assertTrue(p.speakerAvailable)
        assertTrue(p.hangUpAvailable)
        assertFalse(p.micAvailable)
        assertFalse(p.keypadAvailable)
        assertFalse(p.openPhoneOffered)
    }

    @Test
    fun onceAnsweredEveryButtonWorks() {
        val r = Rig().active()
        r.control.onCallAudioChanged(earpiece)
        val p = r.control.panel.value
        assertTrue(p.speakerAvailable && p.micAvailable && p.keypadAvailable && p.hangUpAvailable && p.watchesCall)
        assertFalse(p.speakerOn)
        assertFalse(p.micOff)
    }

    @Test
    fun theSpeakerGoesOnAndBackToWhereTheCallWas() {
        val r = Rig().active()
        r.control.onCallAudioChanged(CallAudio(muted = false, route = CallAudioState.ROUTE_WIRED_HEADSET))
        r.control.setSpeaker(true)
        r.run()
        assertEquals(listOf(CallAudioState.ROUTE_SPEAKER), r.routes)
        r.control.onCallAudioChanged(onSpeaker)
        assertTrue(r.control.panel.value.speakerOn)
        r.control.setSpeaker(false)
        r.run()
        assertEquals(listOf(CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_WIRED_HEADSET), r.routes)
    }

    @Test
    fun theSpeakerIsLeftToTelecomWhenTheCallEnds() {
        val r = Rig().active()
        r.control.onCallAudioChanged(onSpeaker)
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "remote"))
        assertTrue("the route of another call must not change", r.routes.isEmpty())
        assertFalse(r.control.panel.value.speakerAvailable)
    }

    @Test
    fun theMicrophoneStateComesFromTelecom() {
        val r = Rig().active()
        r.control.onCallAudioChanged(earpiece)
        r.control.setMuted(true)
        r.run()
        assertEquals(listOf(true), r.mutes)
        assertFalse("not before Telecom says so", r.control.panel.value.micOff)
        r.control.onCallAudioChanged(earpiece.copy(muted = true))
        assertTrue(r.control.panel.value.micOff)
    }

    @Test
    fun aHoldClosesThePanelAndLeavesThePhoneScreen() {
        val r = Rig().active()
        r.control.onCallAudioChanged(earpiece)
        r.change(r.placed, view(CallStatus.HOLDING))
        val p = r.control.panel.value
        assertFalse(p.hangUpAvailable || p.micAvailable || p.speakerAvailable || p.keypadAvailable)
        assertTrue(p.openPhoneOffered)
    }

    @Test
    fun theEndClosesThePanel() {
        val r = Rig().active()
        r.control.onCallAudioChanged(earpiece)
        r.change(r.placed, view(CallStatus.DISCONNECTED, reason = "remote"))
        val p = r.control.panel.value
        assertFalse(p.hangUpAvailable || p.micAvailable || p.speakerAvailable || p.keypadAvailable || p.openPhoneOffered)
    }

    @Test
    fun aKeyOfTheKeypadSendsOneTone() {
        val r = Rig().active()
        r.control.sendDtmf("5")
        r.scheduler.advance(1_000)
        assertEquals(listOf("play:5", "stop"), r.placed.actions)
    }

    @Test
    fun aHangUpFromTheScreenEndsThePlacedCall() {
        val r = Rig().active()
        r.control.userHangUp()
        r.run()
        assertEquals(listOf("disconnect"), r.placed.actions)
    }

    companion object {
        private const val T0 = 1_800_000_000_000L
    }
}
