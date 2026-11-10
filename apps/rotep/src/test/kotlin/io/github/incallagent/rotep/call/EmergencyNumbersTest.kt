// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNumbersTest {
    // Fictitious 10-digit number that this fake phone lists as an emergency number
    private val listed = "045-000-0000"
    private val phone: (String) -> Boolean = { it == "0450000000" || it == "110" }
    private val broken: (String) -> Boolean = { throw IllegalStateException("telephony service is null") }

    @Test
    fun thePhoneIsAskedAboutDialableForms() {
        assertEquals(listOf("0450000000"), EmergencyNumbers.forms("045-000-0000"))
        assertEquals(listOf("+81450000000", "0450000000"), EmergencyNumbers.forms("+81 45 000 0000"))
        assertEquals(listOf("110"), EmergencyNumbers.forms("1 1 0"))
        assertTrue(EmergencyNumbers.forms(" - ").isEmpty())
    }

    @Test
    fun anEmergencyNumberOfThePhoneIsRefusedInEveryStage() {
        for (dials in listOf(false, true)) {
            assertNotNull("dials=$dials", EmergencyNumbers.problem(listed, phone, dials))
            assertNotNull("dials=$dials", EmergencyNumbers.problem("110", phone, dials))
        }
        assertNull(EmergencyNumbers.problem("045-000-0001", phone, dials = true))
    }

    @Test
    fun whenThePhoneCannotTellOnlyL0GoesOn() {
        assertEquals(EmergencyNumbers.Answer.Unknown("IllegalStateException"), EmergencyNumbers.check(listed, broken))
        assertNull("L0 dials nothing", EmergencyNumbers.problem(listed, broken, dials = false))
        val p = EmergencyNumbers.problem(listed, broken, dials = true)
        assertNotNull(p)
        assertTrue(p!!, p.contains("IllegalStateException"))
    }

    @Test
    fun reasonsNeverShowTheNumber() {
        for (r in listOf(EmergencyNumbers.problem(listed, phone, true), EmergencyNumbers.problem(listed, broken, true))) {
            assertNotNull(r)
            assertFalse(r!!, r.contains("0450000000") || r.contains("000-0000"))
        }
    }
}
