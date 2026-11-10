// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSetupTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `the mouth is the voice model and speaks`() {
        val setup = parse(VoiceSetup.mouth("Kore")).obj("setup")
        assertEquals("models/gemini-3.8-live", setup.text("model"))
        assertEquals("AUDIO", setup.obj("generationConfig").getValue("responseModalities").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `the mouth has the chosen voice and no language`() {
        val speech = parse(VoiceSetup.mouth("Kore")).obj("setup").obj("generationConfig").obj("speechConfig")
        assertEquals("Kore", speech.obj("voiceConfig").obj("prebuiltVoiceConfig").text("voiceName"))
        assertFalse(speech.containsKey("languageCode"))
    }

    @Test
    fun `without a chosen voice the model's own is used`() {
        assertFalse(parse(VoiceSetup.mouth(null)).obj("setup").obj("generationConfig").containsKey("speechConfig"))
    }

    @Test
    fun `the mouth is told to read and nothing else`() {
        val rule = parse(VoiceSetup.mouth(null)).obj("setup").obj("systemInstruction").getValue("parts").jsonArray.single().jsonObject.text("text")
        assertTrue(rule.contains("【読み上げ】"))
        assertTrue(rule.contains("一字一句"))
        assertTrue(rule.contains("足さない"))
        assertTrue(rule.contains("言い換えない"))
        assertTrue(rule.contains("答えない"))
    }

    @Test
    fun `the mouth has no tools and hears nothing, and says what it spoke`() {
        val setup = parse(VoiceSetup.mouth(null)).obj("setup")
        assertFalse(setup.containsKey("tools"))
        assertFalse(setup.containsKey("inputAudioTranscription"))
        assertEquals(JsonObject(emptyMap()), setup.obj("outputAudioTranscription"))
    }

    @Test
    fun `the ear is the transcription model and answers in text`() {
        val setup = parse(VoiceSetup.ear()).obj("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.text("model"))
        assertEquals("TEXT", setup.obj("generationConfig").getValue("responseModalities").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `the ear listens for Japanese`() {
        val heard = parse(VoiceSetup.ear()).obj("setup").obj("inputAudioTranscription")
        assertEquals("ja-JP", heard.getValue("languageCodes").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `the ear has no instruction and no tools`() {
        val setup = parse(VoiceSetup.ear()).obj("setup")
        assertFalse(setup.containsKey("systemInstruction"))
        assertFalse(setup.containsKey("tools"))
    }

    @Test
    fun `a line goes to the mouth as one finished turn`() {
        val content = parse(VoiceSetup.speak("はい。")).obj("clientContent")
        assertEquals(true.toString(), content.text("turnComplete"))
        val turn = content.getValue("turns").jsonArray.single().jsonObject
        assertEquals("user", turn.text("role"))
        assertEquals("【読み上げ】はい。", turn.getValue("parts").jsonArray.single().jsonObject.text("text"))
    }

    @Test
    fun `a line with quotes stays valid`() {
        val line = "「はい」\"と\\言う\n"
        val turn = parse(VoiceSetup.speak(line)).obj("clientContent").getValue("turns").jsonArray.single().jsonObject
        assertEquals("【読み上げ】$line", turn.getValue("parts").jsonArray.single().jsonObject.text("text"))
    }
}
