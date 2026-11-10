// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQuietTest {
    private class FakeStream(
        var muted: Boolean = false,
        var step: Int = 5,
        /** false: the system ignores the mute without an error (a caller it does not accept). */
        val muteTakes: Boolean = true,
    ) : VoiceCallStream {
        val actions = ArrayList<String>()

        override fun isMuted() = muted

        override fun index() = if (muted) 0 else step

        override fun mute() {
            actions += "mute"
            if (muteTakes) muted = true
        }

        override fun unmute() {
            actions += "unmute"
            muted = false
        }

        override fun setIndex(index: Int) {
            actions += "set:$index"
            step = index
        }
    }

    private fun quiet(stream: VoiceCallStream) = CallQuiet(stream) {}

    // --- the order at the end of the audio bridge ---

    @Test
    fun theEndIsReportedBeforeTheSoundComesBack() {
        // The pipes are closed by then, and the app takes a pipe that ends without a report for a dead helper
        for (code in listOf(AudioBridgeEvent.STOPPED, AudioBridgeEvent.SILENCED, AudioBridgeEvent.FAILED)) {
            val order = ArrayList<String>()
            reportThenGiveSoundBack(code, report = { order += "report" }, giveSoundBack = { order += "sound" })
            assertEquals(listOf("report", "sound"), order)
        }
    }

    @Test
    fun theStartOfTheBridgeLeavesTheSoundAlone() {
        val order = ArrayList<String>()
        reportThenGiveSoundBack(AudioBridgeEvent.STARTED, report = { order += "report" }, giveSoundBack = { order += "sound" })
        assertEquals(listOf("report"), order)
    }

    @Test
    fun theSoundComesBackEvenWhenTheReportFails() {
        var sound = false
        runCatching { reportThenGiveSoundBack(AudioBridgeEvent.STOPPED, report = { error("no app") }, giveSoundBack = { sound = true }) }
        assertTrue(sound)
    }

    @Test
    fun itMutesAndGivesTheSoundBackWithoutTouchingTheVolume() {
        val s = FakeStream()
        val q = quiet(s)
        assertTrue(q.engage())
        assertTrue(q.engaged)
        assertTrue(s.muted)
        q.release()
        assertFalse(q.engaged)
        assertFalse(s.muted)
        assertEquals(listOf("mute", "unmute"), s.actions)
        assertEquals(5, s.step)
    }

    @Test
    fun aMuteItDidNotSetIsLeftAlone() {
        val s = FakeStream(muted = true)
        val q = quiet(s)
        assertFalse(q.engage())
        q.release()
        assertTrue("still the user's mute", s.muted)
        assertTrue(s.actions.isEmpty())
    }

    @Test
    fun aMuteThatDoesNotTakeIsNotCountedAsQuiet() {
        val s = FakeStream(muteTakes = false)
        val q = quiet(s)
        assertFalse(q.engage())
        assertFalse(q.engaged)
        q.release()
        assertEquals("nothing to take off", listOf("mute"), s.actions)
    }

    @Test
    fun aStepIsNeverWrittenWhenTheUnmuteTakes() {
        // Steps belong to the output in use: after the call this could be another output's step
        val s = FakeStream(step = 2)
        val q = quiet(s)
        q.engage()
        s.step = 4
        q.release()
        assertEquals(4, s.step)
        assertEquals(listOf("mute", "unmute"), s.actions)
    }

    @Test
    fun whenTheUnmuteDoesNotTakeTheStepIsSetToTakeTheMuteOff() {
        val s = object : VoiceCallStream {
            var muted = false
            val actions = ArrayList<String>()

            override fun isMuted() = muted

            override fun index() = if (muted) 0 else 3

            override fun mute() {
                muted = true
            }

            override fun unmute() {
                actions += "unmute"
            }

            override fun setIndex(index: Int) {
                actions += "set:$index"
                muted = false
            }
        }
        val q = quiet(s)
        q.engage()
        q.release()
        assertEquals(listOf("unmute", "set:3"), s.actions)
        assertFalse(s.muted)
    }

    @Test
    fun releasingTwiceTakesTheMuteOffOnce() {
        val s = FakeStream()
        val q = quiet(s)
        q.engage()
        q.release()
        q.release()
        assertEquals(listOf("mute", "unmute"), s.actions)
    }

    @Test
    fun anEngageAfterTheReleaseDoesNothing() {
        // The delayed engage can run after the AI has already handed the call over
        val s = FakeStream()
        val q = quiet(s)
        q.release()
        assertFalse(q.engage())
        assertFalse(s.muted)
        assertTrue(s.actions.isEmpty())
    }

    @Test
    fun aFailureToMuteDoesNotEscape() {
        val s = object : VoiceCallStream by FakeStream() {
            override fun mute() = throw SecurityException("no")
        }
        assertFalse(quiet(s).engage())
    }

    // --- SilentDownlinkWatch ---

    private class Clock(var now: Long = 0)

    private val zeros = ShortArray(320)
    private val speech = ShortArray(320) { 100 }

    @Test
    fun digitalSilenceAfterTheMuteGivesTheSoundBackOnce() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(speech, speech.size)
        w.arm()
        w.feed(zeros, zeros.size)
        clock.now = SilentDownlinkWatch.HEARD_LIMIT_MS - 1
        w.feed(zeros, zeros.size)
        assertEquals(0, silent)
        clock.now = SilentDownlinkWatch.HEARD_LIMIT_MS
        w.feed(zeros, zeros.size)
        assertEquals(1, silent)
        clock.now += 10_000
        w.feed(zeros, zeros.size)
        assertEquals("once", 1, silent)
    }

    @Test
    fun anySoundKeepsTheMuteOn() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(speech, speech.size)
        w.arm()
        w.feed(zeros, zeros.size)
        clock.now = 2_000
        w.feed(speech, speech.size)
        clock.now = 4_000
        w.feed(zeros, zeros.size)
        clock.now = 5_000
        w.feed(zeros, zeros.size)
        assertEquals("the silence started again at 4 s", 0, silent)
    }

    @Test
    fun silenceBeforeTheMuteDoesNotCount() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(zeros, zeros.size)
        clock.now = 60_000
        w.feed(zeros, zeros.size)
        assertEquals(0, silent)
    }

    @Test
    fun aLineThatWasSilentBeforeTheMuteGetsLonger() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(zeros, zeros.size)
        w.arm()
        w.feed(zeros, zeros.size)
        clock.now = SilentDownlinkWatch.HEARD_LIMIT_MS
        w.feed(zeros, zeros.size)
        assertEquals("nothing was heard before, so it may just be quiet", 0, silent)
        clock.now = SilentDownlinkWatch.NOT_HEARD_LIMIT_MS
        w.feed(zeros, zeros.size)
        assertEquals(1, silent)
    }

    @Test
    fun onlyTheFramesReadCount() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        val partly = ShortArray(320) { if (it < 160) 0 else 100 }
        w.feed(speech, speech.size)
        w.arm()
        w.feed(partly, 160)
        clock.now = SilentDownlinkWatch.HEARD_LIMIT_MS
        w.feed(partly, 160)
        assertEquals("the unread rest of the buffer is not looked at", 1, silent)
    }

    @Test
    fun readsThatBringNothingGiveTheSoundBackQuickly() {
        // One reported phone stopped delivering audio and the call was cut about 2 s later
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(speech, speech.size)
        w.arm()
        w.feed(zeros, 0)
        clock.now = SilentDownlinkWatch.NO_DATA_LIMIT_MS - 1
        w.feed(zeros, 0)
        assertEquals(0, silent)
        clock.now = SilentDownlinkWatch.NO_DATA_LIMIT_MS
        w.feed(zeros, 0)
        assertEquals(1, silent)
    }

    @Test
    fun readsThatBringNothingDoNotCountBeforeTheMuteOrAfterAudioReturns() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(zeros, 0)
        clock.now = 60_000
        w.feed(zeros, 0)
        w.arm()
        w.feed(zeros, 0)
        clock.now += 500
        w.feed(speech, speech.size)
        clock.now += 500
        w.feed(zeros, 0)
        clock.now += 500
        w.feed(zeros, 0)
        assertEquals(0, silent)
    }

    @Test
    fun disarmingStopsTheWatch() {
        val clock = Clock()
        var silent = 0
        val w = SilentDownlinkWatch({ clock.now }) { silent++ }
        w.feed(speech, speech.size)
        w.arm()
        w.feed(zeros, zeros.size)
        w.disarm()
        clock.now = 60_000
        w.feed(zeros, zeros.size)
        assertEquals(0, silent)
    }
}
