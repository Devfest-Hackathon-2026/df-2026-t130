// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.shell.RegistrationPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where a request from another AI comes in: `adb shell content call` runs as the shell user, and
 * only the shell (or root) gets past the real caller's uid from Binder. The request itself is
 * handled by [PhoneRequestHandler]. Nothing about a request is logged.
 */
class PhoneRequestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        if (!RegistrationPolicy.accepts(callingUid)) {
            Log.w(TAG, "request refused: caller uid=$callingUid")
            return reply(failure("not_allowed", "呼び出し元が shell ではありません"))
        }
        val app = context?.applicationContext ?: return reply(failure("not_ready", "アプリの準備ができていません"))
        // From here on it is the app's own work, not the shell's
        val identity = Binder.clearCallingIdentity()
        return try {
            reply(answer(app, method, arg))
        } catch (t: Throwable) {
            // Anything thrown here would travel back over Binder and could end the app. The type only:
            // a message might quote the request
            Log.w(TAG, "request failed inside the app: ${t.javaClass.simpleName}")
            reply(failure("internal", "アプリの中で失敗しました"))
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun answer(app: Context, method: String, arg: String?): String {
        RotepApp.init(app)
        return RotepApp.requestHandler.handle(method, arg)
    }

    private fun failure(error: String, message: String) = PhoneRequestCodec.encode(
        buildJsonObject {
            put("ok", false)
            put("error", error)
            put("message", message)
        },
    )

    private fun reply(encoded: String) = Bundle().apply { putString(PhoneRequestProtocol.KEY_RESULT, encoded) }

    // Unused: this provider carries no data.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "RotepRequest"
    }
}
