// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class AdbPortRetryTest {
    private val stale = 36727
    private val live = 43945

    /** mDNS with [records], first the one that is offered first. Writes down what each search left out. */
    private class Mdns(vararg val records: Int) {
        val searches = mutableListOf<Set<Int>>()

        fun find(refused: Set<Int>): Int? {
            searches += refused.toSet()
            return records.firstOrNull { it !in refused }
        }
    }

    private fun refusedConnection(): Nothing = throw ConnectException("Connection refused")

    @Test
    fun `a port that listens is used at once and nothing more is looked for`() {
        val mdns = Mdns(live, stale)
        val tried = mutableListOf<Int>()
        val result = AdbPorts.firstListening(mdns::find, attempt = { port ->
            tried += port
            "done on $port"
        })
        assertEquals("done on $live", result)
        assertEquals(listOf(live), tried)
        assertEquals(listOf(emptySet<Int>()), mdns.searches)
    }

    @Test
    fun `a record of an earlier session is left out and the new one is used`() {
        val mdns = Mdns(stale, live)
        val tried = mutableListOf<Int>()
        val told = mutableListOf<Int>()
        val result = AdbPorts.firstListening(mdns::find, onRefused = { told += it }, attempt = { port ->
            tried += port
            if (port == stale) refusedConnection()
            port
        })
        assertEquals(live, result)
        assertEquals(listOf(stale, live), tried)
        assertEquals(listOf(stale), told)
        assertEquals(listOf(emptySet(), setOf(stale)), mdns.searches)
    }

    @Test
    fun `no port at all is null and nothing is tried`() {
        val mdns = Mdns()
        var tried = false
        val result = AdbPorts.firstListening<Int>(mdns::find, attempt = {
            tried = true
            it
        })
        assertNull(result)
        assertEquals(false, tried)
    }

    @Test
    fun `when nothing else turns up the refusal is passed on`() {
        val mdns = Mdns(stale)
        val failure = assertThrows(ConnectException::class.java) {
            AdbPorts.firstListening<Int>(mdns::find, attempt = { refusedConnection() })
        }
        assertEquals("Connection refused", failure.message)
        assertEquals(listOf(emptySet(), setOf(stale)), mdns.searches)
    }

    @Test
    fun `only so many ports are tried`() {
        val mdns = Mdns(*IntArray(20) { 40000 + it })
        var tried = 0
        assertThrows(ConnectException::class.java) {
            AdbPorts.firstListening<Int>(mdns::find, attempt = {
                tried++
                refusedConnection()
            })
        }
        assertEquals(AdbPorts.MAX_PORT_TRIES, tried)
    }

    @Test
    fun `something that does not know the key is left out and the real adbd is used`() {
        // Anything on the phone can offer a service under this name
        val other = 45000
        val mdns = Mdns(other, live)
        val result = AdbPorts.firstListening(mdns::find, attempt = { port ->
            if (port == other) throw AdbNotAuthorizedException("not authorized")
            port
        })
        assertEquals(live, result)
    }

    @Test
    fun `a refused key is passed on when no other port turns up`() {
        val mdns = Mdns(stale)
        assertThrows(AdbNotAuthorizedException::class.java) {
            AdbPorts.firstListening<Int>(mdns::find, attempt = { throw AdbNotAuthorizedException("not authorized") })
        }
        assertEquals(listOf(emptySet(), setOf(stale)), mdns.searches)
    }

    @Test
    fun `a refused key counts for more than a closed port`() {
        val mdns = Mdns(live, stale)
        assertThrows(AdbNotAuthorizedException::class.java) {
            AdbPorts.firstListening<Int>(mdns::find, attempt = { port ->
                if (port == live) throw AdbNotAuthorizedException("not authorized")
                refusedConnection()
            })
        }
    }

    @Test
    fun `one refused key does not lose the pairing and several in a row do`() {
        assertEquals(false, AdbStartPolicy.pairingLost(1))
        assertEquals(false, AdbStartPolicy.pairingLost(2))
        assertEquals(true, AdbStartPolicy.pairingLost(3))
    }

    @Test
    fun `any other failure ends the search as well`() {
        var tried = 0
        assertThrows(java.net.SocketTimeoutException::class.java) {
            AdbPorts.firstListening<Int>(Mdns(stale, live)::find, attempt = {
                tried++
                throw java.net.SocketTimeoutException("no answer")
            })
        }
        assertEquals(1, tried)
    }

    @Test
    fun `when no port listens the notice says to check that Wireless debugging is on`() {
        val outcome = AdbStartPolicy.outcomeOf(ConnectException("Connection refused"))
        assertEquals(AdbStartOutcome.NO_PORT, outcome)
        assertTrue(AdbStartPolicy.notice(outcome)!!.text.contains("ワイヤレスデバッグがオン"))
    }
}
