// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class UplinkStatsTest {
    private class Counters(override val playbackRate: Int = 24_000) : UplinkSink {
        var head = 0L
        var underrunsSoFar = 0
        var port = 7

        override fun write(pcm: ShortArray): Int = pcm.size

        override fun flush() = Unit

        override fun playbackHead(): Long = head

        override fun underruns(): Int = underrunsSoFar

        override fun portId(): Int = port
    }

    private class Rig {
        var t = 1_000L
        val lines = ArrayList<String>()
        val counters = Counters()
        val stats = UplinkStats(counters, { lines += it }, { t })

        fun second(writes: Int = 50, frames: Int = 480, voice: Boolean = false, tookMs: Long = 2) {
            repeat(writes) { stats.onWrite(frames, frames, tookMs, voice) }
            t += 1_000
            stats.tick()
        }
    }

    @Test
    fun aLineCarriesOnlyNumbersInTheOrderOfTheHeader() {
        val rig = Rig()
        rig.second()
        assertEquals(1, rig.lines.size)
        val line = rig.lines.single()
        assertTrue("got: $line", line.matches(Regex("-?\\d+(,-?\\d+)*")))
        assertEquals(UplinkStats.HEADER.split(",").size, line.split(",").size)
    }

    @Test
    fun oneSecondOfPlayingIsCountedAsItWent() {
        val rig = Rig()
        rig.counters.head = 1_000 // the head and the counters are read at the first write
        repeat(50) { rig.stats.onWrite(480, 480, 3, voice = true) }
        rig.counters.head = 24_500
        rig.counters.underrunsSoFar = 2
        rig.t += 1_000
        rig.stats.tick()
        assertEquals("1,50,24000,24000,23500,2,0,0,0,0,3", rig.lines.single())
    }

    @Test
    fun aWindowOfSilenceHasNoVoiceFrames() {
        val rig = Rig()
        rig.second(voice = false)
        assertEquals("0", rig.lines.single().split(",")[3])
    }

    @Test
    fun shortAndFailedWritesAreCountedApart() {
        val rig = Rig()
        rig.stats.onWrite(480, 200, 1, voice = true)
        rig.stats.onWrite(480, -6, 1, voice = true)
        rig.stats.onWrite(480, 480, 40, voice = false)
        rig.t += 1_000
        rig.stats.tick()
        val fields = rig.lines.single().split(",")
        assertEquals("3", fields[1]) // writes
        assertEquals("680", fields[2]) // frames that went in
        assertEquals("200", fields[3]) // of them voice
        assertEquals("1", fields[7]) // short
        assertEquals("1", fields[8]) // failed
        assertEquals("40", fields[10]) // the longest write
    }

    @Test
    fun aNewPortIsNoticedAndCountedOncePerWindow() {
        val rig = Rig()
        rig.stats.onWrite(480, 480, 1, false)
        rig.counters.port = 9
        rig.t += 1_000
        rig.stats.tick()
        rig.stats.onWrite(480, 480, 1, false)
        rig.t += 1_000
        rig.stats.tick()
        assertEquals("1", rig.lines[0].split(",")[6])
        assertEquals("0", rig.lines[1].split(",")[6])
    }

    @Test
    fun aHeadThatWrapsAroundThe32BitCounterStillAdvances() {
        val rig = Rig()
        rig.counters.head = 0xFFFF_FFF0L
        rig.stats.onWrite(480, 480, 1, false)
        rig.counters.head = 0x10L
        rig.t += 1_000
        rig.stats.tick()
        assertEquals("32", rig.lines.single().split(",")[4])
    }

    @Test
    fun aFlushBringsTheHeadBackAndIsCounted() {
        val rig = Rig()
        rig.counters.head = 5_000
        rig.stats.onWrite(480, 480, 1, false)
        rig.stats.onFlush()
        rig.counters.head = 480
        rig.t += 1_000
        rig.stats.tick()
        val fields = rig.lines.single().split(",")
        assertEquals("-4520", fields[4])
        assertEquals("1", fields[9])
    }

    @Test
    fun whatTheTrackDoesNotTellStaysUnknown() {
        var t = 0L
        val lines = ArrayList<String>()
        val unknown = object : UplinkSink {
            override val playbackRate = 24_000
            override fun write(pcm: ShortArray) = pcm.size
            override fun flush() = Unit
        }
        val stats = UplinkStats(unknown, { lines += it }, { t })
        stats.onWrite(480, 480, 1, false)
        t += 1_000
        stats.tick()
        val fields = lines.single().split(",")
        assertEquals("-1", fields[4])
        assertEquals("-1", fields[5])
        assertEquals("-1", fields[6])
    }

    @Test
    fun nothingIsEmittedBeforeAWindowIsOver() {
        val rig = Rig()
        rig.stats.onWrite(480, 480, 1, false)
        rig.t += 999
        rig.stats.tick()
        assertTrue(rig.lines.isEmpty())
    }

    @Test
    fun theLastShorterWindowComesOutAtTheEnd() {
        val rig = Rig()
        rig.stats.onWrite(480, 480, 1, true)
        rig.t += 300
        rig.stats.finish()
        assertEquals(1, rig.lines.size)
        // Nothing was written since: no empty window after it.
        rig.stats.finish()
        assertEquals(1, rig.lines.size)
    }

    @Test
    fun nothingHappenedMeansNoLineAtAll() {
        val rig = Rig()
        rig.stats.tick()
        rig.stats.finish()
        assertTrue(rig.lines.isEmpty())
    }

    @Test
    fun theOutputWritesHeaderAndLinesAndDoesNotBlockThePoster() {
        val logged = ArrayList<String>()
        val file = StringWriter()
        val out = UplinkStatsOutput({ synchronized(logged) { logged += it } }, { file })
        out.start()
        out.post("0,1,2,3,4,5,6,7,8,9,10")
        out.post("1,1,2,3,4,5,6,7,8,9,10")
        out.close()
        out.awaitEnd(5_000)
        assertEquals(UplinkStats.HEADER + "\n0,1,2,3,4,5,6,7,8,9,10\n1,1,2,3,4,5,6,7,8,9,10\n", file.toString())
        assertEquals(2, synchronized(logged) { logged.size })
    }

    @Test
    fun aFullQueueDropsLinesInsteadOfWaiting() {
        // A writer that cannot be opened yet: the queue fills, the poster must not wait.
        val out = UplinkStatsOutput({}, { null }, queueSize = 2)
        repeat(100) { out.post("x") }
        out.close() // room is made for the end marker
        out.start()
        out.awaitEnd(5_000)
    }

    @Test
    fun aFileThatCannotBeWrittenIsNotAProblemForAnyone() {
        val out = UplinkStatsOutput({}, { throw java.io.IOException("no") })
        out.start()
        out.post("0,1,2,3,4,5,6,7,8,9,10")
        out.close()
        out.awaitEnd(5_000)
    }
}
