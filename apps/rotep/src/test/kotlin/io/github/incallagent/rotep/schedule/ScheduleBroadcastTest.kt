// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBroadcastTest {
    private val order = ArrayList<String>()
    private val logs = ArrayList<String>()
    private var deferred: (() -> Unit)? = null

    private fun run(prepare: () -> Unit = { order += "prepare" }, step: () -> Unit = { order += "step" }) {
        prepareThenStep(prepare, { deferred = it }, step, { order += "done" }) { logs += it }
    }

    @Test
    fun theStepWaitsForTheSetUpAndTheBroadcastEndsAfterIt() {
        run()
        assertEquals("only the set-up on this thread", listOf("prepare"), order)
        deferred!!()
        assertEquals(listOf("prepare", "step", "done"), order)
    }

    @Test
    fun aSetUpThatFailedDoesNotStopTheStep() {
        run(prepare = { error("half way") })
        deferred!!()
        assertEquals(listOf("step", "done"), order)
        assertEquals(listOf("set-up failed: IllegalStateException"), logs)
    }

    @Test
    fun aStepThatFailsStillEndsTheBroadcast() {
        run(step = { error("no") })
        deferred!!()
        assertTrue(order.last() == "done")
        assertEquals(listOf("step failed: IllegalStateException"), logs)
    }
}
