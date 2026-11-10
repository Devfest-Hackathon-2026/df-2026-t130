// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** One instruction from the server. What it holds goes to [PhoneRequestHandler.handle] and nowhere else. [assistant]: which AI asked, if the server knows. */
data class RelayCommand(val cmd: String, val method: String, val id: String, val arg: JsonObject, val assistant: String? = null)

/** The connection to the server: [RelayHttp] in the app, a fake in tests. A network failure is an [IOException]. */
interface RelayTransport {
    /** What the server has for this phone; waits up to [waitSeconds] if there is nothing yet. */
    fun next(instance: String, waitSeconds: Int): List<RelayCommand>

    fun reply(cmd: String, reply: JsonObject)

    fun state(id: String, state: JsonObject)
}

/** The server does not take this phone's token. */
class RelayUnauthorized : IOException("the server refused the token")

/**
 * The phone's side of the mailbox: fetches what waits, runs it through [handler] as if it had come
 * over adb, and tells the server how it went. [instance] is different in every process, so that the
 * server knows when it has to deliver something again.
 *
 * What cannot be sent now is sent with a later round. Replies wait here in memory; states are read
 * from the request store when they are sent, so nothing outlives what the store keeps (KEPT_MS).
 */
class RelayClient(
    private val transport: RelayTransport,
    private val handler: PhoneRequestHandler,
    private val instance: String,
    private val log: (String) -> Unit = {},
) {
    private val queues = Any()
    private val replies = LinkedHashMap<String, JsonObject>()
    private val states = LinkedHashSet<String>()
    private val sending = Any()

    /** The 15-minute job and the loop can both have a command: a cancel must find the request that came before it. */
    private val running = Any()

    /** One round: send what is left, fetch and run what waits, and send the answers. */
    fun pollOnce(waitSeconds: Int) {
        flush()
        for (command in transport.next(instance, waitSeconds)) {
            try {
                run(command)
            } catch (e: RuntimeException) {
                // The server sends it again: the ones behind it must not wait for this one
                log("command not run: ${e.javaClass.simpleName}")
            }
        }
        flush()
    }

    /** The request [id] changed. If it cannot be told now, it is told with the next round. */
    fun pushState(id: String) {
        synchronized(queues) { states += id }
        try {
            flush()
        } catch (e: IOException) {
            log("state not sent: ${e.javaClass.simpleName}")
        }
    }

    private fun run(command: RelayCommand) {
        if (command.method !in ACCEPTED) return
        val reply = synchronized(running) {
            PhoneRequestCodec.decode(handler.handle(command.method, PhoneRequestCodec.encode(command.arg), command.id, command.assistant))
        } ?: return
        synchronized(queues) { replies[command.cmd] = reply }
    }

    private fun flush() = synchronized(sending) {
        for ((cmd, reply) in synchronized(queues) { replies.toList() }) {
            transport.reply(cmd, reply)
            synchronized(queues) { replies.remove(cmd) }
        }
        for (id in synchronized(queues) { states.toList() }) {
            stateOf(id)?.let { transport.state(id, it) }
            synchronized(queues) { states.remove(id) }
        }
    }

    /** What the sender would read now: a request the store has forgotten has nothing to tell. */
    private fun stateOf(id: String): JsonObject? {
        val arg = PhoneRequestCodec.encode(buildJsonObject { put("id", id) })
        return PhoneRequestCodec.decode(handler.handle(PhoneRequestProtocol.METHOD_GET, arg))
            ?.takeIf { (it["ok"] as? JsonPrimitive)?.booleanOrNull == true }
    }

    private companion object {
        val ACCEPTED = setOf(PhoneRequestProtocol.METHOD_CREATE, PhoneRequestProtocol.METHOD_CANCEL)
    }
}
