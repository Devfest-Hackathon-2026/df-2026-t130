// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** One problem found in a policy. [field] is a JSON-path-like hint ("request.windows[0]"). */
data class PolicyProblem(val code: Code, val field: String, val message: String) {
    enum class Code {
        BLANK,
        INVALID_PHONE_NUMBER,
        INVALID_TIME_ZONE,
        NO_WINDOWS,
        WINDOW_REVERSED,
        WINDOW_IN_PAST,
        INVALID_PARTY_SIZE,
        PARTY_SIZE_REQUIRED,
        NAME_REQUIRED,
        NEGATIVE_VALUE,
        AI_DISCLOSURE_MISSING,
        PERSON_NAME_IN_INTRODUCTION,
        FORBIDDEN_DISCLOSURE,
        UNSUPPORTED_SCHEMA,
    }

    override fun toString() = "$code at $field: $message"
}

/**
 * Checks that a policy can be shown for approval and used for a call.
 * The thinking agent's (LLM) output must pass this before it reaches the user.
 * Messages are Japanese because they are shown to the user.
 */
object PolicyValidator {
    private const val MAX_PARTY_SIZE = 50

    fun validate(p: DelegationPolicy, now: Instant): List<PolicyProblem> {
        val out = ArrayList<PolicyProblem>()
        fun add(code: PolicyProblem.Code, field: String, msg: String) {
            out += PolicyProblem(code, field, msg)
        }

        if (p.schemaVersion != DelegationPolicy.SCHEMA_VERSION) {
            add(PolicyProblem.Code.UNSUPPORTED_SCHEMA, "schemaVersion",
                "このアプリが扱えない形式です（schemaVersion=${p.schemaVersion}）")
        }
        if (p.id.isBlank()) add(PolicyProblem.Code.BLANK, "id", "id が空です")

        val zone = try {
            ZoneId.of(p.timeZone)
        } catch (e: DateTimeException) {
            add(PolicyProblem.Code.INVALID_TIME_ZONE, "timeZone", "タイムゾーンが不正です：${p.timeZone}")
            null
        }

        // target
        if (p.target.businessName.isBlank()) add(PolicyProblem.Code.BLANK, "target.businessName", "電話先の名前が空です")
        if (PhoneNumbers.normalizeJp(p.target.phoneNumber) == null) {
            add(PolicyProblem.Code.INVALID_PHONE_NUMBER, "target.phoneNumber", "電話番号の形が正しくありません")
        }

        // request
        val booking = p.purpose == Purpose.NEW_BOOKING || p.purpose == Purpose.CHANGE
        val r = p.request
        if (booking && r.windows.isEmpty()) {
            add(PolicyProblem.Code.NO_WINDOWS, "request.windows", "希望の日時が1つもありません")
        }
        r.windows.forEachIndexed { i, w ->
            val f = "request.windows[$i]"
            if (w.latestStart.isBefore(w.earliestStart)) {
                add(PolicyProblem.Code.WINDOW_REVERSED, f, "終わりの時刻が始まりより前です")
            }
            if (zone != null && w.latestStart.atZone(zone).toInstant().isBefore(now)) {
                add(PolicyProblem.Code.WINDOW_IN_PAST, f, "過去の日時です")
            }
        }
        if (booking && r.partySize == null) {
            add(PolicyProblem.Code.PARTY_SIZE_REQUIRED, "request.partySize", "人数が決まっていません")
        }
        r.partySize?.let { ps ->
            if (ps.min < 1 || ps.max > MAX_PARTY_SIZE || ps.min > ps.preferred || ps.preferred > ps.max) {
                add(PolicyProblem.Code.INVALID_PARTY_SIZE, "request.partySize",
                    "人数は 1〜$MAX_PARTY_SIZE で、最少 ≦ 希望 ≦ 最多 にしてください")
            }
        }
        r.durationMinutes?.let { if (it <= 0) add(PolicyProblem.Code.NEGATIVE_VALUE, "request.durationMinutes", "時間は1分以上にしてください") }
        r.preferences.forEachIndexed { i, pref ->
            if (pref.text.isBlank()) add(PolicyProblem.Code.BLANK, "request.preferences[$i]", "希望の内容が空です")
        }

        // authority
        p.authority.maxBudgetPerPersonYen?.let {
            if (it < 0) add(PolicyProblem.Code.NEGATIVE_VALUE, "authority.maxBudgetPerPersonYen", "予算が負の値です")
        }

        // disclosure
        val d = p.disclosure
        if (booking && d.reservationName.isNullOrBlank()) {
            add(PolicyProblem.Code.NAME_REQUIRED, "disclosure.reservationName", "予約名が決まっていません")
        }
        d.callbackNumber?.let {
            if (PhoneNumbers.normalizeJp(it) == null) {
                add(PolicyProblem.Code.INVALID_PHONE_NUMBER, "disclosure.callbackNumber", "連絡先の電話番号の形が正しくありません")
            }
        }
        val never = d.neverTell.map { it.trim() }.toSet()
        d.mayTell.forEachIndexed { i, item ->
            val f = "disclosure.mayTell[$i]"
            if (item.label.isBlank() || item.value.isBlank()) add(PolicyProblem.Code.BLANK, f, "伝えてよい情報の名前か値が空です")
            if (item.label.trim() in never) {
                add(PolicyProblem.Code.FORBIDDEN_DISCLOSURE, f, "「${item.label}」は伝えない情報にも入っています")
            }
        }

        // conversation
        val c = p.conversation
        if (!c.aiDisclosure.contains("AI") && !c.aiDisclosure.contains("ＡＩ")) {
            add(PolicyProblem.Code.AI_DISCLOSURE_MISSING, "conversation.aiDisclosure",
                "最初のあいさつで AI であることを名乗っていません")
        }
        d.reservationName?.trim()?.takeIf { it.length >= 2 }?.let { name ->
            if (c.aiDisclosure.contains(name)) {
                add(PolicyProblem.Code.PERSON_NAME_IN_INTRODUCTION, "conversation.aiDisclosure",
                    "名乗りに人名を使わないでください（予約名は聞かれたときに伝えます）")
            }
        }
        if (c.fallbackPhrase.isBlank()) add(PolicyProblem.Code.BLANK, "conversation.fallbackPhrase", "折り返しの言い方が空です")
        if (c.maxCallSeconds <= 0) add(PolicyProblem.Code.NEGATIVE_VALUE, "conversation.maxCallSeconds", "通話時間の上限は1秒以上にしてください")

        return out
    }
}

/** Minimal Japanese phone number check (no external library). */
object PhoneNumbers {
    /**
     * Returns the domestic digits ("0300000000") or null when it does not look like a Japanese
     * number. Accepts "03-0000-0000", "(03) 0000 0000", "+81 3 0000 0000", and the trunk 0 after
     * +81 written or not ("+81 (0)3 0000 0000", "+81 03 0000 0000"). Never digits that start with
     * 00 (carrier selection) or 010 (international): they are not domestic numbers.
     */
    fun normalizeJp(raw: String): String? {
        val compact = raw.filterNot { it == '-' || it == ' ' || it == '(' || it == ')' || it == '　' }
        val domestic = when {
            compact.startsWith("+81") -> compact.removePrefix("+81").let { if (it.startsWith("0")) it else "0$it" }
            else -> compact
        }
        if (!domestic.all { it in '0'..'9' }) return null
        if (!domestic.startsWith("0")) return null
        if (NOT_DOMESTIC.any { domestic.startsWith(it) }) return null
        return if (domestic.length in 10..11) domestic else null
    }

    private val NOT_DOMESTIC = listOf("00", "010")
}
