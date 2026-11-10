// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the AudioTrack / AudioRecord instances created by this module that have not been
 * released yet. Used by the PoC to confirm that nothing is left behind after a call ends.
 */
object AudioResources {
    private val tracks = AtomicInteger()
    private val records = AtomicInteger()

    data class Snapshot(val liveTracks: Int, val liveRecords: Int) {
        val isClean: Boolean get() = liveTracks == 0 && liveRecords == 0
        override fun toString() = "liveAudioTracks=$liveTracks liveAudioRecords=$liveRecords"
    }

    internal fun trackCreated() { tracks.incrementAndGet() }
    internal fun trackReleased() { tracks.decrementAndGet() }
    internal fun recordCreated() { records.incrementAndGet() }
    internal fun recordReleased() { records.decrementAndGet() }

    fun snapshot() = Snapshot(tracks.get(), records.get())
}
