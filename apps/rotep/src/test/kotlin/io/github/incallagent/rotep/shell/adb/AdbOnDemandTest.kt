// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import io.github.incallagent.rotep.app.UserMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AdbOnDemandTest {
    private val log = mutableListOf<String>()

    private inner class Switches(usb: Int = 0, wifi: Int = 0) : DebugSwitches {
        var usbNow = usb
        var wifiNow = wifi
        var refusing = false

        override var usb: Int
            get() = usbNow
            set(value) {
                if (refusing) throw SecurityException("no permission")
                log += "usb=$value"
                usbNow = value
            }

        override var wifi: Int
            get() = wifiNow
            set(value) {
                if (refusing) throw SecurityException("no permission")
                log += "wifi=$value"
                wifiNow = value
            }
    }

    private inner class Markers : OnDemandMarkerStore {
        var marker: OnDemandMarker? = null
        var refusing = false

        override fun read() = marker

        override fun write(marker: OnDemandMarker) {
            if (refusing) throw IOException("disk full")
            log += "marker"
            this.marker = marker
        }

        override fun clear() {
            log += "cleared"
            marker = null
        }
    }

    private inner class Helper : OnDemandHelper {
        var isReady = false
        var going = false
        var outcome = AdbStartOutcome.STARTED
        var startsReady = true
        var duringStart: () -> Unit = {}
        var stops = 0
        var starts = 0
        var permissions = 0

        override val ready get() = isReady

        override val stopping get() = going

        override fun start(allowCalls: Boolean, budget: AdbBudget, stopped: () -> Boolean): AdbStartOutcome {
            starts++
            log += "start"
            duringStart()
            if (outcome == AdbStartOutcome.STARTED && startsReady) isReady = true
            return outcome
        }

        override fun allowCalls(): AdbStartOutcome {
            permissions++
            return AdbStartOutcome.ALREADY_RUNNING
        }

        override fun stopAndWait() {
            stops++
            log += "stop"
            isReady = false
        }
    }

    private var clock = 1_000L
    private val switches = Switches()
    private val markers = Markers()
    private val helper = Helper()
    private var slept = 0
    private val onDemand = AdbOnDemand(switches, markers, helper, { clock }, {
        slept++
        helper.going = false
    })
    private val budget = AdbBudget({ clock }, 30_000)

    private fun prepare(stopped: () -> Boolean = { false }, allowCalls: Boolean = true) = onDemand.prepare(allowCalls, budget, stopped)

    @Test
    fun `the marker is written before any switch and the order is usb wifi start`() {
        assertEquals(AdbStartOutcome.STARTED, prepare())
        assertEquals(listOf("marker", "usb=1", "wifi=1", "start", "wifi=0", "marker"), log)
    }

    @Test
    fun `a helper that is starting is already marked as the app's own`() {
        // If the app ends during the start, what comes up is stopped by the next look at the marker
        var markedAtStart: OnDemandMarker? = null
        helper.duringStart = { markedAtStart = markers.marker }
        prepare()
        assertTrue(markedAtStart!!.startedHelper)
    }

    @Test
    fun `during the call only USB debugging is on`() {
        prepare()
        assertEquals(1, switches.usbNow)
        assertEquals(0, switches.wifiNow)
    }

    @Test
    fun `after the call the helper stops first and USB debugging goes back`() {
        prepare()
        log.clear()
        assertTrue(onDemand.finish())
        assertEquals(listOf("stop", "usb=0", "cleared"), log)
        assertEquals(0, switches.usbNow)
        assertNull(markers.marker)
    }

    @Test
    fun `what the person had on stays on`() {
        val mine = Switches(usb = 1, wifi = 1)
        val own = AdbOnDemand(mine, markers, helper, { clock })
        own.prepare(true, budget) { false }
        assertTrue(own.finish())
        assertEquals(1, mine.usbNow)
        assertEquals(1, mine.wifiNow)
    }

    @Test
    fun `a marker left from before gives the original values`() {
        // The app died with USB debugging on: the 1 there now is the app's, not the person's
        markers.marker = OnDemandMarker(0, 0, 1, 1, startedHelper = false, atMs = 5)
        switches.usbNow = 1
        prepare()
        assertEquals(0, markers.marker!!.originalUsb)
        onDemand.finish()
        assertEquals(0, switches.usbNow)
    }

    @Test
    fun `a switch the person changed during the call is left alone`() {
        prepare()
        switches.usbNow = 0
        switches.wifiNow = 1
        log.clear()
        assertTrue(onDemand.finish())
        assertEquals(listOf("stop", "cleared"), log)
        assertEquals(1, switches.wifiNow)
    }

    @Test
    fun `a helper the person started is not stopped`() {
        helper.isReady = true
        assertEquals(AdbStartOutcome.ALREADY_RUNNING, prepare(allowCalls = false))
        assertTrue(log.isEmpty())
        assertTrue(onDemand.finish())
        assertEquals(0, helper.stops)
    }

    @Test
    fun `with a helper already there only the permission is set for an automatic call`() {
        helper.isReady = true
        prepare()
        assertEquals(1, helper.permissions)
        assertEquals(0, helper.starts)
    }

    @Test
    fun `a helper on its way out is waited for and not counted as running`() {
        helper.isReady = false
        helper.going = true
        assertEquals(AdbStartOutcome.STARTED, prepare())
        assertTrue(slept > 0)
        assertEquals(1, helper.starts)
    }

    @Test
    fun `every failure puts the switches back and leaves no marker`() {
        val failures = AdbStartOutcome.entries - AdbStartOutcome.STARTED - AdbStartOutcome.ALREADY_RUNNING
        for (failure in failures) {
            switches.usbNow = 0
            switches.wifiNow = 0
            helper.outcome = failure
            assertEquals(failure, prepare())
            assertEquals(failure.name, 0, switches.usbNow)
            assertEquals(failure.name, 0, switches.wifiNow)
            assertNull(failure.name, markers.marker)
        }
    }

    @Test
    fun `the phone refusing the switch is told as no permission`() {
        switches.refusing = true
        assertEquals(AdbStartOutcome.NO_PERMISSION, prepare())
        assertEquals(0, helper.starts)
    }

    @Test
    fun `started without the audio path is not a success`() {
        helper.startsReady = false
        assertEquals(AdbStartOutcome.FAILED, prepare())
        assertEquals(0, switches.usbNow)
    }

    @Test
    fun `giving up during the start puts everything back`() {
        var gaveUp = false
        helper.outcome = AdbStartOutcome.FAILED
        helper.duringStart = { gaveUp = true }
        assertEquals(AdbStartOutcome.FAILED, prepare({ gaveUp }))
        assertEquals(0, switches.usbNow)
        assertNull(markers.marker)
    }

    @Test
    fun `a job that only begins after giving up changes nothing`() {
        assertEquals(AdbStartOutcome.FAILED, prepare({ true }))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a job that begins after the time is up changes nothing`() {
        clock += 30_000
        assertEquals(AdbStartOutcome.FAILED, prepare())
        assertTrue(log.isEmpty())
    }

    @Test
    fun `no marker on disk means no switch is touched`() {
        markers.refusing = true
        assertEquals(AdbStartOutcome.FAILED, prepare())
        assertEquals(0, helper.starts)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a write that fails afterwards keeps the marker for the next chance`() {
        prepare()
        switches.refusing = true
        assertFalse(onDemand.finish())
        assertNotNull(markers.marker)
        assertTrue(onDemand.leftOn)
    }

    @Test
    fun `the helper going away after a call is expected`() {
        prepare()
        assertFalse(onDemand.expectedStop)
        onDemand.finish()
        assertTrue(onDemand.expectedStop)
    }

    @Test
    fun `a helper that comes up later is not expected to go`() {
        prepare()
        onDemand.finish()
        onDemand.helperIsRunning()
        assertFalse(onDemand.expectedStop)
    }

    @Test
    fun `the next call expects its helper to stay`() {
        prepare()
        onDemand.finish()
        prepare()
        assertFalse(onDemand.expectedStop)
    }

    @Test
    fun `nothing to do when nothing was changed`() {
        assertTrue(onDemand.finish())
        assertEquals(OnDemandRestore.NOTHING_LEFT, onDemand.restoreIfLeftOn(PhoneCallState.NONE))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `after the app died the switches go back when no call goes on`() {
        markers.marker = OnDemandMarker(0, 0, 1, 1, startedHelper = true, atMs = clock)
        switches.usbNow = 1
        switches.wifiNow = 1
        assertEquals(OnDemandRestore.PUT_BACK, onDemand.restoreIfLeftOn(PhoneCallState.NONE))
        assertEquals(0, switches.usbNow)
        assertEquals(0, switches.wifiNow)
        assertEquals(1, helper.stops)
    }

    @Test
    fun `during a call nothing is put back`() {
        markers.marker = OnDemandMarker(0, 0, 1, 1, startedHelper = true, atMs = clock)
        switches.usbNow = 1
        assertEquals(OnDemandRestore.LATER, onDemand.restoreIfLeftOn(PhoneCallState.ON))
        assertEquals(1, switches.usbNow)
        assertEquals(0, helper.stops)
    }

    @Test
    fun `when the app may not ask it waits until any call must be over`() {
        markers.marker = OnDemandMarker(0, 0, 1, 1, startedHelper = false, atMs = clock)
        switches.usbNow = 1
        assertEquals(OnDemandRestore.LATER, onDemand.restoreIfLeftOn(PhoneCallState.UNKNOWN))
        clock += 31 * 60 * 1000L
        assertEquals(OnDemandRestore.PUT_BACK, onDemand.restoreIfLeftOn(PhoneCallState.UNKNOWN))
        assertEquals(0, switches.usbNow)
    }

    @Test
    fun `the right to change the switches stays while either feature needs it`() {
        assertTrue(keepsSecureSettings(autoStart = true, onDemand = false))
        assertTrue(keepsSecureSettings(autoStart = false, onDemand = true))
        assertTrue(keepsSecureSettings(autoStart = true, onDemand = true))
        assertFalse(keepsSecureSettings(autoStart = false, onDemand = false))
    }

    @Test
    fun `with the switch on a helper that is not running is not a problem`() {
        assertNull(helperProblemForCall(onDemand = true, setupProblem = null, helperThere = false))
        assertEquals("ペアリング", helperProblemForCall(onDemand = true, setupProblem = "ペアリング", helperThere = false))
    }

    @Test
    fun `with the switch off nothing changes`() {
        assertEquals(UserMessages.HELPER_NOT_RUNNING, helperProblemForCall(onDemand = false, setupProblem = null, helperThere = false))
        assertNull(helperProblemForCall(onDemand = false, setupProblem = "ペアリング", helperThere = true))
    }

    @Test
    fun `every way the start can end has words another AI can pass on`() {
        for (outcome in AdbStartOutcome.entries) {
            val problem = AdbStartPolicy.onDemandProblem(outcome)
            if (outcome == AdbStartOutcome.STARTED || outcome == AdbStartOutcome.ALREADY_RUNNING) {
                assertNull(outcome.name, problem)
            } else {
                assertTrue(outcome.name, problem!!.contains("電話をかけませんでした"))
            }
        }
        assertEquals("Wi-Fi につながっていなかったので、電話をかけませんでした", AdbStartPolicy.onDemandProblem(AdbStartOutcome.NO_WIFI))
    }

    @Test
    fun `only a lost key sends the person back to pairing`() {
        for (outcome in AdbStartOutcome.entries) {
            val problem = AdbStartPolicy.onDemandProblem(outcome) ?: continue
            assertEquals(outcome.name, outcome == AdbStartOutcome.NOT_PAIRED, problem.contains("ペアリング"))
        }
    }

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the marker survives on disk and can be replaced`() {
        val file = OnDemandMarkerFile(folder.root)
        assertNull(file.read())
        val first = OnDemandMarker(0, 1, 1, 1, startedHelper = false, atMs = 1_794_000_000_000)
        file.write(first)
        assertEquals(first, OnDemandMarkerFile(folder.root).read())
        val second = first.copy(startedHelper = true, wroteWifi = 1)
        file.write(second)
        assertEquals(second, file.read())
        file.clear()
        assertNull(file.read())
    }

    @Test
    fun `something that is not a marker counts as none`() {
        assertNull(OnDemandMarker.parse(null))
        assertNull(OnDemandMarker.parse(""))
        assertNull(OnDemandMarker.parse("0,1,1"))
        assertNull(OnDemandMarker.parse("a,b,c,d,e,f"))
    }
}
