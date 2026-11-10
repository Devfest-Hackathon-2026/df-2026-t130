// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftMicTest {
    private class Rig(var noted: Boolean = false, var muted: Boolean = false, val unmuteTakes: Boolean = true) {
        val left = LeftMic(
            object : LeftMic.Memo {
                override var stopped: Boolean
                    get() = noted
                    set(value) {
                        noted = value
                    }
            },
            object : LeftMic.Mic {
                override var muted: Boolean
                    get() = this@Rig.muted
                    set(value) {
                        if (value || unmuteTakes) this@Rig.muted = value
                    }
            },
        )
    }

    @Test
    fun aMicrophoneTheAppDidNotStopIsNotTouched() {
        val r = Rig(noted = false, muted = true)
        r.left.giveBackIfLeft()
        assertTrue("the user's own, or another app's", r.muted)
    }

    @Test
    fun aMicrophoneLeftBehindIsGivenBackAndTheNoteGoes() {
        val r = Rig(noted = true, muted = true)
        r.left.giveBackIfLeft()
        assertFalse(r.muted)
        assertFalse(r.noted)
    }

    @Test
    fun whenItDoesNotComeBackTheNoteStaysForTheNextTry() {
        val r = Rig(noted = true, muted = true, unmuteTakes = false)
        r.left.giveBackIfLeft()
        assertTrue(r.noted)
    }

    @Test
    fun theNoteIsWrittenAndTakenBack() {
        val r = Rig()
        r.left.note()
        assertTrue(r.noted)
        r.left.forget()
        assertFalse(r.noted)
    }
}
