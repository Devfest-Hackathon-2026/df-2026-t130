// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.calendar.CalendarUi
import io.github.incallagent.rotep.calendar.calendarUi
import io.github.incallagent.rotep.calendar.openCalendarApp
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.urgentItems
import io.github.incallagent.rotep.ui.component.AitelHero
import io.github.incallagent.rotep.ui.component.HeroKind
import io.github.incallagent.rotep.ui.component.largeText
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import io.github.incallagent.rotep.ui.theme.ResultHeadlineStyle
import io.github.incallagent.rotep.ui.theme.ResultHeadlineStyleLargeText

/** 結果：the CallOutcome and what OutcomeCheck says about it. */
@Composable
fun ResultScreen() {
    val running = RotepApp.call.collectAsState().value
    val kept = RotepApp.lastResult.collectAsState().value
    val context = LocalContext.current
    val calendar = RotepApp.calendar
    val calendarStatus by calendar.status.collectAsState()
    if (running == null && kept != null) {
        // Opened from "前回の電話": no call of this run of the app is behind it
        ResultContent(
            result = kept.result,
            onNewRequest = { RotepApp.newRequest() },
            lastCall = lastCallUi(kept),
            onDeleteLastCall = RotepApp::deleteLastResult,
            shopName = kept.shopName,
            calendar = calendarUi(kept.result, calendarStatus),
            onAddToCalendar = { openCalendarApp(context, calendar, kept.result, kept.shopName) },
            onUndoCalendar = calendar::undo,
            followUps = followUps(kept.result),
            onFollowUp = RotepApp::followUp,
        )
    } else {
        val result = running?.session?.state?.collectAsState()?.value?.result
        val draft by RotepApp.draft.collectAsState()
        val shopName = draft?.target?.businessName
        ResultContent(
            result = result,
            onNewRequest = { RotepApp.newRequest() },
            shopName = shopName,
            practice = running?.stage == RotepApp.Stage.L0,
            calendar = calendarUi(result, calendarStatus),
            onAddToCalendar = { result?.let { openCalendarApp(context, calendar, it, shopName) } },
            onUndoCalendar = calendar::undo,
            followUps = result?.let { followUps(it) }.orEmpty(),
            onFollowUp = RotepApp::followUp,
        )
    }
}

/**
 * The result of the call. The headline says "予約できました" only when the check against the policy
 * found nothing; the check comes right after it. [result] null: no result yet. [lastCall]: this is
 * the result kept from an earlier call, so the screen says which one and lets the user delete it.
 */
@Composable
fun ResultContent(
    result: CallSession.Result?,
    onNewRequest: () -> Unit,
    modifier: Modifier = Modifier,
    lastCall: LastCallUi? = null,
    onDeleteLastCall: () -> Unit = {},
    shopName: String? = null,
    practice: Boolean = false,
    calendar: CalendarUi = CalendarUi.Hidden,
    onAddToCalendar: () -> Unit = {},
    onUndoCalendar: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    followUps: List<FollowUp> = emptyList(),
    onFollowUp: (FollowUp) -> Unit = {},
) {
    if (result == null) {
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenName("結果")
            Text("結果はまだありません")
            MainButton(onClick = onNewRequest, Modifier.fillMaxWidth(), compact = isShortScreen()) { Text("最初に戻る") }
        }
        return
    }
    val o = result.outcome
    val headline = resultHeadline(result)
    val urgentItems = o.urgentItems()
    // The celebration plays once, when the result opens: not again when the hero scrolls back into
    // view or the screen turns
    var celebrated by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Bright, and celebrating, only for a booking the check found nothing wrong with
            val hero = resultHero(headline)
            val good = resultIsGood(headline) && !practice
            AitelHero(
                if (good) HeroKind.RESULT_GOOD else HeroKind.RESULT_OTHER, "結果", if (practice) "練習：${hero.first}" else hero.first, hero.second, hero.mood,
                // A result read again later is not news, and a practice booked nothing
                celebrate = lastCall == null && !practice && resultCelebrates(headline) && !celebrated,
                onCelebrate = { celebrated = true },
                titleIsHeading = !headlineAddsToHero(headline),
            )
        }
        if (practice) item { Notice("練習でした。お店に電話はしていません") }
        lastCall?.let { item { Notice("前回の電話の結果です\n${it.where}") } }
        // What cannot wait comes first: the alert that brought the user here said so
        for (urgent in urgentItems) {
            item { Notice(urgent, error = true) }
        }
        item {
            // Under the hero only what it has not said already
            if (headlineAddsToHero(headline)) {
                val review = headline == ResultHeadline.BOOKED_NEEDS_REVIEW
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallIcon(
                        headlineIcon(headline),
                        modifier = Modifier.size(28.dp),
                        tint = if (review) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        headlineText(headline),
                        style = if (largeText()) ResultHeadlineStyleLargeText else ResultHeadlineStyle,
                        color = if (review) MaterialTheme.colorScheme.error else Color.Unspecified,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            Text(o.summary, style = MaterialTheme.typography.bodyLarge)
            resultNextStep(result, practice)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        // No call was placed: nothing was said to compare, and the line above already says why
        if (result.dialed) {
            item {
                Section("任せた内容と合っているか") {
                    if (result.violations.isEmpty()) {
                        Text("問題はありません", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        ErrorText("範囲と合わない点があります。カレンダーに入れる前に確認してください")
                        for (v in result.violations) ErrorText("・${violationText(v)}")
                    }
                }
            }
        }
        o.booking?.let { b ->
            item {
                // Large only for a booking the check found nothing wrong with
                if (resultIsGood(headline)) {
                    KeyFacts("予約の内容") {
                        shopName?.let { KeyFact("お店", listOf(it), LocalAitelColors.current.heroResult) }
                        KeyFact("日時", listOf(dateTime(b.start)), LocalAitelColors.current.heroResult)
                        b.partySize?.let { KeyFact("人数", listOf("${it}名"), LocalAitelColors.current.heroResult) }
                        BookingNotes(b)
                    }
                } else {
                    Section(if (result.violations.isEmpty()) "予約の内容" else "予約の内容（お店の返事。まだ確かめていません）") {
                        shopName?.let { Text("お店：$it", style = MaterialTheme.typography.bodyMedium) }
                        Text("日時：${dateTime(b.start)}", style = MaterialTheme.typography.bodyMedium)
                        b.partySize?.let { Text("人数：${it}名", style = MaterialTheme.typography.bodyMedium) }
                        BookingNotes(b)
                    }
                }
            }
        }
        if (calendar != CalendarUi.Hidden) {
            item { CalendarSection(calendar, changed = result.purpose == Purpose.CHANGE, onAddToCalendar, onUndoCalendar) }
        }
        val others = o.pendingItems - urgentItems.toSet()
        if (others.isNotEmpty()) {
            item {
                Section("折り返しが要ること") {
                    for (p in others) Text("・$p", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (result.dialed) {
            item {
                Section("通話") {
                    Small(if (result.reportedByAi) "AI が報告した結果です" else "AI は結果を報告しませんでした。終わり方から書いた結果です")
                    // Connected is all the phone knows: it can be a recording that says the number is not in use
                    result.talkMs?.let { Small("つながってからの時間：${mmss(it)}") }
                }
            }
        }
        if (followUps.isNotEmpty()) {
            item {
                Section("続けて頼む") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (next in followUps) {
                            MainOutlinedButton(
                                onClick = { onFollowUp(next) },
                                modifier = Modifier.weight(1f).semantics { contentDescription = next.spoken },
                                compact = true,
                            ) { Text(next.label) }
                        }
                    }
                    Small("内容を入れた依頼のフォームが開きます。まだ電話はかけません" + if (lastCall != null) "。電話番号が入っていないときは、入れ直してください" else "")
                }
            }
        }
        if (lastCall != null) {
            item {
                Small("この端末に残した結果です。次の電話で入れ替わります。")
                TextButton(onClick = onDeleteLastCall) { Text("この結果を消す") }
            }
        }
        item {
            MainButton(onClick = onNewRequest, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("最初に戻る") }
        }
    }
}

/** The rest of a booking, under its date and party size. */
@Composable
private fun BookingNotes(b: ConfirmedBooking) {
    b.nameUsed?.let { Text("予約名：$it", style = MaterialTheme.typography.bodyMedium) }
    if (b.confirmedPreferences.isNotEmpty()) Text("確認できたこと：${b.confirmedPreferences.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
    if (b.shopConditions.isNotEmpty()) Text("お店の条件：${b.shopConditions.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
}

/** A card of its own: how the calendar went says nothing about the booking above it. */
@Composable
private fun CalendarSection(ui: CalendarUi, changed: Boolean, onAdd: () -> Unit, onUndo: () -> Unit) {
    Section("カレンダー") {
        when (ui) {
            CalendarUi.Hidden -> {}
            CalendarUi.Adding -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WaitingMark()
                Text("カレンダーに追加しています", style = MaterialTheme.typography.bodyMedium)
            }
            is CalendarUi.Added -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.problem == null) SmallIcon(R.drawable.ic_check_circle, tint = MaterialTheme.colorScheme.primary)
                    Text("カレンダーに追加しました", style = MaterialTheme.typography.bodyMedium)
                }
                ui.problem?.let { ErrorText(it) }
                Small("消えるのはカレンダーの予定だけです。お店の予約はそのままです")
                TextButton(
                    onClick = onUndo,
                    enabled = !ui.undoing,
                    modifier = Modifier.semantics { contentDescription = "カレンダーの予定を取り消す" },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text("取り消す") }
            }
            is CalendarUi.CanAdd -> {
                ui.note?.let { Small(it) }
                MainOutlinedButton(onClick = onAdd, Modifier.fillMaxWidth(), compact = true) { Text("カレンダーに追加") }
            }
        }
        if (changed && ui != CalendarUi.Adding) Small("変更前の予定がカレンダーにあるときは、カレンダーのアプリで消してください")
    }
}

/** Whether the line under the hero says more than the hero's big words: the same words twice only get in the way. */
internal fun headlineAddsToHero(h: ResultHeadline): Boolean = resultHero(h).let { headlineText(h) != it.first + it.second.orEmpty() }

/** The icon beside the headline (the headline's words carry the meaning). */
private fun headlineIcon(h: ResultHeadline): Int = when (h) {
    ResultHeadline.BOOKED_FIRST, ResultHeadline.BOOKED_OTHER,
    ResultHeadline.CHANGED, ResultHeadline.CHANGED_OTHER,
    ResultHeadline.CANCELLED, ResultHeadline.ANSWERED,
    -> R.drawable.ic_check_circle
    ResultHeadline.BOOKED_NEEDS_REVIEW -> R.drawable.ic_warning
    ResultHeadline.NEEDS_DECISION, ResultHeadline.NOT_CANCELLED_YET, ResultHeadline.NOT_DIALED, ResultHeadline.STOPPED_BY_USER -> R.drawable.ic_info
    ResultHeadline.UNAVAILABLE, ResultHeadline.NOT_REACHED, ResultHeadline.FAILED,
    ResultHeadline.NOT_CHANGED, ResultHeadline.NOT_CANCELLED, ResultHeadline.NOT_ANSWERED,
    -> R.drawable.ic_cancel
}
