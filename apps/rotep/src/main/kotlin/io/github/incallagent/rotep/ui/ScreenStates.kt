// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.ui

import io.github.incallagent.core.policy.BusinessKind
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.PolicyProblem
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.RotepApp.Screen
import io.github.incallagent.rotep.app.RotepApp.Stage
import io.github.incallagent.rotep.app.SavedResult
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.call.CallPanel
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.DialCheck
import io.github.incallagent.rotep.call.SpokenReading
import io.github.incallagent.rotep.call.CallSession.Phase
import io.github.incallagent.rotep.schedule.ScheduleOffer
import io.github.incallagent.rotep.ui.component.AitelMood
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * What each screen draws, as plain values. A screen's *Content function only draws one of these and
 * reports taps; its *Screen function reads RotepApp and builds it. Previews pass made-up values
 * (ui/preview). Nothing here needs Android or Compose, so JUnit can check it.
 */

/** 確認と承認. [blocker]: why the call cannot start now (the settings, a running call, the number). */
data class ConfirmUiState(
    val policy: DelegationPolicy,
    val problems: List<PolicyProblem>,
    val blocker: String?,
    val stage: Stage,
    /** What to keep in mind with the microphone ([Settings.micNote]), or null. */
    val micNote: String? = null,
    /** The switch of 「電話の記録」 is on (ADR-045, ADR-050): a call placed now is kept, and the screen says so. */
    val keepsHistory: Boolean = false,
    /** Whether the words heard also go to the thinking model (ADR-053). */
    val dualBrain: Boolean = true,
    /** "承認" was pressed and the call is starting: the buttons wait. */
    val starting: Boolean = false,
    /** What was looked up about the shop before the call. */
    val lookup: LookupUi = LookupUi.None,
    /** This came from another AI: what to check before approving, or null. */
    val otherAiNote: String? = null,
    /** The phone shows none of this app's notifications. */
    val notificationsOff: Boolean = false,
    /** "時間を指定して電話する". null: the section is not shown (the previews of the other screens). */
    val schedule: ScheduleOffer? = null,
    /** The debugging switches go on just for this call (ADR-049), and the screen says so. */
    val debuggingForCall: Boolean = false,
    /** The helper is being started for the approved call. */
    val preparing: Boolean = false,

    /** A call placed right now would come at night. */
    val lateAtNight: Boolean = false,

    /** The key without which the call cannot go ahead. */
    val missingKey: MissingKey? = null,
) {
    /** L0: the practice without a call. */
    val practice: Boolean get() = stage == Stage.L0

    val shopName: String get() = policy.target.businessName

    val canApprove: Boolean get() = problems.isEmpty() && blocker == null && !starting && lookup.holdsApproval == null

    /** Why "承認" cannot be pressed now, in one line next to the button, or null. */
    val disabledReason: String?
        get() = when {
            preparing -> "電話の準備をしています（少し時間がかかります）"
            starting -> "始めています"
            problems.isNotEmpty() -> "内容の確認で問題があります（上の一覧を直してください）"
            blocker != null -> blocker
            else -> lookup.holdsApproval
        }
}

/**
 * What the confirm screen says leaves the phone and stays on it (ADR-036, ADR-045, ADR-053).
 * [dualBrain] is the setting for this call: with Claude deciding what to say, the words heard and the
 * request also go to Anthropic, so the notice has to name it.
 */
fun retentionNotice(keepsHistory: Boolean, practice: Boolean = false, dualBrain: Boolean = true): String {
    val sent = if (dualBrain) {
        val words = if (practice) "話した言葉" else "相手の言葉"
        "通話の音声は、AI の処理のために Google（Gemini）に送られます。" +
            "${words}の文字起こしと依頼の内容（予約名、連絡先の番号など）は、Anthropic（Claude）にも送られます（規約に沿って一定期間記録されます）。"
    } else {
        "通話の音声と文字起こしは、AI の処理のために Google（Gemini）に送られます。"
    }
    return if (practice) {
        sent + "練習の内容は、この端末に残しません。"
    } else if (keepsHistory) {
        sent + "このアプリは、音声は保存しません。「電話の記録」がオンなので、店名、電話番号、用件、予約の内容、結果と要約、会話の文字を、暗号化してこの端末の中に残します（90 日、50 件まで）。"
    } else {
        sent + "このアプリは、音声も会話の文も保存しません。電話の結果（要約と予約の内容）は、最後の 1 件だけこの端末の中に残します。"
    }
}

/** One short line right above the approve button: who receives what is said. [retentionNotice] has the details. */
fun dataSentLine(practice: Boolean, dualBrain: Boolean): String {
    val voice = if (practice) "あなたの声" else "電話の相手の声"
    val words = if (practice) "あなたの言葉" else "相手の言葉"
    return if (dualBrain) "${voice}は Google に、${words}の文字と依頼の内容は Anthropic に送られます" else "${voice}と依頼の内容は Google に送られます"
}

/** What screen readers say for the approve button: what is approved, and for which shop. */
fun approveDescription(state: ConfirmUiState): String =
    when {
        // Starts with the words on the button (R.string.approve_practice, approve_and_call)
        state.practice -> "承認して練習を始める。${state.shopName}の練習です（電話はかけません）"
        state.policy.purpose == Purpose.CANCEL -> "承認して電話する。${state.shopName}の予約を取り消す電話です"
        else -> "承認して電話する。${state.shopName}に電話します"
    }

/** Whether the call screen offers 「断って終える」「少し待ってもらう」: the one place to take them off (ADR-046). */
internal const val USER_CUE_BUTTONS = true

/** 通話中. */
data class CallUiState(
    val snapshot: CallSession.Snapshot,
    val stage: Stage,
    /** E.g. the foreground service could not start. */
    val warning: String?,
    /** L2: what the user has to do by hand now. */
    val instruction: String?,
    /** See [callElapsedMs]. */
    val elapsedMs: Long?,
    /** The shop called (never its number). */
    val shopName: String? = null,
    /** The longest call allowed, counted from the answer (DelegationPolicy.conversation.maxCallSeconds). */
    val limitSeconds: Int? = null,
    /** What the call screen can do on this call now. */
    val panel: CallPanel = CallPanel(),
    /** The AI's voice needs the phone's microphone on ([Settings.keepMicOn]). */
    val aiNeedsMic: Boolean = false,
    /** The phone plays nothing while the AI has the call. */
    val quietWhileAi: Boolean = false,
    /** "電話を切る" was pressed: the call is on its way out. */
    val hangUpAsked: Boolean = false,
    val userCuesOffered: Boolean = USER_CUE_BUTTONS,
) {
    /** 「断って終える」「少し待ってもらう」: while the AI is the one on the call and nothing is ending it yet. */
    val showUserCues: Boolean
        get() = userCuesOffered && snapshot.phase == Phase.TALKING && snapshot.aiReady && !snapshot.hangingUp && !hangUpAsked

    /** A real call (L2 and later), not the practice without a call (L0). */
    val inCall: Boolean get() = stage != Stage.L0

    /** The stage where the app does not see everything itself: "相手が出た" and "切った" exist (L2). */
    val manual: Boolean get() = stage == Stage.L2

    val ended: Boolean get() = snapshot.phase == Phase.ENDED

    /** "AI を止めて本人に代わる" still has something to stop. */
    val aiOn: Boolean get() = snapshot.phase in AI_PHASES && !snapshot.hangingUp

    val canAnswer: Boolean get() = manual && snapshot.phase == Phase.RINGING

    val canHangUp: Boolean get() = manual && snapshot.phase != Phase.ENDED && snapshot.phase != Phase.IDLE

    /**
     * "相手が出た": only when the app cannot see the answer itself, or has rung for so long without
     * seeing one that the user may know better.
     */
    val showAnswerButton: Boolean
        get() = manual && !ended && (!panel.watchesCall || (canAnswer && (elapsedMs ?: 0L) >= ANSWER_FALLBACK_MS))

    /** "切った": only when the app cannot see the end of the call itself. */
    val showHungUpButton: Boolean get() = manual && !ended && !panel.watchesCall

    val showManualButtons: Boolean get() = showAnswerButton || showHungUpButton

    /** The row of the speaker, the microphone, the keypad and "電話を切る": a real call, until the end. */
    val showPanel: Boolean
        get() = inCall && !ended && with(panel) { speakerAvailable || micAvailable || keypadAvailable || hangUpAvailable || openPhoneOffered }

    /**
     * While the AI talks through the phone's microphone, stopping that microphone would cut its voice
     * off, and the speaker would feed the far end's voice back into it.
     */
    val panelLocked: Boolean get() = aiNeedsMic && aiOn

    /** Why the microphone and the speaker do not do what they seem to right now, or null. */
    val panelNote: String?
        get() = when {
            panelLocked -> "AI の声を端末のマイクで届けているので、AI が話している間はマイクとスピーカーを変えられません"
            quietWhileAi && aiOn && panel.speakerOn -> "AI が話している間は通話の音を消しています（代わると聞こえます）"
            else -> null
        }

    /** Once there is a result, its button takes the place of "AI を止めて本人に代わる". */
    val showResultButton: Boolean get() = snapshot.result != null

    val clock: CallClock get() = callClock(snapshot, elapsedMs, limitSeconds)
}

/** How long a call may ring without an answer being seen before "相手が出た" comes back as a fallback. */
const val ANSWER_FALLBACK_MS = 20_000L

private val AI_PHASES = setOf(Phase.DIALING, Phase.RINGING, Phase.TALKING, Phase.CLOSING)

/** Time shown on the call screen: the talk time once the call ended, otherwise [liveMs]. */
fun callElapsedMs(snapshot: CallSession.Snapshot, liveMs: Long?): Long? =
    if (snapshot.phase == Phase.ENDED) snapshot.result?.talkMs else liveMs

/**
 * The time on the call screen. Before the answer it counts the ringing; after it, the conversation,
 * with the limit (CallSession starts the limit's clock at the answer).
 */
data class CallClock(val answered: Boolean, val elapsedMs: Long?, val limitSeconds: Int?)

fun callClock(snapshot: CallSession.Snapshot, elapsedMs: Long?, limitSeconds: Int?): CallClock {
    val answered = snapshot.answeredAt != null
    return CallClock(answered, elapsedMs, if (answered) limitSeconds else null)
}

/** Minutes and seconds, "1:05". */
fun mmss(ms: Long?): String {
    if (ms == null || ms < 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
}

/** "呼び出し 0:09", or "会話 1:12（上限 5分）" in the words of the time-out message. */
fun clockText(c: CallClock): String =
    if (!c.answered) {
        "呼び出し ${mmss(c.elapsedMs)}"
    } else {
        "会話 ${mmss(c.elapsedMs)}" + (c.limitSeconds?.let { "（上限 ${CallSession.duration(it)}）" } ?: "")
    }

/** An end for one of these reasons says itself what to do: they are the words of [io.github.incallagent.rotep.app.UserMessages.aiConnection]. */
private val SAYS_WHAT_TO_DO = listOf("API キー", "Billing")

/**
 * What to do next when nothing else on the result screen says it. The AI's own report does, the items
 * to call back about do, and so does an end that was about a key. null: there is nothing to add.
 */
fun resultNextStep(result: CallSession.Result, practice: Boolean = false): String? {
    if (practice || !result.dialed) return null
    val status = result.outcome.status
    return when {
        status == OutcomeStatus.NOT_REACHED -> "番号が合っているかを確かめて、時間をおいてかけ直してください。下の「続き」から同じ内容で頼めます"
        result.reportedByAi || status != OutcomeStatus.FAILED -> null
        SAYS_WHAT_TO_DO.any { it in result.detail } -> null
        !result.answered -> "お店とはまだ話していません。原因を確かめてから、下の「続き」からもう一度頼めます"
        else -> "用件が済んだかどうかは、アプリには分かりません。お店に確かめてください"
    }
}

/** The key that stops a call, when one does. */
enum class MissingKey { GEMINI, CLAUDE }

fun missingKey(settings: Settings): MissingKey? = when {
    !settings.hasApiKey -> MissingKey.GEMINI
    settings.dualBrain && !settings.hasClaudeKey -> MissingKey.CLAUDE
    else -> null
}

/** How to go on without the key, in the words of the switch on the settings screen. null: there is no way without it. */
fun missingKeyNote(key: MissingKey): String? = when (key) {
    MissingKey.GEMINI -> null
    MissingKey.CLAUDE -> "設定の「通話の考える役に Claude を使う」を切ると、Claude のキーがなくても Gemini だけで電話できます"
}

/** The headline of the result. "予約できました" only when the check against the policy found nothing. */
enum class ResultHeadline {
    BOOKED_FIRST, BOOKED_OTHER, BOOKED_NEEDS_REVIEW, NEEDS_DECISION, UNAVAILABLE, NOT_REACHED, FAILED,
    CHANGED, CHANGED_OTHER, NOT_CHANGED, CANCELLED, NOT_CANCELLED, NOT_CANCELLED_YET, ANSWERED, NOT_ANSWERED,
    NOT_DIALED, STOPPED_BY_USER,
}

fun resultHeadline(result: CallSession.Result): ResultHeadline {
    val status = result.outcome.status
    val booked = status == OutcomeStatus.BOOKED || status == OutcomeStatus.BOOKED_ALTERNATIVE
    val purpose = result.purpose
    val clean = result.violations.isEmpty()
    return when {
        !result.dialed -> ResultHeadline.NOT_DIALED
        // Only the user knows how it ended, whatever the errand was
        !result.reportedByAi && (result.endReason == CallSession.EndReason.USER_TOOK_OVER || result.endReason == CallSession.EndReason.USER_HUNG_UP) ->
            ResultHeadline.STOPPED_BY_USER
        booked && !clean -> ResultHeadline.BOOKED_NEEDS_REVIEW
        status == OutcomeStatus.BOOKED -> if (purpose == Purpose.CHANGE) ResultHeadline.CHANGED else ResultHeadline.BOOKED_FIRST
        status == OutcomeStatus.BOOKED_ALTERNATIVE -> if (purpose == Purpose.CHANGE) ResultHeadline.CHANGED_OTHER else ResultHeadline.BOOKED_OTHER
        status == OutcomeStatus.DONE && clean && purpose == Purpose.CANCEL -> ResultHeadline.CANCELLED
        status == OutcomeStatus.DONE && clean && purpose == Purpose.INQUIRY -> ResultHeadline.ANSWERED
        status == OutcomeStatus.NEEDS_USER_DECISION ->
            if (purpose == Purpose.CANCEL) ResultHeadline.NOT_CANCELLED_YET else ResultHeadline.NEEDS_DECISION
        status == OutcomeStatus.UNAVAILABLE -> when (purpose) {
            Purpose.CANCEL -> ResultHeadline.NOT_CANCELLED
            Purpose.CHANGE -> ResultHeadline.NOT_CHANGED
            Purpose.INQUIRY -> ResultHeadline.NOT_ANSWERED
            Purpose.NEW_BOOKING -> ResultHeadline.UNAVAILABLE
        }
        status == OutcomeStatus.NOT_REACHED -> ResultHeadline.NOT_REACHED
        else -> ResultHeadline.FAILED
    }
}

fun headlineText(h: ResultHeadline): String = when (h) {
    ResultHeadline.BOOKED_FIRST -> "予約できました（第一希望）"
    ResultHeadline.BOOKED_OTHER -> "予約できました（範囲内の別の候補）"
    ResultHeadline.BOOKED_NEEDS_REVIEW -> "予約の返事はありましたが、確認が要ります"
    ResultHeadline.NEEDS_DECISION -> "折り返しが必要です"
    // Not "空きがありません": the shop may be closed, or take bookings only on the web
    ResultHeadline.UNAVAILABLE -> "予約できませんでした"
    ResultHeadline.NOT_REACHED -> "つながりませんでした"
    ResultHeadline.FAILED -> "うまくいきませんでした"
    ResultHeadline.CHANGED -> "予約を変更できました（第一希望）"
    ResultHeadline.CHANGED_OTHER -> "予約を変更できました（範囲内の別の候補）"
    ResultHeadline.NOT_CHANGED -> "変更できませんでした（下を読んでください）"
    ResultHeadline.CANCELLED -> "予約を取り消せました"
    ResultHeadline.NOT_CANCELLED -> "取り消せませんでした（下を読んでください）"
    ResultHeadline.NOT_CANCELLED_YET -> "まだ取り消していません（ご自分でお店に電話してください）"
    ResultHeadline.ANSWERED -> "答えを聞けました"
    ResultHeadline.NOT_ANSWERED -> "答えを聞けませんでした（下を読んでください）"
    ResultHeadline.NOT_DIALED -> "電話をかけていません"
    ResultHeadline.STOPPED_BY_USER -> "途中で止めました"
}

/** A date and time for a person to read: "11月14日(土) 19:00". */
fun dateTime(t: LocalDateTime): String =
    "${t.monthValue}月${t.dayOfMonth}日(${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)}) " +
        "%d:%02d".format(Locale.ROOT, t.hour, t.minute)

/**
 * The lines of the conversation the call screen shows. Tool calls and the timing note are for the
 * log, not for the person on the phone: they stay out. (The timing note starts as [CallSession]
 * writes it.)
 */
fun visibleCallLines(lines: List<CallSession.Line>): List<CallSession.Line> =
    lines.filterNot { it.who == CallSession.Who.TOOL || (it.who == CallSession.Who.INFO && it.text.startsWith(TIMING_NOTE)) }

/**
 * The line under the call's state, or null when it only says again what the state at the top says
 * ("AI が会話しています" and "AI が話しています"; "終話" and "終わりました").
 */
fun callStatusLine(snapshot: CallSession.Snapshot): String? =
    snapshot.status.takeUnless { it == phaseLabel(snapshot.phase) || it in STATUSES_THE_STATE_SAYS }

private val STATUSES_THE_STATE_SAYS = setOf("待機中", "発信しています", "AI が話しています", "AI が締めくくっています", "終わりました")

private const val TIMING_NOTE ="AI が話し始めるまで"

/**
 * One finding of the check against the policy in words for the screen. The checker's own text is
 * written for the AI (it names the statuses); a text this does not know is shown as it is.
 */
fun violationText(v: OutcomeCheck.Violation): String {
    val m = v.message
    return when {
        m.startsWith("第一希望ではないので") -> "第一希望とは違う内容で予約されています"
        m.startsWith("予約の用件では DONE") -> "予約の電話ですが、予約できたかどうかの報告がありません"
        m.startsWith("希望の日時の範囲外です：") -> {
            val at = runCatching { LocalDateTime.parse(m.substringAfter("：")) }.getOrNull()
            if (at == null) m else "希望の日時の範囲外です：${dateTime(at)}"
        }
        m.startsWith("許可した人数の範囲外です：") -> {
            val n = m.substringAfter("：")
            "許可した人数の範囲外です：" + if (n.isNotEmpty() && n.all { it.isDigit() }) "${n}名" else n
        }
        m == "別の委任ポリシーの結果です" -> "別の依頼の結果です"
        else -> m
    }
}

/** Which earlier call a kept result is about: how it came out, and "the shop, when it ended". */
data class LastCallUi(val headline: String, val where: String)

fun lastCallUi(saved: SavedResult): LastCallUi = LastCallUi(
    headline = headlineText(resultHeadline(saved.result)),
    where = "${saved.shopName}、${dateTime(saved.result.outcome.endedAt.atZone(JAPAN).toLocalDateTime())}",
)

private val JAPAN = ZoneId.of("Asia/Tokyo")

/** The big two-part title of a hero, and AITEL's mood there. */
data class HeroText(val first: String, val second: String?, val mood: AitelMood)

/** 依頼. In practice (L0) nothing is dialed, so the hero does not say 電話. */
fun requestHero(practice: Boolean): HeroText =
    if (practice) HeroText("どこの店で", "練習する？", AitelMood.HAPPY) else HeroText("どこに", "電話する？", AitelMood.HAPPY)

/** 確認と承認：what is approved, for which shop. */
fun confirmHero(state: ConfirmUiState): HeroText =
    when {
        state.practice -> HeroText("${state.shopName}の", "練習を始める？", AitelMood.ASKING)
        state.policy.purpose == Purpose.CANCEL -> HeroText("${state.shopName}の予約を", "取り消していい？", AitelMood.ASKING)
        else -> HeroText("${state.shopName}に", "電話していい？", AitelMood.ASKING)
    }

/** 結果：a few big words. The full headline (and the check) follows under the hero. */
fun resultHero(h: ResultHeadline): HeroText = when (h) {
    ResultHeadline.BOOKED_FIRST, ResultHeadline.BOOKED_OTHER -> HeroText("予約", "できました！", AitelMood.GREAT)
    ResultHeadline.BOOKED_NEEDS_REVIEW -> HeroText("確認が", "要ります", AitelMood.WORRIED)
    ResultHeadline.NEEDS_DECISION -> HeroText("折り返しが", "必要です", AitelMood.ASKING)
    ResultHeadline.UNAVAILABLE -> HeroText("予約", "できませんでした", AitelMood.SAD)
    ResultHeadline.NOT_REACHED -> HeroText("つながり", "ませんでした", AitelMood.SAD)
    ResultHeadline.FAILED -> HeroText("うまく", "いきませんでした", AitelMood.SORRY)
    ResultHeadline.CHANGED, ResultHeadline.CHANGED_OTHER -> HeroText("変更", "できました！", AitelMood.GREAT)
    ResultHeadline.NOT_CHANGED -> HeroText("変更", "できませんでした", AitelMood.SAD)
    ResultHeadline.CANCELLED -> HeroText("取り消し", "できました", AitelMood.GREAT)
    ResultHeadline.NOT_CANCELLED -> HeroText("取り消し", "できませんでした", AitelMood.SAD)
    ResultHeadline.NOT_CANCELLED_YET -> HeroText("まだ", "取り消していません", AitelMood.ASKING)
    ResultHeadline.ANSWERED -> HeroText("答えを", "聞けました", AitelMood.GREAT)
    ResultHeadline.NOT_ANSWERED -> HeroText("答えを", "聞けませんでした", AitelMood.SAD)
    ResultHeadline.NOT_DIALED -> HeroText("電話を", "かけていません", AitelMood.SORRY)
    ResultHeadline.STOPPED_BY_USER -> HeroText("途中で", "止めました", AitelMood.ASKING)
}

/** Only a booking the check found nothing wrong with gets the bright result hero. */
fun resultIsGood(h: ResultHeadline): Boolean = when (h) {
    ResultHeadline.BOOKED_FIRST, ResultHeadline.BOOKED_OTHER,
    ResultHeadline.CHANGED, ResultHeadline.CHANGED_OTHER,
    ResultHeadline.CANCELLED, ResultHeadline.ANSWERED,
    -> true
    else -> false
}

/** The celebration is for a booking made or moved: not for a cancel or an answer. */
fun resultCelebrates(h: ResultHeadline): Boolean = when (h) {
    ResultHeadline.BOOKED_FIRST, ResultHeadline.BOOKED_OTHER, ResultHeadline.CHANGED, ResultHeadline.CHANGED_OTHER -> true
    else -> false
}

/**
 * What the user should know before approving; none of it stops the call. An empty contact number
 * ends many calls early: the shop asks for one, and the AI can only say it will call back.
 */
/** The hours in which no call can be scheduled: a shop may well be closed. */
fun lateAtNight(now: Instant): Boolean = !ScheduleRules.inDaytime(ScheduleRules.localOf(now).toLocalTime())

fun confirmWarnings(policy: DelegationPolicy, practice: Boolean = false, notificationsOff: Boolean = false, lateAtNight: Boolean = false): List<String> = buildList {
    if (lateAtNight && !practice) add("いまは夜遅い時間か早朝です。お店が閉まっている時間かもしれません")
    if (notificationsOff && !practice) {
        add("通知が許可されていません。AI が止まって本人に代わるときの知らせが出ません。承認のあとに聞かれたら許可するか、端末の設定で許可してください")
    }
    val booking = policy.purpose == Purpose.NEW_BOOKING || policy.purpose == Purpose.CHANGE
    val cancel = policy.purpose == Purpose.CANCEL
    if (DialCheck.isNavigationDial(policy.target.phoneNumber)) {
        add("0570 で始まる番号（ナビダイヤル）は、通話料が高く、かけ放題の対象外です")
    }
    if (policy.disclosure.callbackNumber.isNullOrBlank()) {
        if (booking) add("連絡先の電話番号が空です。お店はほぼ必ず聞きます。空のままだと、聞かれたところで折り返しになります")
        if (cancel) add("連絡先の電話番号が空です。予約を探すのに聞かれると、折り返しになります")
    }
    if (policy.target.kind == BusinessKind.CLINIC) {
        add("体調が急に悪いときや、受診してよいかの相談は、ご自分で電話してください。AI は医療の判断を聞きません")
    }
    if (cancel && !practice) add("取り消すと元に戻せないことがあります。店名と取り消す予約を確かめてください")
    if (cancel && policy.authority.mayAcceptCancellationPolicy) {
        add("キャンセル料がいくらでも、AI は取り消します（金額の上限はありません）")
    }
}

/** The confirm screen's line after the chat: the model wrote this down, and it can get it wrong. */
fun chatNote(kind: String, readings: List<SpokenReading> = emptyList(), placeholderNumber: Boolean = false): String = buildString {
    append("AI とのチャットから作った内容です。店名、${if (placeholderNumber) "" else "電話番号、"}日時、予約名、連絡先、お店に伝えること、任せる範囲が正しいか確かめてください")
    if (placeholderNumber) append("\n\n電話番号は練習用の架空の番号です。電話はかけません")
    append("\n\n電話先の種類：$kind")
    readingsNote(readings)?.let { append("\n\n$it") }
}

/** How the AI will say names: on the confirm screen whenever there are any, so nothing is said that was not seen. */
fun readingsNote(readings: List<SpokenReading>): String? =
    readings.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "電話で言う読み：") { "${it.text}（${it.kana}）" }

/** The confirm screen's line for a request from another AI: its number is not one the user typed. */
fun otherAiNote(kind: String, numberSource: String?, assistant: String? = null): String = buildString {
    append("${requestSenderName(assistant)} から届いた依頼です。店名、電話番号、連絡先、予約名、お店に伝えることは、その AI が書きました。正しいか確かめてください。結果はその AI に返します")
    append("\n\n電話先の種類：$kind")
    // A paragraph of its own (a request cannot contain a newline here), so it cannot pass for the app's words
    numberSource?.let { append("\n\nその AI の申告（アプリは確かめていません）\n番号の出どころ：$it") }
}

/** AITEL's face on the call screen, from the call's state. */
fun callMood(snapshot: CallSession.Snapshot): AitelMood = when (snapshot.phase) {
    Phase.IDLE, Phase.DIALING -> AitelMood.GOING
    Phase.RINGING -> AitelMood.WAITING
    Phase.TALKING, Phase.CLOSING -> AitelMood.TALKING
    Phase.HANDED_OVER -> AitelMood.RESTING
    Phase.ENDED -> snapshot.result?.let { resultHero(resultHeadline(it)).mood } ?: AitelMood.RESTING
}

/** 設定. */
data class SettingsUiState(
    val settings: Settings,
    /** The helper is connected, so its audio path can be used (ShellAudioPaths.factory). */
    val shellAvailable: Boolean,
    /** Why the diagnostics screen did not open, or null. */
    val diagnosticsMessage: String?,
    /**
     * Why the automatic call control (L3) cannot be used on this phone, or null when it can
     * (TelecomCallControl.readinessProblem). Shown only while "自動" is chosen.
     */
    val autoProblem: String? = null,
)

/** Under each switch of 任せる範囲: what the AI does now. Off is the default (call back instead). */
fun authorityStateText(on: Boolean): String = if (on) "いま：する" else "いま：しない（確認して折り返す）"

/** The largest party PolicyValidator accepts (its MAX_PARTY_SIZE). */
private const val MAX_PARTY = 50

/**
 * The text of a party size field after "−" (-1) or "＋" (+1), or null when the button cannot be used.
 * Read as RequestForm reads it (full-width digits, a trailing "名"); an empty field starts from
 * [fallback] (an empty minimum or maximum means the preferred size). The result stays within 1..50
 * and is written in ASCII digits.
 */
fun partyStep(text: String, fallback: String, delta: Int): String? {
    val source = text.ifBlank { fallback }
    val ascii = buildString(source.length) { for (c in source) append(if (c in '０'..'９') '0' + (c - '０') else c) }
    val value = ascii.trim().removeSuffix("名").trim().toIntOrNull() ?: return null
    return (value + delta).takeIf { it in 1..MAX_PARTY }?.toString()
}

/** One item of the policy in words: its label (a heading for screen readers) and its lines. */
data class SummaryItem(val label: String, val lines: List<String>)

/**
 * Splits the text of PolicySummary.render ("label：value" lines; indented lines belong to the item
 * above) into items, so each label can be a heading. A line without a label becomes an item without
 * one, so nothing is lost if the text changes.
 */
fun summaryItems(text: String): List<SummaryItem> {
    val items = mutableListOf<SummaryItem>()
    for (raw in text.lines()) {
        if (raw.isBlank()) continue
        if (raw.first().isWhitespace() && items.isNotEmpty()) {
            val line = raw.trim().let { if (it.startsWith("- ")) "・" + it.removePrefix("- ") else it }
            items[items.lastIndex] = items.last().let { it.copy(lines = it.lines + line) }
            continue
        }
        val colon = raw.indexOf('：')
        items += if (colon > 0) {
            SummaryItem(raw.substring(0, colon), listOfNotNull(raw.substring(colon + 1).takeIf { it.isNotBlank() }))
        } else {
            SummaryItem("", listOf(raw.trim()))
        }
    }
    return items
}

/** The summary in two parts: what matters most (shown large) and everything else that is approved. */
data class SummarySplit(val key: List<SummaryItem>, val rest: List<SummaryItem>)

/** The start of each label that goes first, in the order shown (the labels come from PolicySummary). */
private val KEY_SUMMARY_LABELS = listOf("電話先", "日時", "人数")

/** Splits [items] without losing any: a label this list does not know stays in the rest. */
fun splitSummary(items: List<SummaryItem>): SummarySplit {
    val key = KEY_SUMMARY_LABELS.flatMap { start -> items.filter { it.label.startsWith(start) } }
    return SummarySplit(key, items - key.toSet())
}

/**
 * How the call goes in [stage], for the confirm screen. A stage this map does not know yet (a new
 * one from the call control work) gets no note instead of breaking the build.
 */
fun stageNote(stage: Stage): String? = STAGE_NOTES[stage]

private val STAGE_NOTES = mapOf(
    Stage.L0 to "電話はかけません。あなたがお店の店員の役をします。AI の声が端末から出て、" +
        "あなたの声をマイクで AI に送ります。",
    // Whether the phone's microphone is stopped depends on a switch ([Settings.keepMicOn]), and its
    // note is on the same screen: these do not say it is always stopped
    Stage.L2 to "電話をかけます。相手が出ると、アプリがそれを読み取って AI が話し始めます（読み取れないときだけ「相手が出た」を押します）。" +
        "電話を切る、端末のマイクを止める操作は、通話の画面からできます。プッシュ音は、画面の案内に合わせて手で行います。" +
        "端末のマイクを手で止めるのは、画面に案内が出たときだけです。" +
        "保留やほかの通話になったときは、AI が止まり、あなたに代わります。",
    Stage.L3 to "電話をかけます。相手が出たら AI が話し始め、プッシュ音も、電話を切るのも、端末のマイクの操作も、アプリが行います。" +
        "保留やほかの通話になったときは、AI が止まり、あなたに代わります。",
)
