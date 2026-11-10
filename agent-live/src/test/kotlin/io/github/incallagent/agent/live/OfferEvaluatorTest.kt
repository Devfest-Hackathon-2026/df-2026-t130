// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OfferEvaluatorTest {
    private val policy = TestPolicy.example()
    private val ok = Offer(start = TestPolicy.sat19, partySize = 2, confirmedPreferences = listOf("禁煙席"))

    private fun eval(o: Offer) = OfferEvaluator.evaluate(policy, o)

    @Test
    fun firstChoiceIsAccepted() {
        val d = eval(ok)
        assertTrue(d.reasons.toString(), d.accept)
        assertTrue(d.isFirstChoice)
    }

    @Test
    fun alternativeInsideWindowsIsAcceptedButNotFirst() {
        val d = eval(ok.copy(start = LocalDateTime.of(2026, 11, 15, 18, 30), partySize = 3))
        assertTrue(d.reasons.toString(), d.accept)
        assertFalse(d.isFirstChoice)
    }

    @Test
    fun outsideWindowsAndPartySizeAreRefused() {
        val d = eval(ok.copy(start = LocalDateTime.of(2026, 11, 16, 19, 0), partySize = 4))
        assertFalse(d.accept)
        assertEquals(2, d.reasons.size)
    }

    @Test
    fun budget() {
        assertTrue(eval(ok.copy(pricePerPersonYen = 8000)).accept)
        assertFalse(eval(ok.copy(pricePerPersonYen = 8001)).accept)
        val noBudget = policy.copy(authority = policy.authority.copy(maxBudgetPerPersonYen = null))
        assertFalse(OfferEvaluator.evaluate(noBudget, ok.copy(pricePerPersonYen = 1000)).accept)
    }

    @Test
    fun conditionsNeedAuthority() {
        assertFalse(eval(ok.copy(requiresCancellationFee = true)).accept)
        assertFalse(eval(ok.copy(requiresPrepayment = true)).accept)
        assertFalse(eval(ok.copy(isWaitlist = true)).accept)
        val generous = policy.copy(authority = policy.authority.copy(mayAcceptCancellationPolicy = true))
        assertTrue(OfferEvaluator.evaluate(generous, ok.copy(requiresCancellationFee = true)).accept)
    }

    @Test
    fun requiredPreferenceMustBeConfirmed() {
        val d = eval(ok.copy(confirmedPreferences = listOf("個室")))
        assertFalse(d.accept)
        assertTrue(d.reasons.single().contains("禁煙席"))
    }

    @Test
    fun aRequiredPreferenceTheShopRefusedIsNotConfirmed() {
        assertFalse(eval(ok.copy(confirmedPreferences = listOf("禁煙席なし"))).accept)
    }

    @Test
    fun unclearOfferIsRefused() {
        assertFalse(eval(ok.copy(start = null)).accept)
        assertFalse(eval(ok.copy(partySize = null)).accept)
    }

    @Test
    fun inquiryNeverBooks() {
        assertFalse(OfferEvaluator.evaluate(policy.copy(purpose = Purpose.INQUIRY), ok).accept)
    }

    private fun cancelling(mayAcceptFee: Boolean) = policy.copy(
        purpose = Purpose.CANCEL,
        authority = policy.authority.copy(mayAcceptCancellationPolicy = mayAcceptFee),
    )

    @Test
    fun aFeeForACancellationFollowsWhatTheUserAllowed() {
        val fee = Offer(start = null, partySize = null, requiresCancellationFee = true)

        val agreed = OfferEvaluator.evaluate(cancelling(mayAcceptFee = true), fee)
        assertTrue(agreed.reasons.toString(), agreed.accept)
        assertFalse(agreed.isFirstChoice)
        assertTrue(agreed.reasons.isEmpty())

        val refused = OfferEvaluator.evaluate(cancelling(mayAcceptFee = false), fee)
        assertFalse(refused.accept)
        assertFalse(refused.isFirstChoice)
        assertEquals(listOf("キャンセル料に同意することは任されていません"), refused.reasons)
    }

    @Test
    fun aFeeForACancellationIsNotCheckedLikeABooking() {
        // Over the budget, outside the windows, the wrong party, no required preference, and still the user's call
        val fee = Offer(
            start = LocalDateTime.of(2026, 11, 16, 19, 0),
            partySize = 9,
            pricePerPersonYen = 8001,
            requiresCancellationFee = true,
        )
        assertTrue(OfferEvaluator.evaluate(cancelling(mayAcceptFee = true), fee).accept)
        assertEquals(1, OfferEvaluator.evaluate(cancelling(mayAcceptFee = false), fee).reasons.size)
    }

    @Test
    fun anOfferWithoutAFeeIsStillRefusedInACancellation() {
        assertFalse(OfferEvaluator.evaluate(cancelling(mayAcceptFee = true), ok).accept)
    }

    @Test
    fun aFeeInAnInquiryOrABookingIsNotACancellationFee() {
        val generous = policy.authority.copy(mayAcceptCancellationPolicy = true)
        val fee = ok.copy(requiresCancellationFee = true)
        assertFalse(OfferEvaluator.evaluate(policy.copy(purpose = Purpose.INQUIRY, authority = generous), fee).accept)
        // A booking still needs its date
        assertFalse(OfferEvaluator.evaluate(policy.copy(authority = generous), fee.copy(start = null)).accept)
    }
}
