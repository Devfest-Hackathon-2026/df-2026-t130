// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSocketListenerTest {
    /** Records close codes and refuses reserved ones, as OkHttp's WebSocket does. */
    private class FakeWebSocket : WebSocket {
        val closeCodes = ArrayList<Int>()

        override fun close(code: Int, reason: String?): Boolean {
            require(code in 1000..4999 && code !in 1004..1006 && code !in 1015..2999) {
                "Code $code is reserved and may not be used."
            }
            closeCodes += code
            return true
        }

        override fun request(): Request = throw UnsupportedOperationException()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun cancel() {}
    }

    private class Recorder : LiveSession.Listener {
        val states = ArrayList<LiveSession.State>()
        val details = ArrayList<String>()
        override fun onState(state: LiveSession.State, detail: String) {
            states += state
            details += detail
        }
    }

    private val actions = object : CallActions {
        override fun endCall(reason: String) {}
        override fun sendDtmf(digits: String) {}
    }

    private val secret = "k3y-secret"

    private fun setUp(): Triple<LiveSession, Recorder, SessionSocketListener> {
        val rec = Recorder()
        val session = LiveSession(TestPolicy.example(), TestPolicy.today, actions, rec)
        return Triple(session, rec, SessionSocketListener(session, secret))
    }

    @Test
    fun serverCloseWithoutStatusCodeIsAClose() {
        val (session, rec, listener) = setUp()
        val ws = FakeWebSocket()

        // A close frame without a status code reaches onClosing as 1005
        listener.onClosing(ws, 1005, "")
        listener.onClosed(ws, 1005, "")

        assertEquals(LiveSession.State.CLOSED, session.state)
        assertEquals(listOf(LiveSession.State.CLOSED), rec.states)
        assertTrue(rec.details.single().contains("code=1005"))
        assertEquals("answered with a normal closure", listOf(OkHttpLive.NORMAL_CLOSURE), ws.closeCodes)
    }

    @Test
    fun closeReasonAndFailureNeverShowTheSecret() {
        val (_, rec, listener) = setUp()
        listener.onClosing(FakeWebSocket(), 1008, "API key $secret not valid")
        assertFalse(rec.details.single().contains(secret))
        assertTrue(rec.details.single().contains("<hidden>"))

        val (failed, rec2, listener2) = setUp()
        listener2.onFailure(FakeWebSocket(), RuntimeException("unexpected url ...?key=$secret"), null)
        assertEquals(LiveSession.State.FAILED, failed.state)
        assertFalse(rec2.details.single().contains(secret))
    }

    @Test
    fun binaryFramesAreReadAsJson() {
        val (session, _, listener) = setUp()
        listener.onMessage(FakeWebSocket(), """{"setupComplete":{}}""".encodeUtf8())
        // Without onOpen there is no transport: the state still follows the server message
        assertEquals(LiveSession.State.READY, session.state)
    }
}
