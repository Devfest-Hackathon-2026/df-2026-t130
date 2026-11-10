// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.audiobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSpeakerTest {
    private val slice = 960 // 20 ms at 24 kHz

    @Test
    fun slicesAreWholeSamplesOfAtMostOneSlice() {
        assertEquals(960, LocalSpeaker.sliceLength(4_800, slice))
        assertEquals(100, LocalSpeaker.sliceLength(100, slice))
        assertEquals(100, LocalSpeaker.sliceLength(101, slice))
        assertEquals(0, LocalSpeaker.sliceLength(1, slice))
        assertEquals(0, LocalSpeaker.sliceLength(0, slice))
    }

    @Test
    fun anOddChunkEndsAfterItsWholeSamples() {
        // The offsets the write loop goes through: it stops at the lone last byte instead of spinning
        var offset = 0
        val sizes = ArrayList<Int>()
        while (offset < 1_921) {
            val len = LocalSpeaker.sliceLength(1_921 - offset, slice)
            if (len == 0) break
            sizes += len
            offset += len
        }
        assertEquals(listOf(960, 960), sizes)
        assertEquals(1_920, offset)
    }
}
