// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InCallCheckTest {
    private val inCall = UplinkTestRules.MODE_IN_CALL
    private val normal = 0
    private var clock = 0L
    private val slept = ArrayList<Long>()

    private fun await(read: () -> Int?) = InCallCheck.await(read, { clock }, {
        slept += it
        clock += it
    })

    @Test
    fun aCallAlreadyOnIsNotWaitedFor() {
        assertEquals(inCall, await { inCall })
        assertTrue(slept.isEmpty())
    }

    @Test
    fun aModeThatComesAMomentLaterIsWaitedFor() {
        val modes = ArrayDeque(listOf(normal, normal, inCall))
        assertEquals(inCall, await { modes.removeFirst() })
        assertEquals(listOf(InCallCheck.POLL_MS, InCallCheck.POLL_MS), slept)
    }

    @Test
    fun noCallIsGivenUpOnAfterTheWait() {
        assertEquals(normal, await { normal })
        assertTrue(clock >= InCallCheck.WAIT_MS)
        assertTrue("about one poll past the limit at most", clock < InCallCheck.WAIT_MS + 2 * InCallCheck.POLL_MS)
    }

    @Test
    fun aModeThatCannotBeReadIsReportedAsThat() {
        assertNull(await { null })
        assertTrue(InCallCheck.refusal(null), InCallCheck.refusal(null).contains("読めなかった"))
    }

    @Test
    fun theRefusalSaysTheModeItSaw() {
        val text = InCallCheck.refusal(normal)
        assertTrue(text, text.contains("通話中ではない"))
        assertTrue(text, text.contains("MODE_NORMAL (0)"))
        assertTrue(InCallCheck.refusal(3), InCallCheck.refusal(3).contains("MODE_IN_COMMUNICATION (3)"))
    }
}
