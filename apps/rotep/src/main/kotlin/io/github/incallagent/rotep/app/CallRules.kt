// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.os.Build
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.urgentItems
import io.github.incallagent.rotep.history.Entry

/**
 * How the call session runs in [stage] with [settings]. Outside RotepApp (no Android) so that a
 * unit test sees it: in a call the AI may hand the call over, and the phone's microphone is
 * stopped while the AI talks unless [Settings.keepMicOn] says not to. L0 ends at the hand-over and
 * may send silence while the AI speaks (halfDuplex).
 */
internal fun callSessionConfig(stage: RotepApp.Stage, settings: Settings, unattended: Boolean = false): CallSession.Config {
    val inCall = stage != RotepApp.Stage.L0
    return CallSession.Config(
        handOverKeepsCall = inCall && !unattended,
        muteMicDuringAi = inCall && !settings.keepMicOn,
        // With the thinking model the ears would write down the AI's own voice from the loudspeaker, and take it for the shop's
        halfDuplex = !inCall && (settings.halfDuplex || settings.dualBrain),
        unattended = unattended,
        lastWordsMaxMs = if (inCall) IN_CALL_LAST_WORDS_MAX_MS else CallSession.Config().lastWordsMaxMs,
        lastWordsQuietMs = if (inCall) IN_CALL_LAST_WORDS_QUIET_MS else 0,
        audioLossGraceMs = if (inCall) IN_CALL_AUDIO_LOSS_GRACE_MS else 0,
        replyWaitMs = REPLY_WAIT_MS,
        afterReplyQuietMs = AFTER_REPLY_QUIET_MS,
        replyMaxMs = REPLY_MAX_MS,
        lastReportWaitMs = if (settings.dualBrain) DUAL_LAST_REPORT_WAIT_MS else 0,
    )
}

private const val IN_CALL_LAST_WORDS_MAX_MS = 15_000L
private const val IN_CALL_LAST_WORDS_QUIET_MS = 1_500L
private const val IN_CALL_AUDIO_LOSS_GRACE_MS = 4_000L

// ADR-051: not to hang up on the other side's 「失礼します」
private const val REPLY_WAIT_MS = 4_000L
private const val AFTER_REPLY_QUIET_MS = 1_500L
private const val REPLY_MAX_MS = 20_000L

/** Long enough for one turn of the thinking model, which also has the conversation to report from. */
private const val DUAL_LAST_REPORT_WAIT_MS = 6_000L

/**
 * Whether the phone keeps the call quiet while the AI has it: unless the user turned
 * [Settings.playCallAudio] on. Only a call through the helper can be kept quiet.
 */
internal fun keepsCallQuiet(settings: Settings): Boolean = settings.audio.shell != null && !settings.playCallAudio

/**
 * One session of the transcription model lasts about ten minutes (ADR-053), and a call ends a minute before that:
 * if the ears closed first, the mouth would stop too, in the middle of the closing words of a call nobody is on.
 */
private const val DUAL_MAX_CALL_SECONDS = 540

/** [policy] as the call is run: with the thinking model, within what the ears allow. */
internal fun policyForCall(policy: DelegationPolicy, dualBrain: Boolean): DelegationPolicy =
    if (dualBrain && policy.conversation.maxCallSeconds > DUAL_MAX_CALL_SECONDS) {
        policy.copy(conversation = policy.conversation.copy(maxCallSeconds = DUAL_MAX_CALL_SECONDS))
    } else {
        policy
    }

/**
 * Why this phone cannot take a real call at all, or null. The helper refuses the sound of a call below Android 14
 * ([io.github.incallagent.rotep.shell.CallAudioBridge]), and that shows only after the shop has answered. A practice runs from Android 12.
 */
internal fun callAudioProblem(settings: Settings, sdk: Int): String? =
    UserMessages.CALL_NEEDS_ANDROID_14.takeIf { settings.audio.shell != null && sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE }

/** Why a call that dials cannot be placed for want of a network, or null. Without one the other side would answer and hear nothing. */
internal fun noNetworkProblem(stage: RotepApp.Stage, hasNetwork: Boolean): String? =
    UserMessages.NO_NETWORK_FOR_CALL.takeIf { stage != RotepApp.Stage.L0 && !hasNetwork }

/** Why a call cannot use the thinking model, or null. [keyProblem]: what is known about the saved key, in words for the user. */
internal fun dualBrainProblem(settings: Settings, keyProblem: String?): String? =
    if (settings.dualBrain && !settings.hasClaudeKey) keyProblem ?: UserMessages.CLAUDE_KEY_MISSING else null

/** The keys a call needs, as they were read just before it. */
internal sealed interface CallKeys {
    /** [claude]: null when the call does not use the thinking model. */
    class Ready(val gemini: String, val claude: String?) : CallKeys {
        override fun toString() = "Ready(<hidden>)"
    }

    /** One of them cannot be read just now and was kept: the next read tries again. */
    data object NotNow : CallKeys

    data object GeminiGone : CallKeys

    data object ClaudeGone : CallKeys
}

/** [claude]: null when the call does not use the thinking model, and then it is not read at all. */
internal fun callKeys(gemini: ApiKeyStore.Read, claude: ApiKeyStore.Read?): CallKeys = when {
    gemini is ApiKeyStore.Read.NotNow || claude is ApiKeyStore.Read.NotNow -> CallKeys.NotNow
    gemini !is ApiKeyStore.Read.Key -> CallKeys.GeminiGone
    claude == null -> CallKeys.Ready(gemini.value, null)
    claude !is ApiKeyStore.Read.Key -> CallKeys.ClaudeGone
    else -> CallKeys.Ready(gemini.value, claude.value)
}

/** Whether a result replaces the kept one: only a real call that was placed, so a refused dial loses nothing. */
internal fun keepsResult(inCall: Boolean, result: CallSession.Result): Boolean = inCall && result.dialed

/**
 * Goes into 「予約の控え」 (ADR-045): on when the call started (a call at a chosen time starts when
 * its time comes) and still on when it ended, so that turning it off during a call is heard.
 */
internal fun keepsInHistory(inCall: Boolean, result: CallSession.Result, onAtStart: Boolean, onNow: Boolean): Boolean =
    keepsResult(inCall, result) && onAtStart && onNow

/** Where "戻る" on 「電話の記録」 leads. Opened again while it is showing, it keeps what it knew. */
internal fun historyOpenedFrom(current: RotepApp.Screen, remembered: RotepApp.Screen): RotepApp.Screen =
    if (current == RotepApp.Screen.HISTORY) remembered else current

/** The number kept of the very call whose result this is, not of an earlier call to a shop of the same name. */
internal fun keptNumberOf(entry: Entry?, result: CallSession.Result): String? =
    entry?.takeIf { it.at == result.outcome.endedAt }?.phoneNumber?.takeIf { it.isNotBlank() }

internal enum class CallEndNotice { NONE, FINISHED, URGENT }

/**
 * What has to reach the user when a session ends. Outside RotepApp (no Android) so that a unit test
 * sees it: what cannot wait comes first, and L0 has no call to tell about (the user took part in it).
 * A call the app lost is not called over: it may still be ringing or going on.
 */
internal fun callEndNotice(inCall: Boolean, result: CallSession.Result): CallEndNotice = when {
    !result.dialed -> CallEndNotice.NONE
    result.outcome.urgentItems().isNotEmpty() -> CallEndNotice.URGENT
    inCall && result.leftOpen -> CallEndNotice.URGENT
    inCall -> CallEndNotice.FINISHED
    else -> CallEndNotice.NONE
}
