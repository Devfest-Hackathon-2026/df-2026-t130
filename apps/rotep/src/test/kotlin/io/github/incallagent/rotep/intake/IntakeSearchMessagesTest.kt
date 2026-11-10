// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.intake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeSearchMessagesTest {
    private val query = ChatQuery("カフェ・サンプル", listOf("11月14日(土) 19:00"), "2")

    private fun quoted(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    private val result = """{"type":"web_search_tool_result","tool_use_id":"x","content":[{"type":"web_search_result","url":"https://example.com/a","title":"A","encrypted_content":"e"}]}"""

    private fun response(text: String, stop: String = "end_turn", searchResult: String = result) = """
        {"stop_reason":"$stop","content":[
          {"type":"text","text":"調べます"},
          {"type":"server_tool_use","id":"x","name":"web_search","input":{"query":"q"}},
          $searchResult,
          {"type":"text","text":${quoted(text)},"citations":[{"type":"web_search_result_location","url":"https://example.com/a","title":"お店","cited_text":"c"},{"type":"web_search_result_location","url":"http://example.com/b","title":"b"}]}
        ]}
    """.trimIndent()

    @Test
    fun theRequestSearchesApartFromTheChatAndCarriesNoNumber() {
        val body = Json.parseToJsonElement(IntakeSearchMessages.body(query)).jsonObject
        assertEquals("claude-sonnet-5-5", body.getValue("model").jsonPrimitive.content)
        assertFalse("citations and a JSON format do not go together", body.getValue("output_config").jsonObject.containsKey("format"))
        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("web_search_20250305", tool.getValue("type").jsonPrimitive.content)
        assertEquals("JP", tool.getValue("user_location").jsonObject.getValue("country").jsonPrimitive.content)
        val prompt = IntakeSearchMessages.prompt(query)
        assertTrue(prompt.contains("お店：カフェ・サンプル"))
        assertTrue(prompt.contains("希望の日時：11月14日(土) 19:00"))
        assertFalse(prompt.contains("電話番号"))
    }

    @Test
    fun whatWasFoundComesWithItsSources() {
        val found = IntakeSearchMessages.parse(response("結果：見つかった\n1. 合わないようです。19時開店です")) as IntakeSearchResult.Found
        assertEquals("1. 合わないようです。19時開店です", found.findings.text)
        assertEquals(listOf("お店"), found.findings.sources.map { it.title })
        assertTrue(found.findings.suggestionsHtml.isEmpty())
    }

    @Test
    fun aShopNotFoundIsNotAnError() {
        assertEquals(IntakeSearchResult.NotFound(IntakeSearchMessages.NOT_FOUND), IntakeSearchMessages.parse(response("結果：見つからない")))
    }

    @Test
    fun anAnswerWithoutASearchIsNotUsed() {
        val json = """{"stop_reason":"end_turn","content":[{"type":"text","text":"結果：見つかった\\n覚えている話"}]}"""
        assertTrue(IntakeSearchMessages.parse(json) is IntakeSearchResult.NotFound)
    }

    @Test
    fun anErrorInsideASuccessIsAFailure() {
        val error = """{"type":"web_search_tool_result","tool_use_id":"x","content":{"type":"web_search_tool_result_error","error_code":"unavailable"}}"""
        assertTrue(IntakeSearchMessages.parse(response("結果：見つかった\n本文", searchResult = error)) is IntakeSearchResult.Failed)
    }

    @Test
    fun noResultsIsNotFound() {
        val empty = """{"type":"web_search_tool_result","tool_use_id":"x","content":[]}"""
        assertTrue(IntakeSearchMessages.parse(response("結果：見つかった\n本文", searchResult = empty)) is IntakeSearchResult.NotFound)
    }

    @Test
    fun oneFailedOrEmptySearchDoesNotUndoAGoodOne() {
        val good = """{"type":"web_search_tool_result","tool_use_id":"y","content":[{"type":"web_search_result","url":"https://example.com/a","title":"A","encrypted_content":"e"}]}"""
        val error = """{"type":"web_search_tool_result","tool_use_id":"x","content":{"type":"web_search_tool_result_error","error_code":"max_uses_exceeded"}}"""
        val empty = """{"type":"web_search_tool_result","tool_use_id":"x","content":[]}"""
        for (last in listOf(error, empty)) {
            assertTrue(IntakeSearchMessages.parse(response("結果：見つかった\n本文", searchResult = "$good,$last")) is IntakeSearchResult.Found)
        }
    }

    @Test
    fun anAnswerCutOffIsNeverFound() {
        for (stop in listOf("max_tokens", "pause_turn", "refusal")) {
            assertTrue(stop, IntakeSearchMessages.parse(response("結果：見つかった\n本文", stop = stop)) is IntakeSearchResult.Failed)
        }
    }
}
