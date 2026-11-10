// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.callback

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import io.github.incallagent.rotep.app.RotepApp
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.app.SettingsStore
import io.github.incallagent.rotep.history.BookingHistory

/**
 * The earlier calls the notice looks in: 「予約の控え」 (ADR-045), which also makes the numbers
 * comparable. The process may have been started just for this call, so the history is set up here too.
 */
internal fun callbackLookup(context: Context): CallbackLookup = CallbackLookup { number ->
    BookingHistory.init(context)
    BookingHistory.findByNumber(number)?.let(::callbackMatchOf)
}

/**
 * Telecom shows this service each call once the user has made this app the phone's call-screening app.
 * It is how the app sees an incoming number without being the phone app, and without RotepInCallService
 * being on when no AI call is going on. It only ever lets the call through: it never blocks, silences or
 * hides a call (ADR-047).
 *
 * The app's process may have been started just for this call, so nothing here relies on the rest of
 * the app having been set up (RotepApp.init reads the API keys), and only the one setting is read.
 */
class CallbackScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val app = applicationContext
        CallbackScreener(
            settingOn = { SettingsStore.notifyCallback(app) },
            callRunning = { runCatching { RotepApp.callRunning() }.getOrDefault(false) },
            lookup = callbackLookup(app),
            show = { notice, quiet -> CallbackNotices.show(app, notice, quiet) },
            inBackground = { job -> Thread(job, "callback-check").apply { isDaemon = true }.start() },
            log = { RotepLog.i("callback", it) },
        ).screen(
            readCall = {
                ScreenedCall(
                    incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING,
                    // Null when the caller withheld the number: Telecom does not pass it on then
                    number = callDetails.handle?.schemeSpecificPart,
                )
            },
            // A response with nothing set: the call goes on as if this app were not there
            letThrough = { respondToCall(callDetails, CallResponse.Builder().build()) },
        )
    }
}

/** The role that shows this app the calls. Only the user can give it, and only in the phone's own dialog. */
internal object CallbackRole {
    private const val ROLE = RoleManager.ROLE_CALL_SCREENING

    fun available(context: Context): Boolean = runCatching { manager(context).isRoleAvailable(ROLE) }.getOrDefault(false)

    fun held(context: Context): Boolean = runCatching { manager(context).isRoleHeld(ROLE) }.getOrDefault(false)

    /** The phone's dialog that asks for the role, or null when it cannot be made here. */
    fun request(context: Context): Intent? = runCatching { manager(context).createRequestRoleIntent(ROLE) }.getOrNull()

    private fun manager(context: Context) = context.getSystemService(RoleManager::class.java)
}
