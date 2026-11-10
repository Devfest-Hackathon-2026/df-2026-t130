// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.smoke

import io.github.incallagent.agent.live.CallActions
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in every build, without a key or the internet: the transport of [LiveSmokeTest] works on
 * a PC JVM (OkHttp's Android artifact falls back to its JVM platform, TLS can be set up, and a
 * refused connection is a normal connection failure, not a crash), and the message changes of
 * [Variant] do what they say.
 */
class SmokeSocketTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val now = today.atTime(9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant()
    private val policy = PracticePolicy.approved(today, now)

    private val noActions = object : CallActions {
        override fun endCall(reason: String) {}
        override fun sendDtmf(digits: String) {}
    }

    @Test
    fun refusedConnectionIsAConnectionFailure() {
        // A port that was free a moment ago: nothing listens there
        val port = ServerSocket(0).use { it.localPort }
        val (client, platform) = SmokeSocket.newClient()
        try {
            // On a PC (android.os.Build.VERSION.SDK_INT == 0) OkHttp's Android artifact uses its JVM platform
            assertTrue("OkHttp platform on a PC: $platform", platform == "Jdk9Platform" || platform == "Platform")
            assertNotNull("TLS must be available", client.sslSocketFactory)

            val states = CopyOnWriteArrayList<LiveSession.State>()
            val failure = AtomicReference<String>()
            val failed = CountDownLatch(1)
            val stateFailed = CountDownLatch(1)
            val session = LiveSession(policy, today, noActions, object : LiveSession.Listener {
                override fun onState(state: LiveSession.State, detail: String) {
                    states += state
                    if (state == LiveSession.State.FAILED) stateFailed.countDown()
                }
            })
            SmokeSocket.connect(client, "ws://127.0.0.1:$port", session, Variant(), Secrets("unused-secret"),
                object : SmokeSocket.Hooks {
                    override fun failed(detail: String, httpCode: Int?) {
                        failure.set(detail)
                        failed.countDown()
                    }
                })
            assertTrue("no failure reported within 15 s", failed.await(15, TimeUnit.SECONDS))
            // SmokeSocket tells the hook first and the session right after: wait for the session itself
            assertTrue("the session did not reach FAILED within 5 s", stateFailed.await(5, TimeUnit.SECONDS))
            val detail = failure.get()
            assertTrue(detail, detail.contains("ConnectException") || detail.contains("Failed to connect"))
            assertEquals(LiveSession.State.FAILED, session.state)
            assertEquals(listOf(LiveSession.State.FAILED), states.toList())
        } finally {
            SmokeSocket.shutdown(client)
        }
    }

    @Test
    fun secretsAreRemovedAsGivenAndUrlEncoded() {
        val s = Secrets("FAKE+key/0123=")
        val text = "wss://host/path?key=FAKE%2Bkey%2F0123%3D failed; raw FAKE+key/0123= end"
        val cleaned = s.clean(text)
        assertFalse(cleaned, cleaned.contains("FAKE"))
        assertEquals("wss://host/path?key=<hidden> failed; raw <hidden> end", cleaned)
        assertEquals("", s.clean(null))
    }

    @Test
    fun defaultVariantSendsMessagesUnchanged() {
        val setup = LiveMessages.setup(policy, today).toString()
        assertEquals(setup, Variant().rewrite(setup))
        val cue = LiveMessages.openingCue().toString()
        assertEquals(cue, Variant().rewrite(cue))
    }

    @Test
    fun setupWithoutLanguageCode() {
        val out = parse(Variant(languageCode = false).rewrite(LiveMessages.setup(policy, today).toString()))
        val setup = out.getValue("setup").jsonObject
        assertFalse(out.toString(), out.toString().contains("languageCode"))
        val gen = setup.getValue("generationConfig").jsonObject
        assertEquals("[\"AUDIO\"]", gen.getValue("responseModalities").toString())
        assertFalse(gen.containsKey("speechConfig"))
        // Everything else is kept
        for (k in listOf("model", "systemInstruction", "tools", "inputAudioTranscription", "outputAudioTranscription")) {
            assertTrue(k, setup.containsKey(k))
        }
    }

    @Test
    fun setupWithModalitiesAtTheTop() {
        val v = Variant(languageCode = false, modalitiesAtTop = true)
        val setup = parse(v.rewrite(LiveMessages.setup(policy, today).toString())).getValue("setup").jsonObject
        assertEquals("[\"AUDIO\"]", setup.getValue("responseModalities").toString())
        assertFalse(setup.containsKey("generationConfig"))
        assertTrue(setup.getValue("model").jsonPrimitive.content.startsWith("models/"))
    }

    @Test
    fun clientContentAsRealtimeText() {
        val v = Variant(textAsRealtimeInput = true)
        val cue = LiveMessages.openingCue()
        val out = parse(v.rewrite(cue.toString()))
        assertEquals(Variant.textOf(cue), out.getValue("realtimeInput").jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(Variant.textOf(cue).isNotBlank())

        val line = Variant.clientContentText("テスト")
        assertEquals("テスト", Variant.textOf(line))
        assertEquals(cue.keys, line.keys)
        // Other messages are never touched
        val audio = LiveMessages.realtimeAudio(ByteArray(4)).toString()
        assertEquals(audio, v.rewrite(audio))
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
