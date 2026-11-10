// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.smoke

import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTransport
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the steps of [LiveSmokeTest] against a scripted fake of the Gemini Live server (no
 * network, no key), so the one run with the real key does not stumble over the test itself.
 */
class SmokeRunTest {
    private val key = "FAKE-KEY-0123456789"
    private val fast = SmokeTiming(
        totalMs = 30_000, closeReserveMs = 1_000, readyMs = 2_000, noAnswerMs = 600,
        turnMs = 3_000, quietMs = 150, silenceChunks = 5,
    )

    @Test
    fun everythingWorks() = withServer(FakeGemini()) { server ->
        val run = SmokeRun(key, "test-model", server, fast)
        val report = run.runAndReport()
        println(report)
        assertNull(report, run.problem())
        assertTrue(report, report.contains("結論：OK"))
        for (row in listOf(
            "| 1 | 接続（WebSocket） | OK |",
            "| 1 | setup → setupComplete（READY） | OK |",
            "| 2 | きっかけ → 最初の音声 | OK |",
            "| 2 | AI の音声のレート | OK |",
            "| 2 | AI の文字起こしに「AI」 | OK |",
            "| 4 | 店員の発言 → evaluate_offer | OK |",
            "| 4 | toolResponse のあと会話が続く | OK |",
            "| 4 | report_result | OK |",
            "| 4 | end_call | OK |",
            "| 5 | GoAway | 来なかった |",
            "| 5 | 閉じたとき | こちらから |",
        )) {
            assertTrue("$row\n$report", report.contains(row))
        }
        assertTrue(report, Regex("\\| 3 \\| 無音 [^|]+\\| OK \\|").containsMatchIn(report))
        assertEquals(1, server.setups.size)
        assertEquals(5, server.audioChunks.get())
        assertEquals(listOf("evaluate_offer", "report_result", "end_call"), server.toolResponses)
        assertFalse(report.contains(key))
    }

    @Test
    fun rejectedSetupIsRetriedWithoutLanguageCode() = withServer(
        FakeGemini(rejectSetup = { setup ->
            if (setup.toString().contains("languageCode")) "Unknown name \"languageCode\" (wss://x/y?key=$key)" else null
        })
    ) { server ->
        val run = SmokeRun(key, "test-model", server, fast)
        val report = run.runAndReport()
        println(report)
        assertEquals(2, server.setups.size)
        assertTrue(report, report.contains("1. agent-live のまま"))
        assertTrue(report, report.contains("code=1007"))
        assertTrue(report, report.contains("2. languageCode なし"))
        assertTrue(report, report.contains("| 4 | end_call | OK |"))
        assertTrue(run.problem().orEmpty(), run.problem().orEmpty().contains("通った形：languageCode なし"))
        assertFalse(report, report.contains(key))
        assertTrue(report, report.contains("<hidden>"))
    }

    @Test
    fun closedAfterClientContentReconnectsWithRealtimeText() = withServer(FakeGemini(closeOnClientContent = true)) { server ->
        val run = SmokeRun(key, "test-model", server, fast)
        val report = run.runAndReport()
        println(report)
        assertEquals(2, server.setups.size)
        assertTrue(report, report.contains("| 2 | きっかけ → 最初の音声 | OK |"))
        assertTrue(report, report.contains("| 4 | end_call | OK |"))
        assertTrue(run.problem().orEmpty(), run.problem().orEmpty().contains("通った形：テキストを realtimeInput.text で"))
        assertEquals(listOf("evaluate_offer", "report_result", "end_call"), server.toolResponses)
    }

    @Test
    fun ignoredClientContentFallsBackToRealtimeText() = withServer(FakeGemini(answerClientContent = false)) { server ->
        val run = SmokeRun(key, "test-model", server, fast)
        val report = run.runAndReport()
        println(report)
        assertTrue(report, report.contains("| 2 | きっかけ → 最初の音声 | OK |"))
        assertTrue(report, report.contains("realtimeInput.text"))
        assertTrue(run.problem().orEmpty(), run.problem().orEmpty().contains("clientContent に返事がありませんでした"))
        assertEquals(listOf("evaluate_offer", "report_result", "end_call"), server.toolResponses)
    }

    @Test
    fun refusedHandshakeStopsAtOnce() = withServer(
        FakeGemini(refuseHandshake = "ProtocolException: Expected HTTP 101 response but was '403 Forbidden' http=403 body=key=$key")
    ) { server ->
        val run = SmokeRun(key, "test-model", server, fast)
        val report = run.runAndReport()
        println(report)
        assertEquals(0, server.setups.size)
        assertTrue(report, report.contains("| 1 | 接続（WebSocket） | NG |"))
        assertTrue(run.problem().orEmpty(), run.problem().orEmpty().startsWith("setupComplete まで進みませんでした"))
        assertFalse(report, report.contains(key))
    }

    private fun withServer(server: FakeGemini, block: (FakeGemini) -> Unit) {
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}

/**
 * A scripted Gemini Live server. It answers on its own thread, like the network:
 * setup -> setupComplete; the first user text -> a greeting; then evaluate_offer, a line,
 * report_result -> end_call, and a closing line.
 */
private class FakeGemini(
    private val refuseHandshake: String? = null,
    private val rejectSetup: (JsonObject) -> String? = { null },
    private val answerClientContent: Boolean = true,
    /** Close with 1007 on any clientContent (a server that only takes realtimeInput.text). */
    private val closeOnClientContent: Boolean = false,
) : SmokeConnector {
    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-gemini").apply { isDaemon = true } }
    private val firstChoice =
        PracticePolicy.approved(LocalDate.now(SmokeRun.ZONE), Instant.now()).request.windows.first().earliestStart

    val setups = CopyOnWriteArrayList<JsonObject>()
    val toolResponses = CopyOnWriteArrayList<String>()
    val audioChunks = AtomicInteger()

    fun shutdown() {
        net.shutdownNow()
    }

    override fun connect(session: LiveSession, variant: Variant, hooks: SmokeSocket.Hooks): SmokeLink {
        val link = Link(session, variant, hooks)
        net.execute {
            if (refuseHandshake != null) {
                link.open = false
                hooks.failed(refuseHandshake, 403)
                session.onFailure(refuseHandshake)
            } else {
                hooks.opened()
                session.onOpen(link.transport)
            }
        }
        return link
    }

    private inner class Link(
        private val session: LiveSession,
        private val variant: Variant,
        private val hooks: SmokeSocket.Hooks,
    ) : SmokeLink {
        @Volatile
        var open = true
        private var turn = 0
        private var calls = 0

        val transport = object : LiveTransport {
            override fun send(text: String): Boolean {
                val out = variant.rewrite(text)
                hooks.sent(out, open)
                if (!open) return false
                net.execute { fromClient(out) }
                return true
            }

            override fun close(code: Int, reason: String) {
                this@Link.close()
            }
        }

        override fun sendRaw(message: JsonObject): Boolean {
            if (!open) return false
            val text = message.toString()
            net.execute { fromClient(text) }
            return true
        }

        override fun close(): Boolean {
            if (!open) return false
            open = false
            net.execute {
                hooks.closed(1000, "", fromServer = true)
                session.onClosed(1000, "")
            }
            return true
        }

        override fun cancel() {
            open = false
        }

        private fun fromClient(text: String) {
            if (!open) return
            val o = Json.parseToJsonElement(text).jsonObject
            when {
                "setup" in o -> {
                    setups += o
                    val reason = rejectSetup(o)
                    if (reason != null) closeByServer(1007, reason) else server("""{"setupComplete":{}}""")
                }
                "clientContent" in o -> when {
                    closeOnClientContent -> closeByServer(1007, "clientContent is not supported")
                    answerClientContent -> userTurn()
                }
                "realtimeInput" in o ->
                    if ("text" in o.getValue("realtimeInput").jsonObject) userTurn() else audioChunks.incrementAndGet()
                "toolResponse" in o -> {
                    val name = o.getValue("toolResponse").jsonObject.getValue("functionResponses").jsonArray
                        .first().jsonObject.getValue("name").jsonPrimitive.content
                    toolResponses += name
                    when (name) {
                        "evaluate_offer" -> speak("それでは、その内容でお願いいたします。")
                        "report_result" -> toolCall("end_call", """{"reason":"用件が済んだ"}""")
                        "end_call" -> speak("失礼いたします。")
                    }
                }
            }
        }

        private fun userTurn() {
            when (turn++) {
                0 -> speak("お忙しいところ失礼いたします。こちらはAIによる代理のお電話です。")
                1 -> toolCall("evaluate_offer", """{"start":"$firstChoice","partySize":2,"confirmedPreferences":["禁煙席"]}""")
                2 -> speak("予約名はヤマダ、連絡先は090-0000-0000です。")
                3 -> toolCall(
                    "report_result",
                    """{"status":"BOOKED","start":"$firstChoice","partySize":2,"nameUsed":"ヤマダ",""" +
                        """"confirmedPreferences":["禁煙席"],"summary":"予約できました"}""",
                )
                else -> speak("ありがとうございます。")
            }
        }

        private fun speak(text: String) {
            val pcm = Base64.getEncoder().encodeToString(ByteArray(4_800)) // 100 ms at 24 kHz
            server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}}}""")
            server("""{"serverContent":{"outputTranscription":{"text":"$text"}}}""")
            server("""{"serverContent":{"generationComplete":true}}""")
            server("""{"serverContent":{"turnComplete":true}}""")
        }

        private fun toolCall(name: String, args: String) {
            server("""{"toolCall":{"functionCalls":[{"id":"call-${++calls}","name":"$name","args":$args}]}}""")
        }

        private fun server(json: String) {
            if (!open) return
            hooks.received(json)
            session.onMessage(json)
        }

        private fun closeByServer(code: Int, reason: String) {
            open = false
            hooks.closed(code, reason, fromServer = true)
            session.onClosed(code, reason)
        }
    }
}
