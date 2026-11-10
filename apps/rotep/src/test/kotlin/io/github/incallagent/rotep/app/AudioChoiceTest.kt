// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import io.github.incallagent.rotep.app.RotepApp.Stage
import io.github.incallagent.rotep.call.ShellMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChoiceTest {
    // --- 電話のしかた: three choices over the audio and the control ---

    @Test
    fun theThreeChoicesSetTheAudioAndTheControl() {
        assertEquals(AudioChoice.LOCAL, CallChoice.PRACTICE.audio)
        assertEquals(AudioChoice.SHELL_DIRECT, CallChoice.CALL.audio)
        assertEquals(ControlChoice.MANUAL, CallChoice.CALL.control)
        assertEquals(AudioChoice.SHELL_DIRECT, CallChoice.AUTO_CALL.audio)
        assertEquals(ControlChoice.AUTO, CallChoice.AUTO_CALL.control)
        assertEquals(3, CallChoice.entries.size)
    }

    @Test
    fun aChoiceIsReadBackFromTheSettingsItMade() {
        for (c in CallChoice.entries) assertEquals(c.name, c, Settings().with(c).callChoice)
    }

    @Test
    fun aPracticeStaysAPracticeWhateverTheControlWas() {
        // Saved by an earlier version: "自動" with no call
        assertEquals(CallChoice.PRACTICE, Settings(audio = AudioChoice.LOCAL, control = ControlChoice.AUTO).callChoice)
    }

    @Test
    fun theFirstStartIsAPractice() {
        assertEquals(CallChoice.PRACTICE, Settings().callChoice)
    }

    @Test
    fun choosingLeavesTheOtherSettingsAlone() {
        val s = Settings(keepMicOn = true, slowSpeech = true).with(CallChoice.CALL)
        assertTrue(s.keepMicOn)
        assertTrue(s.slowSpeech)
    }

    @Test
    fun theWordsOnTheScreenAreNotTheDevelopersWords() {
        val shown = CallChoice.entries.map { it.label } + Stage.entries.map { it.label } + KEEP_MIC_ON_NOTE
        for (t in shown) assertFalse(t, Regex("L[0-3]|A3|direct|redirection|音声の通り道|手伝い役").containsMatchIn(t))
    }

    @Test
    fun onlyTheDirectWayIsLeftInTheSettings() {
        assertEquals(setOf(null, ShellMode.DIRECT), AudioChoice.entries.map { it.shell }.toSet())
    }

    // --- what earlier versions saved ---

    @Test
    fun theChoicesTakenAwayAreReadAsTheOneLeft() {
        assertEquals(SavedAudio(AudioChoice.LOCAL, keepMicOn = false), readSavedAudio("LOCAL", keepMicOn = false))
        assertEquals(SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn = false), readSavedAudio("SHELL_DIRECT", keepMicOn = false))
        assertEquals(SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn = false), readSavedAudio("SHELL_REDIRECTION", keepMicOn = false))
        assertEquals(SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn = false), readSavedAudio("SHELL_SPEAKER", keepMicOn = false))
    }

    @Test
    fun theOldChoiceWithTheMicrophoneOnBecomesTheSwitch() {
        assertEquals(SavedAudio(AudioChoice.SHELL_DIRECT, keepMicOn = true), readSavedAudio("SHELL_DIRECT_MIC_ON", keepMicOn = false))
    }

    @Test
    fun nothingSavedOrANameNotKnownIsAPractice() {
        assertEquals(SavedAudio(AudioChoice.LOCAL, keepMicOn = false), readSavedAudio(null, keepMicOn = false))
        assertEquals(SavedAudio(AudioChoice.LOCAL, keepMicOn = false), readSavedAudio("SOMETHING_NEW", keepMicOn = false))
    }

    @Test
    fun theSwitchIsReadAsItWasSaved() {
        assertTrue(readSavedAudio("SHELL_DIRECT", keepMicOn = true).keepMicOn)
    }

    // --- 端末のマイクを止めない ---

    @Test
    fun theNoteAboutTheMicrophoneIsThereOnlyWithTheSwitchInACall() {
        val note = Settings(audio = AudioChoice.SHELL_DIRECT, keepMicOn = true).micNote
        assertNotNull(note)
        assertTrue(note!!, note.contains("静かな部屋"))
        assertTrue(note, note.contains("端末のマイクを止めない"))
        assertFalse(note, note.contains("ミュート"))
        assertNull(Settings(audio = AudioChoice.SHELL_DIRECT).micNote)
        assertNull("no call in a practice", Settings(audio = AudioChoice.LOCAL, keepMicOn = true).micNote)
    }

    // --- keepsCallQuiet: whether the phone stays silent while the AI has the call ---

    @Test
    fun aCallIsKeptQuietByDefault() {
        assertFalse("the default is not to play the call", Settings().playCallAudio)
        assertTrue(keepsCallQuiet(Settings(audio = AudioChoice.SHELL_DIRECT)))
        assertTrue(keepsCallQuiet(Settings(audio = AudioChoice.SHELL_DIRECT, keepMicOn = true)))
    }

    @Test
    fun theUserCanChooseToHearTheCall() {
        assertFalse(keepsCallQuiet(Settings(audio = AudioChoice.SHELL_DIRECT, playCallAudio = true)))
    }

    @Test
    fun aPracticeIsNeverKeptQuiet() {
        assertFalse("no call", keepsCallQuiet(Settings(audio = AudioChoice.LOCAL)))
    }

    @Test
    fun aRealCallNeedsAndroid14BeforeItIsApproved() {
        for (choice in listOf(CallChoice.CALL, CallChoice.AUTO_CALL)) {
            val settings = Settings(audio = choice.audio, control = choice.control)
            for (sdk in 31..33) assertEquals("$choice $sdk", UserMessages.CALL_NEEDS_ANDROID_14, callAudioProblem(settings, sdk))
            for (sdk in listOf(34, 35, 37)) assertNull("$choice $sdk", callAudioProblem(settings, sdk))
        }
    }

    @Test
    fun aPracticeRunsFromAndroid12() {
        val settings = Settings(audio = CallChoice.PRACTICE.audio, control = CallChoice.PRACTICE.control)
        for (sdk in 31..37) assertNull("$sdk", callAudioProblem(settings, sdk))
    }

    @Test
    fun whatToDoOnAnOlderPhoneIsSaid() {
        assertTrue(UserMessages.CALL_NEEDS_ANDROID_14.contains("Android 14"))
        assertTrue(UserMessages.CALL_NEEDS_ANDROID_14.contains("「練習」"))
    }

    @Test
    fun aCallThatDialsIsNotPlacedWithoutAnyNetwork() {
        for (stage in listOf(Stage.L2, Stage.L3)) {
            assertEquals("$stage", UserMessages.NO_NETWORK_FOR_CALL, noNetworkProblem(stage, hasNetwork = false))
            assertNull("$stage", noNetworkProblem(stage, hasNetwork = true))
        }
        assertNull("a practice dials nothing", noNetworkProblem(Stage.L0, hasNetwork = false))
    }

    // --- callSessionConfig (what RotepApp.launch passes to CallSession) ---

    @Test
    fun inACallTheMicrophoneIsStoppedUnlessTheSwitchSaysNot() {
        for (stage in listOf(Stage.L2, Stage.L3)) {
            val stopped = callSessionConfig(stage, Settings(audio = AudioChoice.SHELL_DIRECT, halfDuplex = true))
            assertTrue("$stage", stopped.muteMicDuringAi)
            assertTrue("$stage", stopped.handOverKeepsCall)
            assertFalse("$stage: halfDuplex is for a practice only", stopped.halfDuplex)
            // RunningCall.aiNeedsMic is the opposite of this: the call screen locks the microphone then
            assertFalse("$stage", callSessionConfig(stage, Settings(audio = AudioChoice.SHELL_DIRECT, keepMicOn = true)).muteMicDuringAi)
        }
    }

    @Test
    fun withoutACallNothingIsMutedTheSessionEndsAtHandOverAndHalfDuplexFollowsTheSetting() {
        val off = callSessionConfig(Stage.L0, Settings(audio = AudioChoice.LOCAL, halfDuplex = false, dualBrain = false))
        assertFalse(off.muteMicDuringAi)
        assertFalse(off.handOverKeepsCall)
        assertFalse(off.halfDuplex)
        assertTrue(callSessionConfig(Stage.L0, Settings(audio = AudioChoice.LOCAL, halfDuplex = true)).halfDuplex)
    }

    @Test
    fun aCallAtAChosenTimeDoesNotHandOverAndTheFlowsToDayAreUntouched() {
        val scheduled = callSessionConfig(Stage.L3, Settings(audio = AudioChoice.SHELL_DIRECT), unattended = true)
        assertFalse(scheduled.handOverKeepsCall)
        assertTrue(scheduled.unattended)
        assertTrue(scheduled.muteMicDuringAi)
        for (stage in Stage.entries) {
            val today = callSessionConfig(stage, Settings(audio = AudioChoice.SHELL_DIRECT))
            assertFalse(stage.name, today.unattended)
        }
    }
}
