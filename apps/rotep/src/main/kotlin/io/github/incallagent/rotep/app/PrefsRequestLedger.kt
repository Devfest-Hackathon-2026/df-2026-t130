// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.content.Context
import io.github.incallagent.rotep.request.PhoneRequestStore
import io.github.incallagent.rotep.request.RecentRequests

/** The last few requests' ids and statuses, nothing else. commit(): it must be on disk before a call is dialed. */
internal class PrefsRequestLedger(context: Context) : PhoneRequestStore.Ledger {
    private val prefs = context.getSharedPreferences("phone_requests", Context.MODE_PRIVATE)

    override fun save(id: String, status: PhoneRequestStore.Status): Boolean = prefs.edit()
        .putString(KEY_ID, id)
        .putString(KEY_STATUS, status.name)
        .putString(KEY_RECENT, RecentRequests.added(prefs.getString(KEY_RECENT, null), id, status))
        .commit()

    override fun find(id: String): PhoneRequestStore.Status? =
        RecentRequests.find(prefs.getString(KEY_RECENT, null), id) ?: load()?.takeIf { it.first == id }?.second

    override fun load(): Pair<String, PhoneRequestStore.Status>? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val status = prefs.getString(KEY_STATUS, null)?.let { saved -> PhoneRequestStore.Status.entries.find { it.name == saved } }
        return status?.let { id to it }
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_STATUS = "status"
        const val KEY_RECENT = "recent"
    }
}
