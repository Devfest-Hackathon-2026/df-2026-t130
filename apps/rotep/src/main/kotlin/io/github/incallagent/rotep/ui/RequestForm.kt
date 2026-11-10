// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.Authority
import io.github.incallagent.core.policy.BookingRequest
import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.CallTarget
import io.github.incallagent.core.policy.ConversationRules
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.DisclosableItem
import io.github.incallagent.core.policy.Disclosure
import io.github.incallagent.core.policy.PartySize
import io.github.incallagent.core.policy.Preference
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.core.policy.TimeWindow
import io.github.incallagent.rotep.call.SpokenReading
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** One wished-for start time: a date and a range of start times ([to] empty: exactly [from]). */
data class WindowInput(val date: String = "", val from: String = "", val to: String = "")

/**
 * The request screen as typed. [toPolicy] turns it into a (not yet approved) delegation policy;
 * whether the policy is acceptable is [io.github.incallagent.core.policy.PolicyValidator]'s job on
 * the confirm screen. Parse errors, and what each purpose cannot go without, are reported here.
 */
data class RequestForm(
    val businessName: String = "",
    val phoneNumber: String = "",
    val kind: BusinessKind = BusinessKind.RESTAURANT,
    val purpose: Purpose = Purpose.NEW_BOOKING,
    val windows: List<WindowInput> = listOf(WindowInput()),
    val partyPreferred: String = "2",
    val partyMin: String = "",
    val partyMax: String = "",
    val durationMinutes: String = "",
    /** Comma-separated: without these the booking is not acceptable (e.g. "禁煙席"). */
    val requiredPreferences: String = "",
    /** Comma-separated wishes (e.g. "個室"). */
    val optionalPreferences: String = "",
    /** What the shop may be told (the details of a change, an occasion...). */
    val notesForShop: String = "",
    val reservationName: String = "",
    val callbackNumber: String = "",
    /** One per line: "名前：値" (e.g. "メールアドレス：…"). */
    val mayTell: String = "",
    val maxBudgetPerPersonYen: String = "",
    val mayAcceptOutsideWindows: Boolean = false,
    val mayAcceptCancellationPolicy: Boolean = false,
    val mayAcceptPrepayment: Boolean = false,
    val mayLeaveVoicemail: Boolean = false,
    val maxCallMinutes: String = "5",
    /** How the AI says what is written here. Not part of the policy: it does not widen or change what is approved. */
    val readings: List<SpokenReading> = emptyList(),
) {
    /** The purposes that ask for a time and a party size; the others do not use those fields. */
    val takesBooking: Boolean get() = purpose == Purpose.NEW_BOOKING || purpose == Purpose.CHANGE

    /** Another kind, with the usual party size for it, unless the user already changed the number. */
    fun withKind(new: BusinessKind): RequestForm {
        // Not when a range was typed: the preferred size could fall outside it
        val untouched = partyPreferred == usualParty(kind) && partyMin.isBlank() && partyMax.isBlank()
        return copy(kind = new, partyPreferred = if (untouched) usualParty(new) else partyPreferred)
    }

    /**
     * Another purpose. The note means something else for each one, so it starts empty, and agreeing
     * to a fee for a cancel is a different decision from agreeing to a shop's terms for a booking.
     */
    fun withPurpose(new: Purpose): RequestForm {
        if (new == purpose) return this
        val crossesCancel = (purpose == Purpose.CANCEL) != (new == Purpose.CANCEL)
        return copy(purpose = new, notesForShop = "", mayAcceptCancellationPolicy = mayAcceptCancellationPolicy && !crossesCancel)
    }

    sealed interface Built {
        data class Ok(val policy: DelegationPolicy) : Built
        data class Errors(val messages: List<String>) : Built
    }

    fun toPolicy(id: String, createdAt: Instant, today: LocalDate): Built {
        val errors = ArrayList<String>()
        // A cancel or an inquiry asks for no time or party size: what those hidden fields hold does not count
        val booking = takesBooking
        if (purpose == Purpose.CANCEL) {
            if (reservationName.isBlank()) errors += "予約名を書いてください（お店が予約を探すのに使います）"
            if (notesForShop.isBlank()) errors += "取り消す予約の日時などを書いてください"
        }
        if (purpose == Purpose.CHANGE && notesForShop.isBlank()) errors += "もとの予約と、変えたいことを書いてください"
        if (purpose == Purpose.INQUIRY && notesForShop.isBlank()) errors += "聞きたいことを書いてください"

        val windows = this.windows.takeIf { booking }.orEmpty().withIndex()
            .filter { (_, w) -> w.date.isNotBlank() || w.from.isNotBlank() || w.to.isNotBlank() }
            .mapNotNull { (i, w) ->
                val label = "希望の日時 ${i + 1}"
                val date = parseDate(w.date, today) ?: run {
                    errors += "$label：日付の形が分かりません（例 2026-11-14 または 11/14）"
                    return@mapNotNull null
                }
                val from = parseTime(w.from) ?: run {
                    errors += "$label：開始の時刻の形が分かりません（例 19:00）"
                    return@mapNotNull null
                }
                val to = if (w.to.isBlank()) from else parseTime(w.to) ?: run {
                    errors += "$label：終わりの時刻の形が分かりません（例 20:00）"
                    return@mapNotNull null
                }
                TimeWindow(LocalDateTime.of(date, from), LocalDateTime.of(date, to))
            }

        val partySize = if (!booking || partyPreferred.isBlank()) {
            null
        } else {
            val preferred = number(partyPreferred)
            val min = if (partyMin.isBlank()) preferred else number(partyMin)
            val max = if (partyMax.isBlank()) preferred else number(partyMax)
            if (preferred == null || min == null || max == null) {
                errors += "人数は数字で書いてください"
                null
            } else {
                PartySize(preferred = preferred, min = min, max = max)
            }
        }

        val duration = durationMinutes.takeIf { booking && it.isNotBlank() }?.let {
            number(it) ?: run {
                errors += "時間（分）は数字で書いてください"
                null
            }
        }
        val budget = maxBudgetPerPersonYen.takeIf { booking && it.isNotBlank() }?.let {
            number(it) ?: run {
                errors += "予算は数字で書いてください（例 8000）"
                null
            }
        }
        val callMinutes = number(maxCallMinutes)
        when {
            callMinutes == null -> errors += "通話の上限（分）は数字で書いてください"
            callMinutes > MAX_CALL_MINUTES -> errors += "通話の上限は $MAX_CALL_MINUTES 分までにしてください"
        }
        val maxCallSeconds = (callMinutes ?: 5).coerceAtMost(MAX_CALL_MINUTES) * 60

        val mayTellItems = mayTell.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val sep = line.indexOfFirst { it == '：' || it == ':' }
            if (sep <= 0 || sep == line.length - 1) {
                errors += "伝えてよいこと：「名前：値」の形で書いてください（$line）"
                null
            } else {
                DisclosableItem(line.substring(0, sep).trim(), line.substring(sep + 1).trim())
            }
        }

        if (errors.isNotEmpty()) return Built.Errors(errors)
        // Shops often ask why; the user sees this line on the confirm screen
        val tell = if (purpose == Purpose.CANCEL && mayTellItems.none { "理由" in it.label }) {
            mayTellItems + DisclosableItem("キャンセルの理由", "都合がつかなくなったため")
        } else {
            mayTellItems
        }
        return Built.Ok(
            DelegationPolicy(
                id = id,
                createdAt = createdAt,
                target = CallTarget(businessName.trim(), phoneNumber.trim(), kind),
                purpose = purpose,
                request = BookingRequest(
                    partySize = partySize,
                    windows = windows,
                    durationMinutes = duration,
                    preferences = if (!booking) {
                        emptyList()
                    } else {
                        list(requiredPreferences).map { Preference(it, required = true) } +
                            list(optionalPreferences).map { Preference(it, required = false) }
                    },
                    notesForShop = notesForShop.trim().ifEmpty { null },
                ),
                authority = Authority(
                    mayAcceptOutsideWindows = booking && mayAcceptOutsideWindows,
                    maxBudgetPerPersonYen = budget,
                    mayAcceptCancellationPolicy = purpose != Purpose.INQUIRY && mayAcceptCancellationPolicy,
                    mayAcceptPrepayment = booking && mayAcceptPrepayment,
                    mayLeaveVoicemail = mayLeaveVoicemail,
                ),
                disclosure = Disclosure(
                    reservationName = reservationName.trim().ifEmpty { null },
                    callbackNumber = callbackNumber.trim().ifEmpty { null },
                    mayTell = tell,
                ),
                conversation = ConversationRules(maxCallSeconds = maxCallSeconds),
            )
        )
    }

    companion object {
        /** The AI's connection is not good for longer: a voice session of Gemini Live lasts 15 minutes without compressing what was said. */
        private const val MAX_CALL_MINUTES = 15

        /** The form for an existing policy ("練習用の例を入れる"). */
        fun from(p: DelegationPolicy): RequestForm {
            val r = p.request
            val a = p.authority
            val d = p.disclosure
            return RequestForm(
                businessName = p.target.businessName,
                phoneNumber = p.target.phoneNumber,
                kind = p.target.kind,
                purpose = p.purpose,
                windows = r.windows.map {
                    WindowInput(
                        date = it.earliestStart.toLocalDate().toString(),
                        from = time(it.earliestStart.toLocalTime()),
                        to = if (it.latestStart == it.earliestStart) "" else time(it.latestStart.toLocalTime()),
                    )
                }.ifEmpty { listOf(WindowInput()) },
                partyPreferred = r.partySize?.preferred?.toString() ?: "",
                partyMin = r.partySize?.min?.toString() ?: "",
                partyMax = r.partySize?.max?.toString() ?: "",
                durationMinutes = r.durationMinutes?.toString() ?: "",
                requiredPreferences = r.preferences.filter { it.required }.joinToString("、") { it.text },
                optionalPreferences = r.preferences.filterNot { it.required }.joinToString("、") { it.text },
                notesForShop = r.notesForShop ?: "",
                reservationName = d.reservationName ?: "",
                callbackNumber = d.callbackNumber ?: "",
                mayTell = d.mayTell.joinToString("\n") { "${it.label}：${it.value}" },
                maxBudgetPerPersonYen = a.maxBudgetPerPersonYen?.toString() ?: "",
                mayAcceptOutsideWindows = a.mayAcceptOutsideWindows,
                mayAcceptCancellationPolicy = a.mayAcceptCancellationPolicy,
                mayAcceptPrepayment = a.mayAcceptPrepayment,
                mayLeaveVoicemail = a.mayLeaveVoicemail,
                maxCallMinutes = (p.conversation.maxCallSeconds / 60).coerceAtLeast(1).toString(),
            )
        }

        private fun time(t: LocalTime) = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

        /** "2026-11-14", "2026/11/14", "11/14", "11-14", "11月14日". Without a year: the next such day. */
        fun parseDate(text: String, today: LocalDate): LocalDate? {
            val s = digitsToAscii(text).trim().replace('／', '/')
            val full = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?$").find(s)
            val short = Regex("^(\\d{1,2})[-/月](\\d{1,2})日?$").find(s)
            return when {
                full != null -> full.groupValues.let { dateOrNull(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
                short != null -> short.groupValues.let { nextOccurrence(today, it[1].toInt(), it[2].toInt()) }
                else -> null
            }
        }

        /** [month]/[day] this year, or next year once it has passed. February 29 is never moved to the 28th. */
        private fun nextOccurrence(today: LocalDate, month: Int, day: Int): LocalDate? =
            listOf(today.year, today.year + 1)
                .mapNotNull { year -> dateOrNull(year, month, day) }
                .firstOrNull { !it.isBefore(today) }

        private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? = try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }

        /** "19:00", "19：30", "19時", "19時30分", "19". */
        fun parseTime(text: String): LocalTime? {
            val s = digitsToAscii(text).trim().replace('：', ':')
            val m = Regex("^(\\d{1,2})(?:[:時](\\d{1,2})?分?)?$").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }

        private fun number(text: String): Int? =
            digitsToAscii(text).trim().removeSuffix("円").removeSuffix("名").removeSuffix("分").replace(",", "").trim().toIntOrNull()

        private fun usualParty(kind: BusinessKind) = if (kind == BusinessKind.RESTAURANT) "2" else "1"

        private fun list(text: String): List<String> =
            text.split(',', '、', '，').map { it.trim() }.filter { it.isNotEmpty() }

        /** Full-width digits typed with a Japanese keyboard. */
        private fun digitsToAscii(s: String) = buildString(s.length) {
            for (c in s) append(if (c in '０'..'９') '0' + (c - '０') else c)
        }
    }
}
