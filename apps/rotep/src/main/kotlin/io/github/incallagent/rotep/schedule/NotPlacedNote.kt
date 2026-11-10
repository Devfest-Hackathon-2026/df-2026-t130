// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import android.content.Context
import androidx.core.content.edit

/**
 * Why the last call at a chosen time was not placed, until the app is next opened: a notification
 * can be missed or swiped away. One sentence of the app's own, never a number or a name, in the
 * app's private preferences.
 */
internal class NotPlacedNote(private val memo: Memo) {
    interface Memo {
        var text: String?
    }

    fun write(reason: String) {
        memo.text = reason
    }

    /** The note, which is then gone. */
    fun take(): String? = memo.text?.also { memo.text = null }

    companion object {
        private const val PREFS = "schedule_note"
        private const val KEY = "not_placed"

        fun of(context: Context): NotPlacedNote {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return NotPlacedNote(
                object : Memo {
                    override var text: String?
                        get() = prefs.getString(KEY, null)
                        set(value) = prefs.edit { if (value != null) putString(KEY, value) else remove(KEY) }
                },
            )
        }
    }
}
