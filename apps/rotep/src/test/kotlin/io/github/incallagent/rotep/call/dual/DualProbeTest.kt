// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.AudioPathEvent
import io.github.incallagent.rotep.call.FakeAudioPath
import io.github.incallagent.rotep.call.FakeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DualProbeTest {
    private class FakeVoice(val listener: VoiceSocket.Listener) : VoiceSocket {
        val sent = ArrayList<String>()
        var cancels = 0

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun cancel() {
            cancels++
        }
    }

    private val opened = ArrayList<FakeVoice>()
    private val clock = FakeScheduler()
    private val audio = FakeAudioPath()
    private val logged = ArrayList<String>()
    private var state = DualProbeState()
    private val probe = DualProbe(
        VoiceSockets { _, listener -> FakeVoice(listener).also { opened += it } },
        LiveAuth.ApiKey("key"),
        "Kore",
        audio,
        clock,
        { logged += it },
        { state = it },
    )

    private val ear get() = opened[0]
    private val mouth get() = opened[1]
    private val result get() = state.result
    private val theLine = VoiceSetup.speak(DualProbe.PROBE_LINE)

    private fun started() {
        probe.start()
        clock.runUntilIdle()
    }

    private fun say(voice: FakeVoice, message: String) {
        voice.listener.onMessage(message)
        clock.runUntilIdle()
    }

    private fun ready(voice: FakeVoice) = say(voice, """{"setupComplete":{}}""")

    private fun closes(voice: FakeVoice, code: Int, reason: String = "server words") {
        voice.listener.onClosed(code, reason)
        clock.runUntilIdle()
    }

    private fun speech(bytes: Int): String {
        val data = Base64.getEncoder().encodeToString(ByteArray(bytes))
        return """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}}]}}}"""
    }

    private fun readOut(text: String) = """{"serverContent":{"outputTranscription":{"text":"$text"}}}"""

    private fun interim(text: String) = """{"serverContent":{"interimInputTranscription":{"text":"$text"}}}"""

    private fun final(text: String) = """{"serverContent":{"inputTranscription":{"text":"$text"}}}"""

    private val turnComplete = """{"serverContent":{"turnComplete":true}}"""

    private fun speaking() {
        started()
        ready(ear)
        ready(mouth)
    }

    private fun listening() {
        speaking()
        say(mouth, turnComplete)
    }

    private fun microphone() {
        audio.downlink(ByteArray(640))
        clock.runUntilIdle()
    }

    private fun audioSentToTheEars() = ear.sent.count { "\"audio\":" in it }

    private fun assertAllClosedOnce() {
        assertEquals(1, ear.cancels)
        assertEquals(1, mouth.cancels)
        assertEquals(1, audio.stops)
        assertEquals(1, logged.size)
    }

    @Test
    fun `each socket is set up with its own first message`() {
        started()
        assertEquals(2, opened.size)
        ear.listener.onOpen()
        mouth.listener.onOpen()
        clock.runUntilIdle()
        assertEquals(listOf(VoiceSetup.ear()), ear.sent)
        assertEquals(listOf(VoiceSetup.mouth("Kore")), mouth.sent)
    }

    @Test
    fun `each socket keeps its own setup time`() {
        started()
        clock.advance(120)
        ready(mouth)
        clock.advance(80)
        ready(ear)
        assertEquals(120L, result.mouthSetupMs)
        assertEquals(200L, result.earSetupMs)
        assertNull(result.earEnd)
        assertNull(result.mouthEnd)
    }

    @Test
    fun `the line goes out once both sockets are ready and the audio has started`() {
        started()
        ready(mouth)
        assertEquals(0, audio.starts)
        assertFalse(theLine in mouth.sent)

        ready(ear)
        assertEquals(1, audio.starts)
        assertEquals(theLine, mouth.sent.last())
        assertEquals(DualProbePhase.SPEAKING, state.phase)
    }

    @Test
    fun `the first audio and the length of the audio are measured and it is played`() {
        speaking()
        clock.advance(700)
        say(mouth, speech(48_000))
        clock.advance(100)
        say(mouth, speech(24_000))
        assertEquals(700L, result.firstAudioMs)
        assertEquals(1.5, result.audioSeconds, 0.001)
        assertEquals(listOf(48_000, 24_000), audio.played.map { it.size })
    }

    @Test
    fun `a line read as it was sent is a full match`() {
        speaking()
        say(mouth, readOut("にめいで、"))
        say(mouth, readOut("じゅうくじからお願いできますでしょうか。"))
        say(mouth, turnComplete)
        assertEquals(100, result.spokenMatchPercent)
    }

    @Test
    fun `a line read differently is not a full match`() {
        speaking()
        say(mouth, readOut("さんめいで、じゅうはちじからお願いします"))
        say(mouth, turnComplete)
        val percent = result.spokenMatchPercent!!
        assertTrue("$percent", percent in 1..99)
    }

    @Test
    fun `without the words of the line there is no match to give`() {
        listening()
        assertNull(result.spokenMatchPercent)
    }

    @Test
    fun `listening waits until the line has been played out`() {
        speaking()
        audio.playing = true
        say(mouth, turnComplete)
        clock.advance(500)
        assertEquals(DualProbePhase.SPEAKING, state.phase)

        audio.playing = false
        clock.advance(DualProbe.QUIET_POLL_MS)
        assertEquals(DualProbePhase.LISTENING, state.phase)
    }

    @Test
    fun `a line that never stops playing does not hold up the ears`() {
        speaking()
        audio.playing = true
        say(mouth, turnComplete)
        clock.advance(DualProbe.PLAY_OUT_TIMEOUT_MS + DualProbe.QUIET_POLL_MS)
        assertEquals(DualProbePhase.LISTENING, state.phase)
    }

    @Test
    fun `the microphone goes to the ears only while listening`() {
        speaking()
        microphone()
        assertEquals(0, audioSentToTheEars())

        say(mouth, turnComplete)
        microphone()
        assertEquals(1, audioSentToTheEars())

        clock.advance(DualProbe.LISTEN_MS)
        microphone()
        assertEquals(1, audioSentToTheEars())
    }

    @Test
    fun `the first words and the final characters are counted`() {
        listening()
        clock.advance(1_500)
        say(ear, interim("にめいで"))
        clock.advance(500)
        say(ear, interim("にめいでじゅうくじ"))
        say(ear, final("二名で十九時"))
        say(ear, final("から"))
        assertEquals(1_500L, result.firstInterimMs)
        assertEquals(8, result.finalChars)
    }

    @Test
    fun `what is being said is on the screen only while the try runs`() {
        listening()
        say(ear, interim("にめいで"))
        assertEquals("にめいで", state.liveText)
        say(ear, final("二名で"))
        assertEquals("二名で", state.liveText)
        say(ear, interim("じゅう"))
        assertEquals("二名でじゅう", state.liveText)

        clock.advance(DualProbe.LISTEN_MS + DualProbe.LAST_TEXT_MS)
        assertEquals(DualProbePhase.DONE, state.phase)
        assertEquals("", state.liveText)
    }

    @Test
    fun `what was said stays out of the log and of the text form`() {
        val words = "ひみつのことば"
        speaking()
        say(mouth, readOut(words))
        say(mouth, turnComplete)
        say(ear, interim(words))
        val whileRunning = state.toString()
        say(ear, final(words))
        closes(ear, 1008, words)

        assertEquals(1, logged.size)
        assertFalse(logged.single(), words in logged.single())
        assertFalse(whileRunning, words in whileRunning)
        assertFalse(result.toString(), words in result.toString())
    }

    @Test
    fun `after the listening the ears are told the audio ended and the last words still count`() {
        listening()
        clock.advance(DualProbe.LISTEN_MS)
        assertEquals(LiveMessages.audioStreamEnd().toString(), ear.sent.last())
        assertEquals(DualProbePhase.WAITING_FOR_TEXT, state.phase)

        say(ear, final("あい"))
        assertEquals(2, result.finalChars)
        assertEquals(0, audio.stops)

        clock.advance(DualProbe.LAST_TEXT_MS)
        assertEquals(DualProbePhase.DONE, state.phase)
        assertAllClosedOnce()
    }

    @Test
    fun `ears that are refused do not stop the mouth`() {
        started()
        ready(mouth)
        closes(ear, 1008)
        assertEquals(UserMessages.liveLog("closed code=1008 reason=server words"), result.earEnd)
        assertFalse(result.earEnd!!, "server words" in result.earEnd!!)
        assertNull(result.earSetupMs)
        assertEquals(theLine, mouth.sent.last())

        say(mouth, turnComplete)
        assertEquals(DualProbePhase.DONE, state.phase)
    }

    @Test
    fun `a mouth that is refused does not stop the ears`() {
        val detail = "ProtocolException: refused http=403 body=server words"
        started()
        ready(ear)
        mouth.listener.onFailure(detail)
        clock.runUntilIdle()
        assertEquals(UserMessages.liveLog(detail), result.mouthEnd)
        assertFalse(result.mouthEnd!!, "server words" in result.mouthEnd!!)
        assertEquals(1, audio.starts)
        assertEquals(DualProbePhase.LISTENING, state.phase)
    }

    @Test
    fun `with neither socket the audio is not started and the try ends`() {
        started()
        closes(ear, 1008)
        closes(mouth, 1008)
        assertEquals(DualProbePhase.DONE, state.phase)
        assertEquals(0, audio.starts)
        assertEquals(0, audio.stops)
        assertEquals(1, logged.size)
    }

    @Test
    fun `a socket that never answers is given up and the other goes on`() {
        started()
        ready(mouth)
        clock.advance(DualProbe.SETUP_TIMEOUT_MS)
        assertEquals(DualProbe.TIMED_OUT, result.earEnd)
        assertEquals(1, ear.cancels)
        assertNull(result.mouthEnd)
        assertEquals(0, mouth.cancels)
        assertEquals(DualProbePhase.SPEAKING, state.phase)
    }

    @Test
    fun `a mouth that stays silent is given up and the ears are still tried`() {
        speaking()
        clock.advance(DualProbe.SPEECH_TIMEOUT_MS)
        assertEquals(DualProbe.TIMED_OUT, result.mouthEnd)
        assertNull(result.firstAudioMs)
        assertEquals(DualProbePhase.LISTENING, state.phase)
    }

    @Test
    fun `a mouth that closes in the middle of the line still lets the ears be tried`() {
        speaking()
        closes(mouth, 1011)
        assertEquals(DualProbePhase.LISTENING, state.phase)
    }

    @Test
    fun `ears that close while listening end the try`() {
        listening()
        closes(ear, 1011)
        assertEquals(DualProbePhase.DONE, state.phase)
    }

    @Test
    fun `audio that breaks ends the try and says why`() {
        speaking()
        audio.event(AudioPathEvent.Failed("マイクが使えません"))
        clock.runUntilIdle()
        assertEquals("マイクが使えません", result.audioProblem)
        assertEquals(DualProbePhase.DONE, state.phase)
        assertAllClosedOnce()
    }

    @Test
    fun `cancel closes everything once and nothing goes on afterwards`() {
        speaking()
        probe.cancel()
        probe.cancel()
        clock.runUntilIdle()
        assertAllClosedOnce()

        clock.advance(DualProbe.SPEECH_TIMEOUT_MS + DualProbe.LISTEN_MS + DualProbe.LAST_TEXT_MS)
        say(mouth, readOut("おそい"))
        closes(ear, 1011)
        assertEquals(DualProbePhase.DONE, state.phase)
        assertEquals("", state.liveText)
        assertAllClosedOnce()
    }

    @Test
    fun `the match looks at what is said and not how it is written`() {
        assertEquals(100, DualProbe.matchPercent("にめいで、お願い。", "にめいで お願い"))
        assertEquals(100, DualProbe.matchPercent("にめいで", "ニメイデ"))
        assertEquals(100, DualProbe.matchPercent("１９", "19"))
        val close = DualProbe.matchPercent("にめいで", "さんめいで")
        assertTrue("$close", close in 1..99)
        assertEquals(0, DualProbe.matchPercent("にめいで", ""))
        assertEquals(0, DualProbe.matchPercent("にめいで", "、。"))
    }

    @Test
    fun `the numbers are one line in the log`() {
        val found = DualProbeResult(
            earSetupMs = 300,
            mouthEnd = "code=1008 other",
            firstAudioMs = 700,
            audioSeconds = 4.2,
            spokenMatchPercent = 80,
            firstInterimMs = 1_200,
            finalChars = 9,
        )
        assertEquals(
            "probe earSetupMs=300 earEnd=- mouthSetupMs=- mouthEnd=code=1008 other firstAudioMs=700 " +
                "audioSeconds=4.2 spokenMatchPercent=80 firstInterimMs=1200 finalChars=9 audioProblem=-",
            found.logLine(),
        )
    }

    @Test
    fun `the card says what is missing`() {
        val lines = DualProbeResult(earSetupMs = 300, mouthEnd = "code=1008 other", audioProblem = "マイクが使えません").screenLines()
        assertTrue(lines.toString(), "耳の準備: 300 ms" in lines)
        assertTrue(lines.toString(), "口の準備: なし（閉じた: code=1008 other）" in lines)
        assertTrue(lines.toString(), "読み上げの一致: なし" in lines)
        assertTrue(lines.toString(), "マイクとスピーカー: マイクが使えません" in lines)
    }
}
