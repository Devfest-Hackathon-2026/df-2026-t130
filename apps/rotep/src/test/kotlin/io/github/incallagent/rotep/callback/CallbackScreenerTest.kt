// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.callback

import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.app.Settings
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.history.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class CallbackScreenerTest {
    private val number = "0312345678"
    private val known = CallbackMatch("テスト食堂", "11月12日")

    /** Runs the screener with fakes and writes down what it did, in order. */
    private inner class Run(
        val settingOn: Boolean = true,
        val aiCall: Boolean = false,
        val found: CallbackMatch? = known,
        val enabled: Boolean = true,
        val call: () -> ScreenedCall = { ScreenedCall(incoming = true, number = number) },
        val letThrough: () -> Unit = {},
        val lookupFails: Boolean = false,
        val showFails: Boolean = false,
        val backgroundFails: Boolean = false,
        val runBackgroundLater: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val shown = mutableListOf<Pair<CallbackNotice, Boolean>>()
        var later: (() -> Unit)? = null

        init {
            val screener = CallbackScreener(
                settingOn = {
                    events += "setting"
                    settingOn
                },
                callRunning = { aiCall },
                lookup = {
                    events += "lookup"
                    if (lookupFails) throw IllegalStateException("no row for $number")
                    found
                },
                show = { notice, quiet ->
                    events += "show"
                    if (showFails) throw SecurityException("cannot show $number")
                    shown += notice to quiet
                },
                inBackground = { job ->
                    if (backgroundFails) throw OutOfMemoryError("no thread")
                    if (runBackgroundLater) later = job else job()
                },
                log = { logs += it },
                enabled = enabled,
            )
            screener.screen(
                readCall = call,
                letThrough = {
                    events += "through"
                    letThrough()
                },
            )
        }
    }

    @Test
    fun `the call is let through before anything is looked up, and once`() {
        val run = Run()
        assertEquals(listOf("through", "setting", "lookup", "show"), run.events)
    }

    @Test
    fun `no work is done on the calling thread`() {
        val run = Run(runBackgroundLater = true)
        assertEquals("the ring must not wait for the setting or the lookup", listOf("through"), run.events)
        run.later!!.invoke()
        assertEquals(listOf("through", "setting", "lookup", "show"), run.events)
    }

    @Test
    fun `a known number gets a notice that names the shop`() {
        val run = Run()
        assertEquals(1, run.shown.size)
        assertEquals("前に電話したテスト食堂からの着信かもしれません（11月12日の予約の件）", run.shown[0].first.text)
        assertFalse("no AI call is going on", run.shown[0].second)
    }

    @Test
    fun `while the AI is on a call the notice is quiet`() {
        assertTrue(Run(aiCall = true).shown.single().second)
    }

    @Test
    fun `an unknown number is let through and nothing is shown`() {
        val run = Run(found = null)
        assertEquals(listOf("through", "setting", "lookup"), run.events)
        assertTrue(run.shown.isEmpty())
    }

    @Test
    fun `with the setting off nothing is looked up`() {
        assertEquals(listOf("through", "setting"), Run(settingOn = false).events)
    }

    @Test
    fun `with the feature cut the call is only let through`() {
        assertEquals(listOf("through"), Run(enabled = false).events)
    }

    @Test
    fun `an outgoing call is let through and nothing is looked up`() {
        assertEquals(listOf("through"), Run(call = { ScreenedCall(incoming = false, number = number) }).events)
    }

    @Test
    fun `a withheld number is let through and nothing is looked up`() {
        assertEquals(listOf("through"), Run(call = { ScreenedCall(incoming = true, number = null) }).events)
        assertEquals(listOf("through"), Run(call = { ScreenedCall(incoming = true, number = " ") }).events)
    }

    @Test
    fun `a lookup that fails stops nothing`() {
        val run = Run(lookupFails = true)
        assertEquals(listOf("through", "setting", "lookup"), run.events)
        assertTrue(run.shown.isEmpty())
    }

    @Test
    fun `a notice that cannot be shown stops nothing`() {
        assertEquals(listOf("through", "setting", "lookup", "show"), Run(showFails = true).events)
    }

    @Test
    fun `the call is let through even when it cannot be read`() {
        val run = Run(call = { throw IllegalArgumentException("bad handle") })
        assertEquals(listOf("through"), run.events)
    }

    @Test
    fun `a failure to let the call through is not the end of the process`() {
        val run = Run(letThrough = { throw IllegalStateException("service gone") })
        assertEquals("through", run.events.first())
    }

    @Test
    fun `a thread that cannot be started stops nothing`() {
        assertEquals(listOf("through"), Run(backgroundFails = true).events)
    }

    @Test
    fun `the number is never logged, also when something fails`() {
        val runs = listOf(Run(), Run(found = null), Run(lookupFails = true), Run(showFails = true))
        val logs = runs.flatMap { it.logs }
        assertTrue("the check should leave a trace", logs.isNotEmpty())
        assertTrue(logs.toString(), logs.none { it.contains(number) || it.contains("テスト食堂") })
    }

    @Test
    fun `the words without a date leave the date out`() {
        val text = callbackNotice(CallbackMatch("テスト食堂", null)).text
        assertEquals("前に電話したテスト食堂からの着信かもしれません", text)
        assertEquals(text, callbackNotice(CallbackMatch("テスト食堂", "  ")).text)
    }

    @Test
    fun `the words without a shop name say the shop`() {
        assertEquals("前に電話したお店からの着信かもしれません（11月12日の予約の件）", callbackNotice(CallbackMatch(" ", "11月12日")).text)
    }

    @Test
    fun `the title names neither the shop nor the date`() {
        val title = callbackNotice(known).title
        assertFalse(title.contains("テスト食堂"))
        assertFalse(title.contains("11月12日"))
    }

    @Test
    fun `the setting is off until someone turns it on`() {
        assertFalse(Settings().notifyCallback)
    }

    @Test
    fun `turning the switch on asks for the role only when it is missing`() {
        assertEquals(CallbackSwitchStep.ASK_FOR_ROLE, callbackSwitchStep(turnOn = true, roleAvailable = true, roleHeld = false))
        assertEquals(CallbackSwitchStep.TURN_ON, callbackSwitchStep(turnOn = true, roleAvailable = true, roleHeld = true))
        assertEquals(CallbackSwitchStep.NOT_ON_THIS_PHONE, callbackSwitchStep(turnOn = true, roleAvailable = false, roleHeld = false))
    }

    @Test
    fun `turning the switch off never asks for anything`() {
        assertEquals(CallbackSwitchStep.TURN_OFF, callbackSwitchStep(turnOn = false, roleAvailable = true, roleHeld = true))
        assertEquals(CallbackSwitchStep.TURN_OFF, callbackSwitchStep(turnOn = false, roleAvailable = false, roleHeld = false))
    }

    @Test
    fun `a kept call gives the notice its shop and the day, and nothing that was said`() {
        val entry = Entry(
            id = "1",
            at = Instant.parse("2026-11-09T10:00:00Z"),
            shopName = "テスト食堂",
            phoneNumber = "0312345678",
            purpose = Purpose.NEW_BOOKING,
            bookedAt = LocalDateTime.of(2026, 11, 12, 19, 0),
            headline = "予約できました",
            summary = "要約",
            transcript = listOf(CallSession.Line(CallSession.Who.SHOP, "お店の言葉")),
        )
        assertEquals(CallbackMatch("テスト食堂", "11月12日"), callbackMatchOf(entry))
        assertEquals("no booking was made", CallbackMatch("テスト食堂", null), callbackMatchOf(entry.copy(bookedAt = null)))
    }

    @Test
    fun `a switch that is on says what keeps the notice from coming`() {
        assertNull(callbackProblem(on = false, roleHeld = false, canNotify = false))
        assertNull(callbackProblem(on = true, roleHeld = true, canNotify = true))
        assertNotNull(callbackProblem(on = true, roleHeld = false, canNotify = true))
        assertNotNull(callbackProblem(on = true, roleHeld = true, canNotify = false))
    }
}
