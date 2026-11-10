// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import io.github.incallagent.rotep.shell.CallPicture.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPictureTest {
    private fun block(id: Int, ringing: Int, foreground: Int, background: Int) = """
        Phone Id=$id
          mCallState=2
          mRingingCallState=$ringing
          mForegroundCallState=$foreground
          mBackgroundCallState=$background
          mPreciseCallState=Ringing call state: 0
          mCallIncomingNumber=03-0000-0000
          mServiceState=something long
    """.trimIndent()

    private fun dump(vararg blocks: String) = (listOf("last known state:") + blocks + "local logs:" + "more later").joinToString("\n").lineSequence()

    private fun picture(ringing: Int, foreground: Int, background: Int) = CallPicture.of(listOf(Phone(ringing, foreground, background)))

    @Test
    fun theThreeStateLinesOfEachPhoneAreRead() {
        val text = dump(block(0, 0, 1, 0), block(1, -1, -1, -1))
        assertEquals(listOf(Phone(0, 1, 0), Phone(-1, -1, -1)), CallPicture.phones(text))
    }

    @Test
    fun aDumpThatDoesNotLookRightIsUnknownNotAGuess() {
        assertEquals(CallPicture.UNKNOWN, CallPicture.of(CallPicture.phones(emptySequence())))
        assertEquals(CallPicture.UNKNOWN, CallPicture.of(CallPicture.phones(sequenceOf("something else"))))
        val missingLine = dump(block(0, 0, 1, 0).lines().filter { "mBackground" !in it }.joinToString("\n"))
        assertEquals(CallPicture.UNKNOWN, CallPicture.of(CallPicture.phones(missingLine)))
        val notANumber = dump(block(0, 0, 1, 0).replace("mForegroundCallState=1", "mForegroundCallState=x"))
        assertEquals(CallPicture.UNKNOWN, CallPicture.of(CallPicture.phones(notANumber)))
    }

    /** The dump as it stands when the output stops before the end of the per-phone part. */
    private fun cutShort(vararg blocks: String) = (listOf("last known state:") + blocks).joinToString("\n").lineSequence()

    @Test
    fun aDumpTheTimeLimitCutOffIsUnknownNotJustItsFirstPhones() {
        // The call can be on the second SIM, which the dump never got to
        val noCallOnTheFirst = CallPicture.phones(cutShort(block(0, 0, 0, 0)), cutOff = { true })
        assertEquals(CallPicture.UNKNOWN, CallPicture.of(noCallOnTheFirst))
        assertFalse(CallPicture.mayEndCall(CallPicture.phones(cutShort(block(0, 0, 1, 0)), cutOff = { true })))
    }

    @Test
    fun aDumpThatReachedItsEndCountsEvenIfTheLimitCameAfterwards() {
        assertEquals(listOf(Phone(0, 1, 0)), CallPicture.phones(dump(block(0, 0, 1, 0)), cutOff = { true }))
    }

    @Test
    fun aDumpThatEndedByItselfIsReadWithoutTheEndMarker() {
        // The marker is one line of the system's text: a phone without it must not lose the call's picture
        assertEquals(listOf(Phone(0, 1, 0)), CallPicture.phones(cutShort(block(0, 0, 1, 0)), cutOff = { false }))
    }

    @Test
    fun aPhoneThatNeverHadACallCountsAsNoCall() {
        assertEquals(CallPicture.NONE, picture(0, 0, 0))
        assertEquals(CallPicture.NONE, picture(-1, -1, -1))
    }

    @Test
    fun dialingAndAlertingAreNotAnAnswerOnlyActiveIs() {
        assertEquals(CallPicture.DIALING, picture(0, 3, 0))
        assertEquals(CallPicture.DIALING, picture(0, 4, 0))
        assertEquals(CallPicture.ACTIVE, picture(0, 1, 0))
    }

    @Test
    fun aCallGoingAwayIsNotACall() {
        assertEquals(CallPicture.ENDING, picture(0, 8, 0))
        assertEquals(CallPicture.NONE, picture(0, 7, 0))
    }

    @Test
    fun onHoldOrASecondCallIsNotOperated() {
        assertEquals(CallPicture.OTHER, picture(0, 1, 2))
        assertEquals(CallPicture.OTHER, picture(0, 0, 2))
        assertEquals(CallPicture.OTHER, CallPicture.of(listOf(Phone(0, 1, 0), Phone(0, 1, 0))))
    }

    @Test
    fun onlyRingingIsNotAPlacedCallAndCallWaitingChangesNothing() {
        assertEquals(CallPicture.RINGING_ONLY, picture(5, 0, 0))
        assertEquals(CallPicture.RINGING_ONLY, picture(6, 0, 0))
        assertEquals("call waiting", CallPicture.ACTIVE, picture(6, 1, 0))
    }

    @Test
    fun theCallIsOnlyEndedWhenItIsTheOnlyOne() {
        assertTrue(CallPicture.mayEndCall(listOf(Phone(0, 1, 0))))
        assertTrue(CallPicture.mayEndCall(listOf(Phone(0, 3, 0), Phone(-1, -1, -1))))
        assertFalse("something rings too", CallPicture.mayEndCall(listOf(Phone(6, 1, 0))))
        assertFalse("on hold", CallPicture.mayEndCall(listOf(Phone(0, 1, 2))))
        assertFalse("another SIM", CallPicture.mayEndCall(listOf(Phone(0, 1, 0), Phone(0, 1, 0))))
        assertFalse("nothing to end", CallPicture.mayEndCall(listOf(Phone(0, 0, 0))))
        assertFalse("unknown", CallPicture.mayEndCall(emptyList()))
    }
}
