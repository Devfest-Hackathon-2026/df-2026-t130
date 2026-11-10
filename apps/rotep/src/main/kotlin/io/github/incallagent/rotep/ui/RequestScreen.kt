// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.history.BookingHistory
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.ui.component.AitelHero
import io.github.incallagent.rotep.ui.component.HeroKind
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** 依頼：what to ask, of whom, and how far the AI may decide. */
@Composable
fun RequestScreen() {
    val form by RotepApp.form.collectAsState()
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    // What will happen with these settings: practice without a call (L0) or a real call
    val settings by RotepApp.settings.collectAsState()
    val practice = remember(settings) { RotepApp.stage() == RotepApp.Stage.L0 }
    val kept by RotepApp.lastResult.collectAsState()
    val records by BookingHistory.state.collectAsState()

    RequestContent(
        form = form,
        errors = errors,
        practice = practice,
        // Not next to the records, which hold every call: only when there are none (they are off)
        lastCall = kept?.takeIf { records.entries.isEmpty() }?.let(::lastCallUi),
        onShowLastCall = RotepApp::showLastResult,
        onOpenChat = RotepApp::openIntake,
        onEdit = { change -> RotepApp.form.update(change) },
        onFillExample = {
            RotepApp.dropOpenedRequest()
            RotepApp.form.value = RequestForm.from(PracticePolicy.draft(RotepApp.today(), Instant.now()))
            errors = emptyList()
        },
        onClearAll = {
            RotepApp.dropOpenedRequest()
            RotepApp.form.value = RequestForm()
            errors = emptyList()
        },
        onToConfirm = {
            when (val built = form.toPolicy(UUID.randomUUID().toString(), Instant.now(), RotepApp.today())) {
                is RequestForm.Built.Ok -> {
                    errors = emptyList()
                    RotepApp.confirmTyped(built.policy)
                }
                is RequestForm.Built.Errors -> errors = built.messages
            }
        },
    )
}

/**
 * The request form as typed, under the hero. [onEdit] changes the form; [errors] are what the last
 * "確認へ" found; [practice]: the settings make it a practice without a call (L0).
 * [listState]: where the form is scrolled to (the previews open it at a section).
 * [lastCall]: the result kept from the last real call, which the user can read again.
 */
@Composable
fun RequestContent(
    form: RequestForm,
    errors: List<String>,
    onEdit: ((RequestForm) -> RequestForm) -> Unit,
    onFillExample: () -> Unit,
    onClearAll: () -> Unit,
    onToConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    practice: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    lastCall: LastCallUi? = null,
    onShowLastCall: () -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val hero = requestHero(practice)
            AitelHero(HeroKind.REQUEST, "依頼", hero.first, hero.second, hero.mood)
        }
        lastCall?.let { call ->
            item {
                Section("前回の電話") {
                    Text(call.headline, style = MaterialTheme.typography.bodyLarge)
                    Small(call.where)
                    TextButton(onClick = onShowLastCall) { Text("結果を見る") }
                }
            }
        }
        item {
            // Side by side, or one under the other when the text is large
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenChat) { Text("AI と話して入れる") }
                // The practice number can only be used in a practice
                if (practice) OutlinedButton(onClick = onFillExample) { Text("練習用の例を入れる") }
                TextButton(onClick = onClearAll) { Text("全部消す") }
            }
            if (practice) Small("練習用の例は架空の店・番号・名前です。")
        }

        item {
            Section("電話先") {
                Field("店名", form.businessName) { v -> onEdit { it.copy(businessName = v) } }
                Field("電話番号", form.phoneNumber, keyboard = KeyboardType.Phone) { v -> onEdit { it.copy(phoneNumber = v) } }
                Small("種類")
                ChoiceButtons(BusinessKind.entries, form.kind, ::kindLabel) { v -> onEdit { it.withKind(v) } }
                Small("注文や訪問などは「そのほか」を選んでください")
            }
        }

        item {
            Section("用件") {
                RadioRows(Purpose.entries, form.purpose, ::purposeLabel) { v -> onEdit { it.withPurpose(v) } }
                Field(notesLabel(form.purpose), form.notesForShop, singleLine = false, supporting = notesHelp(form.purpose)) { v ->
                    onEdit { it.copy(notesForShop = v) }
                }
                Small("1回の電話で1件です")
            }
        }

        if (form.takesBooking) item {
            Section("希望の日時（上から順にお願いします）") {
                form.windows.forEachIndexed { i, w ->
                    // The date has a line of its own: "2026-11-14" needs the room (no row of three holds it)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field("日付 ${i + 1}", w.date, Modifier.weight(1f)) { v -> onEdit { it.withWindow(i, w.copy(date = v)) } }
                        if (form.windows.size > 1) {
                            TextButton(
                                onClick = { onEdit { it.copy(windows = it.windows.filterIndexed { j, _ -> j != i }) } },
                                modifier = Modifier.semantics { contentDescription = "日時 ${i + 1} を消す" },
                            ) { Text("✕") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("開始", w.from, Modifier.weight(1f)) { v -> onEdit { it.withWindow(i, w.copy(from = v)) } }
                        Field("〜まで", w.to, Modifier.weight(1f)) { v -> onEdit { it.withWindow(i, w.copy(to = v)) } }
                    }
                }
                Small("日付は 2026-11-14 か 11/14、時刻は 19:00。「〜まで」が空なら、その時刻だけ")
                if (form.windows.size < 5) {
                    TextButton(onClick = { onEdit { it.copy(windows = it.windows + WindowInput()) } }) { Text("日時を足す") }
                }
            }
        }

        if (form.takesBooking) item {
            Section("人数と時間") {
                PartyRow("希望の人数", form.partyPreferred, fallback = "") { v -> onEdit { it.copy(partyPreferred = v) } }
                PartyRow("最少の人数", form.partyMin, fallback = form.partyPreferred) { v -> onEdit { it.copy(partyMin = v) } }
                PartyRow("最多の人数", form.partyMax, fallback = form.partyPreferred) { v -> onEdit { it.copy(partyMax = v) } }
                Small("最少・最多が空なら、希望の人数だけ")
                Field("時間（分）", form.durationMinutes, keyboard = KeyboardType.Number, supporting = "空なら決めない") { v ->
                    onEdit { it.copy(durationMinutes = v) }
                }
            }
        }

        if (form.takesBooking) item {
            Section("席や条件") {
                Field("必須", form.requiredPreferences, supporting = "ないなら受けない。「、」で区切る") { v -> onEdit { it.copy(requiredPreferences = v) } }
                Field("できれば", form.optionalPreferences, supporting = "「、」で区切る") { v -> onEdit { it.copy(optionalPreferences = v) } }
            }
        }

        item {
            Section("伝えてよいこと") {
                Field("予約名", form.reservationName, supporting = "名乗りには使いません。フルネームや漢字を聞かれそうなら「ほかに伝えてよいこと」に書いてください") { v -> onEdit { it.copy(reservationName = v) } }
                Field("連絡先の電話番号", form.callbackNumber, keyboard = KeyboardType.Phone, supporting = if (form.takesBooking) "お店はほぼ必ず聞きます" else null) { v -> onEdit { it.copy(callbackNumber = v) } }
                Field("ほかに伝えてよいこと", form.mayTell, singleLine = false, supporting = "聞かれたら伝える。1行に「名前：値」（例 初めてか：はい）") { v ->
                    onEdit { it.copy(mayTell = v) }
                }
                Small("カード番号・口座・住所・生年月日などは、聞かれても伝えません。住所が要るときは、ご自分でお店に伝えてください")
            }
        }

        item {
            Section("任せる範囲（AI が自分で決めてよいこと）") {
                if (form.takesBooking) {
                    Field("予算（円）", form.maxBudgetPerPersonYen, keyboard = KeyboardType.Number, supporting = "1人あたり。空なら金額は決めない") { v ->
                        onEdit { it.copy(maxBudgetPerPersonYen = v) }
                    }
                    AuthorityRow("希望の範囲の外の日時でも受けてよい", form.mayAcceptOutsideWindows) { v -> onEdit { it.copy(mayAcceptOutsideWindows = v) } }
                    AuthorityRow("キャンセル料などの条件に同意してよい", form.mayAcceptCancellationPolicy) { v -> onEdit { it.copy(mayAcceptCancellationPolicy = v) } }
                    AuthorityRow("前払い・予約金に同意してよい（金額の上限はありません）", form.mayAcceptPrepayment) { v -> onEdit { it.copy(mayAcceptPrepayment = v) } }
                } else if (form.purpose == Purpose.CANCEL) {
                    AuthorityRow("キャンセル料がかかっても取り消してよい（金額の上限はありません）", form.mayAcceptCancellationPolicy) { v -> onEdit { it.copy(mayAcceptCancellationPolicy = v) } }
                }
                AuthorityRow("留守番電話に伝言を残してよい", form.mayLeaveVoicemail) { v -> onEdit { it.copy(mayLeaveVoicemail = v) } }
                Field("通話の上限（分）", form.maxCallMinutes, keyboard = KeyboardType.Number) { v -> onEdit { it.copy(maxCallMinutes = v) } }
                Small("任せていないことは、AI は決めずに「確認して折り返します」と伝えます")
            }
        }

        if (errors.isNotEmpty()) {
            item { for (e in errors) ErrorText(e) }
        }

        item {
            MainButton(onClick = onToConfirm, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("確認へ") }
        }
    }
}

/**
 * A party size: "−", the field, "＋" on one line. The buttons rewrite the text of the field (see
 * [partyStep]); they cannot be used when the field is not a number or the size would leave 1..50.
 */
@Composable
private fun PartyRow(label: String, value: String, fallback: String, onChange: (String) -> Unit) {
    val down = partyStep(value, fallback, -1)
    val up = partyStep(value, fallback, +1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { down?.let(onChange) },
            enabled = down != null,
            modifier = Modifier.semantics { contentDescription = "${label}を1人減らす" },
        ) { Icon(painterResource(R.drawable.ic_remove), contentDescription = null) }
        Field(label, value, Modifier.weight(1f), KeyboardType.Number, onChange = onChange)
        IconButton(
            onClick = { up?.let(onChange) },
            enabled = up != null,
            modifier = Modifier.semantics { contentDescription = "${label}を1人増やす" },
        ) { Icon(painterResource(R.drawable.ic_add), contentDescription = null) }
    }
}

/** One thing the AI may decide by itself, with what it does now (off, the default: it calls back). */
@Composable
private fun AuthorityRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(label, on, supporting = authorityStateText(on), onChange = onChange)
}

private fun RequestForm.withWindow(i: Int, w: WindowInput) = copy(windows = windows.toMutableList().also { it[i] = w })

fun kindLabel(k: BusinessKind) = when (k) {
    BusinessKind.RESTAURANT -> "飲食店"
    BusinessKind.SALON -> "美容院など"
    BusinessKind.CLINIC -> "病院・医院"
    BusinessKind.OTHER -> "そのほか"
}

private fun notesLabel(p: Purpose) = when (p) {
    Purpose.NEW_BOOKING -> "お店に伝えること"
    Purpose.CHANGE -> "もとの予約と、変えたいこと"
    Purpose.CANCEL -> "取り消す予約"
    Purpose.INQUIRY -> "聞きたいこと"
}

private fun notesHelp(p: Purpose) = when (p) {
    Purpose.NEW_BOOKING -> "お祝いなど"
    Purpose.CHANGE -> "もとの予約の日時など（新しい日時と人数は下に）"
    Purpose.CANCEL -> "日時、人数、予約番号など、お店が探せること"
    Purpose.INQUIRY -> "営業時間、空き、料金など"
}

fun purposeLabel(p: Purpose) = when (p) {
    Purpose.NEW_BOOKING -> "新しい予約"
    Purpose.CHANGE -> "予約の変更"
    Purpose.CANCEL -> "予約の取り消し"
    Purpose.INQUIRY -> "問い合わせだけ（予約はしない）"
}
