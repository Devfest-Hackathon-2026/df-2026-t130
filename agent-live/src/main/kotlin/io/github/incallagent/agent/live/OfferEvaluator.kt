// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PreferenceMatch
import io.github.incallagent.core.policy.Purpose
import java.time.LocalDateTime

/**
 * What the shop proposed, as the speaking agent understood it.
 * Filled from the arguments of the `evaluate_offer` tool.
 */
data class Offer(
    val start: LocalDateTime?,
    val partySize: Int?,
    val pricePerPersonYen: Int? = null,
    val confirmedPreferences: List<String> = emptyList(),
    val requiresCancellationFee: Boolean = false,
    val requiresPrepayment: Boolean = false,
    val isWaitlist: Boolean = false,
)

data class OfferDecision(
    val accept: Boolean,
    /** true when this is the first window and the preferred party size (report BOOKED, not BOOKED_ALTERNATIVE). */
    val isFirstChoice: Boolean,
    /** Why it cannot be accepted (Japanese, read by the model). Empty when [accept]. */
    val reasons: List<String>,
)

/**
 * Decides in code, not in the model, whether an offer is inside the delegation policy.
 * The model must call `evaluate_offer` before agreeing to anything and follow the answer.
 */
object OfferEvaluator {
    fun evaluate(policy: DelegationPolicy, offer: Offer): OfferDecision {
        val reasons = ArrayList<String>()
        val r = policy.request
        val a = policy.authority

        // A fee is a condition of the cancellation itself: there is no date, party or budget to check
        if (policy.purpose == Purpose.CANCEL && offer.requiresCancellationFee) {
            val allowed = a.mayAcceptCancellationPolicy
            return OfferDecision(
                accept = allowed,
                isFirstChoice = false,
                reasons = if (allowed) emptyList() else listOf("キャンセル料に同意することは任されていません"),
            )
        }

        if (policy.purpose == Purpose.INQUIRY || policy.purpose == Purpose.CANCEL) {
            reasons += "今回の用件（${policy.purpose}）では予約を受けません"
        }

        val start = offer.start
        if (start == null) {
            reasons += "日時がはっきりしていません。日時を確かめてから、もう一度判断してください"
        } else if (!a.mayAcceptOutsideWindows && r.windows.none { start in it }) {
            reasons += "日時（$start）が希望の範囲外です"
        }

        val ps = r.partySize
        if (ps != null) {
            val n = offer.partySize
            if (n == null) {
                reasons += "人数がはっきりしていません"
            } else if (n !in ps) {
                reasons += "人数（$n 名）が任された範囲（${ps.min}〜${ps.max}名）の外です"
            }
        }

        offer.pricePerPersonYen?.let { price ->
            val budget = a.maxBudgetPerPersonYen
            if (budget == null) {
                reasons += "金額を決めることは任されていません"
            } else if (price > budget) {
                reasons += "1人 $price 円は予算（$budget 円）を超えています"
            }
        }

        if (offer.requiresCancellationFee && !a.mayAcceptCancellationPolicy) {
            reasons += "キャンセル料などの条件に同意することは任されていません"
        }
        if (offer.requiresPrepayment && !a.mayAcceptPrepayment) {
            reasons += "前払い・予約金に同意することは任されていません"
        }
        if (offer.isWaitlist && !a.mayJoinWaitlist) {
            reasons += "キャンセル待ちに登録することは任されていません"
        }

        for (pref in r.preferences.filter { it.required }) {
            if (!PreferenceMatch.isConfirmed(pref, offer.confirmedPreferences)) {
                reasons += "必須の条件「${pref.text}」が満たされていません"
            }
        }

        val first = r.windows.firstOrNull()
        val isFirst = start != null &&
            (first == null || start in first) &&
            (ps == null || offer.partySize == ps.preferred)

        return OfferDecision(accept = reasons.isEmpty(), isFirstChoice = reasons.isEmpty() && isFirst, reasons = reasons)
    }
}
