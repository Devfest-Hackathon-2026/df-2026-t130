// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.rotep.shell.AGENT_RATE_HZ
import io.github.incallagent.rotep.shell.AudioBridgeEvent
import io.github.incallagent.rotep.shell.AudioUplinkMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellAudioPathTest {
    @Test
    fun helperEventsBecomePathEvents() {
        assertEquals(AudioPathEvent.Started("x"), audioBridgeEvent(AudioBridgeEvent.STARTED, "x"))
        assertEquals(AudioPathEvent.Stopped("通話が終わった"), audioBridgeEvent(AudioBridgeEvent.STOPPED, "通話が終わった"))
        assertEquals(AudioPathEvent.Silenced, audioBridgeEvent(AudioBridgeEvent.SILENCED, "ignored"))
        assertEquals(AudioPathEvent.Failed("受話を取れなかった"), audioBridgeEvent(AudioBridgeEvent.FAILED, "受話を取れなかった"))
        assertEquals(AudioPathEvent.Failed("code 4"), audioBridgeEvent(AudioBridgeEvent.FAILED, ""))
        assertEquals(AudioPathEvent.Failed("code 99"), audioBridgeEvent(99, ""))
        assertEquals(AudioPathEvent.Failed("why"), audioBridgeEvent(99, "why"))
    }

    @Test
    fun theModeNumbersTheAppSendsAreTheOnesTheHelperExpects() {
        assertEquals(AudioUplinkMode.DIRECT, ShellMode.DIRECT.ordinal)
        assertEquals(AudioUplinkMode.REDIRECTION, ShellMode.REDIRECTION.ordinal)
        assertEquals(AudioUplinkMode.SPEAKER, ShellMode.SPEAKER.ordinal)
        assertEquals(3, ShellMode.entries.size)
    }

    @Test
    fun theAgentRateIsTheOneTheCallSessionUses() {
        assertEquals(CallSession.AGENT_RATE, AGENT_RATE_HZ)
    }
}
