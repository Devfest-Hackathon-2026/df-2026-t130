// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.DelegationPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.util.Base64

/**
 * JSON messages of the Gemini Live API WebSocket (BidiGenerateContent), built from a policy.
 *
 * Field names follow the Live API reference (BidiGenerateContentSetup: model, generationConfig,
 * systemInstruction, tools, inputAudioTranscription, outputAudioTranscription; tool calls are
 * matched by FunctionCall/FunctionResponse id). "setup", "realtimeInput.audio" and "toolCall"
 * appear in the official WebSocket guide; "clientContent" and "toolResponse" have not been
 * checked against a live connection yet: see docs/live-agent.md.
 */
object LiveMessages {
    /** ADR-005 */
    const val DEFAULT_MODEL = "gemini-3.8-live"

    /** Input audio format of the Live API: 16-bit little-endian mono PCM. */
    const val INPUT_SAMPLE_RATE = 16_000

    /** One chunk of microphone (later: call downlink) audio. */
    fun realtimeAudio(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset): JsonObject =
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    // A whole array goes out as it is: this runs for every chunk, about 25 times a second
                    val whole = offset == 0 && length == pcm16le.size
                    val chunk = if (whole) pcm16le else pcm16le.copyOfRange(offset, offset + length)
                    put("data", Base64.getEncoder().encodeToString(chunk))
                    put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                }
            }
        }

    /** Tells the server the audio stream paused (e.g. the microphone was turned off). */
    fun audioStreamEnd(): JsonObject = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }

    /** First message after the WebSocket opens. */
    fun setup(policy: DelegationPolicy, today: LocalDate, model: String = DEFAULT_MODEL): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    put("languageCode", policy.conversation.language)
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", LiveInstructions.render(policy, today)) })
                }
            }
            putJsonArray("tools") {
                add(buildJsonObject { put("functionDeclarations", LiveTools.declarations()) })
            }
            // Transcripts of both sides, for the result report and the thinking agent
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }

    /**
     * Sent when the call becomes ACTIVE (docs/design.md: the bridge and the model start only
     * after the shop answers), so the model speaks the AI disclosure first.
     */
    fun openingCue(): JsonObject = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", "（電話がつながり、お店の方が出ました。最初のあいさつから始めてください。）")
                        })
                    }
                })
            }
            put("turnComplete", true)
        }
    }

    /** `toolCall.functionCalls[]` of a server message, or empty if it is not a tool call. */
    fun functionCalls(serverMessage: JsonObject): List<FunctionCall> {
        val calls = (serverMessage["toolCall"] as? JsonObject)?.get("functionCalls") as? JsonArray ?: return emptyList()
        return calls.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            FunctionCall(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull,
                name = name,
                args = (o["args"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }
    }

    /** Answer to one server tool call message. */
    fun toolResponse(responses: List<FunctionResponse>): JsonObject = buildJsonObject {
        putJsonObject("toolResponse") {
            put("functionResponses", buildJsonArray {
                for (r in responses) {
                    add(buildJsonObject {
                        r.id?.let { put("id", it) }
                        put("name", r.name)
                        put("response", r.response)
                    })
                }
            })
        }
    }

    /** Convenience: run every call of a server message through [handler]. Null if none. */
    fun answerToolCalls(serverMessage: JsonObject, handler: ToolCallHandler): JsonObject? {
        val calls = functionCalls(serverMessage)
        if (calls.isEmpty()) return null
        return toolResponse(calls.map(handler::handle))
    }
}
