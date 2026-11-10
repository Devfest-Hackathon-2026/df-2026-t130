// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.LiveMessages
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What goes over the two sockets of the voice model besides the audio (ADR-053). One Gemini session
 * cannot be told to listen and not answer, so the ears and the mouth are two: the ears only write down
 * what the other side says, and the mouth only reads out the lines it is given.
 */
object VoiceSetup {
    private const val EAR_MODEL = "models/gemini-3.5-transcribe-live"
    private const val EAR_LANGUAGE = "ja-JP"
    private const val READ_OUT = "【読み上げ】"
    private const val MOUTH_RULE = "${READ_OUT}の後の文を一字一句そのまま読む。足さない、言い換えない、答えない"

    /** First message of the mouth. It has no tools and hears nothing. [voiceName]: null is the model's own voice. */
    fun mouth(voiceName: String?): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/${LiveMessages.DEFAULT_MODEL}")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                if (voiceName != null) {
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") { put("voiceName", voiceName) }
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", MOUTH_RULE) } }
            }
            putJsonObject("outputAudioTranscription") {}
        }
    }.toString()

    /** First message of the ears. */
    fun ear(): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", EAR_MODEL)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("TEXT")) }
            }
            putJsonObject("inputAudioTranscription") {
                putJsonArray("languageCodes") { add(JsonPrimitive(EAR_LANGUAGE)) }
            }
        }
    }.toString()

    /** Makes the mouth say [text]: one finished turn at a time. */
    fun speak(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", READ_OUT + text) } }
                }
            }
            put("turnComplete", true)
        }
    }.toString()
}
