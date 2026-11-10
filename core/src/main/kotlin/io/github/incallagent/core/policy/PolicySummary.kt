// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Human-readable Japanese text of a policy, for the approval screen
 * ("人が読んで承認できる形にする", docs/design.md).
 *
 * This text shows the phone number and name in full: it is for the user's own screen.
 * Do not log it.
 */
object PolicySummary {
    fun render(p: DelegationPolicy): String = buildString {
        val r = p.request
        val a = p.authority
        val d = p.disclosure
        val c = p.conversation

        line("電話先", "${p.target.businessName}（${p.target.phoneNumber}）")
        line("用件", purpose(p.purpose))

        r.partySize?.let { ps ->
            val range = if (ps.min == ps.max) "" else "（${ps.min}〜${ps.max}名なら可）"
            line("人数", "${ps.preferred}名$range")
        }
        if (r.windows.isNotEmpty()) {
            appendLine("日時（上から順に聞く）：")
            r.windows.forEachIndexed { i, w -> appendLine("  ${i + 1}. ${window(w)}") }
        }
        r.durationMinutes?.let { line("時間", "${it}分") }
        if (r.preferences.isNotEmpty()) {
            line("条件", r.preferences.joinToString("、") { if (it.required) "${it.text}（必須）" else "${it.text}（できれば）" })
        }
        r.notesForShop?.takeIf { it.isNotBlank() }?.let { line(notesLabel(p.purpose), it) }

        line("予約名", d.reservationName ?: "（伝えない）")
        line("連絡先として伝える番号", d.callbackNumber ?: "（伝えない）")
        if (d.mayTell.isNotEmpty()) {
            line("聞かれたら伝えてよいこと", d.mayTell.joinToString("、") { "${it.label}：${it.value}" })
        }
        line("聞かれても伝えないこと", d.neverTell.joinToString("、"))

        // Lines about a booking are left out of a cancellation or an inquiry, unless the authority
        // was given anyway: the model must not hold anything this screen did not show
        val booking = p.purpose == Purpose.NEW_BOOKING || p.purpose == Purpose.CHANGE
        appendLine("AIが決めてよいこと：")
        if (booking) appendLine("  - 上の日時と人数の範囲での予約")
        if (booking || a.mayAcceptOutsideWindows) bullet("範囲外の日時での予約", a.mayAcceptOutsideWindows)
        // Nothing applies a budget to a cancellation and the model is not told one, so next to
        // "金額の上限はありません" it would only read as a limit on the fee that nothing keeps
        if (booking || (p.purpose == Purpose.INQUIRY && a.maxBudgetPerPersonYen != null)) {
            appendLine("  - 予算：" + (a.maxBudgetPerPersonYen?.let { "1人 ${yen(it)}まで" } ?: "決めない（金額の話になったら折り返す）"))
        }
        if (p.purpose == Purpose.CANCEL) {
            bullet("キャンセル料がかかっても取り消す（金額の上限はありません）", a.mayAcceptCancellationPolicy)
        } else if (booking || a.mayAcceptCancellationPolicy) {
            bullet("キャンセル料などの条件への同意", a.mayAcceptCancellationPolicy)
        }
        if (booking || a.mayAcceptPrepayment) bullet("前払い・予約金への同意", a.mayAcceptPrepayment)
        if (booking || a.mayJoinWaitlist) bullet("キャンセル待ちへの登録", a.mayJoinWaitlist)
        bullet("留守番電話への伝言", a.mayLeaveVoicemail)

        line("最初のあいさつ", "「${c.aiDisclosure}」")
        line("決められないとき", "「${c.fallbackPhrase}」と伝えて終える")
        line("通話の上限", "${c.maxCallSeconds / 60}分${(c.maxCallSeconds % 60).takeIf { it > 0 }?.let { "${it}秒" } ?: ""}")
    }

    private fun StringBuilder.line(label: String, value: String) {
        append(label).append("：").appendLine(value)
    }

    private fun StringBuilder.bullet(label: String, allowed: Boolean) {
        appendLine("  - $label：" + if (allowed) "してよい" else "しない（折り返す）")
    }

    private fun purpose(p: Purpose) = when (p) {
        Purpose.NEW_BOOKING -> "新しい予約"
        Purpose.CHANGE -> "予約の変更"
        Purpose.CANCEL -> "予約の取り消し"
        Purpose.INQUIRY -> "問い合わせのみ（予約はしない）"
    }

    private fun notesLabel(p: Purpose) = when (p) {
        Purpose.NEW_BOOKING -> "お店に伝えること"
        Purpose.CHANGE -> "もとの予約と、変えたいこと"
        Purpose.CANCEL -> "取り消す予約"
        Purpose.INQUIRY -> "聞きたいこと"
    }

    private fun window(w: TimeWindow): String =
        if (w.earliestStart == w.latestStart) {
            dateTime(w.earliestStart)
        } else if (w.earliestStart.toLocalDate() == w.latestStart.toLocalDate()) {
            "${dateTime(w.earliestStart)}〜${time(w.latestStart)} の間に開始"
        } else {
            "${dateTime(w.earliestStart)}〜${dateTime(w.latestStart)} の間に開始"
        }

    private fun dateTime(t: LocalDateTime): String {
        val dow = t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)
        return "${t.monthValue}月${t.dayOfMonth}日($dow) ${time(t)}"
    }

    private fun time(t: LocalDateTime) = "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

    private fun yen(n: Int) = NumberFormat.getIntegerInstance(Locale.JAPAN).format(n) + "円"
}
