// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.core.policy.PhoneNumbers
import io.github.incallagent.rotep.practice.PracticePolicy

/**
 * The only number the app may dial is the approved target number of the policy, as a Japanese
 * number of 10 or 11 digits. Emergency (110, 118, 119) and other short numbers never pass
 * [PhoneNumbers.normalizeJp], so they are refused, and so is the fictitious number of the
 * practice example. So are numbers that start with 00 or 010: carrier selection and
 * international prefixes, never a domestic number (normalizeJp turns "+81 (0)3-…" into "003…"),
 * and so are the paid information services. Reasons never contain the number.
 */
object DialCheck {
    sealed interface Result {
        /** Domestic digits to dial, e.g. "0300000000". */
        data class Ok(val digits: String) : Result {
            override fun toString() = "Ok(<${digits.length} digits>)"
        }

        data class Refused(val reason: String) : Result
    }

    fun check(number: String, approved: String): Result {
        val n = PhoneNumbers.normalizeJp(number)
            ?: return Result.Refused(if (hasCallerIdPrefix(number)) WITHOUT_PREFIX else "電話番号の形が正しくありません（緊急の番号や短い番号には、かけません）")
        if (NOT_DOMESTIC.any { n.startsWith(it) }) {
            return Result.Refused("国際電話や、事業者を選ぶ番号（00・010 で始まる形）には、かけません")
        }
        if (PAID_INFORMATION.any { n.startsWith(it) }) {
            return Result.Refused("有料の情報サービスの番号（0990・0180・0170 で始まる形）には、かけません")
        }
        if (n == PRACTICE) return Result.Refused("練習用の架空の番号には電話をかけません（設定の「電話のしかた」を「練習」にしてください）")
        val a = PhoneNumbers.normalizeJp(approved)
            ?: return Result.Refused("承認した電話番号の形が正しくありません")
        if (n != a) return Result.Refused("承認した電話番号と違います")
        return Result.Ok(n)
    }

    private val PRACTICE = PhoneNumbers.normalizeJp(PracticePolicy.PHONE_NUMBER)

    /** 184 and 186 in front of a number say whether to show the caller's number: they are not part of it. */
    private const val WITHOUT_PREFIX = "184・186 を付けずに、電話番号だけを入れてください"
    private val CALLER_ID_PREFIXES = listOf("184", "186")

    private fun hasCallerIdPrefix(number: String): Boolean {
        val digits = number.filter { it in '0'..'9' }
        return CALLER_ID_PREFIXES.any { digits.startsWith(it) } && PhoneNumbers.normalizeJp(digits.drop(3)) != null
    }

    /** Prefixes of the normalized digits that never start a domestic number. */
    private val NOT_DOMESTIC = listOf("00", "010")

    /**
     * Charged to the caller as an information service, and never a shop's line: 0990 (dial Q2),
     * 0180 (telephone polls and announcements), 0170 (message boards). 0570 is not here: shops use
     * it, and the confirm screen says what it costs.
     */
    private val PAID_INFORMATION = listOf("0990", "0180", "0170")

    /** The number is a navigation dial (0570): it can be dialed, and it costs more. */
    fun isNavigationDial(number: String): Boolean = PhoneNumbers.normalizeJp(number)?.startsWith("0570") == true
}
