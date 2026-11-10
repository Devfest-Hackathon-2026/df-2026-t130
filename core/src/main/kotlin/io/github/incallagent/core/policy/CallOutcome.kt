// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import io.github.incallagent.core.Redact
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime

/**
 * Result of one call, reported by the speaking agent and structured by the thinking agent.
 * Check it against the policy with [OutcomeCheck] before putting anything in the calendar.
 */
@Serializable
data class CallOutcome(
    val policyId: String,
    val status: OutcomeStatus,
    /** Set for BOOKED / BOOKED_ALTERNATIVE. */
    val booking: ConfirmedBooking? = null,
    /** Things the shop asked or offered that the agent could not decide (the reason for a callback). */
    val pendingItems: List<String> = emptyList(),
    /** Short Japanese summary for the notification. */
    val summary: String,
    @Serializable(with = InstantIsoSerializer::class)
    val endedAt: Instant,
)

@Serializable
enum class OutcomeStatus {
    /** Booked with the first choice (first window, preferred party size). */
    BOOKED,

    /** Booked with another choice that was inside the policy. */
    BOOKED_ALTERNATIVE,

    /** A cancellation went through or an inquiry was answered; what happened is in [CallOutcome.summary]. */
    DONE,

    /** Said the fallback phrase: the user has to decide [CallOutcome.pendingItems]. */
    NEEDS_USER_DECISION,

    /** The shop had nothing that fits (full, closed, not taking reservations). */
    UNAVAILABLE,

    /** No answer, busy, answering machine, or stuck in a voice menu. */
    NOT_REACHED,

    /** Technical failure (AI connection lost, call dropped, ...). */
    FAILED,
}

@Serializable
data class ConfirmedBooking(
    @Serializable(with = LocalDateTimeIsoSerializer::class)
    val start: LocalDateTime,
    val partySize: Int? = null,
    /** Name the shop wrote down. */
    val nameUsed: String? = null,
    /** Preferences the shop confirmed (subset of the policy preferences, as text). */
    val confirmedPreferences: List<String> = emptyList(),
    /** Conditions the shop stated: cancellation fee, time limit, deposit... */
    val shopConditions: List<String> = emptyList(),
) {
    override fun toString() = "ConfirmedBooking(start=$start, partySize=$partySize, " +
        "nameUsed=${Redact.name(nameUsed)}, confirmedPreferences=$confirmedPreferences, " +
        "shopConditions=$shopConditions)"
}
