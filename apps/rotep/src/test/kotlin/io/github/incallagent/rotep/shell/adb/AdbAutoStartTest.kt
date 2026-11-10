// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAutoStartTest {
    @Test
    fun onlyAMissingWifiKeepsTheStartWaiting() {
        for (outcome in AdbStartOutcome.values()) {
            for (byUser in listOf(false, true)) {
                val waits = AdbStartPolicy.afterStart(outcome, byUser, triesSoFar = 0) == AdbAfterStart.WAIT_FOR_WIFI
                assertEquals("$outcome byUser=$byUser", outcome == AdbStartOutcome.NO_WIFI, waits)
            }
        }
    }

    @Test
    fun theButtonWithoutWifiKeepsWhatItsNoticePromises() {
        // The notice says the helper starts when Wi-Fi is there
        assertTrue(AdbStartPolicy.notice(AdbStartOutcome.NO_WIFI)!!.text.contains("Wi-Fi につながると"))
        assertEquals(AdbAfterStart.WAIT_FOR_WIFI, AdbStartPolicy.afterStart(AdbStartOutcome.NO_WIFI, byUser = true, triesSoFar = 3))
    }

    @Test
    fun aStartThatMayWorkALittleLaterIsTriedAgainByItself() {
        for (outcome in listOf(AdbStartOutcome.NO_PORT, AdbStartOutcome.FAILED)) {
            assertEquals(outcome.name, AdbAfterStart.TRY_AGAIN, AdbStartPolicy.afterStart(outcome, byUser = false, triesSoFar = 0))
        }
    }

    @Test
    fun theTriesAgainComeToAnEnd() {
        val tries = AdbStartPolicy.RETRY_DELAYS_MS.size
        assertEquals(AdbAfterStart.TRY_AGAIN, AdbStartPolicy.afterStart(AdbStartOutcome.NO_PORT, byUser = false, triesSoFar = tries - 1))
        assertEquals(AdbAfterStart.DONE, AdbStartPolicy.afterStart(AdbStartOutcome.NO_PORT, byUser = false, triesSoFar = tries))
    }

    @Test
    fun whatNeedsAPersonOrIsDoneIsNotTriedAgain() {
        val done = listOf(
            AdbStartOutcome.STARTED,
            AdbStartOutcome.ALREADY_RUNNING,
            AdbStartOutcome.AUTO_OFF,
            AdbStartOutcome.NOT_PAIRED,
            AdbStartOutcome.NO_PERMISSION,
            AdbStartOutcome.NETWORK_NOT_ALLOWED,
        )
        for (outcome in done) assertEquals(outcome.name, AdbAfterStart.DONE, AdbStartPolicy.afterStart(outcome, byUser = false, triesSoFar = 0))
    }

    @Test
    fun theButtonIsNotTriedAgainBehindThePersonsBack() {
        assertEquals(AdbAfterStart.DONE, AdbStartPolicy.afterStart(AdbStartOutcome.NO_PORT, byUser = true, triesSoFar = 0))
    }

    private fun restarts(
        automatic: Boolean = true,
        debuggingStillOn: Boolean = true,
        wifiThere: Boolean = true,
        sinceLastMs: Long? = null,
    ) = AdbStartPolicy.restartsByItself(automatic, debuggingStillOn, wifiThere, sinceLastMs)

    @Test
    fun aHelperThatDiedWhileDebuggingIsOnIsBroughtBack() {
        assertTrue(restarts())
    }

    @Test
    fun aHelperLostWithTheWifiComesBackWithTheWifi() {
        assertTrue(restarts(debuggingStillOn = false, wifiThere = false))
    }

    @Test
    fun bothSwitchesOffOnAWifiThatIsThereIsThePersonsChoice() {
        assertFalse(restarts(debuggingStillOn = false, wifiThere = true))
    }

    @Test
    fun notBroughtBackWhenTheAutomaticStartDoesNotApply() {
        assertFalse(restarts(automatic = false))
    }

    @Test
    fun aHelperThatKeepsDyingIsNotRestartedOverAndOver() {
        assertFalse(restarts(sinceLastMs = AdbStartPolicy.RESTART_INTERVAL_MS - 1))
        assertTrue(restarts(sinceLastMs = AdbStartPolicy.RESTART_INTERVAL_MS))
    }

    @Test
    fun aHelperThatAlreadyRunsNeedsNoNotice() {
        assertNull(AdbStartPolicy.notice(AdbStartOutcome.ALREADY_RUNNING))
    }

    @Test
    fun everyOtherOutcomeSaysSomething() {
        for (outcome in AdbStartOutcome.values()) {
            if (outcome == AdbStartOutcome.ALREADY_RUNNING) continue
            val notice = AdbStartPolicy.notice(outcome)
            assertNotNull(outcome.name, notice)
            assertTrue(outcome.name, notice!!.title.isNotBlank() && notice.text.isNotBlank())
        }
    }

    @Test
    fun theStartButtonIsOfferedWhenPressingItCanHelp() {
        val canStartAgain = listOf(
            AdbStartOutcome.AUTO_OFF,
            AdbStartOutcome.NO_PERMISSION,
            AdbStartOutcome.NETWORK_NOT_ALLOWED,
            AdbStartOutcome.NO_PORT,
            AdbStartOutcome.FAILED,
        )
        for (outcome in canStartAgain) assertTrue(outcome.name, AdbStartPolicy.notice(outcome)!!.startAction)
        // Pairing has to be done again on the screen, and a helper that started needs nothing.
        assertFalse(AdbStartPolicy.notice(AdbStartOutcome.NOT_PAIRED)!!.startAction)
        assertFalse(AdbStartPolicy.notice(AdbStartOutcome.STARTED)!!.startAction)
    }

    @Test
    fun aStartedHelperIsNotAnnouncedForever() {
        assertNotNull(AdbStartPolicy.notice(AdbStartOutcome.STARTED)!!.timeoutMs)
    }

    @Test
    fun theUpdateAndGoneNoticesOfferTheStartButton() {
        assertTrue(AdbStartPolicy.UPDATED.startAction)
        assertTrue(AdbStartPolicy.HELPER_GONE.startAction)
    }

    @Test
    fun anyFailureOfAStartBecomesAnOutcome() {
        assertEquals(AdbStartOutcome.NOT_PAIRED, AdbStartPolicy.outcomeOf(AdbNotAuthorizedException("no")))
        assertEquals(AdbStartOutcome.FAILED, AdbStartPolicy.outcomeOf(java.io.IOException("no")))
        assertEquals(AdbStartOutcome.FAILED, AdbStartPolicy.outcomeOf(AdbProtocolException("no")))
        assertEquals(AdbStartOutcome.FAILED, AdbStartPolicy.outcomeOf(SecurityException("no")))
    }

    @Test
    fun theBudgetIsSharedByTheSteps() {
        var now = 1_000L
        val budget = AdbBudget({ now }, 45_000)
        assertEquals(15_000, budget.limit(15_000))
        now += 40_000
        assertEquals(5_000, budget.limit(15_000))
        now += 10_000
        assertEquals(0, budget.limit(15_000))
        assertEquals(0, budget.remainingMs())
    }

    @Test
    fun theFeatureSwitchIsWhereTheSubmissionCanCutTheAutomaticStart() {
        assertTrue(AdbFeatures.BOOT_START)
    }

    @Test
    fun theRevokeCommandIsFixedText() {
        assertEquals(
            "pm revoke io.github.incallagent.rotep android.permission.WRITE_SECURE_SETTINGS",
            HelperBoot.revokeSecureSettingsCommand(),
        )
    }

    @Test
    fun theGrantCommandIsFixedText() {
        assertEquals(
            "pm grant io.github.incallagent.rotep android.permission.WRITE_SECURE_SETTINGS",
            HelperBoot.grantSecureSettingsCommand(),
        )
    }
}
