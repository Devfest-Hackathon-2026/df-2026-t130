// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import io.github.incallagent.core.policy.PolicyProblem.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class PolicyValidatorTest {
    private val base = Fixtures.example()
    private fun codes(p: DelegationPolicy, now: Instant = Fixtures.now) = PolicyValidator.validate(p, now).map { it.code }

    @Test
    fun exampleIsValid() {
        assertEquals(emptyList<PolicyProblem>(), PolicyValidator.validate(base, Fixtures.now))
    }

    @Test
    fun pastWindowsAreReported() {
        val later = Instant.parse("2026-11-14T12:00:00Z") // after the 14th, before the 15th (JST)
        assertEquals(listOf(Code.WINDOW_IN_PAST, Code.WINDOW_IN_PAST), codes(base, later))
    }

    @Test
    fun aiMustBeDisclosed() {
        val p = base.copy(conversation = base.conversation.copy(aiDisclosure = "ヤマダの代わりにお電話しました"))
        val c = codes(p)
        assertTrue(c.toString(), Code.AI_DISCLOSURE_MISSING in c)
        assertTrue(c.toString(), Code.PERSON_NAME_IN_INTRODUCTION in c)
    }

    @Test
    fun fullWidthAiIsAccepted() {
        val p = base.copy(conversation = base.conversation.copy(aiDisclosure = "こちらはＡＩによる代理のお電話です。"))
        assertEquals(emptyList<Code>(), codes(p))
    }

    @Test
    fun bookingNeedsWindowsPartySizeAndName() {
        val p = base.copy(
            request = base.request.copy(windows = emptyList(), partySize = null),
            disclosure = base.disclosure.copy(reservationName = null),
        )
        assertEquals(setOf(Code.NO_WINDOWS, Code.PARTY_SIZE_REQUIRED, Code.NAME_REQUIRED), codes(p).toSet())
    }

    @Test
    fun inquiryNeedsNoWindowsOrName() {
        val p = base.copy(
            purpose = Purpose.INQUIRY,
            request = BookingRequest(),
            disclosure = Disclosure(),
        )
        assertEquals(emptyList<Code>(), codes(p))
    }

    @Test
    fun reversedWindowAndBadPartySize() {
        val t = LocalDateTime.of(2026, 11, 14, 19, 0)
        val p = base.copy(
            request = base.request.copy(
                windows = listOf(TimeWindow(t, t.minusHours(1))),
                partySize = PartySize(preferred = 2, min = 3, max = 4),
            )
        )
        assertEquals(setOf(Code.WINDOW_REVERSED, Code.INVALID_PARTY_SIZE), codes(p).toSet())
    }

    @Test
    fun badPhoneNumbers() {
        val p = base.copy(
            target = base.target.copy(phoneNumber = "12345"),
            disclosure = base.disclosure.copy(callbackNumber = "abc"),
        )
        assertEquals(listOf(Code.INVALID_PHONE_NUMBER, Code.INVALID_PHONE_NUMBER), codes(p))
    }

    @Test
    fun mayTellMustNotContradictNeverTell() {
        val p = base.copy(disclosure = base.disclosure.copy(mayTell = listOf(DisclosableItem("住所", "東京都"))))
        assertEquals(listOf(Code.FORBIDDEN_DISCLOSURE), codes(p))
    }

    @Test
    fun invalidTimeZone() {
        assertEquals(listOf(Code.INVALID_TIME_ZONE), codes(base.copy(timeZone = "Mars/Base")))
    }

    @Test
    fun phoneNumberFormats() {
        assertEquals("0300000000", PhoneNumbers.normalizeJp("03-0000-0000"))
        assertEquals("0300000000", PhoneNumbers.normalizeJp("(03) 0000 0000"))
        assertEquals("09000000000", PhoneNumbers.normalizeJp("+81 90-0000-0000"))
        assertEquals("0120000000", PhoneNumbers.normalizeJp("0120-000-000"))
        assertNull(PhoneNumbers.normalizeJp("3000-0000"))
        assertNull(PhoneNumbers.normalizeJp("03-0000-000x"))
        assertNull(PhoneNumbers.normalizeJp("+1 555 000 0000"))
        // The trunk 0 after +81, written in brackets or not: the same number, never "003…"
        assertEquals("0300000000", PhoneNumbers.normalizeJp("+81 (0)3-0000-0000"))
        assertEquals("0300000000", PhoneNumbers.normalizeJp("+81 03-0000-0000"))
        assertEquals("09000000000", PhoneNumbers.normalizeJp("+81 (0)90-0000-0000"))
        // 00 (carrier selection) and 010 (international) never start a domestic number
        assertNull(PhoneNumbers.normalizeJp("003-0000-0000"))
        assertNull(PhoneNumbers.normalizeJp("0033-000-0000"))
        assertNull(PhoneNumbers.normalizeJp("010-0000-0000"))
        assertNull(PhoneNumbers.normalizeJp("+81 00-3000-0000"))
    }
}
