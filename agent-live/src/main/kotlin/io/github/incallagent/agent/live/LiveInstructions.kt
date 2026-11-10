// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.core.policy.TimeWindow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Builds the system instruction for the speaking agent from an approved delegation policy.
 *
 * The rules section is fixed (docs/design.md "設計方針として守ること"); the facts section comes
 * from the policy. Decisions about offers are not left to the model: it must call
 * `evaluate_offer` ([OfferEvaluator]) and follow the answer.
 *
 * Contains the reservation name and callback number (the model needs them to answer the shop).
 * Send it only to the paid Gemini API tier; never log it.
 */
object LiveInstructions {
    fun render(policy: DelegationPolicy, today: LocalDate): String {
        require(policy.isApproved) { "the delegation policy must be approved before a call" }

        val r = policy.request
        val a = policy.authority
        val d = policy.disclosure
        val c = policy.conversation

        return buildString {
            appendLine("あなたは、ユーザーの代わりにお店へ電話をかけている AI です。電話の相手は「${policy.target.businessName}」の方です。")
            appendLine("今日は ${date(today)} です。")
            appendLine()

            appendLine("# 必ず守ること")
            appendLine("1. 相手が出たら、最初に「${c.aiDisclosure}」と言い、AI であることを伝える。人の名前で名乗らない。")
            appendLine("2. 決めてよいのは、下の「任されていること」の範囲だけ。それ以外のことを聞かれたり提案されたりしたら、自分で決めずに「${c.fallbackPhrase}」と伝え、${LiveTools.REPORT_RESULT}（status は NEEDS_USER_DECISION、pendingItems に決められなかったこと）を呼んでから ${LiveTools.END_CALL} で終える。")
            appendLine("3. お店から日時・人数・金額・条件の提案があったら、受ける返事をする前に必ず ${LiveTools.EVALUATE_OFFER} を呼び、その答えに従う。accept が false なら受けない。")
            appendLine("4. 日時・人数・電話番号は、相手に復唱して確かめる。")
            appendLine("5. 次の情報は、聞かれても伝えない：${d.neverTell.joinToString("、")}。支払いに関わる情報は扱わない。")
            appendLine("6. 用件が済んだら、${LiveTools.REPORT_RESULT} で結果を報告し、お礼を言ってから ${LiveTools.END_CALL} を呼ぶ。")
            appendLine("7. 自動音声の案内でボタンの操作を求められたら ${LiveTools.SEND_DTMF} を使う。" +
                if (a.mayLeaveVoicemail) "留守番電話につながったら、用件と折り返し先を短く残して終える。"
                else "留守番電話につながったら、何も残さずに status を NOT_REACHED として報告して終える。")
            appendLine("8. 丁寧な敬語で、短くはっきり話す。相手の話をさえぎらない。聞き取れなかったときは聞き返す。")
            appendLine("9. 通話は ${duration(c.maxCallSeconds)}以内に終える。")
            appendLine()

            appendLine("# 用件")
            appendLine(purpose(policy.purpose))
            appendLine()

            appendLine("# 希望")
            r.partySize?.let { appendLine("- 人数：${it.preferred}名") }
            if (r.windows.isNotEmpty()) {
                appendLine("- 日時（上から順にお願いする）：")
                r.windows.forEachIndexed { i, w -> appendLine("  ${i + 1}. ${window(w)}") }
            }
            r.durationMinutes?.let { appendLine("- 時間：${it}分") }
            for (p in r.preferences) {
                appendLine("- ${p.text}：" + if (p.required) "必須（これがないなら受けない）" else "できれば")
            }
            r.notesForShop?.takeIf { it.isNotBlank() }?.let { appendLine("- お店に伝えること：$it") }
            appendLine()

            appendLine("# 任されていること")
            r.partySize?.let { appendLine("- 人数は ${it.min}〜${it.max}名なら受けてよい") }
            if (r.windows.isNotEmpty()) {
                appendLine("- 日時は上の候補の範囲なら受けてよい" + if (a.mayAcceptOutsideWindows) "（範囲外でも受けてよい）" else "（範囲外は受けない）")
            }
            // A cancellation has no budget, and "決めない" would contradict an agreed cancellation fee
            if (policy.purpose != Purpose.CANCEL) {
                appendLine("- 金額：" + (a.maxBudgetPerPersonYen?.let { "1人 ${it}円まで" } ?: "決めない"))
            }
            appendLine("- キャンセル料などの条件への同意：" + yesNo(a.mayAcceptCancellationPolicy))
            appendLine("- 前払い・予約金への同意：" + yesNo(a.mayAcceptPrepayment))
            appendLine("- キャンセル待ちへの登録：" + yesNo(a.mayJoinWaitlist))
            appendLine("- 判断に迷ったら、受けずに折り返す")
            appendLine()

            appendLine("# 聞かれたら伝えてよい情報")
            appendLine("- 予約名：" + (d.reservationName?.let { "$it（名乗りには使わない）" } ?: "伝えない"))
            appendLine("- 連絡先の電話番号：" + (d.callbackNumber ?: "伝えない"))
            for (item in d.mayTell) {
                appendLine("- ${item.label}：${item.value}")
            }
        }.trimEnd() + "\n"
    }

    private fun yesNo(allowed: Boolean) = if (allowed) "してよい" else "しない"

    /** 300 -> "5 分", 90 -> "1 分 30 秒", 45 -> "45 秒" (whole minutes alone would round down). */
    internal fun duration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "$s 秒"
            s == 0 -> "$m 分"
            else -> "$m 分 $s 秒"
        }
    }

    private fun purpose(p: Purpose) = when (p) {
        Purpose.NEW_BOOKING -> "新しく予約をお願いする。"
        Purpose.CHANGE -> "すでにある予約の変更をお願いする（内容は「お店に伝えること」）。"
        Purpose.CANCEL -> "すでにある予約の取り消しをお願いする（内容は「お店に伝えること」）。"
        Purpose.INQUIRY -> "問い合わせだけをする。予約はしない。"
    }

    private fun date(d: LocalDate) =
        "${d.year}年${d.monthValue}月${d.dayOfMonth}日(${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)})"

    private fun dateTime(t: LocalDateTime) = "${date(t.toLocalDate())} ${time(t)}"

    private fun time(t: LocalDateTime) = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

    private fun window(w: TimeWindow) = when {
        w.earliestStart == w.latestStart -> "${dateTime(w.earliestStart)} から"
        w.earliestStart.toLocalDate() == w.latestStart.toLocalDate() ->
            "${dateTime(w.earliestStart)}〜${time(w.latestStart)} の間に始まる時間"
        else -> "${dateTime(w.earliestStart)}〜${dateTime(w.latestStart)} の間に始まる時間"
    }
}
