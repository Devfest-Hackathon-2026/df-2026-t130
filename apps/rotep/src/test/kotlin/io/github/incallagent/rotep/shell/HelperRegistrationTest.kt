// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.os.IBinder
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The app's side of a registration: a helper whose process is already gone must be refused and must
 * never be shown as running. HelperClient itself needs a real Application, so the part that decides
 * is [linkToHelperDeath], a plain function that these tests call with a stand-in binder.
 */
class HelperRegistrationTest {
    /** A binder that records what linkToDeath was asked and then runs [onLink] (which may throw). */
    private class StandInBinder(private val onLink: () -> Unit) {
        var recipient: IBinder.DeathRecipient? = null
        var flags: Int? = null

        val binder: IBinder = Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java),
        ) { _, method, args ->
            if (method.name == "linkToDeath") {
                recipient = args!![0] as IBinder.DeathRecipient
                flags = args[1] as Int
                onLink()
            }
            null
        } as IBinder
    }

    @Test
    fun aHelperThatIsAliveIsLinkedAndNotRefused() {
        val stand = StandInBinder { }
        val recipient = IBinder.DeathRecipient { }

        assertNull(linkToHelperDeath(stand.binder, recipient))

        // The death of the helper reaches the app through exactly this recipient.
        assertSame(recipient, stand.recipient)
        assertEquals(0, stand.flags)
    }

    @Test
    fun aHelperWhoseProcessIsAlreadyGoneIsRefused() {
        // linkToDeath throws a RemoteException for a binder whose process has died.
        val stand = StandInBinder { throw RemoteException() }

        val reply = linkToHelperDeath(stand.binder, IBinder.DeathRecipient { })

        assertNotNull(reply)
        assertFalse(reply!!.accepted)
        assertNull(reply.callback)
        assertFalse(reply.reason.isNullOrBlank())
    }

    @Test
    fun theRefusalLetsTheHelperTryAgain() {
        val reply = linkToHelperDeath(StandInBinder { throw RemoteException() }.binder, IBinder.DeathRecipient { })

        // retry = false would tell a helper to end for good. This one is gone anyway, and a helper that
        // is started again must be taken as usual.
        assertTrue(reply!!.retry)
    }

    @Test
    fun aFailureThatIsNotARemoteExceptionIsNotHidden() {
        val stand = StandInBinder { throw IllegalStateException("boom") }

        try {
            linkToHelperDeath(stand.binder, IBinder.DeathRecipient { })
            fail("the exception must go up to the provider, which answers it with a refusal")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }
}
