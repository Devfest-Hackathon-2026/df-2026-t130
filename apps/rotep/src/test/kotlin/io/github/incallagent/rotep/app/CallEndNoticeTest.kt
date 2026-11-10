// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.URGENT_MARK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CallEndNoticeTest {
    private fun result(vararg pending: String) = CallSession.Result(
        outcome = CallOutcome(
            policyId = "policy",
            status = OutcomeStatus.NEEDS_USER_DECISION,
            pendingItems = pending.toList(),
            summary = "-",
            endedAt = Instant.EPOCH,
        ),
        violations = emptyList(),
        reportedByAi = true,
        endReason = CallSession.EndReason.AI_HUNG_UP,
        detail = "-",
        answered = true,
        talkMs = 1_000,
    )

    private val urgent = "$URGENT_MARK：受診をすすめられました"

    @Test
    fun theEndOfACallIsToldAbout() {
        assertEquals(CallEndNotice.FINISHED, callEndNotice(inCall = true, result()))
        assertEquals(CallEndNotice.FINISHED, callEndNotice(inCall = true, result("折り返しが要ります")))
    }

    @Test
    fun whatCannotWaitComesFirst() {
        assertEquals(CallEndNotice.URGENT, callEndNotice(inCall = true, result(urgent)))
        assertEquals(CallEndNotice.URGENT, callEndNotice(inCall = false, result(urgent)))
    }

    @Test
    fun aCallThatWasNeverPlacedHasNothingToTellAbout() {
        assertEquals(CallEndNotice.NONE, callEndNotice(inCall = true, result().copy(dialed = false)))
        assertEquals(CallEndNotice.NONE, callEndNotice(inCall = true, result().copy(dialed = false, endReason = CallSession.EndReason.CALL_FAILED)))
    }

    @Test
    fun aCallTheAppLostIsNotToldAsOver() {
        assertEquals(CallEndNotice.URGENT, callEndNotice(inCall = true, result().copy(endReason = CallSession.EndReason.CALL_FAILED, leftOpen = true)))
        assertEquals(CallEndNotice.URGENT, callEndNotice(inCall = true, result().copy(leftOpen = true)))
    }

    @Test
    fun aCallSeenToEndIsOverEvenAfterAFailure() {
        assertEquals(CallEndNotice.FINISHED, callEndNotice(inCall = true, result().copy(endReason = CallSession.EndReason.CALL_FAILED)))
    }

    @Test
    fun aPracticeHasNoCallToLose() {
        assertEquals(CallEndNotice.NONE, callEndNotice(inCall = false, result().copy(endReason = CallSession.EndReason.CALL_FAILED)))
    }

    @Test
    fun onlyACallThatWasPlacedReplacesTheKeptResult() {
        assertTrue(keepsResult(inCall = true, result()))
        assertFalse("a refused dial is not a call", keepsResult(inCall = true, result().copy(dialed = false)))
        assertFalse("a practice is not a call", keepsResult(inCall = false, result()))
    }

    @Test
    fun aPracticeHasNoCallToTellAbout() {
        assertEquals(CallEndNotice.NONE, callEndNotice(inCall = false, result()))
    }
}
