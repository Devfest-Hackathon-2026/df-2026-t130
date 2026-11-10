// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.DelegationPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate

/** The network side of a session (OkHttp in the app, a fake in tests). */
interface LiveTransport {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

/**
 * One Gemini Live conversation for one approved delegation policy.
 *
 * Flow: [onOpen] sends `setup` -> server `setupComplete` -> [State.READY] and the opening cue
 * (the model greets and says it is an AI) -> audio both ways; tool calls are answered by
 * [toolHandler] automatically.
 *
 * Threads: [onOpen]/[onMessage]/[onClosed]/[onFailure] come from the transport's single reader
 * thread; [sendAudio] may be called from the audio thread. Listener callbacks run on the
 * reader thread: hand audio to a playback queue, do not block.
 */
class LiveSession(
    private val policy: DelegationPolicy,
    private val today: LocalDate,
    actions: CallActions,
    private val listener: Listener,
    private val model: String = LiveMessages.DEFAULT_MODEL,
    clock: () -> Instant = Instant::now,
) {
    enum class State { CONNECTING, SETTING_UP, READY, CLOSED, FAILED }

    interface Listener {
        fun onState(state: State, detail: String) {}

        /** Audio, transcripts, interruptions, turn ends, GoAway... (also SetupComplete and ToolCall). */
        fun onEvent(event: ServerEvent) {}

        /** A tool call and the answer that was sent back. */
        fun onToolCall(call: FunctionCall, response: FunctionResponse) {}
    }

    /** Executes the model's tool calls against [policy]. Holds the reported outcome. */
    val toolHandler = ToolCallHandler(policy, actions, clock)

    @Volatile
    var state: State = State.CONNECTING
        private set

    @Volatile
    private var transport: LiveTransport? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun onOpen(t: LiveTransport) {
        if (state == State.CLOSED || state == State.FAILED) {
            // Closed while the handshake was still running: do not start talking now
            t.close(1000, "client closed")
            return
        }
        transport = t
        setState(State.SETTING_UP, "connected, sending setup (model=$model)")
        send(LiveMessages.setup(policy, today, model))
    }

    fun onMessage(text: String) {
        // A late message must not bring a finished session back
        if (state == State.CLOSED || state == State.FAILED) return
        val message: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            listener.onState(state, "unparsable server message (${text.length} chars)")
            return
        }
        for (event in ServerEvents.parse(message)) {
            when (event) {
                ServerEvent.SetupComplete -> if (state == State.CONNECTING || state == State.SETTING_UP) {
                    setState(State.READY, "setup complete")
                    send(LiveMessages.openingCue())
                }
                is ServerEvent.ToolCall -> {
                    val responses = event.calls.map { call ->
                        toolHandler.handle(call).also { listener.onToolCall(call, it) }
                    }
                    send(LiveMessages.toolResponse(responses))
                }
                else -> {}
            }
            listener.onEvent(event)
        }
    }

    fun onClosed(code: Int, reason: String) {
        if (state == State.CLOSED || state == State.FAILED) return
        setState(State.CLOSED, "closed code=$code reason=${reason.ifEmpty { "-" }}")
        transport = null
    }

    fun onFailure(detail: String) {
        if (state == State.CLOSED || state == State.FAILED) return
        setState(State.FAILED, detail)
        transport = null
    }

    /** 16 kHz 16-bit little-endian mono PCM. Ignored until [State.READY]. */
    fun sendAudio(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset): Boolean {
        if (state != State.READY) return false
        return send(LiveMessages.realtimeAudio(pcm16le, offset, length))
    }

    fun audioStreamEnd() {
        if (state == State.READY) send(LiveMessages.audioStreamEnd())
    }

    fun close() {
        val t = transport
        if (t == null) {
            // Not open yet: mark closed so a late onOpen does not revive the session
            onClosed(1000, "client closed before the connection opened")
            return
        }
        t.close(1000, "client closed")
        onClosed(1000, "client closed")
    }

    private fun send(message: JsonObject): Boolean = transport?.send(message.toString()) ?: false

    private fun setState(s: State, detail: String) {
        state = s
        listener.onState(s, detail)
    }
}
