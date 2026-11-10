// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.calendar.CalendarUi
import io.github.incallagent.rotep.callback.CALLBACK_ROLE_REFUSED
import io.github.incallagent.rotep.intake.ChatSearch
import io.github.incallagent.rotep.intake.IntakeAsk
import io.github.incallagent.rotep.intake.IntakeLine
import io.github.incallagent.rotep.intake.IntakeLine.Who
import io.github.incallagent.rotep.intake.IntakeQuestion
import io.github.incallagent.rotep.intake.IntakeUi
import io.github.incallagent.rotep.research.ShopFindings
import io.github.incallagent.rotep.ui.CalendarSetting
import io.github.incallagent.rotep.ui.CallbackSetting
import io.github.incallagent.rotep.ui.CallContent
import io.github.incallagent.rotep.ui.ConfirmContent
import io.github.incallagent.rotep.ui.DebuggingSetting
import io.github.incallagent.rotep.ui.IntakeContent
import io.github.incallagent.rotep.ui.RequestContent
import io.github.incallagent.rotep.ui.ResultContent
import io.github.incallagent.rotep.ui.followUps
import io.github.incallagent.rotep.ui.RotepFrame
import io.github.incallagent.rotep.ui.ScheduleChooser
import io.github.incallagent.rotep.ui.ScheduledCallBanner
import io.github.incallagent.rotep.ui.SettingsContent
import io.github.incallagent.rotep.ui.preview.PreviewFixtures as F

/**
 * One made-up screen as the user sees it: the frame (title, practice band, message line) around one
 * screen's content. Taps do nothing. Rendered by the previews (`android preview render`).
 */
class Sample(val id: String, val title: String, val content: @Composable (Modifier) -> Unit) {
    override fun toString() = id
}

/** Every sample, by screen, in the order of the rendered files (`<Screen>Preview_<n>.png`). */
object Samples {
    val intake: List<Sample> = listOf(
        Sample("intake_empty", "会話：始める前") { m -> Intake(m, IntakeUi()) },
        Sample("intake_thinking", "会話：考えている途中") { m -> Intake(m, IntakeUi(lines = TALK, waiting = true)) },
        Sample("intake_question", "会話：質問のカード") { m ->
            Intake(m, IntakeUi(lines = TALK, question = IntakeQuestion(IntakeAsk.OUTSIDE_WINDOWS, "19時が取れないときは、18:30〜20:00 の別の時間で決めてよいですか？", null)))
        },
        Sample("intake_searched", "会話：お店を調べたカード") { m ->
            Intake(m, IntakeUi(lines = TALK + IntakeLine(Who.SEARCH, "", searchIndex = 0), searches = listOf(SEARCH), typing = "予約名はヤマダで"))
        },
        Sample("intake_ready", "会話：そろって「確認へ」") { m -> Intake(m, READY) },
        Sample("intake_details", "会話：いまの内容") { m -> Intake(m, READY, detailsOpen = true) },
        Sample("intake_no_key", "会話：キーなし") { m -> Intake(m, IntakeUi(), hasKey = false) },
        Sample("intake_scheduled", "会話：時間を指定した電話の帯") { m ->
            Intake(m, IntakeUi(), banner = { ScheduledCallBanner(F.scheduledInfo, {}) })
        },
        Sample("intake_question_long", "会話：複数行の質問のカード") { m ->
            Intake(m, IntakeUi(lines = TALK, question = IntakeQuestion(IntakeAsk.MAY_TELL, LONG_QUESTION, null)))
        },
    )

    val request: List<Sample> = listOf(
        Sample("request_empty", "依頼：空（L0）") { m ->
            Frame(Screen.REQUEST, m) { RequestContent(F.emptyForm, emptyList(), {}, {}, {}, {}, practice = true) }
        },
        Sample("request_practice", "依頼：練習用の例（L0）") { m ->
            Frame(Screen.REQUEST, m) { RequestContent(F.practiceForm, emptyList(), {}, {}, {}, {}, practice = true) }
        },
        Sample("request_errors", "依頼：入力の誤り（L2）") { m -> Frame(Screen.REQUEST, m) { RequestContent(F.badForm, F.badFormErrors, {}, {}, {}, {}) } },
        // The form opened at a section further down (item 5: 人数と時間, item 8: 任せる範囲; item 0 is the hero)
        Sample("request_party", "依頼：人数（−／＋）") { m ->
            Frame(Screen.REQUEST, m) {
                RequestContent(
                    F.practiceForm, emptyList(), {}, {}, {}, {},
                    practice = true,
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 5),
                )
            }
        },
        Sample("request_authority", "依頼：任せる範囲") { m ->
            Frame(Screen.REQUEST, m) {
                RequestContent(
                    F.practiceForm, emptyList(), {}, {}, {}, {},
                    practice = true,
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 8),
                )
            }
        },
        Sample("request_last_call", "依頼：前回の電話の結果が残っている") { m ->
            Frame(Screen.REQUEST, m) { RequestContent(F.emptyForm, emptyList(), {}, {}, {}, {}, lastCall = F.lastCall) }
        },
    )

    val confirm: List<Sample> = listOf(
        Sample("confirm_l0", "確認：L0（通話なし）") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmL0, {}, {}) } },
        Sample("confirm_l2", "確認：L2（電話をかける）") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmL2, {}, {}) } },
        Sample("confirm_l2_practice", "確認：L2 で練習の番号（かけられない）") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmL2Practice, {}, {}) } },
        Sample("confirm_problems", "確認：検査で問題あり") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmProblems, {}, {}) } },
        Sample("confirm_no_key", "確認：API キーがない") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmNoKey, {}, {}) } },
        Sample("confirm_long_name", "確認：長い店名") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmLongName, {}, {}) } },
        Sample("confirm_message", "確認：上に知らせ（許可がない）") { m ->
            Frame(Screen.CONFIRM, m, F.PERMISSION_MESSAGE) { ConfirmContent(F.confirmL0, {}, {}) }
        },
        Sample("confirm_starting", "確認：承認のあと（始めている）") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmStarting, {}, {}) } },
        Sample("confirm_schedule_open", "確認：時間を指定できる（L3）") { m ->
            Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmScheduleOpen, {}, {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 6)) }
        },
        Sample("confirm_schedule_blocked", "確認：時間を指定できない理由") { m ->
            Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmScheduleBlocked, {}, {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 6)) }
        },
        Sample("confirm_preparing", "確認：承認のあと（電話の準備をしている）") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmPreparing, {}, {}) } },
        // Opened at the sections that say what the debugging switches do
        Sample("confirm_debugging", "確認：電話の間だけ USB デバッグをオンにする") { m ->
            Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmDebugging, {}, {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)) }
        },
        Sample("confirm_notifications_off", "確認：通知が許可されていない") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmNotificationsOff, {}, {}) } },
        Sample("confirm_schedule_chooser", "確認：日時を選ぶ") { m ->
            Frame(Screen.CONFIRM, m) {
                Column(Modifier.padding(16.dp)) {
                    ScheduleChooser(F.confirmScheduleOpen.shopName, F.today, F.today.plusDays(1), "22:00", F.scheduleTimeProblem, {}, {})
                }
            }
        },
        Sample("confirm_dual_off", "確認：通話の考える役がオフ") { m -> Frame(Screen.CONFIRM, m) { ConfirmContent(F.confirmDualOff, {}, {}) } },
    )

    val call: List<Sample> = listOf(
        Sample("call_l0_talking", "通話中：L0 の会話中") { m -> Frame(Screen.CALL, m) { CallContent(F.callL0Talking, {}, {}, {}, {}) } },
        Sample("call_l2_ringing", "通話中：L2 の呼び出し中") { m -> Frame(Screen.CALL, m) { CallContent(F.callL2Ringing, {}, {}, {}, {}) } },
        Sample("call_l2_talking", "通話中：L2 の会話中") { m -> Frame(Screen.CALL, m) { CallContent(F.callL2Talking, {}, {}, {}, {}) } },
        Sample("call_l3_talking", "通話中：L3 の会話中（操作の列）") { m -> Frame(Screen.CALL, m) { CallContent(F.callL3Talking, {}, {}, {}, {}) } },
        Sample("call_l2_timeout", "通話中：L2 で時間切れ（本人に代わった）") { m -> Frame(Screen.CALL, m) { CallContent(F.callL2Timeout, {}, {}, {}, {}) } },
        Sample("call_l0_lost", "通話中：L0 で AI との接続が切れた") { m -> Frame(Screen.CALL, m) { CallContent(F.callL0Lost, {}, {}, {}, {}) } },
        Sample("call_ended", "通話中：終話（結果あり）") { m -> Frame(Screen.CALL, m) { CallContent(F.callEnded, {}, {}, {}, {}) } },
        Sample("call_l3_keypad", "通話中：キーパッドを開いた") { m ->
            Frame(Screen.CALL, m) { CallContent(F.callL3Talking, {}, {}, {}, {}, keypadInitiallyOpen = true) }
        },
    )

    val result: List<Sample> = listOf(
        Sample("result_booked", "結果：予約できた（L0）") { m -> Frame(Screen.RESULT, m, verifiedResult = true) { ResultContent(F.resultBooked, {}, shopName = F.policy.target.businessName) } },
        Sample("result_booked_outside", "結果：予約の返事はあったが、照合で問題") { m -> Frame(Screen.RESULT, m) { ResultContent(F.resultBookedOutside, {}) } },
        Sample("result_needs_decision", "結果：折り返しが必要") { m -> Frame(Screen.RESULT, m) { ResultContent(F.resultNeedsDecision, {}) } },
        Sample("result_not_reached", "結果：つながらなかった") { m -> Frame(Screen.RESULT, m) { ResultContent(F.resultNotReached, {}) } },
        Sample("result_failed", "結果：AI との接続が切れた") { m -> Frame(Screen.RESULT, m) { ResultContent(F.resultFailed, {}) } },
        Sample("result_not_dialed", "結果：電話をかけていない") { m -> Frame(Screen.RESULT, m) { ResultContent(F.resultNotDialed, {}) } },
        Sample("result_none", "結果：まだない") { m -> Frame(Screen.RESULT, m) { ResultContent(null, {}) } },
        Sample("result_kept", "結果：前回の電話（残してあるもの）") { m ->
            Frame(Screen.RESULT, m, verifiedResult = true) { ResultContent(F.resultBooked, {}, lastCall = F.lastCall, followUps = followUps(F.resultBooked), listState = rememberLazyListState(initialFirstVisibleItemIndex = 6)) }
        },
        Sample("result_booked_follow_up", "結果：変更と取り消しのボタン") { m ->
            Frame(Screen.RESULT, m, verifiedResult = true) { ResultContent(F.resultBooked, {}, followUps = followUps(F.resultBooked), listState = rememberLazyListState(initialFirstVisibleItemIndex = 5)) }
        },
        Sample("result_not_reached_follow_up", "結果：続きのボタン") { m ->
            Frame(Screen.RESULT, m) { ResultContent(F.resultNotReached, {}, followUps = followUps(F.resultNotReached), listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)) }
        },
        // Opened at the booking: the calendar's card comes right under it
        Sample("result_calendar_can_add", "結果：カレンダーに追加できる") { m -> ResultCalendar(m, CalendarUi.CanAdd()) },
        Sample("result_calendar_adding", "結果：カレンダーに追加している") { m -> ResultCalendar(m, CalendarUi.Adding) },
        Sample("result_calendar_added", "結果：カレンダーに追加した") { m -> ResultCalendar(m, CalendarUi.Added()) },
    )

    val settings: List<Sample> = listOf(
        Sample("settings_no_key", "設定：キーなし") { m -> Frame(Screen.SETTINGS, m) { SettingsContent(F.settingsNoKey, "", {}, {}, {}, {}, {}, {}) } },
        Sample("settings_shell_not_yet", "設定：キー保存済み・手伝い役を選んだ（まだない）") { m ->
            Frame(Screen.SETTINGS, m) { SettingsContent(F.settingsShellNotYet, "", {}, {}, {}, {}, {}, {}) }
        },
        Sample("settings_switches", "設定：スイッチの並び") { m ->
            Frame(Screen.SETTINGS, m) {
                SettingsContent(
                    F.settingsSwitches, "", {}, {}, {}, {}, {}, {},
                    calendar = CalendarSetting(account = "example@example.com"),
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 5),
                )
            }
        },
        // Opened at the sections further down (item 7: お店からの折り返し; item 10: ほかの AI からの依頼)
        Sample("settings_callback", "設定：お店からの折り返し（オン、問題あり）") { m ->
            Frame(Screen.SETTINGS, m) {
                SettingsContent(
                    F.settingsCallback, "", {}, {}, {}, {}, {}, {},
                    callback = CallbackSetting(problem = CALLBACK_ROLE_REFUSED),
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 7),
                )
            }
        },
        Sample("settings_relay", "設定：ほかの AI からの依頼（サーバー）") { m ->
            Frame(Screen.SETTINGS, m) {
                SettingsContent(F.settingsNoKey, "", {}, {}, {}, {}, {}, {}, listState = rememberLazyListState(initialFirstVisibleItemIndex = 10))
            }
        },
        // Opened at the section of the debugging switches (item 11)
        Sample("settings_debugging_blocked", "設定：開発者向けオプション（まだオンにできない）") { m ->
            Frame(Screen.SETTINGS, m) {
                SettingsContent(
                    F.settingsNoKey, "", {}, {}, {}, {}, {}, {},
                    debugging = DebuggingSetting(setupProblem = F.DEBUGGING_SETUP_PROBLEM),
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = 11),
                )
            }
        },
        Sample("settings_debugging_on", "設定：開発者向けオプション（オン）") { m ->
            Frame(Screen.SETTINGS, m) {
                SettingsContent(F.settingsNoKey, "", {}, {}, {}, {}, {}, {}, debugging = DebuggingSetting(on = true), listState = rememberLazyListState(initialFirstVisibleItemIndex = 11))
            }
        },
    )

    val all: List<Sample> = intake + request + confirm + call + result + settings
}

private val TALK = listOf(
    IntakeLine(Who.USER, "レストラン・サンプルに、土曜の19時から2人で予約したい"),
    IntakeLine(Who.AI, "承知しました。11月14日（土）の19時から、2名ですね。お店の電話番号は分かりますか？"),
    IntakeLine(Who.USER, "03-0000-0000"),
    IntakeLine(Who.AI, "ありがとうございます。予約名を教えてください。"),
)

private val SEARCH = ChatSearch(
    "レストラン・サンプル",
    listOf("11月14日(土) 19:00"),
    ShopFindings(
        "（見本の文です）レストラン・サンプルの営業時間は 17:00〜23:00 で、土曜も営業しています。予約は電話で受けています。",
        listOf(ShopFindings.Source("レストラン・サンプル 公式サイト（見本）", "https://example.com/"), ShopFindings.Source("お店の案内（見本）", "https://example.org/")),
        emptyList(),
    ),
)

private const val LONG_QUESTION =
    "お店に聞かれたら、次のことを伝えてもよいですか？\n・アレルギーがあること（甲殻類）\n・誕生日のお祝いで使うこと\n・ベビーカーで行くこと\n" +
        "伝えてよいと答えると、AI はお店に聞かれたときだけ話します。"

private val READY = IntakeUi(lines = TALK, form = F.practiceForm, accepted = true)

@Composable
private fun ResultCalendar(modifier: Modifier, calendar: CalendarUi) {
    Frame(Screen.RESULT, modifier, verifiedResult = true) {
        ResultContent(
            F.resultBooked,
            {},
            shopName = F.confirmL0.shopName,
            calendar = calendar,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3),
        )
    }
}

@Composable
private fun Intake(modifier: Modifier, ui: IntakeUi, hasKey: Boolean = true, detailsOpen: Boolean = false, banner: (@Composable () -> Unit)? = null) {
    Frame(Screen.INTAKE, modifier, banner = banner) {
        IntakeContent(
            ui = ui,
            hasKey = hasKey,
            practice = true,
            onType = {},
            onSay = { true },
            onAnswer = {},
            onRetry = {},
            onStop = {},
            onByHand = {},
            onToConfirm = {},
            onRestart = {},
            onOpenSettings = {},
            detailsOpen = detailsOpen,
        )
    }
}

@Composable
private fun Frame(
    screen: Screen,
    modifier: Modifier,
    message: String? = null,
    verifiedResult: Boolean = false,
    banner: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    RotepFrame(
        screen = screen,
        message = message,
        onOpenSettings = {},
        onDismissMessage = {},
        modifier = modifier,
        verifiedResult = verifiedResult,
        banner = banner,
        content = content,
    )
}
