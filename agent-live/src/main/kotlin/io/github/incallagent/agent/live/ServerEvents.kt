// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** What a Live API server message means for the app. One message can carry several events. */
sealed interface ServerEvent {
    data object SetupComplete : ServerEvent

    /** Model speech: 16-bit little-endian mono PCM at [sampleRate]. */
    class Audio(val pcm: ByteArray, val sampleRate: Int) : ServerEvent {
        override fun toString() = "Audio(${pcm.size} bytes @ $sampleRate Hz)"
    }

    /** The user (the shop) started talking over the model: stop playback now. */
    data object Interrupted : ServerEvent
    data object GenerationComplete : ServerEvent
    data object TurnComplete : ServerEvent
    data class InputTranscript(val text: String) : ServerEvent
    data class OutputTranscript(val text: String) : ServerEvent
    data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
    data class ToolCallCancellation(val ids: List<String>) : ServerEvent

    /** The server will close the connection soon ([timeLeft] as sent, e.g. "10s"). */
    data class GoAway(val timeLeft: String?) : ServerEvent
}

/**
 * Parses `BidiGenerateContentServerMessage` (Live API reference: setupComplete, serverContent,
 * toolCall, toolCallCancellation, goAway; serverContent.modelTurn.parts[].inlineData,
 * interrupted, generationComplete, turnComplete, inputTranscription, outputTranscription).
 * Unknown fields are ignored.
 */
object ServerEvents {
    /** Output rate of Live models when the part does not say (decisions.md: output is 24 kHz). */
    const val DEFAULT_OUTPUT_RATE = 24_000

    private val RATE = Regex("rate=(\\d+)")

    fun parse(message: JsonObject): List<ServerEvent> {
        val out = ArrayList<ServerEvent>()

        if (message.containsKey("setupComplete")) out += ServerEvent.SetupComplete

        (message["serverContent"] as? JsonObject)?.let { sc ->
            if (sc.flag("interrupted")) out += ServerEvent.Interrupted
            sc.obj("inputTranscription")?.text()?.let { out += ServerEvent.InputTranscript(it) }
            (sc.obj("modelTurn")?.get("parts") as? JsonArray)?.forEach { part ->
                val inline = (part as? JsonObject)?.obj("inlineData") ?: return@forEach
                val mime = inline.str("mimeType") ?: ""
                val data = inline.str("data") ?: return@forEach
                if (mime.isEmpty() || mime.startsWith("audio/")) {
                    // A broken chunk is dropped: its decode error would otherwise end the whole conversation
                    val pcm = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return@forEach
                    out += ServerEvent.Audio(pcm, rateOf(mime))
                }
            }
            sc.obj("outputTranscription")?.text()?.let { out += ServerEvent.OutputTranscript(it) }
            if (sc.flag("generationComplete")) out += ServerEvent.GenerationComplete
            if (sc.flag("turnComplete")) out += ServerEvent.TurnComplete
        }

        val calls = LiveMessages.functionCalls(message)
        if (calls.isNotEmpty()) out += ServerEvent.ToolCall(calls)

        (message["toolCallCancellation"] as? JsonObject)?.let { c ->
            val ids = (c["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            out += ServerEvent.ToolCallCancellation(ids)
        }

        (message["goAway"] as? JsonObject)?.let { out += ServerEvent.GoAway(it.str("timeLeft")) }

        return out
    }

    /** "audio/pcm;rate=24000" -> 24000 */
    fun rateOf(mimeType: String): Int =
        RATE.find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_OUTPUT_RATE

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.text() = str("text")?.takeIf { it.isNotEmpty() }
}
