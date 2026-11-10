/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson (BCR, capture approach)
 * SPDX-FileCopyrightText: 2026 incall-agent contributors
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Capture approach follows BCR RecorderThread.recordUntilCancelled
 * (https://github.com/chenxiaolong/BCR, commit 8c218fa): AudioRecord on a voice call source,
 * URGENT_AUDIO thread, buffer = several times the minimum. BCR encodes; here raw PCM goes to WAV.
 */

package io.github.incallagent.audiobridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/**
 * Records the call downlink (far-end voice) to a WAV file at the device's own rate (ADR-008).
 *
 * Tries VOICE_DOWNLINK first and VOICE_CALL as a fallback, each at the native output rate and
 * then 48000 / 44100 / 32000 / 16000 / 8000 Hz. The first combination that initialises, starts
 * recording and returns data is used; every attempt is logged.
 */
class DownlinkRecorder(
    context: Context,
    private val outputDir: File,
    private val log: BridgeLog,
    private val listener: Listener,
) {
    enum class State { STARTING, RECORDING, FAILED, STOPPED }

    fun interface Listener {
        fun onStateChanged(recorder: DownlinkRecorder, state: State, detail: String)
    }

    data class Selection(
        val source: Int,
        val requestedRate: Int,
        val actualRate: Int,
        val bufferBytes: Int,
        val routedDevice: String,
    ) {
        override fun toString() = "source=${AudioNames.audioSource(source)} requestedRate=$requestedRate " +
            "actualRate=$actualRate bufferBytes=$bufferBytes routedDevice=$routedDevice"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile var state: State = State.STARTING
        private set
    @Volatile var selection: Selection? = null
        private set
    @Volatile var file: File? = null
        private set
    @Volatile var framesWritten: Long = 0
        private set
    /** Peak absolute sample over the whole recording (0..32767). */
    @Volatile var peak: Int = 0
        private set

    @Volatile private var cancelled = false
    private val thread = Thread(::run, "DownlinkRecorder")

    fun start() = thread.start()

    fun stop(timeoutMs: Long = 2_000) {
        cancelled = true
        if (Thread.currentThread() != thread) {
            thread.join(timeoutMs)
        }
    }

    val isAlive: Boolean get() = thread.isAlive

    private fun setState(s: State, detail: String) {
        state = s
        log.log("downlink $s $detail")
        listener.onStateChanged(this, s, detail)
    }

    fun candidateRates(): List<Int> {
        val native = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        return (listOfNotNull(native) + listOf(48_000, 44_100, 32_000, 16_000, 8_000)).distinct()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        setState(State.STARTING, "candidates=${candidateRates()}")

        val opened = open()
        if (opened == null) {
            setState(State.FAILED, "no source/rate combination worked (see attempts above)")
            return
        }
        val (record, firstChunk, firstCount) = opened
        AudioResources.recordCreated()

        try {
            val sel = selection!!
            val ts = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val name = "downlink-$ts-${AudioNames.audioSource(sel.source).substringBefore('(')}-${sel.actualRate}.wav"
            val out = File(outputDir, name)
            file = out

            WavWriter(out, sel.actualRate).use { wav ->
                setState(State.RECORDING, "$sel file=${out.name}")
                wav.write(firstChunk, 0, firstCount)
                track(firstChunk, firstCount)
                loop(record, wav, sel.actualRate)
                framesWritten = wav.framesWritten
            }
        } catch (t: Throwable) {
            setState(State.FAILED, "record: ${HiddenApis.describe(t)}")
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            AudioResources.recordReleased()
            if (state != State.FAILED) {
                setState(State.STOPPED, "released frames=$framesWritten " +
                    "seconds=${selection?.let { framesWritten / it.actualRate.toDouble() }} " +
                    "peak=${dbfs(peak)} file=${file?.name}")
            }
        }
    }

    private data class Opened(val record: AudioRecord, val chunk: ShortArray, val count: Int)

    @SuppressLint("MissingPermission") // RECORD_AUDIO + CAPTURE_AUDIO_OUTPUT checked by the caller
    private fun open(): Opened? {
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.VOICE_CALL)) {
            for (rate in candidateRates()) {
                if (cancelled) return null
                val tag = "attempt ${AudioNames.audioSource(source)} @ $rate"
                val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    log.log("downlink $tag: getMinBufferSize=$minBuf -> skip")
                    continue
                }
                val bufferBytes = minBuf * 6
                val record = try {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (t: Throwable) {
                    log.log("downlink $tag: constructor threw ${HiddenApis.describe(t)}")
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    log.log("downlink $tag: state=${record.state} (not initialized)")
                    record.release()
                    continue
                }
                try {
                    record.startRecording()
                } catch (t: Throwable) {
                    log.log("downlink $tag: startRecording threw ${HiddenApis.describe(t)}")
                    record.release()
                    continue
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    log.log("downlink $tag: recordingState=${record.recordingState}")
                    record.stop()
                    record.release()
                    continue
                }
                val chunk = ShortArray(SineTone.samplesFor(20, record.sampleRate).coerceAtLeast(1))
                val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    log.log("downlink $tag: first read=$n")
                    record.stop()
                    record.release()
                    continue
                }
                selection = Selection(source, rate, record.sampleRate, bufferBytes, AudioNames.device(record.routedDevice))
                log.log("downlink $tag: OK format=${record.format} firstRead=$n")
                return Opened(record, chunk, n)
            }
        }
        return null
    }

    private fun loop(record: AudioRecord, wav: WavWriter, rate: Int) {
        val chunk = ShortArray(SineTone.samplesFor(20, rate).coerceAtLeast(1))
        var windowPeak = 0
        var lastReport = SystemClock.elapsedRealtime()

        while (!cancelled) {
            val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                throw IllegalStateException("AudioRecord.read failed: $n")
            }
            wav.write(chunk, 0, n)
            framesWritten = wav.framesWritten
            windowPeak = maxOf(windowPeak, track(chunk, n))

            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= 5_000) {
                log.log("downlink level: t=${framesWritten / rate}s windowPeak=${dbfs(windowPeak)} totalPeak=${dbfs(peak)}")
                windowPeak = 0
                lastReport = now
            }
        }
    }

    /** Update [peak] and return the chunk peak. */
    private fun track(chunk: ShortArray, n: Int): Int {
        var p = 0
        for (i in 0 until n) {
            val v = abs(chunk[i].toInt())
            if (v > p) p = v
        }
        if (p > peak) peak = p
        return p
    }

    companion object {
        fun dbfs(peak: Int): String =
            if (peak <= 0) "-inf dBFS" else "%.1f dBFS".format(Locale.US, 20 * log10(peak / 32767.0))
    }
}
