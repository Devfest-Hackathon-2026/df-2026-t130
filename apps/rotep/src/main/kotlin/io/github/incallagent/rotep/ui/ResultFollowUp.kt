// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession

/** Whether the result screen offers 「変更」「取り消し」「続き」: the one place to take them off (ADR-046). */
internal const val FOLLOW_UP_BUTTONS = true

/** What can be asked for next from the result of a call. [spoken]: for screen readers, starting with the words on the button. */
enum class FollowUp(val label: String, val spoken: String) {
    CHANGE("変更", "変更を頼む"),
    CANCEL("取り消し", "取り消しを頼む"),
    CONTINUE("続き", "続きを頼む"),
}

/** A booking that was made can be changed or cancelled; what did not get done can go on; what was done leaves nothing. */
fun followUps(result: CallSession.Result, shown: Boolean = FOLLOW_UP_BUTTONS): List<FollowUp> {
    if (!shown) return emptyList()
    return when (resultHeadline(result)) {
        ResultHeadline.BOOKED_FIRST, ResultHeadline.BOOKED_OTHER, ResultHeadline.BOOKED_NEEDS_REVIEW,
        ResultHeadline.CHANGED, ResultHeadline.CHANGED_OTHER,
        -> listOf(FollowUp.CHANGE, FollowUp.CANCEL)
        ResultHeadline.CANCELLED, ResultHeadline.ANSWERED, ResultHeadline.NOT_DIALED -> emptyList()
        ResultHeadline.NEEDS_DECISION, ResultHeadline.UNAVAILABLE, ResultHeadline.NOT_REACHED, ResultHeadline.FAILED,
        ResultHeadline.NOT_CHANGED, ResultHeadline.NOT_CANCELLED, ResultHeadline.NOT_CANCELLED_YET, ResultHeadline.NOT_ANSWERED,
        ResultHeadline.STOPPED_BY_USER,
        -> listOf(FollowUp.CONTINUE)
    }
}

/**
 * The form for [kind], in the app's own values: nothing goes through a model, and what the shop said or
 * the AI summed up stays out, so the words of one call cannot become the request of the next (ADR-024).
 * [request]: the request of this call, while it is in memory. null: a result kept from earlier, which
 * holds no number (ADR-036). A change or a cancel is a new decision: what the AI may agree to starts
 * from "しない". The form and the confirm screen come before any call.
 */
fun followUpForm(kind: FollowUp, result: CallSession.Result, shopName: String, request: DelegationPolicy?): RequestForm {
    if (kind == FollowUp.CONTINUE) {
        return request?.let(RequestForm::from) ?: RequestForm(businessName = shopName, purpose = result.purpose)
    }
    val booking = result.outcome.booking
    val blank = RequestForm()
    return blank.copy(
        businessName = shopName,
        phoneNumber = request?.target?.phoneNumber.orEmpty(),
        kind = request?.target?.kind ?: blank.kind,
        purpose = if (kind == FollowUp.CHANGE) Purpose.CHANGE else Purpose.CANCEL,
        partyPreferred = booking?.partySize?.toString() ?: blank.partyPreferred,
        notesForShop = booking?.let(::originalBooking).orEmpty(),
        reservationName = request?.disclosure?.reservationName ?: booking?.nameUsed.orEmpty(),
        callbackNumber = request?.disclosure?.callbackNumber.orEmpty(),
    )
}

/** What the speaking AI tells the shop, to find the booking. */
private fun originalBooking(b: ConfirmedBooking): String =
    "もとの予約：${dateTime(b.start)}" + b.partySize?.let { "、${it}名" }.orEmpty()
