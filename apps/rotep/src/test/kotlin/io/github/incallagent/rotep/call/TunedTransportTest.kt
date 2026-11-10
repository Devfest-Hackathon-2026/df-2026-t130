// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.LiveTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunedTransportTest {
    private class Wire : LiveTransport {
        val sent = mutableListOf<String>()
        val closed = mutableListOf<Pair<Int, String>>()

        override fun send(text: String): Boolean = sent.add(text)

        override fun close(code: Int, reason: String) {
            closed += code to reason
        }
    }

    @Test
    fun onlyTheFirstMessageIsTuned() {
        val wire = Wire()
        var calls = 0
        val t = TunedTransport(wire) { calls++; "tuned:$it" }

        t.send("setup")
        t.send("audio")
        t.send("tool response")

        assertEquals(listOf("tuned:setup", "audio", "tool response"), wire.sent)
        assertEquals(1, calls)
    }

    @Test
    fun aSetupThatCouldNotBeTunedIsNotSent() {
        // It would go out without the safety rules: no AI is better than that one
        val wire = Wire()
        val t = TunedTransport(wire) { error("broken") }

        assertFalse(t.send("setup"))

        assertTrue(wire.sent.isEmpty())
        assertEquals(1, wire.closed.size)
    }

    @Test
    fun closeGoesThrough() {
        val wire = Wire()
        TunedTransport(wire) { it }.close(1000, "client closed")
        assertEquals(listOf(1000 to "client closed"), wire.closed)
    }
}
