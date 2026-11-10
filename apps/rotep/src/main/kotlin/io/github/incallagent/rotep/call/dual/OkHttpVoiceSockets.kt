// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveEndpoint
import io.github.incallagent.agent.live.OkHttpLive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/** The two sockets of the voice model, on the same endpoint and client as the one-socket call (RotepLive). */
class OkHttpVoiceSockets(private val client: OkHttpClient = OkHttpLive.defaultClient) : VoiceSockets {
    override fun open(auth: LiveAuth, listener: VoiceSocket.Listener): VoiceSocket {
        val secret = when (auth) {
            is LiveAuth.ApiKey -> auth.key
            is LiveAuth.EphemeralToken -> auth.token
        }.trim()
        val request = Request.Builder().url(LiveEndpoint.url(auth)).build()
        val bridge = VoiceSocketBridge(listener, secret)
        return bridge.handle(client.newWebSocket(request, bridge))
    }
}

/**
 * OkHttp's callbacks as a [VoiceSocket.Listener]. The end is told once, and not at all after [VoiceSocket.cancel].
 * Texts passed on never contain [secret] (the URL carries the key, and OkHttp's messages can quote it);
 * a failure has the same form as RotepLive's, which UserMessages reads.
 */
internal class VoiceSocketBridge(
    private val listener: VoiceSocket.Listener,
    private val secret: String,
) : WebSocketListener() {
    @Volatile
    private var open = false
    private val ended = AtomicBoolean(false)

    fun handle(socket: WebSocket): VoiceSocket = object : VoiceSocket {
        // OkHttp would queue what is sent before the socket opens
        override fun send(text: String): Boolean = open && socket.send(text)

        override fun cancel() {
            open = false
            ended.set(true)
            socket.cancel()
        }
    }

    private fun safe(s: String?) = (s ?: "").let { if (secret.isNotEmpty()) it.replace(secret, "<hidden>") else it }

    private inline fun finish(tell: () -> Unit) {
        open = false
        if (ended.compareAndSet(false, true)) tell()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        open = true
        listener.onOpen()
    }

    override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(bytes.utf8())

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        finish { listener.onClosed(code, safe(reason)) }
        // A normal closure in answer: the server's code can be one OkHttp refuses to send back
        webSocket.close(NORMAL_CLOSURE, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish { listener.onClosed(code, safe(reason)) }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val http = response?.let { r ->
            val body = runCatching { r.body.string() }.getOrNull()?.take(MAX_ERROR_BODY)?.let { " body=${safe(it)}" } ?: ""
            " http=${r.code}$body"
        } ?: ""
        finish { listener.onFailure("${t.javaClass.simpleName}: ${safe(t.message)}$http") }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_ERROR_BODY = 300
    }
}
