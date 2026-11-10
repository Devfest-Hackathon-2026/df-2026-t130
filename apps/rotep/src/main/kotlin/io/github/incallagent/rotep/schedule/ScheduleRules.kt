// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The rules of a call placed at a time the user chose (ADR-040), without Android so that unit tests
 * see them: which times can be chosen, and whether the time that came is still one to call at. All
 * in Japan's time, like the rest of the app. The texts never hold a number or what the call is for.
 */
object ScheduleRules {
    val ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

    /** The last day that can be chosen, counted in dates: any allowed time of that day. */
    const val DAYS_AHEAD = 7L

    /** The first time of a day that can be chosen, and the first that can no longer be. */
    private val DAY_STARTS: LocalTime = LocalTime.of(8, 0)
    private val DAY_ENDS: LocalTime = LocalTime.of(21, 0)

    val NOTICE_BEFORE: Duration = Duration.ofMinutes(1)

    /** So that the notice one minute before always has its minute. */
    val MIN_LEAD: Duration = Duration.ofMinutes(2)

    /** An alarm later than this is not the time the user agreed to. */
    val LATE_LIMIT: Duration = Duration.ofMinutes(5)

    /** An alarm earlier than this means that the clock was changed. */
    val EARLY_LIMIT: Duration = Duration.ofMinutes(1)

    /** Closer than this to the call, the notice would only arrive after it. */
    val NOTICE_TOO_LATE: Duration = Duration.ofSeconds(10)

    fun instantOf(at: LocalDateTime): Instant = at.atZone(ZONE).toInstant()

    fun localOf(at: Instant): LocalDateTime = at.atZone(ZONE).toLocalDateTime()

    fun today(now: Instant): LocalDate = localOf(now).toLocalDate()

    /** The day [approvedAt] fell on, when that is not [today]: the words of the request were written that day. */
    fun writtenOn(approvedAt: Instant?, today: LocalDate): LocalDate? =
        approvedAt?.let { today(it) }?.takeIf { it != today }

    /** Calls are not made late at night or early in the morning. */
    fun inDaytime(t: LocalTime): Boolean = !t.isBefore(DAY_STARTS) && t.isBefore(DAY_ENDS)

    /** Why [at] cannot be chosen at [now], or null. */
    fun problem(at: LocalDateTime, now: Instant): String? {
        val t = instantOf(at)
        return when {
            !t.isAfter(now) -> "過去の日時は指定できません"
            Duration.between(now, t) < MIN_LEAD -> "2 分以上先の時刻にしてください"
            !inDaytime(at.toLocalTime()) -> "夜 9 時から朝 8 時までは指定できません"
            at.toLocalDate().isAfter(today(now).plusDays(DAYS_AHEAD)) -> "指定できるのは 7 日先までです"
            else -> null
        }
    }

    /** Why the alarm that came at [now] for [fireAt] must not lead to a call, or null. */
    fun fireProblem(fireAt: Instant, now: Instant): String? = when {
        now.isBefore(fireAt.minus(EARLY_LIMIT)) -> "端末の時刻が変わったようなので、電話をかけませんでした"
        now.isAfter(fireAt.plus(LATE_LIMIT)) -> "指定の時刻を 5 分以上過ぎていたので、電話をかけませんでした"
        !inDaytime(localOf(now).toLocalTime()) -> "夜 9 時から朝 8 時までの時間になっていたので、電話をかけませんでした"
        else -> null
    }

    /** The days that can be chosen, today first. */
    fun dates(today: LocalDate): List<LocalDate> = (0..DAYS_AHEAD).map(today::plusDays)

    /** What the screen offers first: the time another AI asked for ([suggested]) when it can be chosen, or the next full hour that can. */
    fun firstChoice(now: Instant, suggested: Instant? = null): LocalDateTime {
        suggested?.let(::localOf)?.takeIf { problem(it, now) == null }?.let { return it }
        var t = localOf(now).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (problem(t, now) != null) t = t.plusHours(1)
        return t
    }

    /** "今日 11/9(月)", "明日 11/10(火)", "11/11(水)". */
    fun dateLabel(date: LocalDate, today: LocalDate): String {
        val day = "${date.monthValue}/${date.dayOfMonth}(${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"
        return when (date) {
            today -> "今日 $day"
            today.plusDays(1) -> "明日 $day"
            else -> day
        }
    }

    /** "11月10日(火) 11:00". */
    fun whenText(t: LocalDateTime): String =
        "${t.monthValue}月${t.dayOfMonth}日(${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)}) %d:%02d".format(Locale.ROOT, t.hour, t.minute)

    fun whenText(at: Instant): String = whenText(localOf(at))

    /** The band on the first screen. */
    fun bannerText(shopName: String, fireAt: Instant): String = "${whenText(fireAt)}に $shopName へ電話します"

    /** Told when the app is opened: the alarm did not come (a restart of the phone, the app was stopped). */
    fun lostText(shopName: String, fireAt: Instant): String =
        "${whenText(fireAt)}の $shopName への電話は、かけられませんでした（端末の再起動などで予定が消えました）。必要なら、もう一度依頼してください"
}
