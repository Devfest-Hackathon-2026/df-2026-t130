// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import io.github.incallagent.core.Redact
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime

/**
 * Delegation policy: what the speaking agent may do on one call.
 *
 * The thinking agent builds it, the user approves it, and the speaking agent must stay inside it.
 * Anything outside it ends with [ConversationRules.fallbackPhrase] ("確認して折り返します").
 *
 * Contains a phone number and a reservation name, so toString() of the classes holding them is
 * masked (ADR-021). Use [PolicyJson] for storage, never toString().
 *
 * See docs/delegation-policy.md.
 */
@Serializable
data class DelegationPolicy(
    /** Bumped when the JSON shape changes incompatibly. */
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Unique id, e.g. a UUID. CallOutcome.policyId points here. */
    val id: String,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    /** Set when the user approves. A policy without approval must not be used for a call. */
    val approval: Approval? = null,
    /** IANA time zone the [TimeWindow]s are written in. */
    val timeZone: String = "Asia/Tokyo",
    val target: CallTarget,
    val purpose: Purpose,
    val request: BookingRequest,
    val authority: Authority = Authority(),
    val disclosure: Disclosure,
    val conversation: ConversationRules = ConversationRules(),
) {
    val isApproved: Boolean get() = approval != null

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class Approval(
    @Serializable(with = InstantIsoSerializer::class)
    val approvedAt: Instant,
)

/** Who to call. */
@Serializable
data class CallTarget(
    /** Shop or clinic name, as the user knows it. */
    val businessName: String,
    /** Number to dial. Japanese domestic ("03-0000-0000") or +81 form. */
    val phoneNumber: String,
    val kind: BusinessKind = BusinessKind.OTHER,
) {
    override fun toString() =
        "CallTarget(businessName=$businessName, phoneNumber=${Redact.phoneNumber(phoneNumber)}, kind=$kind)"
}

@Serializable
enum class BusinessKind { RESTAURANT, SALON, CLINIC, OTHER }

@Serializable
enum class Purpose {
    /** Make a new reservation. */
    NEW_BOOKING,

    /** Change an existing reservation (details in [BookingRequest.notesForShop]). */
    CHANGE,

    /** Cancel an existing reservation. */
    CANCEL,

    /** Only ask a question (opening hours, availability), never book. */
    INQUIRY,
}

/** What the user wants. */
@Serializable
data class BookingRequest(
    val partySize: PartySize? = null,
    /**
     * Acceptable start times, best first. The agent tries them in order and may accept any
     * start time inside any window without asking.
     */
    val windows: List<TimeWindow> = emptyList(),
    /** Expected length of stay/appointment, if it matters (e.g. "2時間制"). */
    val durationMinutes: Int? = null,
    /** Seat, course, menu, stylist... Required ones are conditions, others are wishes. */
    val preferences: List<Preference> = emptyList(),
    /** Free text the agent may tell the shop (e.g. "誕生日のお祝いです"). */
    val notesForShop: String? = null,
)

/** Party size the agent may accept: [preferred] first, anything in [min]..[max] is fine. */
@Serializable
data class PartySize(
    val preferred: Int,
    val min: Int = preferred,
    val max: Int = preferred,
) {
    operator fun contains(n: Int): Boolean = n in min..max
}

/** A start time between [earliestStart] and [latestStart] (inclusive), in the policy time zone. */
@Serializable
data class TimeWindow(
    @Serializable(with = LocalDateTimeIsoSerializer::class)
    val earliestStart: LocalDateTime,
    @Serializable(with = LocalDateTimeIsoSerializer::class)
    val latestStart: LocalDateTime,
) {
    operator fun contains(t: LocalDateTime): Boolean = !t.isBefore(earliestStart) && !t.isAfter(latestStart)

    companion object {
        /** A window that only accepts exactly [start]. */
        fun exactly(start: LocalDateTime) = TimeWindow(start, start)
    }
}

@Serializable
data class Preference(
    val text: String,
    /** true: the booking is only acceptable with this. false: nice to have. */
    val required: Boolean = false,
)

/** What the agent may decide by itself. Everything else -> fallback phrase. */
@Serializable
data class Authority(
    /** Accept a start time outside every window. Normally false: propose it to the user instead. */
    val mayAcceptOutsideWindows: Boolean = false,
    /** Upper limit per person in yen (course price etc.). null: price must not be agreed to. */
    val maxBudgetPerPersonYen: Int? = null,
    /** Agree to a cancellation fee policy the shop states. */
    val mayAcceptCancellationPolicy: Boolean = false,
    /** Agree to a deposit or prepayment. The agent never handles payment details either way. */
    val mayAcceptPrepayment: Boolean = false,
    /** Put the user on a waiting list when nothing is available. */
    val mayJoinWaitlist: Boolean = false,
    /** Leave a message on an answering machine. false: hang up and report NOT_REACHED. */
    val mayLeaveVoicemail: Boolean = false,
)

/** What the agent may and may not tell. */
@Serializable
data class Disclosure(
    /** Name for the reservation (e.g. "ヤマダ"). Not used when the agent introduces itself. */
    val reservationName: String? = null,
    /** Number to give when the shop asks for a contact number. null: do not give one. */
    val callbackNumber: String? = null,
    /** Other facts the agent may tell when asked, e.g. label "メールアドレス". */
    val mayTell: List<DisclosableItem> = emptyList(),
    /** Never told, even when asked. Defaults cover payment and identity data. */
    val neverTell: List<String> = DEFAULT_NEVER_TELL,
) {
    override fun toString() = "Disclosure(reservationName=${Redact.name(reservationName)}, " +
        "callbackNumber=${Redact.phoneNumber(callbackNumber)}, " +
        "mayTell=${mayTell.map { it.label }}, neverTell=$neverTell)"

    companion object {
        val DEFAULT_NEVER_TELL = listOf(
            "クレジットカード番号",
            "銀行口座",
            "暗証番号・パスワード",
            "住所",
            "生年月日",
            "マイナンバー・保険証などの番号",
            "病状や健康に関する詳細",
        )
    }
}

@Serializable
data class DisclosableItem(val label: String, val value: String) {
    override fun toString() = "DisclosableItem(label=$label, value=${Redact.truncate(value)})"
}

/** Fixed lines and limits of the conversation. */
@Serializable
data class ConversationRules(
    /** First thing said after the shop answers. Must say it is an AI and must not use a person's name. */
    val aiDisclosure: String = DEFAULT_AI_DISCLOSURE,
    /** Said for anything outside the policy, then the call ends. */
    val fallbackPhrase: String = DEFAULT_FALLBACK,
    /** BCP 47 language tag. */
    val language: String = "ja-JP",
    /** Hard limit for one call. */
    val maxCallSeconds: Int = 300,
) {
    companion object {
        const val DEFAULT_AI_DISCLOSURE = "お忙しいところ失礼いたします。こちらはAIによる代理のお電話です。"
        const val DEFAULT_FALLBACK = "その点は確認して、改めてこちらから折り返しご連絡いたします。"
    }
}
