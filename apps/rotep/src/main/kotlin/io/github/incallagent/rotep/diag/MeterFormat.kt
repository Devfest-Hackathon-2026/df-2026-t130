// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.diag

import io.github.incallagent.rotep.shell.LevelMeter
import java.util.Locale

/** Texts and bar lengths for the level meter on the diagnostics screen. Pure Kotlin. */
object MeterFormat {
    /** The bar is empty at this level and full at 0 dBFS. */
    const val BAR_FLOOR_DBFS = -80f

    /** A window whose RMS is above this counts as "sound is coming". */
    const val SOUND_THRESHOLD_DBFS = -70f

    /** After this long without a level message the screen says that no data comes. */
    const val NO_DATA_AFTER_MS = 1500L

    /** The automatic stop of the meter. */
    const val LIMIT_MS = 60_000L

    /** 0.0 (empty) .. 1.0 (full) for the bar. */
    fun barFraction(dbfs: Float): Float = ((dbfs - BAR_FLOOR_DBFS) / -BAR_FLOOR_DBFS).coerceIn(0f, 1f)

    fun dbText(dbfs: Float): String =
        if (dbfs <= LevelMeter.FLOOR_DBFS) "無音（0）" else String.format(Locale.ROOT, "%.1f dBFS", dbfs)

    fun isSounding(rmsDbfs: Float): Boolean = rmsDbfs > SOUND_THRESHOLD_DBFS

    /** "12 / 60 秒", never past the limit. */
    fun elapsedText(elapsedMs: Long, limitMs: Long = LIMIT_MS): String {
        val shown = elapsedMs.coerceIn(0L, limitMs)
        return "${shown / 1000} / ${limitMs / 1000} 秒"
    }

    /** A warning text when levels stopped coming while the meter runs, else null. */
    fun noDataText(sinceLastLevelMs: Long): String? =
        if (sinceLastLevelMs >= NO_DATA_AFTER_MS) "受話のデータが ${sinceLastLevelMs / 1000} 秒以上来ていない" else null
}
