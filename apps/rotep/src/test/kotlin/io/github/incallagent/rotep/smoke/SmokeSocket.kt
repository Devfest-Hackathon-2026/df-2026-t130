// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.smoke

import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Removes the API key (as given and URL-encoded) from any text before it is printed or saved. */
internal class Secrets(secret: String) {
    private val forms = listOf(secret, URLEncoder.encode(secret, "UTF-8")).filter { it.length >= 4 }.distinct()

    fun clean(s: String?): String = forms.fold(s ?: "") { acc, f -> acc.replace(f, "<hidden>") }
}

/**
 * Changes to the outgoing messages, to find the shape the server accepts without touching
 * agent-live. The default changes nothing (the messages exactly as LiveSession builds them).
 *
 * - [languageCode] false: drop setup.generationConfig.speechConfig.languageCode (the Live API
 *   guide says native audio models do not support setting it)
 * - [modalitiesAtTop]: move responseModalities from setup.generationConfig to setup (the
 *   WebSocket guide's examples put it there)
 * - [textAsRealtimeInput]: send clientContent text turns as realtimeInput.text instead
 */
internal data class Variant(
    val languageCode: Boolean = true,
    val modalitiesAtTop: Boolean = false,
    val textAsRealtimeInput: Boolean = false,
) {
    val label: String
        get() = buildList {
            if (!languageCode) add("languageCode なし")
            if (modalitiesAtTop) add("responseModalities を setup の直下に")
            if (textAsRealtimeInput) add("テキストを realtimeInput.text で")
        }.ifEmpty { listOf("agent-live のまま") }.joinToString("、")

    fun rewrite(text: String): String {
        val setup = text.startsWith("{\"setup\"") && (!languageCode || modalitiesAtTop)
        val content = text.startsWith("{\"clientContent\"") && textAsRealtimeInput
        if (!setup && !content) return text
        val message = Json.parseToJsonElement(text).jsonObject
        return (if (setup) rewriteSetup(message) else realtimeText(textOf(message))).toString()
    }

    private fun rewriteSetup(message: JsonObject): JsonObject {
        val setup = message.getValue("setup").jsonObject.toMutableMap()
        val gen = (setup["generationConfig"] as? JsonObject)?.toMutableMap() ?: return message
        if (!languageCode) {
            (gen["speechConfig"] as? JsonObject)?.toMutableMap()?.let { speech ->
                speech.remove("languageCode")
                if (speech.isEmpty()) gen.remove("speechConfig") else gen["speechConfig"] = JsonObject(speech)
            }
        }
        if (modalitiesAtTop) {
            gen.remove("responseModalities")?.let { setup["responseModalities"] = it }
        }
        if (gen.isEmpty()) setup.remove("generationConfig") else setup["generationConfig"] = JsonObject(gen)
        return JsonObject(mapOf("setup" to JsonObject(setup)))
    }

    companion object {
        /** A user turn as clientContent, the same shape as LiveMessages.openingCue(). */
        fun clientContentText(text: String): JsonObject = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
                put("turnComplete", true)
            }
        }

        fun realtimeText(text: String): JsonObject = buildJsonObject {
            putJsonObject("realtimeInput") { put("text", text) }
        }

        /** All text parts of a clientContent message, joined. */
        fun textOf(message: JsonObject): String {
            val turns = (message["clientContent"] as? JsonObject)?.get("turns") as? JsonArray ?: return ""
            return turns.flatMap { turn -> ((turn as? JsonObject)?.get("parts") as? JsonArray) ?: JsonArray(emptyList()) }
                .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                .joinToString("\n")
        }
    }
}

/** An open connection as the test uses it (OkHttp in [SmokeSocket], a fake server in tests). */
internal interface SmokeLink {
    /** Sends a message of the test's own (the shop's lines), not through the session. */
    fun sendRaw(message: JsonObject): Boolean

    /** Starts the closing handshake. */
    fun close(): Boolean

    /** Drops the connection at once. */
    fun cancel()
}

/** Opens a [SmokeLink] for [session]; the link must call `session.onOpen` with a transport that applies [variant]. */
internal fun interface SmokeConnector {
    fun connect(session: LiveSession, variant: Variant, hooks: SmokeSocket.Hooks): SmokeLink
}

/**
 * Connects a [LiveSession] over OkHttp like agent-live's OkHttpLive, but keeps the WebSocket so
 * the test can send its own messages ([sendRaw]), lets [Variant] change outgoing messages, and
 * reports raw traffic to [Hooks]. Every text that leaves here went through [Secrets.clean].
 */
internal class SmokeSocket private constructor(private val ws: WebSocket) : SmokeLink {
    interface Hooks {
        fun opened() {}

        /** A server message, before the session parses it. */
        fun received(text: String) {}

        /** A message after [Variant.rewrite]; [ok] is what WebSocket.send returned. */
        fun sent(text: String, ok: Boolean) {}

        /** Close frame from the server ([fromServer]) or the end of the closing handshake. */
        fun closed(code: Int, reason: String, fromServer: Boolean) {}

        fun failed(detail: String, httpCode: Int?) {}
    }

    override fun sendRaw(message: JsonObject): Boolean = ws.send(message.toString())

    override fun close(): Boolean = runCatching { ws.close(1000, "client closed") }.getOrDefault(false)

    override fun cancel() = ws.cancel()

    companion object {
        /**
         * Same settings as OkHttpLive.defaultClient, plus a connect timeout. Returns the client and
         * the name of the OkHttp platform it uses.
         *
         * On a PC, OkHttp's Android artifact first tries android.util.Log (not available in unit
         * tests: it prints "Possibly running android unit test without robolectric" with a stack
         * trace and goes on) and then uses its JVM platform. That harmless trace is kept off the
         * output; a real failure still throws.
         */
        fun newClient(): Pair<OkHttpClient, String> {
            val err = System.err
            System.setErr(PrintStream(OutputStream.nullOutputStream()))
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                return client to platformName()
            } finally {
                System.setErr(err)
            }
        }

        /** okhttp3.internal.platform.Platform.get() (an opt-in API, so read by reflection; diagnostics only). */
        private fun platformName(): String = runCatching {
            val companion = Class.forName("okhttp3.internal.platform.Platform").getField("Companion").get(null)
            companion.javaClass.getMethod("get").invoke(companion).javaClass.simpleName
        }.getOrElse { "?" }

        fun shutdown(client: OkHttpClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }

        /** [url] contains the key: it is only given to OkHttp, never printed. */
        fun connect(
            client: OkHttpClient,
            url: String,
            session: LiveSession,
            variant: Variant,
            secrets: Secrets,
            hooks: Hooks,
        ): SmokeSocket {
            val request = Request.Builder().url(url).build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    hooks.opened()
                    session.onOpen(object : LiveTransport {
                        override fun send(text: String): Boolean {
                            val out = variant.rewrite(text)
                            return webSocket.send(out).also { hooks.sent(out, it) }
                        }

                        override fun close(code: Int, reason: String) {
                            runCatching { webSocket.close(code, reason) }
                        }
                    })
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    hooks.received(text)
                    session.onMessage(text)
                }

                // The server may send JSON in binary frames
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val text = bytes.utf8()
                    hooks.received(text)
                    session.onMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val r = secrets.clean(reason)
                    hooks.closed(code, r, fromServer = true)
                    session.onClosed(code, r)
                    // Reserved codes such as 1005 cannot be echoed back: always answer with 1000
                    runCatching { webSocket.close(1000, null) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val r = secrets.clean(reason)
                    hooks.closed(code, r, fromServer = false)
                    session.onClosed(code, r)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A rejected handshake (wrong key, no paid tier, unknown model) explains itself in the body
                    val http = response?.code
                    val body = response?.let { r -> runCatching { r.body.string() }.getOrNull()?.take(400) }
                    val detail = secrets.clean(
                        "${t.javaClass.simpleName}: ${t.message}" +
                            (http?.let { " http=$it" } ?: "") +
                            (body?.takeIf { it.isNotBlank() }?.let { " body=${it.replace('\n', ' ')}" } ?: "")
                    )
                    hooks.failed(detail, http)
                    session.onFailure(detail)
                }
            })
            return SmokeSocket(ws)
        }
    }
}
