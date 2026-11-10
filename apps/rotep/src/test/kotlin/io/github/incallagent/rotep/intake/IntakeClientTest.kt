// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IntakeClientTest {
    private val requests = ArrayList<Request>()
    private val answers = ArrayDeque<Pair<Int, String>>()

    @Volatile
    private var canceledWhenSent = false

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        canceledWhenSent = chain.call().isCanceled()
        val (code, text) = answers.removeFirst()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(text.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val client = IntakeClient({ "test-key-0000" }, {}, http)
    private val body = IntakeMessages.body("決まり", "会話")
    private val done = """{"stop_reason":"end_turn","content":[{"type":"text","text":"{}"}]}"""
    private val noBeta = """{"type":"error","error":{"type":"invalid_request_error","message":"Unexpected value(s) `x` for the `anthropic-beta` header."}}"""

    private fun send(c: IntakeClient = client): IntakeReply {
        var reply: IntakeReply? = null
        val latch = CountDownLatch(1)
        c.send(body) {
            reply = it
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return reply!!
    }

    private fun sentBody(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test
    fun theKeyGoesInItsHeaderToTheMessagesApi() {
        answers += 200 to done
        assertTrue(send() is IntakeReply.Arrived)
        val r = requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", r.url.toString())
        assertEquals("test-key-0000", r.header("x-api-key"))
        assertEquals("2026-10-21", r.header("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", r.header("anthropic-beta"))
        assertTrue(sentBody(r).containsKey("fallbacks"))
    }

    @Test
    fun anAccountThatDoesNotTakeTheFallbackGetsTheTurnOnceMoreWithoutIt() {
        answers += 400 to noBeta
        answers += 200 to done
        assertTrue(send() is IntakeReply.Arrived)
        assertEquals(2, requests.size)
        assertNull(requests[1].header("anthropic-beta"))
        assertFalse(sentBody(requests[1]).containsKey("fallbacks"))
    }

    @Test
    fun theFallbackIsNotOfferedAgainAfterThat() {
        answers += 400 to noBeta
        answers += 200 to done
        send()
        answers += 200 to done
        send()
        assertEquals(3, requests.size)
        assertNull(requests[2].header("anthropic-beta"))
    }

    @Test
    fun anotherRejectionIsNotSentAgain() {
        answers += 400 to """{"type":"error","error":{"type":"invalid_request_error","message":"Schema is too complex for compilation"}}"""
        val reply = send() as IntakeReply.Failed
        assertEquals(1, requests.size)
        assertFalse(reply.canRetry)
    }

    @Test
    fun aRefusalIsAFailureEvenThoughTheStatusIs200() {
        answers += 200 to """{"stop_reason":"refusal","content":[]}"""
        val reply = send() as IntakeReply.Failed
        assertTrue(reply.dropLastSaid)
    }

    @Test
    fun theWarmUpGoesWithoutTheFallback() {
        answers += 200 to done
        client.warmUp()
        var waited = 0
        while (requests.isEmpty() && waited < 100) {
            Thread.sleep(20)
            waited++
        }
        val r = requests.single()
        assertNull(r.header("anthropic-beta"))
        assertFalse(sentBody(r).containsKey("fallbacks"))
    }

    @Test
    fun theKeyIsNotReadOnTheThreadThatSends() {
        var readOn: Thread? = null
        send(IntakeClient({ readOn = Thread.currentThread(); null }, {}, http))
        assertTrue(readOn != null && readOn !== Thread.currentThread())
    }

    @Test
    fun stoppingBeforeTheRequestExistsCancelsIt() {
        val reading = CountDownLatch(1)
        val answered = CountDownLatch(1)
        answers += 200 to done
        val stop = IntakeClient({ reading.await(5, TimeUnit.SECONDS); "test-key-0000" }, {}, http).send(body) { answered.countDown() }
        stop()
        reading.countDown()
        assertTrue(answered.await(5, TimeUnit.SECONDS))
        assertTrue(canceledWhenSent)
    }

    @Test
    fun withoutAKeyNothingIsSent() {
        val reply = send(IntakeClient({ null }, {}, http))
        assertTrue(reply is IntakeReply.Failed)
        assertEquals(0, requests.size)
    }

    @Test
    fun aKeyAHeaderCannotHoldIsNotACrash() {
        val reply = send(IntakeClient({ "bad\nkey" }, {}, http))
        assertTrue(reply is IntakeReply.Failed)
        assertEquals(0, requests.size)
    }

    @Test
    fun theLogHasNoMessageFromTheServer() {
        val lines = ArrayList<String>()
        answers += 400 to """{"type":"error","error":{"type":"invalid_request_error","message":"bad 090-0000-0000 in output_config"}}"""
        send(IntakeClient({ "test-key-0000" }, { lines += it }, http))
        assertEquals(listOf("intake: http 400 invalid_request_error output_config"), lines)
    }
}
