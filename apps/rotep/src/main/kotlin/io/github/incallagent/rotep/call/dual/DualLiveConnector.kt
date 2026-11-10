// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.FunctionCall
import io.github.incallagent.agent.live.FunctionResponse
import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveSession
import io.github.incallagent.agent.live.LiveTools
import io.github.incallagent.agent.live.LiveTransport
import io.github.incallagent.agent.live.ServerEvents
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.CallScheduler
import io.github.incallagent.rotep.call.ExecutorScheduler
import io.github.incallagent.rotep.call.LiveConnector
import io.github.incallagent.rotep.call.RotepConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.thread

/**
 * Shows [LiveSession] and CallSession one Gemini Live connection that is really three parts (ADR-053):
 * the ears (a voice model that only writes down the other side), the thinking model that decides
 * everything that is said and done, and the mouth (a voice model that reads out what it is given).
 *
 * Every event goes through one queue ([scheduler]), so that [io.github.incallagent.agent.live.ToolCallHandler]
 * and all that is here stay on one thread. Only the audio of the other side goes straight to the ears.
 * What is decided stays with the app's code: tools run through the session, as they always did.
 */
class DualLiveConnector(
    private val auth: LiveAuth,
    private val brain: CallBrain,
    private val sockets: VoiceSockets,
    private val spoken: DualSpoken,
    private val log: (String) -> Unit,
    private val scheduler: CallScheduler? = null,
    /** Off in a real call: the final words come within 0.4 s of the interim ones, so this only asked Claude many times for nothing (ADR-053). */
    private val earlyThink: Boolean = false,
) : LiveConnector {
    override fun connect(session: LiveSession): RotepConnection {
        val queue = scheduler ?: ExecutorScheduler("dual-call", onError = { log("step failed: ${it.javaClass.simpleName}") })
        return DualCall(session, auth, brain, sockets, spoken, log, queue, earlyThink).also { it.begin() }
    }

    internal companion object {
        /** The other side has stopped for this long: what they said is complete. */
        const val SHOP_SETTLE_MS = 350L

        /** An answer was taken back, so they are still talking: how long it has to stay quiet before the next question. */
        const val QUIET_AFTER_TAKEN_BACK_MS = 1_000L

        /**
         * How long after its estimated end a fixed line may still be playing, held back by the track and its thread.
         * Talked over in that moment, the sound would be dropped with the end of the line in it.
         */
        const val FIXED_LINE_MARGIN_MS = 1_500L

        /** Interim words that have not changed for this long are thought about without waiting for the final ones. */
        const val EARLY_THINK_MS = 450L

        /** Words only ever written as interim ones: this long after the last of them, they are taken as said. */
        const val INTERIM_ONLY_MS = 1_200L

        /** Longer than the thinking model's own limits add up to (25 s, tried once more): only an answer that never comes gets here. */
        const val BRAIN_MAX_MS = 60_000L

        /** After the end of what they said, with no answer yet. */
        const val FILLER_AFTER_MS = 900L
        const val LONG_WAIT_MS = 5_000L
        const val FILLER = "はい。"
        const val LONG_WAIT = "しょうしょうお待ちください。"

        /** A line the mouth never finishes must not hold up what comes after it. */
        const val LINE_MAX_MS = 30_000L

        /**
         * A line has had its voice, the voice has stopped, and the mouth has not said that the line ended: this long
         * after it should have been heard to the end, it is over. In a real call that end did not come, and what
         * was to be said next waited in silence. A line with no voice yet has all of [LINE_MAX_MS].
         */
        const val LINE_END_WAIT_MS = 2_000L

        /**
         * The line sent after one that was given up on: the voice that comes first may be what was left of the
         * older line, and this line's own can take more than 2 s to start. For this long it is not given up on.
         */
        const val AFTER_GIVING_UP_MS = 4_000L

        /**
         * An end came and the line going out has no voice: it is the late end of the line given up on, or this
         * line's own with nothing in it. They cannot be told apart, so its voice is waited for, but not for [LINE_MAX_MS].
         */
        const val ONLY_AN_END_MS = 8_000L

        const val MIN_INTERRUPT_CHARS = 4
    }
}

private enum class LineKind { DISCLOSURE, SAY, FILLER, FALLBACK }

private class Line(val kind: LineKind, val text: String) {
    /** The app's own words, which are heard to the end. */
    val fixed: Boolean get() = kind == LineKind.DISCLOSURE || kind == LineKind.FALLBACK
}

/** A line whose voice has all come and may still be playing: the mouth makes it faster than it is heard. */
private class PlayingOut(val line: Line, val endsAt: Long)

/** One question to the thinking model. [shopText]: what of the other side's words it was given. */
private class Thinking(val cancel: () -> Unit, val input: BrainInput, var shopText: String) {
    /** The sentences of the answer so far. */
    val linesOut = ArrayList<Line>()
}

private const val SETUP_COMPLETE = """{"setupComplete":{}}"""
private const val INTERRUPTED = """{"serverContent":{"interrupted":true}}"""
private const val TURN_COMPLETE = """{"serverContent":{"turnComplete":true}}"""

/** The audio of the other side, about 25 times a second: told apart without parsing it. */
private const val AUDIO_PREFIX = "{\"realtimeInput\""

private val IGNORED_IN_WORDS = Regex("[\\s、。，．,.!！?？…・]")

/** What people say while listening. They do not stop the AI's line, and do not need an answer while it talks. */
private val NODS = setOf(
    "はい", "はいはい", "はいはいはい", "はあ", "ええ", "ええええ", "うん", "うんうん", "ああ", "あー", "そう",
    "そうです", "そうですね", "そうですか", "なるほど", "わかりました", "分かりました", "承知しました", "かしこまりました",
    "ありがとうございます", "ありがとうございました", "失礼します", "失礼いたします", "失礼致します", "しつれいします", "しつれいいたします",
    "お願いします", "おねがいします", "よろしくお願いします", "よろしくおねがいします",
)

private val NODS_LONGEST_FIRST = NODS.sortedByDescending { it.length }

private fun plainWords(text: String) = text.replace(IGNORED_IN_WORDS, "")

private fun sameWords(a: String, b: String) = plainWords(a) == plainWords(b)

/** What is left once the nods it begins with are taken off: 「はい、失礼します」 is two of them. */
private fun afterNods(words: String): String {
    var rest = words
    while (true) {
        val nod = NODS_LONGEST_FIRST.firstOrNull { rest.startsWith(it) } ?: return rest
        rest = rest.removePrefix(nod)
    }
}

private fun isNod(text: String): Boolean {
    val words = plainWords(text)
    return words.isNotEmpty() && afterNods(words).isEmpty()
}

/**
 * Enough of another sentence to mean that they are talking over the AI. Not a nod, and not the
 * beginning of one either: the words come in a little at a time, 「ありがと」 before 「ありがとうございます」.
 */
private fun isSubstantial(text: String): Boolean {
    val words = plainWords(text)
    if (words.length < DualLiveConnector.MIN_INTERRUPT_CHARS) return false
    val rest = afterNods(words)
    return rest.isNotEmpty() && NODS.none { it.startsWith(rest) }
}

private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.textOf(key: String): String? =
    ((this[key] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** How long the audio in [content] takes to play (16-bit samples at the rate it says, 24 kHz if it does not). */
private fun voiceMs(content: JsonObject): Long {
    val parts = (content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray ?: return 0
    return parts.sumOf { part ->
        val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: return@sumOf 0L
        val data = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: return@sumOf 0L
        val mime = (inline["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val bytes = data.length / 4 * 3L - data.takeLast(2).count { it == '=' }
        bytes * 1_000 / (ServerEvents.rateOf(mime) * 2L)
    }
}

/** The same message, for a turn that has been counted as ended already. */
private fun withoutTheEnd(content: JsonObject) = buildJsonObject {
    put("serverContent", JsonObject(content - "turnComplete"))
}.toString()

private fun heardMessage(text: String) = buildJsonObject {
    putJsonObject("serverContent") { putJsonObject("inputTranscription") { put("text", text) } }
}.toString()

private fun toolCallMessage(call: FunctionCall) = buildJsonObject {
    putJsonObject("toolCall") {
        putJsonArray("functionCalls") {
            add(
                buildJsonObject {
                    call.id?.let { put("id", it) }
                    put("name", call.name)
                    put("args", call.args)
                },
            )
        }
    }
}.toString()

private fun notDone(reason: String) = buildJsonObject {
    put("ok", false)
    put("error", reason)
}

private class DualCall(
    private val session: LiveSession,
    private val auth: LiveAuth,
    private val brain: CallBrain,
    private val sockets: VoiceSockets,
    private val spoken: DualSpoken,
    private val log: (String) -> Unit,
    private val scheduler: CallScheduler,
    private val earlyThink: Boolean,
) : RotepConnection {
    /** What LiveSession takes for the network. */
    private val transport = object : LiveTransport {
        override fun send(text: String): Boolean = fromSession(text)

        override fun close(code: Int, reason: String) = cancel()
    }

    @Volatile private var ear: VoiceSocket? = null
    @Volatile private var mouth: VoiceSocket? = null

    /** No more of anything: read also on other threads. */
    @Volatile private var closed = false

    /** Told the session that both sides are ready. */
    @Volatile private var announced = false

    // Everything below: only in the queue
    private var earReady = false
    private var mouthReady = false
    private var tornDown = false

    private val lines = ArrayDeque<Line>()
    private var speaking: Line? = null

    /** The rest of the line going out is dropped until its end: the other side talked over it. */
    private var cutting = false
    private var lineTimer: CallScheduler.Cancellable? = null

    /** Set once the line has had voice: ends it if the mouth never says that it ended. */
    private var endWatch: CallScheduler.Cancellable? = null

    /** The end of the last line given up on may still come, and is not the end of the line going out. An older one's could only have come before that line's voice. */
    private var endOwed = false

    /** That end came after this line had voice, and no voice has come since. It may well have been this line's own, so this line owes none. */
    private var endMayHaveCome = false

    /** Until then the line going out is not given up on, whatever voice has come. */
    private var notGivenUpBefore = 0L

    /** By the clock, where the voice that has arrived ends. */
    private var voiceEndsAt = 0L
    private var lineHasVoice = false
    private val playingOut = ArrayDeque<PlayingOut>()

    /** Which question counts: an answer to an older one is dropped. */
    private var generation = 0
    private var thinking: Thinking? = null
    private var openingAgain = false

    /** An answer was taken back and none has come since: the other side is still talking. */
    private var stillTalking = false

    /** Went wrong: the fixed line, then the end. */
    private var failing = false

    /** The other side has hung up: only the report is still wanted, and nothing is read out. */
    private var callOver = false

    /** What the other side said that no finished question has seen. */
    private var unanswered = ""
    private var interim = ""

    /** How much of [interim] had stopped before: noise that stops does not add up to a sentence. */
    private var interimStopped = 0
    private var interimTimer: CallScheduler.Cancellable? = null
    private var earlyTimer: CallScheduler.Cancellable? = null
    private var lastInterimAt: Long? = null

    /** Interim words the thinking model already has (in [unanswered] until answered): the final ones decide if they stand. */
    private var earlyWords: String? = null

    /** Words taken from the interim ones and shown: the ears may still make them final, late. */
    private var promoted: String? = null
    private var cutOff: String? = null
    private var settleTimer: CallScheduler.Cancellable? = null

    private val notes = ArrayDeque<String>()

    /** A note from the app has not had its line yet: the session would take any line for that one. */
    private var noteOpen = false

    private val toolCalls = ArrayDeque<FunctionCall>()
    private val collected = ArrayList<FunctionResponse>()

    /** The answers of tools that no finished question has seen. */
    private var heldResults: List<FunctionResponse>? = null

    /** When the other side stopped, while they wait for an answer. */
    private var waitingSince: Long? = null
    private var fillerSaid = false
    private var longWaitSaid = false
    private var fillerTimer: CallScheduler.Cancellable? = null
    private var longWaitTimer: CallScheduler.Cancellable? = null

    // For the log: numbers only
    private var answerMs: Long? = null
    private var answeredAt = 0L
    private var finalLagMs: Long? = null
    private var takenBack = 0

    private val over: Boolean get() = closed || failing || session.toolHandler.ended

    private val mouthIdle: Boolean get() = speaking == null && lines.isEmpty()

    /** The goodbye is going out and the hang-up comes after it: it is not cut, and the hang-up's tool use stays open. */
    private val hangUpWaiting: Boolean get() = toolCalls.any { it.name == LiveTools.END_CALL }

    fun begin() = later { session.onOpen(transport) }

    private fun later(step: () -> Unit) = scheduler.post { guarded(step) }

    private fun afterDelay(ms: Long, step: () -> Unit) = scheduler.postDelayed(ms) { guarded(step) }

    /** A step that throws would leave the other side listening to silence: the AI part ends, and the session hands over. */
    private fun guarded(step: () -> Unit) {
        if (closed) return
        try {
            step()
        } catch (t: Throwable) {
            log("step failed: ${t.javaClass.simpleName}")
            runCatching { session.onFailure(STEP_FAILED) }
            end()
        }
    }

    // --- what LiveSession sends ---

    private fun fromSession(text: String): Boolean {
        if (text.startsWith(AUDIO_PREFIX)) return ear?.send(text) ?: false
        if (closed) return false
        // From here on only from inside session.onOpen and session.onMessage: in the queue
        val message = parse(text) ?: return false
        when {
            "setup" in message -> openSockets()
            "clientContent" in message -> opening()
            "toolResponse" in message -> collect(message)
        }
        return true
    }

    // --- what CallSession sends and does ---

    override fun cancel() {
        closed = true
        scheduler.post { tearDown() }
    }

    /** A cue in the app's words (a button of the user, the time limit): for the thinking model. false: not one it can take. */
    override fun send(text: String): Boolean {
        if (closed || !announced) return false
        val note = CallBrainPrompt.noteFromCue(text) ?: return false
        later { onNote(note) }
        return true
    }

    override fun sendAfterTheCall(text: String): Boolean {
        if (closed || !announced) return false
        val note = CallBrainPrompt.noteFromCue(text) ?: return false
        later {
            callOver = true
            lines.clear()
            stopWaiting()
            onNote(note)
        }
        return true
    }

    // --- the two sockets ---

    private fun openSockets() {
        mouth = sockets.open(auth, Side("mouth", { mouth?.send(VoiceSetup.mouth(spoken.voiceName).toString()) }, ::onMouthMessage))
        ear = sockets.open(auth, Side("ear", { ear?.send(VoiceSetup.ear().toString()) }, ::onEarMessage))
    }

    /** The log says which one ([name]) and what kind of trouble: the server's words are only classified, never written. */
    private inner class Side(private val name: String, private val opened: () -> Unit, private val message: (String) -> Unit) : VoiceSocket.Listener {
        override fun onOpen() = later { opened() }

        override fun onMessage(text: String) = later { message(text) }

        override fun onClosed(code: Int, reason: String) = later {
            log("$name closed ${UserMessages.liveLog("closed code=$code reason=$reason")}")
            session.onClosed(code, reason)
            end()
        }

        override fun onFailure(detail: String) = later {
            log("$name failed ${UserMessages.liveLog(detail)}")
            session.onFailure(detail)
            end()
        }
    }

    private fun bothReady() {
        if (announced || !earReady || !mouthReady) return
        announced = true
        log("both voice sockets ready")
        // Its own thread: nothing may wait for this
        thread(name = "dual-warm-up", isDaemon = true) {
            runCatching { brain.warmUp() }
        }
        session.onMessage(SETUP_COMPLETE)
    }

    /** The disclosure is the app's own words: it does not wait for the thinking model. */
    private fun opening() {
        lines += Line(LineKind.DISCLOSURE, spoken.disclosure)
        pumpMouth()
        think(BrainInput.Opening, "")
    }

    // --- the mouth ---

    private fun onMouthMessage(text: String) {
        val message = parse(text) ?: return
        if ("setupComplete" in message) {
            log("mouth ready")
            mouthReady = true
            return bothReady()
        }
        if ("goAway" in message) session.onMessage(text)
        val content = message["serverContent"] as? JsonObject ?: return
        val ended = content.flag("turnComplete")
        val oldEnd = ended && endOwed
        if (oldEnd) {
            endOwed = false
            log("the end of a line given up on came: voice $lineHasVoice")
            // With voice, it may have been this line's own. With none, this line's own may have had nothing in it
            if (lineHasVoice) endMayHaveCome = true else if (speaking != null) watchForTheEnd()
        }
        val finished = ended && !oldEnd
        if (cutting) {
            if (voiceMs(content) > 0) {
                lineHasVoice = true
                endMayHaveCome = false
                watchForTheEnd()
            }
            if (finished) {
                cutting = false
                session.onMessage(TURN_COMPLETE)
                lineFinished()
            }
            return
        }
        // Nothing was asked of it: a voice of its own is not for the other side. Its written words can still come after a line
        if (speaking == null && voiceMs(content) > 0) return
        noteTheVoice(content)
        session.onMessage(if (oldEnd) withoutTheEnd(content) else text)
        if (finished) lineFinished()
    }

    private fun noteTheVoice(content: JsonObject) {
        val voice = voiceMs(content)
        if (voice > 0) {
            lineHasVoice = true
            endMayHaveCome = false
            voiceEndsAt = maxOf(voiceEndsAt, scheduler.now()) + voice
            watchForTheEnd()
        }
        val ms = answerMs ?: return
        if (speaking?.kind != LineKind.SAY || voice == 0L) return
        answerMs = null
        log("turn: end of the other side -> answer $ms ms -> voice ${scheduler.now() - answeredAt} ms, last interim -> final ${finalLagMs ?: -1} ms, taken back $takenBack")
        takenBack = 0
    }

    /** The voice having stopped ends the line if the mouth never does: put off by every piece of voice that comes. */
    private fun watchForTheEnd() {
        endWatch?.cancel()
        val now = scheduler.now()
        val at = when {
            cutting -> now + DualLiveConnector.LINE_END_WAIT_MS
            !lineHasVoice -> now + DualLiveConnector.ONLY_AN_END_MS
            else -> maxOf(heardBy() + DualLiveConnector.LINE_END_WAIT_MS, notGivenUpBefore)
        }
        endWatch = afterDelay(at - now) { giveUp("the voice of a line stopped and its end did not come") }
    }

    /** By the clock, when what has come of the line going out has surely been heard. */
    private fun heardBy() = voiceEndsAt + if (speaking?.fixed == true) DualLiveConnector.FIXED_LINE_MARGIN_MS else 0

    /** The mouth has not said that the line ended, and it is over all the same: the session hears what the mouth would have said. */
    private fun giveUp(why: String) {
        if (!endMayHaveCome) endOwed = true
        log("$why: ${speaking?.kind}, cut $cutting, end owed $endOwed")
        session.onMessage(TURN_COMPLETE)
        lineFinished()
    }

    private fun lineFinished() {
        lineTimer?.cancel()
        lineTimer = null
        endWatch?.cancel()
        endWatch = null
        val done = speaking
        val heardUntil = voiceEndsAt + if (done?.fixed == true && lineHasVoice) DualLiveConnector.FIXED_LINE_MARGIN_MS else 0
        if (done != null && lineHasVoice && !cutting) playingOut += PlayingOut(done, heardUntil)
        lineHasVoice = false
        cutting = false
        speaking = null
        if (done?.kind == LineKind.FALLBACK) {
            // Once it has been heard: told now, the session would stop the sound at once
            afterDelay((heardUntil - scheduler.now()).coerceAtLeast(0)) {
                session.onClosed(BRAIN_FAILED_CODE, BRAIN_FAILED)
                end()
            }
            return
        }
        pumpMouth()
        if (mouthIdle) pumpTools()
    }

    private fun pumpMouth() {
        if (speaking != null || closed) return
        val next = lines.removeFirstOrNull() ?: return
        if (mouth?.send(VoiceSetup.speak(next.text).toString()) != true) {
            session.onClosed(BRAIN_FAILED_CODE, VOICE_LOST)
            return end()
        }
        speaking = next
        endMayHaveCome = false
        notGivenUpBefore = if (endOwed) scheduler.now() + DualLiveConnector.AFTER_GIVING_UP_MS else 0
        lineTimer = afterDelay(DualLiveConnector.LINE_MAX_MS) { giveUp("a line was not finished in time") }
    }

    // --- the ears ---

    private fun onEarMessage(text: String) {
        val message = parse(text) ?: return
        if ("setupComplete" in message) {
            log("ear ready")
            earReady = true
            return bothReady()
        }
        if ("goAway" in message) session.onMessage(text)
        val content = message["serverContent"] as? JsonObject ?: return
        content.textOf("interimInputTranscription")?.let(::onInterim)
        content.textOf("inputTranscription")?.let(::onFinal)
    }

    private fun onInterim(piece: String) {
        // Written either as the whole so far, or in pieces
        val words = if (piece.startsWith(interim)) piece else interim + piece
        // The same words again are no news: the wait for the end of them goes on
        if (words == interim) return
        interim = words
        promoted = null
        lastInterimAt = scheduler.now()
        finalLagMs = null
        if (over) return
        takeBackEarlyWords()
        if (isSubstantial(interim) && isSubstantial(interim.drop(interimStopped))) {
            interrupt()
            cancelThinking()
        }
        stopFillers()
        settleLater()
        interimTimer?.cancel()
        interimTimer = afterDelay(DualLiveConnector.INTERIM_ONLY_MS) { promoteInterim() }
        if (earlyThink) {
            earlyTimer?.cancel()
            earlyTimer = afterDelay(quiet(DualLiveConnector.EARLY_THINK_MS)) { thinkEarly() }
        }
    }

    /** The words have stopped changing: the answer is begun before the ears say they are final. */
    private fun thinkEarly() {
        earlyTimer = null
        if (!mayThinkEarly()) return
        log("early answer begun")
        val words = interim
        cancelThinking()
        earlyWords = words
        unanswered += words
        waitingSince = lastInterimAt
        stopFillers()
        think(BrainInput.ShopSaid(unanswered, cutOff), unanswered)
    }

    /** Not while the AI talks (nothing is in a hurry then), not for a nod, and not ahead of a note or the answers of tools. */
    private fun mayThinkEarly(): Boolean {
        if (over || hangUpWaiting || heldResults != null || thinking?.input is BrainInput.Note) return false
        if ((speaking != null && !cutting) || stillHeard()) return false
        return plainWords(interim).isNotEmpty() && !isNod(interim)
    }

    /** They went on, or the final words are different: what was begun from these words is not wanted. */
    private fun takeBackEarlyWords() {
        val words = earlyWords ?: return
        earlyWords = null
        // Answered already, these are gone from here: what was said has been said
        unanswered = unanswered.removeSuffix(words)
        if (thinking?.shopText?.endsWith(words) == true) {
            log("early answer taken back")
            cancelThinking()
        }
    }

    /** The final words are the ones the thinking model has: nothing starts over, and they take the place of the interim ones. */
    private fun confirmEarlyWords(words: String, final: String) {
        earlyWords = null
        forgetInterim()
        session.onMessage(heardMessage(final))
        if (!unanswered.endsWith(words)) return
        val asked = unanswered
        unanswered = unanswered.removeSuffix(words) + final
        thinking?.let { if (it.shopText == asked) it.shopText = unanswered }
    }

    private fun onFinal(text: String) {
        finalLagMs = lastInterimAt?.let { scheduler.now() - it }
        lastInterimAt = null
        // The ears were only slow: these words were taken from the interim ones already
        val late = promoted?.let { sameWords(it, text) } == true
        promoted = null
        if (late) return
        val early = earlyWords
        if (early != null && sameWords(early, text)) return confirmEarlyWords(early, text)
        takeBackEarlyWords()
        heard(text, mayCut = true)
    }

    /** The ears never made these words final: they are all there will be. What would have cut the AI did so as it came. */
    private fun promoteInterim() {
        interimTimer = null
        val words = interim
        if (plainWords(words).isEmpty()) return forgetInterim()
        log("no final words came: the interim ones are used")
        if (earlyWords != null) {
            // The thinking model has them already: they only have to be shown
            confirmEarlyWords(words, words)
        } else {
            heard(words, mayCut = false)
            settleTimer?.cancel()
            onSettled()
        }
        promoted = words
    }

    private fun forgetInterim() {
        interim = ""
        interimStopped = 0
        interimTimer?.cancel()
        interimTimer = null
        earlyTimer?.cancel()
        earlyTimer = null
    }

    /** Words that count as said. */
    private fun heard(text: String, mayCut: Boolean) {
        forgetInterim()
        session.onMessage(heardMessage(text))
        if (over) return
        // A nod while the AI talks needs no answer
        if (aiTalking() && isNod(text)) return
        if (mayCut && isSubstantial(text)) interrupt()
        unanswered += text
        waitingSince = scheduler.now()
        stopFillers()
        // A few words do not take back an answer already going out: they are answered after it
        if (isSubstantial(text) || thinking?.linesOut.isNullOrEmpty()) cancelThinking()
        settleLater()
    }

    private fun settleLater() {
        settleTimer?.cancel()
        settleTimer = afterDelay(quiet(DualLiveConnector.SHOP_SETTLE_MS)) { onSettled() }
    }

    /** While they keep talking over what is being thought about, not at every small pause: each question costs one to the API. */
    private fun quiet(ms: Long) = if (stillTalking) maxOf(ms, DualLiveConnector.QUIET_AFTER_TAKEN_BACK_MS) else ms

    private fun onSettled() {
        settleTimer = null
        interimStopped = interim.length
        if (over || hangUpWaiting) return
        val running = thinking
        if (running != null) {
            // It has all of it: only the words that kept the fillers back have stopped
            if (running.shopText == unanswered) armFillers()
            return
        }
        // In a moment, with all of the words
        if (earlyTimer != null && mayThinkEarly()) return
        if (unanswered.isEmpty() && heldResults == null) {
            if (openingAgain) think(BrainInput.Opening, "")
            return
        }
        startTurn()
    }

    /** They talk over a line of the AI: it stops, and what was not said is not left waiting. */
    private fun interrupt() {
        if (cutting || hangUpWaiting) return
        val line = speaking
        val heard = stillHeard()
        if (line == null && !heard) return
        // Stopping the sound stops all of it: what is still playing of a fixed line is not to be lost
        val going = playingOut.map { it.line } + listOfNotNull(line)
        if (going.any { it.fixed }) return
        session.onMessage(INTERRUPTED)
        // All of the line has come and only the playing is stopped: its turn ends here
        if (line != null) {
            cutting = true
            // With no voice yet the mouth may only be late, and has all its time
            if (lineHasVoice) watchForTheEnd()
        } else {
            session.onMessage(TURN_COMPLETE)
        }
        // Those of an answer that is taken back with this are told as a whole, when it is
        val takenBack = thinking?.takeIf { it.input !is BrainInput.Note }?.linesOut.orEmpty()
        val notSaid = (going + lines).filter { it.kind == LineKind.SAY && it !in takenBack }.joinToString("") { it.text }
        playingOut.clear()
        voiceEndsAt = 0
        lines.clear()
        if (notSaid.isNotEmpty()) cutOff = cutOff.orEmpty() + notSaid
        stopFillers()
        cancelThinking()
    }

    /** Not a line whose voice has run out: all it may be waiting for is an end that will not come, and they are answering it. */
    private fun aiTalking(): Boolean {
        val voiceRanOut = lineHasVoice && !cutting && scheduler.now() >= heardBy()
        return (speaking != null && !voiceRanOut) || stillHeard()
    }

    /** Some of a finished line is still coming out of the phone. */
    private fun stillHeard(): Boolean {
        val now = scheduler.now()
        playingOut.removeAll { it.endsAt <= now }
        return playingOut.isNotEmpty()
    }

    // --- the thinking model ---

    private fun startTurn() {
        val results = heldResults
        think(
            if (results != null) BrainInput.ToolResults(results, unanswered.ifEmpty { null }) else BrainInput.ShopSaid(unanswered, cutOff),
            unanswered,
        )
    }

    private fun think(input: BrainInput, shopText: String) {
        openingAgain = false
        if (input is BrainInput.Note) noteOpen = true
        val mine = ++generation
        val cancel = brain.think(
            input,
            onLine = { line -> later { if (mine == generation) onLine(line) } },
            done = { out -> later { if (mine == generation) onOutput(out) } },
        )
        val running = Thinking(cancel, input, shopText)
        thinking = running
        afterDelay(DualLiveConnector.BRAIN_MAX_MS) { if (thinking === running) neverAnswered(running) }
        armFillers()
    }

    /** The call is not left to itself: the fixed line, and the user takes over. */
    private fun neverAnswered(running: Thinking) {
        generation++
        thinking = null
        runCatching { running.cancel() }
        failed(BrainFailure.TIMEOUT)
    }

    /** A note from the app is not dropped for what the other side says. */
    private fun cancelThinking() {
        val running = thinking ?: return
        if (running.input is BrainInput.Note) return
        generation++
        thinking = null
        stillTalking = true
        takenBack++
        if (running.input == BrainInput.Opening && running.linesOut.isEmpty()) openingAgain = true
        runCatching { running.cancel() }
        // A turn taken back leaves nothing in the conversation: what it had said goes to the next one
        if (running.linesOut.isNotEmpty()) cutOff = cutOff.orEmpty() + running.linesOut.joinToString("") { it.text }
    }

    /** One sentence of the answer, as soon as it is whole. */
    private fun onLine(text: String) {
        val running = thinking ?: return
        if (over || callOver || text.isBlank()) return
        running.linesOut += speak(text)
    }

    /** What they were waiting for has come. */
    private fun speak(text: String): Line {
        val line = Line(LineKind.SAY, text)
        lines += line
        waitingSince?.let {
            answeredAt = scheduler.now()
            answerMs = answeredAt - it
        }
        waitIsOver()
        pumpMouth()
        return line
    }

    private fun waitIsOver() {
        stopWaiting()
        noteOpen = false
    }

    private fun onOutput(out: BrainOutput) {
        val done = thinking ?: return
        thinking = null
        out.failed?.let { return failed(it) }
        stillTalking = false
        unanswered = unanswered.removePrefix(done.shopText)
        if (done.input is BrainInput.ShopSaid) cutOff = null
        if (done.input is BrainInput.ToolResults) heldResults = null
        val say = out.say?.takeIf { it.isNotBlank() && !callOver }
        if (say != null) speak(say) else if (out.calls.isEmpty()) waitIsOver()
        if (out.calls.isNotEmpty()) {
            toolCalls += out.calls
            pumpTools()
        } else {
            afterThinking()
        }
    }

    private fun afterThinking() {
        if (over || thinking != null || toolCalls.isNotEmpty()) return
        when {
            heldResults != null -> if (settleTimer == null) startTurn()
            notes.isNotEmpty() -> thinkNote(notes.removeFirst())
            unanswered.isNotEmpty() -> if (settleTimer == null) startTurn()
        }
    }

    /** The fixed line, and then the session ends the way a lost connection does. */
    private fun failed(why: BrainFailure) {
        log("thinking failed: $why")
        failing = true
        if (callOver) {
            // Nobody to read the fixed line to
            session.onClosed(BRAIN_FAILED_CODE, BRAIN_FAILED)
            return end()
        }
        stopWaiting()
        settleTimer?.cancel()
        settleTimer = null
        lines.clear()
        toolCalls.clear()
        lines += Line(LineKind.FALLBACK, spoken.fallback)
        pumpMouth()
    }

    private fun onNote(text: String) {
        if (over) return
        val answersOpen = thinking?.input is BrainInput.ToolResults || heldResults != null || toolCalls.isNotEmpty()
        if (!answersOpen) cancelThinking()
        if (answersOpen || thinking != null) {
            notes += text
            return
        }
        thinkNote(text)
    }

    private fun thinkNote(text: String) {
        // After the call nothing more comes: words no answer has seen would be missing from the report
        val words = if (callOver) unanswered + interim.takeIf { earlyWords == null }.orEmpty() else ""
        if (words.isBlank()) return think(BrainInput.Note(text), "")
        forgetInterim()
        think(BrainInput.Note("$text$LAST_WORDS「$words」"), unanswered)
    }

    // --- tools: through the session, as ever ---

    private fun pumpTools() {
        while (!closed) {
            val next = toolCalls.firstOrNull() ?: break
            // After the line before it: played out first, or the quiet would end the call before the voice
            if (next.name == LiveTools.END_CALL && !mouthIdle) return
            toolCalls.removeFirst()
            runCatching { session.onMessage(toolCallMessage(next)) }.onFailure { t ->
                log("tool failed: ${t.javaClass.simpleName}")
                // Like any other answer that says no: the conversation goes on
                if (collected.none { it.id == next.id && it.name == next.name }) {
                    collected += FunctionResponse(next.id, next.name, notDone(TOOL_FAILED))
                }
            }
        }
        toolsDone()
    }

    private fun collect(message: JsonObject) {
        val answers = (message["toolResponse"] as? JsonObject)?.get("functionResponses") as? JsonArray ?: return
        for (item in answers) {
            val o = item as? JsonObject ?: continue
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
            collected += FunctionResponse((o["id"] as? JsonPrimitive)?.contentOrNull, name, (o["response"] as? JsonObject) ?: JsonObject(emptyMap()))
        }
    }

    private fun toolsDone() {
        if (collected.isEmpty()) return
        val answers = collected.toList()
        collected.clear()
        // The report was all that was still wanted after the call
        if (session.toolHandler.ended || (callOver && session.toolHandler.outcome != null)) return stopWaiting()
        heldResults = heldResults.orEmpty() + answers
        afterThinking()
    }

    // --- 「はい。」 while they wait ---

    private fun armFillers() {
        val since = waitingSince ?: return
        val now = scheduler.now()
        if (!fillerSaid && fillerTimer == null) {
            fillerTimer = afterDelay((since + DualLiveConnector.FILLER_AFTER_MS - now).coerceAtLeast(0)) {
                fillerTimer = null
                if (sayFiller(DualLiveConnector.FILLER)) fillerSaid = true
            }
        }
        if (!longWaitSaid && longWaitTimer == null) {
            longWaitTimer = afterDelay((since + DualLiveConnector.LONG_WAIT_MS - now).coerceAtLeast(0)) {
                longWaitTimer = null
                if (sayFiller(DualLiveConnector.LONG_WAIT)) longWaitSaid = true
            }
        }
    }

    private fun sayFiller(text: String): Boolean {
        if (over || callOver || waitingSince == null || !mouthIdle || stillHeard() || noteOpen || notes.isNotEmpty()) return false
        lines += Line(LineKind.FILLER, text)
        pumpMouth()
        return true
    }

    private fun stopFillers() {
        fillerTimer?.cancel()
        fillerTimer = null
        longWaitTimer?.cancel()
        longWaitTimer = null
    }

    private fun stopWaiting() {
        waitingSince = null
        fillerSaid = false
        longWaitSaid = false
        stopFillers()
    }

    // --- the end ---

    private fun end() {
        closed = true
        tearDown()
    }

    private fun tearDown() {
        if (tornDown) return
        tornDown = true
        generation++
        thinking?.let { running -> runCatching { running.cancel() } }
        thinking = null
        stopFillers()
        settleTimer?.cancel()
        interimTimer?.cancel()
        earlyTimer?.cancel()
        lineTimer?.cancel()
        endWatch?.cancel()
        runCatching { ear?.cancel() }
        runCatching { mouth?.cancel() }
        (scheduler as? ExecutorScheduler)?.shutdown()
    }

    private companion object {
        const val BRAIN_FAILED_CODE = 1011
        const val BRAIN_FAILED = "brain failed"
        const val VOICE_LOST = "voice lost"
        const val STEP_FAILED = "dual step failed"
        const val TOOL_FAILED = "アプリの中の失敗で、実行できませんでした。"
        const val LAST_WORDS = "まだ答えていない、お店の方の最後の言葉："
    }
}
