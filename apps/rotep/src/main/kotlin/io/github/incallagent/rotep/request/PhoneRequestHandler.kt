// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PolicyProblem
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.urgentItems
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.ui.RequestForm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Answers what comes in over adb or from the server. A request is checked as the confirm screen
 * would check it and only then waits in [requests]; nothing here can start a call, that stays with
 * the confirm screen.
 */
class PhoneRequestHandler(private val requests: PhoneRequestStore, private val phone: Phone) {
    /** What the desk needs to know from the app. */
    interface Phone {
        fun now(): Instant

        fun today(): LocalDate

        fun callRunning(): Boolean

        /** Why this number cannot be called, or null. */
        fun dialProblem(policy: DelegationPolicy): String?

        /** What must be set up on the phone before a call can start, or null. */
        fun readiness(): String?

        /** The settings are for the practice without a call: no shop will be phoned. */
        fun practice(): Boolean

        /** Shows the notification. False if the user cannot see it. */
        fun notifyUser(): Boolean

        fun clearNotice()

        /** What must be set up before a call can wait for its time, or null. */
        fun scheduleSetupProblem(): String? = null
    }

    /**
     * One encoded reply for every [method]; never throws for what the sender put in [arg].
     * [requestId]: the id the server gave a request it delivers; adb requests get a new one here.
     * [assistant]: which AI asked, if the server knows.
     */
    fun handle(method: String, arg: String?, requestId: String? = null, assistant: String? = null): String = PhoneRequestCodec.encode(
        when (method) {
            PhoneRequestProtocol.METHOD_PING -> ok {}
            PhoneRequestProtocol.METHOD_CREATE -> create(arg, requestId, assistant)
            PhoneRequestProtocol.METHOD_GET -> get(arg)
            PhoneRequestProtocol.METHOD_CANCEL -> cancel(arg)
            else -> refused("unknown_method", "知らない操作です")
        },
    )

    private fun create(arg: String?, requestId: String?, assistant: String?): JsonObject {
        // Before anything else: delivered again, this request must not be refused because of itself
        requestId?.let { id -> requests.delivered(id)?.let { return alreadyHere(id, it) } }
        val request = PhoneRequestCodec.decode(arg) ?: return refused("invalid", "依頼の形が読めません")
        if (phone.callRunning()) return refused("busy", "スマホは今、電話の途中です。終わってから頼んでください")
        val parsed = when (val p = PhoneRequestCodec.parse(request)) {
            is PhoneRequestCodec.Parsed.Refused -> return invalid(p.problems)
            is PhoneRequestCodec.Parsed.Ok -> p
        }
        val questions = PhoneRequestCodec.missing(parsed.form)
        if (questions.isNotEmpty()) return needsInfo(questions)
        val problems = problemsOf(parsed.form, parsed.callAt)
        if (problems.isNotEmpty()) return invalid(problems)
        return when (val added = requests.add(parsed.form, parsed.numberSource, parsed.callAt, requestId, assistant)) {
            is PhoneRequestStore.Added.Busy -> refused("busy", BUSY) {
                put("existing_id", added.id)
            }
            PhoneRequestStore.Added.TooMany -> refused("too_many", "依頼が多すぎます。しばらくしてから頼んでください")
            is PhoneRequestStore.Added.AlreadyHandled -> alreadyHere(requestId.orEmpty(), added.status)
            is PhoneRequestStore.Added.Ok -> ok {
                put("id", added.id)
                put("status", PhoneRequestStore.Status.WAITING.wire)
                put("phone_notified", phone.notifyUser())
                put("message", "預かりました。本人がスマホで内容を確かめて承認するまで、電話はかかりません。スマホのアプリを開くよう、本人に伝えてください")
                parsed.callAt?.let { put("call_at", offsetText(it)) }
                put("practice", phone.practice())
                if (phone.practice()) put("practice_note", "スマホの設定が通話なしの練習になっています。承認されても、お店に電話はかかりません")
                (phone.readiness() ?: parsed.callAt?.let { phone.scheduleSetupProblem() })?.let { put("phone_setup_needed", it) }
            }
        }
    }

    private fun alreadyHere(id: String, status: PhoneRequestStore.Status): JsonObject = ok {
        put("id", id)
        put("status", status.wire)
        put("message", explanation(status))
    }

    private fun needsInfo(questions: List<PhoneRequestCodec.Question>): JsonObject = ok {
        put("status", "needs_info")
        put("message", "足りない情報があります。次のことを足して、依頼をもう一度送ってください。依頼はまだ預かっていません")
        putJsonArray("questions") {
            for (q in questions) {
                addJsonObject {
                    put("field", q.field)
                    put("question", q.question)
                }
            }
        }
    }

    private fun offsetText(at: Instant): String = at.atZone(ScheduleRules.ZONE).toOffsetDateTime().toString()

    /** What the confirm screen would refuse, so that a request the user could not approve never waits. */
    private fun problemsOf(form: RequestForm, callAt: Instant?): List<String> {
        val now = phone.now()
        val timing = callAt?.let { ScheduleRules.problem(ScheduleRules.localOf(it), now) }
        return listOfNotNull(timing) + when (val built = form.toPolicy(UUID.randomUUID().toString(), now, phone.today())) {
            is RequestForm.Built.Errors -> built.messages
            is RequestForm.Built.Ok ->
                // As of the time of the call: wishes that will be over by then cannot be asked for
                PolicyValidator.validate(built.policy, callAt ?: now).map {
                    if (callAt != null && it.code == PolicyProblem.Code.WINDOW_IN_PAST) "希望の日時が、電話をかける時刻（call_at）より前になっています" else it.message
                } + listOfNotNull(phone.dialProblem(built.policy))
        }
    }

    private fun get(arg: String?): JsonObject {
        val request = PhoneRequestCodec.decode(arg) ?: return refused("invalid", "id が読めません")
        val id = if ("id" in request) idOf(request) ?: return refused("invalid", "id が読めません") else requests.latest() ?: return refused("not_found", NOT_FOUND)
        val seen = requests.get(id) ?: return refused("not_found", NOT_FOUND)
        return ok {
            put("id", id)
            put("status", seen.status.wire)
            put("message", explanation(seen))
            seen.scheduledFor?.let { put("scheduled_for", offsetText(it)) }
            seen.report?.let { put("result", it) }
        }
    }

    private fun cancel(arg: String?): JsonObject {
        val id = idIn(arg) ?: return refused("invalid", "id が読めません")
        return when (requests.withdraw(id)) {
            PhoneRequestStore.Withdrawn.DONE -> {
                phone.clearNotice()
                withdrawn(id)
            }
            // Not waiting; or only the written-down status is left, after the process died
            PhoneRequestStore.Withdrawn.TOO_LATE, PhoneRequestStore.Withdrawn.NOT_FOUND ->
                requests.get(id)?.let { cannotWithdraw(id, it) } ?: refused("not_found", NOT_FOUND)
        }
    }

    private fun withdrawn(id: String) = ok {
        put("id", id)
        put("status", PhoneRequestStore.Status.CANCELLED.wire)
        put("message", "取り下げました。電話はかけません")
    }

    /** No longer waiting: the call runs, it waits for its time, or the request has already ended some other way. */
    private fun cannotWithdraw(id: String, seen: PhoneRequestStore.Seen): JsonObject = when (seen.status) {
        PhoneRequestStore.Status.CALLING -> refused("too_late", "もう取り下げられません。電話を止められるのは本人だけです")
        PhoneRequestStore.Status.SCHEDULED -> refused("too_late", "本人が承認して、指定の時刻を待っています。取り消せるのは本人だけです")
        PhoneRequestStore.Status.CANCELLED -> withdrawn(id)
        else -> refused("already_ended", explanation(seen)) { put("status", seen.status.wire) }
    }

    private fun idIn(arg: String?): String? = PhoneRequestCodec.decode(arg)?.let { idOf(it) }

    private fun idOf(request: JsonObject): String? =
        PhoneRequestCodec.stringField(request, "id")?.takeIf { it.length <= MAX_ID_CHARS }

    private fun explanation(seen: PhoneRequestStore.Seen): String =
        if ((seen.report?.get(PRACTICE) as? JsonPrimitive)?.booleanOrNull == true) {
            "通話なしの練習が終わりました。お店に電話はしていません"
        } else {
            seen.reason ?: explanation(seen.status)
        }

    private fun explanation(status: PhoneRequestStore.Status): String = when (status) {
        PhoneRequestStore.Status.WAITING -> "本人がスマホで確かめるのを待っています。まだ電話はしていません"
        PhoneRequestStore.Status.SCHEDULED -> "本人が承認しました。指定の時刻に電話します"
        PhoneRequestStore.Status.CALLING -> "電話をしています"
        PhoneRequestStore.Status.FINISHED -> "電話が終わりました"
        PhoneRequestStore.Status.DECLINED -> "本人が断りました。電話はしていません"
        PhoneRequestStore.Status.CANCELLED -> "取り下げられました。電話はしていません"
        PhoneRequestStore.Status.EXPIRED -> "承認されないまま期限が切れました。電話はしていません"
        PhoneRequestStore.Status.NOT_PLACED -> "指定の時刻に電話をかけられませんでした"
        PhoneRequestStore.Status.UNKNOWN -> "電話を始めたあとの記録が残っていません。予約などができたかは分かりません。送り直さず、本人に確かめてください"
    }

    private fun ok(fill: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("ok", true)
        fill()
    }

    private fun invalid(problems: List<String>) = refused("invalid", "このままでは預かれません。直して、もう一度頼んでください") {
        putJsonArray("problems") { problems.forEach { add(it) } }
    }

    private fun refused(error: String, message: String, fill: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", error)
        put("message", message)
        fill()
    }

    private companion object {
        const val MAX_ID_CHARS = 64

        const val BUSY = "前の依頼がまだ終わっていません。一度に 1 件だけ預かります。新しい依頼は送らず、get_call_request で前の依頼の状態を確かめてください。本人がまだ承認していなければ、cancel_call_request で取り下げてから送り直せます"

        const val NOT_FOUND = "この id の依頼は残っていません。電話をしたかどうかは分かりません。送り直さず、本人に確かめてください"
    }
}

private const val PRACTICE = "practice"

/**
 * What the sender is told about a finished call. No phone number and no conversation lines. The
 * texts come from what the other side said, so they are data for the sender, not instructions.
 */
fun callReport(result: CallSession.Result, changedOnPhone: Boolean?, practice: Boolean): JsonObject = buildJsonObject {
    val outcome = result.outcome
    put("outcome", outcome.status.name.lowercase())
    put(PRACTICE, practice)
    // The AI reported, and what it reported fits what was approved. Whether the errand succeeded
    // is in the outcome. The practice without a call never counts
    put("verified", !practice && result.reportedByAi && result.violations.isEmpty())
    put("summary", outcome.summary)
    putJsonArray("pending_items") { outcome.pendingItems.forEach { add(it) } }
    putJsonArray("urgent_items") { outcome.urgentItems().forEach { add(it) } }
    outcome.booking?.let { booking ->
        putJsonObject("booking") {
            put("start", booking.start.toString())
            booking.partySize?.let { put("party_size", it) }
            booking.nameUsed?.let { put("name_used", it) }
            putJsonArray("confirmed_preferences") { booking.confirmedPreferences.forEach { add(it) } }
            putJsonArray("shop_conditions") { booking.shopConditions.forEach { add(it) } }
        }
    }
    putJsonArray("violations") { result.violations.forEach { add(it.message) } }
    put("answered", result.answered)
    result.talkMs?.let { put("talk_seconds", it / 1000) }
    put("end_reason", result.endReason.name.lowercase())
    put("detail", result.detail)
    put("changed_on_phone", changedOnPhone)
}

/** Whether the user changed anything the sender asked for, or gave the AI more room, before approving. */
fun changedOnPhone(asked: RequestForm, approved: DelegationPolicy, today: LocalDate): Boolean {
    val original = asked.toPolicy(approved.id, approved.createdAt, today) as? RequestForm.Built.Ok ?: return true
    return original.policy != approved.copy(approval = null)
}
