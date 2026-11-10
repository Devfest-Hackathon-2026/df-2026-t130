// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSoundGuardTest {
    private class Device(var muted: Boolean = false, var current: Int = 4, val stepTakesMuteOff: Boolean = true) : CallSoundDevice {
        val sets = ArrayList<Int>()

        /** The audio service does not answer. */
        var unreadable = false

        override fun isMuted(): Boolean {
            check(!unreadable) { "no audio service" }
            return muted
        }

        override fun step() = if (muted) 0 else current

        override fun setStep(step: Int) {
            sets += step
            current = step
            if (stepTakesMuteOff) muted = false
        }

        var raises = 0
        var raiseTakesMuteOff = false

        override fun raise() {
            raises++
            if (raiseTakesMuteOff) muted = false
        }
    }

    private class Memo(override var step: Int? = null) : CallSoundMemo

    @Test
    fun theStepIsWrittenDownBeforeAQuietCall() {
        val memo = Memo()
        CallSoundGuard(Device(current = 3), memo).remember()
        assertEquals(3, memo.step)
    }

    @Test
    fun anEarlierStepIsKept() {
        val memo = Memo(step = 6)
        CallSoundGuard(Device(current = 3), memo).remember()
        assertEquals("the user's own step", 6, memo.step)
    }

    @Test
    fun aStreamThatReadsZeroIsNotWrittenDown() {
        val memo = Memo()
        CallSoundGuard(Device(muted = true), memo).remember()
        assertNull(memo.step)
    }

    @Test
    fun aMuteLeftBehindIsTakenOffWithTheStep() {
        val device = Device(muted = true, current = 1)
        val memo = Memo(step = 5)
        assertTrue(CallSoundGuard(device, memo).restoreIfRemembered())
        assertFalse(device.muted)
        assertEquals(listOf(5), device.sets)
        assertNull(memo.step)
    }

    @Test
    fun nothingIsWrittenWhenTheCallIsNotMuted() {
        // The helper gave the sound back and the user may have changed the volume since
        val device = Device(current = 2)
        val memo = Memo(step = 5)
        assertTrue(CallSoundGuard(device, memo).restoreIfRemembered())
        assertTrue(device.sets.isEmpty())
        assertEquals(2, device.current)
        assertNull(memo.step)
    }

    @Test
    fun whenTheStepDoesNotTakeTheMuteOffTheVolumeKeyDoes() {
        // A Bluetooth call ignores a step
        val device = Device(muted = true, stepTakesMuteOff = false).apply { raiseTakesMuteOff = true }
        val memo = Memo(step = 5)
        assertTrue(CallSoundGuard(device, memo).restoreIfRemembered())
        assertEquals(1, device.raises)
        assertEquals("the key moved it up: put back", listOf(5, 5), device.sets)
        assertNull(memo.step)
    }

    @Test
    fun whenNothingTakesTheMuteOffItIsKeptForTheNextTry() {
        val device = Device(muted = true, stepTakesMuteOff = false)
        val memo = Memo(step = 5)
        assertFalse(CallSoundGuard(device, memo).restoreIfRemembered())
        assertEquals(5, memo.step)
    }

    @Test
    fun whenTheMuteCannotBeReadNothingIsWrittenAndTheStepIsKept() {
        val device = Device(muted = true).apply { unreadable = true }
        val memo = Memo(step = 5)
        assertFalse(CallSoundGuard(device, memo).restoreIfRemembered())
        assertEquals(5, memo.step)
        assertTrue(device.sets.isEmpty())
    }

    @Test
    fun nothingWrittenDownMeansNothingToDo() {
        val device = Device(muted = true)
        assertTrue(CallSoundGuard(device, Memo()).restoreIfRemembered())
        assertTrue(device.sets.isEmpty())
    }
}
