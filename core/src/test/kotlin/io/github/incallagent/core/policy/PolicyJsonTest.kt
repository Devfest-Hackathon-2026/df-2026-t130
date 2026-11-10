// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class PolicyJsonTest {
    @Test
    fun exampleParses() {
        val p = Fixtures.example()
        assertEquals("example-0001", p.id)
        assertEquals(Purpose.NEW_BOOKING, p.purpose)
        assertEquals(3, p.request.windows.size)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 0), p.request.windows[0].earliestStart)
        assertEquals(8000, p.authority.maxBudgetPerPersonYen)
        assertTrue(p.isApproved)
    }

    @Test
    fun roundTrip() {
        val p = Fixtures.example()
        assertEquals(p, PolicyJson.decodePolicy(PolicyJson.encode(p)))
    }

    @Test
    fun minimalJsonGetsSafeDefaults() {
        val p = PolicyJson.decodePolicy(
            """
            {
              "id": "min",
              "createdAt": "2026-11-06T00:00:00Z",
              "target": {"businessName": "店", "phoneNumber": "0300000000"},
              "purpose": "INQUIRY",
              "request": {},
              "disclosure": {}
            }
            """.trimIndent()
        )
        assertFalse(p.isApproved)
        assertNull(p.authority.maxBudgetPerPersonYen)
        assertFalse(p.authority.mayAcceptOutsideWindows)
        assertFalse(p.authority.mayAcceptPrepayment)
        assertEquals(Disclosure.DEFAULT_NEVER_TELL, p.disclosure.neverTell)
        assertTrue(p.conversation.aiDisclosure.contains("AI"))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = PolicyJson.encode(Fixtures.example()).replaceFirst("{", "{\n    \"futureField\": 1,")
        assertEquals(Fixtures.example(), PolicyJson.decodePolicy(text))
    }

    @Test
    fun outcomeRoundTrip() {
        val o = CallOutcome(
            policyId = "example-0001",
            status = OutcomeStatus.BOOKED,
            booking = ConfirmedBooking(
                start = LocalDateTime.of(2026, 11, 14, 19, 0),
                partySize = 2,
                nameUsed = "ヤマダ",
                confirmedPreferences = listOf("禁煙席"),
            ),
            summary = "11月14日(土) 19:00 に2名で予約できました",
            endedAt = Instant.parse("2026-11-07T01:00:00Z"),
        )
        assertEquals(o, PolicyJson.decodeOutcome(PolicyJson.encode(o)))
    }

    @Test
    fun toStringDoesNotLeakNumbersOrNames() {
        val s = Fixtures.example().toString()
        assertFalse(s, s.contains("0000-0000"))
        assertFalse(s, s.contains("ヤマダ"))
        val b = ConfirmedBooking(start = LocalDateTime.of(2026, 11, 14, 19, 0), nameUsed = "ヤマダ").toString()
        assertFalse(b, b.contains("ヤマダ"))
    }
}
