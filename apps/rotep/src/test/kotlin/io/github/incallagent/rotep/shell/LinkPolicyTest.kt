// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * What the helper does after a try to reach the app, and whose provider it counts as the app's.
 * The scene that matters: the app is removed and installed again, the new install has another uid,
 * and a helper that was started for the old install must end instead of reaching for the new app
 * every few seconds.
 */
class LinkPolicyTest {
    private fun callbackBinder(): IBinder = Proxy.newProxyInstance(
        IBinder::class.java.classLoader,
        arrayOf(IBinder::class.java),
    ) { _, _, _ -> null } as IBinder

    // --- whose provider is it ---------------------------------------------------------------------

    @Test
    fun aProviderWithTheUidOfTheAppIsTheApps() {
        assertEquals(ProviderOwner.THE_APP, ProviderOwnerPolicy.check(providerUid = 10231, appUid = 10231))
    }

    @Test
    fun aProviderWithAnotherUidIsAnotherApp() {
        // The helper still knows the uid of the install it was started for.
        assertEquals(ProviderOwner.ANOTHER_APP, ProviderOwnerPolicy.check(providerUid = 10240, appUid = 10231))
    }

    @Test
    fun anOwnerThatCannotBeReadSaysNothing() {
        assertEquals(ProviderOwner.UNKNOWN, ProviderOwnerPolicy.check(providerUid = null, appUid = 10231))
        assertEquals(ProviderOwner.UNKNOWN, ProviderOwnerPolicy.check(providerUid = -1, appUid = 10231))
        assertEquals(ProviderOwner.UNKNOWN, ProviderOwnerPolicy.check(providerUid = 10231, appUid = -1))
        assertEquals(ProviderOwner.UNKNOWN, ProviderOwnerPolicy.check(providerUid = -1, appUid = -1))
    }

    // --- the helper does not come back for another app's provider ----------------------------------

    @Test
    fun aProviderOfAnotherAppEndsTheHelperAndIsNotTriedAgain() {
        val handoff = ProviderOwnerPolicy.handoffFor(providerUid = 10240, appUid = 10231)

        assertNotNull(handoff)
        assertTrue(handoff is Handoff.AnotherApp)
        assertEquals(LinkStep.END, LinkPolicy.step(handoff!!))
    }

    @Test
    fun theAppsOwnProviderLetsTheCallGoOn() {
        assertNull(ProviderOwnerPolicy.handoffFor(providerUid = 10231, appUid = 10231))
    }

    @Test
    fun anOwnerThatCannotBeReadDoesNotEndTheHelper() {
        // Nothing is known about it, so the helper tries again later instead of ending on a guess.
        for ((providerUid, appUid) in listOf(null to 10231, -1 to 10231, 10231 to -1)) {
            val handoff = ProviderOwnerPolicy.handoffFor(providerUid, appUid)
            assertNotNull("providerUid=$providerUid appUid=$appUid", handoff)
            assertEquals(LinkStep.TRY_AGAIN, LinkPolicy.step(handoff!!))
        }
    }

    @Test
    fun theAnotherAppAnswerNamesBothUidsForTheLog() {
        val handoff = ProviderOwnerPolicy.handoffFor(providerUid = 10240, appUid = 10231) as Handoff.AnotherApp

        assertTrue(handoff.detail.contains("10240"))
        assertTrue(handoff.detail.contains("10231"))
    }

    // --- what each answer means for the loop ----------------------------------------------------------

    @Test
    fun anAcceptedHelperIsConnected() {
        assertEquals(LinkStep.CONNECTED, LinkPolicy.step(Handoff.Accepted(callbackBinder())))
    }

    @Test
    fun aRefusalForNowTriesAgain() {
        assertEquals(LinkStep.TRY_AGAIN, LinkPolicy.step(Handoff.Refused("アプリの準備ができていない", retry = true)))
    }

    @Test
    fun aRefusalForGoodEndsTheHelper() {
        assertEquals(LinkStep.END, LinkPolicy.step(Handoff.Refused("版が合わない", retry = false)))
    }

    @Test
    fun anAppThatCannotBeReachedIsTriedAgain() {
        // For example between the removal and the new install, when the provider is missing.
        assertEquals(LinkStep.TRY_AGAIN, LinkPolicy.step(Handoff.NotReached))
    }

    @Test
    fun onlyTheTwoFinalAnswersEndTheHelper() {
        val ends = listOf(
            Handoff.Accepted(callbackBinder()),
            Handoff.Refused("a", retry = true),
            Handoff.Refused("b", retry = false),
            Handoff.NotReached,
            Handoff.AnotherApp("provider uid=2, app uid=1"),
        ).filter { LinkPolicy.step(it) == LinkStep.END }

        assertEquals(2, ends.size)
        assertTrue(ends.any { it is Handoff.Refused && !it.retry })
        assertTrue(ends.any { it is Handoff.AnotherApp })
    }
}
