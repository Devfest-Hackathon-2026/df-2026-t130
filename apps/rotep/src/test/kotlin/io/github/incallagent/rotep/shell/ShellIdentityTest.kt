// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellIdentityTest {
    @Test
    fun theShellUserHasThePackageComAndroidShell() {
        assertEquals("com.android.shell", ShellIdentity.packageFor(2000))
    }

    @Test
    fun otherUidsHaveNoPackageToUse() {
        assertNull(ShellIdentity.packageFor(0))
        assertNull(ShellIdentity.packageFor(1000))
        assertNull(ShellIdentity.packageFor(10123))
    }

    @Test
    fun onlyThePairShellPackageAndShellUidIsAccepted() {
        assertTrue(ShellIdentity.isShell("com.android.shell", 2000))
        // The app's own package with the shell uid is exactly the mistake that must be caught.
        assertFalse(ShellIdentity.isShell("io.github.incallagent.rotep", 2000))
        assertFalse(ShellIdentity.isShell("com.android.shell", 10123))
        assertFalse(ShellIdentity.isShell(null, 2000))
        assertFalse(ShellIdentity.isShell("com.android.shell", null))
    }

    @Test
    fun callersAreOnlyTheAppAndTheHelperItself() {
        val appUid = 10231
        val ownUid = 2000
        assertTrue(CallerPolicy.isAllowed(appUid, appUid, ownUid))
        assertTrue(CallerPolicy.isAllowed(ownUid, appUid, ownUid))
        // Root was a caller only for the Shizuku server. It is not any more.
        assertFalse(CallerPolicy.isAllowed(0, appUid, ownUid))
        assertFalse(CallerPolicy.isAllowed(10232, appUid, ownUid))
        assertFalse(CallerPolicy.isAllowed(1000, appUid, ownUid))
        assertFalse(CallerPolicy.isAllowed(-1, appUid, ownUid))
    }

    @Test
    fun anUnknownAppUidNeverMatchesACaller() {
        assertFalse(CallerPolicy.isAllowed(callingUid = -1, appUid = -1, ownUid = 2000))
        assertFalse(CallerPolicy.isAllowed(callingUid = 10231, appUid = -1, ownUid = 2000))
        // The helper itself still may.
        assertTrue(CallerPolicy.isAllowed(callingUid = 2000, appUid = -1, ownUid = 2000))
    }

    @Test
    fun theDeviceRateComesFirstThenTheUsualOnesOnce() {
        assertEquals(listOf(48000, 44100, 32000, 24000, 16000, 8000), RateCandidates.order(48000))
        assertEquals(listOf(16000, 48000, 44100, 32000, 24000, 8000), RateCandidates.order(16000))
        assertEquals(listOf(48000, 44100, 32000, 24000, 16000, 8000), RateCandidates.order(null))
    }

    @Test
    fun anImplausibleDeviceRateIsIgnored() {
        val usual = listOf(48000, 44100, 32000, 24000, 16000, 8000)
        assertEquals(usual, RateCandidates.order(0))
        assertEquals(usual, RateCandidates.order(-48000))
        assertEquals(usual, RateCandidates.order(1000))
        assertEquals(usual, RateCandidates.order(999_999))
    }

    @Test
    fun anUnusualButPlausibleDeviceRateIsTriedFirst() {
        val order = RateCandidates.order(96000)
        assertEquals(96000, order.first())
        assertEquals(order.size, order.toSet().size)
    }
}
