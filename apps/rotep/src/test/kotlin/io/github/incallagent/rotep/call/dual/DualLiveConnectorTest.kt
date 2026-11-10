// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.CallActions
import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.call.AiDisclosure
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.call.FakeAudioPath
import io.github.incallagent.rotep.call.FakeCallControl
import io.github.incallagent.rotep.call.FakeScheduler
import io.github.incallagent.rotep.call.UserCue
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DualLiveConnectorTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val now: Instant = today.atTime(10, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant()
    private val policy = PracticePolicy.approved(today, now)
    private val disclosure = "こちらはAIによる代理のお電話です。"
    private val fallback = "その点は確認して、おりかえしご連絡いたします。"
    private val spoken = DualSpoken(disclosure, fallback, null)

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val offer = FunctionCall("c1", "evaluate_offer", args("""{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"]}"""))
    private val report = FunctionCall("c2", "report_result", args("""{"status":"UNAVAILABLE","summary":"空きがありません"}"""))
    private val endCall = FunctionCall("c3", "end_call", args("""{"reason":"済んだ"}"""))

    /** A whole [CallSession] on one hand-driven clock, with the connector in place of the Gemini connection. */
    private inner class Rig(earlyThink: Boolean = true, config: CallSession.Config = CallSession.Config()) {
        val scheduler = FakeScheduler()
        val audio = FakeAudioPath()
        val control = FakeCallControl()
        val brain = FakeBrain()
        val sockets = FakeVoiceSockets()
        val logs = ArrayList<String>()
        val session = CallSession(
            policy = policy,
            audio = audio,
            control = control,
            live = DualLiveConnector(LiveAuth.ApiKey("test-key"), brain, sockets, spoken, { logs += it }, scheduler, earlyThink = earlyThink),
            scheduler = scheduler,
            today = today,
            config = config,
            wallClock = { now },
        )

        val phase get() = session.state.value.phase
        val mouth get() = sockets.opened[0]
        val ear get() = sockets.opened[1]

        fun run() = scheduler.runUntilIdle()

        fun answered(): Rig {
            session.start()
            run()
            control.answer()
            run()
            assertEquals(Phase.TALKING, phase)
            return this
        }

        /** Both voice models are set up: the disclosure has gone to the mouth, the opening to the thinking model. */
        fun ready(): Rig {
            answered()
            mouth.open()
            ear.open()
            run()
            mouth.setupComplete()
            ear.setupComplete()
            run()
            assertTrue(session.state.value.aiReady)
            return this
        }

        /** The disclosure was said and has played out, and the opening answered with nothing to say. */
        fun talking(): Rig {
            ready()
            brain.answer(BrainOutput())
            finishDisclosure()
            scheduler.advance(DualLiveConnector.FIXED_LINE_MARGIN_MS + 200)
            return this
        }

        fun finishDisclosure() {
            mouth.audio()
            mouth.said(disclosure)
            mouth.turnComplete()
            run()
        }

        fun finishLine() {
            mouth.audio()
            mouth.turnComplete()
            run()
        }

        /** They said [text], and it is quiet: the question has gone out. */
        fun shopSays(text: String) {
            ear.final(text)
            run()
            scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        }

        fun answer(output: BrainOutput) {
            brain.answer(output)
            run()
        }

        fun line(text: String) {
            brain.line(text)
            run()
        }
    }

    // --- the start of the call ---

    @Test
    fun thereIsOneSetupCompleteAndOnlyWhenBothSidesHaveAnswered() {
        val r = Rig().answered()
        r.mouth.open()
        r.ear.open()
        r.run()
        assertEquals(1, r.mouth.sent.size)
        assertEquals(1, r.ear.sent.size)
        r.mouth.setupComplete()
        r.run()
        assertFalse("not with one side only", r.session.state.value.aiReady)
        r.ear.setupComplete()
        r.run()
        assertTrue(r.session.state.value.aiReady)
        val deadline = System.currentTimeMillis() + 2_000
        while (r.brain.warmUps.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, r.brain.warmUps.get())
    }

    @Test
    fun theDisclosureIsReadAtOnceAndTheOpeningThoughtAboutMeanwhile() {
        val r = Rig().ready()
        assertEquals(listOf(disclosure), r.mouth.spoken())
        assertEquals(listOf<BrainInput>(BrainInput.Opening), r.brain.inputs)
        assertFalse("the cue of the session is not read out", r.mouth.sent.any { "最初のあいさつ" in it })
    }

    @Test
    fun theLineOfTheOpeningComesAfterTheDisclosure() {
        val r = Rig().ready()
        r.answer(BrainOutput(say = "予約をお願いしたいのですが。"))
        assertEquals(listOf(disclosure), r.mouth.spoken())
        r.finishDisclosure()
        assertEquals(listOf(disclosure, "予約をお願いしたいのですが。"), r.mouth.spoken())
    }

    // --- what the other side says ---

    @Test
    fun theirVoiceGoesToTheEarsAndTheirWordsToTheScreen() {
        val r = Rig().talking()
        r.audio.downlink(byteArrayOf(1, 0, 2, 0))
        assertTrue(r.ear.sent.last().startsWith("{\"realtimeInput\""))
        assertFalse(r.mouth.sent.any { it.contains("realtimeInput") })
        r.ear.final("はい、お店です。")
        r.run()
        assertTrue(r.session.state.value.lines.any { it.who == Who.SHOP && it.text == "はい、お店です。" })
    }

    @Test
    fun whatTheySaidIsThoughtAboutOnceItIsQuiet() {
        val r = Rig().talking()
        val before = r.brain.inputs.size
        r.ear.final("はい、お店です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS - 1)
        assertEquals(before, r.brain.inputs.size)
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("はい、お店です。"), r.brain.inputs.last())
    }

    @Test
    fun wordsStillComingPutOffTheQuestion() {
        val r = Rig().talking()
        val before = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(300)
        r.ear.interim("あの")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS - 1)
        assertEquals("waits again", before, r.brain.inputs.size)
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("はい。あの"), r.brain.inputs.last())
    }

    @Test
    fun aNodStillComingDoesNotHoldBackTheQuestion() {
        val r = Rig().talking()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(300)
        r.ear.interim("はい")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(BrainInput.ShopSaid("二名です。"), r.brain.inputs.last())
    }

    @Test
    fun interimWordsWithNoFinalOnesAreShownAfterALongerWait() {
        val r = Rig().talking()
        r.ear.interim("二名")
        r.run()
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS - 1)
        assertFalse("the final words may still come", r.session.state.value.lines.any { it.who == Who.SHOP })
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("二名です"), r.brain.inputs.last())
        assertEquals("asked once", 1, r.brain.inputs.count { it is BrainInput.ShopSaid })
        assertTrue(r.session.state.value.lines.any { it.who == Who.SHOP && it.text == "二名です" })
    }

    @Test
    fun aNodIsNotThoughtAboutEarlyButIsTakenInTheEnd() {
        val r = Rig().talking()
        val before = r.brain.inputs.size
        r.ear.interim("はい")
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS - 1)
        assertEquals(before, r.brain.inputs.size)
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("はい"), r.brain.inputs.last())
    }

    @Test
    fun finalWordsInTimeAreTheOnesThatCount() {
        val r = Rig().talking()
        r.ear.interim("二名")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS - 1)
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS * 2)
        assertEquals(BrainInput.ShopSaid("二名です。"), r.brain.inputs.last())
        assertEquals(1, r.brain.inputs.count { it is BrainInput.ShopSaid })
    }

    // --- thinking before the final words ---

    private fun Rig.thoughtEarly(words: String = "二名です"): BrainInput {
        ear.interim(words)
        run()
        scheduler.advance(DualLiveConnector.EARLY_THINK_MS)
        assertEquals(BrainInput.ShopSaid(words), brain.inputs.last())
        return brain.inputs.last()
    }

    @Test
    fun interimWordsThatStopChangingAreThoughtAboutAtOnce() {
        val r = Rig().talking()
        val before = r.brain.inputs.size
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS - 1)
        assertEquals(before, r.brain.inputs.size)
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("二名です"), r.brain.inputs.last())
    }

    @Test
    fun theSameInterimWordsAgainDoNotPutItOff() {
        val r = Rig().talking()
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS - 1)
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("二名です"), r.brain.inputs.last())
    }

    @Test
    fun theSameFinalWordsLetTheThoughtGoOn() {
        val r = Rig().talking()
        val early = r.thoughtEarly()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertFalse(early in r.brain.cancelled)
        assertEquals("one question", early, r.brain.inputs.last())
        assertTrue(r.session.state.value.lines.any { it.who == Who.SHOP && it.text == "二名です。" })
        r.answer(BrainOutput(say = "承知しました。"))
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS * 2)
        assertEquals("nothing is left to answer", early, r.brain.inputs.last())
        assertEquals(listOf(disclosure, "承知しました。"), r.mouth.spoken())
    }

    @Test
    fun finalWordsAfterTheAnswerOfTheEarlyThoughtAreOnlyShown() {
        val r = Rig().talking()
        val early = r.thoughtEarly()
        r.answer(BrainOutput(say = "承知しました。"))
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(early, r.brain.inputs.last())
        assertEquals(1, r.brain.inputs.count { it is BrainInput.ShopSaid })
        assertTrue(r.session.state.value.lines.any { it.who == Who.SHOP && it.text == "二名です。" })
    }

    @Test
    fun finalWordsThatDifferStartTheQuestionOver() {
        val r = Rig().talking()
        val early = r.thoughtEarly("二名")
        r.ear.final("三名です。")
        r.run()
        assertTrue(early in r.brain.cancelled)
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS)
        assertEquals(BrainInput.ShopSaid("三名です。"), r.brain.inputs.last())
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS * 2)
        assertEquals("the interim words are gone", 2, r.brain.inputs.count { it is BrainInput.ShopSaid })
    }

    @Test
    fun moreWordsTakeTheEarlyThoughtBack() {
        val r = Rig().talking()
        val early = r.thoughtEarly("二名")
        val late = r.brain.last!!
        r.ear.interim("二名です")
        r.run()
        assertTrue(early in r.brain.cancelled)
        late(BrainOutput(say = "古い答え"))
        r.run()
        assertEquals(listOf(disclosure), r.mouth.spoken())
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS)
        assertEquals(BrainInput.ShopSaid("二名です"), r.brain.inputs.last())
        assertEquals("the log counts them", 2, r.logs.count { it == "early answer begun" })
        assertEquals(1, r.logs.count { it == "early answer taken back" })
    }

    @Test
    fun wordsAlreadyWaitingGoInWithTheEarlyOnes() {
        val r = Rig().talking()
        r.shopSays("はい。")
        val first = r.brain.inputs.last()
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS)
        assertTrue(first in r.brain.cancelled)
        assertEquals(BrainInput.ShopSaid("はい。二名です"), r.brain.inputs.last())
        r.ear.final("二名です。")
        r.run()
        r.answer(BrainOutput(say = "承知しました。"))
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS * 2)
        assertEquals("all of it was answered", 2, r.brain.inputs.count { it is BrainInput.ShopSaid })
    }

    @Test
    fun aLineCutOffGoesWithTheEarlyWords() {
        val r = Rig().talking()
        r.sayingALine()
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS)
        assertEquals(BrainInput.ShopSaid("ちょっと待って", "ご予約は十九時でよろしいでしょうか。"), r.brain.inputs.last())
    }

    @Test
    fun noEarlyThoughtWhileTheAiTalks() {
        val r = Rig().talking()
        r.sayingALine()
        val asked = r.brain.inputs.size
        r.ear.interim("え？")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS)
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun noEarlyThoughtWhileANoteIsAnswered() {
        val r = Rig().talking()
        r.session.userCue(UserCue.ASK_TO_WAIT)
        r.run()
        val asked = r.brain.inputs.size
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS)
        assertEquals(asked, r.brain.inputs.size)
        assertTrue(r.brain.inputs.last() is BrainInput.Note)
    }

    @Test
    fun noEarlyThoughtWhileTheAnswersOfToolsAreOpen() {
        val r = Rig().talking()
        r.shopSays("空いています。")
        r.answer(BrainOutput(calls = listOf(offer)))
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.EARLY_THINK_MS)
        assertEquals("only the one question before the tool", 1, r.brain.inputs.count { it is BrainInput.ShopSaid })
        assertEquals("c1", (r.brain.inputs.last() as BrainInput.ToolResults).results.single().id)
    }

    @Test
    fun theWaitCountsFromTheLastInterimWords() {
        val r = Rig().talking()
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - 1)
        assertEquals(listOf(disclosure), r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals(listOf(disclosure, DualLiveConnector.FILLER), r.mouth.spoken())
    }

    @Test
    fun theFinalWordsDoNotStartTheWaitAgain() {
        val r = Rig().talking()
        r.thoughtEarly()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - DualLiveConnector.EARLY_THINK_MS)
        assertEquals(listOf(disclosure, DualLiveConnector.FILLER), r.mouth.spoken())
    }

    @Test
    fun theLogSaysHowLongTheFinalWordsTook() {
        val r = Rig().talking()
        r.thoughtEarly()
        r.scheduler.advance(300)
        r.ear.final("二名です。")
        r.run()
        r.answer(BrainOutput(say = "承知しました。"))
        r.mouth.audio()
        r.run()
        val log = r.logs.joinToString("\n")
        assertTrue(log, log.contains("last interim -> final ${DualLiveConnector.EARLY_THINK_MS + 300} ms"))
        assertTrue(log, log.contains("answer ${DualLiveConnector.EARLY_THINK_MS + 300} ms"))
    }

    @Test
    fun finalWordsLaterThanTheInterimOnesAreNotCountedTwice() {
        val r = Rig().talking()
        r.ear.interim("二名です")
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS)
        val asked = r.brain.inputs.size
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(asked, r.brain.inputs.size)
        assertEquals("二名です", r.session.state.value.lines.single { it.who == Who.SHOP }.text)
    }

    @Test
    fun theSameWordsSaidAgainAreHeardAgain() {
        val r = Rig().talking()
        r.ear.interim("はい")
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS)
        r.answer(BrainOutput())
        r.ear.interim("はい")
        r.run()
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(BrainInput.ShopSaid("はい。"), r.brain.inputs.last())
    }

    @Test
    fun moreWordsWhileItThinksStartTheQuestionAgain() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        val first = r.brain.inputs.last()
        r.shopSays("禁煙でお願いします。")
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS - DualLiveConnector.SHOP_SETTLE_MS)
        assertTrue(first in r.brain.cancelled)
        assertEquals(BrainInput.ShopSaid("二名です。禁煙でお願いします。"), r.brain.inputs.last())
    }

    @Test
    fun anAnswerToAQuestionTakenBackIsDropped() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        val late = r.brain.last!!
        r.ear.final("やっぱり三名です。")
        r.run()
        late(BrainOutput(say = "古い答え"))
        r.run()
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    // --- while they wait ---

    @Test
    fun aSlowAnswerGetsAShortWordAndThenALongerOne() {
        val r = Rig().talking()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - 1)
        assertEquals(listOf(disclosure), r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals(listOf(disclosure, DualLiveConnector.FILLER), r.mouth.spoken())
        r.finishLine()
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS - DualLiveConnector.FILLER_AFTER_MS)
        assertEquals(listOf(disclosure, DualLiveConnector.FILLER, DualLiveConnector.LONG_WAIT), r.mouth.spoken())
        r.finishLine()
        r.scheduler.advance(20_000)
        assertEquals("each once", 3, r.mouth.spoken().size)
    }

    @Test
    fun aQuickAnswerNeedsNoShortWord() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.answer(BrainOutput(say = "ありがとうございます。"))
        r.scheduler.advance(10_000)
        assertEquals(listOf(disclosure, "ありがとうございます。"), r.mouth.spoken())
    }

    @Test
    fun noShortWordOverALineAlreadyGoingOut() {
        val r = Rig().ready()
        r.brain.answer(BrainOutput())
        r.run()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS)
        assertEquals("still reading the disclosure", listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun noShortWordWhileANoteIsAnsweredForTheSessionWouldTakeItForTheLastWords() {
        val r = Rig().talking()
        r.ear.final("二名です。")
        r.run()
        r.session.userCue(UserCue.DECLINE_AND_END)
        r.run()
        assertTrue(r.brain.inputs.last() is BrainInput.Note)
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals(listOf(disclosure), r.mouth.spoken())
        // The tool first, and only then the line the note asks for
        r.answer(BrainOutput(calls = listOf(report)))
        assertTrue(r.brain.inputs.last() is BrainInput.ToolResults)
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals(listOf(disclosure), r.mouth.spoken())
        assertFalse(r.control.calls.contains("hangUp"))
        r.answer(BrainOutput(say = "本日は見送らせてください。", calls = listOf(endCall)))
        assertEquals(listOf(disclosure, "本日は見送らせてください。"), r.mouth.spoken())
    }

    @Test
    fun noShortWordWhileANoteWaitsItsTurn() {
        val r = Rig().talking()
        r.shopSays("空いています。")
        r.answer(BrainOutput(calls = listOf(offer)))
        r.session.userCue(UserCue.DECLINE_AND_END)
        r.run()
        assertTrue("the answers of the tools come first", r.brain.inputs.last() is BrainInput.ToolResults)
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun aNoteAnsweredWithNothingLeavesTheShortWordsAsTheyWere() {
        val r = Rig().talking()
        r.session.userCue(UserCue.ASK_TO_WAIT)
        r.run()
        r.answer(BrainOutput())
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS)
        assertEquals(listOf(disclosure, DualLiveConnector.FILLER), r.mouth.spoken())
    }

    // --- what the thinking model decides ---

    @Test
    fun oneLineGoesToTheMouthAtATime() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "一つ目です。"))
        r.shopSays("二名です。")
        r.answer(BrainOutput(say = "二つ目です。"))
        assertEquals(listOf(disclosure, "一つ目です。"), r.mouth.spoken())
        r.finishLine()
        assertEquals(listOf(disclosure, "一つ目です。", "二つ目です。"), r.mouth.spoken())
    }

    @Test
    fun toolsRunThroughTheSessionAndTheirAnswersGoBack() {
        val r = Rig().talking()
        r.shopSays("19時でしたら空いています。")
        r.answer(BrainOutput(calls = listOf(offer)))
        val input = r.brain.inputs.last() as BrainInput.ToolResults
        assertEquals("c1", input.results.single().id)
        assertEquals(true, input.results.single().response.getValue("accept").jsonPrimitive.booleanOrNull)
        assertTrue("the session wrote it down", r.session.state.value.lines.any { it.who == Who.TOOL })
        assertFalse("nothing of it goes to the voice models", r.mouth.sent.any { "toolResponse" in it } || r.ear.sent.any { "toolResponse" in it })
    }

    @Test
    fun aHangUpWaitsForTheEndOfTheLineBeforeIt() {
        val r = Rig().talking()
        r.shopSays("はい、ではお待ちしております。")
        val asked = r.brain.inputs.size
        r.answer(BrainOutput(say = "ありがとうございました。", calls = listOf(report, endCall)))
        assertEquals(listOf(disclosure, "ありがとうございました。"), r.mouth.spoken())
        assertEquals("not before the voice", Phase.TALKING, r.phase)
        assertFalse(r.control.calls.contains("hangUp"))
        r.finishLine()
        assertTrue(r.control.calls.contains("hangUp"))
        assertEquals("no more questions after it", asked, r.brain.inputs.size)
        r.ear.final("失礼します。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals("nor when they say something", asked, r.brain.inputs.size)
    }

    @Test
    fun aHangUpRefusedByTheSessionGoesBackToTheThinkingModel() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(calls = listOf(endCall)))
        val input = r.brain.inputs.last() as BrainInput.ToolResults
        assertEquals(false, input.results.single().response.getValue("ok").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun whatTheySaidWhileAToolRanComesWithItsAnswer() {
        val r = Rig().talking()
        r.ear.final("空いています。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        // The first answer calls a tool; they are already speaking again
        r.ear.interim("あ")
        r.run()
        r.answer(BrainOutput(calls = listOf(offer)))
        r.ear.final("あ、すみません。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        val input = r.brain.inputs.last() as BrainInput.ToolResults
        assertEquals("あ、すみません。", input.shopSaid)
        assertEquals("c1", input.results.single().id)
    }

    // --- an answer a sentence at a time ---

    @Test
    fun eachSentenceGoesToTheMouthAsSoonAsItIsWhole() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.line("承知しました。")
        assertEquals(listOf(disclosure, "承知しました。"), r.mouth.spoken())
        r.line("お名前を伺えますか。")
        assertEquals("one at a time", 2, r.mouth.spoken().size)
        r.finishLine()
        assertEquals(listOf(disclosure, "承知しました。", "お名前を伺えますか。"), r.mouth.spoken())
        r.answer(BrainOutput(say = "お願いします"))
        assertEquals(3, r.mouth.spoken().size)
        r.finishLine()
        assertEquals("what was left comes last", "お願いします", r.mouth.spoken().last())
    }

    @Test
    fun theWaitEndsWithTheFirstSentenceAndIsTimedToIt() {
        val r = Rig().talking()
        r.ear.final("二名です。")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - 1)
        r.line("承知しました。")
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals("no short word after it", listOf(disclosure, "承知しました。"), r.mouth.spoken())
        r.mouth.audio()
        r.run()
        val log = r.logs.joinToString("\n")
        assertTrue(log, log.contains("answer ${DualLiveConnector.FILLER_AFTER_MS - 1} ms"))
    }

    @Test
    fun aSentenceOfAQuestionTakenBackIsDropped() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        val late = r.brain.lastLine!!
        r.ear.final("やっぱり三名です。")
        r.run()
        late("古い文です。")
        r.run()
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun aBlankSentenceIsNotRead() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.line(" ")
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun aHangUpWaitsForTheSentencesBeforeIt() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.line("本日はありがとうございました。")
        r.line("失礼いたします。")
        r.answer(BrainOutput(calls = listOf(report, endCall)))
        r.finishLine()
        assertFalse(r.control.calls.contains("hangUp"))
        r.finishLine()
        assertTrue(r.control.calls.contains("hangUp"))
    }

    @Test
    fun whatATurnTakenBackHadSaidGoesToTheNextOne() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.line("一つ目です。")
        r.finishLine()
        r.scheduler.advance(200)
        r.line("二つ目です。")
        r.mouth.audio()
        r.run()
        r.line("三つ目です。")
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
        r.shopSays("ちょっと待ってください。")
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS - DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(BrainInput.ShopSaid("はい。ちょっと待ってください。", "一つ目です。二つ目です。三つ目です。"), r.brain.inputs.last())
    }

    @Test
    fun aFewWordsDoNotTakeBackAnAnswerAlreadyGoingOut() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        val question = r.brain.inputs.last()
        r.line("承知しました。")
        r.shopSays("え？")
        assertFalse(question in r.brain.cancelled)
        r.line("お名前を伺えますか。")
        r.answer(BrainOutput())
        assertEquals("then what they said is answered", BrainInput.ShopSaid("え？"), r.brain.inputs.last())
    }

    @Test
    fun aSentenceThatStopsTheAnswerSaysWhatHadGoneOut() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        val question = r.brain.inputs.last()
        r.line("承知しました。")
        r.finishLine()
        r.scheduler.advance(200)
        r.shopSays("いえ、三名に変えてください。")
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS - DualLiveConnector.SHOP_SETTLE_MS)
        assertTrue(question in r.brain.cancelled)
        assertEquals(BrainInput.ShopSaid("二名です。いえ、三名に変えてください。", "承知しました。"), r.brain.inputs.last())
    }

    @Test
    fun theOpeningIsNotAskedAgainAfterItsFirstSentenceWentOut() {
        val r = Rig().ready()
        r.line("予約をお願いしたいのですが。")
        r.finishDisclosure()
        r.mouth.audio()
        r.run()
        r.ear.interim("あのですね")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(1, r.brain.inputs.count { it == BrainInput.Opening })
    }

    @Test
    fun theFixedLineComesAfterTheSentencesThatWentOut() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.line("一つ目です。")
        r.answer(BrainOutput(failed = BrainFailure.TIMEOUT))
        assertEquals(listOf(disclosure, "一つ目です。"), r.mouth.spoken())
        r.finishLine()
        assertEquals(listOf(disclosure, "一つ目です。", fallback), r.mouth.spoken())
    }

    // --- talking over the AI ---

    private fun Rig.sayingALine(line: String = "ご予約は十九時でよろしいでしょうか。") {
        shopSays("はい。")
        answer(BrainOutput(say = line))
        mouth.audio()
        run()
    }

    @Test
    fun talkingOverALineStopsItAndSaysWhatWasCut() {
        val r = Rig().talking()
        r.sayingALine()
        val played = r.audio.played.size
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
        r.mouth.audio()
        r.run()
        assertEquals("the rest of the line is dropped", played, r.audio.played.size)
        r.mouth.turnComplete()
        r.run()
        r.shopSays("ちょっと待ってください。")
        assertEquals(BrainInput.ShopSaid("ちょっと待ってください。", "ご予約は十九時でよろしいでしょうか。"), r.brain.inputs.last())
        r.answer(BrainOutput(say = "はい、お待ちします。"))
        assertEquals("the next line goes out", "はい、お待ちします。", r.mouth.spoken().last())
    }

    /** Two seconds of voice, which arrive at once: the mouth is done, and the phone is still playing them. */
    private fun Rig.lineOfTwoSecondsMade(line: String = "ご予約は十九時でよろしいでしょうか。") {
        shopSays("はい。")
        answer(BrainOutput(say = line))
        mouth.audio(96_000)
        mouth.turnComplete()
        run()
    }

    @Test
    fun aLineStillPlayingAfterItsEndCanBeTalkedOver() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade()
        r.scheduler.advance(1_999)
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
        r.shopSays("ちょっと待ってください。")
        assertEquals(BrainInput.ShopSaid("ちょっと待ってください。", "ご予約は十九時でよろしいでしょうか。"), r.brain.inputs.last())
    }

    @Test
    fun aLinePlayedToTheEndIsNotCut() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade()
        r.scheduler.advance(2_000)
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(0, r.audio.flushes)
        r.shopSays("ちょっと待ってください。")
        assertEquals(BrainInput.ShopSaid("ちょっと待ってください。"), r.brain.inputs.last())
    }

    @Test
    fun theVoiceOfALineComesAfterTheLineBeforeIt() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade("一つ目です。")
        r.shopSays("え？")
        r.answer(BrainOutput(say = "二つ目です。"))
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        // Made at once: the second is heard from 2 s to 4 s, from when the first was made
        r.scheduler.advance(3_000)
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
    }

    @Test
    fun aNodWhileALineIsStillPlayingNeedsNoAnswer() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade()
        val asked = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun noShortWordOverALineStillPlaying() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade()
        val before = r.mouth.spoken()
        r.ear.final("え？")
        r.run()
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS)
        assertEquals(before, r.mouth.spoken())
    }

    @Test
    fun theDisclosureStillPlayingIsNotCutThroughTheLineBehindIt() {
        val r = Rig().ready()
        r.answer(BrainOutput(say = "予約をお願いしたいのですが。"))
        r.mouth.audio(96_000)
        r.mouth.said(disclosure)
        r.mouth.turnComplete()
        r.run()
        assertEquals("the next line is being made", 2, r.mouth.spoken().size)
        r.scheduler.advance(1_000)
        r.ear.interim("お電話ありがとうございます")
        r.run()
        assertEquals(0, r.audio.flushes)
    }

    /** The disclosure, two seconds of it, made at once, and the line behind it going to the mouth. */
    private fun Rig.disclosureMadeWithALineBehindIt() {
        answer(BrainOutput(say = "予約をお願いしたいのですが。"))
        mouth.audio(96_000)
        mouth.said(disclosure)
        mouth.turnComplete()
        run()
        assertEquals("the next line is being made", 2, mouth.spoken().size)
    }

    @Test
    fun theEndOfTheDisclosureIsNotCutASecondAfterItsEstimatedEnd() {
        val r = Rig().ready()
        r.disclosureMadeWithALineBehindIt()
        r.scheduler.advance(3_000)
        r.ear.interim("お電話ありがとうございます")
        r.run()
        assertEquals(0, r.audio.flushes)
    }

    @Test
    fun aLineBehindTheDisclosureCanBeTalkedOverOnceTheDisclosureIsSurelyOver() {
        val r = Rig().ready()
        r.disclosureMadeWithALineBehindIt()
        r.scheduler.advance(2_000 + DualLiveConnector.FIXED_LINE_MARGIN_MS)
        r.ear.interim("ちょっと待ってください")
        r.run()
        assertEquals(1, r.audio.flushes)
    }

    @Test
    fun aLineOfTheAnswerHasNoSuchMargin() {
        val r = Rig().talking()
        r.lineOfTwoSecondsMade()
        r.scheduler.advance(2_000)
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(0, r.audio.flushes)
        r.shopSays("ちょっと待ってください。")
        assertEquals(BrainInput.ShopSaid("ちょっと待ってください。"), r.brain.inputs.last())
    }

    @Test
    fun wordsThatStoppedDoNotAddUpToATalkingOver() {
        val r = Rig().talking()
        r.sayingALine()
        for (piece in listOf("あ", "い", "う", "え")) {
            r.ear.interim(piece)
            r.run()
            r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        }
        assertEquals(0, r.audio.flushes)
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS)
        assertEquals("nor when they are taken as said", 0, r.audio.flushes)
    }

    @Test
    fun wordsThatCutALineAndWereNeverMadeFinalAreAnswered() {
        val r = Rig().talking()
        r.sayingALine()
        r.ear.interim("ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
        r.mouth.turnComplete()
        r.run()
        r.scheduler.advance(DualLiveConnector.INTERIM_ONLY_MS)
        assertEquals(BrainInput.ShopSaid("ちょっと待って", "ご予約は十九時でよろしいでしょうか。"), r.brain.inputs.last())
    }

    @Test
    fun thanksAndGoodbyesDoNotStopALineAsTheyAreWrittenOut() {
        val closing = listOf(
            "ありがとうございます。",
            "ありがとうございました。",
            "失礼します。",
            "失礼いたします。",
            "お願いします。",
            "よろしくお願いします。",
            "はい、ありがとうございました。失礼いたします。",
        )
        for (words in closing) {
            val r = Rig().talking()
            r.sayingALine()
            val asked = r.brain.inputs.size
            for (end in 1..words.length) {
                r.ear.interim(words.take(end))
                r.run()
            }
            r.ear.final(words)
            r.run()
            r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
            assertEquals(words, 0, r.audio.flushes)
            assertEquals(words, asked, r.brain.inputs.size)
        }
    }

    @Test
    fun aWordWrittenOutWithAPauseInItIsStillANod() {
        val r = Rig().talking()
        r.sayingALine()
        r.ear.interim("ありがとう")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        r.ear.interim("ございます")
        r.run()
        assertEquals(0, r.audio.flushes)
    }

    @Test
    fun aSentenceThatBeginsWithANodStillStopsALine() {
        val r = Rig().talking()
        r.sayingALine()
        r.ear.interim("はい、ちょっと待って")
        r.run()
        assertEquals(1, r.audio.flushes)
    }

    @Test
    fun thanksWhileTheAiIsQuietAreAnswered() {
        val r = Rig().talking()
        r.shopSays("お願いします。")
        assertEquals(BrainInput.ShopSaid("お願いします。"), r.brain.inputs.last())
    }

    @Test
    fun aNodDoesNotStopALineAndNeedsNoAnswer() {
        val r = Rig().talking()
        r.sayingALine()
        val asked = r.brain.inputs.size
        r.ear.interim("はい")
        r.run()
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(0, r.audio.flushes)
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun theDisclosureIsNotCut() {
        val r = Rig().ready()
        r.ear.interim("お電話ありがとうございます")
        r.run()
        assertEquals(0, r.audio.flushes)
        r.mouth.audio()
        r.run()
        assertEquals(1, r.audio.played.size)
    }

    @Test
    fun theLastLineIsNotCutAndTheHangUpStillComes() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "ありがとうございました。", calls = listOf(report, endCall)))
        r.mouth.audio()
        r.run()
        val asked = r.brain.inputs.size
        r.ear.interim("すみません、もう一つ")
        r.run()
        r.ear.final("すみません、もう一つ。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals("the goodbye goes on", 0, r.audio.flushes)
        assertEquals("no question while the hang-up is still open", asked, r.brain.inputs.size)
        r.mouth.turnComplete()
        r.run()
        assertTrue(r.control.calls.contains("hangUp"))
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun whatTheySaidBeforeARefusedHangUpIsNotLost() {
        val r = Rig().talking()
        r.shopSays("はい。")
        // Nothing was reported yet: the session will not take the hang-up
        r.answer(BrainOutput(say = "失礼します。", calls = listOf(endCall)))
        r.mouth.audio()
        r.run()
        r.ear.final("すみません、もう一つ。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        r.mouth.turnComplete()
        r.run()
        val input = r.brain.inputs.last() as BrainInput.ToolResults
        assertEquals(false, input.results.single().response.getValue("ok").jsonPrimitive.booleanOrNull)
        assertEquals("すみません、もう一つ。", input.shopSaid)
        assertFalse(r.control.calls.contains("hangUp"))
    }

    // --- not asking again at every small pause ---

    private val longerQuiet = DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS

    @Test
    fun afterAQuestionWasTakenBackTheQuietHasToLast() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.ear.final("禁煙でお願いします。")
        r.run()
        val asked = r.brain.inputs.size
        r.scheduler.advance(longerQuiet - 1)
        assertEquals(asked, r.brain.inputs.size)
        r.scheduler.advance(1)
        assertEquals(BrainInput.ShopSaid("二名です。禁煙でお願いします。"), r.brain.inputs.last())
    }

    @Test
    fun anAnswerBringsTheShortQuietBack() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.ear.final("禁煙でお願いします。")
        r.run()
        r.scheduler.advance(longerQuiet)
        r.answer(BrainOutput())
        // The short word that covered so long a wait
        r.finishLine()
        r.scheduler.advance(200)
        val asked = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(asked + 1, r.brain.inputs.size)
    }

    @Test
    fun aLongTalkIsNotAskedAboutAtEverySmallPause() {
        val r = Rig(earlyThink = false).talking()
        val before = r.brain.inputs.size
        repeat(30) {
            r.ear.final("ご用件をどうぞおっしゃってください。")
            r.run()
            r.scheduler.advance(400)
        }
        assertEquals("once, and taken back at the next words", 1, r.brain.inputs.size - before)
    }

    @Test
    fun aLongTalkWrittenAsInterimWordsIsNotAskedAboutEither() {
        val r = Rig(earlyThink = true).talking()
        val before = r.brain.inputs.size
        var words = ""
        repeat(30) {
            words += "ながいおはなしです"
            r.ear.interim(words)
            r.run()
            r.scheduler.advance(500)
        }
        assertEquals("once, and taken back at the next words", 1, r.brain.inputs.size - before)
    }

    @Test
    fun theOpeningIsNotAskedAgainAtEverySmallPause() {
        val r = Rig(earlyThink = false).ready()
        r.finishDisclosure()
        r.scheduler.advance(200)
        var words = ""
        repeat(30) {
            words += "ながいおはなしです"
            r.ear.interim(words)
            r.run()
            r.scheduler.advance(500)
        }
        assertEquals(1, r.brain.inputs.count { it == BrainInput.Opening })
        r.scheduler.advance(longerQuiet - 500)
        assertEquals("once it has stayed quiet", 2, r.brain.inputs.count { it == BrainInput.Opening })
    }

    @Test
    fun theLogSaysHowManyQuestionsWereTakenBack() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.ear.final("禁煙でお願いします。")
        r.run()
        r.scheduler.advance(longerQuiet)
        r.answer(BrainOutput(say = "承知しました。"))
        r.finishLine()
        r.mouth.audio()
        r.mouth.turnComplete()
        r.run()
        assertTrue(r.logs.joinToString("\n"), r.logs.last { "turn:" in it }.contains("taken back 1"))
        r.scheduler.advance(200)
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "ありがとうございます。"))
        r.mouth.audio()
        r.run()
        assertTrue(r.logs.joinToString("\n"), r.logs.last { "turn:" in it }.contains("taken back 0"))
    }

    // --- after the other side hung up ---

    private val asksForTheReport = CallSession.Config(lastReportWaitMs = 6_000)

    @Test
    fun theThinkingModelIsAskedForTheReportAfterTheCall() {
        val r = Rig(config = asksForTheReport).talking()
        r.control.end("remote")
        r.run()
        val note = r.brain.inputs.last() as BrainInput.Note
        assertTrue(note.text, note.text.contains("report_result"))
        r.answer(BrainOutput(say = "ありがとうございました。", calls = listOf(report)))
        assertEquals("nobody would hear it", listOf(disclosure), r.mouth.spoken())
        val result = r.session.state.value.result!!
        assertEquals(OutcomeStatus.UNAVAILABLE, result.outcome.status)
        assertTrue(result.reportedByAi)
        assertEquals(CallSession.EndReason.REMOTE_HUNG_UP, result.endReason)
    }

    @Test
    fun sentencesAfterTheCallAreNotRead() {
        val r = Rig(config = asksForTheReport).talking()
        r.control.end("remote")
        r.run()
        r.line("ありがとうございました。")
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun wordsNoAnswerHasSeenGoWithTheRequestForTheReport() {
        val r = Rig(config = asksForTheReport).talking()
        r.shopSays("十九時で承りました。")
        val question = r.brain.inputs.last()
        r.ear.interim("お待ちしております")
        r.run()
        r.control.end("remote")
        r.run()
        assertTrue(question in r.brain.cancelled)
        val note = r.brain.inputs.last() as BrainInput.Note
        assertTrue(note.text, note.text.contains("十九時で承りました。お待ちしております"))
    }

    @Test
    fun theAnswersOfToolsComeBeforeTheRequestForTheReport() {
        val r = Rig(config = asksForTheReport).talking()
        r.shopSays("空いています。")
        r.answer(BrainOutput(calls = listOf(offer)))
        assertTrue(r.brain.inputs.last() is BrainInput.ToolResults)
        r.control.end("remote")
        r.run()
        assertTrue(r.brain.inputs.last() is BrainInput.ToolResults)
        r.answer(BrainOutput())
        assertTrue(r.brain.inputs.last() is BrainInput.Note)
    }

    @Test
    fun nothingMoreIsAskedOnceTheReportIsMade() {
        val r = Rig(config = asksForTheReport).talking()
        r.control.end("remote")
        r.run()
        val asked = r.brain.inputs.size
        r.answer(BrainOutput(calls = listOf(report)))
        r.scheduler.advance(10_000)
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun aFailureAfterTheCallIsNotReadAndEndsTheWaitAtOnce() {
        val r = Rig(config = asksForTheReport).talking()
        r.control.end("remote")
        r.run()
        r.answer(BrainOutput(failed = BrainFailure.TIMEOUT))
        assertEquals(listOf(disclosure), r.mouth.spoken())
        val result = r.session.state.value.result!!
        assertEquals(OutcomeStatus.FAILED, result.outcome.status)
        assertEquals(CallSession.EndReason.REMOTE_HUNG_UP, result.endReason)
    }

    @Test
    fun noShortWordAfterTheCall() {
        val r = Rig(config = asksForTheReport).talking()
        r.ear.final("二名です。")
        r.run()
        r.control.end("remote")
        r.run()
        r.answer(BrainOutput())
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun whenTheUserHangsUpNothingIsAsked() {
        val r = Rig(config = asksForTheReport).talking()
        val asked = r.brain.inputs.size
        r.control.end("local")
        r.run()
        assertEquals(asked, r.brain.inputs.size)
        assertEquals(CallSession.EndReason.USER_HUNG_UP, r.session.state.value.result!!.endReason)
    }

    // --- from the app ---

    @Test
    fun aCueFromTheAppBecomesANote() {
        val r = Rig().talking()
        r.session.userCue(UserCue.ASK_TO_WAIT)
        r.run()
        val note = r.brain.inputs.last() as BrainInput.Note
        assertTrue(note.text, note.text.contains("本人からの指示"))
    }

    @Test
    fun aCueThatCannotBeReadIsRefusedSoThatTheCallIsHandedOver() {
        val scheduler = FakeScheduler()
        val sockets = FakeVoiceSockets()
        val live = LiveSession(
            policy,
            today,
            object : CallActions {
                override fun endCall(reason: String) {}

                override fun sendDtmf(digits: String) {}
            },
            object : LiveSession.Listener {},
        )
        val cue = AiDisclosure.cue(disclosure)
        val connection = DualLiveConnector(LiveAuth.ApiKey("test-key"), FakeBrain(), sockets, spoken, {}, scheduler).connect(live)
        scheduler.runUntilIdle()
        assertFalse("before it is ready", connection.send(cue))
        sockets.opened.forEach { it.open() }
        scheduler.runUntilIdle()
        sockets.opened.forEach { it.setupComplete() }
        scheduler.runUntilIdle()
        assertFalse(connection.send("not a cue"))
        assertTrue(connection.send(cue))
        connection.cancel()
        scheduler.runUntilIdle()
        assertFalse("after the end", connection.send(cue))
    }

    @Test
    fun aNoteWaitsForTheAnswersOfToolsToBeHeardFirst() {
        val r = Rig().talking()
        r.shopSays("空いています。")
        r.answer(BrainOutput(calls = listOf(offer)))
        assertTrue(r.brain.inputs.last() is BrainInput.ToolResults)
        r.session.userCue(UserCue.ASK_TO_WAIT)
        r.run()
        assertTrue("the answers are still open", r.brain.inputs.last() is BrainInput.ToolResults)
        r.answer(BrainOutput())
        assertTrue(r.brain.inputs.last() is BrainInput.Note)
    }

    // --- the watch over the call ---

    @Test
    fun aThinkingModelThatNeverAnswersEndsWithTheFixedLine() {
        val r = Rig().talking()
        r.shopSays("はい。")
        val question = r.brain.inputs.last()
        // The two short words while it waits, which the mouth says as ever
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - DualLiveConnector.SHOP_SETTLE_MS)
        r.finishLine()
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS - DualLiveConnector.FILLER_AFTER_MS)
        r.finishLine()
        r.scheduler.advance(DualLiveConnector.BRAIN_MAX_MS + DualLiveConnector.SHOP_SETTLE_MS - DualLiveConnector.LONG_WAIT_MS - 1)
        assertFalse(fallback in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals(fallback, r.mouth.spoken().last())
        assertTrue(question in r.brain.cancelled)
        r.brain.last!!(BrainOutput(say = "遅れた答え"))
        r.run()
        assertFalse("too late", "遅れた答え" in r.mouth.spoken())
        r.finishLine()
        r.scheduler.advance(DualLiveConnector.FIXED_LINE_MARGIN_MS + 200)
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(r.mouth.cancelled)
        assertTrue(r.ear.cancelled)
    }

    @Test
    fun anAnswerInTimeLeavesTheWatchWithNothingToDo() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "承知しました。"))
        r.finishLine()
        r.scheduler.advance(DualLiveConnector.BRAIN_MAX_MS * 2)
        assertFalse(fallback in r.mouth.spoken())
        assertEquals(Phase.TALKING, r.phase)
    }

    @Test
    fun aQuestionTakenBackIsNotWatchedAnyMore() {
        val r = Rig(earlyThink = false).talking()
        r.shopSays("二名です。")
        r.ear.final("禁煙でお願いします。")
        r.run()
        r.scheduler.advance(DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS)
        // The watch of the first question passes here: the second one has a second more
        r.scheduler.advance(DualLiveConnector.BRAIN_MAX_MS - 1)
        assertFalse(fallback in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals(fallback, r.mouth.spoken().last())
    }

    @Test
    fun nothingMoreIsSaidOrAskedAfterAFailure() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(failed = BrainFailure.TIMEOUT))
        val asked = r.brain.inputs.size
        r.ear.final("もしもし？")
        r.run()
        r.scheduler.advance(DualLiveConnector.LONG_WAIT_MS)
        assertEquals(asked, r.brain.inputs.size)
        assertEquals(listOf(disclosure, fallback), r.mouth.spoken())
    }

    @Test
    fun aVoiceNoLineAskedForIsNotPlayed() {
        val r = Rig().talking()
        val played = r.audio.played.size
        r.mouth.audio()
        r.run()
        assertEquals(played, r.audio.played.size)
    }

    @Test
    fun theWordsOfALineThatComeAfterItsEndStillCount() {
        val r = Rig().ready()
        r.answer(BrainOutput())
        r.mouth.audio()
        r.mouth.turnComplete()
        r.run()
        r.mouth.said(disclosure)
        r.run()
        assertTrue(r.session.state.value.lines.any { it.who == Who.AI && disclosure in it.text })
    }

    @Test
    fun theCallEndsAtItsLimitWhateverTheThinkingModelIsDoing() {
        val limit = policy.conversation.maxCallSeconds * 1000L
        val r = Rig().talking()
        r.scheduler.advance(limit - 5_000)
        r.shopSays("はい。")
        r.scheduler.advance(5_000)
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(r.mouth.cancelled)
        assertTrue(r.ear.cancelled)
        r.control.end("local")
        r.run()
        assertEquals(CallSession.EndReason.TIMEOUT, r.session.state.value.result!!.endReason)
    }

    @Test
    fun aCallNobodyIsAtEndsAtItsLimitEvenIfTheLastLineNeverComes() {
        val limit = policy.conversation.maxCallSeconds * 1000L
        val r = Rig(config = CallSession.Config(handOverKeepsCall = false, unattended = true)).talking()
        r.scheduler.advance(limit)
        assertTrue("asked for its last line", r.brain.inputs.last() is BrainInput.Note)
        assertFalse(r.control.calls.contains("hangUp"))
        r.scheduler.advance(CallSession.Config().closingWordsMaxMs)
        assertTrue(r.control.calls.contains("hangUp"))
    }

    // --- a line whose end does not come ---

    private val waitForTheEnd = DualLiveConnector.LINE_END_WAIT_MS

    /** Three sentences of one answer: the first going out with two seconds of voice, made at once, and no end from the mouth. */
    private fun Rig.lineWhoseEndDoesNotCome() {
        shopSays("はい。")
        line("一つ目です。")
        line("二つ目です。")
        line("三つ目です。")
        mouth.audio(96_000)
        run()
    }

    /** The first of those has been given up on, and the second has gone to the mouth in its place. */
    private fun Rig.firstLineGivenUp() {
        lineWhoseEndDoesNotCome()
        scheduler.advance(2_000 + waitForTheEnd)
        assertEquals("二つ目です。", mouth.spoken().last())
    }

    @Test
    fun aLineWhoseEndNeverComesIsOverSoonAfterItsVoiceStops() {
        val r = Rig().talking()
        r.lineWhoseEndDoesNotCome()
        r.scheduler.advance(2_000 + waitForTheEnd - 1)
        assertEquals("the voice is playing, and then the short wait", listOf(disclosure, "一つ目です。"), r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals(listOf(disclosure, "一つ目です。", "二つ目です。"), r.mouth.spoken())
    }

    @Test
    fun voiceThatGoesOnPutsOffTheEndOfTheLine() {
        val r = Rig().talking()
        r.lineWhoseEndDoesNotCome()
        r.scheduler.advance(1_000)
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(3_000 + waitForTheEnd - 1)
        assertFalse("二つ目です。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("二つ目です。", r.mouth.spoken().last())
    }

    /** As in a real call: the short word had its voice, the mouth never said it ended, and the answer waited behind it. */
    @Test
    fun theAnswerBehindAShortWordThatNeverEndsStillGoesOut() {
        val r = Rig().talking()
        r.shopSays("二名です。")
        r.scheduler.advance(DualLiveConnector.FILLER_AFTER_MS - DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(DualLiveConnector.FILLER, r.mouth.spoken().last())
        r.mouth.audio(24_000)
        r.run()
        r.line("承知しました。")
        assertFalse("承知しました。" in r.mouth.spoken())
        r.scheduler.advance(500 + waitForTheEnd)
        assertEquals("承知しました。", r.mouth.spoken().last())
    }

    @Test
    fun theSessionIsToldThatALineGivenUpOnHasEnded() {
        val r = Rig().ready()
        r.brain.answer(BrainOutput())
        // No words of the AI in it: it is the end of this turn that makes the session ask for them
        r.mouth.audio()
        r.run()
        r.scheduler.advance(100 + DualLiveConnector.FIXED_LINE_MARGIN_MS + waitForTheEnd + CallSession.Config().disclosureGraceMs)
        assertTrue(r.brain.inputs.last() is BrainInput.Note)
    }

    @Test
    fun aHangUpDoesNotWaitLongForAGoodbyeWhoseEndNeverComes() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "ありがとうございました。", calls = listOf(report, endCall)))
        r.mouth.audio()
        r.run()
        assertFalse(r.control.calls.contains("hangUp"))
        r.scheduler.advance(100 + waitForTheEnd)
        assertTrue(r.control.calls.contains("hangUp"))
    }

    @Test
    fun aLineThatEndedIsNotGivenUpOnLater() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.line("一つ目です。")
        r.line("二つ目です。")
        r.line("三つ目です。")
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        assertEquals("二つ目です。", r.mouth.spoken().last())
        r.scheduler.advance(10_000)
        assertEquals("the watch of the first has not ended the second", "二つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun aLineWithNoVoiceYetHasTheWholeWatch() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.line("一つ目です。")
        r.line("二つ目です。")
        r.scheduler.advance(DualLiveConnector.LINE_MAX_MS - 1)
        assertFalse("the model is only late", "二つ目です。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("二つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun aNodAfterTheVoiceHasStoppedIsAnAnswerEvenIfTheLineHasNotEnded() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "十九時でよろしいでしょうか。"))
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(2_500)
        val asked = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(BrainInput.ShopSaid("はい。"), r.brain.inputs.drop(asked).single())
    }

    @Test
    fun aNodWhileTheVoiceStillPlaysNeedsNoAnswerBeforeTheLineHasEnded() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "十九時でよろしいでしょうか。"))
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(1_500)
        val asked = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(asked, r.brain.inputs.size)
    }

    // A line talked over

    /** They talked over a line that had its voice, and the answer to them is waiting for that line to end. */
    private fun Rig.talkedOverWithTheAnswerWaiting() {
        sayingALine()
        ear.interim("ちょっと待って")
        run()
        assertEquals(1, audio.flushes)
        shopSays("ちょっと待ってください。")
        answer(BrainOutput(say = "はい、お待ちします。"))
        assertFalse("behind the line being cut", "はい、お待ちします。" in mouth.spoken())
    }

    @Test
    fun aLineTalkedOverWhoseEndNeverComesIsNotWaitedForLong() {
        val r = Rig().talking()
        r.talkedOverWithTheAnswerWaiting()
        r.scheduler.advance(waitForTheEnd - DualLiveConnector.SHOP_SETTLE_MS - 1)
        assertFalse("はい、お待ちします。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("はい、お待ちします。", r.mouth.spoken().last())
    }

    @Test
    fun voiceOfALineTalkedOverPutsOffTheEndOfTheWait() {
        val r = Rig().talking()
        r.talkedOverWithTheAnswerWaiting()
        r.scheduler.advance(1_000)
        val played = r.audio.played.size
        r.mouth.audio()
        r.run()
        assertEquals("still dropped", played, r.audio.played.size)
        r.scheduler.advance(waitForTheEnd - 1)
        assertFalse("はい、お待ちします。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("はい、お待ちします。", r.mouth.spoken().last())
    }

    /** They talked over a line before any of its voice came. */
    private fun Rig.talkedOverBeforeAnyVoice() {
        shopSays("はい。")
        answer(BrainOutput(say = "承知しました。"))
        ear.interim("ちょっと待って")
        run()
        assertEquals(1, audio.flushes)
        shopSays("ちょっと待ってください。")
        answer(BrainOutput(say = "はい、お待ちします。"))
    }

    @Test
    fun aLineTalkedOverBeforeAnyVoiceHasTheWholeWatch() {
        val r = Rig().talking()
        r.talkedOverBeforeAnyVoice()
        r.scheduler.advance(DualLiveConnector.LINE_MAX_MS - DualLiveConnector.SHOP_SETTLE_MS - 1)
        assertFalse("はい、お待ちします。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("はい、お待ちします。", r.mouth.spoken().last())
    }

    @Test
    fun aLineTalkedOverBeforeItsVoiceIsNotWaitedForLongOnceTheVoiceHasCome() {
        val r = Rig().talking()
        r.talkedOverBeforeAnyVoice()
        r.mouth.audio()
        r.run()
        r.scheduler.advance(waitForTheEnd - 1)
        assertFalse("はい、お待ちします。" in r.mouth.spoken())
        r.scheduler.advance(1)
        assertEquals("はい、お待ちします。", r.mouth.spoken().last())
    }

    // What comes late, after a line was given up on

    @Test
    fun anEndThatComesLateDoesNotEndTheLineAfterIt() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        r.mouth.turnComplete()
        r.run()
        assertEquals("二つ目です。", r.mouth.spoken().last())
        val played = r.audio.played.size
        r.mouth.audio()
        r.run()
        assertEquals("the second line is still going out", played + 1, r.audio.played.size)
        r.mouth.turnComplete()
        r.run()
        assertEquals("its own end", "三つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun aLineThatGotOnlyAnEndIsNotWaitedForLong() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        // An end and no voice: the late end of the first line, or the second one's own, with nothing in it
        r.mouth.turnComplete()
        r.run()
        r.scheduler.advance(DualLiveConnector.ONLY_AN_END_MS - 1)
        assertEquals("its voice may yet come", "二つ目です。", r.mouth.spoken().last())
        r.scheduler.advance(1)
        assertEquals("三つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun anEndThatComesLateWithNothingGoingOutIsForgotten() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = "承知しました。"))
        r.mouth.audio()
        r.run()
        r.scheduler.advance(100 + waitForTheEnd)
        r.mouth.turnComplete()
        r.run()
        r.shopSays("二名です。")
        r.line("一つ目です。")
        r.line("二つ目です。")
        r.mouth.audio()
        r.mouth.turnComplete()
        r.run()
        assertEquals("the end of the next line counts as ever", "二つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun voiceThatComesLateDoesNotEndTheLineAfterIt() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        // The rest of the first line, and the second one's own voice can take over two seconds to start
        r.mouth.audio()
        r.run()
        r.scheduler.advance(3_000)
        assertEquals("二つ目です。", r.mouth.spoken().last())
        val played = r.audio.played.size
        r.mouth.audio(96_000)
        r.run()
        assertEquals("its voice is played", played + 1, r.audio.played.size)
    }

    @Test
    fun anEndThatNeverCameCostsOneShortWaitAndNoMore() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        // This end is the second line's own, and looks like the late one of the first
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        assertEquals("二つ目です。", r.mouth.spoken().last())
        r.scheduler.advance(DualLiveConnector.AFTER_GIVING_UP_MS)
        assertEquals("over all the same", "三つ目です。", r.mouth.spoken().last())
        r.line("四つ目です。")
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        assertEquals("the ends count again", "四つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun manyEndsThatNeverCameStillCostOneShortWait() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(DualLiveConnector.AFTER_GIVING_UP_MS)
        assertEquals("no end for the second either", "三つ目です。", r.mouth.spoken().last())
        r.line("四つ目です。")
        r.line("五つ目です。")
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        assertEquals("三つ目です。", r.mouth.spoken().last())
        r.scheduler.advance(DualLiveConnector.AFTER_GIVING_UP_MS)
        assertEquals("四つ目です。", r.mouth.spoken().last())
        r.mouth.audio(96_000)
        r.mouth.turnComplete()
        r.run()
        assertEquals("the ends count again", "五つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun voiceAfterALateEndMeansThatEndWasNotTheLinesOwn() {
        val r = Rig().talking()
        r.firstLineGivenUp()
        // What was left of the first line, and its end
        r.mouth.audio()
        r.mouth.turnComplete()
        r.run()
        // The second line's own voice, and no end for it
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(2_100 + waitForTheEnd)
        assertEquals("三つ目です。", r.mouth.spoken().last())
        r.line("四つ目です。")
        // The end of the second, late
        r.mouth.turnComplete()
        r.run()
        assertEquals("the third is still going out", "三つ目です。", r.mouth.spoken().last())
    }

    @Test
    fun theWordsThatComeWithALateEndStillCount() {
        val r = Rig().ready()
        r.answer(BrainOutput())
        r.mouth.audio()
        r.run()
        r.scheduler.advance(100 + DualLiveConnector.FIXED_LINE_MARGIN_MS + waitForTheEnd)
        r.mouth.server("""{"serverContent":{"outputTranscription":{"text":"$disclosure"},"turnComplete":true}}""")
        r.run()
        assertTrue(r.session.state.value.lines.any { it.who == Who.AI && disclosure in it.text })
    }

    // The fixed lines

    @Test
    fun aNodInTheLastOfADisclosureWhoseEndHasNotComeNeedsNoAnswer() {
        val r = Rig().ready()
        r.brain.answer(BrainOutput())
        r.mouth.audio(96_000)
        r.mouth.said(disclosure)
        r.run()
        r.scheduler.advance(2_500)
        val asked = r.brain.inputs.size
        r.ear.final("はい。")
        r.run()
        r.scheduler.advance(DualLiveConnector.SHOP_SETTLE_MS)
        assertEquals(asked, r.brain.inputs.size)
    }

    @Test
    fun theDisclosureIsHeardToTheEndEvenIfItsEndNeverComes() {
        val r = Rig().ready()
        r.answer(BrainOutput(say = "予約をお願いしたいのですが。"))
        r.mouth.audio(96_000)
        r.mouth.said(disclosure)
        r.run()
        r.scheduler.advance(2_000 + DualLiveConnector.FIXED_LINE_MARGIN_MS + waitForTheEnd - 1)
        assertEquals("not before it has surely been heard", listOf(disclosure), r.mouth.spoken())
        r.ear.interim("お電話ありがとうございます")
        r.run()
        assertEquals("and it is not cut", 0, r.audio.flushes)
        r.scheduler.advance(1)
        assertEquals(listOf(disclosure, "予約をお願いしたいのですが。"), r.mouth.spoken())
    }

    @Test
    fun theFixedLineOfAFailureIsHeardToTheEndEvenIfItsEndNeverComes() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(failed = BrainFailure.TIMEOUT))
        assertEquals(fallback, r.mouth.spoken().last())
        r.mouth.audio(96_000)
        r.run()
        r.scheduler.advance(2_000 + DualLiveConnector.FIXED_LINE_MARGIN_MS + waitForTheEnd - 1)
        assertEquals(Phase.TALKING, r.phase)
        r.scheduler.advance(1)
        assertEquals(Phase.HANDED_OVER, r.phase)
    }

    // --- when it goes wrong, and the end ---

    @Test
    fun aFailedAnswerReadsTheFixedLineAndThenHandsOver() {
        val r = Rig().ready()
        r.brain.answer(BrainOutput(failed = BrainFailure.TIMEOUT))
        r.finishDisclosure()
        assertEquals(listOf(disclosure, fallback), r.mouth.spoken())
        assertEquals("while it is being said", Phase.TALKING, r.phase)
        r.finishLine()
        assertEquals("while it is still playing", Phase.TALKING, r.phase)
        r.scheduler.advance(200)
        r.run()
        assertEquals("its end may still be in the track", Phase.TALKING, r.phase)
        r.scheduler.advance(DualLiveConnector.FIXED_LINE_MARGIN_MS)
        r.run()
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(r.mouth.cancelled)
        assertTrue(r.ear.cancelled)
    }

    @Test
    fun aBlankLineIsNotRead() {
        val r = Rig().talking()
        r.shopSays("はい。")
        r.answer(BrainOutput(say = " "))
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun aToolThatThrowsIsAnsweredAsNotDoneAndTheCallGoesOn() {
        val scheduler = FakeScheduler()
        val sockets = FakeVoiceSockets()
        val brain = FakeBrain()
        val logs = ArrayList<String>()
        val live = LiveSession(
            policy,
            today,
            object : CallActions {
                override fun endCall(reason: String) {}

                override fun sendDtmf(digits: String) {}
            },
            object : LiveSession.Listener {
                override fun onToolCall(call: FunctionCall, response: FunctionResponse): Unit = throw IllegalStateException("a bug")
            },
        )
        DualLiveConnector(LiveAuth.ApiKey("test-key"), brain, sockets, spoken, { logs += it }, scheduler, earlyThink = true).connect(live)
        scheduler.runUntilIdle()
        sockets.opened.forEach { it.open() }
        scheduler.runUntilIdle()
        sockets.opened.forEach { it.setupComplete() }
        scheduler.runUntilIdle()
        brain.answer(BrainOutput(calls = listOf(offer)))
        scheduler.runUntilIdle()
        val input = brain.inputs.last() as BrainInput.ToolResults
        assertEquals("c1", input.results.single().id)
        assertEquals(false, input.results.single().response.getValue("ok").jsonPrimitive.booleanOrNull)
        assertFalse(sockets.opened[0].cancelled)
        assertTrue(logs.toString(), logs.contains("tool failed: IllegalStateException"))
    }

    @Test
    fun aStepThatThrowsHandsTheCallOver() {
        val r = Rig().talking()
        r.brain.thinkFailure = IllegalStateException("a bug")
        r.shopSays("はい。")
        r.run()
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(r.mouth.cancelled)
        assertTrue(r.logs.contains("step failed: IllegalStateException"))
    }

    @Test
    fun takingOverStopsAllOfIt() {
        val r = Rig().ready()
        r.session.takeOver()
        r.run()
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(BrainInput.Opening in r.brain.cancelled)
        assertTrue(r.mouth.cancelled)
        assertTrue(r.ear.cancelled)
        r.brain.last!!(BrainOutput(say = "もう遅い"))
        r.run()
        assertEquals(listOf(disclosure), r.mouth.spoken())
    }

    @Test
    fun aVoiceSocketThatFailsEndsTheAiPart() {
        val r = Rig().talking()
        r.ear.fail("no connection")
        r.run()
        assertEquals(Phase.HANDED_OVER, r.phase)
        assertTrue(r.mouth.cancelled)
    }

    @Test
    fun theLogSaysWhichSideClosedButNotTheWordsOfTheServer() {
        val r = Rig().talking()
        r.ear.closed(1008, "words of the server about the model")
        r.run()
        val log = r.logs.joinToString("\n")
        assertTrue(log, log.contains("ear closed code=1008"))
        assertFalse(log, log.contains("words of the server"))
        assertEquals(Phase.HANDED_OVER, r.phase)
    }

    @Test
    fun theMouthIsToldApartFromTheEars() {
        val r = Rig().answered()
        r.mouth.fail("ProtocolException: refused http=403 body=words of the server")
        r.run()
        val log = r.logs.joinToString("\n")
        assertTrue(log, log.contains("mouth failed ProtocolException http=403"))
        assertFalse(log, log.contains("words of the server"))
    }

    @Test
    fun eachSideSaysWhenItIsReady() {
        val r = Rig().answered()
        r.mouth.open()
        r.ear.open()
        r.run()
        r.mouth.setupComplete()
        r.run()
        assertTrue(r.logs.contains("mouth ready"))
        assertFalse("the one that does not answer shows", r.logs.contains("ear ready"))
    }

    @Test
    fun whatItDoesNotKnowIsLeftAlone() {
        val r = Rig().talking()
        val asked = r.brain.inputs.size
        r.ear.server("""{"somethingNew":{"text":"x"}}""")
        r.ear.server("not json")
        r.mouth.server("""{"serverContent":{"somethingNew":true}}""")
        r.ear.server("""{"serverContent":{"modelTurn":{"parts":[{"text":"耳が勝手に答えた"}]},"turnComplete":true}}""")
        r.run()
        assertEquals(Phase.TALKING, r.phase)
        assertEquals(asked, r.brain.inputs.size)
        assertFalse(r.session.state.value.lines.any { it.text.contains("耳が勝手に答えた") })
    }

    @Test
    fun theLogHoldsNumbersAndStatesOnly() {
        val r = Rig().talking()
        r.sayingALine("予約名はテストでお願いします。")
        r.ear.interim("ちょっと待って")
        r.run()
        r.brain.inputs.size
        val log = r.logs.joinToString("\n")
        for (words in listOf("はい", "テスト", "ちょっと", disclosure, "test-key")) assertFalse(words, log.contains(words))
        assertTrue(log, log.contains("voice"))
    }

    @Test
    fun aCallThroughIsReportedAsTheSessionAlwaysDid() {
        val r = Rig().talking()
        r.shopSays("満席です。")
        r.answer(BrainOutput(say = "承知しました。", calls = listOf(report, endCall)))
        r.finishLine()
        r.control.end("local")
        r.run()
        val result = r.session.state.value.result!!
        assertEquals(OutcomeStatus.UNAVAILABLE, result.outcome.status)
        assertTrue(result.reportedByAi)
        assertEquals(CallSession.EndReason.AI_HUNG_UP, result.endReason)
    }
}
