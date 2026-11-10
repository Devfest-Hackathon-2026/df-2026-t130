// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

/**
 * Checks a reported booking against the approved policy before it goes to the calendar.
 * The speaking agent can mishear or over-agree; this is the last guard.
 */
object OutcomeCheck {
    data class Violation(val field: String, val message: String) {
        override fun toString() = "$field: $message"
    }

    fun check(policy: DelegationPolicy, outcome: CallOutcome): List<Violation> {
        val out = ArrayList<Violation>()
        if (outcome.policyId != policy.id) {
            out += Violation("policyId", "別の委任ポリシーの結果です")
        }
        val booked = outcome.status == OutcomeStatus.BOOKED || outcome.status == OutcomeStatus.BOOKED_ALTERNATIVE
        val b = outcome.booking

        if (booked && b == null) {
            out += Violation("booking", "予約できたことになっているのに、予約の内容がありません")
        }
        if (!booked && b != null) {
            out += Violation("booking", "予約できていないのに、予約の内容があります")
        }
        // The same rule as OfferEvaluator: these purposes never take a booking
        val noBooking = policy.purpose == Purpose.INQUIRY || policy.purpose == Purpose.CANCEL
        if (booked && noBooking) {
            out += Violation("status", "問い合わせや取り消しの用件では、予約はしません")
        }
        // ...and only these purposes can end with the errand done and nothing booked
        if (outcome.status == OutcomeStatus.DONE && !noBooking) {
            out += Violation("status", "予約の用件では DONE は使えません")
        }
        if (b != null) {
            if (!policy.authority.mayAcceptOutsideWindows && policy.request.windows.none { b.start in it }) {
                out += Violation("booking.start", "希望の日時の範囲外です：${b.start}")
            }
            val ps = policy.request.partySize
            val size = b.partySize
            if (ps != null) {
                // Without a size the range cannot be checked (and BOOKED_ALTERNATIVE skips the first-choice check)
                if (size == null) {
                    out += Violation("booking.partySize", "人数が確認されていません")
                } else if (size !in ps) {
                    out += Violation("booking.partySize", "許可した人数の範囲外です：$size")
                }
            }
            for (pref in policy.request.preferences.filter { it.required }) {
                if (!PreferenceMatch.isConfirmed(pref, b.confirmedPreferences)) {
                    out += Violation("booking.confirmedPreferences", "必須の条件「${pref.text}」が確認されていません")
                }
            }
        }
        if (outcome.status == OutcomeStatus.BOOKED && b != null) {
            val first = policy.request.windows.firstOrNull()
            val preferred = policy.request.partySize?.preferred
            val isFirstChoice = (first == null || b.start in first) && (preferred == null || b.partySize == preferred)
            if (!isFirstChoice) {
                out += Violation("status", "第一希望ではないので BOOKED_ALTERNATIVE にしてください")
            }
        }
        if (outcome.status == OutcomeStatus.NEEDS_USER_DECISION && outcome.pendingItems.isEmpty()) {
            out += Violation("pendingItems", "折り返しが必要な理由が書かれていません")
        }
        return out
    }
}
