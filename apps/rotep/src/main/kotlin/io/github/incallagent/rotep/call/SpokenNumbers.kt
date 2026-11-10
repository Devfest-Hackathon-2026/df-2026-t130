// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.core.policy.DelegationPolicy
import java.time.LocalDateTime
import java.util.Locale

/**
 * How the dates, times and party sizes of this call are said. Worked out here rather than left to
 * the speaking agent: 「14日」「4名」「19:00」 have one right reading each, and they are what the shop
 * writes down. Only values of the approved policy go in, in the form the instruction writes them.
 */
internal object SpokenNumbers {
    fun of(policy: DelegationPolicy): List<SpokenReading> = buildList {
        policy.request.windows.flatMap { listOf(it.earliestStart, it.latestStart) }.forEach { t ->
            add(SpokenReading("${t.monthValue}月${t.dayOfMonth}日", month(t.monthValue) + " " + day(t.dayOfMonth)))
            add(SpokenReading(writtenTime(t), clock(t.hour, t.minute)))
        }
        policy.request.partySize?.let { p ->
            listOf(p.preferred, p.min, p.max).forEach { n ->
                people(n)?.let { add(SpokenReading("${n}名", it)) }
            }
        }
    }.distinct()

    /** The way agent-live's instruction writes a time. */
    private fun writtenTime(t: LocalDateTime) = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

    fun month(m: Int): String = when (m) {
        4 -> "しがつ"
        7 -> "しちがつ"
        9 -> "くがつ"
        else -> number(m) + "がつ"
    }

    fun day(d: Int): String = when (d) {
        1 -> "ついたち"
        2 -> "ふつか"
        3 -> "みっか"
        4 -> "よっか"
        5 -> "いつか"
        6 -> "むいか"
        7 -> "なのか"
        8 -> "ようか"
        9 -> "ここのか"
        10 -> "とおか"
        14 -> "じゅうよっか"
        20 -> "はつか"
        24 -> "にじゅうよっか"
        else -> tens(d / 10) + when (d % 10) {
            7 -> "しち"
            9 -> "く"
            else -> ones(d % 10)
        } + "にち"
    }

    /** 19, 0 → じゅうくじ; 18, 30 → じゅうはちじ はん. */
    fun clock(hour: Int, minute: Int): String {
        val h = when (hour) {
            0 -> "れい"
            else -> tens(hour / 10) + when (hour % 10) {
                4 -> "よ"
                7 -> "しち"
                9 -> "く"
                else -> ones(hour % 10)
            }
        } + "じ"
        return when (minute) {
            0 -> h
            30 -> "$h はん"
            else -> "$h ${minutes(minute)}"
        }
    }

    private fun minutes(m: Int): String {
        val ten = when (m / 10) {
            0 -> ""
            1 -> "じゅう"
            4 -> "よんじゅう"
            else -> number(m / 10) + "じゅう"
        }
        return when (m % 10) {
            0 -> ten.removeSuffix("じゅう") + "じゅっぷん"
            1 -> ten + "いっぷん"
            2 -> ten + "にふん"
            3 -> ten + "さんぷん"
            4 -> ten + "よんぷん"
            5 -> ten + "ごふん"
            6 -> ten + "ろっぷん"
            7 -> ten + "ななふん"
            8 -> ten + "はっぷん"
            else -> ten + "きゅうふん"
        }
    }

    /** Null past two digits: better no reading than a wrong one. */
    fun people(n: Int): String? = if (n in 1..99) number(n) + "めい" else null

    private val PEOPLE_RANGE = Regex("(\\d{1,2})〜(\\d{1,2})名")
    private val PEOPLE = Regex("(\\d{1,2})名")

    /**
     * agent-live's instruction with its party sizes in kana. With 「2名」 in front of it the speaking
     * AI kept saying ふためい (11/9, 11/10), whatever the rules said; it copies what it reads.
     */
    fun peopleInKana(text: String): String {
        val ranges = PEOPLE_RANGE.replace(text) { m ->
            val from = people(m.groupValues[1].toInt())
            val to = people(m.groupValues[2].toInt())
            if (from != null && to != null) "${from}から${to}" else m.value
        }
        return PEOPLE.replace(ranges) { m -> people(m.groupValues[1].toInt()) ?: m.value }
    }

    private fun number(n: Int): String = tens(n / 10) + ones(n % 10)

    private fun tens(t: Int): String = when (t) {
        0 -> ""
        1 -> "じゅう"
        else -> ones(t) + "じゅう"
    }

    private fun ones(o: Int): String = when (o) {
        1 -> "いち"
        2 -> "に"
        3 -> "さん"
        4 -> "よん"
        5 -> "ご"
        6 -> "ろく"
        7 -> "なな"
        8 -> "はち"
        9 -> "きゅう"
        else -> ""
    }
}
