// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.LiveAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** The thinking model played by the test: it answers when told to. */
class FakeBrain : CallBrain {
    val warmUps = AtomicInteger()
    val inputs = ArrayList<BrainInput>()
    val cancelled = ArrayList<BrainInput>()

    /** The answer of the last question, also after it was cancelled. */
    var last: ((BrainOutput) -> Unit)? = null
        private set

    /** The sentences of the last question, also after it was cancelled. */
    var lastLine: ((String) -> Unit)? = null
        private set
    private var open: ((BrainOutput) -> Unit)? = null

    val thinking: Boolean get() = open != null

    /** Thrown by the next [think]. */
    var thinkFailure: Throwable? = null

    override fun warmUp() {
        warmUps.incrementAndGet()
    }

    override fun think(input: BrainInput, onLine: (String) -> Unit, done: (BrainOutput) -> Unit): () -> Unit {
        thinkFailure?.let { throw it }
        inputs += input
        open = done
        last = done
        lastLine = onLine
        return {
            cancelled += input
            if (open === done) open = null
        }
    }

    fun answer(output: BrainOutput) {
        val done = open ?: error("no question is open")
        open = null
        done(output)
    }

    /** One finished sentence of the answer to the open question. */
    fun line(text: String) {
        check(open != null) { "no question is open" }
        lastLine!!(text)
    }
}

/** One voice socket without a network: the test plays the server by pushing its messages. */
class FakeVoiceSocket(private val listener: VoiceSocket.Listener) : VoiceSocket {
    val sent = ArrayList<String>()
    var cancelled = false
    private var opened = false

    override fun send(text: String): Boolean {
        if (!opened || cancelled) return false
        sent += text
        return true
    }

    override fun cancel() {
        cancelled = true
    }

    fun open() {
        opened = true
        listener.onOpen()
    }

    fun server(json: String) = listener.onMessage(json)

    fun setupComplete() = server("""{"setupComplete":{}}""")

    fun audio(bytes: Int = 4_800) {
        val data = Base64.getEncoder().encodeToString(ByteArray(bytes))
        server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}}]}}}""")
    }

    /** What the voice said, written out. */
    fun said(text: String) = server("""{"serverContent":{"outputTranscription":{"text":"$text"}}}""")

    fun turnComplete() = server("""{"serverContent":{"turnComplete":true}}""")

    fun interim(text: String) = server("""{"serverContent":{"interimInputTranscription":{"text":"$text"}}}""")

    fun final(text: String) = server("""{"serverContent":{"inputTranscription":{"text":"$text"}}}""")

    fun fail(detail: String) = listener.onFailure(detail)

    fun closed(code: Int, reason: String) = listener.onClosed(code, reason)

    /** The lines this socket was given to read, in order. */
    fun spoken(): List<String> = sent.mapNotNull { text ->
        runCatching {
            Json.parseToJsonElement(text).jsonObject.getValue("clientContent").jsonObject.getValue("turns").jsonArray[0]
                .jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content.removePrefix("【読み上げ】")
        }.getOrNull()
    }
}

class FakeVoiceSockets : VoiceSockets {
    val opened = ArrayList<FakeVoiceSocket>()

    override fun open(auth: LiveAuth, listener: VoiceSocket.Listener): VoiceSocket = FakeVoiceSocket(listener).also { opened += it }
}
