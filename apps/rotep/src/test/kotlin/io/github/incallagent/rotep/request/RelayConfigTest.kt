// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayConfigTest {
    private val steps = ArrayList<String>()

    private fun save(savedUrl: String?, address: String, token: String, urlWritten: Boolean = true, tokenWritten: Boolean = true) =
        saveRelaySettings(
            savedUrl,
            address,
            token,
            { steps += "clear" },
            {
                steps += "url"
                urlWritten
            },
            {
                steps += "token"
                tokenWritten
            },
        )

    @Test
    fun aNewAddressLosesTheOldTokenBeforeAnythingElse() {
        assertNull(save("https://old.example.test", "https://new.example.test", "t"))
        assertEquals(listOf("clear", "url", "token"), steps)
    }

    @Test
    fun theFirstSaveDoesTheSame() {
        assertNull(save(null, "https://new.example.test", "t"))
        assertEquals(listOf("clear", "url", "token"), steps)
    }

    @Test
    fun aNewTokenForTheSameAddressKeepsTheOldOneUntilItIsReplaced() {
        assertNull(save("https://same.example.test", "https://same.example.test", "t"))
        assertEquals(listOf("url", "token"), steps)
    }

    @Test
    fun theSameAddressWithoutATokenChangesNoToken() {
        assertNull(save("https://same.example.test", "https://same.example.test", " "))
        assertEquals(listOf("url"), steps)
    }

    @Test
    fun noTokenIsWrittenForAnAddressThatCouldNotBeWritten() {
        assertEquals("サーバーの URL を保存できませんでした", save(null, "https://new.example.test", "t", urlWritten = false))
        assertEquals(listOf("clear", "url"), steps)
    }

    @Test
    fun aTokenThatCouldNotBeWrittenIsSaid() {
        assertEquals("トークンを保存できませんでした", save(null, "https://new.example.test", "t", tokenWritten = false))
    }

    @Test
    fun aRoundThatCameBackAtOnceIsFollowedByAPause() {
        assertEquals(5_000L, relayRoundPause(0))
        assertEquals(4_000L, relayRoundPause(1_000))
    }

    @Test
    fun aRoundThatWaitedAtTheServerIsFollowedByTheNext() {
        assertEquals(0L, relayRoundPause(5_000))
        assertEquals(0L, relayRoundPause(25_000))
    }
}
