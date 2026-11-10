// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the model's voice on the phone (for trying without a call; later the uplink injector
 * takes its place). 16-bit little-endian mono PCM, default 24 kHz (Live API output).
 *
 * [flush] drops everything not yet played: call it when the model is interrupted.
 *
 * When it cannot play (no AudioTrack, a write error, an exception on its thread), it stops for
 * good: [onError] is called once, nothing more is queued and [isPlaying] turns false. Pass
 * [onError] by name.
 */
class LocalSpeaker(
    private val log: BridgeLog,
    val sampleRate: Int = 24_000,
    /** Called once from the speaker thread when it cannot play any more. */
    private val onError: ((detail: String) -> Unit)? = null,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var cancelled = false
    @Volatile private var flushRequested = false
    @Volatile private var lastWriteAt = 0L
    private val thread = Thread(::run, "LocalSpeaker")

    /** 20 ms of 16-bit mono audio, even number of bytes. */
    private val sliceBytes = (sampleRate / 50) * 2

    fun start() = thread.start()

    fun stop(timeoutMs: Long = 1_000) {
        cancelled = true
        queue.clear()
        if (Thread.currentThread() != thread) thread.join(timeoutMs)
    }

    fun enqueue(pcm16le: ByteArray) {
        if (!cancelled) queue.put(pcm16le)
    }

    /** Stop playing now and drop the queued audio. */
    fun flush() {
        queue.clear()
        flushRequested = true
    }

    /** true while audio is queued or was written in the last [graceMs] (for half-duplex mode). */
    fun isPlaying(graceMs: Long = 300): Boolean =
        queue.isNotEmpty() || SystemClock.elapsedRealtime() - lastWriteAt < graceMs

    /** Milliseconds of audio waiting in the queue. */
    fun queuedMs(): Long = queue.sumOf { it.size.toLong() } / 2 * 1000 / sampleRate

    private fun fail(detail: String) {
        log.log("speaker: $detail")
        if (!cancelled) onError?.invoke(detail)
    }

    private fun run() {
        try {
            play()
        } catch (t: Throwable) {
            // Uncaught here, it would end the whole app in the middle of a conversation
            fail("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // However the loop ended: nothing more is queued, and isPlaying() turns false
            cancelled = true
            queue.clear()
        }
    }

    private fun play() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            fail("AudioTrack failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        AudioResources.trackCreated()
        log.log("speaker: started rate=$sampleRate")
        try {
            track.play()
            while (!cancelled) {
                if (flushRequested) {
                    flushRequested = false
                    track.pause()
                    track.flush()
                    track.play()
                }
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                // Server chunks can be hundreds of ms long: write in ~20 ms slices so an
                // interruption (flush) stops the voice almost at once
                var offset = 0
                while (offset < chunk.size && !flushRequested && !cancelled) {
                    val len = sliceLength(chunk.size - offset, sliceBytes)
                    if (len == 0) break // an odd last byte is not a whole sample
                    val n = track.write(chunk, offset, len, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) {
                        fail("write error $n")
                        return
                    }
                    if (n == 0) break // nothing taken: drop the rest of this chunk rather than spin
                    offset += n
                    lastWriteAt = SystemClock.elapsedRealtime()
                }
            }
        } finally {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
            AudioResources.trackReleased()
            log.log("speaker: stopped")
        }
    }

    internal companion object {
        /** Bytes to write next: at most one slice, whole 16-bit samples only (0 for a lone odd byte). */
        fun sliceLength(remaining: Int, sliceBytes: Int): Int = minOf(sliceBytes, remaining) and 1.inv()
    }
}
