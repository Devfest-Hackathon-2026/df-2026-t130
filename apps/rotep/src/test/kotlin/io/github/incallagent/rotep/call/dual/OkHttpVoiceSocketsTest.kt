// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class OkHttpVoiceSocketsTest {
    private val secret = "FAKE-KEY-0000"

    private class Heard : VoiceSocket.Listener {
        val events = ArrayList<String>()

        override fun onOpen() {
            events += "open"
        }

        override fun onMessage(text: String) {
            events += "message:$text"
        }

        override fun onClosed(code: Int, reason: String) {
            events += "closed:$code:$reason"
        }

        override fun onFailure(detail: String) {
            events += "failure:$detail"
        }
    }

    private class FakeSocket : WebSocket {
        val sent = ArrayList<String>()
        val closed = ArrayList<Int>()
        var cancelled = 0
        var acceptsSend = true

        override fun cancel() {
            cancelled++
        }

        override fun close(code: Int, reason: String?): Boolean {
            closed += code
            return true
        }

        override fun queueSize() = 0L

        override fun request() = Request.Builder().url("https://example.com/").build()

        override fun send(text: String): Boolean {
            sent += text
            return acceptsSend
        }

        override fun send(bytes: ByteString) = true
    }

    private val heard = Heard()
    private val socket = FakeSocket()
    private val bridge = VoiceSocketBridge(heard, secret)
    private val voice: VoiceSocket = bridge.handle(socket)

    private fun response(code: Int, body: String) = Response.Builder()
        .request(Request.Builder().url("https://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .body(body.toResponseBody())
        .build()

    @Test
    fun `nothing is sent before the socket has opened`() {
        assertFalse(voice.send("too early"))
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `once open the text goes out`() {
        bridge.onOpen(socket, response(101, ""))
        assertTrue(voice.send("hello"))
        assertEquals(listOf("hello"), socket.sent)
        assertEquals(listOf("open"), heard.events)
    }

    @Test
    fun `a socket that refuses is told as false`() {
        bridge.onOpen(socket, response(101, ""))
        socket.acceptsSend = false
        assertFalse(voice.send("hello"))
    }

    @Test
    fun `messages come as text, also those sent as bytes`() {
        bridge.onMessage(socket, "as text")
        bridge.onMessage(socket, "as bytes".encodeUtf8())
        assertEquals(listOf("message:as text", "message:as bytes"), heard.events)
    }

    @Test
    fun `the end is told once, and the close is answered`() {
        bridge.onOpen(socket, response(101, ""))
        bridge.onClosing(socket, 1011, "server error")
        bridge.onClosed(socket, 1011, "server error")
        assertEquals(listOf("open", "closed:1011:server error"), heard.events)
        assertEquals(listOf(1000), socket.closed)
        assertFalse(voice.send("too late"))
    }

    @Test
    fun `a failure ends it too and the end is not told again`() {
        bridge.onOpen(socket, response(101, ""))
        bridge.onFailure(socket, IOException("reset"), null)
        bridge.onClosed(socket, 1006, "gone")
        assertEquals(listOf("open", "failure:IOException: reset"), heard.events)
        assertFalse(voice.send("too late"))
    }

    @Test
    fun `the key never comes out of a failure or a close`() {
        bridge.onFailure(socket, UnknownHostException("host?key=$secret"), response(403, "bad key $secret"))
        assertEquals(1, heard.events.size)
        assertFalse(heard.events.single(), heard.events.single().contains(secret))
        assertTrue(heard.events.single(), heard.events.single().contains("UnknownHostException"))
        assertTrue(heard.events.single(), heard.events.single().contains("http=403"))
        val other = Heard()
        VoiceSocketBridge(other, secret).onClosing(socket, 1008, "invalid key $secret")
        assertFalse(other.events.single(), other.events.single().contains(secret))
    }

    @Test
    fun `cancel drops the connection`() {
        voice.cancel()
        assertEquals(1, socket.cancelled)
    }

    @Test
    fun `nothing can be sent after cancel`() {
        bridge.onOpen(socket, response(101, ""))
        voice.cancel()
        assertFalse(voice.send("too late"))
    }
}
