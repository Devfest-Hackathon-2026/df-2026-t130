// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.OutcomeStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveToolsTest {
    private val status = LiveTools.declarations()
        .map { it.jsonObject }
        .single { it["name"]!!.jsonPrimitive.content == LiveTools.REPORT_RESULT }
        .getValue("parameters").jsonObject
        .getValue("properties").jsonObject
        .getValue("status").jsonObject

    private val description = status.getValue("description").jsonPrimitive.content

    @Test
    fun reportResultOffersAndExplainsEveryStatus() {
        val names = OutcomeStatus.entries.map { it.name }
        assertEquals(names, status.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        for (name in names) assertTrue(name, description.contains("$name="))
    }

    @Test
    fun doneIsExplainedAsAnErrandOtherThanBooking() {
        assertTrue(description, description.contains("DONE=予約以外の用件が済んだ"))
        assertTrue(description, description.contains("中身は summary に書く"))
    }

    private fun parametersOf(name: String) = LiveTools.declarations()
        .map { it.jsonObject }
        .single { it["name"]!!.jsonPrimitive.content == name }
        .getValue("parameters").jsonObject

    @Test
    fun evaluateOfferRequiresNoDateForAFeeOnlyOffer() {
        val parameters = parametersOf(LiveTools.EVALUATE_OFFER)
        assertFalse(parameters.toString(), parameters.containsKey("required"))
        val start = parameters.getValue("properties").jsonObject.getValue("start").jsonObject
        val text = start.getValue("description").jsonPrimitive.content
        assertTrue(text, text.contains("キャンセル料だけの話のときは要らない"))
    }

    @Test
    fun theOtherToolsKeepWhatTheyRequire() {
        fun required(name: String) = parametersOf(name).getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("status", "summary"), required(LiveTools.REPORT_RESULT))
        assertEquals(listOf("reason"), required(LiveTools.END_CALL))
        assertEquals(listOf("digits"), required(LiveTools.SEND_DTMF))
    }
}
