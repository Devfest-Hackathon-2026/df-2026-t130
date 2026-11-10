// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.LiveTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSessionSummaryTest {
    private val nothing = JsonObject(emptyMap())

    private fun summaryOf(status: String) =
        CallSession.summary(FunctionCall("id", LiveTools.REPORT_RESULT, JsonObject(mapOf("status" to JsonPrimitive(status)))), nothing)

    @Test
    fun aKnownStatusIsLogged() {
        assertEquals("status=DONE", summaryOf("DONE"))
    }

    @Test
    fun whateverElseTheModelWroteIsNot() {
        assertEquals("-", summaryOf("山田さんの予約"))
    }
}
