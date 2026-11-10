// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.practice

import io.github.incallagent.core.policy.Approval
import io.github.incallagent.core.policy.Authority
import io.github.incallagent.core.policy.BookingRequest
import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.CallTarget
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.Disclosure
import io.github.incallagent.core.policy.PartySize
import io.github.incallagent.core.policy.Preference
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.core.policy.TimeWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The practice request: a dinner for two at a fictitious restaurant on the coming weekend.
 *
 * Dates are relative to [today] (the next Saturday after it, and the Sunday after that), so the
 * policy is always valid. The shop, the numbers and the name are made up.
 *
 * Used by the "練習用の例を入れる" button ([draft]: the user still reads and approves it) and by
 * the PC check of the Gemini Live connection ([approved]).
 */
object PracticePolicy {
    const val SHOP_NAME = "レストラン・サンプル"

    /** Fictitious: never dialed ([io.github.incallagent.rotep.call.DialCheck] refuses it). */
    const val PHONE_NUMBER = "03-0000-0000"

    fun draft(today: LocalDate, now: Instant, id: String = "practice-$today"): DelegationPolicy {
        val sat = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        val sun = sat.plusDays(1)
        return DelegationPolicy(
            id = id,
            createdAt = now,
            target = CallTarget(
                businessName = SHOP_NAME,
                phoneNumber = PHONE_NUMBER,
                kind = BusinessKind.RESTAURANT,
            ),
            purpose = Purpose.NEW_BOOKING,
            request = BookingRequest(
                partySize = PartySize(preferred = 2, min = 2, max = 3),
                windows = listOf(
                    TimeWindow.exactly(sat.atTime(19, 0)),
                    TimeWindow(sat.atTime(18, 30), sat.atTime(20, 0)),
                    TimeWindow(sun.atTime(18, 0), sun.atTime(19, 30)),
                ),
                durationMinutes = 120,
                preferences = listOf(
                    Preference("禁煙席", required = true),
                    Preference("個室", required = false),
                ),
                notesForShop = "誕生日のお祝いです",
            ),
            authority = Authority(maxBudgetPerPersonYen = 8000),
            disclosure = Disclosure(
                reservationName = "ヤマダ",
                callbackNumber = "090-0000-0000",
            ),
        )
    }

    /** [draft] with the approval already given (for checks that never reach a real call). */
    fun approved(today: LocalDate, now: Instant): DelegationPolicy =
        draft(today, now).copy(approval = Approval(approvedAt = now))
}
