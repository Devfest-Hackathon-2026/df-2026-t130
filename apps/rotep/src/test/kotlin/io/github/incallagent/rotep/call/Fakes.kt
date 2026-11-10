// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.PriorityQueue

/** A clock moved by hand. Tasks run only in [runUntilIdle] and [advance], in time order. */
class FakeScheduler : CallScheduler {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit) {
        var cancelled = false
    }

    var time = 0L
        private set
    private var seq = 0L
    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

    override fun now() = time

    override fun post(task: () -> Unit) {
        add(time, task)
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): CallScheduler.Cancellable {
        val t = add(time + delayMs, task)
        return CallScheduler.Cancellable { t.cancelled = true }
    }

    private fun add(at: Long, task: () -> Unit) = Task(at, seq++, task).also { tasks += it }

    /** Runs everything that is due now, including tasks posted while running. */
    fun runUntilIdle() = advance(0)

    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val t = tasks.peek() ?: break
            if (t.at > end) break
            tasks.poll()
            if (t.at > time) time = t.at
            if (!t.cancelled) t.run()
        }
        time = end
    }
}

class FakeAudioPath(override val name: String = "fake") : CallAudioPath {
    var listener: CallAudioPath.Listener? = null
    val played = ArrayList<ByteArray>()
    var starts = 0
    var stops = 0
    var flushes = 0
    var playing = false
    var restores = 0
    var onRestore: () -> Unit = {}
    var soundComesBack = true

    override fun restoreCallSound(): Boolean {
        restores++
        onRestore()
        return soundComesBack
    }

    override fun start(listener: CallAudioPath.Listener) {
        starts++
        this.listener = listener
        listener.onEvent(AudioPathEvent.Started("test"))
    }

    override fun play(pcm24k: ByteArray) {
        played += pcm24k
    }

    override fun flush() {
        flushes++
    }

    override fun isPlaying() = playing

    override fun stop() {
        stops++
    }

    fun downlink(pcm: ByteArray) = listener!!.onDownlink(pcm)

    fun event(e: AudioPathEvent) = listener!!.onEvent(e)
}

/** Like L2: dialing reports Dialing; the test answers and ends the call. */
class FakeCallControl : CallControl {
    private var listener: ((CallEvent) -> Unit)? = null
    val calls = ArrayList<String>()
    var failDial: String? = null

    override fun setListener(listener: (CallEvent) -> Unit) {
        this.listener = listener
    }

    override fun dial(number: String) {
        calls += "dial"
        val fail = failDial
        listener?.invoke(if (fail != null) CallEvent.Failed(fail) else CallEvent.Dialing)
    }

    fun answer() = listener!!.invoke(CallEvent.Active)

    fun end(reason: String) = listener!!.invoke(CallEvent.Ended(reason))

    /** The control gives up the call after dialing (like L3 when it cannot find or keep the call). */
    fun fail(detail: String) = listener!!.invoke(CallEvent.Failed(detail))

    override fun hangUp() {
        calls += "hangUp"
    }

    override fun sendDtmf(digits: String) {
        calls += "dtmf:$digits"
    }

    override fun setMuted(muted: Boolean) {
        calls += "muted:$muted"
    }

    override fun close() {
        calls += "close"
    }
}

/**
 * Gemini Live without a network: the test plays the server by pushing its JSON messages. [sent]
 * has everything sent to the server, LiveSession's messages and rotep's own (the disclosure cue).
 */
class FakeLive : io.github.incallagent.rotep.call.LiveConnector {
    var session: LiveSession? = null
    val sent = ArrayList<JsonObject>()
    var cancelled = false
    var closedWith: Int? = null
    private var opened = false
    private var calls = 0

    private val transport = object : LiveTransport {
        override fun send(text: String): Boolean {
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }

        override fun close(code: Int, reason: String) {
            closedWith = code
        }
    }

    override fun connect(session: LiveSession): RotepConnection {
        this.session = session
        return object : RotepConnection {
            override fun cancel() {
                cancelled = true
            }

            override fun send(text: String): Boolean = opened && !cancelled && transport.send(text)
        }
    }

    fun open() {
        opened = true
        session!!.onOpen(transport)
    }

    fun server(json: String) = session!!.onMessage(json)

    fun ready() {
        open()
        server("""{"setupComplete":{}}""")
    }

    fun audio(bytes: Int = 4_800) {
        val data = Base64.getEncoder().encodeToString(ByteArray(bytes))
        server("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}}]}}}""")
    }

    fun say(text: String) = server("""{"serverContent":{"outputTranscription":{"text":"$text"}}}""")

    /** The model was cut off: what it had not played yet is dropped. */
    fun interrupted() = server("""{"serverContent":{"interrupted":true}}""")

    /** The end of the model's turn. */
    fun turnComplete() = server("""{"serverContent":{"turnComplete":true}}""")

    /** How many clientContent messages went out (the opening cue, then the disclosure cues). */
    fun cues(): Int = sent.count { it.containsKey("clientContent") }

    fun tool(name: String, args: String) {
        server("""{"toolCall":{"functionCalls":[{"id":"c${++calls}","name":"$name","args":$args}]}}""")
    }

    fun sentKinds(): List<String> = sent.map { it.keys.single() }

    /** The answer to the last tool call. */
    fun lastToolResponse(): JsonObject =
        sent.last { it.containsKey("toolResponse") }.getValue("toolResponse").jsonObject
}
