// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveConnection
import io.github.incallagent.agent.live.LiveEndpoint
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTransport
import io.github.incallagent.agent.live.OkHttpLive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The Live connection of one session, through which rotep can also send messages of its own (the
 * cue that asks the agent to say it is an AI, [AiDisclosure]) besides what LiveSession sends.
 */
interface RotepConnection : LiveConnection {
    /** Sends [text] on the session's transport. false before the connection opened, or after it closed. */
    fun send(text: String): Boolean

    /** Like [send], with the call already over: only what the AI does still counts, nobody hears what it says. */
    fun sendAfterTheCall(text: String): Boolean = send(text)
}

/**
 * Connects a [LiveSession] to the Gemini Live API like agent-live's OkHttpLive, with one
 * difference: the setup message goes out through [SpeakingStyle.tune] (rotep's speaking rules and
 * the voice). agent-live has no hook for an app to add to the setup, so rotep keeps this copy of
 * the connection code; the callbacks behave exactly like OkHttpLive's. The [TunedTransport] that
 * LiveSession sends on is also the one [RotepConnection.send] uses.
 */
object RotepLive {
    fun connect(
        auth: LiveAuth,
        session: LiveSession,
        style: SpeakingStyle,
        client: OkHttpClient = OkHttpLive.defaultClient,
    ): RotepConnection {
        val secret = when (auth) {
            is LiveAuth.ApiKey -> auth.key
            is LiveAuth.EphemeralToken -> auth.token
        }.trim()
        val request = Request.Builder().url(LiveEndpoint.url(auth)).build()
        val listener = TunedSocketListener(session, secret, style::tune)
        val ws = client.newWebSocket(request, listener)
        return object : RotepConnection {
            override fun cancel() = ws.cancel()

            override fun send(text: String): Boolean = listener.transport?.send(text) ?: false
        }
    }
}

/**
 * Forwards OkHttp's WebSocket callbacks to [session], as agent-live's listener does. Texts passed
 * on never contain [secret] (the URL carries the key, and OkHttp error messages can quote it).
 */
private class TunedSocketListener(
    private val session: LiveSession,
    private val secret: String,
    private val tune: (String) -> String,
) : WebSocketListener() {
    /** Set once the socket is open: the transport LiveSession sends on. */
    @Volatile
    var transport: TunedTransport? = null
        private set

    private fun safe(s: String?) = (s ?: "").let { if (secret.isNotEmpty()) it.replace(secret, "<hidden>") else it }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val raw = object : LiveTransport {
            override fun send(text: String) = webSocket.send(text)
            override fun close(code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        val t = TunedTransport(raw, tune)
        transport = t
        session.onOpen(t)
    }

    override fun onMessage(webSocket: WebSocket, text: String) = session.onMessage(text)

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = session.onMessage(bytes.utf8())

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        // Report the server's code first, then answer with a normal closure (the server's code can
        // be one OkHttp refuses to send back, which would turn a clean close into onFailure)
        session.onClosed(code, safe(reason))
        webSocket.close(NORMAL_CLOSURE, null)
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

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
