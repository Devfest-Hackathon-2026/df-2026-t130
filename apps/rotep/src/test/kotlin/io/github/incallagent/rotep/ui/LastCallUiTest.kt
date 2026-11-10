// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.SavedResult
import io.github.incallagent.rotep.call.CallSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class LastCallUiTest {
    private fun saved(violations: List<OutcomeCheck.Violation> = emptyList()) = SavedResult(
        result = CallSession.Result(
            outcome = CallOutcome(
                policyId = "policy",
                status = OutcomeStatus.BOOKED,
                booking = ConfirmedBooking(start = LocalDateTime.of(2026, 11, 14, 19, 0), partySize = 2),
                summary = "-",
                // 19:05 in Japan
                endedAt = Instant.parse("2026-11-08T10:05:00Z"),
            ),
            violations = violations,
            reportedByAi = true,
            endReason = CallSession.EndReason.AI_HUNG_UP,
            detail = "-",
            answered = true,
            talkMs = 1_000,
        ),
        stage = RotepApp.Stage.L3,
        shopName = "レストラン・サンプル",
    )

    @Test
    fun itSaysWhichCallAndWhenInJapanTime() {
        assertEquals("レストラン・サンプル、11月8日(日) 19:05", lastCallUi(saved()).where)
    }

    @Test
    fun itSaysHowTheCallCameOut() {
        assertEquals("予約できました（第一希望）", lastCallUi(saved()).headline)
    }

    @Test
    fun aBookingTheCheckFoundFaultWithIsNotShownAsDone() {
        val faulty = saved(listOf(OutcomeCheck.Violation("booking.partySize", "許可した人数の範囲外です：4")))
        assertEquals("予約の返事はありましたが、確認が要ります", lastCallUi(faulty).headline)
    }
}
