// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.agent.live.CallActions
import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTools
import io.github.incallagent.agent.live.ServerEvent
import io.github.incallagent.core.policy.CallOutcome
import io.github.incallagent.core.policy.ConfirmedBooking
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.OutcomeStatus
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.UserMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Opens the Gemini Live connection of a session (RotepLive in the app, a fake in tests). */
fun interface LiveConnector {
    fun connect(session: LiveSession): RotepConnection
}

/**
 * One call for one approved delegation policy:
 * 待機 → 発信 → 相手が出るのを待つ → AI が会話 → 締めくくり → 終話 → 結果.
 *
 * Every way through ends with exactly one [Result] ([Listener.onResult] once, and in [state]):
 * the outcome the AI reported, or one written here with the reason when it did not report.
 *
 * - The AI starts when the far end answers ([CallEvent.Active]; the practice reports it at once)
 * - Audio goes straight through on the audio and network threads: downlink →
 *   [LiveSession.sendAudio]; agent audio → [CallAudioPath.play]; interrupted → [CallAudioPath.flush]
 * - end_call: wait until the last words are played (at most [Config.lastWordsMaxMs]), give the
 *   other side the time to answer them ([Config.replyWaitMs]), then hang up
 * - report_result that nobody was reached, when no message may be left: hang up at once, without last words
 * - Time limit, lost AI connection (or none ready within [Config.aiReadyMaxMs] of the answer),
 *   stopped audio path, or the user's "AI を止めて本人に代わる": the AI stops, the user is
 *   alerted ([Listener.onAlert]) and gets the call ([CallControl.setMuted] false). The practice has no call
 *   to hand over, so it ends there
 * - The AI must say that it is an AI ([AiDisclosure]): checked on the transcript of what it says.
 *   When its first utterance did not, it gets a cue to say so; when the next one does not either,
 *   the AI stops and the user takes over ([EndReason.NO_AI_DISCLOSURE])
 *
 * Control steps run on [scheduler] one at a time, so the fields below need no locks. [log] gets
 * phases, reasons and counts, never a number, a name or what was said; transcripts only go to
 * [state] for the screen.
 */
class CallSession(
    private val policy: DelegationPolicy,
    private val audio: CallAudioPath,
    private val control: CallControl,
    private val live: LiveConnector,
    private val scheduler: CallScheduler,
    private val today: LocalDate,
    private val config: Config = Config(),
    private val listener: Listener = object : Listener {},
    private val wallClock: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val model: String = LiveMessages.DEFAULT_MODEL,
        /** false (the practice): there is no call to hand over, so the session ends when the AI stops. */
        val handOverKeepsCall: Boolean = true,
        /** Mute the phone's own microphone while the AI talks (not when the AI's voice goes out through it). */
        val muteMicDuringAi: Boolean = true,
        /** Send silence instead of the downlink while the agent's voice plays (echo in the practice). */
        val halfDuplex: Boolean = false,
        val lastWordsMaxMs: Long = 5_000,
        /** How long nothing must be playing before the last words count as said. 0: at once. */
        val lastWordsQuietMs: Long = 0,
        /**
         * In a real call: how long a stopped audio path waits for the report that the call has ended.
         * The end of a call shows in the audio first. 0: at once.
         */
        val audioLossGraceMs: Long = 0,
        val lastWordsPollMs: Long = 100,
        /** After the last words: how long the other side gets to answer them (「失礼します」) before the hang-up. */
        val replyWaitMs: Long = 0,
        /** Once the other side has answered: how long both must have been quiet before the hang-up. */
        val afterReplyQuietMs: Long = 0,
        /** The wait for the answer at the longest, so that talk or noise cannot keep the line open. 0: no wait at all. */
        val replyMaxMs: Long = 0,
        /** After the AI's turn ends, wait this long for the rest of its transcript (it can come later). */
        val disclosureGraceMs: Long = 2_000,
        /** After the cue to say it is an AI, at most this long until it does. */
        val disclosureCueTimeoutMs: Long = 20_000,
        /**
         * After the answer, at most this long until the AI is ready (connected and set up); then the
         * user takes over instead of the shop hearing silence until the time limit. Counts the
         * connection, so it is long enough for a slow line. Not until its first words: the AI waits
         * while the far end talks (an announcement, a hold message), which can take longer.
         */
        val aiReadyMaxMs: Long = 30_000,
        /**
         * Nobody is at the phone (a call placed at a time the user chose): when the AI cannot go on,
         * the call is not handed over. The AI says the policy's fallback line if it still can, and
         * the call is hung up without an alert. Only the user's own "AI を止めて本人に代わる" gives
         * them the call. Used with [handOverKeepsCall] false.
         */
        val unattended: Boolean = false,
        /** From asking the AI for its last line to the hang-up, at the longest. */
        val closingWordsMaxMs: Long = 15_000,
        /** Unattended: after the hang-up was asked for, the session ends by itself, whether the call did or not. */
        val hangUpGiveUpMs: Long = 15_000,
        /** Someone is at the phone: the same wait. Left to do by hand, the end may never be reported ("切った" can be forgotten). */
        val handHangUpGiveUpMs: Long = 60_000,
        /** Unattended: how long a call may ring without an answer before it is hung up. */
        val ringingMaxMs: Long = 90_000,
        /** Push tones the AI may send in one call, enough for the menus of an automatic answer. */
        val dtmfMaxSends: Int = 10,
        val dtmfMaxDigits: Int = 40,
        /** Unattended: nobody can check what is typed in, so one send is a menu choice and no more. */
        val dtmfUnattendedMaxLength: Int = 3,
        /** After 「少し待ってもらう」, another tap within this time is one tap: the AI would be cut off again. */
        val waitCueGapMs: Long = 5_000,
        /**
         * The other side hung up before the AI said how it went: how long the AI is still given to report it
         * from the conversation. 0: the result is made at once, without it.
         */
        val lastReportWaitMs: Long = 0,
    )

    enum class Phase { IDLE, DIALING, RINGING, TALKING, CLOSING, HANDED_OVER, ENDED }

    enum class EndReason {
        AI_HUNG_UP, REMOTE_HUNG_UP, USER_HUNG_UP, TIMEOUT, AI_CONNECTION_LOST, AUDIO_STOPPED,
        USER_TOOK_OVER, CALL_FAILED, NOT_ANSWERED,

        /** The AI did not say that it is an AI, even after the cue. */
        NO_AI_DISCLOSURE,
    }

    enum class Who { SHOP, AI, TOOL, INFO }

    /** One line of the conversation for the screen. Never logged. */
    data class Line(val who: Who, val text: String)

    data class Result(
        val outcome: CallOutcome,
        val violations: List<OutcomeCheck.Violation>,
        /** false: the AI did not report; [outcome] was written here from [endReason]. */
        val reportedByAi: Boolean,
        val endReason: EndReason,
        /** Why it ended, in Japanese for the screen. */
        val detail: String,
        val answered: Boolean,
        val talkMs: Long?,
        /** What the call was for: the same status reads differently for a cancel. */
        val purpose: Purpose = Purpose.NEW_BOOKING,
        /** false: it was refused before any call was placed. */
        val dialed: Boolean = true,
        /** The session ended without seeing the call end: it may still be going on. */
        val leftOpen: Boolean = false,
    )

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val status: String = "待機中",
        val dialedAt: Long? = null,
        val answeredAt: Long? = null,
        val aiReady: Boolean = false,
        val lines: List<Line> = emptyList(),
        /** Why the AI stopped and the user has the call. */
        val handover: String? = null,
        val result: Result? = null,
        /** The AI has finished and the hang-up was asked for: there is nothing left to take over from it. */
        val hangingUp: Boolean = false,
    )

    interface Listener {
        /** The AI stopped and the user must take over now: make a sound. */
        fun onAlert(reason: EndReason, text: String) {}

        /** Called exactly once per session. */
        fun onResult(result: Result) {}
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    // Control fields: only touched on [scheduler]
    private var phase = Phase.IDLE
    private var answeredAt: Long? = null
    private var timeout: CallScheduler.Cancellable? = null
    private var readyTimeout: CallScheduler.Cancellable? = null
    private var ringingLimit: CallScheduler.Cancellable? = null
    private var dtmfSends = 0
    private var dtmfDigits = 0
    private var connection: RotepConnection? = null
    private var audioStarted = false
    private var aiStopped = false
    private var aiClosed = false

    /** The audio is gone, and very likely the call with it: the AI stays, in case its report is still to be asked for. */
    private var audioLost = false

    /** The call is over and the AI was asked for its report: [pendingEnd] stands when that comes. */
    private var lastReportAsked = false
    private var pendingEnd: EndReason? = null
    private var pendingDetail = ""
    private var reported: CallOutcome? = null
    private var reportedViolations: List<OutcomeCheck.Violation> = emptyList()
    private var result: Result? = null

    /** Since when nothing has been playing, while the last words are waited for. */
    private var quietSince: Long? = null

    /** When the call began to close. */
    private var closingAt = 0L

    /** When words of the other side last came in. They come as transcript, a moment after they were said. */
    private var lastHeardAt: Long? = null

    /** Whether the AI's stop left the call to nobody (and not to the user who asked for it). */
    private val nobodyThere: Boolean get() = config.unattended && pendingEnd != EndReason.USER_TOOK_OVER

    // The AI's disclosure (also only touched on [scheduler])
    private var disclosed = false
    private val heard = StringBuilder()
    private var aiTurns = 0

    /** The AI's voice has begun in a turn that has not ended yet. */
    private var aiTurnOpen = false

    /** The words were heard in the turn now going on: if it is cut off, they may not have been played. */
    private var disclosedInThisTurn = false
    private var cueAfterTurn: Int? = null
    private var cueTimeout: CallScheduler.Cancellable? = null

    // The last line of an unattended call (also only touched on [scheduler])
    private var closingByCue = false
    private var closingTurnsToSkip = 0
    private var closingDeadline = 0L

    // The user's instructions from the call screen (also only touched on [scheduler])
    private var nextWaitCueAt = 0L
    private var declinedByUser = false
    private var agreedBeforeDecline = false

    // Only on the network thread (LiveSession's listener): what the AI's current turn has done so far
    private var audioInThisTurn = false
    private var thisTurnCut = false
    /** What evaluate_offer let through in this call: a booking reported has to be one of these. */
    private val acceptedOffers = ArrayList<AcceptedOffer>()
    /** That one was let through at all, for the scheduler's thread. */
    @Volatile private var offerAccepted = false

    // Data path: read on the audio and network threads
    @Volatile private var session: LiveSession? = null
    @Volatile private var uplinkToAi = false
    @Volatile private var agentAudio = false
    @Volatile private var warnedRate = false
    @Volatile private var firstAgentAudioSeen = false

    fun start() = scheduler.post { doStart() }

    /** "AI を止めて本人に代わる". */
    fun takeOver() = scheduler.post { if (!_state.value.hangingUp) handOver(EndReason.USER_TOOK_OVER, "本人に代わりました") }

    /** 「断って終える」「少し待ってもらう」 on the call screen: only while the AI has the call (ADR-046). */
    fun userCue(kind: UserCue) = scheduler.post { sendUserCue(kind) }

    /** Milliseconds since the far end answered (or since dialing), for the screen. */
    fun elapsedMs(): Long? = _state.value.let { it.answeredAt ?: it.dialedAt }?.let { scheduler.now() - it }

    // --- steps (on the scheduler) ---

    private fun doStart() {
        if (phase != Phase.IDLE) return
        if (!policy.isApproved) {
            finish(EndReason.CALL_FAILED, "承認されていないので、電話をかけませんでした", dialed = false)
            return
        }
        _state.update { it.copy(dialedAt = scheduler.now()) }
        setPhase(Phase.DIALING, "発信しています")
        if (config.unattended) ringingLimit = scheduler.postDelayed(config.ringingMaxMs) { onRingingTooLong() }
        control.setListener { e -> scheduler.post { onCallEvent(e) } }
        try {
            control.dial(policy.target.phoneNumber)
        } catch (t: Throwable) {
            log("dial failed ${t.javaClass.simpleName}")
            onCallEvent(CallEvent.Failed("発信できませんでした。少し待ってから、もう一度試してください"))
        }
    }

    private fun onCallEvent(e: CallEvent) {
        log("call ${e.javaClass.simpleName}${if (e is CallEvent.Ended) " reason=${e.reason}" else ""} in $phase")
        when (e) {
            CallEvent.Dialing -> if (phase == Phase.DIALING) setPhase(Phase.RINGING, "相手が出るのを待っています")
            CallEvent.Active -> if (phase == Phase.DIALING || phase == Phase.RINGING) startTalking()
            is CallEvent.Ended -> callEnded(e.reason)
            is CallEvent.Failed -> onCallFailed(e.detail)
        }
    }

    /** The call control refused to dial or gave up the call: what that means depends on how far the call got. */
    private fun onCallFailed(detail: String) {
        when (phase) {
            // Not dialed: a refusal before the call was placed (automatic control reports Dialing once it finds the
            // call, or just before it gives up a placed call it never found)
            Phase.IDLE, Phase.DIALING -> finish(EndReason.CALL_FAILED, "電話をかけられませんでした：$detail", dialed = false)
            // Dialed, and maybe still ringing at the shop: the user has to see to it
            Phase.RINGING -> {
                val text = "電話の操作ができませんでした：$detail"
                if (config.handOverKeepsCall) runCatching { listener.onAlert(EndReason.CALL_FAILED, text) }
                finish(EndReason.CALL_FAILED, text, leftOpen = true)
            }
            Phase.TALKING, Phase.CLOSING -> handOver(EndReason.CALL_FAILED, "通話の操作に失敗しました：$detail")
            // Handed over before the answer: the control may not know the call, and then no end comes.
            // Finish here (the alert already sounded; closing the control does not hang up)
            Phase.HANDED_OVER -> if (answeredAt == null || nobodyThere) {
                finish(pendingEnd ?: EndReason.CALL_FAILED, "$pendingDetail（$detail）", leftOpen = true)
            } else {
                note("通話の操作に失敗しました：$detail")
            }
            Phase.ENDED -> note("通話の操作に失敗しました：$detail")
        }
    }

    /** Nobody is at the phone, and nothing has been said yet: the call does not go on ringing. */
    private fun onRingingTooLong() {
        ringingLimit = null
        if (phase != Phase.DIALING && phase != Phase.RINGING) return
        handOver(EndReason.NOT_ANSWERED, "相手が出ないまま${duration((config.ringingMaxMs / 1000).toInt())}たったので、電話を切りました")
    }

    private fun startTalking() {
        ringingLimit?.cancel()
        ringingLimit = null
        val now = scheduler.now()
        answeredAt = now
        _state.update { it.copy(answeredAt = now) }
        setPhase(Phase.TALKING, "AI に接続しています")
        if (config.muteMicDuringAi) runCatching { control.setMuted(true) }
        val limit = policy.conversation.maxCallSeconds
        timeout = scheduler.postDelayed(limit * 1000L) {
            handOver(EndReason.TIMEOUT, "通話の上限（${duration(limit)}）になりました")
        }
        readyTimeout = scheduler.postDelayed(config.aiReadyMaxMs) { onAiNotReady() }
        uplinkToAi = true
        agentAudio = true
        audioStarted = true
        try {
            audio.start(audioListener)
        } catch (t: Throwable) {
            log("audio start failed ${t.javaClass.simpleName}")
            handOver(EndReason.AUDIO_STOPPED, UserMessages.AUDIO_START_FAILED)
            return
        }
        val s = LiveSession(policy.withFlatText().withSpokenPhrases(), today, actions, liveListener, config.model, wallClock)
        session = s
        try {
            connection = live.connect(s)
        } catch (t: Throwable) {
            handOver(EndReason.AI_CONNECTION_LOST, UserMessages.aiConnectFailed(t))
        }
    }

    private fun onAiReady() {
        readyTimeout?.cancel()
        readyTimeout = null
        if (phase != Phase.TALKING || aiStopped) return
        log("ai ready")
        _state.update { it.copy(aiReady = true) }
        setStatus("AI が話しています")
    }

    /** [Config.aiReadyMaxMs] after the answer and the AI is still not ready: the shop only hears silence. */
    private fun onAiNotReady() {
        readyTimeout = null
        if (phase != Phase.TALKING || aiStopped || _state.value.aiReady) return
        val waited = duration((config.aiReadyMaxMs / 1000).toInt())
        handOver(EndReason.AI_CONNECTION_LOST, "AI につながりませんでした（${waited}待ちました）")
    }

    /** For the call records: from the answer (manual control: the user's 「相手が出た」) to the AI's first audio. */
    private fun onFirstAgentAudio(at: Long) {
        val ms = answeredAt?.let { at - it } ?: return
        log("first agent audio $ms ms after answered")
        val tenths = (ms + 50) / 100
        note("AI が話し始めるまで ${tenths / 10}.${tenths % 10} 秒")
    }

    // --- the AI's disclosure ---

    /** A piece of the transcript of what the AI said (pieces of one sentence come one by one). */
    private fun onAiSaid(text: String) {
        if (disclosed || aiStopped) return
        heard.append(text)
        if (heard.length > HEARD_MAX) heard.delete(0, heard.length - HEARD_MAX)
        if (AiDisclosure.mentionsAi(heard, policy.target.businessName)) {
            disclosed = true
            disclosedInThisTurn = true
            cueTimeout?.cancel()
            cueTimeout = null
            log("ai disclosure heard (after $aiTurns finished turns)")
        }
    }

    /** The AI's turn ended. [spoke]: there was voice in it. [cut]: it was interrupted before it finished. */
    private fun onAiTurnComplete(spoke: Boolean, cut: Boolean) {
        aiTurnOpen = false
        val saidItJustNow = disclosedInThisTurn
        disclosedInThisTurn = false
        if (closingByCue) {
            // The turn that was going on when the cue went out is not the answer to it
            if (closingTurnsToSkip > 0) closingTurnsToSkip-- else if (spoke) waitForLastWords(closingDeadline)
            return
        }
        if (aiStopped || phase != Phase.TALKING) return
        if (saidItJustNow && (cut || !spoke)) {
            // The transcript runs ahead of the voice, and what was cut off was thrown away unplayed
            disclosed = false
            heard.clear()
            cueAfterTurn = null
            log("ai disclosure cut off")
            return
        }
        if (!spoke || disclosed) return
        aiTurns++
        if (cut && cueAfterTurn != null) {
            // Not a whole utterance, so not an answer to the cue. The time limit on the cue still applies
            cueAfterTurn = aiTurns
            return
        }
        val turn = aiTurns
        // The transcript of the turn can still arrive after its end
        scheduler.postDelayed(config.disclosureGraceMs) { checkDisclosure(turn) }
    }

    /** First utterance without "AI": the cue. The first whole utterance after the cue without it: hand over. */
    private fun checkDisclosure(turn: Int) {
        if (disclosed || aiStopped || phase != Phase.TALKING) return
        val cueAt = cueAfterTurn
        when {
            cueAt == null -> sendDisclosureCue()
            turn > cueAt -> handOver(EndReason.NO_AI_DISCLOSURE, NO_DISCLOSURE)
        }
    }

    private fun sendDisclosureCue() {
        // Turns that ended before the cue went out are not answers to it, and neither is one the AI is
        // in the middle of: it started before the cue, and it may end a moment later without the words
        cueAfterTurn = if (aiTurnOpen) aiTurns + 1 else aiTurns
        val sent = runCatching { connection?.send(AiDisclosure.cue(policy.conversation.aiDisclosure.saidInKana())) == true }
            .getOrDefault(false)
        log("ai disclosure missing: cue sent=$sent")
        if (!sent) {
            handOver(EndReason.NO_AI_DISCLOSURE, "AI が名乗らず、名乗り直すように伝えることもできませんでした")
            return
        }
        note("AI が AI であることを名乗らなかったので、名乗るように伝えました")
        cueTimeout = scheduler.postDelayed(config.disclosureCueTimeoutMs) {
            if (!disclosed && phase == Phase.TALKING) handOver(EndReason.NO_AI_DISCLOSURE, NO_DISCLOSURE)
        }
    }

    private fun onAiClosed(state: LiveSession.State, detail: String) {
        // LiveSession may report CLOSED twice (close() racing the network): only the first counts
        if (aiClosed) return
        aiClosed = true
        // The server's reason and error messages are only classified: never logged or shown
        log("ai $state ${UserMessages.liveLog(detail)}")
        if (aiStopped) return
        if (lastReportAsked) return finishAfterLastReport()
        // Whether the call goes on will show: then it is handed over for the lost audio
        if (audioLost) return
        when (phase) {
            Phase.TALKING -> handOver(
                EndReason.AI_CONNECTION_LOST,
                UserMessages.aiConnection(UserMessages.classifyLive(detail), connected = _state.value.aiReady),
            )
            Phase.CLOSING -> hangUpAfterAi()
            else -> {}
        }
    }

    private fun onAudioEvent(e: AudioPathEvent) {
        log("audio ${audio.name} ${e.javaClass.simpleName}")
        if (aiStopped || audioLost || lastReportAsked) return
        val text = UserMessages.audioPath(e, local = audio.name == LocalAudioPath.NAME) ?: return
        when (phase) {
            Phase.TALKING -> if (config.audioLossGraceMs > 0 && (e is AudioPathEvent.Stopped || e is AudioPathEvent.Failed)) {
                // Nothing can be heard any more. Only a call that goes on after this has lost its audio
                if (config.lastReportWaitMs > 0 && reported == null) stopHearing() else stopAi()
                scheduler.postDelayed(config.audioLossGraceMs) {
                    if (phase == Phase.TALKING) handOver(EndReason.AUDIO_STOPPED, text)
                }
            } else {
                handOver(EndReason.AUDIO_STOPPED, text)
            }
            Phase.CLOSING -> hangUpAfterAi() // the AI already said goodbye
            else -> {}
        }
    }

    private fun onTool(name: String, summary: String, outcome: CallOutcome?, violations: List<OutcomeCheck.Violation>) {
        log("tool $name $summary")
        addLine(Who.TOOL, "$name：$summary")
        if (outcome != null && result == null) {
            reported = outcome
            reportedViolations = violations
            if (lastReportAsked) return finishAfterLastReport()
            if (audioLost) stopAi()
            if (outcome.status == OutcomeStatus.NOT_REACHED && !policy.authority.mayLeaveVoicemail) endWithoutLastWords()
        }
    }

    /** Nobody is there, and no message may be left: whatever the AI said now would only stay on the answering machine. */
    private fun endWithoutLastWords() {
        if (phase != Phase.TALKING || closingByCue) return
        log("nobody reached: hanging up without last words")
        setPhase(Phase.CLOSING, "相手が出なかったので、何も残さずに切っています")
        hangUpAfterAi()
    }

    private fun onEndCall() {
        if (closingByCue) {
            waitForLastWords(closingDeadline)
            return
        }
        if (phase != Phase.TALKING) return
        log("end_call accepted")
        setPhase(Phase.CLOSING, "AI が締めくくっています")
        timeout?.cancel()
        closingAt = scheduler.now()
        waitForLastWords(scheduler.now() + config.lastWordsMaxMs)
    }

    private fun waitForLastWords(deadline: Long) {
        if (phase != Phase.CLOSING) return
        // Nothing more can be said: the end_call came just after the audio was lost
        if (aiStopped) return hangUpAfterAi()
        // The goodbye can reach the app after the tool call, or come with a gap in it: quiet has to last
        val now = scheduler.now()
        quietSince = if (audio.isPlaying()) null else quietSince ?: now
        val said = quietSince?.let { now - it >= config.lastWordsQuietMs } == true
        if (said || now >= deadline) {
            waitForReply(now + config.replyMaxMs)
        } else {
            scheduler.postDelayed(config.lastWordsPollMs) { waitForLastWords(deadline) }
        }
    }

    /**
     * The AI has said goodbye: the other side gets to answer, and only a moment of quiet after that
     * is the call hung up. What ends the call anyway (the other side hanging up) ends this too.
     */
    private fun waitForReply(deadline: Long) {
        if (phase != Phase.CLOSING || _state.value.hangingUp) return
        if (aiStopped || config.replyMaxMs <= 0) return hangUpAfterAi()
        val now = scheduler.now()
        // The AI may answer what it hears now
        quietSince = if (audio.isPlaying()) null else quietSince ?: now
        val aiQuiet = quietSince?.let { now - it } ?: 0
        val heard = lastHeardAt?.takeIf { it >= closingAt }
        val enough = if (heard != null) aiQuiet >= config.afterReplyQuietMs && now - heard >= config.afterReplyQuietMs else aiQuiet >= config.replyWaitMs
        if (enough || now >= deadline) {
            hangUpAfterAi()
        } else {
            scheduler.postDelayed(config.lastWordsPollMs) { waitForReply(deadline) }
        }
    }

    private fun hangUpAfterAi() {
        if (phase != Phase.CLOSING || _state.value.hangingUp) return
        stopAi()
        // An unattended call that was ended for a reason keeps that reason
        if (pendingEnd == null) {
            pendingEnd = EndReason.AI_HUNG_UP
            pendingDetail = "AI が用件を終えて電話を切りました"
        }
        setStatus("電話を切っています")
        _state.update { it.copy(hangingUp = true) }
        try {
            control.hangUp()
        } catch (t: Throwable) {
            log("hang up failed ${t.javaClass.simpleName}")
            note("電話を切れませんでした。電話の画面で切ってください")
        }
        if (config.unattended) endAtTheLatest() else endAtTheLatest(config.handHangUpGiveUpMs, onlyWhileClosing = true)
    }

    private fun onDtmf(digits: String) {
        if (phase != Phase.TALKING) return
        if (!dtmfAllowed(digits)) {
            log("dtmf refused: ${digits.length} digits")
            note("プッシュ音の上限に達したので、送りませんでした")
            return
        }
        dtmfSends++
        dtmfDigits += digits.length
        log("dtmf ${digits.length} digits")
        addLine(Who.INFO, "プッシュ音：$digits")
        try {
            control.sendDtmf(digits)
        } catch (t: Throwable) {
            log("dtmf failed ${t.javaClass.simpleName}")
            note("プッシュ音を送れませんでした")
        }
    }

    /** agent-live only checks the form of the digits. */
    private fun dtmfAllowed(digits: String): Boolean =
        dtmfSends < config.dtmfMaxSends &&
            dtmfDigits + digits.length <= config.dtmfMaxDigits &&
            (!config.unattended || digits.length <= config.dtmfUnattendedMaxLength)

    private fun handOver(reason: EndReason, text: String) {
        // The call is over: there is nothing to hand to anyone
        if (lastReportAsked) return
        when (phase) {
            Phase.DIALING, Phase.RINGING, Phase.TALKING, Phase.CLOSING -> {}
            Phase.IDLE, Phase.HANDED_OVER, Phase.ENDED -> return
        }
        // Nobody to hand it to: the AI's last line first, if it can still say one
        val noOneToHandTo = config.unattended && reason != EndReason.USER_TOOK_OVER
        if (noOneToHandTo && sayCallbackLine(reason, text)) return
        log("hand over reason=$reason")
        stopAi()
        // Before the alert, which plays on the stream that was kept quiet
        val soundBack = !audioStarted || runCatching { audio.restoreCallSound() }.getOrDefault(false)
        val shown = if (soundBack) text else "$text（通話の音が消えたままです。通話中に音量の＋を押すと聞こえます）"
        pendingEnd = reason
        pendingDetail = text
        _state.update { it.copy(handover = shown) }
        val keepsCall = config.handOverKeepsCall || (config.unattended && !noOneToHandTo)
        setPhase(Phase.HANDED_OVER, if (keepsCall) "$shown。本人に代わってください" else shown)
        if (!noOneToHandTo) runCatching { listener.onAlert(reason, text) }
        runCatching { control.setMuted(false) }
        if (!keepsCall) {
            try {
                control.hangUp()
            } catch (t: Throwable) {
                finish(reason, text, leftOpen = noOneToHandTo)
            }
        }
        if (noOneToHandTo) {
            // The control gave the call up, so it cannot hang up either: only the phone's own screen can
            if (reason == EndReason.CALL_FAILED) finish(reason, "$text。$CHECK_THE_PHONE", leftOpen = true) else endAtTheLatest()
        }
    }

    /**
     * Asks the AI to say the fallback line and end the call; the session then goes on through
     * [Phase.CLOSING] and keeps [reason]. false when there is no one to say it or nobody would hear
     * it: only a limit that was reached leaves the AI and the line in working order.
     */
    private fun sayCallbackLine(reason: EndReason, text: String): Boolean {
        if (phase != Phase.TALKING || aiStopped || !_state.value.aiReady) return false
        if (reason != EndReason.TIMEOUT && reason != EndReason.NO_AI_DISCLOSURE) return false
        val cue = AiDisclosure.callbackCue(policy.conversation.fallbackPhrase.saidInKana(), named = disclosed)
        val sent = runCatching { connection?.send(cue) == true }.getOrDefault(false)
        log("unattended: last line asked for, sent=$sent")
        if (!sent) return false
        closeByCue(reason, text, "折り返しを伝えています")
        return true
    }

    /** The AI was just asked for its last words: the call ends with them, or at [Config.closingWordsMaxMs]. */
    private fun closeByCue(reason: EndReason, text: String, status: String) {
        pendingEnd = reason
        pendingDetail = text
        closingByCue = true
        closingTurnsToSkip = if (aiTurnOpen) 1 else 0
        closingDeadline = scheduler.now() + config.closingWordsMaxMs
        closingAt = scheduler.now()
        timeout?.cancel()
        timeout = null
        setPhase(Phase.CLOSING, status)
        scheduler.postDelayed(config.closingWordsMaxMs) { hangUpAfterAi() }
    }

    private fun sendUserCue(kind: UserCue) {
        if (phase != Phase.TALKING || aiStopped || !_state.value.aiReady) return
        val now = scheduler.now()
        if (kind == UserCue.ASK_TO_WAIT && now < nextWaitCueAt) return
        val sent = runCatching { connection?.send(AiDisclosure.userCue(kind, named = disclosed, purpose = policy.purpose)) == true }.getOrDefault(false)
        log("user cue ${kind.name} sent=$sent")
        if (!sent) {
            note("AI に伝えられませんでした")
            return
        }
        note("本人の指示：${kind.label}")
        when (kind) {
            UserCue.ASK_TO_WAIT -> nextWaitCueAt = now + config.waitCueGapMs
            UserCue.DECLINE_AND_END -> {
                declinedByUser = true
                agreedBeforeDecline = offerAccepted || reported?.status.let {
                    it == OutcomeStatus.BOOKED || it == OutcomeStatus.BOOKED_ALTERNATIVE || it == OutcomeStatus.DONE
                }
                closeByCue(EndReason.AI_HUNG_UP, DECLINED_BY_USER, "お断りして電話を終えています")
            }
        }
    }

    /** However the hang-up goes, the session ends, so that later calls are not refused. */
    private fun endAtTheLatest(afterMs: Long = config.hangUpGiveUpMs, onlyWhileClosing: Boolean = false) {
        scheduler.postDelayed(afterMs) {
            // Handed over to someone at the phone in the meantime: the call is theirs, for as long as they have it
            if (onlyWhileClosing && phase != Phase.CLOSING) return@postDelayed
            finish(pendingEnd ?: EndReason.AI_HUNG_UP, "$pendingDetail。$CHECK_THE_PHONE", leftOpen = true)
        }
    }

    private fun callEnded(reason: String) {
        if (lastReportAsked) return
        when (phase) {
            Phase.IDLE, Phase.DIALING, Phase.RINGING ->
                finish(EndReason.NOT_ANSWERED, "相手が出ないまま通話が終わりました${DisconnectReasons.inWords(reason)}")
            Phase.TALKING -> {
                val detail = "AI が話している間に通話が終わりました${DisconnectReasons.inWords(reason)}"
                when {
                    DisconnectReasons.endedHere(reason) -> finish(EndReason.USER_HUNG_UP, detail)
                    !askForLastReport(detail) -> finish(EndReason.REMOTE_HUNG_UP, detail)
                }
            }
            Phase.CLOSING -> finish(pendingEnd ?: EndReason.AI_HUNG_UP, pendingDetail.ifEmpty { "AI が用件を終えて電話を切りました" })
            Phase.HANDED_OVER -> finish(pendingEnd ?: EndReason.USER_TOOK_OVER, pendingDetail)
            Phase.ENDED -> {}
        }
    }

    /**
     * The other side hung up before the AI said how it went, and what was agreed is only in its conversation. It is
     * asked for its report, and the session ends when that comes or after [Config.lastReportWaitMs]. false: it cannot be asked.
     */
    private fun askForLastReport(detail: String): Boolean {
        if (config.lastReportWaitMs <= 0 || reported != null || aiStopped || aiClosed) return false
        val sent = runCatching { connection?.sendAfterTheCall(AiDisclosure.lastReportCue()) == true }.getOrDefault(false)
        log("hung up before the report: asked for it, sent=$sent")
        if (!sent) return false
        stopHearing()
        if (audioStarted) runCatching { audio.stop() }
        timeout?.cancel()
        timeout = null
        readyTimeout?.cancel()
        readyTimeout = null
        pendingEnd = EndReason.REMOTE_HUNG_UP
        pendingDetail = detail
        lastReportAsked = true
        setPhase(Phase.CLOSING, "通話が終わりました。結果をまとめています")
        scheduler.postDelayed(config.lastReportWaitMs) { finishAfterLastReport() }
        return true
    }

    private fun finishAfterLastReport() {
        if (!lastReportAsked) return
        lastReportAsked = false
        finish(pendingEnd ?: EndReason.REMOTE_HUNG_UP, pendingDetail)
    }

    /** Nothing can be heard or said any more, but the AI stays. */
    private fun stopHearing() {
        audioLost = true
        uplinkToAi = false
        agentAudio = false
    }

    /** Stops the AI and the audio path. Safe to call more than once. */
    private fun stopAi() {
        if (aiStopped) return
        aiStopped = true
        uplinkToAi = false
        agentAudio = false
        timeout?.cancel()
        timeout = null
        readyTimeout?.cancel()
        readyTimeout = null
        ringingLimit?.cancel()
        ringingLimit = null
        cueTimeout?.cancel()
        cueTimeout = null
        session?.let { runCatching { it.close() } }
        connection?.let { runCatching { it.cancel() } }
        if (audioStarted) runCatching { audio.stop() }
    }

    private fun finish(reason: EndReason, detail: String, dialed: Boolean = true, leftOpen: Boolean = false) {
        if (result != null) return
        stopAi()
        // Once the user has declined, only "not taken" stands. And when something may have been agreed
        // before that, the app cannot know that the shop took it back: the user is told to check
        val mayStillStand = declinedByUser && agreedBeforeDecline
        // Nothing went wrong, and only the user knows how it ended. A practice has no shop to look into
        val stoppedByUser = !declinedByUser && (config.handOverKeepsCall || config.unattended) &&
            (reason == EndReason.USER_TOOK_OVER || reason == EndReason.USER_HUNG_UP)
        val ai = reported?.takeUnless { declinedByUser && (mayStillStand || it.status != OutcomeStatus.UNAVAILABLE) }
        val outcome = (ai ?: CallOutcome(
            policyId = policy.id,
            status = when {
                reason == EndReason.NOT_ANSWERED -> OutcomeStatus.NOT_REACHED
                mayStillStand || stoppedByUser -> OutcomeStatus.NEEDS_USER_DECISION
                declinedByUser -> OutcomeStatus.UNAVAILABLE
                else -> OutcomeStatus.FAILED
            },
            pendingItems = when {
                mayStillStand -> listOf(AGREED_BEFORE_DECLINE)
                stoppedByUser -> listOf(STOPPED_BY_USER)
                else -> emptyList()
            },
            summary = when {
                mayStillStand -> "$DECLINED_BY_USER。$AGREED_BEFORE_DECLINE"
                !dialed -> detail
                else -> "AI からの結果の報告はありません。$detail"
            },
            endedAt = wallClock(),
        )).let {
            // Nothing here saw the call end: this comes first on the result screen
            if (leftOpen) it.copy(pendingItems = listOf("$URGENT_MARK：$CHECK_THE_PHONE") + it.pendingItems) else it
        }
        val violations = if (ai != null) reportedViolations else OutcomeCheck.check(policy, outcome)
        val r = Result(
            outcome = outcome,
            violations = violations,
            reportedByAi = ai != null,
            endReason = reason,
            detail = detail,
            answered = answeredAt != null,
            talkMs = answeredAt?.let { scheduler.now() - it },
            purpose = policy.purpose,
            dialed = dialed,
            leftOpen = leftOpen,
        )
        result = r
        log("result status=${outcome.status} reportedByAi=${ai != null} reason=$reason violations=${violations.size}")
        _state.update { it.copy(result = r) }
        setPhase(Phase.ENDED, "終わりました")
        runCatching { control.close() }
        runCatching { listener.onResult(r) }
    }

    // --- callbacks from other threads: data goes straight on, control steps are posted ---

    private val audioListener = object : CallAudioPath.Listener {
        /**
         * Half-duplex silence, kept for the next chunk of the same size instead of a new array every
         * chunk. Only ever zeros and never written, so sharing it is safe; sendAudio does not keep it.
         */
        private var silence = ByteArray(0)

        override fun onDownlink(pcm16k: ByteArray) {
            if (!uplinkToAi) return
            val s = session ?: return
            s.sendAudio(if (config.halfDuplex && audio.isPlaying()) silenceOf(pcm16k.size) else pcm16k)
        }

        private fun silenceOf(size: Int): ByteArray {
            if (silence.size != size) silence = ByteArray(size)
            return silence
        }

        override fun onEvent(event: AudioPathEvent) = scheduler.post { onAudioEvent(event) }
    }

    private val liveListener = object : LiveSession.Listener {
        override fun onState(state: LiveSession.State, detail: String) {
            when (state) {
                LiveSession.State.READY -> scheduler.post { onAiReady() }
                LiveSession.State.CLOSED, LiveSession.State.FAILED -> scheduler.post { onAiClosed(state, detail) }
                LiveSession.State.CONNECTING, LiveSession.State.SETTING_UP -> {}
            }
        }

        override fun onEvent(event: ServerEvent) {
            when (event) {
                is ServerEvent.Audio -> if (agentAudio) {
                    if (!audioInThisTurn) {
                        audioInThisTurn = true
                        scheduler.post { aiTurnOpen = true }
                    }
                    if (!firstAgentAudioSeen) {
                        firstAgentAudioSeen = true
                        val at = scheduler.now()
                        scheduler.post { onFirstAgentAudio(at) }
                    }
                    if (event.sampleRate != AGENT_RATE && !warnedRate) {
                        warnedRate = true
                        log("WARNING agent audio rate ${event.sampleRate} Hz (expected $AGENT_RATE)")
                    }
                    audio.play(event.pcm)
                }
                ServerEvent.Interrupted -> {
                    thisTurnCut = true
                    if (agentAudio) audio.flush()
                }
                is ServerEvent.InputTranscript -> {
                    addLine(Who.SHOP, event.text)
                    if (event.text.isNotBlank()) scheduler.post { lastHeardAt = scheduler.now() }
                }
                is ServerEvent.OutputTranscript -> {
                    addLine(Who.AI, event.text)
                    val text = event.text
                    scheduler.post { onAiSaid(text) }
                }
                ServerEvent.TurnComplete -> {
                    val spoke = audioInThisTurn
                    val cut = thisTurnCut
                    audioInThisTurn = false
                    thisTurnCut = false
                    scheduler.post { onAiTurnComplete(spoke, cut) }
                }
                is ServerEvent.GoAway -> scheduler.post { note("まもなく AI との接続が切れます") }
                else -> {}
            }
        }

        override fun onToolCall(call: FunctionCall, response: FunctionResponse) {
            // Runs on the same thread as the tool handler, so its outcome is safe to read here
            val handler = session?.toolHandler
            if (call.name == LiveTools.EVALUATE_OFFER && (response.response["accept"] as? JsonPrimitive)?.booleanOrNull == true) {
                acceptedOffers += AcceptedOffer.of(call.args)
                offerAccepted = true
            }
            val outcome = if (call.name == LiveTools.REPORT_RESULT) handler?.outcome else null
            val violations = (handler?.outcomeViolations ?: emptyList()) + noOfferBehind(outcome)
            val summary = summary(call, response.response)
            // The name comes from the model: only the ones the app declared are written down
            val name = call.name.takeIf { it in LiveTools.NAMES } ?: UNKNOWN_TOOL
            scheduler.post { onTool(name, summary, outcome, violations) }
        }
    }

    /**
     * A booking the AI reports without any offer that passed the check against the policy: what it
     * says may be right, but it did not ask first, so the user is asked to look (agent-live does not tie the two).
     */
    private fun noOfferBehind(outcome: CallOutcome?): List<OutcomeCheck.Violation> {
        val booked = outcome?.status == OutcomeStatus.BOOKED || outcome?.status == OutcomeStatus.BOOKED_ALTERNATIVE
        if (!booked || outcome == null) return emptyList()
        if (acceptedOffers.isEmpty()) return listOf(OutcomeCheck.Violation("booking", "お店の提案を範囲と照らさずに、予約できたと報告しました"))
        val booking = outcome.booking ?: return emptyList() // OutcomeCheck says that one
        if (acceptedOffers.any { it.covers(booking) }) return emptyList()
        return listOf(OutcomeCheck.Violation("booking", "受けてよいと確かめた提案と、報告された日時か人数が違います"))
    }

    /** One offer evaluate_offer accepted, read the way ToolCallHandler reads it. */
    private class AcceptedOffer(val start: LocalDateTime?, val partySize: Int?) {
        /** A size that was not said on one side is no difference. */
        fun covers(booking: ConfirmedBooking): Boolean =
            start == booking.start && (partySize == null || booking.partySize == null || partySize == booking.partySize)

        companion object {
            private val LOCAL_DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?")

            fun of(args: JsonObject): AcceptedOffer = AcceptedOffer(
                start = (args["start"] as? JsonPrimitive)?.contentOrNull
                    ?.let { LOCAL_DATE_TIME.find(it)?.value?.replace(' ', 'T') }
                    ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                partySize = (args["partySize"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() },
            )
        }
    }

    private val actions = object : CallActions {
        override fun endCall(reason: String) = scheduler.post { onEndCall() }

        override fun sendDtmf(digits: String) = scheduler.post { onDtmf(digits) }
    }

    // --- helpers ---

    private fun setPhase(p: Phase, status: String) {
        if (phase != p) log("phase $phase -> $p")
        phase = p
        _state.update { it.copy(phase = p, status = status) }
    }

    private fun setStatus(status: String) = _state.update { it.copy(status = status) }

    private fun note(text: String) = addLine(Who.INFO, text)

    /** Transcripts arrive in pieces: join them to the last line of the same speaker. */
    private fun addLine(who: Who, text: String) {
        if (text.isEmpty()) return
        _state.update { st ->
            val last = st.lines.lastOrNull()
            val lines = if ((who == Who.SHOP || who == Who.AI) && last != null && last.who == who) {
                st.lines.dropLast(1) + last.copy(text = last.text + text)
            } else {
                st.lines + Line(who, text.trimStart())
            }
            st.copy(lines = lines.takeLast(MAX_LINES))
        }
    }

    companion object {
        const val AGENT_RATE = 24_000
        private const val MAX_LINES = 200
        private const val HEARD_MAX = 400
        private const val UNKNOWN_TOOL = "(unknown tool)"
        private const val STOPPED_BY_USER = "本人が途中で止めたので、お店との話がどこまで進んだかはアプリには分かりません。予約や変更が残っていないか確かめてください"
        private const val NO_DISCLOSURE = "AI が、AI であることを名乗りませんでした"
        private const val CHECK_THE_PHONE = "電話が切れていないかもしれません。電話の画面で確かめて、切ってください"

        /** "accept=true", "ok=true status=BOOKED"... (no free text from the model). */
        fun summary(call: FunctionCall, response: JsonObject): String {
            fun flag(key: String) = (response[key] as? JsonPrimitive)?.booleanOrNull
            val parts = ArrayList<String>()
            flag("accept")?.let { parts += "accept=$it" }
            flag("ok")?.let { parts += "ok=$it" }
            if (call.name == LiveTools.REPORT_RESULT) {
                (call.args["status"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { status -> OutcomeStatus.entries.any { it.name == status } }
                    ?.let { parts += "status=$it" }
            }
            return parts.joinToString(" ").ifEmpty { "-" }
        }

        fun duration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return when {
                m == 0 -> "${s}秒"
                s == 0 -> "${m}分"
                else -> "${m}分${s}秒"
            }
        }
    }
}
