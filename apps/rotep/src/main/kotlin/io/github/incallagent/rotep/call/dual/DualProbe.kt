// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call.dual

import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.agent.live.LiveMessages
import io.github.incallagent.agent.live.ServerEvent
import io.github.incallagent.agent.live.ServerEvents
import io.github.incallagent.rotep.app.UserMessages
import io.github.incallagent.rotep.call.AudioPathEvent
import io.github.incallagent.rotep.call.CallAudioPath
import io.github.incallagent.rotep.call.CallScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class DualProbePhase { IDLE, CONNECTING, SPEAKING, LISTENING, WAITING_FOR_TEXT, DONE }

/**
 * What the try found: numbers and classes, never a word that was said or that the server wrote.
 * [earEnd] and [mouthEnd]: why that socket ended before the try did.
 */
internal data class DualProbeResult(
    val earSetupMs: Long? = null,
    val earEnd: String? = null,
    val mouthSetupMs: Long? = null,
    val mouthEnd: String? = null,
    val firstAudioMs: Long? = null,
    val audioSeconds: Double = 0.0,
    val spokenMatchPercent: Int? = null,
    val firstInterimMs: Long? = null,
    val finalChars: Int = 0,
    val audioProblem: String? = null,
) {
    private val audioLength get() = "%.1f".format(Locale.ROOT, audioSeconds)

    fun logLine(): String = "probe earSetupMs=${earSetupMs ?: "-"} earEnd=${earEnd ?: "-"} " +
        "mouthSetupMs=${mouthSetupMs ?: "-"} mouthEnd=${mouthEnd ?: "-"} firstAudioMs=${firstAudioMs ?: "-"} " +
        "audioSeconds=$audioLength spokenMatchPercent=${spokenMatchPercent ?: "-"} " +
        "firstInterimMs=${firstInterimMs ?: "-"} finalChars=$finalChars audioProblem=${audioProblem ?: "-"}"

    fun screenLines(): List<String> = listOfNotNull(
        "耳の準備: ${ms(earSetupMs)}${closed(earEnd)}",
        "口の準備: ${ms(mouthSetupMs)}${closed(mouthEnd)}",
        "最初の音声まで: ${ms(firstAudioMs)}",
        "音声の長さ: $audioLength 秒",
        "読み上げの一致: ${spokenMatchPercent?.let { "$it %" } ?: "なし"}",
        "最初の途中の文字まで: ${ms(firstInterimMs)}",
        "確定の文字数: $finalChars",
        audioProblem?.let { "マイクとスピーカー: $it" },
    )

    private fun ms(value: Long?) = value?.let { "$it ms" } ?: "なし"

    private fun closed(end: String?) = end?.let { "（閉じた: $it）" } ?: ""
}

/** For the card. [liveText] is what is being said just now: never for the log, and gone when the try ends. */
internal data class DualProbeState(
    val phase: DualProbePhase = DualProbePhase.IDLE,
    val result: DualProbeResult = DualProbeResult(),
    val liveText: String = "",
    val problem: String? = null,
) {
    override fun toString() = "DualProbeState($phase, $result)"
}

/**
 * Tries the two sockets of the voice model on their own: no call, no thinking model, only the Gemini
 * key. Opens the ears and the mouth, has the mouth read one line out through [audio], then sends the
 * microphone to the ears for [LISTEN_MS] and closes everything. A socket that does not work does not
 * stop the other from being tried.
 *
 * Everything runs on [scheduler].
 */
internal class DualProbe(
    private val sockets: VoiceSockets,
    private val auth: LiveAuth,
    private val voiceName: String?,
    private val audio: CallAudioPath,
    private val scheduler: CallScheduler,
    private val log: (String) -> Unit,
    private val onUpdate: (DualProbeState) -> Unit,
) {
    private var state = DualProbeState()
    private var startedAt = 0L
    private var lineSentAt = 0L
    private var listeningSince = 0L
    private var audioStarted = false
    private var finished = false
    private var timer: CallScheduler.Cancellable? = null

    // The words, for the screen and the comparison only: dropped when the try ends
    private val readOut = StringBuilder()
    private val finals = StringBuilder()

    private val ear = Voice(VoiceSetup.ear(), ::onEarMessage) { setupMs, end ->
        copy(earSetupMs = setupMs ?: earSetupMs, earEnd = end ?: earEnd)
    }
    private val mouth = Voice(VoiceSetup.mouth(voiceName), ::onMouthMessage) { setupMs, end ->
        copy(mouthSetupMs = setupMs ?: mouthSetupMs, mouthEnd = end ?: mouthEnd)
    }

    fun start() = scheduler.post { begin() }

    /** Ends the try now. Safe at any time and more than once. */
    fun cancel() = scheduler.post { finish() }

    private fun begin() {
        startedAt = scheduler.now()
        show { copy(phase = DualProbePhase.CONNECTING) }
        ear.open()
        mouth.open()
        after(SETUP_TIMEOUT_MS) { listOf(ear, mouth).filterNot { it.ready }.forEach { it.giveUp() } }
    }

    private fun onVoiceSettled() {
        if (finished || audioStarted || !ear.settled || !mouth.settled) return
        cancelTimer()
        if (ear.ready || mouth.ready) startAudio() else finish()
    }

    private fun startAudio() {
        audioStarted = true
        audio.start(object : CallAudioPath.Listener {
            override fun onDownlink(pcm16k: ByteArray) = scheduler.post { sendMicrophone(pcm16k) }

            override fun onEvent(event: AudioPathEvent) = scheduler.post { onAudioEvent(event) }
        })
    }

    private fun onAudioEvent(event: AudioPathEvent) {
        if (finished) return
        when (event) {
            is AudioPathEvent.Started -> speakOrListen()
            is AudioPathEvent.Failed -> audioBroke(event.detail)
            AudioPathEvent.HelperDied -> audioBroke("helper died")
            AudioPathEvent.Silenced, is AudioPathEvent.Stopped -> Unit
        }
    }

    private fun audioBroke(detail: String) {
        record { copy(audioProblem = detail) }
        finish()
    }

    private fun speakOrListen() {
        if (!mouth.ready) {
            listen()
            return
        }
        show { copy(phase = DualProbePhase.SPEAKING) }
        lineSentAt = scheduler.now()
        mouth.send(VoiceSetup.speak(PROBE_LINE))
        after(SPEECH_TIMEOUT_MS) {
            mouth.giveUp()
            waitUntilQuiet()
        }
    }

    private fun onMouthMessage(message: JsonObject) {
        for (event in ServerEvents.parse(message)) {
            when (event) {
                is ServerEvent.Audio -> onSpeech(event)
                is ServerEvent.OutputTranscript -> {
                    readOut.append(event.text)
                    show { copy(liveText = readOut.toString()) }
                }
                ServerEvent.TurnComplete -> if (state.phase == DualProbePhase.SPEAKING) waitUntilQuiet()
                else -> Unit
            }
        }
    }

    private fun onSpeech(speech: ServerEvent.Audio) {
        audio.play(speech.pcm)
        val seconds = speech.pcm.size.toDouble() / (BYTES_PER_SAMPLE * speech.sampleRate)
        record {
            copy(firstAudioMs = firstAudioMs ?: (scheduler.now() - lineSentAt), audioSeconds = audioSeconds + seconds)
        }
    }

    /** The ears start once the line has been played out: they would write down the phone's own voice. */
    private fun waitUntilQuiet(until: Long = scheduler.now() + PLAY_OUT_TIMEOUT_MS) {
        if (audio.isPlaying() && scheduler.now() < until) {
            after(QUIET_POLL_MS) { waitUntilQuiet(until) }
            return
        }
        cancelTimer()
        if (readOut.isNotEmpty()) {
            val percent = matchPercent(PROBE_LINE, readOut.toString())
            record { copy(spokenMatchPercent = percent) }
        }
        listen()
    }

    private fun listen() {
        if (!ear.ready) {
            finish()
            return
        }
        listeningSince = scheduler.now()
        show { copy(phase = DualProbePhase.LISTENING, liveText = "") }
        after(LISTEN_MS) {
            // Without this, words still being said when the time is up would never become final
            ear.send(LiveMessages.audioStreamEnd().toString())
            show { copy(phase = DualProbePhase.WAITING_FOR_TEXT) }
            after(LAST_TEXT_MS) { finish() }
        }
    }

    private fun sendMicrophone(pcm16k: ByteArray) {
        if (!finished && state.phase == DualProbePhase.LISTENING) ear.send(LiveMessages.realtimeAudio(pcm16k).toString())
    }

    private fun onEarMessage(message: JsonObject) {
        val content = message["serverContent"] as? JsonObject ?: return
        content.textOf("interimInputTranscription")?.let { interim ->
            val firstAt = state.result.firstInterimMs ?: (scheduler.now() - listeningSince)
            show { copy(result = result.copy(firstInterimMs = firstAt), liveText = finals.toString() + interim) }
        }
        content.textOf("inputTranscription")?.let { final ->
            finals.append(final)
            show { copy(result = result.copy(finalChars = result.finalChars + final.length), liveText = finals.toString()) }
        }
    }

    private fun JsonObject.textOf(key: String): String? =
        ((this[key] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun finish() {
        if (finished) return
        finished = true
        cancelTimer()
        ear.close()
        mouth.close()
        if (audioStarted) audio.stop()
        readOut.clear()
        finals.clear()
        log(state.result.logLine())
        state = state.copy(phase = DualProbePhase.DONE, liveText = "")
        onUpdate(state)
    }

    /** One timer at a time: each step of the try has at most one thing to wait for. */
    private fun after(delayMs: Long, task: () -> Unit) {
        cancelTimer()
        timer = scheduler.postDelayed(delayMs) { if (!finished) task() }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    private fun show(change: DualProbeState.() -> DualProbeState) {
        if (finished) return
        state = state.change()
        onUpdate(state)
    }

    private fun record(change: DualProbeResult.() -> DualProbeResult) = show { copy(result = result.change()) }

    private inner class Voice(
        private val setup: String,
        private val onServerMessage: (JsonObject) -> Unit,
        private val write: DualProbeResult.(setupMs: Long?, end: String?) -> DualProbeResult,
    ) : VoiceSocket.Listener {
        private var socket: VoiceSocket? = null
        private var ended = false
        var ready = false
            private set
        val settled get() = ready || ended

        fun open() {
            socket = sockets.open(auth, this)
        }

        fun send(text: String) {
            socket?.send(text)
        }

        fun close() {
            socket?.cancel()
            socket = null
        }

        fun giveUp() {
            if (ended) return
            markEnded(TIMED_OUT)
            close()
            onVoiceSettled()
        }

        private fun markEnded(end: String) {
            ended = true
            ready = false
            record { write(null, end) }
        }

        override fun onOpen() = scheduler.post { send(setup) }

        override fun onMessage(text: String) = scheduler.post {
            if (finished || ended) return@post
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@post
            if (!ready && message.containsKey(SETUP_COMPLETE)) {
                ready = true
                record { write(scheduler.now() - startedAt, null) }
                onVoiceSettled()
            }
            onServerMessage(message)
        }

        override fun onClosed(code: Int, reason: String) = scheduler.post { end("closed code=$code reason=$reason") }

        override fun onFailure(detail: String) = scheduler.post { end(detail) }

        private fun end(detail: String) {
            if (finished || ended) return
            // Only the code and the class: the server's words stay out of the numbers
            markEnded(UserMessages.liveLog(detail))
            when {
                !audioStarted -> onVoiceSettled()
                this === mouth && state.phase == DualProbePhase.SPEAKING -> waitUntilQuiet()
                this === ear && state.phase in STILL_LISTENING -> finish()
            }
        }
    }

    companion object {
        /** A line a shop would hear, written as it is said. */
        const val PROBE_LINE = "にめいで、じゅうくじからお願いできますでしょうか。"
        const val SETUP_TIMEOUT_MS = 15_000L
        const val SPEECH_TIMEOUT_MS = 20_000L
        const val PLAY_OUT_TIMEOUT_MS = 10_000L
        const val QUIET_POLL_MS = 100L
        const val LISTEN_MS = 8_000L
        const val LAST_TEXT_MS = 2_000L
        const val TIMED_OUT = "timeout"
        private const val SETUP_COMPLETE = "setupComplete"
        private const val BYTES_PER_SAMPLE = 2
        private const val KANA_DISTANCE = 0x60
        private val KATAKANA = 'ァ'..'ヶ'
        private val STILL_LISTENING = setOf(DualProbePhase.LISTENING, DualProbePhase.WAITING_FOR_TEXT)

        /**
         * How close two lines are, 0 to 100, in what is said: spaces, punctuation and hiragana or
         * katakana do not count. A line written in kanji or digits where the other has kana counts as different.
         */
        fun matchPercent(sent: String, heard: String): Int {
            val a = comparable(sent)
            val b = comparable(heard)
            if (a.isEmpty() || b.isEmpty()) return 0
            val longer = max(a.length, b.length)
            return ((longer - editDistance(a, b)) * 100.0 / longer).roundToInt()
        }

        private fun comparable(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .filter { it.isLetterOrDigit() }
            .map { if (it in KATAKANA) it - KANA_DISTANCE else it }
            .joinToString("")

        private fun editDistance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1)
                current[0] = i + 1
                for (j in b.indices) {
                    val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                    current[j + 1] = min(substitution, min(previous[j + 1], current[j]) + 1)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
