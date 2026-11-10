// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveMessagesTest {
    private val policy = TestPolicy.example()

    @Test
    fun setupMessage() {
        val setup = LiveMessages.setup(policy, TestPolicy.today)["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)

        val text = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("AIによる代理のお電話です"))

        val decls = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(LiveTools.NAMES, decls.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        for (d in decls) {
            assertEquals("OBJECT", d.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun toolCallRoundTrip() {
        val server = Json.parseToJsonElement(
            """{"toolCall":{"functionCalls":[
                {"id":"c1","name":"send_dtmf","args":{"digits":"2"}},
                {"id":"c2","name":"end_call","args":{"reason":"x"}}
            ]}}"""
        ).jsonObject
        val actions = object : CallActions {
            val dtmf = ArrayList<String>()
            override fun endCall(reason: String) {}
            override fun sendDtmf(digits: String) { dtmf += digits }
        }
        val handler = ToolCallHandler(policy, actions)

        val reply = LiveMessages.answerToolCalls(server, handler)!!["toolResponse"]!!.jsonObject
        val responses = reply["functionResponses"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("c1", "c2"), responses.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("2"), actions.dtmf)
        // end_call is refused before report_result
        assertEquals("false", responses[1]["response"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonToolMessagesAreIgnored() {
        val server = Json.parseToJsonElement("""{"serverContent":{"turnComplete":true}}""").jsonObject
        assertNull(LiveMessages.answerToolCalls(server, ToolCallHandler(policy, object : CallActions {
            override fun endCall(reason: String) {}
            override fun sendDtmf(digits: String) {}
        })))
    }

    @Test
    fun partOfAnArrayIsSentAlone() {
        val data = LiveMessages.realtimeAudio(byteArrayOf(9, 1, 0, 2, 0, 9), 1, 4)["realtimeInput"]!!
            .jsonObject["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertEquals(listOf<Byte>(1, 0, 2, 0), Base64.getDecoder().decode(data).toList())
    }

    @Test
    fun openingCueIsAUserTurn() {
        val c = LiveMessages.openingCue()["clientContent"]!!.jsonObject
        assertEquals("true", c["turnComplete"]!!.jsonPrimitive.content)
    }
}
