// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.incallagent.rotep.app.CallChoice
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.calendar.CALENDAR_PERMISSIONS
import io.github.incallagent.rotep.calendar.CalendarCheck
import io.github.incallagent.rotep.calendar.calendarCheckProblem
import io.github.incallagent.rotep.call.ShellAudioPaths
import io.github.incallagent.rotep.call.TelecomCallControl
import io.github.incallagent.rotep.callback.CALLBACK_NOT_ON_THIS_PHONE
import io.github.incallagent.rotep.callback.CALLBACK_ROLE_REFUSED
import io.github.incallagent.rotep.callback.CallbackFeature
import io.github.incallagent.rotep.callback.CallbackNotices
import io.github.incallagent.rotep.callback.CallbackRole
import io.github.incallagent.rotep.callback.CallbackSwitchStep
import io.github.incallagent.rotep.callback.callbackProblem
import io.github.incallagent.rotep.callback.callbackSwitchStep
import io.github.incallagent.rotep.request.RelaySettingsSection
import io.github.incallagent.rotep.ui.about.AboutActivity
import io.github.incallagent.rotep.ui.history.BookingHistorySection
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.adb.AdbFeatures
import io.github.incallagent.rotep.shell.adb.AdbSetup

/** 設定：API keys, the way of calling, the call's sound, the practice, and what to do when something is wrong. */
@Composable
fun SettingsScreen(activity: Activity) {
    val s by RotepApp.settings.collectAsState()
    // The helper is started from the PC, also while this screen is open
    val helper by HelperClient.state.collectAsState()
    var key by remember { mutableStateOf("") }
    var claudeDraft by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var calendarChecking by remember { mutableStateOf(false) }
    var calendarAccount by remember { mutableStateOf<String?>(null) }
    var calendarProblem by remember { mutableStateOf<String?>(null) }
    fun checkCalendar(turnOn: Boolean) {
        calendarChecking = true
        RotepApp.calendar.check { found ->
            calendarChecking = false
            calendarProblem = calendarCheckProblem(found)
            calendarAccount = (found as? CalendarCheck.Found)?.account
            if (turnOn && found is CalendarCheck.Found) RotepApp.updateSettings { it.copy(addToCalendar = true) }
        }
    }
    // Whatever was answered: the check reads the permissions again, and says what is missing
    val calendarPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkCalendar(turnOn = true) }
    // The account may be gone, or the permission taken back, since the switch was turned on
    LaunchedEffect(s.addToCalendar) { if (s.addToCalendar) checkCalendar(turnOn = false) }

    // The automatic-call permission is granted on the diagnostics screen, another Activity:
    // read it again whenever this screen comes back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    var callbackRefused by remember { mutableStateOf<String?>(null) }
    // The phone's own dialog. Whatever it answered, what counts is whether the app now has the role
    val callbackRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (CallbackRole.held(activity)) RotepApp.updateSettings { it.copy(notifyCallback = true) } else callbackRefused = CALLBACK_ROLE_REFUSED
    }
    // The role and the notifications can be taken away in the phone's settings: read again when the screen comes back
    val callbackNow = remember(s.notifyCallback, resumed) {
        callbackProblem(s.notifyCallback, CallbackRole.held(activity), CallbackNotices.canNotify(activity))
    }
    val adb by AdbSetup.state.collectAsState()
    val onDemandSetup = remember(helper.phase, resumed, adb.onDemand) { if (AdbFeatures.ON_DEMAND) AdbSetup.onDemandSetupProblem() else null }
    SettingsContent(
        state = SettingsUiState(
            settings = s,
            shellAvailable = remember(helper.phase, adb.onDemand) { adb.onDemand || ShellAudioPaths.factory != null },
            diagnosticsMessage = diagnostics,
            // Read again whenever the choice changes and when the screen comes back (see above)
            autoProblem = remember(s.control, resumed, adb.onDemand) { TelecomCallControl.readinessProblem(activity, appOpAtCallTime = adb.onDemand) },
        ),
        keyDraft = key,
        onKeyDraftChange = { key = it },
        onSaveKey = {
            RotepApp.saveApiKey(key)
            key = ""
        },
        onClearKey = { RotepApp.clearApiKey() },
        onUpdate = { change -> RotepApp.updateSettings(change) },
        onOpenDiagnostics = { diagnostics = RotepApp.openDiagnostics(activity) },
        onOpenAbout = { activity.startActivity(Intent(activity, AboutActivity::class.java)) },
        onBack = { RotepApp.closeSettings() },
        claudeKey = KeyDraft(
            text = claudeDraft,
            onChange = { claudeDraft = it },
            onSave = {
                RotepApp.saveClaudeKey(claudeDraft)
                claudeDraft = ""
            },
            onClear = { RotepApp.clearClaudeKey() },
        ),
        calendar = CalendarSetting(
            checking = calendarChecking,
            account = calendarAccount,
            problem = calendarProblem,
            onChange = { on ->
                calendarProblem = null
                when {
                    !on -> RotepApp.updateSettings { it.copy(addToCalendar = false) }
                    RotepApp.calendar.hasPermission() -> checkCalendar(turnOn = true)
                    else -> calendarPermissions.launch(CALENDAR_PERMISSIONS)
                }
            },
        ),
        debugging = DebuggingSetting(adb.onDemand, onDemandSetup, AdbSetup::setOnDemand),
        callback = CallbackSetting(
            problem = callbackRefused ?: callbackNow,
            onChange = { on ->
                callbackRefused = null
                when (callbackSwitchStep(on, CallbackRole.available(activity), CallbackRole.held(activity))) {
                    CallbackSwitchStep.TURN_OFF -> RotepApp.updateSettings { it.copy(notifyCallback = false) }
                    CallbackSwitchStep.TURN_ON -> RotepApp.updateSettings { it.copy(notifyCallback = true) }
                    CallbackSwitchStep.NOT_ON_THIS_PHONE -> callbackRefused = CALLBACK_NOT_ON_THIS_PHONE
                    CallbackSwitchStep.ASK_FOR_ROLE -> {
                        val ask = CallbackRole.request(activity)
                        if (ask != null) callbackRole.launch(ask) else callbackRefused = CALLBACK_NOT_ON_THIS_PHONE
                    }
                }
            },
        ),
    )
}

/** The calendar switch on the settings screen. [account]: where the events go. */
class CalendarSetting(
    val checking: Boolean = false,
    val account: String? = null,
    val problem: String? = null,
    val onChange: (Boolean) -> Unit = {},
)

/** 「開発者向けオプションを普段は切っておく」. [setupProblem]: what has to be done before it can be turned on. */
class DebuggingSetting(
    val on: Boolean = false,
    val setupProblem: String? = null,
    val onChange: (Boolean) -> Unit = {},
)

/** The store-callback switch on the settings screen. [problem]: why it stayed off, or why it cannot do its work. */
class CallbackSetting(
    val problem: String? = null,
    val onChange: (Boolean) -> Unit = {},
)

/** The field of an API key on the settings screen. */
class KeyDraft(
    val text: String = "",
    val onChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/**
 * The settings. [keyDraft] is the API key being pasted (never shown again once saved);
 * [onUpdate] changes the other settings.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    keyDraft: String,
    onKeyDraftChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    claudeKey: KeyDraft = KeyDraft(),
    calendar: CalendarSetting = CalendarSetting(),
    callback: CallbackSetting = CallbackSetting(),
    debugging: DebuggingSetting = DebuggingSetting(),
    onOpenAbout: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val s = state.settings

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenName("設定") }
        item {
            KeySection("Gemini API キー（有料枠）", s.hasApiKey, KeyDraft(keyDraft, onKeyDraftChange, onSaveKey, onClearKey)) {
                Small("キーはこの端末のアプリの中だけに保管します")
                Small("お店の人の声も Gemini に送られるので、有料枠のキーを使ってください。無料枠では、送った内容が Google の製品の改善に使われ、人が読むこともあります。")
            }
        }
        item {
            KeySection("Claude API キー", s.hasClaudeKey, claudeKey) {
                Small("会話で依頼を作るときと、通話の考える役に使います。キーはこの端末のアプリの中だけに保管します")
                Small("会話に書いたことは、電話番号も含めて Anthropic（Claude）に送られます。店名、希望の日時、人数は、お店を調べるウェブ検索にも使います。会話はこのアプリに残しません（規約に沿って一定期間記録されます）。")
            }
        }
        item {
            Section("通話の考える役") {
                SwitchRow("通話の考える役に Claude を使う", s.dualBrain, supporting = "切ると、今までどおり Gemini だけで電話します") { v ->
                    onUpdate { it.copy(dualBrain = v) }
                }
                Small("オンのときは、声は Gemini、次に言うことを決めるのは Claude です。Claude の API キーが必要です。相手の言葉の文字起こしと依頼の内容（予約名、連絡先の番号など）も Anthropic（Claude）に送られます。")
            }
        }
        item {
            Section("電話のしかた") {
                RadioRows(CallChoice.entries, s.callChoice, { it.label }) { v -> onUpdate { it.with(v) } }
                if (s.audio.shell != null && !state.shellAvailable) ErrorText(UserMessages.HELPER_NOT_RUNNING)
                if (s.callChoice == CallChoice.AUTO_CALL) state.autoProblem?.let { ErrorText(it) }
            }
        }
        item {
            Section("通話の音（電話をかけるとき）") {
                SwitchRow("通話の音をこの端末で鳴らす", s.playCallAudio, supporting = "お店と AI の会話が聞こえます") { v ->
                    onUpdate { it.copy(playCallAudio = v) }
                }
                Small(
                    "切っているときは、AI が話している間、この端末から通話の音を出しません。" +
                        "AI が止まったときと、通話が終わったときは戻ります。変えると、次の電話から効きます。" +
                        "消している間は、アラームや通知の音も小さくなることがあります。"
                )
            }
        }
        item {
            Section("AI の話し方") {
                SwitchRow("ゆっくり話す", s.slowSpeech) { v -> onUpdate { it.copy(slowSpeech = v) } }
            }
        }
        item {
            Section("カレンダー") {
                SwitchRow("予約が取れたら Google カレンダーに追加する", s.addToCalendar, enabled = !calendar.checking, onChange = calendar.onChange)
                calendar.problem?.let { ErrorText(it) }
                if (s.addToCalendar) calendar.account?.let { Small("追加先：$it") }
                Small("電話をかけて予約が取れたときだけ、この端末のメインのカレンダーに予定を入れます。内容が合わない結果は入れません。")
                Small("予定には店名、日時、人数、予約名、お店の電話番号が入ります。会話の文は入れません。")
            }
        }
        if (CallbackFeature.ENABLED) {
            item {
                Section("お店からの折り返し") {
                    SwitchRow("お店からの折り返しを知らせる", s.notifyCallback, onChange = callback.onChange)
                    callback.problem?.let { ErrorText(it) }
                    Small("AI が前に電話したお店から着信があると、どのお店の件かを通知で知らせます。着信はいつもどおりで、止めたり変えたりしません。通知にはお店の名前が出るので、ロック中の画面には出しません。")
                    Small("オンにするとき、このアプリを端末の「発信者番号と迷惑電話」のアプリに選ぶ確認が出ます。端末で 1 つしか選べないので、ほかのアプリを選んでいると、そちらは外れます。オフにしても選ばれたままですが、何もしません。外すときは端末の設定から外してください。")
                    Small("電話の記録に残っている電話だけが対象です。連絡先に入っている番号と、番号を通知しない着信は知らせられません。")
                    if (s.notifyCallback && !s.keepBookingHistory) Small("「電話の記録」がオフなので、これからの電話は対象になりません。")
                }
            }
        }
        if (s.callChoice == CallChoice.PRACTICE) item {
            Section("練習のとき") {
                SwitchRow("スピーカーで鳴らす", s.loudspeaker, supporting = "切ると受話口やイヤホンで鳴ります") { v -> onUpdate { it.copy(loudspeaker = v) } }
                // With the thinking model the microphone is stopped anyway (CallRules): the switch would do nothing
                if (!s.dualBrain) {
                    SwitchRow("AI が話している間はマイクを止める", s.halfDuplex, supporting = "AI が自分の声で止まるときに。割り込みはできなくなります") { v ->
                        onUpdate { it.copy(halfDuplex = v) }
                    }
                }
            }
        }
        item { BookingHistorySection(s.keepBookingHistory) { v -> onUpdate { it.copy(keepBookingHistory = v) } } }
        item { RelaySettingsSection() }
        if (AdbFeatures.ON_DEMAND) {
            item {
                Section("開発者向けオプション") {
                    SwitchRow("開発者向けオプションを普段は切っておく", debugging.on, enabled = debugging.on || debugging.setupProblem == null, onChange = debugging.onChange)
                    if (!debugging.on) debugging.setupProblem?.let { Small(it) }
                    Small("USB デバッグがオンだと起動しない銀行のアプリのための設定です。オンにすると、電話の前にアプリが USB デバッグなどをオンにして手伝い役を起動し、電話のあとで元に戻します。電話の間は、銀行のアプリが起動しないことがあります。")
                    Small("最初のペアリングと手伝い役の起動は、開発者向けオプションをオンにして行います。済んだら、端末の設定で切ってください。電話のときは、「常に許可」にした Wi-Fi につながっている必要があります。")
                }
            }
        }
        item {
            Section("困ったとき") {
                SwitchRow("端末のマイクを止めない", s.keepMicOn, supporting = "AI の声が相手に届かないときだけ、オンにしてください") { v ->
                    onUpdate { it.copy(keepMicOn = v) }
                }
                s.micNote?.let { Small(it) }
                OutlinedButton(onClick = onOpenDiagnostics) { Text("診断の画面を開く") }
                state.diagnosticsMessage?.let { Small(it) }
            }
        }
        item {
            MainOutlinedButton(onClick = onOpenAbout, Modifier.fillMaxWidth(), compact = true) { Text("このアプリについて") }
        }
        item {
            MainButton(onClick = onBack, Modifier.fillMaxWidth().padding(bottom = 24.dp), compact = isShortScreen()) { Text("戻る") }
        }
    }
}

/** An API key: pasted once, then never shown again. */
@Composable
private fun KeySection(title: String, saved: Boolean, key: KeyDraft, notes: @Composable () -> Unit) {
    Section(title) {
        if (saved) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保存済み（この端末のアプリの中だけ）", Modifier.weight(1f))
                TextButton(onClick = key.onClear) { Text("消す") }
            }
        } else {
            OutlinedTextField(
                value = key.text,
                onValueChange = key.onChange,
                label = { Text("キーを貼り付け") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MainButton(onClick = key.onSave, enabled = key.text.isNotBlank(), compact = true) { Text("保存") }
        }
        notes()
    }
}
