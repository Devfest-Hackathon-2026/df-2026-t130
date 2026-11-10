// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.practice

import io.github.incallagent.agent.live.LiveInstructions
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.core.policy.PolicyValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PracticePolicyTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun validOnEveryDayOfTheWeekUntilMidnight() {
        // 2026-11-07 is a Saturday; check a whole week, late in the evening
        for (d in 0L..6L) {
            val today = LocalDate.of(2026, 11, 7).plusDays(d)
            val now = today.atTime(23, 59).atZone(zone).toInstant()
            val p = PracticePolicy.approved(today, now)
            assertEquals("$today", emptyList<Any>(), PolicyValidator.validate(p, now))
            val first = p.request.windows.first().earliestStart
            assertEquals(DayOfWeek.SATURDAY, first.dayOfWeek)
            assertTrue("$today -> $first", first.toLocalDate().isAfter(today))
        }
    }

    @Test
    fun draftNeedsApprovalApprovedRendersInstructions() {
        val today = LocalDate.of(2026, 11, 7)
        val now = today.atTime(9, 0).atZone(zone).toInstant()
        assertFalse(PracticePolicy.draft(today, now).isApproved)

        val p = PracticePolicy.approved(today, now)
        assertTrue(p.isApproved)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 0), p.request.windows.first().earliestStart)
        val text = LiveInstructions.render(p, today)
        assertTrue(text, text.contains(PracticePolicy.SHOP_NAME))
        assertTrue(LiveMessages.setup(p, today).toString().contains("functionDeclarations"))
    }
}
