// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.incallagent.rotep.call.LeftMic

/**
 * Receives the helper's binder from the shell process ([BinderSender]). Only the `register` method
 * is used; the usual ContentProvider data methods are never called.
 *
 * Two gates guard the handoff, both on the app side where they cannot be spoofed:
 *  - the caller's real uid (from [Binder.getCallingUid]) must be the shell or root ([RegistrationPolicy]);
 *  - the app version the helper saw when it started must match the installed app ([VersionGate],
 *    checked in [HelperClient.onHelperRegistered]).
 *
 * The pid and uid in the extras are informational (shown on the screen); they are never used for the
 * decision.
 *
 * The provider also starts the app's process when the helper reaches for it, so the helper can be
 * started before the app is opened, and can find the app again after the app process died.
 */
class HelperProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val c = context ?: return true
        (c.applicationContext as? Application)?.let { HelperClient.init(it) }
        // Once per process, before anything else: no call of ours can be going on yet. After a crash
        // the helper starts this process again within a moment, so the phone gets its microphone back
        runCatching { LeftMic.of(c).giveBackIfLeft() }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperProtocol.METHOD_REGISTER) return null
        val callingUid = Binder.getCallingUid()
        if (!RegistrationPolicy.accepts(callingUid)) {
            Log.w(TAG, "register refused: caller uid=$callingUid")
            return refused("呼び出し元が shell でも root でもない", retry = false)
        }
        // An unexpected exception here would travel back over Binder as an uncaught exception on a
        // binder thread of this app and could end the app. So it is caught and answered as a refusal.
        return try {
            // onCreate normally did this already; init is safe to repeat.
            (context?.applicationContext as? Application)?.let { HelperClient.init(it) }
            val serviceBinder = extras?.getBinder(HelperProtocol.KEY_SERVICE)
                ?: return refused("手伝い役の binder がない", retry = true)
            val version = extras.getInt(HelperProtocol.KEY_VERSION, -1)
            val pid = extras.getInt(HelperProtocol.KEY_PID, -1)
            val uid = extras.getInt(HelperProtocol.KEY_UID, -1)

            val reply = HelperClient.onHelperRegistered(serviceBinder, version, pid, uid)
            Bundle().apply {
                putBoolean(HelperProtocol.KEY_ACCEPTED, reply.accepted)
                if (reply.accepted) {
                    putBinder(HelperProtocol.KEY_CALLBACK, reply.callback)
                } else {
                    putString(HelperProtocol.KEY_REASON, reply.reason ?: "")
                    putBoolean(HelperProtocol.KEY_RETRY, reply.retry)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "register failed inside the app: ${ReportText.exceptionLine(t)}")
            refused("アプリの中で失敗した（${t.javaClass.simpleName}）", retry = true)
        }
    }

    private fun refused(reason: String, retry: Boolean) = Bundle().apply {
        putBoolean(HelperProtocol.KEY_ACCEPTED, false)
        putString(HelperProtocol.KEY_REASON, reason)
        putBoolean(HelperProtocol.KEY_RETRY, retry)
    }

    // Unused: this provider carries no data.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "RotepDiag"
    }
}
