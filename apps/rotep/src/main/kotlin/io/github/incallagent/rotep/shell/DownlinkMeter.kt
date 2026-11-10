// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens to the far end of the call (VOICE_DOWNLINK) and reports only how loud it is.
 *
 * The audio is read into a small buffer, measured and thrown away: it is never written anywhere and
 * never sent anywhere. Everything runs on a thread of its own, not on a Binder thread.
 *
 * The recording is made under the shell identity ([ShellContext]); see there for why.
 */
internal class DownlinkMeter(
    private val shell: ShellContext,
    private val audio: AudioManager,
    private val sink: Sink,
    private val maxMillis: Long = MAX_MILLIS,
) {
    interface Sink {
        fun onStarted(report: String)
        fun onLevel(level: Level, totalFrames: Long)

        /** Called exactly once, after the recording is released. */
        fun onStopped(report: String, reason: String)
    }

    private class Opened(val record: AudioRecord, val rate: Int, val tried: List<String>)

    private class OpenFailure(val tried: List<String>) : Exception("AudioRecord を作れなかった")

    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    val isRunning: Boolean get() = thread?.isAlive == true

    fun start() {
        val t = Thread({ runMeter() }, "downlink-meter")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Asks the meter thread to stop. It reports by itself. */
    fun requestStop() {
        stopRequested.set(true)
    }

    fun stopAndWait(timeoutMillis: Long) {
        requestStop()
        val t = thread ?: return
        if (t !== Thread.currentThread()) {
            try {
                t.join(timeoutMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runMeter() {
        var opened: Opened? = null
        var reason = REASON_STOP_BUTTON
        var report = ""
        try {
            val o = open()
            opened = o
            val record = o.record
            asSelf { record.startRecording() }
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "startRecording の後も録音が始まっていない（recordingState=${record.recordingState}）"
            }
            waitUntilListed(record)
            sink.onStarted(EnvReport.formatMeter(facts(record, o, "開始")))
            reason = readLoop(record, o.rate)
            // Taken before stop(): once the recording is stopped the system no longer lists it.
            report = EnvReport.formatMeter(facts(record, o, "停止"))
        } catch (e: OpenFailure) {
            reason = "失敗: ${e.message}"
            report = EnvReport.formatMeter(failureFacts(e.tried))
        } catch (t: Throwable) {
            reason = "失敗: ${ReportText.exceptionLine(t)}"
            val o = opened
            report = if (o != null) {
                EnvReport.formatMeter(facts(o.record, o, "失敗"))
            } else {
                EnvReport.formatMeter(failureFacts(emptyList()))
            }
        } finally {
            val o = opened
            attempt { o?.record?.stop() }
            attempt { o?.record?.release() }
        }
        Log.i(TAG, "meter ended: $reason")
        sink.onStopped(report, reason)
    }

    /**
     * Tries the rates in [RateCandidates] order; the first one that gives a working AudioRecord wins.
     *
     * Lint's permission check does not apply: the recording is made as the shell user (see
     * [ShellContext]), which holds CAPTURE_AUDIO_OUTPUT and RECORD_AUDIO itself. The app does not
     * declare them. A refusal shows up as an exception and is reported on the screen.
     */
    @SuppressLint("MissingPermission")
    private fun open(): Opened {
        val nativeRate = attempt { asSelf { audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() } }
        val tried = ArrayList<String>()
        for (rate in RateCandidates.order(nativeRate)) {
            try {
                val minBytes = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
                if (minBytes <= 0) {
                    tried += "$rate: 最小バッファ $minBytes"
                    continue
                }
                // At least 400 ms of room, so that a late read does not lose samples.
                val bufferBytes = maxOf(minBytes * 2, rate / 10 * BYTES_PER_FRAME * 4)
                val record = asSelf {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(ENCODING)
                                .setSampleRate(rate)
                                .setChannelMask(CHANNEL)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .setContext(shell)
                        .build()
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    tried += "$rate: 初期化されなかった"
                    continue
                }
                tried += "$rate: 使えた"
                return Opened(record, rate, tried)
            } catch (t: Throwable) {
                tried += "$rate: ${ReportText.exceptionLine(t)}"
            }
        }
        throw OpenFailure(tried)
    }

    /** Waits a moment (up to ~250 ms) until the system lists the recording, so the start report can show it. */
    private fun waitUntilListed(record: AudioRecord) {
        repeat(5) {
            if (ownConfig(record) != null) return
            Thread.sleep(50)
        }
    }

    private fun readLoop(record: AudioRecord, rate: Int): String {
        val frames = LevelMeter.windowFrames(rate)
        val buffer = ShortArray(frames)
        var total = 0L
        val window = LevelWindow(frames) { level -> sink.onLevel(level, total) }
        val begin = SystemClock.elapsedRealtime()
        while (true) {
            if (stopRequested.get()) return REASON_STOP_BUTTON
            if (SystemClock.elapsedRealtime() - begin >= maxMillis) return "${maxMillis / 1000} 秒で自動停止"
            // Non-blocking, so the stop request and the time limit are looked at every few milliseconds.
            val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            when {
                n > 0 -> {
                    total += n
                    window.feed(buffer, n)
                }
                n == 0 -> Thread.sleep(IDLE_SLEEP_MS)
                else -> return "読み取りの失敗（${readErrorName(n)}）"
            }
        }
    }

    private fun ownConfig(record: AudioRecord): AudioRecordingConfiguration? = attempt {
        asSelf {
            val session = record.audioSessionId
            audio.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == session }
        }
    }

    private fun facts(record: AudioRecord, opened: Opened, phase: String): MeterFacts {
        val config = ownConfig(record)
        return MeterFacts(
            phase = phase,
            audioSource = attempt { record.audioSource },
            sampleRate = attempt { record.sampleRate },
            triedRates = opened.tried,
            routedDeviceType = attempt { record.routedDevice?.type },
            configFound = config != null,
            clientAudioSource = config?.let { attempt { it.clientAudioSource } },
            silenced = config?.let { attempt { it.isClientSilenced } },
            configDeviceType = config?.let { attempt { it.audioDevice?.type } },
            attributionPackage = shell.attributionPackage,
            attributionUid = shell.attributionUid,
        )
    }

    private fun failureFacts(tried: List<String>) = MeterFacts(
        phase = "失敗",
        audioSource = null,
        sampleRate = null,
        triedRates = tried,
        routedDeviceType = null,
        configFound = null,
        clientAudioSource = null,
        silenced = null,
        configDeviceType = null,
        attributionPackage = shell.attributionPackage,
        attributionUid = shell.attributionUid,
    )

    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> "code $code"
    }

    companion object {
        private const val TAG = "RotepHelper"
        const val MAX_MILLIS = 60_000L
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 2
        private const val IDLE_SLEEP_MS = 5L
        const val REASON_STOP_BUTTON = "止めるボタン"
    }
}
