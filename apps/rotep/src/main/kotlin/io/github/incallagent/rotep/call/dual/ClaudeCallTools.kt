// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The tools of a call in the form of the Claude Messages API. There is one list of tools
 * ([LiveTools]): what they mean and what they answer stays with agent-live.
 */
object ClaudeCallTools {
    /** The `tools` of a request. */
    fun tools(): JsonArray = JsonArray(
        LiveTools.declarations().map { declaration ->
            val function = declaration as JsonObject
            buildJsonObject {
                put("name", function.getValue("name"))
                put("description", function.getValue("description"))
                put("input_schema", lowerCaseTypes(function.getValue("parameters")))
            }
        },
    )

    /**
     * The call in one block of a response's `content`. null for any other block, so the content can
     * be mapped as it is. A tool the app does not know comes through: [ToolCallHandler][io.github.incallagent.agent.live.ToolCallHandler]
     * answers it with an error, and every `tool_use` has to get an answer.
     */
    fun toFunctionCall(block: JsonObject): FunctionCall? {
        if (block.text("type") != "tool_use") return null
        val id = block.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = block.text("name") ?: return null
        return FunctionCall(id, name, block["input"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** The answer as a `tool_result` block. Its id is the one [toFunctionCall] read from the `tool_use`. */
    fun toolResult(response: FunctionResponse): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", response.id.orEmpty())
        // A refusal of the handler. An ok of false with its problems is an answer to act on, not a failure
        if ("error" in response.response) put("is_error", true)
        put("content", response.response.toString())
    }

    /** Gemini's schema writes its types in capitals. Only the value of a `type` changes: enum values stay as they are. */
    private fun lowerCaseTypes(schema: JsonElement): JsonElement = when (schema) {
        is JsonObject -> JsonObject(
            schema.mapValues { (key, value) ->
                if (key == "type" && value is JsonPrimitive && value.isString) JsonPrimitive(value.content.lowercase()) else lowerCaseTypes(value)
            },
        )
        is JsonArray -> JsonArray(schema.map(::lowerCaseTypes))
        else -> schema
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
