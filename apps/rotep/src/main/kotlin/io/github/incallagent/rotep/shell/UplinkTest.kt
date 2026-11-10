// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// The uplink tests (the procedure is apps/rotep/a3.md): the helper plays the marker tone in one of
// five ways and records what the system did with it.
//
// The course of a test and its record ([UplinkTestRun]) are plain Kotlin and run in unit tests. The
// part that talks to the system is at the end ([UplinkTests], [UplinkTest]). The rules are in
// UplinkTestRules.kt and the words of the record in UplinkReport.kt.

/** What a test needs from the system. The helper gives the real one ([UplinkTest]); unit tests give a fake. */
internal interface UplinkEnv {
    /** A clock that only goes forward, in milliseconds. */
    fun nowMs(): Long

    fun sleepMs(ms: Long)

    /** The wall clock for the record ("HH:mm:ss"). */
    fun clockText(): String

    /** The audio mode, or null when it cannot be read. */
    fun audioMode(): Int?

    fun conditions(kind: UplinkTestKind): UplinkConditions

    /** Makes the track for [kind], not playing yet. Throws when it cannot be made. */
    fun open(kind: UplinkTestKind, sampleRate: Int): UplinkOutput
}

/** The AudioTrack of one test. */
internal interface UplinkOutput {
    val facts: UplinkTrackFacts

    /** Starts playing. Throws when the track does not play. */
    fun play()

    /** Type of the device the track plays to now, or null when there is none (yet). */
    fun routedDeviceType(): Int?

    /** Writes buffer[0 until count] and waits while the track is full. Negative: an AudioTrack error code. */
    fun write(buffer: ShortArray, count: Int): Int

    /** Stops at once and lets the track go. Called exactly once, also after a failure. */
    fun close(): UplinkEndFacts
}

/**
 * The course of one uplink test: the start condition, the delayed start, the sound for at most
 * [UplinkTestRequest.playSeconds], and the record. Runs on the thread that calls [run];
 * [requestStop] may come from any thread. The record goes to [sink] at every phase, and
 * [UplinkPhase.ENDED] comes exactly once, at the end of [run].
 */
internal class UplinkTestRun(
    private val request: UplinkTestRequest,
    private val env: UplinkEnv,
    private val sink: Sink,
    /** Which test-3 run of this helper this is; null for the other tests. */
    private val redirectionRun: Int? = null,
) {
    fun interface Sink {
        fun onPhase(phase: Int, log: String)
    }

    private val stopRequested = AtomicBoolean(false)
    private val lines = ArrayList<String>()
    private var framesWritten = 0L
    private var routed: Int? = null
    private var routeReported = false

    /** Stops the sound at once, or the wait for the delayed start. */
    fun requestStop() {
        stopRequested.set(true)
    }

    fun run() {
        val reason = try {
            steps()
        } catch (t: Throwable) {
            "失敗: ${ReportText.exceptionLine(unwrapInvocation(t))}"
        }
        lines += UplinkReport.reasonLine(reason)
        sink.onPhase(UplinkPhase.ENDED, text())
    }

    private fun text(): String = lines.joinToString("\n")

    /** Returns why the test ended. */
    private fun steps(): String {
        lines += UplinkReport.header(request, env.clockText())
        UplinkTestRules.refusal(request.kind, env.audioMode())?.let { return it }
        if (request.delaySeconds > 0) {
            lines += "待ち始めた時刻: ${env.clockText()}（${request.delaySeconds} 秒あとに鳴らす）"
            sink.onPhase(UplinkPhase.WAITING, text())
            if (!waitForStart()) return REASON_STOPPED_BEFORE
        }
        if (stopRequested.get()) return REASON_STOPPED_BEFORE
        val conditions = env.conditions(request.kind)
        lines += UplinkReport.conditionLines(request.kind, conditions, redirectionRun)
        // Asked again: the call may have ended during the delay.
        UplinkTestRules.refusal(request.kind, conditions.audioMode)?.let { return it }
        val output = try {
            env.open(request.kind, MarkerTone.SAMPLE_RATE)
        } catch (t: Throwable) {
            lines += "作れなかった: ${ReportText.exceptionLine(unwrapInvocation(t))}"
            return REASON_OPEN_FAILED
        }
        lines += UplinkReport.trackLines(output.facts)
        val preferred = output.facts.preferredDeviceType
        if (preferred != null && output.facts.preferredDeviceAccepted != true) {
            // Played anyway, the tone would go to the default output: the test would not be the one asked for.
            closeQuietly(output)
            return "出力先を ${EnvReport.deviceTypeName(preferred)} にできなかったので、鳴らさない"
        }
        var played = false
        var reason: String
        try {
            output.play()
            played = true
            lines += "始めた時刻: ${env.clockText()}"
            sink.onPhase(UplinkPhase.PLAYING, text())
            reason = playLoop(output, preferred)
        } catch (t: Throwable) {
            reason = "失敗: ${ReportText.exceptionLine(unwrapInvocation(t))}"
        } finally {
            if (played && !routeReported) {
                // The test ended before the route was reported: say what it is now.
                lines += UplinkReport.routeLine(routeNow(output), preferred)
                routeReported = true
            }
            val end = closeQuietly(output)
            lines += "止めた時刻: ${env.clockText()}"
            lines += UplinkReport.endLine(framesWritten, end)
        }
        return reason
    }

    private fun waitForStart(): Boolean {
        val startAt = env.nowMs() + request.delaySeconds * 1000L
        while (!stopRequested.get()) {
            val left = startAt - env.nowMs()
            if (left <= 0) return true
            env.sleepMs(minOf(left, WAIT_STEP_MS))
        }
        return false
    }

    private fun playLoop(output: UplinkOutput, preferred: Int?): String {
        val rate = MarkerTone.SAMPLE_RATE
        val cycle = MarkerCycle(MarkerTone.pattern(request.levelDbfs.toDouble(), rate), MarkerTone.cycleFrames(rate))
        val chunk = ShortArray(MarkerTone.frames(CHUNK_MS, rate))
        val playMs = request.playSeconds * 1000L
        val playFrames = request.playSeconds.toLong() * rate
        val begin = env.nowMs()
        var nextModeCheck = begin + MODE_CHECK_MS
        var nextRouteCheck = begin
        while (true) {
            if (stopRequested.get()) return REASON_STOP_BUTTON
            val now = env.nowMs()
            if (now - begin >= playMs || framesWritten >= playFrames) return "${request.playSeconds} 秒で自動停止"
            if (request.kind.needsCall && now >= nextModeCheck) {
                nextModeCheck = now + MODE_CHECK_MS
                val mode = env.audioMode()
                if (mode != UplinkTestRules.MODE_IN_CALL) {
                    return "音声のモードが ${mode?.let(EnvReport::audioModeName) ?: "分からない"} になった（通話が終わった見込み）"
                }
            }
            if (now >= nextRouteCheck) {
                checkRoute(output, preferred, now - begin)
                nextRouteCheck = now + if (routeReported) ROUTE_CHECK_MS else FIRST_ROUTE_STEP_MS
            }
            val n = minOf(chunk.size.toLong(), playFrames - framesWritten).toInt()
            cycle.read(chunk, n)
            val written = output.write(chunk, n)
            when {
                written > 0 -> framesWritten += written
                written == 0 -> env.sleepMs(IDLE_SLEEP_MS)
                else -> return "書き込みの失敗（${UplinkReport.writeErrorName(written)}）"
            }
        }
    }

    private fun checkRoute(output: UplinkOutput, preferred: Int?, sinceStartMs: Long) {
        val type = routeNow(output)
        if (!routeReported) {
            // Right after play() the route may not be known yet: wait up to ROUTE_WAIT_MS for it.
            if (type == null && sinceStartMs < ROUTE_WAIT_MS) return
            routeReported = true
            routed = type
            lines += UplinkReport.routeLine(type, preferred)
            sink.onPhase(UplinkPhase.PLAYING, text())
        } else if (type != routed) {
            lines += UplinkReport.routeChangeLine(env.clockText(), routed, type)
            routed = type
            sink.onPhase(UplinkPhase.PLAYING, text())
        }
    }

    private fun routeNow(output: UplinkOutput): Int? = try {
        output.routedDeviceType()
    } catch (_: Throwable) {
        null
    }

    private fun closeQuietly(output: UplinkOutput): UplinkEndFacts? = try {
        output.close()
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** One write: 20 ms of sound. */
        const val CHUNK_MS = 20
        const val WAIT_STEP_MS = 100L
        const val MODE_CHECK_MS = 500L
        const val ROUTE_CHECK_MS = 500L
        const val FIRST_ROUTE_STEP_MS = 50L
        const val ROUTE_WAIT_MS = 1_000L
        const val IDLE_SLEEP_MS = 5L
        const val REASON_STOP_BUTTON = "止めるボタン"
        const val REASON_STOPPED_BEFORE = "止めるボタン（鳴らす前）"
        const val REASON_OPEN_FAILED = "失敗: AudioTrack を作れなかった"
    }
}

// --- The system ----------------------------------------------------------------------------------

/** Starts uplink tests in the helper, or refuses them (with a record) when they cannot run here. */
internal object UplinkTests {
    private const val TAG = "RotepHelper"
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    /** Test-3 runs made by this helper process (the procedure allows two). */
    private val redirectionRuns = AtomicInteger(0)

    fun clockText(): String = LocalTime.now().format(CLOCK)

    /**
     * Makes the test, not started yet. When it cannot run here, the reason goes to [sink] as an
     * [UplinkPhase.ENDED] record and the answer is null.
     */
    fun create(
        shell: ShellContext?,
        audio: AudioManager,
        test: Int,
        delaySeconds: Int,
        playSeconds: Int,
        levelDbfs: Int,
        sink: UplinkTestRun.Sink,
    ): UplinkTest? {
        val request = UplinkTestRules.request(test, delaySeconds, playSeconds, levelDbfs)
            ?: return refuse(null, "試験の番号が違う（$test）", sink)
        if (shell == null) return refuse(request, "手伝い役の uid が 2000（shell）ではないため、鳴らさない", sink)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return refuse(request, "Android 14（API 34）以上が要る（AudioTrack に shell の名義を付けるため）", sink)
        }
        val run = if (request.kind.method == UplinkMethod.REDIRECTION) redirectionRuns.incrementAndGet() else null
        return UplinkTest(shell, audio, request, sink, run)
    }

    private fun refuse(request: UplinkTestRequest?, reason: String, sink: UplinkTestRun.Sink): UplinkTest? {
        Log.i(TAG, "uplink test refused: ${ReportText.scrub(reason)}")
        sink.onPhase(UplinkPhase.ENDED, UplinkReport.refusedText(request, clockText(), reason))
        return null
    }
}

/**
 * One uplink test inside the helper: plays the marker tone on a thread of its own and records what
 * the system did. The course of the test is [UplinkTestRun]; this class gives it the real system.
 *
 * Every AudioTrack carries the shell identity ([ShellContext] goes to AudioTrack.Builder.setContext),
 * so that the system routes and allows it as the shell user's. The exception is test 3: there
 * AudioManager makes the track itself and takes no Context, and the record says so. Every call into
 * the system is wrapped with [asSelf]. Needs Android 14 (API 34, AudioTrack.Builder.setContext);
 * [UplinkTests.create] refuses older versions.
 */
internal class UplinkTest(
    private val shell: ShellContext,
    private val audio: AudioManager,
    request: UplinkTestRequest,
    sink: UplinkTestRun.Sink,
    redirectionRun: Int?,
) {
    private val run = UplinkTestRun(request, SystemEnv(), sink, redirectionRun)

    @Volatile
    private var thread: Thread? = null

    /** True from [start] until the test has ended (also while it waits for its delayed start). */
    val isActive: Boolean get() = thread?.isAlive == true

    fun start() {
        val t = Thread({
            attempt { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            try {
                run.run()
            } catch (e: Throwable) {
                // Only the message to the app can fail here; the track is already let go.
                Log.w(TAG, "uplink test: ${ReportText.exceptionLine(e)}")
            }
            Log.i(TAG, "uplink test ended")
        }, "uplink-test")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Stops the sound at once, or the wait for the delayed start. The record arrives by itself. */
    fun requestStop() {
        run.requestStop()
    }

    private inner class SystemEnv : UplinkEnv {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()

        override fun sleepMs(ms: Long) = Thread.sleep(ms)

        override fun clockText(): String = UplinkTests.clockText()

        override fun audioMode(): Int? = attempt { asSelf { audio.mode } }

        override fun conditions(kind: UplinkTestKind) = UplinkConditions(
            audioMode = attempt { asSelf { audio.mode } },
            microphoneMuted = attempt { asSelf { audio.isMicrophoneMute } },
            communicationDeviceType = attempt { asSelf { audio.communicationDevice?.type } },
            telephonyOutput = attempt { output(AudioDeviceInfo.TYPE_TELEPHONY) != null },
            voiceCallVolume = attempt {
                asSelf {
                    "${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} / ${audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}"
                }
            },
            pstnInterceptable = if (kind.method == UplinkMethod.REDIRECTION) pstnInterceptable() else null,
        )

        override fun open(kind: UplinkTestKind, sampleRate: Int): UplinkOutput = when (kind.method) {
            UplinkMethod.DEFAULT_ROUTE -> openTrack(sampleRate, null)
            UplinkMethod.DIRECT -> openTrack(sampleRate, requireOutput(AudioDeviceInfo.TYPE_TELEPHONY))
            UplinkMethod.SPEAKER -> openTrack(sampleRate, requireOutput(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            UplinkMethod.REDIRECTION -> openRedirection(sampleRate)
        }
    }

    /** The same AudioTrack for tests 0, 1, 2 and 4: only the chosen output differs. */
    private fun openTrack(sampleRate: Int, preferred: AudioDeviceInfo?): UplinkOutput {
        // UplinkTests.create already refuses older versions; asked again here for the API check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            throw IllegalStateException("Android 14（API 34）以上が要る（AudioTrack.Builder.setContext）")
        }
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "getMinBufferSize の答えが $minBytes" }
        // At least 100 ms of room, so that a late write does not leave a gap.
        val bufferBytes = maxOf(minBytes * 2, sampleRate / 10 * BYTES_PER_FRAME)
        val track = asSelf {
            AudioTrack.Builder()
                .setContext(shell)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(monoFormat(sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            attempt { track.release() }
            throw IllegalStateException("AudioTrack が初期化されなかった（state=${track.state}）")
        }
        // Asked for before play(): the output is chosen when the track starts.
        val accepted = preferred?.let { device -> attempt { asSelf { track.setPreferredDevice(device) } } }
        return TrackOutput(
            track,
            UplinkTrackFacts(
                maker = "AudioTrack.Builder（setContext に shell の Context）",
                attributes = "USAGE_VOICE_COMMUNICATION・CONTENT_TYPE_SPEECH",
                attributionGiven = true,
                attributionPackage = shell.attributionPackage,
                attributionUid = shell.attributionUid,
                preferredDeviceType = preferred?.type,
                preferredDeviceAccepted = accepted,
                sampleRate = attempt { track.sampleRate },
                bufferFrames = attempt { track.bufferSizeInFrames },
            ),
        )
    }

    /**
     * Test 3. AudioManager.getCallUplinkInjectionAudioTrack is a system API that the SDK does not
     * list, so it is called by reflection (the helper is not an app process, so the system does not
     * block it). AudioManager builds the track itself: no Context can be given to it. When the call
     * ends, AudioManager releases the track by itself; writing then fails and the test ends.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun openRedirection(sampleRate: Int): UplinkOutput {
        val method = audio.javaClass.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
        val track = try {
            asSelf { method.invoke(audio, monoFormat(sampleRate)) } as? AudioTrack
                ?: throw IllegalStateException("AudioTrack が返ってこなかった")
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return TrackOutput(
            track,
            UplinkTrackFacts(
                maker = "AudioManager.getCallUplinkInjectionAudioTrack（反射）",
                attributes = "USAGE_CALL_ASSISTANT・CONTENT_TYPE_SPEECH（AudioManager の中で決まる）",
                attributionGiven = false,
                attributionPackage = null,
                attributionUid = null,
                preferredDeviceType = null,
                preferredDeviceAccepted = null,
                sampleRate = attempt { track.sampleRate },
                bufferFrames = attempt { track.bufferSizeInFrames },
            ),
        )
    }

    /** isPstnCallAudioInterceptable(), a system API: called by reflection. Only a text for the record. */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun pstnInterceptable(): String = try {
        asSelf { audio.javaClass.getMethod("isPstnCallAudioInterceptable").invoke(audio) }?.toString() ?: "null"
    } catch (_: NoSuchMethodException) {
        "この版にはない"
    } catch (e: InvocationTargetException) {
        "取得できなかった（${ReportText.exceptionLine(e.targetException ?: e)}）"
    } catch (e: Throwable) {
        "取得できなかった（${e.javaClass.simpleName}）"
    }

    // Only the types are looked at. Product names and addresses could identify a person.
    private fun output(type: Int): AudioDeviceInfo? =
        asSelf { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.firstOrNull { it.type == type }

    private fun requireOutput(type: Int): AudioDeviceInfo =
        output(type) ?: throw IllegalStateException("${EnvReport.deviceTypeName(type)} の出力が見つからない（通話中か確かめる）")

    private fun monoFormat(sampleRate: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private class TrackOutput(private val track: AudioTrack, override val facts: UplinkTrackFacts) : UplinkOutput {
        override fun play() {
            asSelf { track.play() }
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                "play の後も再生が始まっていない（playState=${track.playState}）"
            }
        }

        override fun routedDeviceType(): Int? = attempt { asSelf { track.routedDevice?.type } }

        override fun write(buffer: ShortArray, count: Int): Int = track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)

        override fun close(): UplinkEndFacts {
            val head = attempt { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }
            val underruns = attempt { track.underrunCount }
            // Cut at once (pause and flush), then let go. In test 3 the system may have released the
            // track already, so every step stands on its own.
            attempt { track.pause() }
            attempt { track.flush() }
            attempt { track.stop() }
            attempt { track.release() }
            return UplinkEndFacts(head, underruns)
        }
    }

    private companion object {
        const val TAG = "RotepHelper"
        const val BYTES_PER_FRAME = 2
    }
}
