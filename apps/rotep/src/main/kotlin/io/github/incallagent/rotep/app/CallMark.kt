// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.content.Context
import androidx.core.content.edit

/**
 * That a real call was placed and has not been seen to end. A process that dies during the call leaves
 * it behind, and the next time the app is opened the user hears that the result is not known. Holds the
 * shop's name and nothing else (the kept result holds that too): never a number, in the app's private
 * preferences. For a request from another AI, the ledger says "unknown" for the same call.
 */
internal class CallMark(private val memo: Memo) {
    interface Memo {
        var shop: String?
    }

    /** This process placed a call, so from now on the mark can only be its own: never one left behind. */
    @Volatile
    private var placedHere = false

    /** Just before the call is dialed. */
    fun placed(shop: String) {
        placedHere = true
        memo.shop = shop
    }

    fun ended() {
        memo.shop = null
    }

    /**
     * What to tell the user about a call left behind, which is then forgotten; null: there is none.
     * A call of this process shows as over a moment before [ended], on another thread, and the screen
     * can come back in that moment (the phone's in-call screen closes as the call ends).
     */
    fun takeLeftBehind(): String? = memo.shop?.takeUnless { placedHere }?.let {
        memo.shop = null
        "「$it」への電話の途中でアプリが終わったので、結果が分かりません。お店に確かめてください"
    }

    companion object {
        private const val PREFS = "call_mark"
        private const val KEY = "shop"

        fun of(context: Context): CallMark {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return CallMark(
                object : Memo {
                    override var shop: String?
                        get() = prefs.getString(KEY, null)

                        // commit: on the disk before the call is dialed
                        set(value) = prefs.edit(commit = true) { if (value != null) putString(KEY, value) else remove(KEY) }
                },
            )
        }
    }
}
