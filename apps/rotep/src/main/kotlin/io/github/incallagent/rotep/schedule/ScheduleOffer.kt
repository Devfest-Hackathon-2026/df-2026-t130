// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.rotep.app.RotepApp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "時間を指定して電話する" on the confirm screen. [blocked]: why it cannot be used for this request now,
 * or null. [suggestedAt]: the time another AI asked for; only a wish, the user chooses the time and
 * approves it on this screen.
 */
data class ScheduleOffer(val blocked: String?, val today: LocalDate, val first: LocalDateTime, val suggestedAt: Instant? = null)

/** For the confirm screen, so that the time offered first is not taken for the user's own. */
fun suggestionNote(suggestedAt: Instant?): String? =
    suggestedAt?.let { "ほかの AI の希望：${ScheduleRules.whenText(ScheduleRules.localOf(it))}" }

/**
 * Why a call cannot be put off to a time of the user's choosing, or null. Only fully automatic control places and
 * ends a call by itself. Outside RotepApp (no Android), for unit tests.
 */
fun scheduleBlocked(
    alreadyScheduled: Boolean,
    stage: RotepApp.Stage,
    keepMicOn: Boolean,
    checkFailed: Boolean,
    blocker: String?,
    lookupHold: String?,
): String? = when {
    alreadyScheduled -> ScheduledCalls.ALREADY
    stage != RotepApp.Stage.L3 -> "時間を指定して電話するには、設定の「電話のしかた」を「全自動で電話をかける」にしてください"
    keepMicOn -> "設定の「端末のマイクを止めない」がオンのときは、時間を指定して電話できません（そばに人がいないと、部屋の音が相手に届くためです）"
    checkFailed -> "内容の確認で問題があります（上の一覧を直してください）"
    blocker != null -> blocker
    else -> lookupHold
}
