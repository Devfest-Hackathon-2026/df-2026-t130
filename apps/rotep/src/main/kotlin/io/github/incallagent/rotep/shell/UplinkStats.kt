// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import java.io.Writer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

// The record of how the uplink AudioTrack did during a call, one line per second: the numbers a test
// call needs to tell a re-created track from an underrun from an audio HAL that is not keeping real
// time. It holds counts only: no audio, no number, no words (ADR-021).

/**
 * Counts what the uplink side writes and, once per [periodMs], reads the real track's counters and
 * hands one CSV line (see [HEADER]) to [emit]. Everything here runs on the uplink thread and touches
 * nothing but counters and the track's own getters (`AudioTrack` is only touched by its own thread),
 * so [emit] must not block: the helper's one queues the line for [UplinkStatsOutput].
 *
 * The record of the playback head is the track's own, so a track that was re-created jumps forward by
 * what was lost at that moment (`mPosition = mReleased` in AudioTrack::restoreTrack_l) and a flush
 * brings it back: a second with `head` well above the rate, or below zero with a flush, is that.
 */
internal class UplinkStats(
    private val track: UplinkSink,
    private val emit: (String) -> Unit,
    private val now: () -> Long = ::monotonicMillis,
    private val periodMs: Long = 1_000L,
) {
    private var startedAt = NEVER
    private var windowStart = NEVER

    private var writes = 0
    private var frames = 0L
    private var voiceFrames = 0L
    private var shortWrites = 0
    private var failedWrites = 0
    private var flushes = 0
    private var longestWriteMs = 0L

    private var lastHead = UNKNOWN
    private var lastUnderruns = -1
    private var lastPort = -1

    /**
     * One write to the track: [frames] asked for, [result] what `write` answered (frames, or a
     * negative error), [tookMs] how long it blocked, [voice] whether the piece held any sound.
     */
    fun onWrite(frames: Int, result: Int, tookMs: Long, voice: Boolean) {
        begin()
        writes++
        if (result < 0) {
            failedWrites++
        } else {
            this.frames += result
            if (voice) voiceFrames += result
            if (result < frames) shortWrites++
        }
        if (tookMs > longestWriteMs) longestWriteMs = tookMs
        tick()
    }

    /** A flush went to the track. */
    fun onFlush() {
        begin()
        flushes++
    }

    /** Emits the line of the window that is over, if one is. Called after every piece. */
    fun tick() {
        if (startedAt == NEVER) return
        val t = now()
        if (t - windowStart >= periodMs) closeWindow(t)
    }

    /** The bridge side is ending: the last, shorter window goes out too. */
    fun finish() {
        if (startedAt != NEVER && (writes > 0 || flushes > 0)) closeWindow(now())
    }

    private fun begin() {
        if (startedAt == NEVER) {
            startedAt = now()
            windowStart = startedAt
            lastHead = track.playbackHead()
            lastUnderruns = track.underruns()
            lastPort = track.portId()
        }
    }

    private fun closeWindow(t: Long) {
        val head = track.playbackHead()
        val underruns = track.underruns()
        val port = track.portId()
        val headAdvance = if (head < 0 || lastHead < 0) UNKNOWN else advance(head, lastHead)
        val underrunsNew = if (underruns < 0 || lastUnderruns < 0) -1 else underruns - lastUnderruns
        val portChanged = if (port < 0 || lastPort < 0) -1 else if (port != lastPort) 1 else 0
        lastHead = head
        lastUnderruns = underruns
        lastPort = port
        emit(
            "${(t - startedAt) / 1_000},$writes,$frames,$voiceFrames,$headAdvance,$underrunsNew," +
                "$portChanged,$shortWrites,$failedWrites,$flushes,$longestWriteMs",
        )
        windowStart = t
        writes = 0
        frames = 0
        voiceFrames = 0
        shortWrites = 0
        failedWrites = 0
        flushes = 0
        longestWriteMs = 0
    }

    companion object {
        const val HEADER = "sec,writes,frames,voice_frames,head_advance,underruns_new,port_changed,short_writes,failed_writes,flushes,longest_write_ms"

        private const val NEVER = Long.MIN_VALUE
        private const val UNKNOWN = -1L

        /** How far the head moved, keeping in mind that the track counts in a wrapping 32-bit int. */
        private fun advance(head: Long, last: Long): Long {
            val diff = (head - last).toInt()
            return diff.toLong()
        }
    }
}

/**
 * Takes the lines of [UplinkStats] off the audio thread: [post] only queues (a full queue drops the
 * line, it never waits), a background thread writes them to the helper's log and to a file made by
 * [openFile] (on that same thread, so opening it does not delay the audio either).
 */
internal class UplinkStatsOutput(
    private val log: (String) -> Unit,
    private val openFile: () -> Writer?,
    private val queueSize: Int = QUEUE_SIZE,
) {
    private val queue = ArrayBlockingQueue<Entry>(queueSize)
    private val thread: Thread = Thread({ pump() }, "uplink-stats")

    private sealed interface Entry
    private data class Line(val text: String) : Entry
    private data object Close : Entry

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    /** Never blocks. A line that does not fit is dropped. */
    fun post(line: String) {
        queue.offer(Line(line))
    }

    /** Asks the writer to finish what is queued and close the file. Does not wait for it. */
    fun close() {
        // Make room for the end marker: losing a line of statistics at the very end is fine.
        if (!queue.offer(Close)) {
            queue.poll()
            queue.offer(Close)
        }
    }

    /** Blocks until the writer has ended (tests; the audio side never calls this). */
    fun awaitEnd(timeoutMs: Long) {
        thread.join(timeoutMs)
    }

    private fun pump() {
        var file: Writer? = null
        try {
            file = openFile()
            file?.append(UplinkStats.HEADER)?.append('\n')
            while (true) {
                val entry = queue.poll(STOP_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (entry !is Line) break
                log("uplink stats ${entry.text}")
                file?.append(entry.text)?.append('\n')
                file?.flush()
            }
        } catch (t: Throwable) {
            attempt { log("uplink stats could not be saved: ${t.javaClass.simpleName}") }
        } finally {
            attempt { file?.close() }
        }
    }

    private companion object {
        const val QUEUE_SIZE = 64
        const val STOP_POLL_MS = 500L
    }
}
