// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import io.github.incallagent.rotep.practice.PracticePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DualBrainRulesTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val policy = PracticePolicy.approved(today, Instant.parse("2026-11-10T01:00:00Z"))
    private val saved = ApiKeyStore.Read.Key("saved-key")
    private val claude = ApiKeyStore.Read.Key("claude-key")
    private val notNow = ApiKeyStore.Read.NotNow("busy")
    private val gone = ApiKeyStore.Read.Unreadable("broken")

    private fun limited(seconds: Int) = policy.copy(conversation = policy.conversation.copy(maxCallSeconds = seconds))

    @Test
    fun `the thinking model is on unless someone turns it off`() {
        assertTrue(Settings().dualBrain)
    }

    @Test
    fun `without the Claude key a call cannot use the thinking model`() {
        assertEquals(UserMessages.CLAUDE_KEY_MISSING, dualBrainProblem(Settings(dualBrain = true, hasClaudeKey = false), null))
    }

    @Test
    fun `a key that could not be read says so in its own words`() {
        assertEquals("読めません", dualBrainProblem(Settings(dualBrain = true, hasClaudeKey = false), "読めません"))
    }

    @Test
    fun `with the Claude key there is no problem`() {
        assertNull(dualBrainProblem(Settings(dualBrain = true, hasClaudeKey = true), null))
    }

    @Test
    fun `with the switch off the Claude key does not matter`() {
        assertNull(dualBrainProblem(Settings(dualBrain = false, hasClaudeKey = false), "読めません"))
    }

    @Test
    fun `both keys are there`() {
        val keys = callKeys(saved, claude) as CallKeys.Ready
        assertEquals("saved-key", keys.gemini)
        assertEquals("claude-key", keys.claude)
    }

    @Test
    fun `with the switch off only the Gemini key is read`() {
        val keys = callKeys(saved, null) as CallKeys.Ready
        assertEquals("saved-key", keys.gemini)
        assertNull(keys.claude)
    }

    @Test
    fun `either key not readable just now means not now`() {
        assertEquals(CallKeys.NotNow, callKeys(notNow, claude))
        assertEquals(CallKeys.NotNow, callKeys(saved, notNow))
        assertEquals(CallKeys.NotNow, callKeys(notNow, null))
    }

    @Test
    fun `not now comes before a key that is gone`() {
        assertEquals(CallKeys.NotNow, callKeys(gone, notNow))
        assertEquals(CallKeys.NotNow, callKeys(notNow, gone))
    }

    @Test
    fun `a key that is gone says which one`() {
        assertEquals(CallKeys.GeminiGone, callKeys(gone, claude))
        assertEquals(CallKeys.GeminiGone, callKeys(ApiKeyStore.Read.None, claude))
        assertEquals(CallKeys.ClaudeGone, callKeys(saved, gone))
        assertEquals(CallKeys.ClaudeGone, callKeys(saved, ApiKeyStore.Read.None))
        assertEquals(CallKeys.GeminiGone, callKeys(gone, ApiKeyStore.Read.None))
    }

    @Test
    fun `the keys never come out of the text form`() {
        val shown = callKeys(saved, claude).toString()
        assertTrue(shown, "saved-key" !in shown && "claude-key" !in shown)
    }

    @Test
    fun `in a practice with the thinking model the microphone stops while the AI speaks`() {
        // The loudspeaker is next to the microphone: the ears would take the AI's own voice for the shop's
        assertTrue(callSessionConfig(RotepApp.Stage.L0, Settings(audio = AudioChoice.LOCAL, halfDuplex = false, dualBrain = true)).halfDuplex)
    }

    @Test
    fun `only with the thinking model is a report waited for after the other side hung up`() {
        assertEquals(6_000L, callSessionConfig(RotepApp.Stage.L2, Settings(audio = AudioChoice.SHELL_DIRECT, dualBrain = true)).lastReportWaitMs)
        assertEquals(0L, callSessionConfig(RotepApp.Stage.L2, Settings(audio = AudioChoice.SHELL_DIRECT, dualBrain = false)).lastReportWaitMs)
    }

    @Test
    fun `a real call is never half duplex`() {
        for (stage in listOf(RotepApp.Stage.L2, RotepApp.Stage.L3)) {
            assertFalse("$stage", callSessionConfig(stage, Settings(audio = AudioChoice.SHELL_DIRECT, halfDuplex = true, dualBrain = true)).halfDuplex)
        }
    }

    @Test
    fun `with the thinking model a call ends a minute before the ears do`() {
        assertEquals(540, policyForCall(limited(900), dualBrain = true).conversation.maxCallSeconds)
        assertEquals(540, policyForCall(limited(600), dualBrain = true).conversation.maxCallSeconds)
    }

    @Test
    fun `a shorter limit stays as it was`() {
        val short = limited(300)
        assertSame(short, policyForCall(short, dualBrain = true))
        assertEquals(540, policyForCall(limited(540), dualBrain = true).conversation.maxCallSeconds)
    }

    @Test
    fun `with the switch off the limit is as it was`() {
        val long = limited(900)
        assertSame(long, policyForCall(long, dualBrain = false))
    }

    @Test
    fun `a capped policy is otherwise the same`() {
        val long = limited(900)
        assertEquals(long.copy(conversation = long.conversation.copy(maxCallSeconds = 540)), policyForCall(long, dualBrain = true))
    }
}
