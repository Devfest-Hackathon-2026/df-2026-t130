// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleRulesTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val now: Instant = ScheduleRules.instantOf(today.atTime(10, 0))

    private fun at(day: Long, hour: Int, minute: Int = 0): LocalDateTime = today.plusDays(day).atTime(hour, minute)

    private fun problem(t: LocalDateTime) = ScheduleRules.problem(t, now)

    // --- what can be chosen ---

    @Test
    fun aTimeLaterTodayCanBeChosen() {
        assertNull(problem(at(0, 11)))
    }

    @Test
    fun thePastAndNowAreRefused() {
        assertTrue(problem(at(0, 9, 59))!!.contains("過去"))
        assertTrue(problem(at(0, 10))!!.contains("過去"))
    }

    @Test
    fun theNoticeOneMinuteBeforeAlwaysHasItsMinute() {
        assertTrue(problem(at(0, 10, 1))!!.contains("2 分以上"))
        assertNull(problem(at(0, 10, 2)))
    }

    @Test
    fun nightAndEarlyMorningAreRefusedAtTheEdges() {
        assertNull(problem(at(0, 20, 59)))
        assertTrue(problem(at(0, 21))!!.contains("夜 9 時"))
        assertTrue(problem(at(1, 7, 59))!!.contains("朝 8 時"))
        assertNull(problem(at(1, 8)))
        assertTrue(problem(at(1, 0))!!.contains("朝 8 時"))
    }

    @Test
    fun sevenDaysAheadIsCountedInDates() {
        // Later in the day than now, on the last day: still within 7 days by date
        assertNull(problem(at(7, 20, 59)))
        assertTrue(problem(at(8, 9))!!.contains("7 日先"))
    }

    @Test
    fun oneProblemIsSaidAtATimeInTheOrderTheUserFixesThem() {
        assertTrue(problem(at(8, 22))!!.contains("夜 9 時"))
    }

    // --- at the time of the alarm ---

    private val fireAt: Instant = ScheduleRules.instantOf(at(0, 11))

    @Test
    fun anAlarmOnTimeIsAccepted() {
        assertNull(ScheduleRules.fireProblem(fireAt, fireAt))
        assertNull(ScheduleRules.fireProblem(fireAt, fireAt.plusSeconds(20)))
        assertNull(ScheduleRules.fireProblem(fireAt, fireAt.minusSeconds(30)))
    }

    @Test
    fun anAlarmThatIsTooLateIsNotACall() {
        assertNull(ScheduleRules.fireProblem(fireAt, fireAt.plus(Duration.ofMinutes(5))))
        assertTrue(ScheduleRules.fireProblem(fireAt, fireAt.plus(Duration.ofMinutes(5)).plusSeconds(1))!!.contains("5 分以上過ぎていた"))
    }

    @Test
    fun anAlarmThatComesTooEarlyIsNotACall() {
        assertTrue(ScheduleRules.fireProblem(fireAt, fireAt.minus(Duration.ofMinutes(1)).minusSeconds(1))!!.contains("時刻が変わった"))
    }

    @Test
    fun aLateAlarmThatWouldCallAtNightIsRefused() {
        val last = ScheduleRules.instantOf(at(0, 20, 58))
        val inTheNight = ScheduleRules.instantOf(at(0, 21, 1))
        assertTrue(ScheduleRules.fireProblem(last, inTheNight)!!.contains("夜 9 時"))
    }

    // --- what the screen offers and says ---

    @Test
    fun todayAndTheNextSevenDaysAreOffered() {
        val dates = ScheduleRules.dates(today)
        assertEquals(8, dates.size)
        assertEquals(today, dates.first())
        assertEquals(today.plusDays(7), dates.last())
    }

    @Test
    fun theFirstChoiceIsAFullHourThatCanBeChosen() {
        assertEquals(at(0, 11), ScheduleRules.firstChoice(now))
        // Too close to the next hour: the one after
        assertEquals(at(0, 12), ScheduleRules.firstChoice(ScheduleRules.instantOf(at(0, 10, 59))))
        // After the last call of the day: tomorrow morning
        assertEquals(at(1, 8), ScheduleRules.firstChoice(ScheduleRules.instantOf(at(0, 20, 30))))
        assertNull(problem(ScheduleRules.firstChoice(now)))
    }

    @Test
    fun aRequestApprovedOnAnEarlierDayKnowsThatDay() {
        val approved = ScheduleRules.instantOf(at(-1, 23, 30))
        assertEquals(today.minusDays(1), ScheduleRules.writtenOn(approved, today))
        assertNull("the same day needs no word", ScheduleRules.writtenOn(ScheduleRules.instantOf(at(0, 0, 10)), today))
        assertNull(ScheduleRules.writtenOn(null, today))
    }

    @Test
    fun aTimeAnotherAiAskedForComesFirstWhenItCanBeChosen() {
        assertEquals(at(1, 11, 30), ScheduleRules.firstChoice(now, ScheduleRules.instantOf(at(1, 11, 30))))
    }

    @Test
    fun aTimeAnotherAiAskedForThatCannotBeChosenIsNotOffered() {
        val usual = ScheduleRules.firstChoice(now)
        for (bad in listOf(at(0, 9), at(1, 22), at(0, 10, 1), at(8, 11))) {
            assertEquals("$bad", usual, ScheduleRules.firstChoice(now, ScheduleRules.instantOf(bad)))
        }
    }

    @Test
    fun theDaysAreNamedSoTheyCanBeTold() {
        assertEquals("今日 11/9(月)", ScheduleRules.dateLabel(today, today))
        assertEquals("明日 11/10(火)", ScheduleRules.dateLabel(today.plusDays(1), today))
        assertEquals("11/11(水)", ScheduleRules.dateLabel(today.plusDays(2), today))
    }

    @Test
    fun theBandSaysWhenAndWhom() {
        assertEquals(
            "11月10日(火) 11:00に 練習レストラン へ電話します",
            ScheduleRules.bannerText("練習レストラン", ScheduleRules.instantOf(at(1, 11))),
        )
        assertEquals("11月10日(火) 8:05", ScheduleRules.whenText(at(1, 8, 5)))
    }

    @Test
    fun aLostCallSaysThatItWasNotMade() {
        val text = ScheduleRules.lostText("練習レストラン", ScheduleRules.instantOf(at(1, 11)))
        assertNotNull(text)
        assertTrue(text.contains("かけられませんでした"))
        assertTrue(text.contains("11月10日(火) 11:00"))
    }
}
