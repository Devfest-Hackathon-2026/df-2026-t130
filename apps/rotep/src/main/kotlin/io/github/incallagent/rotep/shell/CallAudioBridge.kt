// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The call audio bridge of 1b: the helper captures the downlink, resamples it to 16 kHz and hands
// it to the app (20 ms chunks); the app hands the agent's voice (24 kHz) to the helper, which plays
// it into the call by uplinkMode. The pipes carry AudioPipe's wire format.
//
// As in UplinkTest.kt, the first part of this file is plain Kotlin (no Android classes) so the
// course of each side runs in unit tests against a fake [DownlinkSource] / [UplinkSink]; the part
// that talks to the system (AudioRecord, AudioTrack, the pipes themselves) is at the end.

/** Event codes of IHelperCallback.onAudioBridge. */
object AudioBridgeEvent {
    const val STARTED = 1
    const val STOPPED = 2
    const val SILENCED = 3
    const val FAILED = 4
}

/** The AIDL's uplinkMode int (0, 1, 2): the same order as the app's ShellMode, without this
 * package depending on the call package. */
object AudioUplinkMode {
    const val DIRECT = 0
    const val REDIRECTION = 1
    const val SPEAKER = 2

    fun isValid(mode: Int): Boolean = mode in DIRECT..SPEAKER
}

/** The rate [CallAudioPath.Listener.onDownlink][io.github.incallagent.rotep.call.CallAudioPath.Listener.onDownlink] is always resampled to. */
const val DOWNLINK_RATE_HZ = 16_000

/** The rate the agent's voice always arrives at (matches CallSession.AGENT_RATE). */
const val AGENT_RATE_HZ = 24_000

/** The rate the uplink AudioTrack is made at when the system will not take [AGENT_RATE_HZ]. */
const val FALLBACK_RATE_HZ = 48_000

/** Bytes of one 20 ms chunk of 16-bit mono PCM at [rate] (exact for every rate this project uses). */
fun bytesFor20ms(rate: Int): Int = rate / 50 * 2

/**
 * How much sound the helper's playback AudioTrack holds. The app side counts on it (UplinkFeeder
 * adds it to the time `isPlaying()` stays true after the last piece was handed over).
 */
const val UPLINK_BUFFER_MS = 300L

/**
 * How much sound is enough for the direct track to start playing, instead of the framework's
 * default, the whole buffer. While the audio arrives in real time, a new or re-created track that
 * waits for all of [UPLINK_BUFFER_MS] can be taken off the mixer and invalidated again before it is
 * full (uplink-start.md; [UplinkFeeder.forTelephonyOutput]). 60 ms is three 20 ms pieces of silence.
 */
const val UPLINK_START_THRESHOLD_MS = 60L

/** Why one side of the bridge ended. */
internal sealed interface BridgeEndReason {
    data class Stopped(val why: String) : BridgeEndReason
    data object Silenced : BridgeEndReason
    data class Failed(val detail: String) : BridgeEndReason
}

// --- Opening something that must be let go again when it does not work out -------------------------

/**
 * Tries the [candidates] one after the other and returns the first thing that [open] makes and
 * [start] gets going. [open] answers null for a candidate that cannot be used at all and throws
 * for one that failed: in both cases nothing was made, so there is nothing to let go. What [open]
 * made but [start] could not get going ([start] throws) is let go with [release] before the next
 * candidate is tried, so a failed attempt never leaves an AudioRecord or an AudioTrack behind;
 * [release] is called for nothing else. When no candidate works, the first failure is thrown with
 * the later ones attached to it (the first candidate is the one the device most likely takes, so
 * its failure says the most), or an IllegalStateException with [none] when every candidate was
 * unusable and nothing failed.
 */
internal fun <C, T : Any> firstThatStarts(
    candidates: List<C>,
    none: String,
    open: (C) -> T?,
    start: (T) -> Unit,
    release: (T) -> Unit,
): T {
    var first: Throwable? = null
    for (candidate in candidates) {
        try {
            val thing = open(candidate) ?: continue
            try {
                start(thing)
            } catch (t: Throwable) {
                try {
                    release(thing)
                } catch (r: Throwable) {
                    if (r !== t) t.addSuppressed(r)
                }
                throw t
            }
            return thing
        } catch (t: Throwable) {
            val earlier = first
            if (earlier == null) first = t else if (earlier !== t) earlier.addSuppressed(t)
        }
    }
    throw first ?: IllegalStateException(none)
}

/** What a reflective call really threw. */
internal fun unwrapInvocation(t: Throwable): Throwable = if (t is InvocationTargetException) t.targetException ?: t else t

/**
 * One line for a failure that goes into the record: the class and the scrubbed message of [t]
 * (ReportText: never a stack trace, never a number that looks like a phone number), then the same
 * for the failures attached to it (the other candidates [firstThatStarts] tried), at most two.
 */
internal fun failureText(t: Throwable): String {
    val main = ReportText.exceptionLine(unwrapInvocation(t))
    val others = t.suppressed.take(2)
    if (others.isEmpty()) return main
    return "$main（ほかの候補: ${others.joinToString(" / ") { ReportText.exceptionLine(unwrapInvocation(it)) }}）"
}

// --- Downlink: AudioRecord -> resample -> 20 ms chunks -> the app's pipe ------------------------

/** What the downlink run needs from the system; [CallAudioBridge] backs this with AudioRecord. */
internal interface DownlinkSource {
    val nativeRate: Int

    /** Blocking read, AudioRecord-style: the frame count, or a negative error code. */
    fun read(buffer: ShortArray): Int

    /** Best-effort: true once the system is known to be silencing this recording. */
    fun isSilenced(): Boolean
}

/**
 * Whether a call is on, as the bridge needs to know before it opens anything. The system sets the
 * audio mode a moment after a call is placed, and 「相手が出た」 can be pressed before that, so a mode
 * that is not there yet is given a moment. A refusal says the mode it saw.
 */
internal object InCallCheck {
    const val WAIT_MS = 3_000L
    const val POLL_MS = 100L

    /** The mode as soon as it is MODE_IN_CALL, else the last one read after [WAIT_MS]. Null: it could not be read. */
    fun await(readMode: () -> Int?, now: () -> Long, sleep: (Long) -> Unit): Int? {
        val deadline = now() + WAIT_MS
        while (true) {
            val mode = readMode()
            if (mode == UplinkTestRules.MODE_IN_CALL || now() >= deadline) return mode
            sleep(POLL_MS)
        }
    }

    fun modeText(mode: Int?): String = mode?.let(EnvReport::audioModeName) ?: "読めなかった"

    fun refusal(mode: Int?): String = "通話中ではない（音声のモード: ${modeText(mode)}）。MODE_IN_CALL のときだけ始める"
}

/**
 * Reads [source] continuously, resamples to [DOWNLINK_RATE_HZ] and writes exactly-20-ms chunks to
 * [sink] (see AudioPipe), until [requestStop], the call ends ([audioMode] leaves MODE_IN_CALL) or
 * an error. [sink] only needs to be an OutputStream, so tests use a plain ByteArrayOutputStream.
 */
internal class DownlinkBridgeRun(
    private val source: DownlinkSource,
    private val sink: OutputStream,
    /** What to do when a read brings nothing: the real one waits a moment, so the loop does not spin. */
    private val idleSleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Sees each read before it is resampled (the frames in the array, how many; 0 when it brought nothing). On the capture thread. */
    private val observer: (ShortArray, Int) -> Unit = { _, _ -> },
    private val audioMode: () -> Int?,
) {
    private val stopRequested = AtomicBoolean(false)

    fun requestStop() = stopRequested.set(true)

    fun run(): BridgeEndReason {
        val resampler = StreamResampler(source.nativeRate, DOWNLINK_RATE_HZ)
        val chunker = FixedChunker(bytesFor20ms(DOWNLINK_RATE_HZ))
        val buffer = ShortArray((source.nativeRate / 50).coerceAtLeast(1))
        var untilCheck = 0
        while (!stopRequested.get()) {
            // The call and the recording are looked at before the first read, then every
            // CHECK_PERIOD loops (each asks the audio service, so not on every 20 ms read).
            if (untilCheck <= 0) {
                val mode = audioMode()
                if (mode != UplinkTestRules.MODE_IN_CALL) {
                    return BridgeEndReason.Stopped("音声のモードが${mode?.let(EnvReport::audioModeName) ?: "分からない状態"}になった（通話が終わった見込み）")
                }
                if (source.isSilenced()) return BridgeEndReason.Silenced
                untilCheck = CHECK_PERIOD
            }
            untilCheck--
            val n = source.read(buffer)
            if (n < 0) return BridgeEndReason.Failed("受話の読み取りの失敗（コード $n）")
            if (n == 0) {
                observer(buffer, 0)
                // Nothing came: wait a little rather than ask again at once (the stop request and the
                // call's end are still looked at on every round).
                idleSleep(IDLE_SLEEP_MS)
                continue
            }
            observer(buffer, n)
            val native = if (n == buffer.size) buffer else buffer.copyOf(n)
            val resampled = resampler.process(native)
            if (resampled.isEmpty()) continue
            val bytes = Pcm16.toBytes(resampled)
            for (piece in chunker.process(bytes)) {
                try {
                    AudioPipe.writeChunk(sink, piece)
                } catch (t: Throwable) {
                    // The only reader of this pipe is the app, and a write to a pipe fails when it has
                    // closed its end (or died): that is the app stopping, which also happens on every
                    // ordinary end of a call, so it is not reported as a failure.
                    return BridgeEndReason.Stopped("受話のパイプが閉じられた（${t.javaClass.simpleName}。アプリが止めた見込み）")
                }
            }
        }
        return BridgeEndReason.Stopped(REASON_STOP)
    }

    companion object {
        /** How many 20 ms loops between looks at the audio mode and at isClientSilenced (about 200 ms). */
        const val CHECK_PERIOD = 10

        /** How long to wait after a read that brought nothing. */
        const val IDLE_SLEEP_MS = 5L
        const val REASON_STOP = "止める指示"
    }
}

// --- Uplink: the app's pipe -> (resample if needed) -> AudioTrack -------------------------------

/** What the uplink run needs from the system; [CallAudioBridge] backs this with AudioTrack. */
internal interface UplinkSink {
    /** The rate actually in use for playback (AGENT_RATE_HZ, or the device's fallback rate). */
    val playbackRate: Int

    /** Blocking write, AudioTrack-style: the frame count written, or a negative error code. */
    fun write(pcm: ShortArray): Int

    /** Stops playback at once and drops whatever has not played yet. */
    fun flush()

    // The record of a test call (see UplinkStats), read on the uplink thread. -1 means "not known".

    /** Where the track's playback head is, in frames (the wrapping 32-bit counter, kept non-negative). */
    fun playbackHead(): Long = -1L

    /** How many times the track underran so far (the framework's count, kept across re-creation). */
    fun underruns(): Int = -1

    /** The track's port id: it changes when the AudioTrack was re-created. */
    fun portId(): Int = -1
}

/**
 * Reads the app's messages (see AudioPipe) from [input] and plays them on [sink], resampling from
 * [AGENT_RATE_HZ] when [UplinkSink.playbackRate] differs, until [requestStop], a clean end of
 * stream (the app called stop(), closing its end) or an error. [input] only needs to be an
 * InputStream, so tests use a plain ByteArrayInputStream (or a PipedInputStream for a live feed).
 */
internal class UplinkBridgeRun(
    private val input: InputStream,
    private val sink: UplinkSink,
    /** Keeps the record of the call (see [UplinkStats]); null when nobody asked for one. */
    private val stats: UplinkStats? = null,
    private val now: () -> Long = ::monotonicMillis,
) {
    private val stopRequested = AtomicBoolean(false)

    fun requestStop() = stopRequested.set(true)

    fun run(): BridgeEndReason {
        val resampler = if (sink.playbackRate != AGENT_RATE_HZ) StreamResampler(AGENT_RATE_HZ, sink.playbackRate) else null
        try {
            while (!stopRequested.get()) {
                val message = try {
                    AudioPipe.readMessage(input)
                } catch (t: Throwable) {
                    return BridgeEndReason.Failed("パイプの読み取りの失敗（${t.javaClass.simpleName}）")
                } ?: return BridgeEndReason.Stopped("パイプが閉じられた（止める指示の見込み）")
                when (message) {
                    AudioPipe.Message.Flush -> {
                        sink.flush()
                        stats?.onFlush()
                    }
                    is AudioPipe.Message.Chunk -> {
                        val agent = Pcm16.toShorts(message.pcm)
                        val toPlay = resampler?.process(agent) ?: agent
                        if (toPlay.isNotEmpty()) {
                            val began = now()
                            val n = sink.write(toPlay)
                            stats?.onWrite(toPlay.size, n, now() - began, voice = message.pcm.any { it != 0.toByte() })
                            if (n < 0) return BridgeEndReason.Failed("送話の書き込みの失敗（コード $n）")
                        }
                    }
                }
                stats?.tick()
            }
            return BridgeEndReason.Stopped(DownlinkBridgeRun.REASON_STOP)
        } finally {
            stats?.finish()
        }
    }
}

// --- Both sides together ------------------------------------------------------------------------

/**
 * The two sides of one bridge and how they end, as plain Kotlin: everything that touches the
 * system (the AudioRecord and AudioTrack, the pipe ends, the callback to the app, the threads)
 * comes in as a function, so the order of events runs in unit tests with fake sides.
 *
 * Each side runs on a thread of its own ([startThread]). When its [Side.run] ends (it returns, or
 * it throws: then the exception's class and scrubbed message become a FAILED reason), the side
 *  1. asks both sides to stop,
 *  2. lets go of its own audio object ([Side.release]): each thread touches only its own
 *     AudioRecord or AudioTrack, because releasing one from another thread while it is in use can
 *     crash inside the platform,
 *  3. and, for the first side to get here only, closes the pipe ends ([closePipes]) and reports
 *     why ([report]): one report per bridge. The other side's reason is only noted ([note]).
 *
 * [closePipes] is the step the teardown depends on. The helper's copy of the downlink pipe's write
 * end is that pipe's one and only write end, so closing it is a reliable, immediate end-of-stream
 * for the app's thread blocked reading the other end, whereas forcing a blocked read to give up by
 * closing the very descriptor it reads is something POSIX leaves undefined. When the app sees the
 * end-of-stream it closes its own uplink writer, which is the same kind of reliable signal for the
 * uplink thread, blocked reading the uplink pipe. So the uplink side may end one round trip later,
 * even if it was waiting for the next piece of the agent's voice; closing the uplink pipe's read
 * end here as well is only a best-effort extra, not relied on.
 *
 * [isActive] stays true until BOTH sides have let go of their audio objects and the report has
 * been sent (also when a side threw or the report failed): the helper starts no new bridge, no
 * second downlink recording and no second playback track, while the old one still holds the audio
 * devices, and the old bridge's report can never arrive after the new bridge has started.
 */
internal class BridgeSides(
    private val downlink: Side,
    private val uplink: Side,
    private val closePipes: () -> Unit,
    private val report: (code: Int, detail: String) -> Unit,
    private val startThread: (name: String, body: () -> Unit) -> Unit,
    private val note: (String) -> Unit = {},
) {
    /** One side of the bridge, as [BridgeSides] needs it. */
    class Side(
        /** For the record: which side it was. */
        val name: String,
        /** Blocks until the side ends and says why. */
        val run: () -> BridgeEndReason,
        val requestStop: () -> Unit,
        /** Stops and lets go of this side's audio object. Called once, on this side's own thread. */
        val release: () -> Unit,
    )

    private val reported = AtomicBoolean(false)
    private val sidesLeft = AtomicInteger(2)

    /** True until both sides have ended, let go of their audio objects and the report is out. */
    val isActive: Boolean get() = sidesLeft.get() > 0

    fun start() {
        startSide("audio-bridge-downlink", downlink)
        startSide("audio-bridge-uplink", uplink)
    }

    /**
     * A thread that cannot be made (out of memory) must not leave the bridge half alive. That side
     * never ran, so it is its own end: the other side is asked to stop, this side's audio object is
     * let go here (no thread has used it), and the failure is reported if nothing was reported yet.
     */
    private fun startSide(name: String, side: Side) {
        try {
            startThread(name) { runSide(side) }
        } catch (t: Throwable) {
            attempt { requestStop() }
            attempt { side.release() }
            end(side, BridgeEndReason.Failed("${side.name}のスレッドを作れなかった: ${ReportText.exceptionLine(t)}"))
            sidesLeft.decrementAndGet()
        }
    }

    /** Asks both sides to stop. Safe to call more than once and from any thread. */
    fun requestStop() {
        downlink.requestStop()
        uplink.requestStop()
    }

    private fun runSide(side: Side) {
        try {
            val reason = try {
                side.run()
            } catch (t: Throwable) {
                BridgeEndReason.Failed("${side.name}の処理が例外で止まった: ${ReportText.exceptionLine(unwrapInvocation(t))}")
            }
            attempt { requestStop() }
            attempt { side.release() }
            end(side, reason)
        } finally {
            sidesLeft.decrementAndGet()
        }
    }

    private fun end(side: Side, reason: BridgeEndReason) {
        if (!reported.compareAndSet(false, true)) {
            attempt { note("${side.name}の側も終わった（報告は済み）: ${describe(reason)}") }
            return
        }
        attempt { closePipes() }
        try {
            when (reason) {
                is BridgeEndReason.Stopped -> report(AudioBridgeEvent.STOPPED, reason.why)
                BridgeEndReason.Silenced -> report(AudioBridgeEvent.SILENCED, "")
                is BridgeEndReason.Failed -> report(AudioBridgeEvent.FAILED, reason.detail)
            }
        } catch (t: Throwable) {
            attempt { note("終わりを知らせられなかった: ${ReportText.exceptionLine(t)}") }
        }
    }

    private fun describe(reason: BridgeEndReason): String = when (reason) {
        is BridgeEndReason.Stopped -> "止まった（${reason.why}）"
        BridgeEndReason.Silenced -> "音を止められた"
        is BridgeEndReason.Failed -> "失敗（${reason.detail}）"
    }
}

// --- The system -----------------------------------------------------------------------------

/**
 * One run of the audio bridge inside the helper: a downlink side and an uplink side
 * ([BridgeSides]), backed by [DownlinkBridgeRun] / [UplinkBridgeRun] and a real AudioRecord /
 * AudioTrack. Every AudioRecord/AudioTrack carries the shell identity ([ShellContext]); every call
 * into the system is wrapped with [asSelf]. Needs Android 14 (API 34, for
 * AudioTrack.Builder.setContext); [create] refuses older versions.
 */
internal class CallAudioBridge private constructor(
    private val sides: BridgeSides,
    private val sink: Sink,
) {
    fun interface Sink {
        /** Sent at most once for STARTED, and exactly once for one of STOPPED/SILENCED/FAILED. */
        fun onEvent(code: Int, detail: String)
    }

    /**
     * True from [create] until both sides have ended and let go of their audio objects (an
     * AudioRecord, an AudioTrack): the helper starts no new bridge before that. The report of the
     * end (STOPPED/SILENCED/FAILED) comes earlier, from the side that ends first, and always before
     * this turns false.
     */
    val isActive: Boolean get() = sides.isActive

    /** [create] has already called play()/startRecording(), before either thread exists. */
    fun start() {
        sink.onEvent(AudioBridgeEvent.STARTED, "")
        sides.start()
    }

    /**
     * Asks both sides to stop. Safe to call more than once and from any thread; each side then
     * ends, lets go of its own audio object and reports by itself.
     */
    fun requestStop() = sides.requestStop()

    companion object {
        private const val TAG = "RotepHelper"

        /**
         * Builds the bridge, not started yet. Closes both [downlinkWriteEnd] and [uplinkReadEnd] and
         * returns null when the bridge cannot run here (reports FAILED through [sink] first).
         */
        fun create(
            shell: ShellContext?,
            audio: AudioManager,
            uplinkMode: Int,
            downlinkWriteEnd: ParcelFileDescriptor,
            uplinkReadEnd: ParcelFileDescriptor,
            sink: Sink,
            /** Sees the captured downlink (see [DownlinkBridgeRun]). */
            downlinkObserver: (ShortArray, Int) -> Unit = { _, _ -> },
        ): CallAudioBridge? {
            fun refuse(detail: String): CallAudioBridge? {
                Log.i(TAG, "audio bridge refused: ${ReportText.scrub(detail)}")
                attempt { downlinkWriteEnd.close() }
                attempt { uplinkReadEnd.close() }
                sink.onEvent(AudioBridgeEvent.FAILED, detail)
                return null
            }
            if (!AudioUplinkMode.isValid(uplinkMode)) return refuse("uplinkMode の値が違う（$uplinkMode）")
            if (shell == null) return refuse("手伝い役の uid が 2000（shell）ではないため、音声を扱わない")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return refuse("Android 14（API 34）以上が要る（AudioTrack.Builder.setContext）")
            }
            val asked = SystemClock.elapsedRealtime()
            val mode = InCallCheck.await(
                readMode = { attempt { asSelf { audio.mode } } },
                now = SystemClock::elapsedRealtime,
                sleep = Thread::sleep,
            )
            Log.i(TAG, "audio bridge: audio mode ${InCallCheck.modeText(mode)} after ${SystemClock.elapsedRealtime() - asked} ms")
            if (mode != UplinkTestRules.MODE_IN_CALL) return refuse(InCallCheck.refusal(mode))
            // Both openers hand back something that is already running, and let go of what they made
            // but could not start (see firstThatStarts). So when one of them throws, nothing of its
            // own is left behind, and the only thing still held is the record from the first.
            val record = try {
                openDownlink(shell, audio)
            } catch (t: Throwable) {
                return refuse("受話を取れなかった: ${failureText(t)}")
            }
            val track = try {
                openUplink(shell, audio, uplinkMode)
            } catch (t: Throwable) {
                attempt { record.stop() }
                attempt { record.release() }
                return refuse("送話を作れなかった: ${failureText(t)}")
            }
            val downlinkOut = ParcelFileDescriptor.AutoCloseOutputStream(downlinkWriteEnd)
            val uplinkIn = ParcelFileDescriptor.AutoCloseInputStream(uplinkReadEnd)
            val downlinkSource = object : DownlinkSource {
                override val nativeRate = record.sampleRate
                override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                override fun isSilenced(): Boolean = attempt {
                    asSelf {
                        val session = record.audioSessionId
                        audio.activeRecordingConfigurations
                            .firstOrNull { it.clientAudioSessionId == session }
                            ?.let(AudioRecordingConfiguration::isClientSilenced)
                    }
                } ?: false
            }
            val uplinkSink = object : UplinkSink {
                override val playbackRate = track.sampleRate
                override fun write(pcm: ShortArray): Int = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                override fun flush() {
                    attempt { track.pause() }
                    attempt { track.flush() }
                    attempt { track.play() }
                }

                // On this thread only (see the note at BridgeSides on who may touch a track). The
                // head's getter can itself re-create a track that became invalid; that would have
                // happened at the next write anyway, and it costs the writing thread the same.
                override fun playbackHead(): Long = attempt { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL } ?: -1L
                override fun underruns(): Int = attempt { track.underrunCount } ?: -1
                override fun portId(): Int = trackPortId(track) ?: -1
            }
            val downlinkRun = DownlinkBridgeRun(downlinkSource, downlinkOut, observer = downlinkObserver) {
                attempt { asSelf { audio.mode } }
            }
            val statsOutput = UplinkStatsOutput(log = { Log.i(TAG, it) }, openFile = { openStatsFile() }).apply { start() }
            val uplinkRun = UplinkBridgeRun(uplinkIn, uplinkSink, stats = UplinkStats(uplinkSink, statsOutput::post))
            Log.i(TAG, "uplink track: ${describeTrack(track)}")
            val sides = BridgeSides(
                downlink = BridgeSides.Side(
                    name = "受話",
                    run = { downlinkRun.run() },
                    requestStop = { downlinkRun.requestStop() },
                    release = {
                        attempt { record.stop() }
                        attempt { record.release() }
                    },
                ),
                uplink = BridgeSides.Side(
                    name = "送話",
                    run = { uplinkRun.run() },
                    requestStop = { uplinkRun.requestStop() },
                    release = {
                        attempt { track.pause() }
                        attempt { track.stop() }
                        attempt { track.release() }
                        statsOutput.close()
                    },
                ),
                closePipes = {
                    attempt { downlinkWriteEnd.close() }
                    attempt { uplinkReadEnd.close() }
                },
                report = { code, detail -> sink.onEvent(code, detail) },
                startThread = { name, body -> startAudioThread(name, body) },
                note = { Log.i(TAG, ReportText.scrub(it)) },
            )
            return CallAudioBridge(sides, sink)
        }

        /** The record of the last call's uplink, for after the call (`adb shell cat`). Counts only. */
        private const val STATS_FILE = "/data/local/tmp/rotep-uplink-stats.csv"

        /** The call before it: one generation is kept. */
        private const val STATS_FILE_PREVIOUS = "/data/local/tmp/rotep-uplink-stats.prev.csv"

        /** The file for [UplinkStatsOutput]; null when it cannot be made (the log still has every line). */
        private fun openStatsFile(): Writer? = attempt {
            val file = File(STATS_FILE)
            if (file.exists()) {
                val previous = File(STATS_FILE_PREVIOUS)
                previous.delete()
                file.renameTo(previous)
            }
            BufferedWriter(FileWriter(file))
        }

        /** Rate, buffer and start threshold of the track that was opened, for the log. */
        private fun describeTrack(track: AudioTrack): String {
            val routed = attempt { track.routedDevice?.let { EnvReport.deviceTypeName(it.type) } } ?: "未確定"
            return "rate=${attempt { track.sampleRate }} bufferFrames=${attempt { track.bufferSizeInFrames }} " +
                "capacityFrames=${attempt { track.bufferCapacityInFrames }} startThresholdFrames=${attempt { track.startThresholdInFrames }} " +
                "routed=$routed portId=${trackPortId(track)}"
        }

        private val getPortId: Method? = findGetPortId()

        /** The hidden native getter, by reflection (the helper is not an app, so this is allowed). */
        @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
        private fun findGetPortId(): Method? = attempt {
            AudioTrack::class.java.getDeclaredMethod("native_getPortId").apply { isAccessible = true }
        }

        private fun trackPortId(track: AudioTrack): Int? = attempt { getPortId?.invoke(track) as? Int }

        private fun startAudioThread(name: String, body: () -> Unit) {
            val t = Thread({
                attempt { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                body()
            }, name)
            t.isDaemon = true
            t.start()
        }

        /**
         * Opens the downlink AudioRecord and starts recording: VOICE_DOWNLINK, the device's own rate
         * first (RateCandidates order). An AudioRecord that was made but does not record is
         * released before the next rate is tried. If no rate works, the first failure is the one
         * reported (the others are attached to it).
         */
        @SuppressLint("MissingPermission")
        private fun openDownlink(shell: ShellContext, audio: AudioManager): AudioRecord {
            val nativeRate = attempt { asSelf { audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() } }
            return firstThatStarts(
                candidates = RateCandidates.order(nativeRate),
                none = "候補のレートのどれでも開けなかった",
                open = { rate -> buildDownlinkRecord(shell, rate) },
                start = { record ->
                    asSelf { record.startRecording() }
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "startRecording の後も録音が始まっていない（recordingState=${record.recordingState}）"
                    }
                },
                release = { record ->
                    attempt { record.stop() }
                    attempt { record.release() }
                },
            )
        }

        /** The AudioRecord at [rate], not recording yet; null when the device has no buffer size for the rate. */
        @SuppressLint("MissingPermission")
        private fun buildDownlinkRecord(shell: ShellContext, rate: Int): AudioRecord? {
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) return null
            val bufferBytes = maxOf(minBytes * 2, rate / 10 * 2 * 4)
            val record = asSelf {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setContext(shell)
                    .build()
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                attempt { record.release() }
                throw IllegalStateException("AudioRecord が初期化されなかった（$rate Hz、state=${record.state}）")
            }
            return record
        }

        /**
         * Opens the uplink AudioTrack and gets it playing. Tries AGENT_RATE_HZ first and lets the
         * system convert; when that fails (the track cannot be made, or does not play), tries
         * [FALLBACK_RATE_HZ] and the helper converts the voice itself (UplinkBridgeRun resamples
         * whenever the track's rate is not AGENT_RATE_HZ). A track that was made but does not play
         * is released before the next try. If both fail, the first failure is the one reported (the
         * other is attached to it).
         */
        private fun openUplink(shell: ShellContext, audio: AudioManager, uplinkMode: Int): AudioTrack = firstThatStarts(
            candidates = listOf(AGENT_RATE_HZ, FALLBACK_RATE_HZ),
            none = "送話のレートの候補がなかった",
            open = { rate -> openUplinkAt(shell, audio, uplinkMode, rate) },
            start = { track ->
                asSelf { track.play() }
                check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    "play の後も再生が始まっていない（playState=${track.playState}）"
                }
            },
            release = { track ->
                attempt { track.pause() }
                attempt { track.stop() }
                attempt { track.release() }
            },
        )

        private fun openUplinkAt(shell: ShellContext, audio: AudioManager, uplinkMode: Int, rate: Int): AudioTrack {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            if (uplinkMode == AudioUplinkMode.REDIRECTION) return openRedirection(audio, format)
            // create() already refuses older versions; asked again here, next to the call that needs it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                throw IllegalStateException("Android 14（API 34）以上が要る（AudioTrack.Builder.setContext）")
            }
            val preferredType = if (uplinkMode == AudioUplinkMode.SPEAKER) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_TELEPHONY
            val preferred = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == preferredType }
                ?: throw IllegalStateException("${EnvReport.deviceTypeName(preferredType)} の出力が見つからない（通話中か確かめる）")
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "getMinBufferSize の答えが $minBytes" }
            val track = asSelf {
                AudioTrack.Builder()
                    .setContext(shell)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBytes, (rate * 2L * UPLINK_BUFFER_MS / 1000).toInt()))
                    .build()
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                attempt { track.release() }
                throw IllegalStateException("AudioTrack が初期化されなかった（state=${track.state}）")
            }
            val accepted = attempt { asSelf { track.setPreferredDevice(preferred) } }
            if (accepted != true) {
                attempt { track.release() }
                throw IllegalStateException("出力先を ${EnvReport.deviceTypeName(preferredType)} にできなかった")
            }
            if (uplinkMode == AudioUplinkMode.DIRECT) {
                // Before play(), in frames of the rate the track really runs at. The value is logged
                // by describeTrack; if it could not be set the track simply keeps the default.
                attempt { track.setStartThresholdInFrames((track.sampleRate * UPLINK_START_THRESHOLD_MS / 1000).toInt()) }
            }
            return track
        }

        @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
        private fun openRedirection(audio: AudioManager, format: AudioFormat): AudioTrack {
            val method = audio.javaClass.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            return try {
                asSelf { method.invoke(audio, format) } as? AudioTrack ?: throw IllegalStateException("AudioTrack が返ってこなかった")
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }
}
