// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    @Test
    fun truncateKeepsOnlyEnds() {
        assertEquals("09<...>78", Redact.truncate("09012345678"))
    }

    @Test
    fun shortStringsAreFullyHidden() {
        assertEquals("<...>", Redact.truncate("1234"))
        assertEquals("<...>", Redact.truncate(""))
    }

    @Test
    fun phoneNumberNullOrEmpty() {
        assertEquals("<none>", Redact.phoneNumber(null))
        assertEquals("<none>", Redact.phoneNumber(""))
    }

    @Test
    fun handleKeepsScheme() {
        val masked = Redact.handle("tel", "0312345678")
        assertEquals("tel:03<...>78", masked)
        assertFalse(masked.contains("123456"))
    }

    @Test
    fun nameNeverLeaksCharacters() {
        assertEquals("<name len=4>", Redact.name("山田太郎"))
        assertEquals("<none>", Redact.name(null))
    }
}
