// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterFormatTest {
    @Test
    fun theBarIsFullAtZeroDbfsAndEmptyAtTheFloor() {
        assertEquals(1f, MeterFormat.barFraction(0f), 0f)
        assertEquals(0f, MeterFormat.barFraction(-80f), 0f)
        assertEquals(0.5f, MeterFormat.barFraction(-40f), 0.0001f)
    }

    @Test
    fun theBarNeverLeavesItsRange() {
        assertEquals(1f, MeterFormat.barFraction(6f), 0f)
        assertEquals(0f, MeterFormat.barFraction(-120f), 0f)
        assertEquals(0f, MeterFormat.barFraction(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun levelsAreWrittenWithOneDecimalWhateverTheLocale() {
        assertEquals("-12.3 dBFS", MeterFormat.dbText(-12.34f))
        assertEquals("0.0 dBFS", MeterFormat.dbText(0f))
        assertEquals("-90.3 dBFS", MeterFormat.dbText(-90.31f))
    }

    @Test
    fun digitalSilenceIsWrittenAsSilence() {
        assertEquals("無音（0）", MeterFormat.dbText(-120f))
    }

    @Test
    fun soundIsToldFromSilenceByTheRms() {
        assertTrue(MeterFormat.isSounding(-30f))
        assertTrue(MeterFormat.isSounding(-69f))
        assertFalse(MeterFormat.isSounding(-70f))
        assertFalse(MeterFormat.isSounding(-120f))
    }

    @Test
    fun elapsedTimeIsShownAgainstTheSixtySecondLimit() {
        assertEquals("0 / 60 秒", MeterFormat.elapsedText(0))
        assertEquals("12 / 60 秒", MeterFormat.elapsedText(12_500))
        assertEquals("60 / 60 秒", MeterFormat.elapsedText(60_000))
    }

    @Test
    fun elapsedTimeNeverPassesTheLimitOrGoesNegative() {
        assertEquals("60 / 60 秒", MeterFormat.elapsedText(75_000))
        assertEquals("0 / 60 秒", MeterFormat.elapsedText(-3_000))
    }

    @Test
    fun theLimitMatchesTheHelpersAutomaticStop() {
        // DownlinkMeter.MAX_MILLIS is internal to the helper; the screen counts down the same 60 s.
        assertEquals(60_000L, MeterFormat.LIMIT_MS)
    }

    @Test
    fun silenceOnTheLineIsNotAWarningUntilLevelsStopComing() {
        assertNull(MeterFormat.noDataText(0))
        assertNull(MeterFormat.noDataText(1_499))
        val warning = MeterFormat.noDataText(1_500)
        assertNotNull(warning)
        assertEquals("受話のデータが 1 秒以上来ていない", warning)
        assertEquals("受話のデータが 5 秒以上来ていない", MeterFormat.noDataText(5_200))
    }
}
