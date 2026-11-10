// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.service

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.github.incallagent.rotep.shell.adb.AdbSetup

/**
 * Sets up the watch on the helper whenever the app's process starts, also when the diagnostics screen
 * is never opened: a provider is created before anything else in the process runs. It carries no data
 * and is not exported.
 */
class AdbInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { AdbSetup.init(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
