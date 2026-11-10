// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** A function call from the model (`toolCall.functionCalls[]`). */
data class FunctionCall(val id: String?, val name: String, val args: JsonObject)

/** The answer sent back (`toolResponse.functionResponses[]`), matched by [id]. */
data class FunctionResponse(val id: String?, val name: String, val response: JsonObject)

/** What the tools do to the phone call. Implemented by the app (telecom layer). */
interface CallActions {
    fun endCall(reason: String)
    fun sendDtmf(digits: String)
}

/**
 * Executes the speaking agent's tool calls against an approved policy.
 *
 * - evaluate_offer: [OfferEvaluator] decides, the model only follows
 * - report_result: kept as [outcome]; checked with [OutcomeCheck], problems are sent back
 * - end_call: refused until a result was reported, so every call ends with an outcome
 * - send_dtmf: only 0-9 * #
 *
 * Not thread-safe: call [handle] from one thread (the WebSocket reader).
 */
class ToolCallHandler(
    private val policy: DelegationPolicy,
    private val actions: CallActions,
    private val clock: () -> Instant = Instant::now,
) {
    /** Last reported result, or null if the model has not reported yet. */
    var outcome: CallOutcome? = null
        private set

    /** [OutcomeCheck] problems of [outcome]. Not empty -> ask the user before using the result. */
    var outcomeViolations: List<OutcomeCheck.Violation> = emptyList()
        private set

    var ended = false
        private set

    fun handle(call: FunctionCall): FunctionResponse {
        val response = try {
            when (call.name) {
                LiveTools.EVALUATE_OFFER -> evaluateOffer(call.args)
                LiveTools.REPORT_RESULT -> reportResult(call.args)
                LiveTools.END_CALL -> endCall(call.args)
                LiveTools.SEND_DTMF -> sendDtmf(call.args)
                else -> error("知らないツールです：${call.name}")
            }
        } catch (e: BadArguments) {
            error(e.message ?: "引数が正しくありません")
        }
        return FunctionResponse(call.id, call.name, response)
    }

    private fun evaluateOffer(args: JsonObject): JsonObject {
        val offer = Offer(
            start = args.dateTime("start"),
            partySize = args.int("partySize"),
            pricePerPersonYen = args.int("pricePerPersonYen"),
            confirmedPreferences = args.strings("confirmedPreferences"),
            requiresCancellationFee = args.bool("requiresCancellationFee") ?: false,
            requiresPrepayment = args.bool("requiresPrepayment") ?: false,
            isWaitlist = args.bool("isWaitlist") ?: false,
        )
        val d = OfferEvaluator.evaluate(policy, offer)
        return buildJsonObject {
            put("accept", d.accept)
            put("isFirstChoice", d.isFirstChoice)
            putJsonArray("reasons") { d.reasons.forEach { add(JsonPrimitive(it)) } }
            put("instruction",
                if (policy.purpose == Purpose.CANCEL && offer.requiresCancellationFee) {
                    // A fee is a condition of the cancellation itself, not an offer to book
                    if (d.accept) {
                        "取り消しを進めてよい。金額を復唱して確かめ、summary に金額を書く。報告の status は DONE。"
                    } else {
                        "取り消さない。自分で決めずに「${policy.conversation.fallbackPhrase}」と伝え、report_result は NEEDS_USER_DECISION（pendingItems に金額）で報告する。"
                    }
                } else if (d.accept) {
                    "受けてよい。日時と人数を復唱して確かめてから受ける。報告の status は " +
                        if (d.isFirstChoice) "BOOKED。" else "BOOKED_ALTERNATIVE。"
                } else if (policy.purpose == Purpose.INQUIRY || policy.purpose == Purpose.CANCEL) {
                    "受けない。今回は予約のお願いではないと伝えて、用件に戻る。"
                } else {
                    "受けない。ほかの候補があればお願いし、なければ「${policy.conversation.fallbackPhrase}」と伝える。"
                })
        }
    }

    private fun reportResult(args: JsonObject): JsonObject {
        val statusText = args.string("status") ?: throw BadArguments("status がありません")
        val status = OutcomeStatus.entries.find { it.name == statusText }
            ?: throw BadArguments("status が正しくありません：$statusText")
        val summary = args.string("summary") ?: throw BadArguments("summary がありません")
        val start = args.dateTime("start")

        val booked = status == OutcomeStatus.BOOKED || status == OutcomeStatus.BOOKED_ALTERNATIVE
        val booking = if (booked && start != null) {
            ConfirmedBooking(
                start = start,
                partySize = args.int("partySize"),
                nameUsed = args.string("nameUsed"),
                confirmedPreferences = args.strings("confirmedPreferences"),
                shopConditions = args.strings("shopConditions"),
            )
        } else {
            null
        }

        val o = CallOutcome(
            policyId = policy.id,
            status = status,
            booking = booking,
            pendingItems = args.strings("pendingItems"),
            summary = summary,
            endedAt = clock(),
        )
        outcome = o
        outcomeViolations = OutcomeCheck.check(policy, o)

        return buildJsonObject {
            put("ok", outcomeViolations.isEmpty())
            putJsonArray("problems") { outcomeViolations.forEach { add(JsonPrimitive(it.message)) } }
            put("instruction",
                if (outcomeViolations.isEmpty()) "記録した。お礼を言ってから end_call を呼ぶ。"
                else "記録したが、委任の範囲と合わない点がある。内容を確かめて report_result を呼び直す。" +
                    "予約が範囲外になっていたら、お店に「${policy.conversation.fallbackPhrase}」と伝える。")
        }
    }

    private fun endCall(args: JsonObject): JsonObject {
        if (outcome == null) {
            return error("先に report_result で結果を報告してから end_call を呼ぶ。")
        }
        if (!ended) {
            ended = true
            actions.endCall(args.string("reason") ?: "")
        }
        return buildJsonObject { put("ok", true) }
    }

    private fun sendDtmf(args: JsonObject): JsonObject {
        val digits = args.string("digits") ?: throw BadArguments("digits がありません")
        if (!DTMF.matches(digits)) {
            return error("digits は 0〜9、*、# だけ、32文字までにする：$digits")
        }
        actions.sendDtmf(digits)
        return buildJsonObject { put("ok", true) }
    }

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message)
    }

    private class BadArguments(message: String) : Exception(message)

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? {
        val p = primitive(key) ?: return null
        return p.intOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toInt()
            ?: throw BadArguments("$key は整数にする")
    }

    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        ((this[key] as? JsonArray) ?: return emptyList())
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }

    private fun JsonObject.dateTime(key: String): LocalDateTime? {
        val s = string(key) ?: return null
        // Accept "2026-11-14T19:30", "2026-11-14 19:30:00"; a trailing offset or zone is ignored
        // (times are always in the policy time zone)
        val m = LOCAL_DATE_TIME.find(s) ?: throw BadArguments(dateTimeHint(key, s))
        return try {
            LocalDateTime.parse(m.value.replace(' ', 'T'))
        } catch (e: DateTimeParseException) {
            throw BadArguments(dateTimeHint(key, s))
        }
    }

    private fun dateTimeHint(key: String, s: String) = "$key は ISO 8601 の日時にする（例 2026-11-14T19:30）：$s"

    companion object {
        private val DTMF = Regex("[0-9*#]{1,32}")
        private val LOCAL_DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?")
    }
}
