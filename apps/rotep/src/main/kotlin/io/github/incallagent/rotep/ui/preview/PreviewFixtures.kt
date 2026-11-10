// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui.preview

import android.Manifest
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.core.policy.TimeWindow
import io.github.incallagent.rotep.app.AudioChoice
import io.github.incallagent.rotep.app.RotepApp.Stage
import io.github.incallagent.rotep.app.SavedResult
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.CallPanel
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSession.EndReason
import io.github.incallagent.rotep.call.CallSession.Line
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.call.CallSession.Snapshot
import io.github.incallagent.rotep.call.CallSession.Who
import io.github.incallagent.rotep.call.DialCheck
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.schedule.ScheduleOffer
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.schedule.ScheduledCalls
import io.github.incallagent.rotep.ui.CallUiState
import io.github.incallagent.rotep.ui.ConfirmUiState
import io.github.incallagent.rotep.ui.RequestForm
import io.github.incallagent.rotep.ui.SettingsUiState
import io.github.incallagent.rotep.ui.WindowInput
import io.github.incallagent.rotep.ui.lastCallUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Made-up states for the previews and the debug gallery. Every value is fictitious: the practice
 * shop with its number (03-0000-0000, which is never dialed), a made-up name, and no API key
 * (ADR-021). The date is fixed so the pictures do not change from day to day. The texts of the
 * call states are the ones CallSession and RotepApp show.
 */
object PreviewFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 7)
    val now: Instant = today.atTime(9, 0).atZone(ZoneId.of("Asia/Tokyo")).toInstant()

    val policy: DelegationPolicy = PracticePolicy.draft(today, now)

    val longNamePolicy: DelegationPolicy =
        policy.copy(target = policy.target.copy(businessName = "レストラン・サンプル 駅前本店 二階 個室フロア（長い店名の見本）"))

    /** The only wished-for time is yesterday: the check finds a problem. */
    val pastPolicy: DelegationPolicy =
        policy.copy(request = policy.request.copy(windows = listOf(TimeWindow.exactly(today.minusDays(1).atTime(19, 0)))))

    // --- 依頼 ---

    val emptyForm = RequestForm()

    val practiceForm: RequestForm = RequestForm.from(policy)

    val badForm = RequestForm(
        phoneNumber = PracticePolicy.PHONE_NUMBER,
        windows = listOf(WindowInput("11/31", "25:00", "")),
        partyPreferred = "",
    )

    /** What "確認へ" reports for [badForm]. */
    val badFormErrors: List<String> =
        (badForm.toPolicy("preview", now, today) as? RequestForm.Built.Errors)?.messages.orEmpty()

    // --- 確認と承認 ---

    val confirmL0 = ConfirmUiState(
        policy = policy,
        problems = PolicyValidator.validate(policy, now),
        blocker = null,
        stage = Stage.L0,
    )

    /** Ready to dial. In the app the practice number is refused ([confirmL2Practice]); here it stays, so no real number appears. */
    val confirmL2 = confirmL0.copy(stage = Stage.L2)

    /** What the app really shows for the practice example in L2. */
    val confirmL2Practice = confirmL2.copy(
        blocker = (DialCheck.check(policy.target.phoneNumber, policy.target.phoneNumber) as? DialCheck.Result.Refused)?.reason,
    )

    val confirmProblems = confirmL0.copy(policy = pastPolicy, problems = PolicyValidator.validate(pastPolicy, now))

    /** The text of RotepApp.readiness() without a key. */
    val confirmNoKey = confirmL0.copy(blocker = "Gemini の API キーがありません（設定で貼り付けてください）")

    val confirmLongName = confirmL0.copy(policy = longNamePolicy)

    /** Just after "承認": waiting for the call screen. */
    val confirmStarting = confirmL0.copy(starting = true)

    private val scheduleOffer = ScheduleOffer(blocked = null, today = today, first = ScheduleRules.firstChoice(now))

    /** L3 with the helper: a time can be chosen. */
    val confirmScheduleOpen = confirmL2.copy(stage = Stage.L3, schedule = scheduleOffer)

    /** Not L3: the reason stands where the button would be used. */
    val confirmScheduleBlocked = confirmL2.copy(schedule = scheduleOffer.copy(blocked = "時間を指定して電話するには、設定の「電話のしかた」を「全自動で電話をかける」にしてください"))

    /** The debugging switches go on just for the call: what the confirm screen says about it. */
    val confirmDebugging = confirmL2.copy(stage = Stage.L3, debuggingForCall = true, schedule = scheduleOffer)

    /** After "承認" while the helper is being started for the call: "やめる" takes the place of "直す". */
    val confirmPreparing = confirmDebugging.copy(starting = true, preparing = true)

    /** The thinking model is switched off: nothing goes to Anthropic. */
    val confirmDualOff = confirmL2.copy(dualBrain = false)

    /** The phone shows none of this app's notifications. */
    val confirmNotificationsOff = confirmL2.copy(notificationsOff = true)

    /** The call that waits, on the band of the first screen. */
    val scheduledInfo = ScheduledCalls.Info(PracticePolicy.SHOP_NAME, ScheduleRules.instantOf(today.plusDays(1).atTime(11, 0)))

    /** What the dialog shows, with a time that cannot be chosen. */
    val scheduleTimeProblem: String? = ScheduleRules.problem(today.atTime(22, 0), now)

    /** The text RotepApp puts on top when a permission was refused. */
    val PERMISSION_MESSAGE = UserMessages.missingPermissions(listOf(Manifest.permission.RECORD_AUDIO))

    // --- 通話中 ---

    private val lines = listOf(
        Line(Who.AI, policy.conversation.aiDisclosure),
        Line(Who.SHOP, "はい、${PracticePolicy.SHOP_NAME}です。"),
        Line(Who.INFO, "AI が話し始めるまで 1.2 秒"),
        Line(Who.AI, "今度の土曜日の19時から、2名で予約をお願いできますか。禁煙席を希望しています。"),
        Line(Who.SHOP, "19時ですと、禁煙席をご用意できます。"),
        Line(Who.TOOL, "evaluate_offer：範囲の中です（第一希望）"),
    )

    private val limit = CallSession.duration(policy.conversation.maxCallSeconds)

    /** Any time: only whether it is set matters to the screen. */
    private const val ANSWERED_AT = 1_000L

    val callL0Talking = CallUiState(
        snapshot = Snapshot(phase = Phase.TALKING, status = "AI が話しています", answeredAt = ANSWERED_AT, aiReady = true, lines = lines),
        stage = Stage.L0,
        warning = null,
        instruction = null,
        elapsedMs = 72_000,
        shopName = policy.target.businessName,
        limitSeconds = policy.conversation.maxCallSeconds,
    )

    val callL2Ringing = CallUiState(
        snapshot = Snapshot(phase = Phase.RINGING, status = "相手が出るのを待っています"),
        stage = Stage.L2,
        warning = null,
        instruction = "相手が出たら、この画面の「相手が出た」を押してください",
        elapsedMs = 9_000,
        shopName = policy.target.businessName,
        limitSeconds = policy.conversation.maxCallSeconds,
    )

    val callL2Talking = callL2Ringing.copy(
        snapshot = Snapshot(phase = Phase.TALKING, status = "AI が話しています", answeredAt = ANSWERED_AT, aiReady = true, lines = lines),
        instruction = null,
        elapsedMs = 72_000,
        panel = CallPanel(micAvailable = true, micOff = true, hangUpAvailable = true, watchesCall = true, openPhoneOffered = true),
    )

    val callL3Talking = callL2Talking.copy(
        stage = Stage.L3,
        panel = CallPanel(speakerAvailable = true, speakerOn = true, micAvailable = true, micOff = true, keypadAvailable = true, hangUpAvailable = true, watchesCall = true),
    )

    /** The words of CallSession.handOver in L2 (the status gets "。本人に代わってください"). */
    val callL2Timeout = callL2Talking.copy(
        snapshot = Snapshot(
            phase = Phase.HANDED_OVER,
            status = "通話の上限（$limit）になりました。本人に代わってください",
            answeredAt = ANSWERED_AT,
            aiReady = true,
            lines = lines,
            handover = "通話の上限（$limit）になりました",
        ),
        instruction = "電話の画面で電話を切ってから、「切った」を押してください",
        elapsedMs = policy.conversation.maxCallSeconds * 1_000L,
    )

    // --- 結果 ---

    private val saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

    private val booking = ConfirmedBooking(
        start = saturday.atTime(19, 0),
        partySize = 2,
        nameUsed = policy.disclosure.reservationName,
        confirmedPreferences = listOf("禁煙席"),
        shopConditions = listOf("2時間制"),
    )

    private fun result(
        status: OutcomeStatus,
        booking: ConfirmedBooking?,
        pending: List<String>,
        summary: String,
        reportedByAi: Boolean,
        endReason: EndReason,
        detail: String,
        answered: Boolean,
        talkMs: Long?,
    ): CallSession.Result {
        val outcome = CallOutcome(
            policyId = policy.id,
            status = status,
            booking = booking,
            pendingItems = pending,
            summary = summary,
            endedAt = now,
        )
        return CallSession.Result(outcome, OutcomeCheck.check(policy, outcome), reportedByAi, endReason, detail, answered, talkMs)
    }

    val resultBooked = result(
        OutcomeStatus.BOOKED, booking, emptyList(),
        "${saturday.monthValue}月${saturday.dayOfMonth}日の19時から、2名で予約できました。禁煙席です。",
        reportedByAi = true, endReason = EndReason.AI_HUNG_UP, detail = "AI が結果を報告して切りました", answered = true, talkMs = 95_000,
    )

    /** [resultBooked] as it is kept after the call, for the request and result screens. */
    val lastCall = lastCallUi(SavedResult(resultBooked, Stage.L3, PracticePolicy.SHOP_NAME))

    /** The shop said yes, but for 4 people and without the non-smoking seat: outside the approved range. */
    val resultBookedOutside = result(
        OutcomeStatus.BOOKED, booking.copy(partySize = 4, confirmedPreferences = emptyList()), emptyList(),
        "${saturday.monthValue}月${saturday.dayOfMonth}日の19時から、4名で予約できました。",
        reportedByAi = true, endReason = EndReason.AI_HUNG_UP, detail = "AI が結果を報告して切りました", answered = true, talkMs = 110_000,
    )

    val resultNeedsDecision = result(
        OutcomeStatus.NEEDS_USER_DECISION, null, listOf("20時からなら空いていると言われました（希望の範囲の外）"),
        "希望の時間は満席でした。折り返しが必要です。",
        reportedByAi = true, endReason = EndReason.AI_HUNG_UP, detail = "AI が結果を報告して切りました", answered = true, talkMs = 80_000,
    )

    val resultNotReached = result(
        OutcomeStatus.NOT_REACHED, null, emptyList(),
        "AI からの結果の報告はありません。相手が出ないまま通話が終わりました（相手が切った）",
        reportedByAi = false, endReason = EndReason.NOT_ANSWERED,
        detail = "相手が出ないまま通話が終わりました（相手が切った）", answered = false, talkMs = null,
    )

    val resultFailed = result(
        OutcomeStatus.FAILED, null, emptyList(),
        "AI からの結果の報告はありません。AI との接続が切れました",
        reportedByAi = false, endReason = EndReason.AI_CONNECTION_LOST, detail = "AI との接続が切れました", answered = true, talkMs = 41_000,
    )

    /** Refused before any call was placed (the words of CallSession). */
    val resultNotDialed = result(
        OutcomeStatus.FAILED, null, emptyList(),
        "承認されていないので、電話をかけませんでした",
        reportedByAi = false, endReason = EndReason.CALL_FAILED, detail = "承認されていないので、電話をかけませんでした", answered = false, talkMs = null,
    ).copy(dialed = false)

    /** L0, the AI connection was lost: the practice ends (there is no call to hand over). */
    val callL0Lost = callL0Talking.copy(
        snapshot = Snapshot(
            phase = Phase.ENDED,
            status = "終わりました",
            answeredAt = ANSWERED_AT,
            aiReady = true,
            lines = lines.take(4),
            handover = "AI との接続が切れました",
            result = resultFailed,
        ),
        elapsedMs = resultFailed.talkMs,
    )

    val callEnded = callL0Talking.copy(
        snapshot = Snapshot(
            phase = Phase.ENDED,
            status = "終わりました",
            answeredAt = ANSWERED_AT,
            aiReady = true,
            lines = lines + Line(Who.TOOL, "report_result：予約できました"),
            result = resultBooked,
        ),
        elapsedMs = resultBooked.talkMs,
    )

    // --- 設定 ---

    val settingsNoKey = SettingsUiState(settings = Settings(), shellAvailable = false, diagnosticsMessage = null)

    /** Some switches on: both looks side by side. */
    val settingsSwitches = settingsNoKey.copy(settings = settingsNoKey.settings.copy(slowSpeech = true, addToCalendar = true))

    /** Why the debugging switch cannot be turned on yet (the words of AdbSetup.onDemandSetupProblem). */
    const val DEBUGGING_SETUP_PROBLEM = "開発者向けオプションを普段は切っておくには、先に診断の画面でペアリングしてください"

    /** The switch of the store's callback is on. */
    val settingsCallback = settingsNoKey.copy(settings = settingsNoKey.settings.copy(notifyCallback = true))

    val settingsShellNotYet = SettingsUiState(
        settings = Settings(hasApiKey = true, audio = AudioChoice.SHELL_DIRECT),
        shellAvailable = false,
        diagnosticsMessage = "診断の画面を開けませんでした（アプリを入れ直してください）",
    )

    /** Every policy shown in the samples (the test checks that none of them can be dialed). */
    val policies: List<DelegationPolicy> = listOf(policy, longNamePolicy, pastPolicy)

    /** Every result shown in the samples. */
    val results: List<CallSession.Result> = listOf(resultBooked, resultBookedOutside, resultNeedsDecision, resultNotReached, resultFailed, resultNotDialed)
}
