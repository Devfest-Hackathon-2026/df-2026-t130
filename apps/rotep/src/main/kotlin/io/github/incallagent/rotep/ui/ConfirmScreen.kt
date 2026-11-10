// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.incallagent.core.policy.Approval
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PolicySummary
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.rotep.R
import io.github.incallagent.rotep.app.Alerts
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.shell.adb.AdbSetup
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.ui.component.AitelHero
import io.github.incallagent.rotep.ui.component.HeroKind
import io.github.incallagent.rotep.ui.theme.LocalAitelColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

/** 確認と承認：the policy in words, the validator's findings, and "承認して電話する". */
@Composable
fun ConfirmScreen() {
    // A window drawn over this one cannot get "承認" pressed through it. On the window's root: touches
    // reach Compose's own view through there, and that view does not look at the flag itself
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        onDispose { root.filterTouchesWhenObscured = before }
    }
    val draft by RotepApp.draft.collectAsState()
    val settings by RotepApp.settings.collectAsState()
    val lookup by RotepApp.lookup.collectAsState()
    val incoming by RotepApp.incoming.collectAsState()
    val filledByChat by RotepApp.filledByChat.collectAsState()
    val form by RotepApp.form.collectAsState()
    // The helper is started from the PC, also while this screen is open
    val helper by HelperClient.state.collectAsState()
    val policy = draft ?: run {
        ConfirmContent(state = null, onFix = {}, onApprove = {})
        return
    }
    val problems = remember(policy) { PolicyValidator.validate(policy, Instant.now()) }
    // Between "承認" and the call screen (up to the service's answer), the buttons wait. Polling
    // callRunning() brings them back after any refusal, at once or later (no new call then).
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(starting) {
        while (starting) {
            delay(200)
            if (!RotepApp.callRunning()) starting = false
        }
    }
    fun approve() {
        starting = true
        approveAndCall(policy)
        if (!RotepApp.callRunning()) starting = false // refused at once; the message says why
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val missing = RotepApp.missingRequired()
        if (missing.isNotEmpty()) RotepApp.message.value = UserMessages.missingPermissions(missing) else approve()
    }
    // A launcher of its own. The time is only in memory: when the screen is made anew during the
    // dialog (the phone was turned) it is gone, and that must never become a call right now
    var scheduleAfterAsking by remember { mutableStateOf<LocalDateTime?>(null) }
    val schedulePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val at = scheduleAfterAsking
        scheduleAfterAsking = null
        val missing = RotepApp.missingRequired()
        when {
            missing.isNotEmpty() -> RotepApp.message.value = UserMessages.missingPermissions(missing)
            at != null -> approveAndSchedule(policy, at)
            else -> RotepApp.message.value = "時刻をもう一度選んでください"
        }
    }
    // Asks the system (emergency numbers, roles): not on every recomposition. What is missing is
    // often granted from the PC while this screen is open, so it is asked again now and then
    var recheck by remember { mutableStateOf(0) }
    val blocker = remember(policy, settings, incoming, starting, helper.phase, recheck) { currentBlocker(policy) }
    LaunchedEffect(blocker) {
        while (blocker != null) {
            delay(RECHECK_MS)
            // Off the main thread, and the screen is only made again for a new answer
            if (withContext(Dispatchers.Default) { currentBlocker(policy) } != blocker) recheck++
        }
    }
    val scheduled by RotepApp.scheduled.collectAsState()
    val preparing by RotepApp.preparingCall.collectAsState()
    val offer = remember(blocker, problems, lookup, scheduled, settings, incoming) {
        RotepApp.scheduleOffer(blocker, problems.isNotEmpty(), lookup.holdsApproval)
    }
    val context = LocalContext.current
    val notificationsOn = remember(policy, settings, starting) { Alerts.canNotify(context) }
    val state = ConfirmUiState(
        policy = policy,
        notificationsOff = !notificationsOn,
        problems = problems,
        blocker = blocker,
        stage = RotepApp.stage(),
        micNote = settings.micNote,
        keepsHistory = settings.keepBookingHistory,
        dualBrain = settings.dualBrain,
        starting = starting,
        lookup = lookup,
        otherAiNote = incoming?.takeIf { it.opened }?.let { otherAiNote(kindLabel(policy.target.kind), it.numberSource, it.assistant) }
            ?: chatNote(kindLabel(policy.target.kind), RotepApp.speakable(form.readings, policy), policy.target.phoneNumber == PracticePolicy.PHONE_NUMBER).takeIf { filledByChat }
            ?: readingsNote(RotepApp.speakable(form.readings, policy)),
        schedule = offer,
        debuggingForCall = AdbSetup.onDemandEnabled && RotepApp.stage() != RotepApp.Stage.L0,
        preparing = preparing,
        missingKey = if (blocker != null) missingKey(settings) else null,
        lateAtNight = lateAtNight(Instant.now()),
    )

    ConfirmContent(
        state = state,
        onFix = RotepApp::fixRequest,
        onCancelPreparing = RotepApp::cancelPreparing,
        onOpenSettings = { RotepApp.show(RotepApp.Screen.SETTINGS) },
        onApprove = {
            val ask = RotepApp.permissionsToAsk()
            if (ask.isEmpty()) approve() else permissions.launch(ask.toTypedArray())
        },
        onLookUp = RotepApp::lookUpShop,
        onLookupReviewed = RotepApp::lookupReviewed,
        onLookupSkip = RotepApp::skipLookup,
        onSchedule = { at ->
            val ask = RotepApp.permissionsToAsk()
            if (ask.isEmpty()) {
                approveAndSchedule(policy, at)
            } else {
                scheduleAfterAsking = at
                schedulePermissions.launch(ask.toTypedArray())
            }
        },
    )
}

/**
 * What the AI will be allowed to do and what the check found (scrolls), with the buttons fixed at the
 * bottom and the reason they cannot be pressed right above them. [state] null: no request yet.
 */
@Composable
fun ConfirmContent(
    state: ConfirmUiState?,
    onFix: () -> Unit,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    onLookUp: () -> Unit = {},
    onLookupReviewed: () -> Unit = {},
    onLookupSkip: () -> Unit = {},
    onSchedule: (LocalDateTime) -> Unit = {},
    onCancelPreparing: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    if (state == null) {
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenName("確認と承認")
            Text("依頼がありません")
        }
        return
    }

    val summary = splitSummary(summaryItems(PolicySummary.render(state.policy)))
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val hero = confirmHero(state)
                AitelHero(HeroKind.CONFIRM, "確認と承認", hero.first, hero.second, hero.mood)
            }
            state.otherAiNote?.let { note ->
                item { Notice(note, icon = R.drawable.ic_warning) }
            }
            // Before the long summary, so they are read before the buttons. Not red: they do not stop the call
            for (w in confirmWarnings(state.policy, state.practice, state.notificationsOff, state.lateAtNight)) {
                item { Notice(w, icon = R.drawable.ic_warning) }
            }
            // A request that cannot go ahead is fixed first: nothing is sent for it
            if (state.lookup != LookupUi.None && (state.problems.isEmpty() || state.lookup != LookupUi.Available)) {
                item { LookupSection(state.lookup, onLookUp, onLookupReviewed, onLookupSkip, onFix) }
            }
            item {
                // Whom and when first, large; everything else that is approved follows, nothing folded away
                KeyFacts("AI に任せる内容（承認すると、この範囲だけで話します）") {
                    for (item in summary.key) KeyFact(item.label, item.lines, LocalAitelColors.current.heroConfirm, big = !item.label.startsWith("日時"))
                }
            }
            if (summary.rest.isNotEmpty()) {
                item {
                    Section("そのほかの範囲") {
                        // One heading per item, so screen readers can move item by item
                        for (item in summary.rest) {
                            if (item.label.isNotEmpty()) {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                                )
                            }
                            for (line in item.lines) Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Section("内容の確認") {
                    if (state.problems.isEmpty()) Text("問題はありません", style = MaterialTheme.typography.bodyMedium)
                    for (p in state.problems) ErrorText("・${p.message}")
                }
            }
            item {
                Section("電話のしかた：${state.stage.label}") {
                    state.micNote?.let { Small(it) }
                    stageNote(state.stage)?.let { Small(it) }
                    if (state.debuggingForCall) Small("電話の間だけ USB デバッグをオンにします。電話のあとで元に戻します")
                }
            }
            state.schedule?.let { offer ->
                item { ScheduleSection(offer, state.shopName, enabled = !state.starting, needsWifi = state.debuggingForCall, onApprove = onSchedule) }
            }
            item {
                Notice(retentionNotice(state.keepsHistory, state.practice, state.dualBrain))
            }
        }
        // The line marks where the scrolling part ends
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // In view when the user approves, however long the list above is
            Small(dataSentLine(state.practice, state.dualBrain))
            state.disabledReason?.let {
                // Waiting is not a fault. After the phone was turned only "preparing" is left of the two
                if (state.starting || state.preparing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WaitingMark()
                        Small(it)
                    }
                } else {
                    ErrorText(it)
                }
            }
            state.missingKey?.let { key ->
                missingKeyNote(key)?.let { Small(it) }
                MainOutlinedButton(onClick = onOpenSettings, Modifier.fillMaxWidth(), compact = isShortScreen()) { Text("設定を開く") }
            }
            // Evenly tall: the longer label may take two lines at large text
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val short = isShortScreen()
                if (state.preparing) {
                    MainOutlinedButton(
                        onClick = onCancelPreparing,
                        modifier = Modifier.fillMaxHeight().semantics { contentDescription = "やめる。電話の準備を止めます" },
                        compact = short,
                    ) { Text("やめる") }
                } else {
                    MainOutlinedButton(onClick = onFix, Modifier.fillMaxHeight(), enabled = !state.starting, compact = short) { Text(stringResource(R.string.fix_request)) }
                }
                val description = approveDescription(state)
                MainButton(
                    onClick = onApprove,
                    enabled = state.canApprove,
                    compact = short,
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description },
                ) {
                    Text(stringResource(if (state.practice) R.string.approve_practice else R.string.approve_and_call))
                }
            }
        }
    }
}

private const val RECHECK_MS = 2_000L

private fun currentBlocker(policy: DelegationPolicy): String? =
    RotepApp.readiness() ?: RotepApp.requestProblem() ?: RotepApp.dialProblem(policy)

private fun approveAndCall(policy: DelegationPolicy) {
    RotepApp.startCall(policy.copy(approval = Approval(approvedAt = Instant.now())))
}

private fun approveAndSchedule(policy: DelegationPolicy, at: LocalDateTime) {
    RotepApp.scheduleCall(policy.copy(approval = Approval(approvedAt = Instant.now())), at)
}
