// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milliseconds from a clock that only goes forward. The wall clock (currentTimeMillis) is not
 * used for pacing: it jumps when the time is set (by the network, or by hand), and a jump back
 * would hold the agent's voice back by as much as the jump.
 */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

/**
 * Whether agent audio is still queued or was only just handed over: true while anything is
 * queued, or for [graceMs] after the last piece was handed over (the helper's own AudioTrack
 * still holds some of it). Pure; [now] is injected so tests do not need real time or threads.
 *
 * The count is touched by up to three threads: the one that enqueues counts up, the sending thread
 * counts down, and a flush (from the enqueuing side) resets it. A plain volatile Int loses updates
 * when two of them meet, and a count that is too low ends [isPlaying] in the middle of the voice,
 * so it is atomic, and a count down never goes below zero.
 */
internal class PlaybackTracker(private val graceMs: Long, private val now: () -> Long) {
    private val queued = AtomicInteger(0)
    @Volatile private var lastSentAt = NEVER

    fun onQueued() {
        queued.incrementAndGet()
    }

    /** One queued item is done. [handedOver]: something of it really went out (starts the grace). */
    fun onDone(handedOver: Boolean) {
        queued.updateAndGet { if (it > 0) it - 1 else 0 }
        if (handedOver) lastSentAt = now()
    }

    /** Everything waiting was dropped, and what was already handed over is cut by the helper. */
    fun onFlushed() {
        queued.set(0)
        lastSentAt = NEVER
    }

    fun isPlaying(): Boolean {
        if (queued.get() > 0) return true
        // Read once: a flush may reset it between the two uses.
        val sent = lastSentAt
        return sent != NEVER && now() - sent < graceMs
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * The agent's voice on its way to the helper: [enqueue] takes what the model sends (any size,
 * 24 kHz), [next] hands out what goes into the uplink pipe, one message at a time, in order.
 *
 * What it takes care of, all of it pure and checked on a PC:
 *  - **20 ms pieces.** Every message is [sliceBytes] long (a shorter one only for the very end of
 *    the voice, when nothing else is queued), so a flush can cut in between pieces.
 *  - **Real-time pacing.** A piece is handed out only when at most [leadMs] of sound is ahead of
 *    what has played by now. Without this the pipe (64 KB, about 1.4 s) fills up in front of the
 *    helper, and an interruption's flush marker would wait behind seconds of old voice, while
 *    [isPlaying] would say "done" long before the voice really is.
 *  - **Flush.** [flush] drops everything waiting and the helper is told once ([AudioPipe.Message.Flush]),
 *    before anything queued afterwards. Pieces not yet handed out are never sent late.
 *  - **[isPlaying].** See [PlaybackTracker]. The grace covers the lead and the helper's buffer.
 *  - **Keep-alive** ([keepOutputAlive]). While no voice waits, a silent piece goes out on the same
 *    clock, up to [KEEP_ALIVE_LEAD_MS] ahead, so the helper's AudioTrack never runs empty. A track
 *    that runs empty is taken off the mixer and its output stopped (AudioFlinger's BUFFER TIMEOUT);
 *    for a track bound to the telephony output that also makes the audio policy invalidate the
 *    track itself (AudioPolicyManager::stopSource), and the next write re-creates it, dropping
 *    whatever was buffered. Silence is not voice: it never touches [isPlaying].
 *
 * [enqueue], [flush] and [isPlaying] may come from any thread; [next] is for one sending thread.
 */
internal class UplinkFeeder(
    sliceBytes: Int = bytesFor20ms(AGENT_RATE_HZ),
    private val leadMs: Long = LEAD_MS,
    graceMs: Long = GRACE_MS,
    /** A clock that only goes forward, in milliseconds; its zero can be anywhere (see [monotonicMillis]). */
    private val now: () -> Long = ::monotonicMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Keeps the helper's track fed with silence while the agent says nothing (see the class comment). */
    private val keepOutputAlive: Boolean = false,
) {
    private class Item(val generation: Int, val pcm: ByteArray)

    private val queue = LinkedBlockingQueue<Item>()
    private val generation = AtomicInteger(0)
    private val tracker = PlaybackTracker(graceMs, now)

    // Only the sending thread touches these.
    private val chunker = FixedChunker(sliceBytes)
    private val ready = ArrayDeque<ByteArray>()
    private var readyGeneration = 0
    private var markedGeneration = 0

    // The clock's own zero is anywhere, so the first anchor is taken from the clock, not from 0.
    // Voice and silence are paced on this one clock: only how far ahead each may go differs.
    private var anchor = now()
    private var handedMs = 0L

    /** What did not make a whole millisecond of [handedMs] yet, in units of 1/(2 * AGENT_RATE_HZ) ms. */
    private var handedRest = 0L

    private val silence = ByteArray(sliceBytes)
    private val silenceMs = sliceBytes * 1000L / (2L * AGENT_RATE_HZ)

    fun enqueue(pcm: ByteArray) {
        // A trailing odd byte is half a sample: dropped, so the pieces stay aligned to samples.
        val even = if (pcm.size % 2 == 0) pcm else pcm.copyOf(pcm.size - 1)
        if (even.isEmpty()) return
        tracker.onQueued()
        queue.put(Item(generation.get(), even))
    }

    fun flush() {
        generation.incrementAndGet()
        queue.clear()
        tracker.onFlushed()
    }

    fun isPlaying(): Boolean = tracker.isPlaying()

    /** The next message for the pipe, or null when nothing came within [timeoutMs]. */
    fun next(timeoutMs: Long): AudioPipe.Message? {
        while (true) {
            val gen = generation.get()
            if (markedGeneration != gen) {
                markedGeneration = gen
                ready.clear()
                chunker.drain()
                handedMs = 0
                handedRest = 0
                anchor = now()
                return AudioPipe.Message.Flush
            }
            if (ready.isNotEmpty()) {
                if (readyGeneration != gen) {
                    ready.clear()
                    continue
                }
                val sinceAnchor = now() - anchor
                val ahead = handedMs - sinceAnchor
                if (ahead > leadMs) {
                    // Sleep in short steps and look at the generation again: a flush during the wait ends it.
                    sleep(minOf(ahead - leadMs, SLEEP_STEP_MS))
                    continue
                }
                if (ahead < 0) anchor = now() - handedMs // went idle: measure the lead from now
                val piece = ready.removeFirst()
                countHanded(piece.size)
                if (ready.isEmpty()) tracker.onDone(handedOver = true)
                return AudioPipe.Message.Chunk(piece)
            }
            // Nothing to hand out now. With keep-alive the wait for voice lasts only until the next
            // silent piece is due, so a flush or the voice is still looked at between silent pieces.
            val untilSilence = if (keepOutputAlive) untilSilenceDue() else Long.MAX_VALUE
            val item = queue.poll(minOf(timeoutMs, untilSilence), TimeUnit.MILLISECONDS)
            if (item == null) {
                if (untilSilence == 0L) {
                    handedMs += silenceMs
                    return AudioPipe.Message.Chunk(silence)
                }
                if (untilSilence > timeoutMs) return null // the caller's wait ended first
                continue // the silent piece has come due meanwhile
            }
            if (item.generation != generation.get()) continue // dropped by a flush while it was being taken
            readyGeneration = item.generation
            ready.addAll(chunker.process(item.pcm))
            // Nothing else is waiting: the last, shorter piece goes out now instead of waiting for more.
            if (queue.isEmpty()) chunker.drain()?.let { ready.addLast(it) }
            if (ready.isEmpty()) tracker.onDone(handedOver = false) // all of it is kept for the next item
        }
    }

    /** Whole milliseconds into [handedMs], the rest kept for the next piece: pieces need not be whole milliseconds. */
    private fun countHanded(bytes: Int) {
        val units = bytes * 1000L + handedRest
        handedMs += units / (2L * AGENT_RATE_HZ)
        handedRest = units % (2L * AGENT_RATE_HZ)
    }

    /** Milliseconds until the next silent piece may go out, 0 when it may go now. */
    private fun untilSilenceDue(): Long {
        val ahead = handedMs - (now() - anchor)
        if (ahead < 0) {
            anchor = now() - handedMs // nothing went out for a while (a stall): measure the lead from now
            return 0
        }
        return maxOf(0L, ahead - KEEP_ALIVE_LEAD_MS)
    }

    companion object {
        /** How much sound may be in the pipe ahead of the playing position. */
        const val LEAD_MS = 120L

        /** Lead, the helper's AudioTrack buffer, and some margin. */
        const val GRACE_MS = LEAD_MS + UPLINK_BUFFER_MS + 180L

        /** How far ahead silence goes while the agent says nothing: enough to cover a mixer period. */
        const val KEEP_ALIVE_LEAD_MS = 80L

        private const val SLEEP_STEP_MS = 10L

        /**
         * The feeder for the helper's direct track (TYPE_TELEPHONY): keep-alive, and voice handed out
         * as far ahead as the track holds. Keep-alive and the track's lower start threshold
         * ([UPLINK_START_THRESHOLD_MS]) go together with this lead: the buffer the voice plays from
         * stays as deep as it was while the whole buffer had to fill before playing, and it is the
         * lead that fills it now, not the threshold. [isPlaying]'s grace is the default one: what
         * can still be unplayed after the last piece stays within the buffer.
         */
        fun forTelephonyOutput(
            now: () -> Long = ::monotonicMillis,
            sleep: (Long) -> Unit = { Thread.sleep(it) },
        ): UplinkFeeder = UplinkFeeder(leadMs = UPLINK_BUFFER_MS, now = now, sleep = sleep, keepOutputAlive = true)
    }
}
