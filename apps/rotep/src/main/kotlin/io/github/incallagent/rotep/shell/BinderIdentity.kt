// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell

import android.os.Binder

/**
 * Runs [block] as the helper itself instead of as the app that is calling it over Binder.
 *
 * While a Binder call from the app is being handled, system code that looks at the calling uid
 * would see the app (an ordinary app uid) and not the helper's shell uid. Every call into the
 * system that starts from an AIDL method of the helper is wrapped with this.
 */
internal inline fun <T> asSelf(block: () -> T): T {
    val token = Binder.clearCallingIdentity()
    try {
        return block()
    } finally {
        Binder.restoreCallingIdentity(token)
    }
}

/** Runs [block] and returns null if it throws. For read-only questions to the system. */
internal inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (_: Throwable) {
    null
}
