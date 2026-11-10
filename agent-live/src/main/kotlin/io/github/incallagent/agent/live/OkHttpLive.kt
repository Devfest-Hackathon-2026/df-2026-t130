// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/** Handle of an open connection. */
fun interface LiveConnection {
    /** Drops the connection immediately (no close handshake). */
    fun cancel()
}

/** Connects a [LiveSession] to the Gemini Live API over OkHttp's WebSocket. */
object OkHttpLive {
    /** Close code sent when this side closes, also in answer to the server's close. */
    internal const val NORMAL_CLOSURE = 1000

    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // streaming: no read timeout
            .build()
    }

    fun connect(auth: LiveAuth, session: LiveSession, client: OkHttpClient = defaultClient): LiveConnection {
        val secret = when (auth) {
            is LiveAuth.ApiKey -> auth.key
            is LiveAuth.EphemeralToken -> auth.token
        }.trim()
        val request = Request.Builder().url(LiveEndpoint.url(auth)).build()
        val ws = client.newWebSocket(request, SessionSocketListener(session, secret))
        return LiveConnection { ws.cancel() }
    }
}

/**
 * Forwards OkHttp's WebSocket callbacks to [session]. Texts passed on never contain [secret]
 * (the URL carries the key or token, and OkHttp error messages can quote it).
 */
internal class SessionSocketListener(
    private val session: LiveSession,
    private val secret: String,
) : WebSocketListener() {
    private fun safe(s: String?) = (s ?: "").let { if (secret.isNotEmpty()) it.replace(secret, "<hidden>") else it }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        session.onOpen(object : LiveTransport {
            override fun send(text: String) = webSocket.send(text)
            override fun close(code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        })
    }

    override fun onMessage(webSocket: WebSocket, text: String) = session.onMessage(text)

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = session.onMessage(bytes.utf8())

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // Report the server's code first, then answer with a normal closure: the server's close
        // frame can carry a code OkHttp refuses to send back (e.g. 1005 "no status"), and the
        // exception would turn a clean close into onFailure.
        session.onClosed(code, safe(reason))
        webSocket.close(OkHttpLive.NORMAL_CLOSURE, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
        session.onClosed(code, safe(reason))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // A rejected handshake (wrong key, no paid tier, unknown model) explains itself in the body
        val http = response?.let { r ->
            val body = runCatching { r.body.string() }.getOrNull()?.take(300)?.let { " body=${safe(it)}" } ?: ""
            " http=${r.code}$body"
        } ?: ""
        session.onFailure("${t.javaClass.simpleName}: ${safe(t.message)}$http")
    }
}
