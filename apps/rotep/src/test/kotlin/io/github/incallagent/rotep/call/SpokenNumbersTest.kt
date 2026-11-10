// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SpokenNumbersTest {
    @Test
    fun theMonthsThatAreNotRegular() {
        assertEquals("しがつ", SpokenNumbers.month(4))
        assertEquals("しちがつ", SpokenNumbers.month(7))
        assertEquals("くがつ", SpokenNumbers.month(9))
        assertEquals("じゅういちがつ", SpokenNumbers.month(11))
        assertEquals("じゅうにがつ", SpokenNumbers.month(12))
    }

    @Test
    fun theDaysThatAreNotRegular() {
        val days = mapOf(
            1 to "ついたち", 2 to "ふつか", 3 to "みっか", 4 to "よっか", 5 to "いつか", 6 to "むいか",
            7 to "なのか", 8 to "ようか", 9 to "ここのか", 10 to "とおか", 11 to "じゅういちにち",
            14 to "じゅうよっか", 17 to "じゅうしちにち", 19 to "じゅうくにち", 20 to "はつか",
            21 to "にじゅういちにち", 24 to "にじゅうよっか", 27 to "にじゅうしちにち", 29 to "にじゅうくにち",
            30 to "さんじゅうにち", 31 to "さんじゅういちにち",
        )
        days.forEach { (d, kana) -> assertEquals(kana, SpokenNumbers.day(d)) }
    }

    @Test
    fun everyDayOfAMonthHasAReading() {
        (1..31).forEach { assertTrue(it.toString(), SpokenNumbers.day(it).length >= 3) }
    }

    @Test
    fun theHours() {
        val hours = mapOf(
            0 to "れいじ", 1 to "いちじ", 4 to "よじ", 7 to "しちじ", 9 to "くじ", 10 to "じゅうじ",
            11 to "じゅういちじ", 14 to "じゅうよじ", 17 to "じゅうしちじ", 19 to "じゅうくじ",
            20 to "にじゅうじ", 23 to "にじゅうさんじ",
        )
        hours.forEach { (h, kana) -> assertEquals(kana, SpokenNumbers.clock(h, 0)) }
    }

    @Test
    fun theMinutes() {
        assertEquals("じゅうはちじ はん", SpokenNumbers.clock(18, 30))
        assertEquals("じゅうくじ じゅうごふん", SpokenNumbers.clock(19, 15))
        assertEquals("じゅうくじ よんじゅうごふん", SpokenNumbers.clock(19, 45))
        assertEquals("じゅうくじ にじゅっぷん", SpokenNumbers.clock(19, 20))
        assertEquals("じゅうくじ じゅっぷん", SpokenNumbers.clock(19, 10))
        assertEquals("じゅうくじ ごふん", SpokenNumbers.clock(19, 5))
        assertEquals("じゅうくじ よんぷん", SpokenNumbers.clock(19, 4))
        assertEquals("じゅうくじ さんじゅういっぷん", SpokenNumbers.clock(19, 31))
    }

    @Test
    fun peopleAreCountedWithTheFormalWord() {
        assertEquals("いちめい", SpokenNumbers.people(1))
        assertEquals("にめい", SpokenNumbers.people(2))
        assertEquals("よんめい", SpokenNumbers.people(4))
        assertEquals("ななめい", SpokenNumbers.people(7))
        assertEquals("じゅうにめい", SpokenNumbers.people(12))
        assertNull(SpokenNumbers.people(0))
        assertNull(SpokenNumbers.people(100))
    }

    @Test
    fun aPartyIsAlwaysCountedIchiNiSan() {
        val said = listOf("いちめい", "にめい", "さんめい", "よんめい", "ごめい", "ろくめい", "ななめい", "はちめい", "きゅうめい", "じゅうめい")
        said.forEachIndexed { i, kana -> assertEquals(kana, SpokenNumbers.people(i + 1)) }
    }

    private val policy = PracticePolicy.approved(LocalDate.of(2026, 11, 7), Instant.parse("2026-11-07T10:00:00Z"))

    @Test
    fun theReadingOfThePartyReachesTheRulesInTheFormTheInstructionWritesIt() {
        val rules = SpeakingStyle(readings = SpokenNumbers.of(policy)).rules()
        assertTrue(rules, rules.contains("「2名」は「にめい」と読む"))
        assertTrue(rules, rules.contains("「3名」は「さんめい」と読む"))
    }

    @Test
    fun aCallGetsTheReadingsOfItsOwnDatesTimesAndPeopleInTheFormTheInstructionWritesThem() {
        val readings = SpokenNumbers.of(policy)
        val window = policy.request.windows.first().earliestStart
        val party = policy.request.partySize!!.preferred
        assertTrue(readings.any { it.text == "${window.monthValue}月${window.dayOfMonth}日" })
        assertTrue(readings.any { it.text == "%d:%02d".format(window.hour, window.minute) })
        assertTrue(readings.any { it.text == "${party}名" })
        assertEquals(readings.size, readings.distinct().size)
    }

    @Test
    fun theyReachTheRulesWithoutTouchingAnythingElse() {
        val rules = SpeakingStyle(readings = SpokenNumbers.of(policy)).rules()
        assertTrue(rules.contains("# 読み方"))
        assertFalse(rules.contains(policy.target.phoneNumber))
    }

    @Test
    fun theSpeakingAgentIsToldToStayInJapanese() {
        assertTrue(SpeakingStyle().rules().contains("常に日本語で話す"))
    }

    @Test
    fun numbersAreReadDigitByDigit() {
        assertTrue(SpeakingStyle().rules().contains("数字を1つずつ"))
    }
}
