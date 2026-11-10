// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptTest {
    @Test
    fun aStepThatFailsDoesNotStopTheNext() {
        val logs = ArrayList<String>()
        val done = ArrayList<String>()
        attempt("first", { logs += it }) { error("no") }
        attempt("second", { logs += it }) { done += "second" }
        assertEquals(listOf("second"), done)
        assertEquals("what failed, and only the class", listOf("first failed: IllegalStateException"), logs)
    }
}
