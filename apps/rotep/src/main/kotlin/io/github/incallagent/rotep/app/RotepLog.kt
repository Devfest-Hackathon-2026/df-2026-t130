// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.util.Log
import io.github.incallagent.audiobridge.BridgeLog

/**
 * Logcat for this app ("Rotep/<area>"). Callers pass states, reasons and counts only: never a
 * phone number, a name, what was said or the API key (ADR-021).
 */
object RotepLog {
    private const val TAG = "Rotep"

    fun i(area: String, message: String) {
        Log.i("$TAG/$area", message)
    }

    fun w(area: String, message: String) {
        Log.w("$TAG/$area", message)
    }

    val audio = BridgeLog { i("audio", it) }
}
