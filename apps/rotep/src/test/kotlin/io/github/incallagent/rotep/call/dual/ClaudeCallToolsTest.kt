// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.CallActions
import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.LiveTools
import io.github.incallagent.agent.live.ToolCallHandler
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.practice.PracticePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ClaudeCallToolsTest {
    private val tools = ClaudeCallTools.tools().map { it.jsonObject }

    private fun tool(name: String) = tools.single { it.getValue("name").jsonPrimitive.content == name }

    private fun properties(name: String) = tool(name).getValue("input_schema").jsonObject.getValue("properties").jsonObject

    private fun strings(element: JsonElement) = element.jsonArray.map { it.jsonPrimitive.content }

    private fun typesIn(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) -> if (key == "type" && value is JsonPrimitive) listOf(value.content) else typesIn(value) }
        is JsonArray -> element.flatMap(::typesIn)
        else -> emptyList()
    }

    @Test
    fun everyToolOfACallIsOffered() {
        assertEquals(LiveTools.NAMES, tools.map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun aToolHasWhatTheApiTakesAndNothingElse() {
        for (t in tools) assertEquals(setOf("name", "description", "input_schema"), t.keys)
    }

    @Test
    fun theDescriptionsAreTheOnesOfTheVoiceModel() {
        val written = LiveTools.declarations().map { it.jsonObject.getValue("description") }
        assertEquals(written, tools.map { it.getValue("description") })
    }

    @Test
    fun typesAreInLowerCaseAtEveryDepth() {
        val types = tools.flatMap { typesIn(it.getValue("input_schema")) }.toSet()
        assertEquals(setOf("object", "string", "integer", "boolean", "array"), types)
        val preferences = properties(LiveTools.REPORT_RESULT).getValue("confirmedPreferences").jsonObject
        assertEquals("string", preferences.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun theValuesOfAnEnumStayAsTheyAre() {
        val status = properties(LiveTools.REPORT_RESULT).getValue("status").jsonObject
        assertEquals(OutcomeStatus.entries.map { it.name }, strings(status.getValue("enum")))
    }

    @Test
    fun whatIsRequiredStaysRequired() {
        assertEquals(listOf("status", "summary"), strings(tool(LiveTools.REPORT_RESULT).getValue("input_schema").jsonObject.getValue("required")))
        // OfferEvaluator refuses a booking without a start: it is not the schema's to say
        assertFalse("required" in tool(LiveTools.EVALUATE_OFFER).getValue("input_schema").jsonObject)
    }

    private fun toolUse(id: String = "toolu_1", name: String = LiveTools.END_CALL, input: JsonObject? = buildJsonObject { put("reason", "用件が済んだ") }) =
        buildJsonObject {
            put("type", "tool_use")
            put("id", id)
            put("name", name)
            if (input != null) put("input", input)
        }

    @Test
    fun aToolUseBecomesACall() {
        val input = buildJsonObject { put("reason", "用件が済んだ") }
        assertEquals(FunctionCall("toolu_1", LiveTools.END_CALL, input), ClaudeCallTools.toFunctionCall(toolUse(input = input)))
    }

    @Test
    fun theOtherBlocksOfAnAnswerAreNotCalls() {
        val text = buildJsonObject {
            put("type", "text")
            put("text", "はい")
        }
        val thinking = buildJsonObject {
            put("type", "thinking")
            put("thinking", "")
            put("signature", "x")
        }
        assertNull(ClaudeCallTools.toFunctionCall(text))
        assertNull(ClaudeCallTools.toFunctionCall(thinking))
    }

    @Test
    fun aToolUseWithNoIdCouldNotBeAnswered() {
        assertNull(ClaudeCallTools.toFunctionCall(toolUse(id = "")))
        assertNull(ClaudeCallTools.toFunctionCall(JsonObject(toolUse() - "id")))
        assertNull(ClaudeCallTools.toFunctionCall(JsonObject(toolUse() - "name")))
    }

    @Test
    fun anInputThatIsNotAnObjectIsNoArguments() {
        assertEquals(JsonObject(emptyMap()), ClaudeCallTools.toFunctionCall(toolUse(input = null))!!.args)
        val odd = JsonObject(toolUse() + ("input" to JsonPrimitive("文字")))
        assertEquals(JsonObject(emptyMap()), ClaudeCallTools.toFunctionCall(odd)!!.args)
    }

    private val today = LocalDate.of(2026, 11, 7)
    private val policy = PracticePolicy.approved(today, Instant.parse("2026-11-07T10:00:00Z"))
    private val handler = ToolCallHandler(
        policy,
        object : CallActions {
            override fun endCall(reason: String) = Unit

            override fun sendDtmf(digits: String) = Unit
        },
    )

    private fun run(block: JsonObject) = ClaudeCallTools.toolResult(handler.handle(ClaudeCallTools.toFunctionCall(block)!!))

    @Test
    fun anAnswerGoesBackUnderTheIdOfItsCall() {
        val input = buildJsonObject {
            put("start", policy.request.windows.first().earliestStart.toString())
            put("partySize", 2)
            putJsonArray("confirmedPreferences") { add("禁煙席") }
        }
        val result = run(toolUse("toolu_9", LiveTools.EVALUATE_OFFER, input))
        assertEquals("tool_result", result.getValue("type").jsonPrimitive.content)
        assertEquals("toolu_9", result.getValue("tool_use_id").jsonPrimitive.content)
        assertFalse("is_error" in result)
        val answer = Json.parseToJsonElement(result.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals("true", answer.getValue("accept").jsonPrimitive.content)
    }

    @Test
    fun aToolTheAppDoesNotKnowStillGetsItsAnswer() {
        val result = run(toolUse(name = "other_tool", input = JsonObject(emptyMap())))
        assertEquals("toolu_1", result.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals("true", result.getValue("is_error").jsonPrimitive.content)
    }

    @Test
    fun aRefusalOfTheHandlerIsMarkedAsAnError() {
        assertEquals("true", run(toolUse()).getValue("is_error").jsonPrimitive.content)
    }

    @Test
    fun aLaterReportReplacesTheEarlierOne() {
        // The instruction has a result reported as soon as it is known, and again when it changes
        fun report(status: OutcomeStatus) = run(
            toolUse(
                name = LiveTools.REPORT_RESULT,
                input = buildJsonObject {
                    put("status", status.name)
                    put("summary", "要約")
                    putJsonArray("pendingItems") { add("理由") }
                },
            ),
        )
        report(OutcomeStatus.UNAVAILABLE)
        report(OutcomeStatus.NEEDS_USER_DECISION)
        assertEquals(OutcomeStatus.NEEDS_USER_DECISION, handler.outcome!!.status)
    }

    @Test
    fun aReportWithProblemsIsAnAnswerToActOn() {
        val input = buildJsonObject {
            put("status", OutcomeStatus.NEEDS_USER_DECISION.name)
            put("summary", "折り返しにした")
        }
        val result = run(toolUse(name = LiveTools.REPORT_RESULT, input = input))
        val answer = Json.parseToJsonElement(result.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals("false", answer.getValue("ok").jsonPrimitive.content)
        assertTrue(answer.getValue("problems").jsonArray.isNotEmpty())
        assertFalse("is_error" in result)
    }
}
