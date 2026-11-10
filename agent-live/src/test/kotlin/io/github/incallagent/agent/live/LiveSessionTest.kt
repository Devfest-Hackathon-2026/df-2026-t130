// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveSessionTest {
    private class FakeTransport : LiveTransport {
        val sent = ArrayList<JsonObject>()
        var closed: Int? = null
        override fun send(text: String): Boolean {
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }
        override fun close(code: Int, reason: String) { closed = code }
    }

    private class Recorder : LiveSession.Listener {
        val states = ArrayList<LiveSession.State>()
        val events = ArrayList<ServerEvent>()
        val toolCalls = ArrayList<String>()
        override fun onState(state: LiveSession.State, detail: String) { states += state }
        override fun onEvent(event: ServerEvent) { events += event }
        override fun onToolCall(call: FunctionCall, response: FunctionResponse) { toolCalls += call.name }
    }

    private val actions = object : CallActions {
        override fun endCall(reason: String) {}
        override fun sendDtmf(digits: String) {}
    }

    private fun newSession(rec: Recorder) = LiveSession(TestPolicy.example(), TestPolicy.today, actions, rec)

    @Test
    fun setupThenCueThenAudio() {
        val rec = Recorder()
        val s = newSession(rec)
        val t = FakeTransport()

        s.onOpen(t)
        assertEquals(listOf("setup"), t.sent.map { it.keys.single() })
        assertFalse("audio must wait for setupComplete", s.sendAudio(ByteArray(640)))

        s.onMessage("""{"setupComplete":{}}""")
        assertEquals(LiveSession.State.READY, s.state)
        assertEquals("clientContent", t.sent[1].keys.single())

        assertTrue(s.sendAudio(byteArrayOf(1, 0, 2, 0)))
        val audio = t.sent[2]["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals(listOf<Byte>(1, 0, 2, 0), Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content).toList())
    }

    @Test
    fun serverContentBecomesEvents() {
        val rec = Recorder()
        val s = newSession(rec)
        s.onOpen(FakeTransport())
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7, 8))
        s.onMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]},
                "outputTranscription":{"text":"こちらは"}}}"""
        )
        s.onMessage("""{"serverContent":{"interrupted":true}}""")
        s.onMessage("""{"serverContent":{"inputTranscription":{"text":"はい"},"turnComplete":true}}""")

        val audio = rec.events.filterIsInstance<ServerEvent.Audio>().single()
        assertEquals(24_000, audio.sampleRate)
        assertEquals(listOf<Byte>(5, 6, 7, 8), audio.pcm.toList())
        assertTrue(ServerEvent.Interrupted in rec.events)
        assertTrue(ServerEvent.TurnComplete in rec.events)
        assertEquals(ServerEvent.OutputTranscript("こちらは"), rec.events.filterIsInstance<ServerEvent.OutputTranscript>().single())
        assertEquals(ServerEvent.InputTranscript("はい"), rec.events.filterIsInstance<ServerEvent.InputTranscript>().single())
    }

    @Test
    fun aBrokenAudioChunkIsDroppedAndTheRestOfTheMessageStillCounts() {
        val rec = Recorder()
        val s = newSession(rec)
        s.onOpen(FakeTransport())
        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        val broken = """{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"%%not base64%%"}}"""
        val good = """{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}"""
        s.onMessage("""{"serverContent":{"modelTurn":{"parts":[$broken,$good]},"turnComplete":true}}""")
        assertEquals(listOf<Byte>(1, 2), rec.events.filterIsInstance<ServerEvent.Audio>().single().pcm.toList())
        assertTrue(ServerEvent.TurnComplete in rec.events)
    }

    @Test
    fun toolCallsAreAnsweredAutomatically() {
        val rec = Recorder()
        val s = newSession(rec)
        val t = FakeTransport()
        s.onOpen(t)
        s.onMessage("""{"toolCall":{"functionCalls":[{"id":"x1","name":"evaluate_offer",
            "args":{"start":"2026-11-14T19:00","partySize":2,"confirmedPreferences":["禁煙席"]}}]}}""")

        assertEquals(listOf("evaluate_offer"), rec.toolCalls)
        val reply = t.sent.last()["toolResponse"]!!.jsonObject["functionResponses"]!!
        assertTrue(reply.toString(), reply.toString().contains("\"id\":\"x1\""))
        assertTrue(reply.toString(), reply.toString().contains("\"accept\":true"))
    }

    @Test
    fun goAwayAndCancellation() {
        val rec = Recorder()
        val s = newSession(rec)
        s.onOpen(FakeTransport())
        s.onMessage("""{"goAway":{"timeLeft":"10s"}}""")
        s.onMessage("""{"toolCallCancellation":{"ids":["a","b"]}}""")
        assertTrue(ServerEvent.GoAway("10s") in rec.events)
        assertTrue(ServerEvent.ToolCallCancellation(listOf("a", "b")) in rec.events)
    }

    @Test
    fun closeBeforeOpenStaysClosed() {
        val rec = Recorder()
        val s = newSession(rec)
        s.close()
        assertEquals(LiveSession.State.CLOSED, s.state)

        // The handshake finishes after the app gave up on the call
        val t = FakeTransport()
        s.onOpen(t)
        assertTrue("nothing is sent", t.sent.isEmpty())
        assertEquals(1000, t.closed)
        assertEquals(LiveSession.State.CLOSED, s.state)
        assertEquals(listOf(LiveSession.State.CLOSED), rec.states)
    }

    @Test
    fun closeAndFailureAreReportedOnce() {
        val rec = Recorder()
        val s = newSession(rec)
        val t = FakeTransport()
        s.onOpen(t)
        s.close()
        s.onClosed(1000, "again")
        s.onFailure("late")
        assertEquals(1000, t.closed)
        assertEquals(listOf(LiveSession.State.SETTING_UP, LiveSession.State.CLOSED), rec.states)
        assertFalse(s.sendAudio(ByteArray(2)))
    }

    private fun lateSetupCompleteIsDropped(expected: LiveSession.State, finish: (LiveSession) -> Unit) {
        val rec = Recorder()
        val s = newSession(rec)
        val t = FakeTransport()
        s.onOpen(t)
        finish(s)

        s.onMessage("""{"setupComplete":{}}""")

        assertEquals(expected, s.state)
        assertEquals(listOf(LiveSession.State.SETTING_UP, expected), rec.states)
        assertTrue("no events", rec.events.isEmpty())
        assertEquals("no opening cue", listOf("setup"), t.sent.map { it.keys.single() })
    }

    @Test
    fun aLateSetupCompleteDoesNotReviveAClosedSession() =
        lateSetupCompleteIsDropped(LiveSession.State.CLOSED) { it.close() }

    @Test
    fun aLateSetupCompleteDoesNotReviveAFailedSession() =
        lateSetupCompleteIsDropped(LiveSession.State.FAILED) { it.onFailure("lost") }

    @Test
    fun aRepeatedSetupCompleteDoesNotRepeatTheOpeningCue() {
        val rec = Recorder()
        val s = newSession(rec)
        val t = FakeTransport()
        s.onOpen(t)
        s.onMessage("""{"setupComplete":{}}""")
        s.onMessage("""{"setupComplete":{}}""")
        assertEquals(listOf(LiveSession.State.SETTING_UP, LiveSession.State.READY), rec.states)
        assertEquals(listOf("setup", "clientContent"), t.sent.map { it.keys.single() })
    }

    @Test
    fun garbageIsIgnored() {
        val rec = Recorder()
        val s = newSession(rec)
        s.onOpen(FakeTransport())
        s.onMessage("not json")
        assertTrue(rec.events.isEmpty())
    }

    @Test
    fun endpointAndSecrets() {
        val url = LiveEndpoint.url(LiveAuth.ApiKey(" AIzaTEST "))
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="))
        assertTrue(url.endsWith("key=AIzaTEST"))
        assertTrue(LiveEndpoint.url(LiveAuth.EphemeralToken("tok")).contains("BidiGenerateContentConstrained?access_token=tok"))
        assertFalse(LiveAuth.ApiKey("AIzaTEST").toString().contains("AIza"))
    }

    @Test
    fun rateFromMimeType() {
        assertEquals(24_000, ServerEvents.rateOf("audio/pcm;rate=24000"))
        assertEquals(16_000, ServerEvents.rateOf("audio/pcm;rate=16000"))
        assertEquals(ServerEvents.DEFAULT_OUTPUT_RATE, ServerEvents.rateOf("audio/pcm"))
    }
}
